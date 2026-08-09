package com.cfst.android.engine

import com.cfst.android.engine.model.ScanResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

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
    fun high_precision_floats_round_trip_exactly() {
        val records = listOf(sample().copy(avgMs = 12.345f, speed = 123.456789f, lossPct = 0.1234567f))
        assertEquals(records, CsvCodec.parse(CsvCodec.encode(records)))
    }

    @Test
    fun embedded_newline_in_region_name_survives_round_trip() {
        val records = listOf(sample().copy(regionName = "line1\nline2"))
        val csv = CsvCodec.encode(records)
        assertEquals(records, CsvCodec.parse(csv))
    }
}
