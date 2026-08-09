package com.cfst.android.engine.model

import com.cfst.android.engine.ColoRegionMapper
import java.util.Locale

object ResultFormatter {

    fun formatCopyLines(records: List<ScanResult>): String =
        records.joinToString("\n") { formatCopyLine(it) }

fun formatCopyLine(record: ScanResult): String {
        val short = ColoRegionMapper.shortRegionName(record.regionCode)
        val latency = formatNumber(record.avgMs)
        val mbps = record.speed?.let { formatNumber(it * 8f) } ?: "0"
        return "${record.ip}#${short}优选[${latency}ms${mbps}mbps]"
    }

    private fun formatNumber(value: Float?): String {
        if (value == null) return "0"
        val s = String.format(Locale.US, "%.2f", value)
        return s.trimEnd('0').trimEnd('.')
    }
}