package com.cfst.android.ui.screens.result

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.cfst.android.CfApp
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
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class ResultSortMode { LATENCY_ASC, SPEED_DESC, LOSS_ASC }

data class ResultUiState(
    val results: List<ScanResult> = emptyList(),
    val regionFilter: String = "全部",
    val requestedRegion: String = "全部",
    val regions: List<String> = emptyList(),
    val sortMode: ResultSortMode = ResultSortMode.LATENCY_ASC,
    val filteredResults: List<ScanResult> = emptyList(),
)

class ResultViewModel(application: Application) : AndroidViewModel(application) {

    private val container = (application as CfApp).container

    private val _filterState = MutableStateFlow(ResultUiState())

    private val _refreshTick = MutableStateFlow(0)

    val uiState: StateFlow<ResultUiState> =
        combine(container.lastResults, _filterState, _refreshTick) { results, filter, _ ->
            computeState(results, filter)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ResultUiState())

    private val _isRefreshing = MutableStateFlow(false)

    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    fun refresh(context: Context) {
        viewModelScope.launch {
            _isRefreshing.value = true
            _refreshTick.update { it + 1 }
            delay(80)
            _isRefreshing.value = false
            if (uiState.value.results.isEmpty()) {
                toast(context, "暂无扫描结果")
            } else {
                toast(context, "结果来自最近一次扫描")
            }
        }
    }

    fun setRegionFilter(region: String) {
        _filterState.update { it.copy(regionFilter = region) }
    }

    fun setSortMode(mode: ResultSortMode) {
        _filterState.update { it.copy(sortMode = mode) }
    }

    fun ackRegionFallback() {
        _filterState.update { it.copy(regionFilter = "全部") }
    }

    fun copyAll(context: Context) {
        val results = uiState.value.filteredResults
        if (results.isEmpty()) {
            toast(context, "暂无结果可复制")
            return
        }
        clipboardPut(context, ResultFormatter.formatCopyLines(results))
        toast(context, "已复制 ${results.size} 条结果")
    }

    fun exportCsv(context: Context, uri: Uri) {
        val results = uiState.value.filteredResults
        if (results.isEmpty()) {
            toast(context, "暂无结果可导出")
            return
        }
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val output = context.contentResolver.openOutputStream(uri)
                        ?: throw IllegalStateException("无法打开输出流")
                    output.use { out ->
                        out.write(CsvCodec.encode(results).toByteArray(Charsets.UTF_8))
                    }
                }.isSuccess
            }
            if (ok) {
                toast(context, "已导出 ${results.size} 条结果")
            } else {
                toast(context, "导出失败")
            }
        }
    }

    private val _editing = MutableStateFlow(false)

    val editing: StateFlow<Boolean> = _editing.asStateFlow()

    private val _selectedIps = MutableStateFlow<Set<String>>(emptySet())

    val selectedIps: StateFlow<Set<String>> = _selectedIps.asStateFlow()

    init {
        viewModelScope.launch {
            uiState.collect { state ->
                val visible = HashSet<String>(state.filteredResults.size)
                state.filteredResults.forEach { visible.add(rowKey(it.ip, it.port)) }
                _selectedIps.update { cur ->
                    if (cur.isEmpty() || cur.all { it in visible }) {
                        cur
                    } else {
                        cur.filter { it in visible }.toSet()
                    }
                }
            }
        }
    }

    fun setEditing(enabled: Boolean) {
        _editing.value = enabled
        if (!enabled) _selectedIps.value = emptySet()
    }

    fun toggleSelect(ip: String, port: Int) {
        val key = rowKey(ip, port)
        _selectedIps.update { cur ->
            if (key in cur) cur - key else cur + key
        }
    }

    fun selectAll() {
        _selectedIps.value =
            uiState.value.filteredResults.map { rowKey(it.ip, it.port) }.toSet()
    }

    fun clearSelection() {
        _selectedIps.value = emptySet()
    }

    fun copyRow(context: Context, result: ScanResult) {
        clipboardPut(context, ResultFormatter.formatCopyLine(result))
        toast(context, "已复制 ${result.ip}")
    }

    fun copySelected(context: Context) {
        val selected = uiState.value.filteredResults
            .filter { rowKey(it.ip, it.port) in _selectedIps.value }
        if (selected.isEmpty()) {
            toast(context, "未选择任何结果")
            return
        }
        clipboardPut(context, ResultFormatter.formatCopyLines(selected))
        toast(context, "已复制 ${selected.size} 条结果")
    }

    private fun rowKey(ip: String, port: Int) = "$ip:$port"

    private fun clipboardPut(context: Context, text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("CF测速结果", text))
    }

    private fun computeState(results: List<ScanResult>, filter: ResultUiState): ResultUiState {
        val allRegions = if (results.isEmpty()) {
            emptyList()
        } else {
            listOf("全部") + results.map { it.regionName }.filter { it.isNotBlank() }.distinct()
        }
        val requested = filter.regionFilter
        val regionFallback = requested != "全部" && requested !in allRegions
        val effectiveRegion = if (regionFallback) "全部" else requested
        val base = if (effectiveRegion == "全部") {
            results
        } else {
            results.filter { it.regionName == effectiveRegion }
        }
        val sorted = when (filter.sortMode) {
            ResultSortMode.LATENCY_ASC ->
                base.sortedWith(
                    compareBy<ScanResult> { it.avgMs ?: Float.MAX_VALUE }.thenBy { it.ip },
                )
            ResultSortMode.SPEED_DESC ->
                base.sortedWith(
                    compareByDescending<ScanResult> { it.speed ?: -1f }.thenBy { it.ip },
                )
            ResultSortMode.LOSS_ASC ->
                base.sortedWith(compareBy<ScanResult> { it.lossPct }.thenBy { it.ip })
        }
        return ResultUiState(
            results = results,
            regionFilter = effectiveRegion,
            requestedRegion = if (regionFallback) requested else "全部",
            regions = allRegions,
            sortMode = filter.sortMode,
            filteredResults = sorted,
        )
    }

    private fun toast(context: Context, message: String) {
        Toast.makeText(context.applicationContext, message, Toast.LENGTH_SHORT).show()
    }
}
