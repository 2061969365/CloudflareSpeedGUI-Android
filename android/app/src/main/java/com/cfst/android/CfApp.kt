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
import com.cfst.android.data.HistoryRepository
import com.cfst.android.engine.LatencyProbe
import com.cfst.android.engine.ScanController
import com.cfst.android.engine.SpeedProbe
import com.cfst.android.engine.model.IpSource
import com.cfst.android.engine.model.ScanResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetAddress
import java.util.concurrent.TimeUnit

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

    private val db = Room.databaseBuilder(appContext, HistoryDb::class.java, "history.db").build()
    val historyRepository = HistoryRepository(db.historyDao())

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .callTimeout(5, TimeUnit.SECONDS)
        .build()

    fun buildScanController(): ScanController = ScanController(
        latencyProbe = { ip, port, pingCount, timeoutMs ->
            LatencyProbe.probe(ip, port, pingCount, timeoutMs)
        },
        speedProbe = { ip, port, url, durationSec, speedLimit ->
            SpeedProbe.measure(ip, port, url, durationSec, speedLimit)
        },
        regionResolver = { ip, port -> resolveRegion(ip, port) },
    )

    val scanController: ScanController = buildScanController()

    fun assetIpLines(source: IpSource): List<String> {
        if (source == IpSource.CUSTOM) return emptyList()
        val fileName = if (source == IpSource.OFFICIAL) "ip/official.txt" else "ip/cmip.txt"
        return runCatching {
            appContext.assets.open(fileName).bufferedReader().useLines { lines ->
                lines.map { it.trim() }.filter { it.isNotEmpty() }.toList()
            }
        }.getOrElse { emptyList() }
    }

    private suspend fun resolveRegion(ip: String, port: Int): String? = withContext(Dispatchers.IO) {
        try {
            val client = okHttpClient.newBuilder()
                .dns(object : Dns {
                    override fun lookup(hostname: String): List<InetAddress> =
                        listOf(InetAddress.getByName(ip))
                })
                .build()
            val request = Request.Builder()
                .url("https://speed.cloudflare.com/cdn-cgi/trace")
                .build()
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                val body = resp.body?.string() ?: return@withContext null
                body.lineSequence()
                    .firstOrNull { it.startsWith("colo=") }
                    ?.substringAfter("colo=")
                    ?.takeIf { it.isNotBlank() }
            }
        } catch (_: Exception) {
            null
        }
    }
}
