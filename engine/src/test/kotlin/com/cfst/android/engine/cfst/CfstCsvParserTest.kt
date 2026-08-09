package com.cfst.android.engine.cfst

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class CfstCsvParserTest {

    private val fixture = """
        IP 地址,已发送,已接收,丢包率,平均延迟,下载速度(MB/s),地区码
        104.16.1.1,4,4,0.05,50.12,0.00,HKG
        104.16.2.2,4,3,1.00,88.30,12.50,LAX
    """.trimIndent()

    @Test
    fun parse_basic_fields() {
        val results = CfstCsvParser.parse(fixture, port = 443, testedAt = 123456789L)
        assertEquals(2, results.size)

        val hkg = results[0]
        assertEquals("104.16.1.1", hkg.ip)
        assertEquals(443, hkg.port)
        assertEquals(50.12f, hkg.avgMs!!, 0f)
        assertEquals(5.0f, hkg.lossPct, 0f)
        assertEquals(0.0f, hkg.speed!!, 0f)
        assertEquals("HKG", hkg.regionCode)
        assertEquals("中国香港", hkg.regionName)
        assertEquals(123456789L, hkg.testedAt)
        assertNull(hkg.minMs)
        assertNull(hkg.maxMs)
    }

    @Test
    fun parse_second_row_loss_and_speed() {
        val results = CfstCsvParser.parse(fixture, port = 443, testedAt = 123456789L)
        val lax = results[1]
        assertEquals("104.16.2.2", lax.ip)
        assertEquals(88.3f, lax.avgMs!!, 0f)
        assertEquals(100.0f, lax.lossPct, 0f)
        assertEquals(12.5f, lax.speed!!, 0f)
        assertEquals("LAX", lax.regionCode)
        assertEquals("美国洛杉矶", lax.regionName)
    }

    @Test
    fun parse_bom_stripped() {
        val results = CfstCsvParser.parse("\uFEFF$fixture", port = 443, testedAt = 1L)
        assertEquals(2, results.size)
    }

    @Test
    fun parse_empty_input() {
        assertEquals(0, CfstCsvParser.parse("").size)
        assertEquals(0, CfstCsvParser.parse("\n\n").size)
    }

    @Test
    fun parse_blank_colo_maps_to_unknown() {
        val csv = "IP 地址,已发送,已接收,丢包率,平均延迟,下载速度(MB/s),地区码\n1.1.1.1,4,4,0.00,10.00,1.20,N/A\n"
        val results = CfstCsvParser.parse(csv, port = 80, testedAt = 9L)
        assertEquals(1, results.size)
        assertEquals("N/A", results[0].regionCode)
        assertEquals("N/A", results[0].regionName)
    }

    @Test
    fun parse_zero_avg_ms_is_null() {
        val csv = "IP 地址,已发送,已接收,丢包率,平均延迟,下载速度(MB/s),地区码\n1.1.1.1,4,4,0.00,0.00,0.00,HKG\n"
        val results = CfstCsvParser.parse(csv, port = 80, testedAt = 9L)
        assertNull(results[0].avgMs)
        assertNotNull(results[0].speed)
    }
}
