package com.cfst.android.engine

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit

const val SPEED_TIMEOUT_MARGIN_MS = 5_000L

object SpeedProbe {

    private const val MIN_BYTES = 64L * 1024

    suspend fun measure(ip: String, port: Int, url: String, durationSec: Int, speedLimit: Float): Float? =
        withContext(Dispatchers.IO) {
            if (durationSec <= 0) return@withContext null
            val timeoutSec = durationSec + SPEED_TIMEOUT_MARGIN_MS / 1000
            val client = OkHttpClient.Builder()
                .connectTimeout(timeoutSec, TimeUnit.SECONDS)
                .readTimeout(timeoutSec, TimeUnit.SECONDS)
                .proxy(Proxy.NO_PROXY)
                .dns(object : Dns {
                    override fun lookup(hostname: String): List<InetAddress> =
                        listOf(InetAddress.getByName(ip))
                })
                .build()
            try {
                val request = Request.Builder()
                    .url(url)
                    .let { builder ->
                        val u = builder.build().url
                        builder.url(u.newBuilder().port(port).build())
                    }
                    .build()
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
                    if (totalBytes == 0L || totalBytes < MIN_BYTES) return@withContext null
                    val elapsedSec = (System.nanoTime() - start) / 1_000_000_000.0
                    val mbps = totalBytes / 1_000_000f / elapsedSec.toFloat()
                    if (mbps.isFinite().not()) return@withContext null
                    if (speedLimit > 0 && mbps < speedLimit) null else mbps
                }
            } catch (_: Exception) {
                null
            } finally {
                runCatching { client.connectionPool.evictAll() }
            }
        }
}
