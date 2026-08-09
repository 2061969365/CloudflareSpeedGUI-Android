package com.cfst.android.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class ColoRegionMapperTest {

    @Test
    fun `maps known colo codes`() {
        assertEquals("中国香港", ColoRegionMapper.map("HKG"))
        assertEquals("美国洛杉矶", ColoRegionMapper.map("LAX"))
    }

    @Test
    fun `normalizes lowercase input`() {
        assertEquals("中国香港", ColoRegionMapper.map("hkg"))
    }

    @Test
    fun `returns unknown code normalized`() {
        assertEquals("ZZZ", ColoRegionMapper.map("ZZZ"))
    }

    @Test
    fun `returns unknown for empty input`() {
        assertEquals("未知", ColoRegionMapper.map(""))
    }
}
