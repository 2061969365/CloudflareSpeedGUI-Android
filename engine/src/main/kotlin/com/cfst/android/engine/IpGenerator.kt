package com.cfst.android.engine

object IpGenerator {

    fun generate(
        cidrs: List<String>,
        maxIps: Int,
        fullScan: Boolean,
        progress: (Int) -> Unit,
    ): List<String> {
        val lines = cidrs.map { it.trim() }.filter { it.isNotEmpty() }
        val networks = mutableListOf<Pair<String, IpNetwork>>()
        val bareIps = mutableListOf<String>()
        for (line in lines) {
            if ('/' in line) {
                IpParser.parseCidr(line)?.let { networks += line to it }
            } else if (isValidIp(line)) {
                bareIps += line
            }
        }

        val total = networks.size
        progress(0)

        val out = mutableListOf<String>()
        out += bareIps

        if (fullScan) {
            val seen = linkedSetOf<String>()
            seen += bareIps
            val MAX_FULLSCAN_TOTAL = 2_000_000
            networks.forEachIndexed { i, (line, net) ->
                if (seen.size < MAX_FULLSCAN_TOTAL) {
                    if (net.version == 4) {
                        net.expandAll().forEach {
                            if (seen.size >= MAX_FULLSCAN_TOTAL) return@forEachIndexed
                            seen += it
                        }
                    } else {
                        seen += line
                    }
                }
                report(progress, total, i + 1)
            }
            progress(100)
            return seen.take(MAX_FULLSCAN_TOTAL)
        } else if (maxIps == 0) {
            networks.forEachIndexed { i, (_, net) ->
                out += when {
                    net.version == 6 -> net.sampleHosts(1)
                    net.prefixLen >= 24 -> net.sampleHosts(1)
                    else -> oneHostPer24(net)
                }
                report(progress, total, i + 1)
            }
        } else if (total > 0) {
            val base = maxIps / total
            val remainder = maxIps % total
            networks.forEachIndexed { i, (_, net) ->
                val quota = base + if (i < remainder) 1 else 0
                if (quota > 0) out += net.sampleHosts(quota)
                report(progress, total, i + 1)
            }
        }

        progress(100)
        return out.distinct()
    }

    private fun oneHostPer24(net: IpNetwork): List<String> {
        val subnets = 1 shl (24 - net.prefixLen)
        return (0 until subnets).map { k -> net.hostAt(k * 256L + 1L) }
    }

    private fun report(progress: (Int) -> Unit, total: Int, done: Int) {
        if (total > 0) progress(100 * done / total)
    }

    private fun isValidIp(s: String): Boolean {
        if (':' in s) {
            val colonCount = s.count { it == ':' }
            if (colonCount !in 2..7) return false
            return s.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' || it == ':' || it == '.' }
        }
        val parts = s.split('.')
        if (parts.size != 4) return false
        return parts.all { p ->
            p.isNotEmpty() && p.length <= 3 && p.all { it.isDigit() } && p.toInt() <= 255
        }
    }
}
