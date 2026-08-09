package com.cfst.android.engine

import com.cfst.android.engine.model.ScanResult
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Lossless CSV codec for [ScanResult].
 *
 * Column order: [HEADER]. The six leading columns follow the desktop
 * CloudflareSpeedGUI export layout (`IP 地址,端口,平均延迟(ms),下载速度(MB/s),地区码,测试时间`);
 * the remaining columns (min/max latency, loss %, region name) are appended so that every
 * [ScanResult] field survives an encode -> parse round-trip intact.
 *
 * Number handling:
 * - Nullable float fields (avgMs, minMs, maxMs, speed) are written as an empty field when null
 *   and parsed back as null; when non-null they are written with 2 decimals (MB/s for speed,
 *   ms for latencies) and parsed back to the exact same Float value.
 * - lossPct is non-nullable, always written with 2 decimals; an empty field parses to 0f.
 *
 * testedAt is epoch millis. The 测试时间 column stores a human readable UTC timestamp
 * formatted `yyyy-MM-dd HH:mm:ss.SSS`, which parses back to the exact epoch millis
 * (sub-second precision preserved).
 */
object CsvCodec {

    private val TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")

    private const val HEADER =
        "IP 地址,端口,平均延迟(ms),下载速度(MB/s),地区码,测试时间,最小延迟(ms),最大延迟(ms),丢包率(%),地区名"

    fun encode(records: List<ScanResult>): String = buildString {
        append(HEADER)
        append('\n')
        for (r in records) {
            append(
                listOf(
                    r.ip,
                    r.port.toString(),
                    formatNumber(r.avgMs),
                    formatNumber(r.speed),
                    r.regionCode,
                    formatTime(r.testedAt),
                    formatNumber(r.minMs),
                    formatNumber(r.maxMs),
                    formatNumber(r.lossPct),
                    r.regionName,
                ).joinToString(",") { escape(it) },
            )
            append('\n')
        }
    }

    fun parse(text: String): List<ScanResult> {
        val lines = csvLines(text).filter { it.isNotBlank() }
        if (lines.isEmpty()) return emptyList()
        return lines.drop(1).map { line ->
            val f = splitCsvLine(line)
            ScanResult(
                ip = f.getOrElse(0) { "" },
                port = f.getOrElse(1) { "" }.toIntOrNull() ?: 0,
                avgMs = parseFloatOrNull(f.getOrElse(2) { "" }),
                speed = parseFloatOrNull(f.getOrElse(3) { "" }),
                regionCode = f.getOrElse(4) { "" },
                testedAt = parseTime(f.getOrElse(5) { "" }),
                minMs = parseFloatOrNull(f.getOrElse(6) { "" }),
                maxMs = parseFloatOrNull(f.getOrElse(7) { "" }),
                lossPct = parseFloatOrNull(f.getOrElse(8) { "" }) ?: 0f,
                regionName = f.getOrElse(9) { "" },
            )
        }
    }

    private fun formatNumber(v: Float?): String =
        v?.let { it.toString() } ?: ""

    private fun parseFloatOrNull(s: String): Float? =
        s.trim().takeIf { it.isNotEmpty() }?.toFloatOrNull()

    private fun formatTime(epochMillis: Long): String =
        Instant.ofEpochMilli(epochMillis).atZone(ZoneOffset.UTC).format(TIME_FORMAT)

    private fun parseTime(s: String): Long =
        LocalDateTime.parse(s.trim(), TIME_FORMAT).atZone(ZoneOffset.UTC).toInstant().toEpochMilli()

    private fun escape(field: String): String =
        if (field.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
            "\"" + field.replace("\"", "\"\"") + "\""
        } else {
            field
        }

    private fun splitCsvLine(line: String): List<String> {
        val fields = mutableListOf<String>()
        val sb = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                inQuotes -> when {
                    c == '"' && i + 1 < line.length && line[i + 1] == '"' -> {
                        sb.append('"')
                        i++
                    }
                    c == '"' -> inQuotes = false
                    else -> sb.append(c)
                }
                c == '"' -> inQuotes = true
                c == ',' -> {
                    fields.add(sb.toString())
                    sb.setLength(0)
                }
                else -> sb.append(c)
            }
            i++
        }
        fields.add(sb.toString())
        return fields
    }

    private fun csvLines(text: String): List<String> {
        val lines = mutableListOf<String>()
        val sb = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when (c) {
                '"' -> {
                    sb.append(c)
                    if (inQuotes && i + 1 < text.length && text[i + 1] == '"') {
                        sb.append('"')
                        i++
                    } else {
                        inQuotes = !inQuotes
                    }
                }
                '\n' -> if (inQuotes) {
                    sb.append(c)
                } else {
                    lines.add(sb.toString().trimEnd('\r'))
                    sb.setLength(0)
                }
                else -> sb.append(c)
            }
            i++
        }
        if (sb.isNotEmpty()) lines.add(sb.toString().trimEnd('\r'))
        return lines
    }
}
