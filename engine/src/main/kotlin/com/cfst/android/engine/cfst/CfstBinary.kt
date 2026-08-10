package com.cfst.android.engine.cfst

object CfstBinary {

    const val DEFAULT_SPEED_URL = "https://speed.hatexianyu.ccwu.cc/?bytes=209715200"

    const val DEFAULT_TIMEOUT_MS = 120_000L

    const val MAX_CONCURRENCY = 1000

    fun latencyTimeoutMs(
        ipCount: Int,
        concurrency: Int,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        pingCount: Int = 0,
    ): Long {
        val c = maxOf(1, concurrency)
        val estimatedMs = ipCount * 400L / c + pingCount * 2000L / c
        return maxOf(timeoutMs, estimatedMs + 30_000L)
    }

    fun speedTimeoutMs(
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        downloadTime: Int,
        downloadCount: Int,
        survivors: Int = 0,
    ): Long {
        val perHostMs = downloadCount.toLong() * downloadTime * 1000L
        return maxOf(timeoutMs, perHostMs + survivors.toLong() * downloadTime * 1000L + 30_000L)
    }

    fun latencyCmd(
        ipFile: String,
        port: Int,
        probeCount: Int,
        pingCount: Int,
        latencyLimit: Float,
        outCsv: String,
        concurrency: Int = probeCount,
        url: String = DEFAULT_SPEED_URL,
    ): List<String> = listOf(
        "-f", ipFile,
        "-tp", port.toString(),
        "-n", minOf(MAX_CONCURRENCY, maxOf(1, concurrency)).toString(),
        "-t", pingCount.toString(),
        "-httping",
        // -dd locks flag/output semantics to CloudflareSpeedTest v2.3.5; newer versions may change arg handling.
        "-dd",
        "-tl", latencyLimit.toInt().toString(),
        "-url", if (url.isBlank()) DEFAULT_SPEED_URL else url,
        "-p", "0",
        "-o", outCsv,
    )

    fun speedCmd(
        ipFile: String,
        port: Int,
        url: String,
        downloadTime: Int,
        downloadCount: Int,
        speedLimit: Float,
        outCsv: String,
        concurrency: Int = 200,
    ): List<String> {
        val args = mutableListOf(
            "-f", ipFile,
            "-tp", port.toString(),
            "-n", minOf(MAX_CONCURRENCY, maxOf(1, concurrency)).toString(),
            "-t", "4",
            "-httping",
            "-dt", downloadTime.toString(),
            "-dn", downloadCount.toString(),
            "-p", "0",
            "-o", outCsv,
        )
        if (url.isNotBlank()) {
            args += listOf("-url", url)
        }
        if (speedLimit > 0f) {
            args += listOf("-sl", speedLimit.toString())
        }
        return args
    }
}
