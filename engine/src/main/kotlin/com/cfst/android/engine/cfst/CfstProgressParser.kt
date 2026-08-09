package com.cfst.android.engine.cfst

object CfstProgressParser {

    private val PROGRESS_RE =
        Regex("(?:进度|Progress|ETA|剩余).*?(\\d+)\\s*/\\s*(\\d+)", RegexOption.IGNORE_CASE)
    private val BARE_RE = Regex("^(\\d+)\\s*/\\s*(\\d+)\\s")

    fun parseProgress(line: String): Pair<Int, Int>? {
        val trimmed = line.trimEnd('\r')
        PROGRESS_RE.find(trimmed)?.let { m ->
            return m.groupValues[1].toInt() to m.groupValues[2].toInt()
        }
        BARE_RE.find(trimmed)?.let { m ->
            return m.groupValues[1].toInt() to m.groupValues[2].toInt()
        }
        return null
    }

    fun percent(done: Int, total: Int): Int {
        if (total <= 0) return 0
        return minOf(100, done * 100 / total)
    }

    fun computeEtaMs(elapsedMs: Long, done: Int, total: Int): Long? {
        if (done < 1) return null
        return elapsedMs * (total - done) / done
    }
}
