package com.cfst.android.engine.cfst

import com.cfst.android.engine.ScanEngine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CfstEngineTest {

    @get:Rule
    val tmp: TemporaryFolder = TemporaryFolder()

    private fun fakeRunner(
        exitCode: Int,
        progressLines: List<String> = emptyList(),
    ): CfstProcessRunner = CfstProcessRunner(
        processRunner = { cmd ->
            val outIdx = cmd.indexOf("-o")
            if (outIdx >= 0 && exitCode == 0) {
                val outFile = File(cmd[outIdx + 1])
                outFile.parentFile?.mkdirs()
                outFile.writeText("IP 地址,已发送,已接收,丢包率,平均延迟,下载速度(MB/s),地区码\n" +
                    "1.1.1.1,4,4,0.00,50.00,12.50,HKG\n" +
                    "1.1.1.2,4,0,1.00,0.00,0.00,UNK\n")
            }
            object : Process() {
                override fun getOutputStream() = java.io.ByteArrayOutputStream()
                override fun getInputStream() =
                    java.io.ByteArrayInputStream((progressLines + "").joinToString("\n").toByteArray())
                override fun getErrorStream() = java.io.ByteArrayInputStream(ByteArray(0))
                override fun waitFor(): Int = exitCode
                override fun exitValue(): Int = exitCode
                override fun destroy() {}
                override fun destroyForcibly(): Process = this
            }
        }
    )

    private fun engine(
        binaryExists: Boolean = true,
        exitCode: Int = 0,
        progressLines: List<String> = emptyList(),
    ): ScanEngine {
        val workDir = tmp.root
        if (binaryExists) File(workDir, "libcfst.so").writeText("x")
        return CfstEngine(
            binaryPath = { File(workDir, "libcfst.so") },
            workDir = { workDir },
            runner = fakeRunner(exitCode, progressLines),
        )
    }

    @Test
    fun `isAvailable true when binary exists and -v succeeds`() = runBlocking {
        assertTrue(engine().isAvailable())
    }

    @Test
    fun `isAvailable false when binary missing`() = runBlocking {
        assertFalse(engine(binaryExists = false).isAvailable())
    }

    @Test
    fun `isAvailable false when -v fails`() = runBlocking {
        assertFalse(engine(exitCode = 1).isAvailable())
    }

    @Test
    fun `latencyScan parses csv survivors with latency and region`() = runBlocking {
        val engine = engine()
        val progress = mutableListOf<Pair<Int, Int>>()
        val results = engine.latencyScan(
            ips = listOf("1.1.1.1", "1.1.1.2"),
            port = 443,
            probeCount = 500,
            pingCount = 2,
            latencyLimit = 200f,
            concurrency = 8,
        ) { done, total -> progress.add(done to total) }

        assertEquals(1, results.size)
        assertEquals("1.1.1.1", results[0].ip)
        assertEquals(443, results[0].port)
        assertEquals(50f, results[0].avgMs)
        assertEquals("HKG", results[0].regionCode)
    }

    @Test
    fun `latencyScan empty on non-zero exit`() = runBlocking {
        val engine = engine(exitCode = 1)
        val results = engine.latencyScan(
            ips = listOf("1.1.1.1"),
            port = 443,
            probeCount = 500,
            pingCount = 2,
            latencyLimit = 200f,
            concurrency = 8,
        ) { _, _ -> }
        assertTrue(results.isEmpty())
    }

    @Test
    fun `speedScan parses speeds from csv`() = runBlocking {
        val engine = engine()
        val results = engine.speedScan(
            ips = listOf("1.1.1.1", "1.1.1.2"),
            port = 443,
            url = "https://speed.hatexianyu.ccwu.cc/",
            downloadTime = 10,
            downloadCount = 50,
            speedLimit = 0f,
            concurrency = 5,
        ) { _, _ -> }

        assertEquals(1, results.size)
        assertEquals(12.5f, results[0].speed)
        assertEquals(50f, results[0].avgMs)
    }
}
