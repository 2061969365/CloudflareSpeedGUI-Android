package com.cfst.android.engine

import com.cfst.android.engine.model.LatencyStats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

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