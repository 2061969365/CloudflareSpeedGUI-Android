package com.cfst.android.engine

import com.cfst.android.engine.model.ResultStats
import com.cfst.android.engine.model.ScanResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ResultStatsTest {
    private fun scan(
        ip: String = "1.1.1.1",
        avgMs: Float? = 10f,
        regionCode: String = "US",
    ) = ScanResult(
        ip = ip,
        port = 443,
        avgMs = avgMs,
        minMs = null,
        maxMs = null,
        lossPct = 0f,
        speed = null,
        regionCode = regionCode,
        regionName = "",
        testedAt = 0L,
    )

    @Test
    fun top3_regions_sorted_by_lowest_latency() {
        val records = listOf(
            scan(avgMs = 40f, regionCode = "US"),
            scan(avgMs = 60f, regionCode = "US"),
            scan(avgMs = 10f, regionCode = "JP"),
            scan(avgMs = 20f, regionCode = "JP"),
            scan(avgMs = 15f, regionCode = "JP"),
            scan(avgMs = 8f, regionCode = "DE"),
            scan(avgMs = 30f, regionCode = "SG"),
            scan(avgMs = 50f, regionCode = "SG"),
        )
        val stats = ResultStats.compute(records)
        assertEquals(8, stats.total)
        assertEquals(listOf("DE" to 1, "JP" to 3, "SG" to 2), stats.topRegions)
    }

    @Test
    fun blank_region_codes_are_excluded_from_topRegions() {
        val records = listOf(
            scan(avgMs = 5f, regionCode = ""),
            scan(avgMs = 20f, regionCode = "CN"),
        )
        val stats = ResultStats.compute(records)
        assertEquals(listOf("CN" to 1), stats.topRegions)
    }

    @Test
    fun region_codes_are_grouped_case_insensitively() {
        val records = listOf(
            scan(avgMs = 10f, regionCode = "hkg"),
            scan(avgMs = 20f, regionCode = "HKG"),
            scan(avgMs = 30f, regionCode = "sin"),
        )
        val stats = ResultStats.compute(records)
        assertEquals(3, stats.total)
        assertEquals(listOf("HKG" to 2, "SIN" to 1), stats.topRegions)
    }

    @Test
    fun fastestMs_is_min_of_non_null_avgMs() {
        val records = listOf(
            scan(avgMs = null),
            scan(avgMs = 25f),
            scan(avgMs = 12.5f),
            scan(avgMs = 30f),
        )
        val stats = ResultStats.compute(records)
        assertEquals(12.5f, stats.fastestMs!!, 0f)
    }

    @Test
    fun all_null_avgMs_yields_null_fastest() {
        val stats = ResultStats.compute(listOf(scan(avgMs = null), scan(avgMs = null)))
        assertNull(stats.fastestMs)
    }

    @Test
    fun empty_list_is_safe() {
        val stats = ResultStats.compute(emptyList())
        assertEquals(0, stats.total)
        assertEquals(emptyList<Pair<String, Int>>(), stats.topRegions)
        assertNull(stats.fastestMs)
    }
}
