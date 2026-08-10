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

    @Test
    fun zero_network_with_maxIps_zero_does_not_oom() {
        val ips = IpGenerator.generate(listOf("0.0.0.0/0"), 0, false, ::noop)
        assertTrue("per-/24 sampling of /0 must be capped", ips.size in 1..200_000)
        assertTrue("all hosts must be plain IPv4", ips.all { it.split(".").size == 4 && '/' !in it })
    }

    @Test
    fun bare_ips_truncated_by_max_ips() {
        val bare = (1..100).map { "10.0.$it.1" }
        val ips = IpGenerator.generate(bare, 5, false, ::noop)
        assertEquals("bare IPs must be truncated to maxIps", 5, ips.size)
    }

    @Test
    fun bare_ips_and_networks_capped_together() {
        val ips = IpGenerator.generate(listOf("1.1.1.1", "2.2.2.2", "173.245.48.0/20"), 5, false, ::noop)
        assertTrue("bare IPs + network samples must not exceed maxIps", ips.distinct().size <= 5)
    }

    @Test
    fun ipv6_fullScan_produces_no_slash_ips() {
        val ips = IpGenerator.generate(
            listOf("2606:4700::/64", "2606:4700::/127", "2606:4700::1/128"),
            0, true, ::noop,
        )
        assertTrue("full scan of v6 must still produce hosts", ips.isNotEmpty())
        assertTrue("no generated IP may contain '/'", ips.all { '/' !in it })
    }

    @Test
    fun ipv6_sampling_produces_plain_ips() {
        val ips = IpGenerator.generate(listOf("2606:4700::1/128", "2606:4700::/127"), 0, false, ::noop)
        assertTrue(ips.isNotEmpty())
        assertTrue("no sampled v6 IP may contain '/'", ips.all { '/' !in it })
    }

    @Test
    fun invalid_ipv6_not_accepted_as_bare_ip() {
        val ips = IpGenerator.generate(listOf("1:2:3:4:5:6:7", "::", "2606:4700::1"), 0, false, ::noop)
        assertFalse(ips.contains("1:2:3:4:5:6:7"))
        assertFalse(ips.contains("::"))
        assertTrue(ips.contains("2606:4700::1"))
    }

    @Test
    fun leading_zero_ipv4_rejected_as_bare_ip() {
        val ips = IpGenerator.generate(listOf("01.2.3.4", "1.1.1.1"), 0, false, ::noop)
        assertFalse("leading-zero octet must be rejected", ips.contains("01.2.3.4"))
        assertTrue(ips.contains("1.1.1.1"))
    }
}
