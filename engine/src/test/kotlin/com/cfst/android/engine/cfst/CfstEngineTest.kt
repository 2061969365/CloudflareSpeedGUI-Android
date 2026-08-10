package com.cfst.android.engine.cfst

import com.cfst.android.engine.ScanEngine
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

class CfstEngineTest {

    @get:Rule
    val tmp: TemporaryFolder = TemporaryFolder()

    private val recordedCmds = mutableListOf<List<String>>()
    private var outExistedAtProcessStart: Boolean? = null
    private var writeCsv = true

    private val csvFixture = "IP 地址,已发送,已接收,丢包率,平均延迟,下载速度(MB/s),地区码\n" +
        "1.1.1.1,4,4,0.00,50.00,12.50,HKG\n" +
        "1.1.1.2,4,0,1.00,0.00,0.00,UNK\n"

    private fun fakeRunner(
        exitCode: Int,
        progressLines: List<String> = emptyList(),
    ): CfstProcessRunner = CfstProcessRunner(
        processRunner = { cmd ->
            recordedCmds += cmd
            val outIdx = cmd.indexOf("-o")
            if (outIdx >= 0) {
                val outFile = File(cmd[outIdx + 1])
                outFile.parentFile?.mkdirs()
                outExistedAtProcessStart = outFile.exists()
                if (writeCsv) {
                    outFile.writeText(csvFixture)
                }
            }
            object : Process() {
                override fun getOutputStream() = ByteArrayOutputStream()
                override fun getInputStream() =
                    ByteArrayInputStream((progressLines + "").joinToString("\n").toByteArray())
                override fun getErrorStream() = ByteArrayInputStream(ByteArray(0))
                override fun waitFor(): Int = exitCode
                override fun exitValue(): Int = exitCode
                override fun destroy() {}
                override fun destroyForcibly(): Process = this
            }
        }
    )

    private fun blockingFakeRunner(): CfstProcessRunner = CfstProcessRunner(
        processRunner = {
            val alive = AtomicBoolean(true)
            object : Process() {
                override fun getOutputStream() = ByteArrayOutputStream()
                override fun getInputStream() = ByteArrayInputStream(ByteArray(0))
                override fun getErrorStream() = ByteArrayInputStream(ByteArray(0))
                override fun isAlive(): Boolean = alive.get()
                override fun waitFor(): Int {
                    while (alive.get()) Thread.sleep(10)
                    return 0
                }
                override fun exitValue(): Int = 0
                override fun destroy() {
                    alive.set(false)
                }
                override fun destroyForcibly(): Process {
                    alive.set(false)
                    return this
                }
            }
        }
    )

    private fun engine(
        binaryExists: Boolean = true,
        exitCode: Int = 0,
        progressLines: List<String> = emptyList(),
        timeoutMs: Long = 120_000,
        runnerOverride: CfstProcessRunner? = null,
    ): ScanEngine {
        val workDir = tmp.root
        if (binaryExists) File(workDir, "libcfst.so").writeText("x")
        return CfstEngine(
            binaryPath = { File(workDir, "libcfst.so") },
            workDir = { workDir },
            runner = runnerOverride ?: fakeRunner(exitCode, progressLines),
            timeoutMs = timeoutMs,
        )
    }

    @Test
    fun `isAvailable true when binary exists and probe succeeds`() = runBlocking {
        assertTrue(engine().isAvailable())
    }

    @Test
    fun `isAvailable false when binary missing`() = runBlocking {
        assertFalse(engine(binaryExists = false).isAvailable())
    }

    @Test
    fun `isAvailable false when probe fails`() = runBlocking {
        assertFalse(engine(exitCode = 1).isAvailable())
    }

    @Test
    fun `isAvailable probes with -h instead of -v`() = runBlocking {
        engine().isAvailable()
        val probe = recordedCmds.last()
        assertEquals("-h", probe.last())
        assertFalse(probe.any { it == "-v" })
    }

    @Test
    fun `latencyScan parses csv survivors with latency and region`() = runBlocking {
        val engine = engine()
        val results = engine.latencyScan(
            ips = listOf("1.1.1.1", "1.1.1.2"),
            port = 443,
            probeCount = 500,
            pingCount = 2,
            latencyLimit = 200f,
            concurrency = 8,
            pingTimeoutMs = 1000,
        ) { _, _ -> }

        assertEquals(1, results.size)
        assertEquals("1.1.1.1", results[0].ip)
        assertEquals(443, results[0].port)
        assertEquals(50f, results[0].avgMs!!, 0f)
        assertEquals("HKG", results[0].regionCode)
    }

    @Test
    fun `latencyScan throws IllegalStateException on non-zero exit`() = runBlocking {
        val engine = engine(exitCode = 1)
        try {
            engine.latencyScan(listOf("1.1.1.1"), 443, 500, 2, 200f, 8, 1000) { _, _ -> }
            fail("expected IllegalStateException")
        } catch (_: IllegalStateException) {
            // expected
        }
    }

    @Test
    fun `speedScan parses normalized speeds from csv`() = runBlocking {
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
        assertEquals(12.5f / 1.048576f, results[0].speed!!, 0.01f)
        assertEquals(50f, results[0].avgMs!!, 0f)
    }

    @Test
    fun `speedScan throws IllegalStateException on non-zero exit`() = runBlocking {
        val engine = engine(exitCode = 1)
        try {
            engine.speedScan(listOf("1.1.1.1"), 443, "https://x/", 10, 50, 0f, 5) { _, _ -> }
            fail("expected IllegalStateException")
        } catch (_: IllegalStateException) {
            // expected
        }
    }

    @Test
    fun `latencyScan deletes stale csv before the process starts`() = runBlocking {
        File(tmp.root, "latency-443.csv").writeText("stale content")
        val engine = engine()
        engine.latencyScan(listOf("1.1.1.1"), 443, 500, 2, 200f, 8, 1000) { _, _ -> }
        assertEquals(false, outExistedAtProcessStart)
    }

    @Test
    fun `stale csv is not returned when process produces nothing`() = runBlocking {
        File(tmp.root, "latency-443.csv").writeText(
            "IP 地址,已发送,已接收,丢包率,平均延迟,下载速度(MB/s),地区码\n9.9.9.9,4,4,0.00,99.00,0.00,HKG\n",
        )
        writeCsv = false
        val engine = engine()
        val results = engine.latencyScan(listOf("1.1.1.1"), 443, 500, 2, 200f, 8, 1000) { _, _ -> }
        assertTrue(results.isEmpty())
    }

    @Test
    fun `heartbeat reports progress while process is silent`() = runBlocking {
        val engine = engine(runnerOverride = blockingFakeRunner())
        val progress = mutableListOf<Pair<Int, Int>>()
        val job = launch {
            try {
                engine.latencyScan(listOf("1.1.1.1", "1.1.1.2"), 443, 500, 2, 200f, 8, 1000) { d, t ->
                    progress.add(d to t)
                }
            } catch (_: Exception) {
                // cancellation after heartbeat is expected
            }
        }
        delay(3_000)
        job.cancel()
        job.join()
        assertTrue(progress.any { it.first == 0 && it.second == 2 })
    }
}
