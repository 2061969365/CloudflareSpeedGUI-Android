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

    @Test
    fun invalid_cidr_returns_null() {
        assertNull(IpParser.parseCidr("300.1.1.1/24"))
        assertNull(IpParser.parseCidr("1.2.3.4/33"))
    }

    @Test
    fun v4_slash32_generates_the_host_itself() {
        val n = IpParser.parseCidr("104.16.1.1/32")!!
        assertEquals(1L, n.numAddresses)
        assertEquals("104.16.1.1", n.expandAll().toList().single())
        assertEquals("104.16.1.1", n.sampleHosts(1).single())
    }

    @Test
    fun v4_slash31_generates_p2p_pair() {
        val n = IpParser.parseCidr("10.0.0.2/31")!!
        val hosts = n.expandAll().toList()
        assertEquals(2, hosts.size)
        assertTrue(hosts.containsAll(listOf("10.0.0.2", "10.0.0.3")))
    }

    @Test
    fun v6_slash128_generates_self() {
        val n = IpParser.parseCidr("2606:4700::1/128")!!
        val hosts = n.sampleHosts(1)
        assertEquals(1, hosts.size)
    }

    @Test
    fun v6_slash127_generates_p2p_pair() {
        val n = IpParser.parseCidr("2606:4700::/127")!!
        assertEquals(2, n.expandAll().toList().size)
    }
}
