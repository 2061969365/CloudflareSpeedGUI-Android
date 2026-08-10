package com.cfst.android.engine

import java.math.BigInteger
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.ThreadLocalRandom

class IpNetwork(
    val original: String,
    val version: Int,
    val prefixLen: Int,
    val numAddresses: Long,
    private val network: BigInteger,
    private val total: BigInteger,
    private val usable: Long,
) {

    fun sampleHosts(n: Int): List<String> = when (version) {
        4 -> sampleHostsV4(n)
        6 -> sampleHostsV6(n)
        else -> emptyList()
    }

    fun expandAll(): Sequence<String> = when (version) {
        4 -> expandOffsets().map { hostAt(it) }
        6 -> expandV6All()
        else -> emptySequence()
    }

    private fun expandV6All(): Sequence<String> = when {
        prefixLen >= 128 -> sequenceOf(hostAt(0))
        prefixLen == 127 -> sequenceOf(hostAt(0), hostAt(1))
        else -> emptySequence()
    }

    fun hostAt(offset: Long): String = when (version) {
        4 -> toV4String(network.add(BigInteger.valueOf(offset)))
        6 -> toV6String(network.add(BigInteger.valueOf(offset)))
        else -> original
    }

    private fun expandOffsets(): Sequence<Long> = when {
        prefixLen >= 32 -> sequenceOf(0L)
        prefixLen == 31 -> sequenceOf(0L, 1L)
        else -> (1..usable).asSequence()
    }

    private fun sampleHostsV4(n: Int): List<String> {
        if (n <= 0) return emptyList()
        if (usable <= 0) return emptyList()
        if (prefixLen >= 32) return listOf(hostAt(0))
        if (prefixLen == 31) {
            return if (n >= 2) listOf(hostAt(0), hostAt(1))
            else listOf(hostAt(ThreadLocalRandom.current().nextLong(0L, 2L)))
        }
        val want = minOf(n.toLong(), usable)
        if (want == usable) {
            return (1..usable).map { hostAt(it) }
        }
        val seen = HashSet<Long>()
        val result = ArrayList<String>(want.toInt())
        while (result.size < want) {
            val r = ThreadLocalRandom.current().nextLong(1L, usable + 1)
            if (seen.add(r)) result.add(hostAt(r))
        }
        return result
    }

    private fun sampleHostsV6(n: Int): List<String> {
        if (n <= 0 || usable <= 0) return emptyList()
        if (prefixLen >= 128) return listOf(hostAt(0))
        if (prefixLen == 127) {
            return if (n >= 2) listOf(hostAt(0), hostAt(1))
            else listOf(hostAt(ThreadLocalRandom.current().nextLong(0L, 2L)))
        }
        val want = minOf(n.toLong(), usable)
        val seen = HashSet<BigInteger>()
        val result = ArrayList<BigInteger>(want.toInt())
        while (result.size < want) {
            val offset = BigInteger(128, ThreadLocalRandom.current())
                .mod(BigInteger.valueOf(usable)).add(BigInteger.ONE)
            if (seen.add(offset)) result.add(offset)
        }
        return result.map { toV6String(network.add(it)) }
    }

    private fun toV4String(addr: BigInteger): String {
        val v = addr.toLong()
        return "${(v ushr 24) and 0xFF}.${(v ushr 16) and 0xFF}.${(v ushr 8) and 0xFF}.${v and 0xFF}"
    }

    private fun toV6String(addr: BigInteger): String {
        val bytes = ByteArray(16) { index ->
            addr.shiftRight((15 - index) * 8).toByte()
        }
        val groups = (0 until 8).map { i ->
            val v = ((bytes[i * 2].toInt() and 0xFF) shl 8) or (bytes[i * 2 + 1].toInt() and 0xFF)
            v.toString(16)
        }
        var bestStart = -1
        var bestLen = 0
        var curStart = -1
        var curLen = 0
        for (i in 0 until 8) {
            if (groups[i] == "0") {
                if (curStart < 0) curStart = i
                curLen++
                if (curLen > bestLen) {
                    bestStart = curStart
                    bestLen = curLen
                }
            } else {
                curStart = -1
                curLen = 0
            }
        }
        return if (bestLen >= 2) {
            val head = groups.take(bestStart).joinToString(":")
            val tail = groups.drop(bestStart + bestLen).joinToString(":")
            when {
                head.isEmpty() && tail.isEmpty() -> "::"
                head.isEmpty() -> "::$tail"
                tail.isEmpty() -> "$head::"
                else -> "$head::$tail"
            }
        } else {
            groups.joinToString(":")
        }
    }
}

object IpParser {

    fun parseCidr(s: String): IpNetwork? {
        val trimmed = s.trim()
        if (trimmed.isEmpty()) return null
        val slash = trimmed.lastIndexOf('/')
        if (slash < 0) {
            if (looksLikeIpv6WithPort(trimmed)) return null
            parseV4WithPort(trimmed)?.let { return it }
            return parseV4(trimmed, 32) ?: parseV6(trimmed, 128)
        }
        val addr = trimmed.substring(0, slash).trim()
        val prefix = trimmed.substring(slash + 1).trim().toIntOrNull() ?: return null
        if (looksLikeIpv6WithPort(addr)) return null
        return parseV4(addr, prefix) ?: parseV6(addr, prefix)
    }

