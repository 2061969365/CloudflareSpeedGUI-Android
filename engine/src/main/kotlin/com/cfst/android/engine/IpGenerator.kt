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
            networks.forEachIndexed { i, (line, net) ->
                if (net.version == 4) out += net.expandAll().toList() else out += line
                report(progress, total, i + 1)
            }
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
        return net.expandAll()
            .filterIndexed { index, _ -> index % 256 == 0 }
            .take(subnets)
            .toList()
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
