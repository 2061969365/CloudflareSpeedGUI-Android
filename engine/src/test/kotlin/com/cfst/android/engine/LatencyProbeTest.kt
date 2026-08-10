package com.cfst.android.engine

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket

class LatencyProbeTest {

    @Test
    fun open_port_measures_successful_connections() = runBlocking {
        val server = ServerSocket(0)
        val port = server.localPort
        val acceptThread = Thread {
            repeat(3) { runCatching { server.accept().close() } }
        }
        acceptThread.start()

        val stats = LatencyProbe.probe("127.0.0.1", port, pingCount = 3, timeoutMs = 1000)

        assertEquals(3, stats.sent)
        assertEquals(3, stats.received)
        assertEquals(0f, stats.lossPct, 0f)
        assertNotNull(stats.avgMs)
        assertNotNull(stats.minMs)
        assertNotNull(stats.maxMs)
        assertTrue(stats.avgMs!! >= stats.minMs!!)
        assertTrue(stats.avgMs!! <= stats.maxMs!!)

        acceptThread.join(2000)
        server.close()
    }

    @Test
    fun closed_port_is_full_loss() = runBlocking {
        val server = ServerSocket(0)
        val port = server.localPort
        server.close()
        Thread.sleep(150)

        val stats = LatencyProbe.probe("127.0.0.1", port, pingCount = 3, timeoutMs = 500)

        assertEquals(3, stats.sent)
        assertEquals(0, stats.received)
        assertEquals(100f, stats.lossPct, 0f)
        assertNull(stats.avgMs)
        assertNull(stats.minMs)
        assertNull(stats.maxMs)
    }

    @Test
    fun different_closed_ports_all_count_as_loss() = runBlocking {
        val ports = (0 until 3).map { i ->
            ServerSocket(0).use { it.localPort }
        }
        Thread.sleep(150)
        ports.forEach { port ->
            val stats = LatencyProbe.probe("127.0.0.1", port, pingCount = 1, timeoutMs = 500)
            assertEquals(1, stats.sent)
            assertEquals(0, stats.received)
            assertEquals(100f, stats.lossPct, 0f)
            assertNull(stats.avgMs)
        }
    }

    @Test
    fun timeout_limits_hang_and_counts_as_loss() = runBlocking {
        withTimeout(15_000) {
            val stats = LatencyProbe.probe("192.0.2.1", 80, pingCount = 2, timeoutMs = 50)

            assertEquals(2, stats.sent)
            assertEquals(0, stats.received)
            assertEquals(100f, stats.lossPct, 0f)
            assertNull(stats.avgMs)
        }
    }

    @Test
    fun zero_ping_count_returns_no_loss_nan() = runBlocking {
        val stats = LatencyProbe.probe("127.0.0.1", 80, pingCount = 0, timeoutMs = 50)
        assertEquals(0, stats.sent)
        assertEquals(0, stats.received)
        assertEquals(0f, stats.lossPct, 0f)
        assertNull(stats.avgMs)
        assertTrue(stats.lossPct.isNaN().not())
    }
}
