package com.cfst.android.engine.cfst

import com.cfst.android.engine.ScanEngine
import com.cfst.android.engine.model.ScanResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

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
                cmd = listOf(bin.absolutePath, "-v"),
                workDir = workDir(),
                env = env,
                timeoutMs = 10_000,
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
        onProgress: (done: Int, total: Int) -> Unit,
    ): List<ScanResult> {
        val bin = binaryPath() ?: throw IllegalStateException("cfst binary unavailable")
        val dir = workDir()
        val ipFile = File(dir, "latency-ips.txt")
        val outCsv = File(dir, "latency-$port.csv")
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
        val dynamicTimeout = CfstBinary.latencyTimeoutMs(ips.size, concurrency, timeoutMs)
        val exit = runner.run(
            cmd = listOf(bin.absolutePath) + cmd,
            workDir = dir,
            env = env,
            timeoutMs = dynamicTimeout,
            onProgress = { (done, total) -> onProgress(done, total) },
        )
        if (exit != 0) return emptyList()
        return readCsv(outCsv, port).filter { it.avgMs != null }
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
        val ipFile = File(dir, "speed-ips.txt")
        val outCsv = File(dir, "speed-$port.csv")
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
        val dynamicTimeout = CfstBinary.speedTimeoutMs(timeoutMs, downloadTime = downloadTime, downloadCount = downloadCount)
        val exit = runner.run(
            cmd = listOf(bin.absolutePath) + cmd,
            workDir = dir,
            env = env,
            timeoutMs = dynamicTimeout,
            onProgress = { (done, total) -> onProgress(done, total) },
        )
        if (exit != 0) return emptyList()
        return readCsv(outCsv, port).filter { it.speed != null && it.speed > 0f }
    }

    private suspend fun writeIps(file: File, ips: List<String>) = withContext(Dispatchers.IO) {
        file.writeText(ips.joinToString("\n"))
    }

    private suspend fun readCsv(file: File, port: Int): List<ScanResult> = withContext(Dispatchers.IO) {
        if (!file.exists()) return@withContext emptyList()
        CfstCsvParser.parse(file.readText(), port, System.currentTimeMillis())
    }
}
