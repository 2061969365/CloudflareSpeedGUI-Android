package com.cfst.android.engine.cfst

object CfstBinary {

    const val DEFAULT_SPEED_URL = "https://speed.hatexianyu.ccwu.cc/?bytes=209715200"

    const val DEFAULT_TIMEOUT_MS = 120_000L

    fun latencyTimeoutMs(ipCount: Int, concurrency: Int, timeoutMs: Long = DEFAULT_TIMEOUT_MS): Long =
        maxOf(timeoutMs, ipCount * 400L / maxOf(1, concurrency))

    fun speedTimeoutMs(timeoutMs: Long = DEFAULT_TIMEOUT_MS, downloadTime: Int, downloadCount: Int): Long =
        maxOf(timeoutMs, downloadCount.toLong() * downloadTime * 1000L + 30_000L)

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
        "-n", concurrency.toString(),
        "-t", pingCount.toString(),
        "-httping",
        "-dd",
        "-tl", latencyLimit.toInt().toString(),
        "-url", url,
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
            "-n", concurrency.toString(),
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
