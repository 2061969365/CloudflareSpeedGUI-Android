package com.cfst.android.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IpGeneratorTest {

    private fun noop(p: Int) {}

    private fun inNet20(ip: String): Boolean {
        val parts = ip.split(".")
        if (parts.size != 4) return false
        val a = parts[0].toIntOrNull() ?: return false
        val b = parts[1].toIntOrNull() ?: return false
        val c = parts[2].toIntOrNull() ?: return false
        return a == 173 && b == 245 && c in 48..63
    }

    @Test
    fun sampling_nonempty_all_in_subnet() {
        val ips = IpGenerator.generate(listOf("173.245.48.0/20"), 0, false, ::noop)
        assertTrue("sampled list must not be empty", ips.isNotEmpty())
        assertTrue("every sampled host must be inside the CIDR", ips.all(::inNet20))
    }

    @Test
    fun quota_respects_max_ips() {
        val ips = IpGenerator.generate(listOf("173.245.48.0/20", "173.245.64.0/20"), 10, false, ::noop)
        assertTrue("distinct result must not exceed maxIps", ips.distinct().size <= 10)
    }

    @Test
    fun fullScan_expands_ipv4() {
        val ips = IpGenerator.generate(listOf("10.0.0.0/30"), 0, true, ::noop)
        assertEquals(setOf("10.0.0.1", "10.0.0.2"), ips.toSet())
    }

    @Test
    fun bare_ip_line_preserved() {
        val ips = IpGenerator.generate(listOf("1.1.1.1"), 0, false, ::noop)
        assertTrue("bare IP line must be kept as-is", ips.contains("1.1.1.1"))
    }

    @Test
    fun progress_callback_invoked() {
        val seen = mutableListOf<Int>()
        IpGenerator.generate(listOf("173.245.48.0/20"), 0, false) { seen += it }
        assertTrue("progress callback must be invoked", seen.isNotEmpty())
        assertTrue("progress values must be in 0..100", seen.all { it in 0..100 })
        assertEquals(100, seen.last())
    }

    @Test
    fun invalid_lines_dropped() {
        val ips = IpGenerator.generate(listOf("not-an-ip", "999.1.1.1", "173.245.48.0/20"), 0, false, ::noop)
        assertFalse(ips.contains("not-an-ip"))
        assertFalse(ips.contains("999.1.1.1"))
        assertTrue(ips.isNotEmpty())
    }

    @Test
    fun slash32_line_treated_as_single_host() {
        val ips = IpGenerator.generate(listOf("104.16.1.1/32"), 0, false, ::noop)
        assertEquals(listOf("104.16.1.1"), ips)
    }

    @Test
    fun fullScan_large_network_does_not_oom() {
        val ips = IpGenerator.generate(listOf("0.0.0.0/0"), 0, true, ::noop)
        assertTrue("full scan of /0 must be capped to a sane bound", ips.size in 1..2_000_000)
        assertTrue(ips.isNotEmpty())
    }
}
