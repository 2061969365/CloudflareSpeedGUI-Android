package com.cfst.android.engine

import com.cfst.android.engine.model.LatencyStats
import com.cfst.android.engine.model.ScanEvent
import com.cfst.android.engine.model.ScanRequest
import com.cfst.android.engine.model.ScanResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicInteger

class ScanController(
    private val latencyProbe: suspend (String, Int, Int, Int) -> LatencyStats,
    private val speedProbe: suspend (String, Int, String, Int, Float) -> Float?,
    private val regionResolver: suspend (String, Int) -> String?,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    val events: MutableSharedFlow<ScanEvent> = MutableSharedFlow(extraBufferCapacity = 256)

    private var scope: CoroutineScope? = null

    fun start(req: ScanRequest) {
        val previous = scope
        previous?.cancel()
        val newScope = CoroutineScope(SupervisorJob() + dispatcher)
        scope = newScope
        newScope.launch { run(req) }
    }

    fun cancel() {
        scope?.cancel()
    }

    private suspend fun run(req: ScanRequest) {
        try {
            emit(ScanEvent.PhaseChanged("生成IP列表"))
            val generatorMaxIps = if (req.fullScan) req.fullProbeCount else req.probeCount
            val ips = IpGenerator.generate(req.customLines, generatorMaxIps, req.fullScan) { pct ->
                events.tryEmit(ScanEvent.Progress(pct, "生成IP列表 $pct%", null))
            }
            emit(ScanEvent.Log("共生成 ${ips.size} 个待测 IP"))
            if (ips.isEmpty()) {
                emit(ScanEvent.Error("没有可用的 IP，请检查 IP 来源"))
                emit(ScanEvent.Done)
                return
            }

            emit(ScanEvent.PhaseChanged("延迟扫描"))
            val survivors = runLatencyPhase(req, ips)
            emit(ScanEvent.Log("延迟扫描完成: ${survivors.size} 个 IP 存活"))
            if (survivors.isEmpty()) {
                emit(ScanEvent.ResultReady(emptyList()))
                emit(ScanEvent.PhaseChanged("完成"))
                emit(ScanEvent.Done)
                return
            }

            val finalRecords = if (req.speedEnabled) {
                emit(ScanEvent.Log("选择下载测速目标 (地区=${req.region}, 数量=${req.speedCount})"))
                val targets = selectSpeedTargets(req, survivors)
                if (targets.isEmpty()) {
                    emptyList()
                } else {
                    emit(ScanEvent.PhaseChanged("下载测速"))
                    runSpeedPhase(req, targets)
                }
            } else {
                survivors
            }

            val sorted = finalRecords.sortedBy { it.avgMs ?: Float.MAX_VALUE }
            emit(ScanEvent.ResultReady(sorted))
            emit(ScanEvent.PhaseChanged("完成"))
            emit(ScanEvent.Done)
        } catch (e: CancellationException) {
            emit(ScanEvent.Log("已取消"))
            emit(ScanEvent.Error("已取消"))
            emit(ScanEvent.Done)
        } catch (e: Exception) {
            emit(ScanEvent.Log("错误: ${e.message}"))
            emit(ScanEvent.Error(e.message ?: "未知错误"))
            emit(ScanEvent.Done)
        }
    }

    private suspend fun runLatencyPhase(req: ScanRequest, ips: List<String>): List<ScanResult> {
        val completed = AtomicInteger(0)
        val elapsedMsSamples = mutableListOf<Long>()
        val totalTasks = req.ports.size * ips.size
        val latencyLimit = req.latencyLimit

        if (req.multiPortBest) {
            val byIp = java.util.Collections.synchronizedMap(mutableMapOf<String, ScanResult>())
            for (port in req.ports) {
                coroutineScope {
                    for (ip in ips) {
                        launch(dispatcher.limitedParallelism(req.pingConcurrency)) {
                            val result = probeOne(req, ip, port, completed, totalTasks, elapsedMsSamples, latencyLimit)
                            if (result != null) {
                                synchronized(byIp) {
                                    val existing = byIp[ip]
                                    if (existing == null || (result.avgMs ?: Float.MAX_VALUE) < (existing.avgMs ?: Float.MAX_VALUE)) {
                                        byIp[ip] = result
                                    }
                                }
                            }
                        }
                    }
                }
            }
            return byIp.values.toList()
        }

        val all = java.util.Collections.synchronizedList(mutableListOf<ScanResult>())
        coroutineScope {
            for (ip in ips) {
                for (port in req.ports) {
                    launch(dispatcher) {
                        val result = probeOne(req, ip, port, completed, totalTasks, elapsedMsSamples, latencyLimit)
                        if (result != null) {
                            all.add(result)
                        }
                    }
                }
            }
        }
        return all.toList()
    }

    private suspend fun probeOne(
        req: ScanRequest,
        ip: String,
        port: Int,
        completed: AtomicInteger,
        totalTasks: Int,
        elapsedMsSamples: MutableList<Long>,
        latencyLimit: Float,
    ): ScanResult? {
        currentCoroutineContext().ensureActive()
        val start = System.nanoTime()
        val stats: LatencyStats? = try {
            latencyProbe(ip, port, req.pingCount, req.pingTimeoutMs)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        synchronized(elapsedMsSamples) { elapsedMsSamples.add(elapsedMs) }
        val done = completed.incrementAndGet()
        val avgElapsed = elapsedMsSamples.takeLast(20).average().toLong()
        val etaMs = if (elapsedMsSamples.size >= 3) (totalTasks - done) * avgElapsed else null
        val ms = stats?.avgMs
        emit(
            ScanEvent.Progress(
                pct = done * 100 / totalTasks,
                text = if (ms != null) "延迟扫描 $done/$totalTasks (${ms.toInt()} ms)" else "延迟扫描 $done/$totalTasks",
                etaMs = etaMs,
            )
        )
        if (stats == null || stats.avgMs == null || stats.avgMs > latencyLimit) return null
        return ScanResult(
            ip = ip,
            port = port,
            avgMs = stats.avgMs,
            minMs = stats.minMs,
            maxMs = stats.maxMs,
            lossPct = stats.lossPct,
            speed = null,
            regionCode = "",
            regionName = "",
            testedAt = System.currentTimeMillis(),
        )
    }

    private suspend fun selectSpeedTargets(req: ScanRequest, survivors: List<ScanResult>): List<ScanResult> {
        if (req.region == "全部") {
            return survivors
                .sortedBy { it.avgMs ?: Float.MAX_VALUE }
                .take(req.speedCount)
        }
        val resolved = java.util.Collections.synchronizedMap(mutableMapOf<String, ScanResult>())
        coroutineScope {
            for (rec in survivors) {
                launch(dispatcher.limitedParallelism(req.pingConcurrency)) {
                    currentCoroutineContext().ensureActive()
                    val code = try {
                        regionResolver(rec.ip, rec.port)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        null
                    }
                    if (code != null && regionCode(code) == regionCode(req.region)) {
                        resolved[rec.ip] = rec.copy(
                            regionCode = code,
                            regionName = ColoRegionMapper.map(code),
                        )
                    }
                }
            }
        }
        return resolved.values
            .sortedBy { it.avgMs ?: Float.MAX_VALUE }
            .take(req.speedCount)
    }

    private suspend fun runSpeedPhase(req: ScanRequest, targets: List<ScanResult>): List<ScanResult> {
        val url = req.downloadUrl.ifBlank { DEFAULT_SPEED_URL }
        val groupTimeoutMs = req.downloadCount.toLong() * req.downloadTime * 1000L + 30_000L
        val grouped = targets.groupBy { it.port }.toList()
        val result = java.util.Collections.synchronizedList(mutableListOf<ScanResult>())
        val groupIndex = AtomicInteger(0)

        for ((port, group) in grouped) {
            val idx = groupIndex.incrementAndGet()
            emit(ScanEvent.Log("测速组 $idx/${grouped.size} 端口 $port (${group.size} 个 IP)"))
            val speedMap = java.util.Collections.synchronizedMap(mutableMapOf<String, Float?>())
            val completed = withTimeoutOrNull(groupTimeoutMs) {
                coroutineScope {
                    for (rec in group) {
                        launch(dispatcher.limitedParallelism(req.speedConcurrency)) {
                            currentCoroutineContext().ensureActive()
                            val speed = try {
                                speedProbe(rec.ip, rec.port, url, req.downloadTime, req.speedLimit)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (_: Exception) {
                                null
                            }
                            speedMap[rec.ip] = speed
                            emit(
                                ScanEvent.Progress(
                                    pct = idx * 100 / grouped.size,
                                    text = "测速中 group $idx/${grouped.size} (${formatSpeed(speed)})",
                                    etaMs = null,
                                )
                            )
                        }
                    }
                }
                true
            }
            if (completed == null) {
                emit(ScanEvent.Log("测速组 $port 超时，跳过"))
            }
            for (rec in group) {
                val speed = speedMap[rec.ip]
                result.add(rec.copy(speed = speed))
            }
        }
        return result.toList()
    }

    private fun formatSpeed(speed: Float?): String =
        if (speed == null) "失败" else "%.2f MB/s".format(speed)

    private suspend fun emit(event: ScanEvent) {
        events.emit(event)
    }

    private fun regionCode(value: String): String =
        value.trim().substringBefore(" (").trim()

    companion object {
        private val DEFAULT_SPEED_URL = "https://cf.xiu2.xyz/url"
    }
}
