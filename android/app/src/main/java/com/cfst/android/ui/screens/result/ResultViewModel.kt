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
import com.cfst.android.engine.model.ScanResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class ResultSortMode { LATENCY_ASC, SPEED_DESC, IP_ASC }

data class ResultUiState(
    val results: List<ScanResult> = emptyList(),
    val regionFilter: String = "全部",
    val regions: List<String> = emptyList(),
    val sortMode: ResultSortMode = ResultSortMode.LATENCY_ASC,
    val filteredResults: List<ScanResult> = emptyList(),
)

class ResultViewModel(application: Application) : AndroidViewModel(application) {

    private val container = (application as CfApp).container

    private val _filterState = MutableStateFlow(ResultUiState())

    val uiState: StateFlow<ResultUiState> =
        combine(container.lastResults, _filterState) { results, filter ->
            computeState(results, filter)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ResultUiState())

    fun setRegionFilter(region: String) {
        _filterState.update { it.copy(regionFilter = region) }
    }

    fun setSortMode(mode: ResultSortMode) {
        _filterState.update { it.copy(sortMode = mode) }
    }

    fun copyAll(context: Context) {
        val results = uiState.value.filteredResults
        if (results.isEmpty()) {
            toast(context, "暂无结果可复制")
            return
        }
        viewModelScope.launch {
            val csv = withContext(Dispatchers.IO) { CsvCodec.encode(results) }
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("CF测速结果", csv))
            toast(context, "已复制 ${results.size} 条结果")
        }
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

    private fun computeState(results: List<ScanResult>, filter: ResultUiState): ResultUiState {
        val allRegions = if (results.isEmpty()) {
            emptyList()
        } else {
            listOf("全部") + results.map { it.regionName }.filter { it.isNotBlank() }.distinct()
        }
        val effectiveRegion =
            if (filter.regionFilter == "全部" || filter.regionFilter in allRegions) {
                filter.regionFilter
            } else {
                "全部"
            }
        val base = if (effectiveRegion == "全部") {
            results
        } else {
            results.filter { it.regionName == effectiveRegion }
        }
        val sorted = when (filter.sortMode) {
            ResultSortMode.LATENCY_ASC ->
                base.sortedWith(
                    compareBy<ScanResult, Float>(nullsLast()) { it.avgMs }.thenBy { it.ip },
                )
            ResultSortMode.SPEED_DESC ->
                base.sortedWith(
                    compareByDescending<ScanResult> { it.speed }.thenBy { it.ip },
                )
            ResultSortMode.IP_ASC -> base.sortedWith(compareBy<ScanResult> { it.ip })
        }
        return ResultUiState(
            results = results,
            regionFilter = effectiveRegion,
            regions = allRegions,
            sortMode = filter.sortMode,
            filteredResults = sorted,
        )
    }

    private fun toast(context: Context, message: String) {
        Toast.makeText(context.applicationContext, message, Toast.LENGTH_SHORT).show()
    }
}
