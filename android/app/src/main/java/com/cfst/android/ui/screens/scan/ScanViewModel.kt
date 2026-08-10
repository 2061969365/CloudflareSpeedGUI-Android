package com.cfst.android.ui.screens.scan

import android.app.Application
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.cfst.android.CfApp
import com.cfst.android.engine.ColoRegionMapper
import com.cfst.android.engine.cfst.CfstBinary
import com.cfst.android.engine.ScanController
import com.cfst.android.engine.model.IpSource
import com.cfst.android.engine.model.ResultStats
import com.cfst.android.engine.model.ScanEvent
import com.cfst.android.engine.model.ScanRequest
import com.cfst.android.engine.model.ScanResult
import com.cfst.android.service.ScanService
import com.cfst.android.service.ScanStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
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

    private val _scanFinished = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val scanFinished: SharedFlow<Unit> = _scanFinished.asSharedFlow()

    private var sourceBeforeCustom: IpSource? = null
    private var lastResults: List<ScanResult> = emptyList()

    init {
        val restored = ScanService.scanStatus.value
        if (restored.running) {
            _uiState.update {
                it.copy(running = true, phase = restored.phase, progress = restored.progress)
            }
        }
        viewModelScope.launch {
            val persisted = runCatching { container.configRepository.flow.first() }
                .getOrElse { emptyMap() }
            _uiState.update {
                it.copy(
                    pingConcurrency = (persisted["pingConcurrency"] as? Number)?.toInt()
                        ?: it.pingConcurrency,
                    speedConcurrency = (persisted["speedConcurrency"] as? Number)?.toInt()
                        ?: it.speedConcurrency,
                )
            }
            controller.events.collect { event -> onEvent(event) }
        }
    }

    fun start() {
        if (_uiState.value.running) return
        if (ScanService.scanStatus.value.running) {
            val global = ScanService.scanStatus.value
            _uiState.update {
                it.copy(running = true, phase = global.phase, progress = global.progress)
            }
            return
        }
        if (_uiState.value.source == IpSource.CUSTOM && _uiState.value.customLines.isEmpty()) {
            _uiState.update {
                it.copy(log = (it.log + "请先导入自定义 IP 文件").takeLast(LOG_LIMIT))
            }
            return
        }
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
        ScanService.scanStatus.value = ScanStatus(running = true, phase = "生成IP列表", progress = 0)
        viewModelScope.launch {
            lastResults = emptyList()
            val request = withContext(Dispatchers.IO) { buildScanRequest() }
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
        if (ScanService.scanStatus.value.running) return
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
        ScanService.scanStatus.value = ScanStatus(running = true, phase = "生成IP列表", progress = 0)
        viewModelScope.launch {
            lastResults = emptyList()
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
            val app = getApplication<Application>()
            ContextCompat.startForegroundService(
                app,
                Intent(app, ScanService::class.java).setAction(ScanService.ACTION_START),
            )
            controller.start(request)
        }
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
                    region = "全部",
                    maxIps = 500,
                    source = IpSource.OFFICIAL,
                )
                ScanScene.SINGLE -> it.copy(
                    scene = ScanScene.SINGLE,
                    ports = listOf(443),
                    multiPortBest = false,
                    fullScan = false,
                    speedEnabled = false,
                    region = "全部",
                    maxIps = 500,
                    source = IpSource.OFFICIAL,
                )
                ScanScene.CUSTOM -> it.copy(scene = ScanScene.CUSTOM)
            }
        }
    }

    fun setSource(source: IpSource) {
        _uiState.update { it.copy(source = source) }
    }

    fun selectCustomSource() {
        sourceBeforeCustom = _uiState.value.source
        _uiState.update { it.copy(source = IpSource.CUSTOM) }
    }

    fun revertCustomSource() {
        sourceBeforeCustom?.let { previous ->
            _uiState.update { it.copy(source = previous) }
        }
        sourceBeforeCustom = null
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
        persistInt("speedCount", count)
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
        val persistedUrl = persisted["downloadUrl"] as? String
        return ScanRequest(
            source = state.source,
            customLines = customLines,
            maxIps = state.maxIps,
            fullScan = state.fullScan,
            ports = state.ports,
            multiPortBest = state.multiPortBest,
            speedEnabled = state.speedEnabled,
            region = state.region,
            speedCount = state.speedCount,
            probeCount = intOf(persisted, "probeCount", 500),
            fullProbeCount = intOf(persisted, "fullScanProbeCount", 5000),
            latencyLimit = floatOf(persisted, "latencyLimit", 200f),
            downloadTime = intOf(persisted, "downloadTime", 10),
            downloadCount = intOf(persisted, "downloadCount", 50),
            speedLimit = floatOf(persisted, "speedLimit", 0f),
            downloadUrl = if (persistedUrl.isNullOrBlank()) {
                CfstBinary.DEFAULT_SPEED_URL
            } else {
                persistedUrl
            },
            pingCount = intOf(persisted, "pingCount", 2),
            pingTimeoutMs = 2000,
            pingConcurrency = state.pingConcurrency,
            speedConcurrency = state.speedConcurrency,
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
                _uiState.update { s -> s.copy(log = (s.log + event.line).takeLast(LOG_LIMIT)) }
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
                        log = (it.log + summaryLine(stats)).takeLast(LOG_LIMIT),
                    )
                }
            }
            is ScanEvent.Error -> _uiState.update {
                it.copy(
                    running = false,
                    quickIp = null,
                    log = (it.log + "错误: ${event.message}").takeLast(LOG_LIMIT),
                )
            }
            ScanEvent.Cancelled -> _uiState.update {
                it.copy(
                    running = false,
                    quickIp = null,
                    log = (it.log + "扫描已取消").takeLast(LOG_LIMIT),
                )
            }
            ScanEvent.Done -> {
                val hasResults = lastResults.isNotEmpty()
                _uiState.update { it.copy(running = false, quickIp = null) }
                if (hasResults) _scanFinished.tryEmit(Unit)
            }
        }
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

    private companion object {
        const val LOG_LIMIT = 200
    }
}
