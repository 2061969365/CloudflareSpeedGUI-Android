package com.cfst.android.engine.model

sealed interface ScanEvent {
    data class PhaseChanged(val phase: String) : ScanEvent
    data class Progress(val pct: Int, val text: String, val etaMs: Long?) : ScanEvent
    data class Log(val line: String) : ScanEvent
    data class ResultReady(val results: List<ScanResult>) : ScanEvent
    data class Error(val message: String) : ScanEvent
    data object Done : ScanEvent
}
