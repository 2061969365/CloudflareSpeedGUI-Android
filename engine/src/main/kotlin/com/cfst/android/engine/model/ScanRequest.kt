package com.cfst.android.engine.model

enum class IpSource { OFFICIAL, CMIP, CUSTOM }

data class ScanRequest(
    val source: IpSource,
    val customLines: List<String>,
    val maxIps: Int,
    val fullScan: Boolean,
    val ports: List<Int>,
    val multiPortBest: Boolean,
    val speedEnabled: Boolean,
    val region: String,
    val speedCount: Int,
    val probeCount: Int,
    val fullProbeCount: Int,
    val latencyLimit: Float,
    val downloadTime: Int,
    val downloadCount: Int,
    val speedLimit: Float,
    val downloadUrl: String,
    val pingCount: Int,
    val pingTimeoutMs: Int,
    val pingConcurrency: Int,
    val speedConcurrency: Int,
)
