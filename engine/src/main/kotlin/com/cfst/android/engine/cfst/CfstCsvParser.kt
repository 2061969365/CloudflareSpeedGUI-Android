package com.cfst.android.engine.cfst

import com.cfst.android.engine.ColoRegionMapper
import com.cfst.android.engine.model.ScanResult

object CfstCsvParser {

    fun parse(csvText: String): List<ScanResult> = parse(csvText, port = 0, testedAt = 0L)

    fun parse(csvText: String, port: Int, testedAt: Long): List<ScanResult> {
        val results = mutableListOf<ScanResult>()
        for (line in csvText.removePrefix("\uFEFF").lineSequence()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            if (trimmed.startsWith("IP")) continue
            val fields = trimmed.split(',')
            if (fields.size < 7) continue
            val regionCode = fields[6].trim().ifBlank { "" }
            results += ScanResult(
                ip = fields[0].trim(),
                port = port,
                avgMs = fields[4].trim().toFloatOrNull()?.takeIf { it > 0f },
                minMs = null,
                maxMs = null,
                lossPct = (fields[3].trim().toFloatOrNull() ?: 0f) * 100f,
                speed = fields[5].trim().toFloatOrNull(),
                regionCode = regionCode,
                regionName = ColoRegionMapper.map(regionCode),
                testedAt = testedAt,
            )
        }
        return results
    }
}
