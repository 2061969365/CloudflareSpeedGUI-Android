package com.cfst.android.engine

import com.cfst.android.engine.model.ScanResult

class FallbackEngine(
    private val primary: ScanEngine,
    private val fallback: ScanEngine,
) : ScanEngine {

    override val name: String
        get() = "fallback(${primary.name}/${fallback.name})"

    @Volatile
    private var resolved: ScanEngine? = null

    private suspend fun active(): ScanEngine {
        resolved?.let { return it }
        val chosen = if (primary.isAvailable()) primary else fallback
        resolved = chosen
        return chosen
    }

    override suspend fun isAvailable(): Boolean = active().isAvailable()

    override suspend fun latencyScan(
        ips: List<String>,
        port: Int,
        probeCount: Int,
        pingCount: Int,
        latencyLimit: Float,
        concurrency: Int,
        onProgress: (done: Int, total: Int) -> Unit,
    ): List<ScanResult> = active().latencyScan(
        ips, port, probeCount, pingCount, latencyLimit, concurrency, onProgress,
    )

    override suspend fun speedScan(
        ips: List<String>,
        port: Int,
        url: String,
        downloadTime: Int,
        downloadCount: Int,
        speedLimit: Float,
        concurrency: Int,
        onProgress: (done: Int, total: Int) -> Unit,
    ): List<ScanResult> = active().speedScan(
        ips, port, url, downloadTime, downloadCount, speedLimit, concurrency, onProgress,
    )
}
