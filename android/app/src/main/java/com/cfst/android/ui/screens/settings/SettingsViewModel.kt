package com.cfst.android.ui.screens.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.cfst.android.CfApp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SettingsUiState(
    val downloadCount: Int = 50,
    val downloadTime: Int = 10,
    val speedLimit: Int = 0,
    val latencyLimit: Int = 200,
    val probeCount: Int = 500,
    val fullScanProbeCount: Int = 5000,
    val downloadUrl: String = "",
    val pingConcurrency: Int = 8,
    val speedConcurrency: Int = 5,
    val historyRetentionDays: Int = 30,
)

class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val container = (application as CfApp).container

    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            _state.value = buildState(container.configRepository.flow.first())
        }
    }

    fun setDownloadCount(value: Int) {
        updateInt("downloadCount", value, 5, 200) { s, v -> s.copy(downloadCount = v) }
    }

    fun setDownloadTime(value: Int) {
        updateInt("downloadTime", value, 5, 30) { s, v -> s.copy(downloadTime = v) }
    }

    fun setSpeedLimit(value: Int) {
        updateInt("speedLimit", value, 0, 100) { s, v -> s.copy(speedLimit = v) }
    }

    fun setLatencyLimit(value: Int) {
        updateInt("latencyLimit", value, 0, 10000) { s, v -> s.copy(latencyLimit = v) }
    }

    fun setProbeCount(value: Int) {
        updateInt("probeCount", value, 100, 50000) { s, v -> s.copy(probeCount = v) }
    }

    fun setFullScanProbeCount(value: Int) {
        updateInt("fullScanProbeCount", value, 100, 200000) { s, v -> s.copy(fullScanProbeCount = v) }
    }

    fun setDownloadUrl(value: String) {
        _state.update { it.copy(downloadUrl = value) }
        viewModelScope.launch {
            runCatching { container.configRepository.set("downloadUrl", value) }
        }
    }

    fun setPingConcurrency(value: Int) {
        updateInt("pingConcurrency", value, 1, 64) { s, v -> s.copy(pingConcurrency = v) }
    }

    fun setSpeedConcurrency(value: Int) {
        updateInt("speedConcurrency", value, 1, 32) { s, v -> s.copy(speedConcurrency = v) }
    }

    fun setRetentionDays(value: Int) {
        updateInt("historyRetentionDays", value, 1, 365) { s, v -> s.copy(historyRetentionDays = v) }
    }

    fun resetDefaults() {
        viewModelScope.launch {
            container.configRepository.resetDefaults()
            _state.value = buildState(container.configRepository.flow.first())
        }
    }

    private fun updateInt(
        key: String,
        value: Int,
        min: Int,
        max: Int,
        apply: (SettingsUiState, Int) -> SettingsUiState,
    ) {
        val clamped = value.coerceIn(min, max)
        _state.update { apply(it, clamped) }
        viewModelScope.launch {
            runCatching { container.configRepository.set(key, clamped) }
        }
    }

    private fun buildState(cfg: Map<String, Any>): SettingsUiState = SettingsUiState(
        downloadCount = intOf(cfg, "downloadCount", 50),
        downloadTime = intOf(cfg, "downloadTime", 10),
        speedLimit = intOf(cfg, "speedLimit", 0),
        latencyLimit = intOf(cfg, "latencyLimit", 200),
        probeCount = intOf(cfg, "probeCount", 500),
        fullScanProbeCount = intOf(cfg, "fullScanProbeCount", 5000),
        downloadUrl = cfg["downloadUrl"] as? String ?: "",
        pingConcurrency = intOf(cfg, "pingConcurrency", 8),
        speedConcurrency = intOf(cfg, "speedConcurrency", 5),
        historyRetentionDays = intOf(cfg, "historyRetentionDays", 30),
    )

    private fun intOf(map: Map<String, Any>, key: String, default: Int): Int =
        (map[key] as? Number)?.toInt() ?: default
}
