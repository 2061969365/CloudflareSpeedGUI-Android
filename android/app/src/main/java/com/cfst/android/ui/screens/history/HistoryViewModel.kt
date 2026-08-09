package com.cfst.android.ui.screens.history

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.cfst.android.CfApp
import com.cfst.android.data.HistoryEntry
import com.cfst.android.engine.CsvCodec
import com.cfst.android.engine.model.ScanResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class HistoryViewModel(application: Application) : AndroidViewModel(application) {

    private val container = (application as CfApp).container

    val all: StateFlow<List<HistoryEntry>> =
        container.historyRepository.all.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            emptyList(),
        )

    private val _detailMap = MutableStateFlow<Map<Long, List<ScanResult>>>(emptyMap())
    val detailMap: StateFlow<Map<Long, List<ScanResult>>> = _detailMap.asStateFlow()

    init {
        viewModelScope.launch {
            val days = container.configRepository.flow.first()["historyRetentionDays"] as? Int ?: 30
            container.historyRepository.deleteOlderThan(
                System.currentTimeMillis() - days * 86_400_000L,
            )
        }
    }

    fun loadDetail(id: Long) {
        if (_detailMap.value.containsKey(id)) return
        viewModelScope.launch {
            val entry = all.value.firstOrNull { it.id == id } ?: return@launch
            val records = withContext(Dispatchers.IO) {
                runCatching { CsvCodec.parse(entry.recordsCsv) }.getOrDefault(emptyList())
            }
            _detailMap.update { it + (id to records) }
        }
    }

    fun collapseDetail(id: Long) {
        _detailMap.update { it - id }
    }

    fun delete(id: Long) {
        viewModelScope.launch {
            container.historyRepository.delete(id)
            _detailMap.update { it - id }
        }
    }

    fun clearAll() {
        viewModelScope.launch {
            container.historyRepository.clearAll()
            _detailMap.value = emptyMap()
        }
    }
}
