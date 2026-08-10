package com.cfst.android.engine

import com.cfst.android.engine.cfst.CfstBinary
import com.cfst.android.engine.model.ScanEvent
import com.cfst.android.engine.model.ScanRequest
import com.cfst.android.engine.model.ScanResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
import kotlin.math.ceil

class ScanController(
    private val engine: ScanEngine,
    private val regionResolver: suspend (String, Int) -> String?,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    val events: MutableSharedFlow<ScanEvent> = MutableSharedFlow(replay = 1, extraBufferCapacity = 256)

    private var scope: CoroutineScope? = null

    @Volatile
    private var epoch = 0

    fun start(req: ScanRequest) {
        val previous = scope
        val myEpoch = ++epoch
        previous?.cancel()
        events.resetReplayCache()
        val newScope = CoroutineScope(SupervisorJob() + dispatcher)
        scope = newScope
        newScope.launch {
            withTimeoutOrNull(2_000) { previous?.coroutineContext?.get(Job)?.join() }
            run(req, myEpoch)
        }
    }

    fun cancel() {
        scope?.cancel()
    }

    private suspend fun run(req: ScanRequest, myEpoch: Int) {
        try {
            emit(ScanEvent.PhaseChanged("生成IP列表"))
            val generatorMaxIps = if (req.fullScan) req.fullProbeCount else req.maxIps
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
                val finalTargets = if (targets.isEmpty()) {
                    events.tryEmit(ScanEvent.Log("地区解析失败，回退选择最快存活 IP"))
                    survivors.sortedBy { it.avgMs ?: Float.MAX_VALUE }.take(req.speedCount)
                } else {
                    targets
                }
                emit(ScanEvent.PhaseChanged("下载测速"))
                runSpeedPhase(req, finalTargets)
            } else {
                survivors
            }

            val sorted = finalRecords
                .filter { !req.speedEnabled || it.speed != null }
                .sortedBy { it.avgMs ?: Float.MAX_VALUE }
            emit(ScanEvent.ResultReady(sorted))
            emit(ScanEvent.PhaseChanged("完成"))
            emit(ScanEvent.Done)
        } catch (e: CancellationException) {
            if (epoch == myEpoch) {
                events.tryEmit(ScanEvent.Log("已取消"))
                events.tryEmit(ScanEvent.Cancelled)
                events.tryEmit(ScanEvent.Done)
            }
        } catch (e: Exception) {
            if (epoch == myEpoch) {
                events.tryEmit(ScanEvent.Log("错误: ${e.message}"))
                events.tryEmit(ScanEvent.Error(e.message ?: "未知错误"))
                events.tryEmit(ScanEvent.Done)
            }
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
                    pingTimeoutMs = req.pingTimeoutMs,
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
                pingTimeoutMs = req.pingTimeoutMs,
            ) { done, total ->
                reportProgress(phaseStart, completed + done, totalTasks, "延迟扫描")
            }
            completed += ips.size
            all.addAll(results)
        }
        return all.toList()
    }

    @Volatile
    private var throttledAtNs = 0L
    @Volatile
    private var lastEmittedPct = -1

    private fun reportProgress(startNs: Long, done: Int, total: Int, label: String) {
        if (done == 0 || total <= 0) return
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
        if (normalizeRegion(req.region) == "全部") {
            return survivors
                .sortedBy { it.avgMs ?: Float.MAX_VALUE }
                .take(req.speedCount)
        }
        val sorted = survivors.sortedBy { it.avgMs ?: Float.MAX_VALUE }
        val window = sorted.take(maxOf(req.speedCount * 2, 256))
        val resolved = Collections.synchronizedMap(mutableMapOf<String, ScanResult>())
        val concurrency = minOf(maxOf(1, req.pingConcurrency), 16)
        val limited = dispatcher.limitedParallelism(concurrency)
        val matched = AtomicInteger(0)
        coroutineScope {
            for (rec in window) {
                if (matched.get() >= req.speedCount) break
                launch(limited) {
                    currentCoroutineContext().ensureActive()
                    val code = rec.regionCode.takeIf { it.isNotBlank() }
                        ?: try {
                            regionResolver(rec.ip, rec.port)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            null
                        }
                    if (code != null && normalizeRegion(code) == normalizeRegion(req.region)) {
                        val candidate = rec.copy(
                            regionCode = code,
                            regionName = ColoRegionMapper.map(code),
                        )
                        synchronized(resolved) {
                            val existing = resolved[candidate.ip]
                            if (existing == null ||
                                (candidate.avgMs ?: Float.MAX_VALUE) < (existing.avgMs ?: Float.MAX_VALUE)
                            ) {
                                resolved[candidate.ip] = candidate
                                matched.incrementAndGet()
                            }
                        }
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
        val grouped = targets.groupBy { it.port }.toList()
        val result = Collections.synchronizedList(mutableListOf<ScanResult>())
        val groupIndex = AtomicInteger(0)
        val globalTotal = targets.size
        var globalDone = 0

        for ((port, group) in grouped) {
            val idx = groupIndex.incrementAndGet()
            emit(ScanEvent.Log("测速组 $idx/${grouped.size} 端口 $port (${group.size} 个 IP)"))
            val concurrency = maxOf(1, req.speedConcurrency)
            val batches = ceil(group.size / concurrency.toDouble()).toLong()
            // +5s per batch = SPEED_TIMEOUT_MARGIN_MS(5000)/1000, giving slow networks room per batch
            val groupTimeoutMs = maxOf(60_000L, batches * (req.downloadTime + 5) * 1000L + 30_000L)
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
                    val accDone = globalDone + done
                    if (accDone > 0 && globalTotal > 0) {
                        events.tryEmit(
                            ScanEvent.Progress(
                                pct = accDone * 100 / globalTotal,
                                text = "测速中 group $idx/${grouped.size} ($done/$total)",
                                etaMs = null,
                                done = accDone,
                                total = globalTotal,
                            )
                        )
                    }
                }
            }
            if (sped == null) {
                emit(ScanEvent.Log("测速组 $port 超时，跳过"))
            }
            val speedByIp = (sped ?: emptyList()).associateBy({ it.ip }, { it.speed })
            for (rec in group) {
                result.add(rec.copy(speed = speedByIp[rec.ip]))
            }
            globalDone += group.size
        }
        return result.toList()
    }

    private suspend fun emit(event: ScanEvent) {
        events.emit(event)
    }

    private fun normalizeRegion(value: String): String =
        value.trim().substringBefore(" (").trim().uppercase()
}
