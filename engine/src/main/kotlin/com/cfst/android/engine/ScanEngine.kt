package com.cfst.android.engine

import com.cfst.android.engine.model.ScanResult

interface ScanEngine {

    val name: String

    suspend fun isAvailable(): Boolean

    suspend fun latencyScan(
        ips: List<String>,
        port: Int,
        probeCount: Int,
        pingCount: Int,
        latencyLimit: Float,
        concurrency: Int,
        pingTimeoutMs: Int,
        onProgress: (done: Int, total: Int) -> Unit,
    ): List<ScanResult>

    suspend fun speedScan(
        ips: List<String>,
        port: Int,
        url: String,
        downloadTime: Int,
        downloadCount: Int,
        speedLimit: Float,
        concurrency: Int,
        onProgress: (done: Int, total: Int) -> Unit,
    ): List<ScanResult>
}
