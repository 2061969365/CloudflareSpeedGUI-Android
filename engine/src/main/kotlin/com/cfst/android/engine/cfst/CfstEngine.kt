package com.cfst.android.engine.cfst

import com.cfst.android.engine.ScanEngine
import com.cfst.android.engine.model.ScanResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

class CfstEngine(
    private val binaryPath: () -> File?,
    private val workDir: () -> File,
    private val runner: CfstProcessRunner = CfstProcessRunner(),
    private val env: Map<String, String> = emptyMap(),
    private val timeoutMs: Long = 120_000,
) : ScanEngine {

    override val name: String = "cfst"

    override suspend fun isAvailable(): Boolean {
        val bin = binaryPath() ?: return false
        if (!bin.exists()) return false
        return runCatching {
            val exit = runner.run(
                cmd = listOf(bin.absolutePath, "-h"),
                workDir = workDir(),
                env = env,
                timeoutMs = 3_000,
            )
            exit == 0
        }.getOrDefault(false)
    }

    override suspend fun latencyScan(
        ips: List<String>,
        port: Int,
        probeCount: Int,
        pingCount: Int,
        latencyLimit: Float,
        concurrency: Int,
        pingTimeoutMs: Int,
        onProgress: (done: Int, total: Int) -> Unit,
    ): List<ScanResult> {
        val bin = binaryPath() ?: throw IllegalStateException("cfst binary unavailable")
        val dir = workDir()
        val runId = newRunId()
        val ipFile = File(dir, "latency-ips-$runId.txt")
        val outCsv = File(dir, "latency-$port-$runId.csv")
        withContext(Dispatchers.IO) {
            ipFile.delete()
            outCsv.delete()
        }
        writeIps(ipFile, ips)
        val cmd = CfstBinary.latencyCmd(
            ipFile = ipFile.absolutePath,
            port = port,
            probeCount = probeCount,
            pingCount = pingCount,
            latencyLimit = latencyLimit,
            outCsv = outCsv.absolutePath,
            concurrency = concurrency,
        )
        val dynamicTimeout = CfstBinary.latencyTimeoutMs(
            ips.size,
            concurrency,
            timeoutMs,
            pingCount = pingCount,
        )
        val startTs = System.currentTimeMillis()
        val lastDone = AtomicInteger(0)
        val exit = coroutineScope {
            val heartbeat = launch {
                while (isActive) {
                    delay(HEARTBEAT_INTERVAL_MS)
                    onProgress(lastDone.get(), ips.size)
                }
            }
            try {
                runner.run(
                    cmd = listOf(bin.absolutePath) + cmd,
                    workDir = dir,
                    env = env,
                    timeoutMs = dynamicTimeout,
                    onProgress = { (done, total) ->
                        lastDone.set(done)
                        onProgress(done, total)
                    },
                )
            } finally {
                heartbeat.cancel()
            }
        }
        if (exit != 0) throw IllegalStateException("cfst latency scan failed with exit code $exit")
        val result = readCsv(outCsv, port, staleBefore = startTs).filter { it.avgMs != null }
        cleanupTempFiles(ipFile, outCsv)
        return result
    }

    override suspend fun speedScan(
        ips: List<String>,
        port: Int,
        url: String,
        downloadTime: Int,
        downloadCount: Int,
        speedLimit: Float,
        concurrency: Int,
        onProgress: (done: Int, total: Int) -> Unit,
    ): List<ScanResult> {
        val bin = binaryPath() ?: throw IllegalStateException("cfst binary unavailable")
        val dir = workDir()
        val runId = newRunId()
        val ipFile = File(dir, "speed-ips-$runId.txt")
        val outCsv = File(dir, "speed-$port-$runId.csv")
        withContext(Dispatchers.IO) {
            ipFile.delete()
            outCsv.delete()
        }
        writeIps(ipFile, ips)
        val cmd = CfstBinary.speedCmd(
            ipFile = ipFile.absolutePath,
            port = port,
            url = url,
            downloadTime = downloadTime,
            downloadCount = downloadCount,
            speedLimit = speedLimit,
            outCsv = outCsv.absolutePath,
            concurrency = concurrency,
        )
        val dynamicTimeout = CfstBinary.speedTimeoutMs(
            timeoutMs,
            downloadTime = downloadTime,
            downloadCount = downloadCount,
            survivors = ips.size,
        )
        val startTs = System.currentTimeMillis()
        val lastDone = AtomicInteger(0)
        val exit = coroutineScope {
            val heartbeat = launch {
                while (isActive) {
                    delay(HEARTBEAT_INTERVAL_MS)
                    onProgress(lastDone.get(), ips.size)
                }
            }
            try {
                runner.run(
                    cmd = listOf(bin.absolutePath) + cmd,
                    workDir = dir,
                    env = env,
                    timeoutMs = dynamicTimeout,
                    onProgress = { (done, total) ->
                        lastDone.set(done)
                        onProgress(done, total)
                    },
                )
            } finally {
                heartbeat.cancel()
            }
        }
        if (exit != 0) throw IllegalStateException("cfst speed scan failed with exit code $exit")
        val result = readCsv(outCsv, port, staleBefore = startTs).filter { it.speed != null && it.speed > 0f }
        cleanupTempFiles(ipFile, outCsv)
        return result
    }

    private suspend fun writeIps(file: File, ips: List<String>) = withContext(Dispatchers.IO) {
        file.writeText(ips.joinToString("\n"))
    }

    private suspend fun readCsv(file: File, port: Int, staleBefore: Long): List<ScanResult> =
        withContext(Dispatchers.IO) {
            if (!file.exists()) return@withContext emptyList()
            if (file.lastModified() < staleBefore) return@withContext emptyList()
            CfstCsvParser.parse(file.readText(), port, System.currentTimeMillis())
        }

    private suspend fun cleanupTempFiles(vararg files: File) = withContext(Dispatchers.IO) {
        files.forEach { runCatching { it.delete() } }
    }

    private fun newRunId(): String =
        "${System.nanoTime().toString(16)}-${RUN_SEQ.incrementAndGet()}"

    companion object {
        private const val HEARTBEAT_INTERVAL_MS = 2_000L
        private val RUN_SEQ = java.util.concurrent.atomic.AtomicInteger(0)
    }
}
