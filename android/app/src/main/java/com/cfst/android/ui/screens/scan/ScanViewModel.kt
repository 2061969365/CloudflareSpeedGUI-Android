package com.cfst.android.ui.screens.scan

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.cfst.android.CfApp
import com.cfst.android.data.HistoryEntry
import com.cfst.android.engine.ColoRegionMapper
import com.cfst.android.engine.CsvCodec
import com.cfst.android.engine.ScanController
import com.cfst.android.engine.model.IpSource
import com.cfst.android.engine.model.ResultStats
import com.cfst.android.engine.model.ScanEvent
import com.cfst.android.engine.model.ScanRequest
import com.cfst.android.engine.model.ScanResult
import com.cfst.android.service.ScanService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

enum class ScanScene { QUICK, SINGLE, CUSTOM }

data class ScanUiState(
    val running: Boolean = false,
    val scene: ScanScene = ScanScene.QUICK,
    val source: IpSource = IpSource.OFFICIAL,
    val ports: List<Int> = listOf(443),
    val maxIps: Int = 500,
    val pingConcurrency: Int = 200,
    val speedConcurrency: Int = 5,
    val fullScan: Boolean = false,
    val multiPortBest: Boolean = false,
    val speedEnabled: Boolean = false,
    val region: String = "全部",
    val speedCount: Int = 50,
    val resultCount: Int = 0,
    val totalScanned: Int = 0,
    val bestRegion: String = "-",
    val fastestMs: Float? = null,
    val phase: String = "就绪",
    val progress: Int = 0,
    val etaMs: Long? = null,
    val log: List<String> = emptyList(),
    val customLines: List<String> = emptyList(),
    val quickIp: String? = null,
)

class ScanViewModel(application: Application) : AndroidViewModel(application) {

    private val container = (application as CfApp).container
    private val controller: ScanController = container.scanController

    private val _uiState = MutableStateFlow(ScanUiState())
    val uiState: StateFlow<ScanUiState> = _uiState.asStateFlow()

    private val _scanFinished = MutableStateFlow(false)
    val scanFinished: StateFlow<Boolean> = _scanFinished.asStateFlow()

    private var scanStartedAt = 0L
    private var generatedCount = 0
    private var lastResults: List<ScanResult> = emptyList()

    init {
        viewModelScope.launch {
            controller.events.collect { event -> onEvent(event) }
        }
    }

    fun start() {
        if (_uiState.value.running) return
        _scanFinished.value = false
        viewModelScope.launch {
            val request = withContext(Dispatchers.IO) { buildScanRequest() }
            scanStartedAt = System.currentTimeMillis()
            generatedCount = 0
            lastResults = emptyList()
            _uiState.update {
                it.copy(
                    running = true,
                    phase = "生成IP列表",
                    progress = 0,
                    etaMs = null,
                    log = emptyList(),
                    resultCount = 0,
                    totalScanned = 0,
                    bestRegion = "-",
                    fastestMs = null,
                    quickIp = null,
                )
            }
            val app = getApplication<Application>()
            ContextCompat.startForegroundService(
                app,
                Intent(app, ScanService::class.java).setAction(ScanService.ACTION_START),
            )
            controller.start(request)
        }
    }

    fun quickTest(ip: String, port: Int) {
        if (_uiState.value.running) return
        _scanFinished.value = false
        viewModelScope.launch {
            val request = withContext(Dispatchers.IO) {
                buildScanRequest(overrideLines = listOf(ip.trim()))
            }.copy(
                ports = listOf(port),
                maxIps = 0,
                probeCount = 1,
                fullScan = false,
                multiPortBest = false,
                speedEnabled = true,
                region = "全部",
            )
            scanStartedAt = System.currentTimeMillis()
            generatedCount = 0
            lastResults = emptyList()
            _uiState.update {
                it.copy(
                    running = true,
                    phase = "生成IP列表",
                    progress = 0,
                    etaMs = null,
                    log = emptyList(),
                    resultCount = 0,
                    totalScanned = 0,
                    bestRegion = "-",
                    fastestMs = null,
                    quickIp = ip.trim(),
                )
            }
            val app = getApplication<Application>()
            ContextCompat.startForegroundService(
                app,
                Intent(app, ScanService::class.java).setAction(ScanService.ACTION_START),
            )
            controller.start(request)
        }
    }

    fun consumeScanFinished() {
        _scanFinished.value = false
    }

    fun cancelScan() {
        controller.cancel()
    }

    fun onSceneSelected(scene: ScanScene) {
        _uiState.update {
            when (scene) {
                ScanScene.QUICK -> it.copy(
                    scene = ScanScene.QUICK,
                    ports = listOf(443),
                    multiPortBest = false,
                    fullScan = false,
                    speedEnabled = true,
                )
                ScanScene.SINGLE -> it.copy(
                    scene = ScanScene.SINGLE,
                    ports = listOf(443),
                    multiPortBest = false,
                    fullScan = false,
                    speedEnabled = false,
                )
                ScanScene.CUSTOM -> it.copy(scene = ScanScene.CUSTOM)
            }
        }
    }

    fun setSource(source: IpSource) {
        _uiState.update { it.copy(source = source) }
    }

    fun togglePort(port: Int) {
        _uiState.update { s ->
            val ports = if (port in s.ports) {
                if (s.ports.size > 1) s.ports - port else s.ports
            } else {
                s.ports + port
            }
            s.copy(ports = ports)
        }
    }

