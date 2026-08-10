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
        return if (slash < 0) {
            parseV4(trimmed, 32) ?: parseV6(trimmed, 128)
        } else {
            val addr = trimmed.substring(0, slash).trim()
            val prefix = trimmed.substring(slash + 1).trim().toIntOrNull() ?: return null
            parseV4(addr, prefix) ?: parseV6(addr, prefix)
        }
    }

    private fun parseV4(addr: String, prefix: Int): IpNetwork? {
        if (prefix !in 0..32) return null
        val octets = addr.split('.')
        if (octets.size != 4) return null
        var value = 0L
        for (o in octets) {
            if (o.isEmpty() || o.length > 3 || !o.all { it.isDigit() }) return null
            if (o.length > 1 && o.startsWith("0")) return null
            val n = o.toInt()
            if (n > 255) return null
            value = (value shl 8) or n.toLong()
        }
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
}
