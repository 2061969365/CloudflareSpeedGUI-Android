package com.cfst.android.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IpParserTest {
    @Test
    fun parse_v4_cidr() {
        val n = IpParser.parseCidr("173.245.48.0/20")!!
        assertEquals(20, n.prefixLen)
        assertEquals(4, n.version)
        assertEquals(4096L, n.numAddresses)
        val s = n.sampleHosts(1).single()
        assertTrue(s.startsWith("173.245."))
    }

    @Test
    fun bad_cidr_returns_null() {
        assertNull(IpParser.parseCidr("not-a-cidr"))
    }

    @Test
    fun ipv6_parse() {
        assertNotNull(IpParser.parseCidr("2606:4700::/128"))
    }

    @Test
    fun expandAll_covers_full_range() {
        assertEquals(2, IpParser.parseCidr("10.0.0.0/30")!!.expandAll().toList().size)
    }
}
