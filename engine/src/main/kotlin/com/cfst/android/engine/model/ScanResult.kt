package com.cfst.android.engine.model

data class ScanResult(
    val ip: String,
    val port: Int,
    val avgMs: Float?,
    val minMs: Float?,
    val maxMs: Float?,
    val lossPct: Float,
    val speed: Float?,
    val regionCode: String,
    val regionName: String,
    val testedAt: Long,
)