    fun setMultiPortBest(enabled: Boolean) {
        _uiState.update { it.copy(multiPortBest = enabled) }
    }

    fun setFullScan(enabled: Boolean) {
        _uiState.update { it.copy(fullScan = enabled) }
    }

    fun setSpeedEnabled(enabled: Boolean) {
        _uiState.update { it.copy(speedEnabled = enabled) }
    }

    fun setRegion(region: String) {
        _uiState.update { it.copy(region = region) }
    }

    fun setSpeedCount(count: Int) {
        _uiState.update { it.copy(speedCount = count) }
    }

    fun setMaxIps(value: Int) {
        _uiState.update { it.copy(maxIps = value) }
    }

    fun setPingConcurrency(value: Int) {
        _uiState.update { it.copy(pingConcurrency = value) }
        persistInt("pingConcurrency", value)
    }

    fun setSpeedConcurrency(value: Int) {
        _uiState.update { it.copy(speedConcurrency = value) }
        persistInt("speedConcurrency", value)
    }

    fun setCustomLines(lines: List<String>) {
        _uiState.update { it.copy(customLines = lines) }
    }

    private fun persistInt(key: String, value: Int) {
        viewModelScope.launch {
            runCatching { container.configRepository.set(key, value) }
        }
    }

    private suspend fun buildScanRequest(overrideLines: List<String>? = null): ScanRequest {
        val state = _uiState.value
        val persisted = container.configRepository.flow.first()
        val customLines = overrideLines ?: when (state.source) {
            IpSource.OFFICIAL, IpSource.CMIP -> container.assetIpLines(state.source)
            IpSource.CUSTOM -> state.customLines
        }
        return ScanRequest(
            source = state.source,
            customLines = customLines,
            maxIps = state.maxIps,
            fullScan = state.fullScan,
            ports = state.ports,
            multiPortBest = state.multiPortBest,
            speedEnabled = state.speedEnabled,
            region = state.region,
            speedCount = intOf(persisted, "speedCount", 50),
            probeCount = intOf(persisted, "probeCount", 500),
            fullProbeCount = intOf(persisted, "fullScanProbeCount", 5000),
            latencyLimit = floatOf(persisted, "latencyLimit", 200f),
            downloadTime = intOf(persisted, "downloadTime", 10),
            downloadCount = intOf(persisted, "downloadCount", 50),
            speedLimit = floatOf(persisted, "speedLimit", 0f),
            downloadUrl = persisted["downloadUrl"] as? String
                ?: "https://speed.hatexianyu.ccwu.cc/?bytes=209715200",
            pingCount = intOf(persisted, "pingCount", 2),
            pingTimeoutMs = 2000,
            pingConcurrency = intOf(persisted, "pingConcurrency", 200),
            speedConcurrency = intOf(persisted, "speedConcurrency", 5),
        )
    }

    private fun intOf(map: Map<String, Any>, key: String, default: Int): Int =
        (map[key] as? Number)?.toInt() ?: default

    private fun floatOf(map: Map<String, Any>, key: String, default: Float): Float =
        (map[key] as? Number)?.toFloat() ?: default

    private fun onEvent(event: ScanEvent) {
        when (event) {
            is ScanEvent.PhaseChanged -> _uiState.update { it.copy(phase = event.phase) }
            is ScanEvent.Progress -> _uiState.update {
                it.copy(
                    progress = event.pct.coerceIn(0, 100),
                    phase = event.text,
                    etaMs = event.etaMs,
                    totalScanned = if (event.total > 0) event.done else it.totalScanned,
                )
            }
            is ScanEvent.Log -> {
                trackGenerated(event.line)
                _uiState.update { s -> s.copy(log = s.log + event.line) }
            }
            is ScanEvent.ResultReady -> {
                lastResults = event.results
                container.lastResults.value = event.results
                val stats = ResultStats.compute(event.results)
                val topCode = stats.topRegions.firstOrNull()?.first
                _uiState.update {
                    it.copy(
                        resultCount = stats.total,
                        totalScanned = stats.total,
                        bestRegion = topCode?.let { code -> ColoRegionMapper.map(code) } ?: "-",
                        fastestMs = stats.fastestMs,
                        log = it.log + summaryLine(stats),
                    )
                }
            }
            is ScanEvent.Error -> _uiState.update {
                it.copy(log = it.log + "错误: ${event.message}")
            }
            ScanEvent.Done -> {
                _uiState.update { it.copy(running = false, quickIp = null) }
                _scanFinished.value = true
                persistHistoryIfNeeded()
            }
        }
    }

    private fun trackGenerated(line: String) {
        if (!line.startsWith("共生成 ")) return
        line.removePrefix("共生成 ")
            .substringBefore(" 个待测 IP")
            .toIntOrNull()
            ?.let { generatedCount = it }
    }

    private fun summaryLine(stats: ResultStats): String {
        val fastest = stats.fastestMs
            ?.let { String.format(Locale.US, "%.1f ms", it) }
            ?: "-"
        val region = stats.topRegions.firstOrNull()?.first
            ?.let { ColoRegionMapper.map(it) }
            ?: "-"
        return "扫描完成: ${stats.total} 个结果，最快 $fastest，最佳地区 $region"
    }

    private fun persistHistoryIfNeeded() {
        val results = lastResults
        if (results.isEmpty()) return
        lastResults = emptyList()
        val startedAt = scanStartedAt
        val count = generatedCount
        viewModelScope.launch {
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
            runCatching { container.historyRepository.add(entry) }
        }
    }
}
