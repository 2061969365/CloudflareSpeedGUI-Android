package com.cfst.android.engine

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetAddress

object SpeedProbe {

    suspend fun measure(ip: String, port: Int, url: String, durationSec: Int, speedLimit: Float): Float? =
        withContext(Dispatchers.IO) {
            val client = OkHttpClient.Builder()
                .dns(object : Dns {
                    override fun lookup(hostname: String): List<InetAddress> =
                        listOf(InetAddress.getByName(ip))
                })
                .build()
            try {
                val request = Request.Builder().url(url).build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        return@withContext null
                    }
                    val body = response.body ?: return@withContext null
                    val buffer = ByteArray(128 * 1024)
                    val source = body.source()
                    val start = System.nanoTime()
                    val windowNs = durationSec.toLong() * 1_000_000_000L
                    var totalBytes = 0L
                    while (true) {
                        if (System.nanoTime() - start >= windowNs) break
                        val read = source.read(buffer, 0, buffer.size)
                        if (read == -1) break
                        totalBytes += read
                    }
                    val elapsedSec = (System.nanoTime() - start) / 1_000_000_000.0
                    val mbps = totalBytes / 1_000_000f / maxOf(elapsedSec.toFloat(), 0.1f)
                    if (speedLimit > 0 && mbps < speedLimit) null else mbps
                }
            } catch (_: Exception) {
                null
            } finally {
                runCatching { client.connectionPool.evictAll() }
            }
        }
}
