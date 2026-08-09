@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)

package com.cfst.android.ui.screens.history

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.cfst.android.data.HistoryEntry
import com.cfst.android.engine.model.ScanResult
import com.cfst.android.ui.components.CopyIcon
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val TIME_FORMAT = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)

@Composable
fun HistoryScreen(modifier: Modifier = Modifier) {
    val vm: HistoryViewModel = viewModel()
    val entries by vm.all.collectAsState()
    val detailMap by vm.detailMap.collectAsState()
    val historySelection by vm.historySelection.collectAsState()
    val isRefreshing by vm.isRefreshing.collectAsState()
    val context = LocalContext.current
    var expandedId by rememberSaveable { mutableStateOf<Long?>(null) }
    var pendingDeleteId by rememberSaveable { mutableStateOf<Long?>(null) }
    var showClearDialog by rememberSaveable { mutableStateOf(false) }

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
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "历史",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { vm.refresh() }) {
                Icon(Icons.Filled.Refresh, contentDescription = "刷新")
            }
            TextButton(onClick = {
                if (entries.isEmpty()) {
                    Toast.makeText(context, "暂无数据", Toast.LENGTH_SHORT).show()
                } else {
                    val timestamp = System.currentTimeMillis()
                    csvExporter.launch("cf_speedtest_history_$timestamp.csv")
                }
            }) {
                Text("导出CSV")
            }
            if (entries.isNotEmpty()) {
                TextButton(onClick = { showClearDialog = true }) {
                    Text("清空")
                }
            }
        }

        val pullRefreshState = rememberPullToRefreshState()

        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = { vm.refresh() },
            state = pullRefreshState,
            modifier = Modifier.fillMaxSize(),
        ) {
            if (entries.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "暂无历史记录",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(entries, key = { it.id }) { entry ->
                        HistoryCard(
                            entry = entry,
                            expanded = expandedId == entry.id,
                            records = detailMap[entry.id],
                            selectedKeys = historySelection[entry.id] ?: emptySet(),
                            onToggle = {
                                expandedId = if (expandedId == entry.id) null else entry.id
                                if (expandedId == entry.id) {
                                    vm.loadDetail(entry.id)
                                } else {
                                    vm.collapseDetail(entry.id)
                                }
                            },
                            onDelete = { pendingDeleteId = entry.id },
                            onToggleSelect = { result ->
                                vm.toggleHistorySelect(entry.id, result.ip, result.port)
                            },
                            onToggleSelectAll = { vm.toggleHistorySelectAll(entry.id) },
                            onCopyRow = { result -> vm.copyHistoryRow(context, result) },
                            onCopySelected = { vm.copyHistorySelected(context, entry.id) },
                            onLongCopy = { vm.copyHistoryBest(context, entry.id) },
                        )
                    }
                }
            }
        }
    }

    val pendingEntry = entries.firstOrNull { it.id == pendingDeleteId }
    if (pendingEntry != null) {
        AlertDialog(
            onDismissRequest = { pendingDeleteId = null },
            title = { Text("删除该记录？") },
            text = { Text("删除后不可恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    vm.delete(pendingEntry.id)
                    pendingDeleteId = null
                }) {
                    Text("删除")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteId = null }) {
                    Text("取消")
                }
            },
        )
    }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text("清空全部历史？") },
            text = { Text("所有历史记录将被删除，此操作不可恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    vm.clearAll()
                    showClearDialog = false
                }) {
                    Text("清空")
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) {
                    Text("取消")
                }
            },
        )
    }
}

@Composable
private fun HistoryCard(
    entry: HistoryEntry,
    expanded: Boolean,
    records: List<ScanResult>?,
    selectedKeys: Set<String>,
    onToggle: () -> Unit,
    onDelete: () -> Unit,
    onToggleSelect: (ScanResult) -> Unit,
    onToggleSelectAll: () -> Unit,
    onCopyRow: (ScanResult) -> Unit,
    onCopySelected: () -> Unit,
    onLongCopy: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = onToggle,
                    onLongClick = onLongCopy,
                )
                .padding(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = formatTime(entry.startedAt),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = entrySummary(entry),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Filled.Delete, contentDescription = "删除")
                }
            }
            Text(
                text = entry.regionsSummary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (expanded) {
                Spacer(modifier = Modifier.height(8.dp))
                HistoryDetail(
                    records = records,
                    selectedKeys = selectedKeys,
                    onToggleSelect = onToggleSelect,
                    onToggleSelectAll = onToggleSelectAll,
                    onCopyRow = onCopyRow,
                    onCopySelected = onCopySelected,
                )
            }
        }
    }
}

@Composable
private fun HistoryDetail(
    records: List<ScanResult>?,
    selectedKeys: Set<String>,
    onToggleSelect: (ScanResult) -> Unit,
    onToggleSelectAll: () -> Unit,
    onCopyRow: (ScanResult) -> Unit,
    onCopySelected: () -> Unit,
) {
    if (records == null) {
        Text(
            text = "加载中...",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    if (records.isEmpty()) {
        Text(
            text = "无记录",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val selectedCount = records.count { rowKeyOf(it) in selectedKeys }
        TextButton(onClick = onToggleSelectAll) {
            Text(text = if (selectedCount == records.size) "取消全选" else "全选")
        }
        TextButton(onClick = onCopySelected) {
            Text("复制($selectedCount)")
        }
    }
    Row(modifier = Modifier.fillMaxWidth()) {
        Spacer(modifier = Modifier.width(52.dp))
        Text(
            text = "IP",
            modifier = Modifier.width(100.dp),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = "端口",
            modifier = Modifier.width(40.dp),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = "延迟",
            modifier = Modifier.width(56.dp),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = "速度",
            modifier = Modifier.width(64.dp),
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
    records.forEach { result ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (rowKeyOf(result) in selectedKeys) {
                        Modifier.background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f))
                    } else {
                        Modifier
                    },
                )
                .padding(vertical = 2.dp)
                .combinedClickable(
                    onClick = {},
                    onLongClick = { onCopyRow(result) },
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(
                checked = rowKeyOf(result) in selectedKeys,
                onCheckedChange = { onToggleSelect(result) },
            )
            Text(
                text = result.ip,
                modifier = Modifier.width(100.dp),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = result.port.toString(),
                modifier = Modifier.width(40.dp),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text = formatLatency(result.avgMs),
                modifier = Modifier.width(56.dp),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text = formatSpeed(result.speed),
                modifier = Modifier.width(64.dp),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text = result.regionName.ifBlank { result.regionCode }.ifBlank { "-" },
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            IconButton(
                onClick = { onCopyRow(result) },
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

private fun entrySummary(entry: HistoryEntry): String {
    val base = "IP ${entry.ipCount} / 结果 ${entry.resultCount}"
    return entry.fastestMs?.let { "$base    最快 $it ms" } ?: base
}

private fun formatTime(ts: Long): String = TIME_FORMAT.format(Date(ts))

private fun formatLatency(avgMs: Float?): String =
    avgMs?.let { String.format(Locale.US, "%.1f ms", it) } ?: "-"

private fun formatSpeed(speed: Float?): String =
    speed?.let { String.format(Locale.US, "%.2f MB/s", it) } ?: "-"