package com.cfst.android.engine

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

class SpeedProbeTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun streamingResponse(totalBytes: Long, throttleBytesPerSec: Long): MockResponse {
        val body = Buffer()
        val chunk = ByteArray(64 * 1024)
        var remaining = totalBytes
        while (remaining > 0) {
            val n = minOf(chunk.size.toLong(), remaining).toInt()
            body.write(chunk, 0, n)
            remaining -= n
        }
        return MockResponse()
            .setResponseCode(200)
            .setBody(body)
            .throttleBody(throttleBytesPerSec, 1, TimeUnit.SECONDS)
    }

    @Test
    fun large_body_measures_positive_speed() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                streamingResponse(totalBytes = 12L * 1024 * 1024, throttleBytesPerSec = 8L * 1024 * 1024)
        }
        val url = server.url("/__down").toString()
        val mbps = SpeedProbe.measure("127.0.0.1", server.port, url, durationSec = 1, speedLimit = 0f)

        assertNotNull(mbps)
        assertTrue(mbps!! > 0f)
    }

    @Test
    fun speed_below_limit_returns_null() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                streamingResponse(totalBytes = 12L * 1024 * 1024, throttleBytesPerSec = 8L * 1024 * 1024)
        }
        val url = server.url("/__down").toString()
        val mbps = SpeedProbe.measure("127.0.0.1", server.port, url, durationSec = 1, speedLimit = 99999f)

        assertNull(mbps)
    }

    @Test
    fun http_500_returns_null() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500))
        val url = server.url("/down").toString()
        val mbps = SpeedProbe.measure("127.0.0.1", server.port, url, durationSec = 1, speedLimit = 0f)

        assertNull(mbps)
    }

    @Test
    fun zero_duration_returns_null_without_probing() = runBlocking {
        val url = server.url("/__down").toString()
        val mbps = SpeedProbe.measure("127.0.0.1", server.port, url, durationSec = 0, speedLimit = 0f)

        assertNull(mbps)
    }

    @Test
    fun small_body_returns_null() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("tiny response"))
        val url = server.url("/__down").toString()
        val mbps = SpeedProbe.measure("127.0.0.1", server.port, url, durationSec = 1, speedLimit = 0f)

        assertNull(mbps)
    }

    @Test
    fun read_timeout_configuration_applies() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val url = server.url("/__down").toString()
        val start = System.nanoTime()
        val mbps = withTimeout(30_000) {
            SpeedProbe.measure("127.0.0.1", server.port, url, durationSec = 1, speedLimit = 0f)
        }
        val elapsedSec = (System.nanoTime() - start) / 1_000_000_000.0

        assertNull(mbps)
        assertTrue("expected explicit read timeout to bound the call", elapsedSec < 25)
    }
}
