package com.cfst.android.engine.cfst

import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import kotlin.coroutines.coroutineContext

class CfstProcessRunner(
    private val processRunner: (List<String>) -> Process = defaultRunner,
) {

    suspend fun run(
        cmd: List<String>,
        workDir: File,
        env: Map<String, String> = emptyMap(),
        timeoutMs: Long = 120_000,
        onProgress: (Pair<Int, Int>) -> Unit = {},
        onLine: (String) -> Unit = {},
    ): Int {
        val process = launchProcess(cmd, workDir, env)
        val job = coroutineContext[Job]
            ?: throw IllegalStateException("run must be called from a coroutine")
        val cancellationHandle = job.invokeOnCompletion { cause ->
            if (cause != null) destroyForciblyAndWait(process)
        }
        val reader = startReader(process, onProgress, onLine)
        return try {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (process.isAlive && System.currentTimeMillis() < deadline) {
                delay(POLL_INTERVAL_MS)
            }
            if (process.isAlive) {
                destroyForciblyAndWait(process)
                -1
            } else {
                process.exitValue()
            }
        } finally {
            cancellationHandle.dispose()
            // Kill the process BEFORE joining the reader: destroying closes the stdout pipe,
            // which unblocks the reader thread's read() so join returns quickly instead of
            // blocking for the full JOIN_TIMEOUT_MS on a live process.
            if (process.isAlive) {
                destroyForciblyAndWait(process)
            }
            reader.join(JOIN_TIMEOUT_MS)
        }
    }

    fun streamAndWait(
        process: Process,
        onProgress: (Pair<Int, Int>) -> Unit = {},
        onLine: (String) -> Unit = {},
    ): Int {
        val reader = startReader(process, onProgress, onLine)
        return try {
            process.waitFor()
        } finally {
            reader.join(JOIN_TIMEOUT_MS)
        }
    }

    private fun startReader(
        process: Process,
        onProgress: (Pair<Int, Int>) -> Unit,
        onLine: (String) -> Unit,
    ): Thread = Thread {
        streamIncrementally(process.inputStream, onProgress, onLine)
    }.apply {
        isDaemon = true
        start()
    }

    private fun streamIncrementally(
        input: InputStream,
        onProgress: (Pair<Int, Int>) -> Unit,
        onLine: (String) -> Unit,
    ) {
        // InputStreamReader carries UTF-8 decoder state across reads, so a multi-byte
        // character split across chunk boundaries decodes correctly (per-chunk String(bytes)
        // decoding would corrupt it into U+FFFD and break Chinese progress parsing).
        val reader = InputStreamReader(input, Charsets.UTF_8)
        val buffer = CharArray(READ_BUFFER_SIZE)
        val pending = StringBuilder()
        try {
            while (true) {
                val read = reader.read(buffer)
                if (read < 0) break
                pending.append(buffer, 0, read)
                emitCompleteSegments(pending, onProgress, onLine)
            }
            if (pending.isNotEmpty()) {
                emitSegment(pending.toString(), onProgress, onLine)
                pending.setLength(0)
            }
        } catch (_: IOException) {
        }
    }

    private fun emitCompleteSegments(
        pending: StringBuilder,
        onProgress: (Pair<Int, Int>) -> Unit,
        onLine: (String) -> Unit,
    ) {
        while (true) {
            val nl = pending.indexOf('\n')
            val cr = pending.indexOf('\r')
            val end = when {
                nl >= 0 && cr >= 0 -> minOf(nl, cr)
                nl >= 0 -> nl
                cr >= 0 -> cr
                else -> return
            }
            emitSegment(pending.substring(0, end), onProgress, onLine)
            pending.delete(0, end + 1)
        }
    }

    private fun emitSegment(
        segment: String,
        onProgress: (Pair<Int, Int>) -> Unit,
        onLine: (String) -> Unit,
    ) {
        val line = segment.trim()
        if (line.isEmpty()) return
        onLine(line)
        CfstProgressParser.parseProgress(line)?.let(onProgress)
    }

    private fun destroyForciblyAndWait(process: Process) {
        process.destroyForcibly()
        val deadline = System.currentTimeMillis() + DESTROY_WAIT_MS
        while (process.isAlive && System.currentTimeMillis() < deadline) {
            Thread.sleep(DESTROY_POLL_MS)
        }
    }

    private fun launchProcess(cmd: List<String>, workDir: File, env: Map<String, String>): Process {
        if (processRunner !== defaultRunner) {
            return processRunner(cmd)
        }
        val pb = ProcessBuilder(cmd)
        pb.directory(workDir)
        pb.redirectErrorStream(true)
        env.forEach { (k, v) -> pb.environment()[k] = v }
        return pb.start()
    }

    companion object {
        private val defaultRunner: (List<String>) -> Process = { cmd ->
            ProcessBuilder(cmd).redirectErrorStream(true).start()
        }
        private const val READ_BUFFER_SIZE = 8 * 1024
        private const val JOIN_TIMEOUT_MS = 5_000L
        private const val DESTROY_WAIT_MS = 5_000L
        private const val DESTROY_POLL_MS = 50L
        private const val POLL_INTERVAL_MS = 50L
    }
}
