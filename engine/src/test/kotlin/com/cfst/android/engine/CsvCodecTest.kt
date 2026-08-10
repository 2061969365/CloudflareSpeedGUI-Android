package com.cfst.android.engine

import com.cfst.android.engine.model.ScanResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

class CsvCodecTest {
    private val header = "IP 地址,端口,平均延迟(ms),下载速度(MB/s),地区码,测试时间,最小延迟(ms),最大延迟(ms),丢包率(%),地区名"

    private fun sample() = ScanResult(
        ip = "104.16.1.1",
        port = 443,
        avgMs = 12.34f,
        minMs = 10f,
        maxMs = 20f,
        lossPct = 0.5f,
        speed = 25.5f,
        regionCode = "US",
        regionName = "美国 圣何塞",
        testedAt = 1700000000123L,
    )

    @Test
    fun encode_parse_round_trip_equals_original() {
        val records = listOf(sample())
        assertEquals(records, CsvCodec.parse(CsvCodec.encode(records)))
    }

    @Test
    fun empty_list_produces_header_only_and_parses_to_empty() {
        val csv = CsvCodec.encode(emptyList())
        assertTrue(csv.startsWith(header))
        assertEquals(emptyList<ScanResult>(), CsvCodec.parse(csv))
    }

    @Test
    fun null_numbers_encoded_as_empty_and_back_to_null() {
        val records = listOf(sample().copy(avgMs = null, minMs = null, maxMs = null, speed = null))
        val parsed = CsvCodec.parse(CsvCodec.encode(records))
        assertNull(parsed.single().avgMs)
        assertNull(parsed.single().minMs)
        assertNull(parsed.single().maxMs)
        assertNull(parsed.single().speed)
        assertEquals(records, parsed)
    }

    @Test
    fun multiple_records_round_trip() {
        val records = listOf(
            sample(),
            sample().copy(
                ip = "104.16.1.2",
                regionCode = "JP",
                regionName = "日本 东京",
                avgMs = 55.5f,
                speed = null,
            ),
        )
        assertEquals(records, CsvCodec.parse(CsvCodec.encode(records)))
    }

    @Test
    fun fields_with_commas_and_quotes_are_escaped_and_round_trip() {
        val records = listOf(sample().copy(regionName = "a,b\"c"))
        val csv = CsvCodec.encode(records)
        assertTrue(csv.contains("\"a,b\"\"c\""))
        assertEquals(records, CsvCodec.parse(csv))
    }

    @Test
    fun floats_are_rounded_to_two_decimals_on_encode() {
        val records = listOf(sample().copy(avgMs = 12.345f, speed = 123.456789f, lossPct = 0.1234567f))
        val csv = CsvCodec.encode(records)
        assertTrue(csv.contains("12.35"))
        assertTrue(csv.contains("123.46"))
        assertTrue(csv.contains("0.12"))
        val parsed = CsvCodec.parse(csv).single()
        assertEquals(12.35f, parsed.avgMs!!, 0.001f)
        assertEquals(123.46f, parsed.speed!!, 0.001f)
        assertEquals(0.12f, parsed.lossPct, 0.001f)
    }

    @Test
    fun embedded_newline_in_region_name_survives_round_trip() {
        val records = listOf(sample().copy(regionName = "line1\nline2"))
        val csv = CsvCodec.encode(records)
        assertEquals(records, CsvCodec.parse(csv))
    }

    @Test
    fun malformed_row_missing_columns_parses_with_defaults() {
        val csv = header + "\n104.16.1.1,443\n"
        val parsed = CsvCodec.parse(csv)
        assertEquals(1, parsed.size)
        val r = parsed.single()
        assertEquals("104.16.1.1", r.ip)
        assertEquals(443, r.port)
        assertEquals(0L, r.testedAt)
        assertNull(r.avgMs)
        assertNull(r.minMs)
        assertNull(r.maxMs)
        assertNull(r.speed)
        assertEquals("", r.regionCode)
        assertEquals("", r.regionName)
        assertEquals(0f, r.lossPct, 0f)
    }

    @Test
    fun empty_time_field_parses_to_zero() {
        val csv = header + "\n104.16.1.1,443,12.34,,HKG,,10,20,0.5,香港"
        assertEquals(0L, CsvCodec.parse(csv).single().testedAt)
    }

    @Test
    fun time_without_millis_parses() {
        val csv = header + "\n104.16.1.1,443,12.34,,HKG,2024-11-14 22:13:20,10,20,0.5,香港"
        val expected = LocalDateTime.parse(
            "2024-11-14 22:13:20",
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
        ).atZone(ZoneOffset.UTC).toInstant().toEpochMilli()
        assertEquals(expected, CsvCodec.parse(csv).single().testedAt)
    }

    @Test
    fun garbage_time_field_parses_to_zero() {
        val csv = header + "\n104.16.1.1,443,12.34,,HKG,not-a-date,10,20,0.5,香港"
        assertEquals(0L, CsvCodec.parse(csv).single().testedAt)
    }

    @Test
    fun first_line_not_header_is_not_dropped() {
        val csv = "104.16.1.1,443,12.34,25.5,HKG,2024-11-14 22:13:20.123,10,20,0.5,香港\n" +
            "104.16.1.2,80,,,SIN,,,," +
            ""
        val parsed = CsvCodec.parse(csv)
        assertEquals(2, parsed.size)
        assertEquals("104.16.1.1", parsed[0].ip)
        assertEquals("104.16.1.2", parsed[1].ip)
        assertEquals(443, parsed[0].port)
    }

    @Test
    fun escaped_line_with_quotes_commas_and_newline_parses() {
        val csv = header + "\n104.16.1.1,443,12.34,25.5,HKG,2024-11-14 22:13:20.123,10,20,0.5,\"香港,九龙\"\"区\n第2行\""
        val parsed = CsvCodec.parse(csv)
        assertEquals(1, parsed.size)
        assertEquals("香港,九龙\"区\n第2行", parsed.single().regionName)
    }
}
