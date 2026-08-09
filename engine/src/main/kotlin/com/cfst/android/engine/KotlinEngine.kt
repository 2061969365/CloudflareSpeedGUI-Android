package com.cfst.android.engine

import com.cfst.android.engine.model.LatencyStats
import com.cfst.android.engine.model.ScanResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

class KotlinEngine(
    private val latencyProbe: suspend (String, Int, Int, Int) -> LatencyStats,
    private val speedProbe: suspend (String, Int, String, Int, Float) -> Float?,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ScanEngine {

    override val name: String = "kotlin"

    override suspend fun isAvailable(): Boolean = true

    override suspend fun latencyScan(
        ips: List<String>,
        port: Int,
        probeCount: Int,
        pingCount: Int,
        latencyLimit: Float,
        concurrency: Int,
        onProgress: (done: Int, total: Int) -> Unit,
    ): List<ScanResult> {
        val completed = AtomicInteger(0)
        val total = ips.size
        val results = Collections.synchronizedList(mutableListOf<ScanResult>())
        coroutineScope {
            for (ip in ips) {
                launch(dispatcher.limitedParallelism(maxOf(1, concurrency))) {
                    currentCoroutineContext().ensureActive()
                    val stats = try {
                        latencyProbe(ip, port, pingCount, DEFAULT_PING_TIMEOUT_MS)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        null
                    }
                    onProgress(completed.incrementAndGet(), total)
                    if (stats?.avgMs != null && stats.avgMs <= latencyLimit) {
                        results.add(
                            ScanResult(
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
                        )
                    }
                }
            }
        }
        return results.toList()
    }

    override suspend fun speedScan(
        ips: List<String>,
        port: Int,
        url: String,
        downloadTime: Int,
        downloadCount: Int,
        speedLimit: Float,
        concurrency: Int,
        onProgress: (done: Int, total: Int) -> Unit,
    ): List<ScanResult> {
        val completed = AtomicInteger(0)
        val total = ips.size
        val speeds = Collections.synchronizedMap(mutableMapOf<String, Float?>())
        coroutineScope {
            for (ip in ips) {
                launch(dispatcher.limitedParallelism(maxOf(1, concurrency))) {
                    currentCoroutineContext().ensureActive()
                    val speed = try {
                        speedProbe(ip, port, url, downloadTime, speedLimit)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        null
                    }
                    speeds[ip] = speed
                    onProgress(completed.incrementAndGet(), total)
                }
            }
        }
        return ips.map { ip ->
            ScanResult(
                ip = ip,
                port = port,
                avgMs = null,
                minMs = null,
                maxMs = null,
                lossPct = 0f,
                speed = speeds[ip],
                regionCode = "",
                regionName = "",
                testedAt = System.currentTimeMillis(),
            )
        }
    }

    private companion object {
        const val DEFAULT_PING_TIMEOUT_MS = 2000
    }
}
