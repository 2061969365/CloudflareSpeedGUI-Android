package com.cfst.android.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun v4_slash31_sample_includes_both_usable_addresses() {
        val n = IpParser.parseCidr("10.0.0.0/31")!!
        assertEquals(2, n.expandAll().count())
        assertEquals(setOf("10.0.0.0", "10.0.0.1"), n.sampleHosts(2).toSet())
        val one = n.sampleHosts(1).single()
        assertTrue("single sample must be one of the two usable addresses", one == "10.0.0.0" || one == "10.0.0.1")
    }

    @Test
    fun v6_slash128_generates_self() {
        val n = IpParser.parseCidr("2606:4700::1/128")!!
        assertEquals(1, n.expandAll().count())
        val host = n.sampleHosts(1).single()
        assertFalse("sample must not contain '/'", host.contains("/"))
        assertEquals("2606:4700::1", host)
        assertEquals(listOf("2606:4700::1"), n.expandAll().toList())
    }

    @Test
    fun v6_slash127_generates_p2p_pair() {
        val n = IpParser.parseCidr("2606:4700::/127")!!
        assertEquals(2, n.expandAll().count())
        val hosts = n.expandAll().toList()
        assertEquals(2, hosts.size)
        assertTrue(hosts.all { !it.contains("/") })
        assertEquals(setOf("2606:4700::", "2606:4700::1"), hosts.toSet())
        assertEquals(setOf("2606:4700::", "2606:4700::1"), n.sampleHosts(2).toSet())
    }

    @Test
    fun v6_large_prefix_expandAll_returns_nothing() {
        val n = IpParser.parseCidr("2606:4700::/64")!!
        assertTrue("non-enumerable v6 prefix must not emit a CIDR string", n.expandAll().toList().isEmpty())
    }

    @Test
    fun leading_zero_octets_rejected() {
        assertNull(IpParser.parseCidr("01.2.3.4"))
        assertNull(IpParser.parseCidr("10.0.0.01"))
        assertNull(IpParser.parseCidr("10.0.00.1/24"))
    }

    @Test
    fun parseCidr_ipv4_with_port_parses_as_slash32() {
        val n = IpParser.parseCidr("1.2.3.4:443")!!
        assertEquals(4, n.version)
        assertEquals(32, n.prefixLen)
        assertEquals(1L, n.numAddresses)
        assertEquals("1.2.3.4", n.expandAll().toList().single())
    }

    @Test
    fun parseCidr_ipv6_with_port_rejected() {
        assertNull(IpParser.parseCidr("2606:4700::1:443"))
    }

    @Test
    fun parseRange_start_before_end() {
        val n = IpParser.parseRange("1.2.3.4-1.2.3.10")!!
        assertEquals(4, n.version)
        assertEquals(7L, n.numAddresses)
        val all = n.expandAll().toList()
        assertEquals(7, all.size)
        assertEquals("1.2.3.4", all.first())
        assertEquals("1.2.3.10", all.last())
        assertTrue(all.contains("1.2.3.7"))
    }

    @Test
    fun parseRange_with_spaces_around_dash() {
        val n = IpParser.parseRange("1.2.3.4 - 1.2.3.6")!!
        assertEquals(3L, n.numAddresses)
        assertTrue(n.expandAll().toList().containsAll(listOf("1.2.3.4", "1.2.3.6")))
    }

    @Test
    fun parseRange_single_ip_span() {
        val n = IpParser.parseRange("1.2.3.4-1.2.3.4")!!
        assertEquals(1L, n.numAddresses)
        assertEquals(listOf("1.2.3.4"), n.expandAll().toList())
    }

    @Test
    fun parseRange_start_after_end_uses_minmax() {
        val n = IpParser.parseRange("1.2.3.10-1.2.3.4")!!
        val all = n.expandAll().toList()
        assertEquals(7, all.size)
        assertEquals("1.2.3.4", all.first())
        assertEquals("1.2.3.10", all.last())
    }

    @Test
    fun parseRange_sampling_includes_endpoints() {
        val n = IpParser.parseRange("1.2.3.4-1.2.3.10")!!
        val sample = n.sampleHosts(7)
        assertEquals(7, sample.size)
        assertTrue(sample.contains("1.2.3.4"))
        assertTrue(sample.contains("1.2.3.10"))
        assertTrue(sample.all { it.split(".").size == 4 })
    }

    @Test
    fun parseRange_invalid_returns_null() {
        assertNull(IpParser.parseRange("1.2.3.4-999.1.1.1"))
        assertNull(IpParser.parseRange("not-a-range"))
        assertNull(IpParser.parseRange("1.2.3.4"))
    }
}
