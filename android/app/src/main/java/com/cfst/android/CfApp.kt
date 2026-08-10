package com.cfst.android

import android.app.Application
import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.room.Room
import com.cfst.android.data.ConfigRepository
import com.cfst.android.data.HistoryDb
import com.cfst.android.data.HistoryEntry
import com.cfst.android.data.HistoryRepository
import com.cfst.android.engine.CsvCodec
import com.cfst.android.engine.FallbackEngine
import com.cfst.android.engine.KotlinEngine
import com.cfst.android.engine.LatencyProbe
import com.cfst.android.engine.ScanController
import com.cfst.android.engine.SpeedProbe
import com.cfst.android.engine.cfst.CfstEngine
import com.cfst.android.engine.model.IpSource
import com.cfst.android.engine.model.ResultStats
import com.cfst.android.engine.model.ScanEvent
import com.cfst.android.engine.model.ScanResult
import java.io.File
import java.net.InetAddress
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.ConnectionPool
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request

class CfApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(applicationContext)
    }
}

class AppContainer(private val context: Context) {

    private val appContext = context.applicationContext

    private val dataStore: DataStore<Preferences> = PreferenceDataStoreFactory.create {
        appContext.preferencesDataStoreFile("settings")
    }

    val configRepository = ConfigRepository(dataStore)

    val lastResults = MutableStateFlow<List<ScanResult>>(emptyList())

    val darkTheme = MutableStateFlow(false)

    val resolvedEngineName = MutableStateFlow<String?>(null)

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val db = Room.databaseBuilder(appContext, HistoryDb::class.java, "history.db")
        .addMigrations(*HistoryDb.MIGRATIONS)
        .build()
    val historyRepository = HistoryRepository(db.historyDao())

    private val fallbackEngine = FallbackEngine(
        primary = CfstEngine(
            binaryPath = {
                File(appContext.applicationInfo.nativeLibraryDir, "libcfst.so")
            },
            workDir = { appContext.filesDir },
            env = mapOf("TMPDIR" to appContext.cacheDir.absolutePath),
        ),
        fallback = KotlinEngine(
            latencyProbe = { ip, port, pingCount, timeoutMs ->
                LatencyProbe.probe(ip, port, pingCount, timeoutMs)
            },
            speedProbe = { ip, port, url, durationSec, speedLimit ->
                SpeedProbe.measure(ip, port, url, durationSec, speedLimit)
            },
        ),
    )

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .callTimeout(5, TimeUnit.SECONDS)
        .build()

    private val threadLocalProbeIp = ThreadLocal<String?>()

    private val regionHttpClient = okHttpClient.newBuilder()
        .connectionPool(ConnectionPool(0, 1, TimeUnit.NANOSECONDS))
        .dns(object : Dns {
            override fun lookup(hostname: String): List<InetAddress> {
                val probeIp = threadLocalProbeIp.get()
                return if (probeIp != null) {
                    listOf(InetAddress.getByName(probeIp))
                } else {
                    Dns.SYSTEM.lookup(hostname)
                }
            }
        })
        .build()

    private val regionColoCache = ConcurrentHashMap<String, String>()
    private val regionFailed = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    val scanController: ScanController = ScanController(
        engine = fallbackEngine,
        regionResolver = { ip, port -> resolveRegion(ip, port) },
    )

    init {
        appScope.launch {
            val stored = runCatching {
                configRepository.flow.first()["darkTheme"] as? Boolean
            }.getOrNull()
            darkTheme.value = stored ?: false
        }
        appScope.launch {
            val days = runCatching {
                (configRepository.get("historyRetentionDays") as? Number)?.toInt()
            }.getOrNull() ?: 30
            runCatching {
                historyRepository.deleteOlderThan(System.currentTimeMillis() - days * 86_400_000L)
            }
        }
        appScope.launch {
            resolvedEngineName.value = runCatching { fallbackEngine.resolvedEngineName() }.getOrNull()
        }
        appScope.launch {
            scanController.events.collect { event -> onScanEvent(event) }
        }
    }

    fun setDarkTheme(value: Boolean) {
        darkTheme.value = value
    }

    private var scanStartedAt = 0L
    private var scanGeneratedCount = 0
    private var scanLastResults: List<ScanResult> = emptyList()

    private fun onScanEvent(event: ScanEvent) {
        when (event) {
            is ScanEvent.PhaseChanged -> {
                if (event.phase == "生成IP列表") {
                    scanStartedAt = System.currentTimeMillis()
                    scanGeneratedCount = 0
                    scanLastResults = emptyList()
                }
            }
            is ScanEvent.Log -> trackGenerated(event.line)
            is ScanEvent.ResultReady -> {
                scanLastResults = event.results
                lastResults.value = event.results
            }
            ScanEvent.Done -> persistHistory()
            else -> {}
        }
    }

    private fun trackGenerated(line: String) {
        if (!line.startsWith("共生成 ")) return
        line.removePrefix("共生成 ")
            .substringBefore(" 个待测 IP")
            .toIntOrNull()
            ?.let { scanGeneratedCount = it }
    }

    private fun persistHistory() {
        val results = scanLastResults
        if (results.isEmpty()) return
        scanLastResults = emptyList()
        val startedAt = scanStartedAt
        val count = scanGeneratedCount
        appScope.launch {
            val entry = withContext(Dispatchers.IO) {
                val stats = ResultStats.compute(results)
                HistoryEntry(
                    startedAt = startedAt,
                    ipCount = count,
                    resultCount = results.size,
                    fastestMs = stats.fastestMs?.toLong(),
                    regionsSummary = results.groupBy { it.regionName }
                        .map { (region, list) -> "$region:${list.size}" }
                        .joinToString(";"),
                    recordsCsv = CsvCodec.encode(results),
                )
            }
            runCatching { historyRepository.add(entry) }
        }
    }

    fun assetIpLines(source: IpSource): List<String> {
        if (source == IpSource.CUSTOM) return emptyList()
        val fileName = if (source == IpSource.OFFICIAL) "ip/official.txt" else "ip/cmip.txt"
        return runCatching {
            appContext.assets.open(fileName).bufferedReader().useLines { lines ->
                lines.map { it.trim() }.filter { it.isNotEmpty() }.toList()
            }
        }.getOrElse { emptyList() }
    }

    private suspend fun resolveRegion(ip: String, port: Int): String? {
        regionColoCache[ip]?.let { return it }
        if (ip in regionFailed) return null
        val colo = withContext(Dispatchers.IO) {
            try {
                threadLocalProbeIp.set(ip)
                try {
                    val request = Request.Builder()
                        .url("https://speed.cloudflare.com/cdn-cgi/trace")
                        .build()
                    regionHttpClient.newCall(request).execute().use { resp ->
                        if (!resp.isSuccessful) return@use null
                        val body = resp.body?.string() ?: return@use null
                        body.lineSequence()
                            .firstOrNull { it.startsWith("colo=") }
                            ?.substringAfter("colo=")
                            ?.takeIf { it.isNotBlank() }
                    }
                } finally {
                    threadLocalProbeIp.remove()
                }
            } catch (_: Exception) {
                null
            }
        }
        if (colo != null) regionColoCache[ip] = colo else regionFailed.add(ip)
        return colo
    }
}
