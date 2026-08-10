package com.cfst.android.engine

import com.cfst.android.engine.model.ScanResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class FallbackEngine(
    private val primary: ScanEngine,
    private val fallback: ScanEngine,
) : ScanEngine {

    override val name: String
        get() = "fallback(${primary.name}/${fallback.name})"

    private val mutex = Mutex()

    @Volatile
    private var resolved: ScanEngine? = null

    suspend fun resolvedEngineName(): String = active().name

    private suspend fun active(): ScanEngine {
        resolved?.let { return it }
        return mutex.withLock {
            resolved?.let { return it }
            val chosen = runCatching { primary.isAvailable() }.getOrDefault(false)
                .let { if (it) primary else fallback }
            resolved = chosen
            chosen
        }
    }

    override suspend fun isAvailable(): Boolean = active().isAvailable()

    override suspend fun latencyScan(
        ips: List<String>,
        port: Int,
        probeCount: Int,
        pingCount: Int,
        latencyLimit: Float,
        concurrency: Int,
        pingTimeoutMs: Int,
        onProgress: (done: Int, total: Int) -> Unit,
    ): List<ScanResult> {
        val engine = active()
        return if (engine === primary) {
            try {
                primary.latencyScan(ips, port, probeCount, pingCount, latencyLimit, concurrency, pingTimeoutMs, onProgress)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                fallback.latencyScan(ips, port, probeCount, pingCount, latencyLimit, concurrency, pingTimeoutMs, onProgress)
            }
        } else {
            engine.latencyScan(ips, port, probeCount, pingCount, latencyLimit, concurrency, pingTimeoutMs, onProgress)
        }
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
        val engine = active()
        return if (engine === primary) {
            try {
                primary.speedScan(ips, port, url, downloadTime, downloadCount, speedLimit, concurrency, onProgress)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                fallback.speedScan(ips, port, url, downloadTime, downloadCount, speedLimit, concurrency, onProgress)
            }
        } else {
            engine.speedScan(ips, port, url, downloadTime, downloadCount, speedLimit, concurrency, onProgress)
        }
    }
}
