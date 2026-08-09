@file:OptIn(ExperimentalMaterial3Api::class)

package com.cfst.android.ui.screens.result

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.cfst.android.ui.components.CopyIcon
import java.util.Locale

@Composable
fun ResultScreen(modifier: Modifier = Modifier) {
    val vm: ResultViewModel = viewModel()
    val state by vm.uiState.collectAsState()
    val editing by vm.editing.collectAsState()
    val selectedIps by vm.selectedIps.collectAsState()
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
                    IconButton(onClick = { vm.setEditing(!editing) }) {
                        Icon(
                            imageVector = if (editing) Icons.Filled.Close else Icons.Filled.Check,
                            contentDescription = if (editing) "退出选择" else "进入多选",
                        )
                    }
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

            LazyColumn(modifier = Modifier.weight(1f)) {
                item(key = "header") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                    ) {
                        if (editing) {
                            Spacer(modifier = Modifier.width(48.dp))
                        }
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
                            text = "丢包",
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
                    ResultRow(
                        result = result,
                        editing = editing,
                        selected = rowKeyOf(result) in selectedIps,
                        onToggleSelect = { vm.toggleSelect(result.ip, result.port) },
                        onCopy = { vm.copyRow(context, result) },
                    )
                }
            }

            if (editing) {
                ResultSelectionBar(
                    selectedCount = state.filteredResults.count { rowKeyOf(it) in selectedIps },
                    visibleCount = state.filteredResults.size,
                    onSelectAll = {
                        if (state.filteredResults.count { rowKeyOf(it) in selectedIps } == state.filteredResults.size) {
                            vm.clearSelection()
                        } else {
                            vm.selectAll()
                        }
                    },
                    onCopySelected = { vm.copySelected(context) },
                    onExit = { vm.setEditing(false) },
                )
            }
        }
    }
}

@Composable
private fun ResultSelectionBar(
    selectedCount: Int,
    visibleCount: Int,
    onSelectAll: () -> Unit,
    onCopySelected: () -> Unit,
    onExit: () -> Unit,
) {
    Surface(modifier = Modifier.fillMaxWidth(), tonalElevation = 4.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TextButton(onClick = onSelectAll) {
                Text(text = if (selectedCount == visibleCount) "取消全选" else "全选")
            }
            Spacer(modifier = Modifier.weight(1f))
            Button(onClick = onCopySelected, enabled = selectedCount > 0) {
                Text("复制($selectedCount)")
            }
            IconButton(onClick = onExit) {
                Icon(Icons.Filled.Close, contentDescription = "退出选择")
            }
        }
    }
}

@Composable
private fun ResultRow(
    result: ScanResult,
    editing: Boolean,
    selected: Boolean,
    onToggleSelect: () -> Unit,
    onCopy: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (selected) {
                    Modifier.background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f))
                } else {
                    Modifier
                },
            )
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (editing) {
            Checkbox(
                checked = selected,
                onCheckedChange = { onToggleSelect() },
            )
        }
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
            text = formatLoss(result.lossPct),
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
        if (!editing) {
            IconButton(
                onClick = onCopy,
                modifier = Modifier.size(32.dp),
            ) {
                Icon(
                    imageVector = CopyIcon,
                    contentDescription = "复制",
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

private fun rowKeyOf(result: ScanResult): String = "${result.ip}:${result.port}"

private fun nextSortMode(current: ResultSortMode): ResultSortMode = when (current) {
    ResultSortMode.LATENCY_ASC -> ResultSortMode.SPEED_DESC
    ResultSortMode.SPEED_DESC -> ResultSortMode.LOSS_ASC
    ResultSortMode.LOSS_ASC -> ResultSortMode.LATENCY_ASC
}

private fun sortModeLabel(mode: ResultSortMode): String = when (mode) {
    ResultSortMode.LATENCY_ASC -> "排序：延迟↑"
    ResultSortMode.SPEED_DESC -> "排序：速度↓"
    ResultSortMode.LOSS_ASC -> "排序：丢包率↑"
}

private fun formatLatency(avgMs: Float?): String =
    avgMs?.let { String.format(Locale.US, "%.1f ms", it) } ?: "-"

private fun formatSpeed(speed: Float?): String =
    speed?.let { String.format(Locale.US, "%.2f MB/s", it) } ?: "-"

private fun formatLoss(lossPct: Float): String =
    if (lossPct == 0f) "0%" else String.format(Locale.US, "%.1f%%", lossPct)

private fun formatRegion(result: ScanResult): String =
    result.regionName.ifBlank { result.regionCode }.ifBlank { "-" }