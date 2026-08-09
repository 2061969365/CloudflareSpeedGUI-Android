package com.cfst.android.engine.model

data class LatencyStats(
    val sent: Int,
    val received: Int,
    val lossPct: Float,
    val avgMs: Float?,
    val minMs: Float?,
    val maxMs: Float?,
)
