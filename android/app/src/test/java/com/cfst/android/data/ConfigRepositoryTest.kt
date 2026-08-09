package com.cfst.android.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import java.io.File
import kotlinx.coroutines.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class ConfigRepositoryTest {

    private fun createDataStore(): DataStore<Preferences> {
        val file = File.createTempFile("cfst-configrepo-", ".preferences")
        file.deleteOnExit()
        return PreferenceDataStoreFactory.create(corruptionHandler = null) { file }
    }

    private fun defaults(): Map<String, Any> = mapOf(
        "lastSource" to "official",
        "lastScene" to "quick",
        "lastPorts" to listOf(443),
        "multiPortEnabled" to false,
        "fullScanEnabled" to false,
        "pingCount" to 2,
        "downloadCount" to 50,
        "downloadTime" to 10,
        "speedLimit" to 0,
        "latencyLimit" to 200,
        "probeCount" to 500,
        "fullScanProbeCount" to 5000,
        "downloadUrl" to "http://speed.cloudflare.com/__down?bytes=50000000",
        "historyRetentionDays" to 30,
        "speedRegion" to "全部",
        "speedCount" to 50,
        "pingConcurrency" to 8,
        "speedConcurrency" to 5,
    )

    @Test
    fun defaults_flow_emits_all_defaults() = runTest {
        val repo = ConfigRepository(createDataStore())
        val config = repo.flow.first()
        assertEquals(18, config.size)
        assertEquals(defaults(), config)
    }

    @Test
    fun get_returns_default_when_unset() = runTest {
        val repo = ConfigRepository(createDataStore())
        assertEquals(2, repo.get("pingCount"))
        assertEquals("official", repo.get("lastSource"))
        assertEquals(listOf(443), repo.get("lastPorts"))
    }

    @Test
    fun set_then_get_roundtrip() = runTest {
        val repo = ConfigRepository(createDataStore())
        repo.set("lastSource", "custom")
        repo.set("multiPortEnabled", true)
        repo.set("pingCount", 7)
        repo.set("lastPorts", listOf(443, 8443, 2053))
        assertEquals("custom", repo.get("lastSource"))
        assertEquals(true, repo.get("multiPortEnabled"))
        assertEquals(7, repo.get("pingCount"))
        assertEquals(listOf(443, 8443, 2053), repo.get("lastPorts"))
    }

    @Test
    fun set_unknown_key_throws() = runTest {
        val repo = ConfigRepository(createDataStore())
        assertThrows(IllegalArgumentException::class.java) {
            repo.set("noSuchKey", 1)
        }
    }

    @Test
    fun flow_reeemits_on_set() = runTest {
        val repo = ConfigRepository(createDataStore())
        val emissions = Channel<Map<String, Any>>(Channel.UNLIMITED)
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            repo.flow.collect { emissions.trySend(it) }
        }
        val first = emissions.receive()
        assertEquals(2, first["pingCount"])
        repo.set("pingCount", 7)
        val second = emissions.receive()
        assertEquals(7, second["pingCount"])
        job.cancel()
        emissions.close()
    }

    @Test
    fun resetDefaults_keeps_protected_keys() = runTest {
        val repo = ConfigRepository(createDataStore())
        repo.set("lastSource", "custom")
        repo.set("lastScene", "full")
        repo.set("lastPorts", listOf(443, 8443, 2053))
        repo.set("multiPortEnabled", true)
        repo.set("fullScanEnabled", true)
        repo.set("pingCount", 7)
        repo.set("downloadCount", 99)
        repo.set("speedCount", 123)
        repo.resetDefaults()
        assertEquals("custom", repo.get("lastSource"))
        assertEquals("full", repo.get("lastScene"))
        assertEquals(listOf(443, 8443, 2053), repo.get("lastPorts"))
        assertEquals(true, repo.get("multiPortEnabled"))
        assertEquals(true, repo.get("fullScanEnabled"))
        assertEquals(2, repo.get("pingCount"))
        assertEquals(50, repo.get("downloadCount"))
        assertEquals(50, repo.get("speedCount"))
    }

    @Test
    fun get_unknown_key_returns_null() = runTest {
        val repo = ConfigRepository(createDataStore())
        assertNull(repo.get("noSuchKey"))
    }
}