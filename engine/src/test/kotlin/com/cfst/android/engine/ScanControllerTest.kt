package com.cfst.android.engine

import com.cfst.android.engine.model.IpSource
import com.cfst.android.engine.model.LatencyStats
import com.cfst.android.engine.model.ScanEvent
import com.cfst.android.engine.model.ScanRequest
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ScanControllerTest {

    private fun baseRequest(
        customLines: List<String> = listOf("1.1.1.1", "1.1.1.2", "1.1.1.3"),
        ports: List<Int> = listOf(443),
        multiPortBest: Boolean = false,
        speedEnabled: Boolean = false,
        region: String = "全部",
        speedCount: Int = 50,
        latencyLimit: Float = 200f,
        downloadTime: Int = 10,
        downloadCount: Int = 50,
    ) = ScanRequest(
        source = IpSource.CUSTOM,
        customLines = customLines,
        maxIps = 0,
        fullScan = false,
        ports = ports,
        multiPortBest = multiPortBest,
        speedEnabled = speedEnabled,
        region = region,
        speedCount = speedCount,
        probeCount = 500,
        fullProbeCount = 5000,
        latencyLimit = latencyLimit,
        downloadTime = downloadTime,
        downloadCount = downloadCount,
        speedLimit = 0f,
        downloadUrl = "",
        pingCount = 2,
        pingTimeoutMs = 2000,
        pingConcurrency = 8,
        speedConcurrency = 5,
    )

    private fun TestScope.collectEvents(controller: ScanController, dispatcher: CoroutineDispatcher): List<ScanEvent> {
        val collected = mutableListOf<ScanEvent>()
        CoroutineScope(SupervisorJob() + dispatcher).launch {
            controller.events.collect { collected.add(it) }
        }
        return collected
    }

    @Test
    fun singlePortNormalFlow() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val controller = ScanController(
            latencyProbe = { ip, _, _, _ ->
                val avg = when (ip) {
                    "1.1.1.1" -> 50f
                    "1.1.1.2" -> 300f
                    else -> 100f
                }
                LatencyStats(2, 2, 0f, avg, avg, avg)
            },
            speedProbe = { _, _, _, _, _ -> 12.5f },
            regionResolver = { _, _ -> null },
            dispatcher = dispatcher,
        )
        val collected = collectEvents(controller, dispatcher)
        controller.start(baseRequest())
        advanceUntilIdle()

        val phases = collected.filterIsInstance<ScanEvent.PhaseChanged>().map { it.phase }
        assertEquals(listOf("生成IP列表", "延迟扫描", "完成"), phases)
        assertFalse(collected.any { it is ScanEvent.PhaseChanged && it.phase == "下载测速" })

        val ready = collected.filterIsInstance<ScanEvent.ResultReady>().single()
        assertEquals(listOf("1.1.1.1", "1.1.1.3"), ready.results.map { it.ip })
        assertEquals(listOf(50f, 100f), ready.results.map { it.avgMs })
        assertTrue(ready.results.all { it.avgMs!! <= 200f })
        assertTrue(collected.any { it is ScanEvent.Done })
    }

    @Test
    fun speedEnabledFillsSpeed() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val controller = ScanController(
            latencyProbe = { _, _, _, _ -> LatencyStats(2, 2, 0f, 50f, 50f, 50f) },
            speedProbe = { _, _, _, _, _ -> 12.5f },
            regionResolver = { _, _ -> null },
            dispatcher = dispatcher,
        )
        val collected = collectEvents(controller, dispatcher)
        controller.start(baseRequest(speedEnabled = true))
        advanceUntilIdle()

        assertTrue(collected.any { it is ScanEvent.PhaseChanged && it.phase == "下载测速" })
        val ready = collected.filterIsInstance<ScanEvent.ResultReady>().single()
        assertEquals(3, ready.results.size)
        assertTrue(ready.results.all { it.speed == 12.5f })
        assertTrue(ready.results.size <= 50)
        assertTrue(collected.any { it is ScanEvent.Done })
    }

    @Test
    fun multiPortBestSerial() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val controller = ScanController(
            latencyProbe = { _, port, _, _ ->
                val avg = if (port == 8443) 150f else 50f
                LatencyStats(2, 2, 0f, avg, avg, avg)
            },
            speedProbe = { _, _, _, _, _ -> 12.5f },
            regionResolver = { _, _ -> null },
            dispatcher = dispatcher,
        )
        val collected = collectEvents(controller, dispatcher)
        controller.start(baseRequest(ports = listOf(443, 8443), multiPortBest = true))
        advanceUntilIdle()

        val ready = collected.filterIsInstance<ScanEvent.ResultReady>().single()
        assertEquals(3, ready.results.size)
        assertEquals(3, ready.results.map { it.ip }.distinct().size)
        assertTrue(ready.results.all { it.port == 443 })
        assertTrue(ready.results.all { it.avgMs == 50f })
        assertTrue(collected.any { it is ScanEvent.Done })
    }

    @Test
    fun regionFilterLimitsTargets() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val customLines = listOf("1.1.1.1", "1.1.1.2", "1.1.1.3", "1.1.1.4")
        val speedCalls = mutableListOf<String>()
        val controller = ScanController(
            latencyProbe = { _, _, _, _ -> LatencyStats(2, 2, 0f, 50f, 50f, 50f) },
            speedProbe = { ip, _, _, _, _ ->
                speedCalls.add(ip)
                12.5f
            },
            regionResolver = { ip, _ -> if (ip.endsWith(".1") || ip.endsWith(".3")) "HKG" else "LAX" },
            dispatcher = dispatcher,
        )
        val collected = collectEvents(controller, dispatcher)
        controller.start(baseRequest(customLines = customLines, speedEnabled = true, region = "HKG", speedCount = 2))
        advanceUntilIdle()

        assertEquals(listOf("1.1.1.1", "1.1.1.3"), speedCalls)
        val ready = collected.filterIsInstance<ScanEvent.ResultReady>().single()
        assertEquals(listOf("1.1.1.1", "1.1.1.3"), ready.results.map { it.ip })
        assertTrue(ready.results.all { it.regionCode == "HKG" })
        assertTrue(collected.any { it is ScanEvent.Done })
    }

    @Test
    fun cancelStopsEmitting() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val controller = ScanController(
            latencyProbe = { _, _, _, _ ->
                delay(Long.MAX_VALUE)
                LatencyStats(2, 0, 100f, null, null, null)
            },
            speedProbe = { _, _, _, _, _ -> 12.5f },
            regionResolver = { _, _ -> null },
            dispatcher = dispatcher,
        )
        val collected = collectEvents(controller, dispatcher)
        controller.start(baseRequest())
        runCurrent()
        controller.cancel()
        advanceUntilIdle()

        assertTrue(collected.any { it is ScanEvent.Log && it.line == "已取消" })
        assertTrue(collected.any { it is ScanEvent.Error && it.message == "已取消" })
        assertTrue(collected.any { it is ScanEvent.Done })
        assertFalse(collected.any { it is ScanEvent.ResultReady })
    }

    @Test
    fun watchdogSkipsStuckGroup() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val controller = ScanController(
            latencyProbe = { _, _, _, _ -> LatencyStats(2, 2, 0f, 50f, 50f, 50f) },
            speedProbe = { _, port, _, _, _ ->
                if (port == 8443) {
                    delay(Long.MAX_VALUE)
                    5f
                } else {
                    20f
                }
            },
            regionResolver = { _, _ -> null },
            dispatcher = dispatcher,
        )
        val collected = collectEvents(controller, dispatcher)
        controller.start(
            baseRequest(
                ports = listOf(443, 8443),
                speedEnabled = true,
                downloadTime = 1,
                downloadCount = 1,
            )
        )
        advanceUntilIdle()

        assertTrue(collected.any { it is ScanEvent.Log && it.line.contains("超时，跳过") })
        val ready = collected.filterIsInstance<ScanEvent.ResultReady>().single()
        assertTrue(ready.results.any { it.port == 443 && it.speed == 20f })
        assertTrue(collected.any { it is ScanEvent.Done })
    }
}
