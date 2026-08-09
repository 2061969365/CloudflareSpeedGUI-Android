package com.cfst.android.engine.cfst

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CfstProgressParserTest {

    @Test
    fun parseProgress_chinese() {
        assertEquals(123 to 456, CfstProgressParser.parseProgress("进度: 123/456"))
    }

    @Test
    fun parseProgress_english() {
        assertEquals(123 to 456, CfstProgressParser.parseProgress("Progress: 123/456"))
    }

    @Test
    fun parseProgress_bare() {
        assertEquals(123 to 456, CfstProgressParser.parseProgress("123/456  [12ms]"))
    }

    @Test
    fun parseProgress_trailing_cr_stripped() {
        assertEquals(1 to 2, CfstProgressParser.parseProgress("进度: 1/2\r"))
    }

    @Test
    fun parseProgress_no_match_returns_null() {
        assertNull(CfstProgressParser.parseProgress("hello world"))
        assertNull(CfstProgressParser.parseProgress(""))
        assertNull(CfstProgressParser.parseProgress("123/456"))
    }

    @Test
    fun percent_basic() {
        assertEquals(50, CfstProgressParser.percent(50, 100))
        assertEquals(0, CfstProgressParser.percent(0, 100))
        assertEquals(100, CfstProgressParser.percent(100, 100))
    }

    @Test
    fun percent_caps_at_100() {
        assertEquals(100, CfstProgressParser.percent(150, 100))
    }

    @Test
    fun percent_total_zero() {
        assertEquals(0, CfstProgressParser.percent(10, 0))
    }

    @Test
    fun computeEtaMs_done_zero() {
        assertNull(CfstProgressParser.computeEtaMs(1000, 0, 100))
    }

    @Test
    fun computeEtaMs_basic() {
        assertEquals(4000L, CfstProgressParser.computeEtaMs(1000, 1, 5))
    }

    @Test
    fun computeEtaMs_done_equals_total() {
        assertEquals(0L, CfstProgressParser.computeEtaMs(1000, 5, 5))
    }
}
