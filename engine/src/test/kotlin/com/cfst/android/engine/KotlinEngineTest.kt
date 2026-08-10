package com.cfst.android.engine

import com.cfst.android.engine.model.LatencyStats
import com.cfst.android.engine.model.ScanResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class KotlinEngineTest {

    private fun latencyProbeBlocking(counters: Counters) =
        { _: String, _: Int, _: Int, _: Int ->
            counters.enter()
            try {
                Thread.sleep(10)
            } finally {
                counters.exit()
            }
            LatencyStats(2, 2, 0f, 5f, 4f, 6f)
        }

    private fun speedProbeBlocking(counters: Counters) =
        { _: String, _: Int, _: String, _: Int, _: Float ->
            counters.enter()
            try {
                Thread.sleep(10)
            } finally {
                counters.exit()
            }
            2f
        }

    @Test
    fun latencyScan_respects_concurrency_limit() = runTest {
        val ips = (1..40).map { "10.0.0.$it" }
        val counters = Counters()
        val engine = KotlinEngine(
            latencyProbe = latencyProbeBlocking(counters),
            speedProbe = { _, _, _, _, _ -> 1f },
            dispatcher = Dispatchers.Default,
        )
        engine.latencyScan(
            ips = ips,
            port = 443,
            probeCount = ips.size,
            pingCount = 2,
            latencyLimit = 100f,
            concurrency = 4,
            pingTimeoutMs = 2000,
            onProgress = { _, _ -> },
        )
        assertTrue("peak concurrency ${counters.peak()} must be > 1", counters.peak() > 1)
        assertTrue("peak concurrency ${counters.peak()} must not exceed cap 4", counters.peak() <= 4)
    }

    @Test
    fun speedScan_respects_concurrency_limit() = runTest {
        val ips = (1..30).map { "10.0.0.$it" }
        val counters = Counters()
        val engine = KotlinEngine(
            latencyProbe = { _, _, _, _ -> LatencyStats(2, 2, 0f, 5f, 5f, 5f) },
            speedProbe = speedProbeBlocking(counters),
            dispatcher = Dispatchers.Default,
        )
        engine.speedScan(
            ips = ips,
            port = 443,
            url = "https://example.com/file",
            downloadTime = 2,
            downloadCount = ips.size,
            speedLimit = 0f,
            concurrency = 3,
            onProgress = { _, _ -> },
        )
        assertTrue("peak concurrency ${counters.peak()} must be > 1", counters.peak() > 1)
        assertTrue("peak concurrency ${counters.peak()} must not exceed cap 3", counters.peak() <= 3)
    }

    @Test
    fun speeds_collect_all_ips() = runTest {
        val ips = listOf("10.0.0.1", "10.0.0.2", "10.0.0.3")
        val engine = KotlinEngine(
            latencyProbe = { _, _, _, _ -> LatencyStats(2, 2, 0f, 5f, 5f, 5f) },
            speedProbe = { _, _, _, _, _ -> 2f },
            dispatcher = Dispatchers.Default,
        )
        val results = engine.speedScan(
            ips = ips,
            port = 443,
            url = "https://example.com/file",
            downloadTime = 2,
            downloadCount = ips.size,
            speedLimit = 0f,
            concurrency = 2,
            onProgress = { _, _ -> },
        )
        assertEquals(ips.size, results.size)
        assertEquals(ips.toSet(), results.map { it.ip }.toSet())
        assertTrue(results.all { it.speed != null })
    }

    @Test
    fun latencyScan_zero_limit_keeps_all() = runTest {
        val ips = listOf("10.0.0.1", "10.0.0.2")
        val engine = KotlinEngine(
            latencyProbe = { _, _, _, _ -> LatencyStats(2, 2, 0f, 150f, 150f, 150f) },
            speedProbe = { _, _, _, _, _ -> 1f },
        )
        val results = engine.latencyScan(
            ips = ips,
            port = 443,
            probeCount = 2,
            pingCount = 2,
            latencyLimit = 0f,
            concurrency = 2,
            pingTimeoutMs = 2000,
            onProgress = { _, _ -> },
        )
        assertEquals(2, results.size)
    }

    @Test
    fun latencyScan_forwards_pingTimeoutMs() = runTest {
        var capturedTimeout = -1
        val engine = KotlinEngine(
            latencyProbe = { _, _, _, timeoutMs ->
                capturedTimeout = timeoutMs
                LatencyStats(2, 2, 0f, 5f, 5f, 5f)
            },
            speedProbe = { _, _, _, _, _ -> 1f },
        )
        engine.latencyScan(
            ips = listOf("10.0.0.1"),
            port = 443,
            probeCount = 2,
            pingCount = 2,
            latencyLimit = 100f,
            concurrency = 1,
            pingTimeoutMs = 4321,
            onProgress = { _, _ -> },
        )
        assertEquals(4321, capturedTimeout)
    }

    @Test
    fun latencyScan_cancel_returns_promptly_and_stops_stale_progress() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val latencyProbe = { _: String, _: Int, _: Int, _: Int ->
            started.complete(Unit)
            Thread.sleep(300)
            LatencyStats(1, 1, 0f, 5f, 5f, 5f)
        }
        val engine = KotlinEngine(
            latencyProbe = latencyProbe,
            speedProbe = { _, _, _, _, _ -> 1f },
            dispatcher = Dispatchers.Default,
        )
        val cancelled = AtomicBoolean(false)
        val progressAfterCancel = AtomicInteger(0)
        val ips = (1..5).map { "10.0.0.$it" }
        val job = async {
            engine.latencyScan(
                ips = ips,
                port = 443,
                probeCount = ips.size,
                pingCount = 2,
                latencyLimit = 100f,
                concurrency = 2,
                pingTimeoutMs = 2000,
                onProgress = { _, _ ->
                    if (cancelled.get()) progressAfterCancel.incrementAndGet()
                },
            )
        }

        assertTrue("first probe should start", withTimeoutOrNull(5_000) { started.await() } != null)
        delay(50)
        cancelled.set(true)
        val cancelStart = System.nanoTime()
        job.cancel()
        val outcome = runCatching { job.await() }
        val cancelMs = (System.nanoTime() - cancelStart) / 1_000_000

        assertTrue(
            "scan should have been cancelled, not completed normally: ${outcome.exceptionOrNull()}",
            outcome.exceptionOrNull() is CancellationException,
        )
        assertEquals("no progress should be reported after cancellation", 0, progressAfterCancel.get())
        assertTrue("cancel took ${cancelMs}ms, expected well under total scan time", cancelMs < 5000)
    }

    @Test
    fun speedScan_cancel_returns_promptly_and_stops_stale_progress() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val speedProbe = { _: String, _: Int, _: String, _: Int, _: Float ->
            started.complete(Unit)
            Thread.sleep(300)
            2f
        }
        val engine = KotlinEngine(
            latencyProbe = { _, _, _, _ -> LatencyStats(1, 1, 0f, 5f, 5f, 5f) },
            speedProbe = speedProbe,
            dispatcher = Dispatchers.Default,
        )
        val cancelled = AtomicBoolean(false)
        val progressAfterCancel = AtomicInteger(0)
        val ips = (1..5).map { "10.0.0.$it" }
        val job = async {
            engine.speedScan(
                ips = ips,
                port = 443,
                url = "https://example.com/file",
                downloadTime = 2,
                downloadCount = ips.size,
                speedLimit = 0f,
                concurrency = 2,
                onProgress = { _, _ ->
                    if (cancelled.get()) progressAfterCancel.incrementAndGet()
                },
            )
        }

        assertTrue("first probe should start", withTimeoutOrNull(5_000) { started.await() } != null)
        delay(50)
        cancelled.set(true)
        val cancelStart = System.nanoTime()
        job.cancel()
        val outcome = runCatching { job.await() }
        val cancelMs = (System.nanoTime() - cancelStart) / 1_000_000

        assertTrue(
            "scan should have been cancelled, not completed normally: ${outcome.exceptionOrNull()}",
            outcome.exceptionOrNull() is CancellationException,
        )
        assertEquals("no progress should be reported after cancellation", 0, progressAfterCancel.get())
        assertTrue("cancel took ${cancelMs}ms, expected well under total scan time", cancelMs < 5000)
    }

    @Test
    fun fallback_retries_latency_when_primary_throws() = runTest {
        var primaryLatencyCalls = 0
        val primary = object : ScanEngine {
            override val name = "primary"
            override suspend fun isAvailable() = true
            override suspend fun latencyScan(
                ips: List<String>, port: Int, probeCount: Int, pingCount: Int,
                latencyLimit: Float, concurrency: Int, pingTimeoutMs: Int,
                onProgress: (done: Int, total: Int) -> Unit,
            ): List<ScanResult> {
                primaryLatencyCalls++
                throw IllegalStateException("primary latency failed")
            }
            override suspend fun speedScan(
                ips: List<String>, port: Int, url: String, downloadTime: Int,
                downloadCount: Int, speedLimit: Float, concurrency: Int,
                onProgress: (done: Int, total: Int) -> Unit,
            ): List<ScanResult> = emptyList()
        }
        val fallback = object : ScanEngine {
            override val name = "fallback"
            override suspend fun isAvailable() = true
            override suspend fun latencyScan(
                ips: List<String>, port: Int, probeCount: Int, pingCount: Int,
                latencyLimit: Float, concurrency: Int, pingTimeoutMs: Int,
                onProgress: (done: Int, total: Int) -> Unit,
            ): List<ScanResult> = listOf(scanResult("1.1.1.1", port, 10f))
            override suspend fun speedScan(
                ips: List<String>, port: Int, url: String, downloadTime: Int,
                downloadCount: Int, speedLimit: Float, concurrency: Int,
                onProgress: (done: Int, total: Int) -> Unit,
            ): List<ScanResult> = emptyList()
        }
        val engine = FallbackEngine(primary, fallback)
        val results = engine.latencyScan(
            ips = listOf("1.1.1.1"),
            port = 443,
            probeCount = 2,
            pingCount = 2,
            latencyLimit = 100f,
            concurrency = 1,
            pingTimeoutMs = 2000,
            onProgress = { _, _ -> },
        )
        assertEquals(1, primaryLatencyCalls)
        assertEquals(listOf("1.1.1.1"), results.map { it.ip })
        assertEquals("primary", engine.resolvedEngineName())
    }

    @Test
    fun fallback_retries_speed_when_primary_throws() = runTest {
        var primarySpeedCalls = 0
        val primary = object : ScanEngine {
            override val name = "primary"
            override suspend fun isAvailable() = true
            override suspend fun latencyScan(
                ips: List<String>, port: Int, probeCount: Int, pingCount: Int,
                latencyLimit: Float, concurrency: Int, pingTimeoutMs: Int,
                onProgress: (done: Int, total: Int) -> Unit,
            ): List<ScanResult> = emptyList()
            override suspend fun speedScan(
                ips: List<String>, port: Int, url: String, downloadTime: Int,
                downloadCount: Int, speedLimit: Float, concurrency: Int,
                onProgress: (done: Int, total: Int) -> Unit,
            ): List<ScanResult> {
                primarySpeedCalls++
                throw RuntimeException("primary speed failed")
            }
        }
        val fallback = object : ScanEngine {
            override val name = "fallback"
            override suspend fun isAvailable() = true
            override suspend fun latencyScan(
                ips: List<String>, port: Int, probeCount: Int, pingCount: Int,
                latencyLimit: Float, concurrency: Int, pingTimeoutMs: Int,
                onProgress: (done: Int, total: Int) -> Unit,
            ): List<ScanResult> = emptyList()
            override suspend fun speedScan(
                ips: List<String>, port: Int, url: String, downloadTime: Int,
                downloadCount: Int, speedLimit: Float, concurrency: Int,
                onProgress: (done: Int, total: Int) -> Unit,
            ): List<ScanResult> = listOf(scanResult("1.1.1.1", port, 0f).copy(speed = 8f))
        }
        val engine = FallbackEngine(primary, fallback)
        val results = engine.speedScan(
            ips = listOf("1.1.1.1"),
            port = 443,
            url = "https://example.com/file",
            downloadTime = 2,
            downloadCount = 1,
            speedLimit = 0f,
            concurrency = 1,
            onProgress = { _, _ -> },
        )
        assertEquals(1, primarySpeedCalls)
        assertEquals(8f, results.single().speed)
    }

    @Test
    fun fallback_resolvedEngineName_reflects_availability() = runTest {
        val primary = object : ScanEngine {
            override val name = "primary"
            override suspend fun isAvailable() = false
            override suspend fun latencyScan(
                ips: List<String>, port: Int, probeCount: Int, pingCount: Int,
                latencyLimit: Float, concurrency: Int, pingTimeoutMs: Int,
                onProgress: (done: Int, total: Int) -> Unit,
            ): List<ScanResult> = emptyList()
            override suspend fun speedScan(
                ips: List<String>, port: Int, url: String, downloadTime: Int,
                downloadCount: Int, speedLimit: Float, concurrency: Int,
                onProgress: (done: Int, total: Int) -> Unit,
            ): List<ScanResult> = emptyList()
        }
        val fallback = object : ScanEngine {
            override val name = "fallback"
            override suspend fun isAvailable() = true
            override suspend fun latencyScan(
                ips: List<String>, port: Int, probeCount: Int, pingCount: Int,
                latencyLimit: Float, concurrency: Int, pingTimeoutMs: Int,
                onProgress: (done: Int, total: Int) -> Unit,
            ): List<ScanResult> = emptyList()
            override suspend fun speedScan(
                ips: List<String>, port: Int, url: String, downloadTime: Int,
                downloadCount: Int, speedLimit: Float, concurrency: Int,
                onProgress: (done: Int, total: Int) -> Unit,
            ): List<ScanResult> = emptyList()
        }
        val engine = FallbackEngine(primary, fallback)
        assertTrue(engine.isAvailable())
        assertEquals("fallback", engine.resolvedEngineName())
    }

    private fun scanResult(ip: String, port: Int, avgMs: Float) = ScanResult(
        ip = ip,
        port = port,
        avgMs = avgMs,
        minMs = avgMs,
        maxMs = avgMs,
        lossPct = 0f,
        speed = null,
        regionCode = "",
        regionName = "",
        testedAt = 0L,
    )

    private class Counters {
        private var current = 0
        private var peak = 0

        fun enter() = synchronized(this) {
            current++
            if (current > peak) peak = current
        }

        fun exit() = synchronized(this) { current-- }

        fun peak(): Int = peak
    }
}