@file:OptIn(ExperimentalMaterial3Api::class)

package com.cfst.android.ui.screens.result

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.cfst.android.engine.model.ScanResult
import java.util.Locale

@Composable
fun ResultScreen(modifier: Modifier = Modifier) {
    val vm: ResultViewModel = viewModel()
    val state by vm.uiState.collectAsState()
    val context = LocalContext.current

    val csvExporter = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv"),
    ) { uri ->
        if (uri != null) vm.exportCsv(context, uri)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(text = "结果", style = MaterialTheme.typography.headlineMedium)

        if (state.results.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "暂无结果，请先在「测速」页开始扫描",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                state.regions.forEach { region ->
                    FilterChip(
                        selected = state.regionFilter == region,
                        onClick = { vm.setRegionFilter(region) },
                        label = { Text(region) },
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "共 ${state.filteredResults.size} 个结果",
                    style = MaterialTheme.typography.titleSmall,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { vm.setSortMode(nextSortMode(state.sortMode)) }) {
                        Icon(Icons.Filled.List, contentDescription = "切换排序")
                    }
                    IconButton(onClick = { vm.copyAll(context) }) {
                        Icon(Icons.Filled.Share, contentDescription = "复制全部")
                    }
                    IconButton(onClick = {
                        val timestamp = System.currentTimeMillis()
                        csvExporter.launch("cf_speedtest_results_$timestamp.csv")
                    }) {
                        Icon(Icons.Filled.Send, contentDescription = "导出CSV")
                    }
                }
            }

            Text(
                text = sortModeLabel(state.sortMode),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            LazyColumn(modifier = Modifier.fillMaxSize()) {
                item(key = "header") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                    ) {
                        Text(
                            text = "IP 地址",
                            modifier = Modifier.width(110.dp),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = "端口",
                            modifier = Modifier.width(48.dp),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = "延迟",
                            modifier = Modifier.width(64.dp),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = "速度",
                            modifier = Modifier.width(72.dp),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = "地区",
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                items(state.filteredResults) { result ->
                    ResultRow(result)
                }
            }
        }
    }
}

@Composable
private fun ResultRow(result: ScanResult) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = result.ip,
            modifier = Modifier.width(110.dp),
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = result.port.toString(),
            modifier = Modifier.width(48.dp),
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            text = formatLatency(result.avgMs),
            modifier = Modifier.width(64.dp),
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            text = formatSpeed(result.speed),
            modifier = Modifier.width(72.dp),
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            text = formatRegion(result),
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun nextSortMode(current: ResultSortMode): ResultSortMode = when (current) {
    ResultSortMode.LATENCY_ASC -> ResultSortMode.SPEED_DESC
    ResultSortMode.SPEED_DESC -> ResultSortMode.IP_ASC
    ResultSortMode.IP_ASC -> ResultSortMode.LATENCY_ASC
}

private fun sortModeLabel(mode: ResultSortMode): String = when (mode) {
    ResultSortMode.LATENCY_ASC -> "排序：延迟↑"
    ResultSortMode.SPEED_DESC -> "排序：速度↓"
    ResultSortMode.IP_ASC -> "排序：IP↑"
}

private fun formatLatency(avgMs: Float?): String =
    avgMs?.let { String.format(Locale.US, "%.1f ms", it) } ?: "-"

private fun formatSpeed(speed: Float?): String =
    speed?.let { String.format(Locale.US, "%.2f MB/s", it) } ?: "-"

private fun formatRegion(result: ScanResult): String =
    result.regionName.ifBlank { result.regionCode }.ifBlank { "-" }
