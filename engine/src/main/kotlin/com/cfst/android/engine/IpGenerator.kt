package com.cfst.android.engine

import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.ThreadLocalRandom

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
                continue
            }
            val range = IpParser.parseRange(line)
            if (range != null) {
                networks += line to range
                continue
            }
            val bare = IpParser.stripV4Port(line) ?: line
            if (isValidIp(bare)) bareIps += bare
        }

        val total = networks.size
        progress(0)

        if (fullScan) {
            val MAX_FULLSCAN_TOTAL = 2_000_000
            val effectiveMax = if (maxIps > 0) minOf(maxIps, MAX_FULLSCAN_TOTAL) else MAX_FULLSCAN_TOTAL
            val seen = linkedSetOf<String>()
            seen += bareIps
            networks.forEachIndexed { i, (_, net) ->
                if (seen.size < effectiveMax) {
                    if (net.version == 4) {
                        net.expandAll().forEach {
                            if (seen.size >= effectiveMax) return@forEachIndexed
                            seen += it
                        }
                    } else {
                        net.sampleHosts(1).forEach {
                            if (seen.size >= effectiveMax) return@forEachIndexed
                            seen += it
                        }
                    }
                }
                report(progress, total, i + 1)
            }
            progress(100)
            return seen.take(effectiveMax)
        }

        val out = mutableListOf<String>()
        if (maxIps == 0) {
            out += bareIps
            networks.forEachIndexed { i, (_, net) ->
                out += when {
                    net.version == 6 -> net.sampleHosts(1)
                    net.prefixLen >= 24 -> net.sampleHosts(1)
                    else -> oneHostPer24(net)
                }
                report(progress, total, i + 1)
            }
        } else if (maxIps > 0) {
            val budget = maxIps
            out += bareIps.take(budget)
            val remaining = (budget - out.size).coerceAtLeast(0)
            if (total > 0) {
                val base = remaining / total
                val remainder = remaining % total
                networks.forEachIndexed { i, (_, net) ->
                    val quota = base + if (i < remainder) 1 else 0
                    if (quota > 0) out += net.sampleHosts(quota)
                    report(progress, total, i + 1)
                }
            }
        }

        progress(100)
        return out.distinct()
    }

    private fun oneHostPer24(net: IpNetwork): List<String> {
        val MAX_SUBNETS = 200_000L
        val subnets = (1L shl (24 - net.prefixLen)).coerceAtMost(MAX_SUBNETS)
        val rng = ThreadLocalRandom.current()
        return (0 until subnets).asSequence()
            .map { k -> net.hostAt(k * 256L + rng.nextLong(1L, 255L)) }
            .toList()
    }

    private fun report(progress: (Int) -> Unit, total: Int, done: Int) {
        if (total > 0) progress(100 * done / total)
    }

    private fun isValidIp(s: String): Boolean {
        if (':' in s) {
            if (s == "::") return false
            if (IpParser.looksLikeIpv6WithPort(s)) return false
            return try {
                val addr = InetAddress.getByName(s)
                addr.address.size == 16 && addr.address.any { it != 0.toByte() }
            } catch (e: UnknownHostException) {
                false
            }
        }
        val parts = s.split('.')
        if (parts.size != 4) return false
        return parts.all { p ->
            p.isNotEmpty() && p.length <= 3 && p.all { it.isDigit() } &&
                (p.length == 1 || p[0] != '0') && p.toInt() <= 255
        }
    }
}
