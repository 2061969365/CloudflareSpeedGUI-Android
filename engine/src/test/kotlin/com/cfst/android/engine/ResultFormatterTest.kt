package com.cfst.android.engine

import com.cfst.android.engine.model.ResultFormatter
import com.cfst.android.engine.model.ScanResult
import org.junit.Assert.assertEquals
import org.junit.Test

class ResultFormatterTest {

    private fun rec(
        ip: String = "172.67.162.190",
        regionCode: String = "HKG",
        avgMs: Float? = 19.21f,
        speed: Float? = 12.24f,
    ) = ScanResult(
        ip = ip,
        port = 443,
        avgMs = avgMs,
        minMs = null,
        maxMs = null,
        lossPct = 0f,
        speed = speed,
        regionCode = regionCode,
        regionName = "中国香港",
        testedAt = 0L,
    )

    @Test
    fun formatCopyLine_matches_user_format() {
        assertEquals("172.67.162.190#HK优选[19.21ms97.92mbps]", ResultFormatter.formatCopyLine(rec(speed = 12.24f)))
    }

    @Test
    fun formatCopyLine_null_speed_and_latency_show_zero() {
        assertEquals("172.67.162.190#HK优选[0ms0mbps]", ResultFormatter.formatCopyLine(rec(avgMs = null, speed = null)))
    }

    @Test
    fun formatCopyLine_trailing_zeros_trimmed() {
        assertEquals("1.1.1.1#SJ优选[20ms100mbps]", ResultFormatter.formatCopyLine(rec(ip = "1.1.1.1", regionCode = "SJC", avgMs = 20f, speed = 12.5f)))
    }

    @Test
    fun formatCopyLine_unknown_region_falls_back_to_code_prefix() {
        assertEquals("9.9.9.9#XY优选[50ms50mbps]", ResultFormatter.formatCopyLine(rec(ip = "9.9.9.9", regionCode = "XYZ", avgMs = 50f, speed = 6.25f)))
    }

    @Test
    fun formatCopyLines_joins_with_newline() {
        val a = rec(ip = "1.1.1.1", regionCode = "HKG", avgMs = 19.21f, speed = 12.24f)
        val b = rec(ip = "2.2.2.2", regionCode = "LAX", avgMs = 40f, speed = 3f)
        assertEquals(
            "1.1.1.1#HK优选[19.21ms97.92mbps]\n2.2.2.2#LA优选[40ms24mbps]",
            ResultFormatter.formatCopyLines(listOf(a, b)),
        )
    }
}