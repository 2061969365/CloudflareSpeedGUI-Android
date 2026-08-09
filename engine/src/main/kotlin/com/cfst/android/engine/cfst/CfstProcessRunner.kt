package com.cfst.android.engine.cfst

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStreamReader

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
        val exit = withTimeoutOrNull(timeoutMs) {
            withContext(Dispatchers.IO) {
                streamAndWait(process, onProgress, onLine)
            }
        }
        if (exit == null) {
            process.destroyForcibly()
            return -1
        }
        return exit
    }

    fun streamAndWait(
        process: Process,
        onProgress: (Pair<Int, Int>) -> Unit = {},
        onLine: (String) -> Unit = {},
    ): Int {
        val reader = BufferedReader(InputStreamReader(process.inputStream))
        val thread = Thread {
            try {
                while (true) {
                    val raw = reader.readLine() ?: break
                    val line = raw.trimEnd('\r')
                    onLine(line)
                    CfstProgressParser.parseProgress(line)?.let(onProgress)
                }
            } catch (_: IOException) {
            }
        }.apply { isDaemon = true }
        thread.start()
        try {
            return process.waitFor()
        } finally {
            thread.join()
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
    }
}
