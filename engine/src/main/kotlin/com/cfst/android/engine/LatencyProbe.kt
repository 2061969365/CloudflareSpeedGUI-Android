package com.cfst.android.engine

import com.cfst.android.engine.model.LatencyStats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

object LatencyProbe {

    suspend fun probe(ip: String, port: Int, pingCount: Int, timeoutMs: Int): LatencyStats =
        withContext(Dispatchers.IO) {
            val rtts = mutableListOf<Float>()
            repeat(pingCount) {
                val socket = Socket()
                try {
                    val start = System.nanoTime()
                    socket.connect(InetSocketAddress(ip, port), timeoutMs)
                    val end = System.nanoTime()
                    rtts += (end - start) / 1_000_000f
                } catch (_: Exception) {
                    // connect failure or timeout counts as loss
                } finally {
                    runCatching { socket.close() }
                }
            }
            val received = rtts.size
            LatencyStats(
                sent = pingCount,
                received = received,
                lossPct = (pingCount - received) * 100f / pingCount,
                avgMs = if (rtts.isNotEmpty()) rtts.average().toFloat() else null,
                minMs = rtts.minOrNull(),
                maxMs = rtts.maxOrNull(),
            )
        }
}
