package com.cfst.android.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

class ConfigRepository(private val dataStore: DataStore<Preferences>) {

    private data class Entry<T>(val key: Preferences.Key<T>, val default: T)

    private val stringEntries: Map<String, Entry<String>> = mapOf(
        "lastSource" to Entry(stringPreferencesKey("lastSource"), "official"),
        "lastScene" to Entry(stringPreferencesKey("lastScene"), "quick"),
        "downloadUrl" to Entry(
            stringPreferencesKey("downloadUrl"),
            "https://speed.hatexianyu.ccwu.cc/?bytes=209715200",
        ),
        "speedRegion" to Entry(stringPreferencesKey("speedRegion"), "全部"),
    )

    private val booleanEntries: Map<String, Entry<Boolean>> = mapOf(
        "multiPortEnabled" to Entry(booleanPreferencesKey("multiPortEnabled"), false),
        "fullScanEnabled" to Entry(booleanPreferencesKey("fullScanEnabled"), false),
    )

    private val intEntries: Map<String, Entry<Int>> = mapOf(
        "pingCount" to Entry(intPreferencesKey("pingCount"), 2),
        "downloadCount" to Entry(intPreferencesKey("downloadCount"), 50),
        "downloadTime" to Entry(intPreferencesKey("downloadTime"), 10),
        "speedLimit" to Entry(intPreferencesKey("speedLimit"), 0),
        "latencyLimit" to Entry(intPreferencesKey("latencyLimit"), 200),
        "probeCount" to Entry(intPreferencesKey("probeCount"), 500),
        "fullScanProbeCount" to Entry(intPreferencesKey("fullScanProbeCount"), 5000),
        "historyRetentionDays" to Entry(intPreferencesKey("historyRetentionDays"), 30),
        "speedCount" to Entry(intPreferencesKey("speedCount"), 50),
        "pingConcurrency" to Entry(intPreferencesKey("pingConcurrency"), 8),
        "speedConcurrency" to Entry(intPreferencesKey("speedConcurrency"), 5),
    )

    private val allKeys: Set<String> =
        stringEntries.keys + booleanEntries.keys + intEntries.keys + setOf(LAST_PORTS_KEY)

    private val protectedKeys: Set<String> = setOf(
        "lastSource",
        "lastScene",
        LAST_PORTS_KEY,
        "multiPortEnabled",
        "fullScanEnabled",
    )

    val flow: Flow<Map<String, Any>> = dataStore.data.map { prefs -> readAll(prefs) }

    suspend fun get(key: String): Any? {
        if (key !in allKeys) return null
        return readValue(dataStore.data.first(), key)
    }

    suspend fun set(key: String, value: Any) {
        if (key !in allKeys) {
            throw IllegalArgumentException("Unknown config key: $key")
        }
        dataStore.edit { prefs -> writeValue(prefs, key, value) }
    }

    suspend fun resetDefaults() {
        val preserved = protectedKeys.associateWith { key -> readValue(dataStore.data.first(), key) }
        dataStore.edit { prefs ->
            prefs.clear()
            for (key in allKeys) {
                if (key !in preserved) {
                    writeValue(prefs, key, defaultFor(key))
                }
            }
            for ((key, value) in preserved) {
                writeValue(prefs, key, value)
            }
        }
    }

    private fun readAll(prefs: Preferences): Map<String, Any> =
        allKeys.associateWith { key -> readValue(prefs, key) }

    private fun readValue(prefs: Preferences, key: String): Any {
        stringEntries[key]?.let { (prefKey, default) -> return prefs[prefKey] ?: default }
        booleanEntries[key]?.let { (prefKey, default) -> return prefs[prefKey] ?: default }
        intEntries[key]?.let { (prefKey, default) -> return prefs[prefKey] ?: default }
        if (key == LAST_PORTS_KEY) {
            val raw = prefs[lastPortsKey]
            return raw?.let { toPortsList(it) } ?: DEFAULT_PORTS
        }
        throw IllegalArgumentException("Unknown config key: $key")
    }

    private fun writeValue(prefs: MutablePreferences, key: String, value: Any) {
        if (key == LAST_PORTS_KEY) {
            if (value !is List<*>) {
                throw IllegalArgumentException(
                    "Config key '$key' requires a List<Int>, got ${value::class.simpleName}",
                )
            }
            prefs[lastPortsKey] = value.joinToString(",")
            return
        }
        when (value) {
            is String -> prefs[requireStringEntry(key).key] = value
            is Boolean -> prefs[requireBooleanEntry(key).key] = value
            is Int -> prefs[requireIntEntry(key).key] = value
            else -> throw IllegalArgumentException(
                "Unsupported type ${value::class.simpleName} for config key '$key'",
            )
        }
    }

    private fun requireStringEntry(key: String): Entry<String> =
        stringEntries[key]
            ?: throw IllegalArgumentException("Config key '$key' does not accept a String value")

    private fun requireBooleanEntry(key: String): Entry<Boolean> =
        booleanEntries[key]
            ?: throw IllegalArgumentException("Config key '$key' does not accept a Boolean value")

    private fun requireIntEntry(key: String): Entry<Int> =
        intEntries[key]
            ?: throw IllegalArgumentException("Config key '$key' does not accept an Int value")

    private fun defaultFor(key: String): Any {
        stringEntries[key]?.let { return it.default }
        booleanEntries[key]?.let { return it.default }
        intEntries[key]?.let { return it.default }
        if (key == LAST_PORTS_KEY) return DEFAULT_PORTS
        throw IllegalArgumentException("Unknown config key: $key")
    }

    private fun toPortsList(raw: String): List<Int> =
        if (raw.isBlank()) emptyList() else raw.split(',').map { it.trim().toInt() }

    private companion object {
        const val LAST_PORTS_KEY = "lastPorts"
        val DEFAULT_PORTS: List<Int> = listOf(443)
        val lastPortsKey = stringPreferencesKey(LAST_PORTS_KEY)
    }
}