package com.cfst.android.engine.cfst

import org.junit.Assert.assertEquals
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
        assertEquals("200", cmd[cmd.indexOf("-tl") + 1])
    }

    @Test
    fun latencyCmd_default_url_when_blank() {
        val cmd = CfstBinary.latencyCmd("ips.txt", 443, 300, 4, 200f, "out.csv", url = "")
        assertEquals("", cmd[cmd.indexOf("-url") + 1])
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
}
