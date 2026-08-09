package com.cfst.android.ui.screens.history

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.cfst.android.CfApp
import com.cfst.android.data.HistoryEntry
import com.cfst.android.engine.CsvCodec
import com.cfst.android.engine.model.ResultFormatter
import com.cfst.android.engine.model.ScanResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class HistoryViewModel(application: Application) : AndroidViewModel(application) {

    private val container = (application as CfApp).container

    private val _refreshTick = MutableStateFlow(0)

    val all: StateFlow<List<HistoryEntry>> =
        combine(container.historyRepository.all, _refreshTick) { entries, _ -> entries }
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5_000),
                emptyList(),
            )

    private val _isRefreshing = MutableStateFlow(false)

    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    fun refresh() {
        viewModelScope.launch {
            _isRefreshing.value = true
            _refreshTick.update { it + 1 }
            delay(350)
            _isRefreshing.value = false
        }
    }

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
            _historySelection.update { it - id }
        }
    }

    fun clearAll() {
        viewModelScope.launch {
            container.historyRepository.clearAll()
            _detailMap.value = emptyMap()
            _historySelection.value = emptyMap()
        }
    }

    private val _historySelection = MutableStateFlow<Map<Long, Set<String>>>(emptyMap())

    val historySelection: StateFlow<Map<Long, Set<String>>> = _historySelection.asStateFlow()

    fun toggleHistorySelect(id: Long, ip: String, port: Int) {
        val key = rowKey(ip, port)
        _historySelection.update { map ->
            val cur = map[id] ?: emptySet()
            val next = if (key in cur) cur - key else cur + key
            if (next.isEmpty()) map - id else map + (id to next)
        }
    }

    fun toggleHistorySelectAll(id: Long) {
        _historySelection.update { map ->
            val records = _detailMap.value[id] ?: emptyList()
            val keys = records.map { rowKey(it.ip, it.port) }.toSet()
            if (keys.isEmpty()) return@update map
            val cur = map[id] ?: emptySet()
            val next = if (cur.containsAll(keys)) emptySet() else keys
            if (next.isEmpty()) map - id else map + (id to next)
        }
    }

    fun copyHistoryRow(context: Context, result: ScanResult) {
        clipboardPut(context, ResultFormatter.formatCopyLine(result))
        toast(context, "已复制 ${result.ip}")
    }

    fun copyHistorySelected(context: Context, id: Long) {
        val records = _detailMap.value[id] ?: emptyList()
        val keys = _historySelection.value[id] ?: emptySet()
        val selected = records.filter { rowKey(it.ip, it.port) in keys }
        if (selected.isEmpty()) {
            toast(context, "未选择任何结果")
            return
        }
        clipboardPut(context, ResultFormatter.formatCopyLines(selected))
        toast(context, "已复制 ${selected.size} 条结果")
    }

    fun copyHistoryBest(context: Context, id: Long) {
        viewModelScope.launch {
            val entry = all.value.firstOrNull { it.id == id } ?: return@launch
            val records = withContext(Dispatchers.IO) {
                runCatching { CsvCodec.parse(entry.recordsCsv) }.getOrDefault(emptyList())
            }
            val best = records.minByOrNull { it.avgMs ?: Float.MAX_VALUE }
            if (best != null) {
                copyHistoryRow(context, best)
            } else {
                toast(context, "该记录暂无明细")
            }
        }
    }

    fun exportCsv(context: Context, uri: Uri) {
        val entries = all.value
        if (entries.isEmpty()) {
            toast(context, "暂无数据")
            return
        }
        viewModelScope.launch {
            val records = withContext(Dispatchers.IO) {
                entries.flatMap { entry ->
                    runCatching { CsvCodec.parse(entry.recordsCsv) }.getOrDefault(emptyList())
                }
            }
            if (records.isEmpty()) {
                toast(context, "暂无数据")
                return@launch
            }
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val output = context.contentResolver.openOutputStream(uri)
                        ?: throw IllegalStateException("无法打开输出流")
                    output.use { out ->
                        out.write(CsvCodec.encode(records).toByteArray(Charsets.UTF_8))
                    }
                }.isSuccess
            }
            if (ok) {
                toast(context, "已导出 ${records.size} 条结果")
            } else {
                toast(context, "导出失败")
            }
        }
    }

    private fun rowKey(ip: String, port: Int) = "$ip:$port"

    private fun clipboardPut(context: Context, text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("CF测速结果", text))
    }

    private fun toast(context: Context, message: String) {
        Toast.makeText(context.applicationContext, message, Toast.LENGTH_SHORT).show()
    }
}
