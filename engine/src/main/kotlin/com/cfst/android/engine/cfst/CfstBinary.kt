package com.cfst.android.engine.cfst

object CfstBinary {

    const val DEFAULT_SPEED_URL = "https://speed.hatexianyu.ccwu.cc/?bytes=209715200"

    fun latencyCmd(
        ipFile: String,
        port: Int,
        probeCount: Int,
        pingCount: Int,
        latencyLimit: Float,
        outCsv: String,
        url: String = DEFAULT_SPEED_URL,
    ): List<String> = listOf(
        "-f", ipFile,
        "-tp", port.toString(),
        "-n", probeCount.toString(),
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
    ): List<String> {
        val args = mutableListOf(
            "-f", ipFile,
            "-tp", port.toString(),
            "-n", "200",
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
