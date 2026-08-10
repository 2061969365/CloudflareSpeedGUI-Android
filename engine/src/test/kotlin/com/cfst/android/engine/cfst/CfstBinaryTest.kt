package com.cfst.android.engine.cfst

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CfstBinaryTest {

    @Test
    fun latencyCmd_exact_args() {
        val cmd = CfstBinary.latencyCmd(
            ipFile = "ips.txt",
            port = 443,
            probeCount = 300,
            pingCount = 4,
            latencyLimit = 200.0f,
            outCsv = "out.csv",
        )
        assertEquals(
            listOf(
                "-f", "ips.txt",
                "-tp", "443",
                "-n", "300",
                "-t", "4",
                "-httping",
                "-dd",
                "-tl", "200",
                "-url", CfstBinary.DEFAULT_SPEED_URL,
                "-p", "0",
                "-o", "out.csv",
            ),
            cmd,
        )
    }

    @Test
    fun latencyLimit_truncated_to_int_for_go_flag() {
        val cmd = CfstBinary.latencyCmd("ips.txt", 443, 300, 4, 200.5f, "out.csv")
        assertEquals("201", cmd[cmd.indexOf("-tl") + 1])
    }

    @Test
    fun latencyLimit_fractional_rounds_not_truncates() {
        // 0.9 rounds to 1 (truncation would produce -tl 0 == "no limit", silently ignoring the filter)
        assertEquals("1", tlArg(0.9f))
        assertEquals("200", tlArg(199.9f))
        assertEquals("0", tlArg(0f))
    }

    private fun tlArg(latencyLimit: Float): String {
        val cmd = CfstBinary.latencyCmd("ips.txt", 443, 300, 4, latencyLimit, "out.csv")
        return cmd[cmd.indexOf("-tl") + 1]
    }

    @Test
    fun latencyCmd_default_url_when_blank() {
        val cmd = CfstBinary.latencyCmd("ips.txt", 443, 300, 4, 200f, "out.csv", url = "")
        assertEquals(CfstBinary.DEFAULT_SPEED_URL, cmd[cmd.indexOf("-url") + 1])
    }

    @Test
    fun latencyCmd_custom_url_passed_through() {
        val cmd = CfstBinary.latencyCmd("ips.txt", 443, 300, 4, 200f, "out.csv", url = "https://example.com/url")
        assertEquals("https://example.com/url", cmd[cmd.indexOf("-url") + 1])
    }

    @Test
    fun speedCmd_without_url_and_speed_limit() {
        val cmd = CfstBinary.speedCmd(
            ipFile = "ips.txt",
            port = 443,
            url = "",
            downloadTime = 10,
            downloadCount = 3,
            speedLimit = 0f,
            outCsv = "out.csv",
        )
        assertEquals(
            listOf(
                "-f", "ips.txt",
                "-tp", "443",
                "-n", "200",
                "-t", "4",
                "-httping",
                "-dt", "10",
                "-dn", "3",
                "-p", "0",
                "-o", "out.csv",
            ),
            cmd,
        )
    }

    @Test
    fun speedCmd_with_url_and_speed_limit() {
        val cmd = CfstBinary.speedCmd(
            ipFile = "ips.txt",
            port = 443,
            url = "https://cf.xiu2.xyz/url",
            downloadTime = 10,
            downloadCount = 3,
            speedLimit = 5.0f,
            outCsv = "out.csv",
        )
        assertEquals(
            listOf(
                "-f", "ips.txt",
                "-tp", "443",
                "-n", "200",
                "-t", "4",
                "-httping",
                "-dt", "10",
                "-dn", "3",
                "-p", "0",
                "-o", "out.csv",
                "-url", "https://cf.xiu2.xyz/url",
                "-sl", "5.0",
            ),
            cmd,
        )
    }

    @Test
    fun default_speed_url_constant() {
        assertEquals("https://speed.hatexianyu.ccwu.cc/?bytes=209715200", CfstBinary.DEFAULT_SPEED_URL)
    }

    @Test
    fun latencyCmd_concurrency_capped_at_1000() {
        val cmd = CfstBinary.latencyCmd(
            ipFile = "ips.txt",
            port = 443,
            probeCount = 300,
            pingCount = 4,
            latencyLimit = 200f,
            outCsv = "out.csv",
            concurrency = 1500,
        )
        assertEquals("1000", cmd[cmd.indexOf("-n") + 1])
    }

    @Test
    fun speedCmd_concurrency_overrides_default_200_in_n() {
        val cmd = CfstBinary.speedCmd(
            ipFile = "ips.txt",
            port = 443,
            url = "https://url",
            downloadTime = 10,
            downloadCount = 3,
            speedLimit = 0f,
            outCsv = "out.csv",
            concurrency = 800,
        )
        assertEquals("800", cmd[cmd.indexOf("-n") + 1])
    }

    @Test
    fun speedCmd_concurrency_capped_at_1000() {
        val cmd = CfstBinary.speedCmd(
            ipFile = "ips.txt",
            port = 443,
            url = "https://url",
            downloadTime = 10,
            downloadCount = 3,
            speedLimit = 0f,
            outCsv = "out.csv",
            concurrency = 1500,
        )
        assertEquals("1000", cmd[cmd.indexOf("-n") + 1])
    }

    @Test
    fun latency_timeout_scales_with_ip_count_and_inverse_concurrency() {
        assertEquals(120_000L, CfstBinary.latencyTimeoutMs(ipCount = 100, concurrency = 100))
        assertEquals(4_030_000L, CfstBinary.latencyTimeoutMs(ipCount = 1_000_000, concurrency = 100))
        assertEquals(40_030_000L, CfstBinary.latencyTimeoutMs(ipCount = 1_000_000, concurrency = 10))
        assertEquals(120_000L, CfstBinary.latencyTimeoutMs(ipCount = 1, concurrency = 0))
    }

    @Test
    fun latency_timeout_includes_ping_count_factor() {
        val withoutPing = CfstBinary.latencyTimeoutMs(ipCount = 100_000, concurrency = 100)
        val withPing = CfstBinary.latencyTimeoutMs(ipCount = 100_000, concurrency = 100, pingCount = 100)
        assertTrue(withPing > withoutPing)
        assertEquals(432_000L, withPing)
    }

    @Test
    fun speed_timeout_scales_with_download_work() {
        assertEquals(530_000L, CfstBinary.speedTimeoutMs(downloadTime = 10, downloadCount = 50))
        assertEquals(120_000L, CfstBinary.speedTimeoutMs(downloadTime = 3, downloadCount = 3))
    }

    @Test
    fun speed_timeout_includes_survivor_factor() {
        assertEquals(
            1_530_000L,
            CfstBinary.speedTimeoutMs(downloadTime = 10, downloadCount = 50, survivors = 100),
        )
    }
}