    fun parseRange(s: String): IpNetwork? {
        val t = s.trim()
        if ('-' !in t || '/' in t) return null
        val dash = t.lastIndexOf('-')
        if (dash == 0 || dash == t.length - 1) return null
        if (t.substring(0, dash).contains('-') || t.substring(dash + 1).contains('-')) return null
        val startRaw = t.substring(0, dash).trim()
        val endRaw = t.substring(dash + 1).trim()
        if (!isValidV4(startRaw) || !isValidV4(endRaw)) return null
        val lo = minOf(ipv4ToLong(startRaw), ipv4ToLong(endRaw))
        val hi = maxOf(ipv4ToLong(startRaw), ipv4ToLong(endRaw))
        val total = hi - lo + 1
        if (total <= 0 || total > 0x1_0000_0000L) return null
        return IpNetwork(
            original = "${longToIpv4(lo)}-${longToIpv4(hi)}",
            version = 4,
            prefixLen = 24,
            numAddresses = total,
            network = BigInteger.valueOf(lo - 1),
            total = BigInteger.valueOf(total),
            usable = total,
        )
    }

    fun stripV4Port(s: String): String? {
        val t = s.trim()
        if (':' !in t) return null
        val lastColon = t.lastIndexOf(':')
        if (lastColon == 0 || lastColon == t.length - 1) return null
        val addr = t.substring(0, lastColon)
        val port = t.substring(lastColon + 1)
        if (addr.contains(':') || port.isEmpty() || port.length > 5 || !port.all { it.isDigit() }) return null
        return if (isValidV4(addr)) addr else null
    }

    fun isValidV4(s: String): Boolean {
        val octets = s.split('.')
        if (octets.size != 4) return false
        for (o in octets) {
            if (o.isEmpty() || o.length > 3 || !o.all { it.isDigit() }) return false
            if (o.length > 1 && o.startsWith("0")) return false
            val n = o.toIntOrNull() ?: return false
            if (n > 255) return false
        }
        return true
    }

    fun looksLikeIpv6WithPort(s: String): Boolean {
        if (':' !in s) return false
        if (s.startsWith("[")) return false
        val lastColon = s.lastIndexOf(':')
        if (lastColon == 0 || lastColon == s.length - 1) return false
        val tail = s.substring(lastColon + 1)
        if (tail.isEmpty() || !tail.all { it.isDigit() }) return false
        val rest = s.substring(0, lastColon)
        if (':' !in rest) return false
        if (tail.length >= 4) return true
        return isParsableIpv6(rest)
    }

    private fun parseV4WithPort(s: String): IpNetwork? =
        stripV4Port(s)?.let { parseV4(it, 32) }

    private fun parseV4(addr: String, prefix: Int): IpNetwork? {
        if (prefix !in 0..32 || !isValidV4(addr)) return null
        val value = ipv4ToLong(addr)
        val mask = if (prefix == 0) 0L else ((-1L) shl (32 - prefix)) and 0xFFFFFFFFL
        val network = value and mask
        val total = 1L shl (32 - prefix)
        val usable = when {
            prefix == 32 -> 1L
            prefix == 31 -> 2L
            else -> (total - 2).coerceAtLeast(0L)
        }
        return IpNetwork(
            original = addr + "/" + prefix,
            version = 4,
            prefixLen = prefix,
            numAddresses = total,
            network = BigInteger.valueOf(network),
            total = BigInteger.valueOf(total),
            usable = usable,
        )
    }

    private fun parseV6(addr: String, prefix: Int): IpNetwork? {
        if (prefix !in 0..128 || ':' !in addr) return null
        val bytes = try {
            InetAddress.getByName(addr).address
        } catch (e: UnknownHostException) {
            return null
        }
        if (bytes.size != 16) return null
        val address = BigInteger(1, bytes)
        val upper = BigInteger.ONE.shiftLeft(128).subtract(BigInteger.ONE)
        val lower = BigInteger.ONE.shiftLeft(128 - prefix).subtract(BigInteger.ONE)
        val mask = upper.xor(lower)
        val network = address.and(mask)
        val total = BigInteger.ONE.shiftLeft(128 - prefix)
        val usable = when {
            prefix >= 128 -> BigInteger.ONE
            prefix == 127 -> BigInteger.TWO
            else -> total.subtract(BigInteger.TWO).max(BigInteger.ZERO)
        }
        return IpNetwork(
            original = addr + "/" + prefix,
            version = 6,
            prefixLen = prefix,
            numAddresses = total.min(BigInteger.valueOf(Long.MAX_VALUE)).toLong(),
            network = network,
            total = total,
            usable = usable.min(BigInteger.valueOf(Long.MAX_VALUE)).toLong(),
        )
    }

    private fun isParsableIpv6(s: String): Boolean {
        if (':' !in s) return false
        return try {
            InetAddress.getByName(s).address.size == 16
        } catch (e: UnknownHostException) {
            false
        }
    }

    private fun ipv4ToLong(s: String): Long {
        var v = 0L
        for (p in s.split('.')) v = (v shl 8) or p.toLong()
        return v
    }

    private fun longToIpv4(v: Long): String =
        "${(v ushr 24) and 0xFF}.${(v ushr 16) and 0xFF}.${(v ushr 8) and 0xFF}.${v and 0xFF}"
}
