package com.cfst.android.engine

import com.cfst.android.engine.cfst.CfstBinary
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
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

class ScanController(
    private val engine: ScanEngine,
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
        val totalTasks = req.ports.size * ips.size
        val phaseStart = System.nanoTime()
        var completed = 0
        val latencyLimit = req.latencyLimit

        if (req.multiPortBest) {
            val byIp = Collections.synchronizedMap(mutableMapOf<String, ScanResult>())
            for (port in req.ports) {
                val results = engine.latencyScan(
                    ips = ips,
                    port = port,
                    probeCount = req.probeCount,
                    pingCount = req.pingCount,
                    latencyLimit = latencyLimit,
                    concurrency = req.pingConcurrency,
                ) { done, total ->
                    reportProgress(phaseStart, completed + done, totalTasks, "延迟扫描")
                }
                completed += ips.size
                synchronized(byIp) {
                    for (r in results) {
                        val existing = byIp[r.ip]
                        if (existing == null || (r.avgMs ?: Float.MAX_VALUE) < (existing.avgMs ?: Float.MAX_VALUE)) {
                            byIp[r.ip] = r
                        }
                    }
                }
            }
            return byIp.values.toList()
        }

        val all = Collections.synchronizedList(mutableListOf<ScanResult>())
        for (port in req.ports) {
            val results = engine.latencyScan(
                ips = ips,
                port = port,
                probeCount = req.probeCount,
                pingCount = req.pingCount,
                latencyLimit = latencyLimit,
                concurrency = req.pingConcurrency,
            ) { done, total ->
                reportProgress(phaseStart, completed + done, totalTasks, "延迟扫描")
            }
            completed += ips.size
            all.addAll(results)
        }
        return all.toList()
    }

    private var throttledAtNs = 0L
    private var lastEmittedPct = -1

    private fun reportProgress(startNs: Long, done: Int, total: Int, label: String) {
        if (done == 0) return
        val now = System.nanoTime()
        val pct = done * 100 / total
        val mustEmit = done == total || pct != lastEmittedPct || now - throttledAtNs >= 200_000_000L
        if (!mustEmit) return
        throttledAtNs = now
        lastEmittedPct = pct
        val elapsedMs = (now - startNs) / 1_000_000
        val etaMs = if (done >= 3 && done > 0) elapsedMs * (total - done) / done else null
        events.tryEmit(
            ScanEvent.Progress(
                pct = pct,
                text = "$label $done/$total",
                etaMs = etaMs,
                done = done,
                total = total,
            )
        )
    }

    private suspend fun selectSpeedTargets(req: ScanRequest, survivors: List<ScanResult>): List<ScanResult> {
        if (req.region == "全部") {
            return survivors
                .sortedBy { it.avgMs ?: Float.MAX_VALUE }
                .take(req.speedCount)
        }
        val resolved = Collections.synchronizedMap(mutableMapOf<String, ScanResult>())
        coroutineScope {
            for (rec in survivors) {
                launch(dispatcher.limitedParallelism(req.pingConcurrency)) {
                    currentCoroutineContext().ensureActive()
                    val code = rec.regionCode.takeIf { it.isNotBlank() }
                        ?: runCatching { regionResolver(rec.ip, rec.port) }.getOrNull()
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
        val url = req.downloadUrl.ifBlank { CfstBinary.DEFAULT_SPEED_URL }
        val groupTimeoutMs = req.downloadCount.toLong() * req.downloadTime * 1000L + 30_000L
        val grouped = targets.groupBy { it.port }.toList()
        val result = Collections.synchronizedList(mutableListOf<ScanResult>())
        val groupIndex = AtomicInteger(0)

        for ((port, group) in grouped) {
            val idx = groupIndex.incrementAndGet()
            emit(ScanEvent.Log("测速组 $idx/${grouped.size} 端口 $port (${group.size} 个 IP)"))
            val sped = withTimeoutOrNull(groupTimeoutMs) {
                engine.speedScan(
                    ips = group.map { it.ip },
                    port = port,
                    url = url,
                    downloadTime = req.downloadTime,
                    downloadCount = req.downloadCount,
                    speedLimit = req.speedLimit,
                    concurrency = req.speedConcurrency,
                ) { done, total ->
                    events.tryEmit(
                        ScanEvent.Progress(
                            pct = idx * 100 / grouped.size,
                            text = "测速中 group $idx/${grouped.size} ($done/$total)",
                            etaMs = null,
                            done = done,
                            total = total,
                        )
                    )
                }
            }
            if (sped == null) {
                emit(ScanEvent.Log("测速组 $port 超时，跳过"))
            }
            val speedByIp = (sped ?: emptyList()).associateBy({ it.ip }, { it.speed })
            for (rec in group) {
                result.add(rec.copy(speed = speedByIp[rec.ip]))
            }
        }
        return result.toList()
    }

    private suspend fun emit(event: ScanEvent) {
        events.emit(event)
    }

    private fun regionCode(value: String): String =
        value.trim().substringBefore(" (").trim()
}
