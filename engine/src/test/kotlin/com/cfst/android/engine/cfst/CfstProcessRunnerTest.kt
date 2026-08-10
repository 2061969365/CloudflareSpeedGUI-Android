package com.cfst.android.engine.cfst

import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.atomic.AtomicBoolean

class CfstProcessRunnerTest {

    @get:Rule
    val tmp: TemporaryFolder = TemporaryFolder()

    private val isWindows = System.getProperty("os.name").lowercase().contains("win")

    private fun blockingCommand(seconds: Int): List<String> =
        if (isWindows) {
            listOf("powershell", "-NoProfile", "-Command", "Start-Sleep -Seconds $seconds")
        } else {
            listOf("sleep", seconds.toString())
        }

    private class ProcessHolder {
        var process: Process? = null
    }

    @Test
    fun `timed out process is force destroyed and returns -1`() = runBlocking {
        val holder = ProcessHolder()
        val runner = CfstProcessRunner(processRunner = { cmd ->
            holder.process = ProcessBuilder(cmd).redirectErrorStream(true).start()
            holder.process!!
        })
        val exit = runner.run(cmd = blockingCommand(60), workDir = tmp.root, timeoutMs = 3_000)
        assertEquals(-1, exit)
        assertFalse(holder.process!!.isAlive)
    }

    @Test
    fun `cancelling the coroutine destroys the child process`() = runBlocking {
        val holder = ProcessHolder()
        val runner = CfstProcessRunner(processRunner = { cmd ->
            holder.process = ProcessBuilder(cmd).redirectErrorStream(true).start()
            holder.process!!
        })
        val job = launch {
            runner.run(cmd = blockingCommand(60), workDir = tmp.root, timeoutMs = 120_000)
        }
        delay(1_500)
        job.cancel()
        job.join()
        assertFalse(holder.process!!.isAlive)
    }

    @Test
    fun `cancel destroys the process quickly instead of joining reader for 5s`() = runBlocking {
        val holder = ProcessHolder()
        val runner = CfstProcessRunner(processRunner = { cmd ->
            holder.process = ProcessBuilder(cmd).redirectErrorStream(true).start()
            holder.process!!
        })
        val started = System.currentTimeMillis()
        val job = launch {
            runner.run(cmd = blockingCommand(60), workDir = tmp.root, timeoutMs = 120_000)
        }
        delay(1_500)
        job.cancel()
        job.join()
        val elapsed = System.currentTimeMillis() - started
        assertFalse(holder.process!!.isAlive)
        assertTrue(
            "run() should return shortly after cancel (took ${elapsed}ms)",
            elapsed < 4_000,
        )
    }

    @Test
    fun `multi-byte chars split across read boundaries are decoded without corruption`() = runBlocking {
        // "进度 12 / 456\r\n" — the UTF-8 bytes of 进 (3 bytes) are fed one byte per read()
        // so no single chunk contains a whole character. InputStreamReader must keep decoder
        // state across chunks for the progress regex to still match.
        val bytes = "进度 12 / 456\r\n".toByteArray(Charsets.UTF_8)
        val alive = AtomicBoolean(true)
        val input = object : InputStream() {
            private var offset = 0
            override fun read(): Int =
                if (offset < bytes.size) bytes[offset++].toInt() else -1.also { alive.set(false) }

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (offset >= bytes.size) {
                    alive.set(false)
                    return -1
                }
                b[off] = bytes[offset++]
                return 1
            }
        }
        val progress = mutableListOf<Pair<Int, Int>>()
        val runner = CfstProcessRunner(
            processRunner = {
                object : Process() {
                    override fun getOutputStream() = ByteArrayOutputStream()
                    override fun getInputStream() = input
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
            },
        )
        val exit = runner.run(
            cmd = listOf("fake"),
            workDir = tmp.root,
            timeoutMs = 5_000,
            onProgress = { p -> progress.add(p) },
        )
        assertEquals(0, exit)
        assertEquals(listOf(12 to 456), progress)
    }

    @Test
    fun `cr separated progress lines are parsed incrementally`() = runBlocking {
        val input = PipedInputStream()
        val out = PipedOutputStream(input)
        val alive = AtomicBoolean(true)
        val progress = mutableListOf<Pair<Int, Int>>()
        val runner = CfstProcessRunner(
            processRunner = {
                object : Process() {
                    override fun getOutputStream() = ByteArrayOutputStream()
                    override fun getInputStream() = input
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
            },
        )
        val writer = Thread {
            try {
                for (i in 1..5) {
                    out.write("$i / 5".toByteArray())
                    out.write('\r'.code)
                    out.flush()
                    Thread.sleep(100)
                }
            } catch (_: IOException) {
            } finally {
                try {
                    out.close()
                } catch (_: IOException) {
                }
                alive.set(false)
            }
        }.apply { isDaemon = true }
        writer.start()

        val exit = runner.run(
            cmd = listOf("fake"),
            workDir = tmp.root,
            timeoutMs = 5_000,
            onProgress = { p -> progress.add(p) },
        )

        assertEquals(0, exit)
        assertEquals(listOf(1 to 5, 2 to 5, 3 to 5, 4 to 5, 5 to 5), progress)
    }

    @Test
    fun `streamAndWait joins daemon reader thread`() {
        val runner = CfstProcessRunner()
        val alive = AtomicBoolean(false)
        val process = object : Process() {
            override fun getOutputStream() = ByteArrayOutputStream()
            override fun getInputStream() = ByteArrayInputStream("12 / 456 ... 可用: 8\n".toByteArray())
            override fun getErrorStream() = ByteArrayInputStream(ByteArray(0))
            override fun isAlive(): Boolean = alive.get()
            override fun waitFor(): Int = 0
            override fun exitValue(): Int = 0
            override fun destroy() {}
            override fun destroyForcibly(): Process = this
        }
        val progress = mutableListOf<Pair<Int, Int>>()
        val exit = runner.streamAndWait(process, onProgress = { p -> progress.add(p) })
        assertEquals(0, exit)
        assertEquals(listOf(12 to 456), progress)
    }
}
