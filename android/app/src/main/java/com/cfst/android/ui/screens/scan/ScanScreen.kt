@file:OptIn(ExperimentalMaterial3Api::class)

package com.cfst.android.ui.screens.scan

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.cfst.android.engine.ColoRegionMapper
import com.cfst.android.engine.model.IpSource
import com.cfst.android.ui.components.StatCard
import java.util.Locale

private val PORT_OPTIONS = listOf(443, 8443, 2083, 2087, 2053, 2096)
private val REGION_OPTIONS = listOf(
    "全部", "HKG", "LAX", "SIN", "NRT", "FRA", "LHR", "AMS", "SEA", "SJC", "ICN",
)
private val SPEED_COUNT_OPTIONS = listOf(10, 20, 50, 100)
private val SCENE_OPTIONS = listOf(
    ScanScene.QUICK to "一键极速",
    ScanScene.SINGLE to "单端口快速",
    ScanScene.CUSTOM to "自定义",
)
private val SOURCE_OPTIONS = listOf(
    IpSource.OFFICIAL to "官方库(150万)",
    IpSource.CMIP to "CMIP(6.3万)",
    IpSource.CUSTOM to "自定义文件",
)

@Composable
fun ScanScreen(modifier: Modifier = Modifier, onScanFinished: () -> Unit = {}) {
    val vm: ScanViewModel = viewModel()
    val state by vm.uiState.collectAsState()
    val scanFinished by vm.scanFinished.collectAsState()
    val context = LocalContext.current
    var speedExpanded by remember { mutableStateOf(false) }
    var regionExpanded by remember { mutableStateOf(false) }
    var showQuickTestDialog by remember { mutableStateOf(false) }
    var quickIpText by remember { mutableStateOf("") }
    var quickPortText by remember { mutableStateOf("443") }
    val logListState = rememberLazyListState()

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { vm.setCustomLines(readUriLines(context, it)) }
    }

    LaunchedEffect(state.log.size) {
        if (state.log.isNotEmpty()) {
            logListState.scrollToItem(state.log.size - 1)
        }
    }

    LaunchedEffect(scanFinished) {
        if (scanFinished) {
            onScanFinished()
            vm.consumeScanFinished()
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(text = "测速", style = MaterialTheme.typography.headlineMedium)

        Text(text = "场景", style = MaterialTheme.typography.titleMedium)
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            SCENE_OPTIONS.forEachIndexed { index, (scene, label) ->
                SegmentedButton(
                    selected = state.scene == scene,
                    onClick = { vm.onSceneSelected(scene) },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = SCENE_OPTIONS.size),
                ) {
                    Text(label)
                }
            }
        }

        Text(text = "IP 来源", style = MaterialTheme.typography.titleMedium)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SOURCE_OPTIONS.forEach { (source, label) ->
                FilterChip(
                    selected = state.source == source,
                    onClick = {
                        vm.setSource(source)
                        if (source == IpSource.CUSTOM) {
                            filePicker.launch(arrayOf("*/*"))
                        }
                    },
                    label = { Text(label) },
                )
            }
        }

        Text(text = "端口", style = MaterialTheme.typography.titleMedium)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PORT_OPTIONS.forEach { port ->
                FilterChip(
                    selected = port in state.ports,
                    onClick = { vm.togglePort(port) },
                    label = { Text(port.toString()) },
                )
            }
        }

OutlinedTextField(
            value = state.maxIps.toString(),
            onValueChange = { raw ->
                vm.setMaxIps(raw.filter { it.isDigit() }.toIntOrNull() ?: 0)
            },
            label = { Text("抽取IP数 (0 = 采样)") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = state.pingConcurrency.toString(),
                onValueChange = { raw ->
                    vm.setPingConcurrency((raw.filter { it.isDigit() }.toIntOrNull()?.coerceIn(1, 1500)) ?: 1)
                },
                label = { Text("延迟并发 (1-1500)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = state.speedConcurrency.toString(),
                onValueChange = { raw ->
                    vm.setSpeedConcurrency(raw.filter { it.isDigit() }.toIntOrNull()?.coerceIn(1, 32) ?: 1)
                },
                label = { Text("测速并发 (1-32)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f),
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = "多端口择优", modifier = Modifier.weight(1f))
            Switch(checked = state.multiPortBest, onCheckedChange = vm::setMultiPortBest)
            Spacer(modifier = Modifier.width(16.dp))
            Text(text = "全量扫描", modifier = Modifier.weight(1f))
            Switch(checked = state.fullScan, onCheckedChange = vm::setFullScan)
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { speedExpanded = !speedExpanded },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "下载测速",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(checked = state.speedEnabled, onCheckedChange = vm::setSpeedEnabled)
                }
                if (speedExpanded) {
                    Spacer(modifier = Modifier.height(8.dp))
                    ExposedDropdownMenuBox(
                        expanded = regionExpanded,
                        onExpandedChange = { regionExpanded = it },
                    ) {
                        OutlinedTextField(
                            value = ColoRegionMapper.map(state.region),
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("测速地区") },
                            trailingIcon = {
                                ExposedDropdownMenuDefaults.TrailingIcon(expanded = regionExpanded)
                            },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .menuAnchor(MenuAnchorType.PrimaryNotEditable),
                        )
                        ExposedDropdownMenu(
                            expanded = regionExpanded,
                            onDismissRequest = { regionExpanded = false },
                        ) {
                            REGION_OPTIONS.forEach { raw ->
                                DropdownMenuItem(
                                    text = { Text(ColoRegionMapper.map(raw)) },
                                    onClick = {
                                        vm.setRegion(raw)
                                        regionExpanded = false
                                    },
                                    trailingIcon = if (state.region == raw) {
                                        { Icon(Icons.Filled.Check, contentDescription = null) }
                                    } else null,
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(text = "测速数量", style = MaterialTheme.typography.labelLarge)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        SPEED_COUNT_OPTIONS.forEach { count ->
                            FilterChip(
                                selected = state.speedCount == count,
                                onClick = { vm.setSpeedCount(count) },
                                label = { Text(count.toString()) },
                            )
                        }
                    }
                }
            }
        }

Button(
            onClick = { if (state.running) vm.cancelScan() else vm.start() },
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
        ) {
            Text(if (state.running) "取消" else "开始扫描")
        }

        OutlinedButton(
            onClick = {
                quickIpText = ""
                quickPortText = "443"
                showQuickTestDialog = true
            },
            enabled = !state.running,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
        ) {
            Text("单IP测量")
        }

        if (state.running) {
            LinearProgressIndicator(
                progress = { state.progress.coerceIn(0, 100) / 100f },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(text = state.phase, style = MaterialTheme.typography.bodyMedium)
            if (state.quickIp != null) {
                Text(
                    text = "正在测速 IP: ${state.quickIp}",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        if (state.running) {
            state.etaMs?.let { eta ->
                Spacer(modifier = Modifier.height(4.dp))
                Text(text = formatEta(eta), style = MaterialTheme.typography.bodySmall)
            }
        }

        Text(text = "测速概况", style = MaterialTheme.typography.titleMedium)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            StatCard(
                title = "已测IP",
                value = state.totalScanned.toString(),
                modifier = Modifier.weight(1f),
            )
            StatCard(title = "最佳地区", value = state.bestRegion, modifier = Modifier.weight(1f))
            StatCard(
                title = "最快延迟",
                value = state.fastestMs?.let { String.format(Locale.US, "%.1f ms", it) } ?: "-",
                modifier = Modifier.weight(1f),
            )
        }

        Text(text = "日志", style = MaterialTheme.typography.titleMedium)
        if (state.log.isEmpty()) {
            Text(text = "暂无日志", style = MaterialTheme.typography.bodySmall)
        } else {
LazyColumn(
                state = logListState,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp),
            ) {
                items(state.log) { line ->
                    Text(
                        text = line,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(vertical = 2.dp),
                    )
                }
            }
        }
    }

    if (showQuickTestDialog) {
        AlertDialog(
            onDismissRequest = { showQuickTestDialog = false },
            title = { Text("单IP测量") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = quickIpText,
                        onValueChange = { quickIpText = it },
                        label = { Text("IP 地址") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = quickPortText,
                        onValueChange = { raw ->
                            quickPortText = raw.filter { it.isDigit() }
                        },
                        label = { Text("端口") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val ip = quickIpText.trim()
                        val port = quickPortText.toIntOrNull() ?: 443
                        if (ip.isNotEmpty()) {
                            vm.quickTest(ip, port)
                        }
                        showQuickTestDialog = false
                    },
                ) {
                    Text("开始测量")
                }
            },
            dismissButton = {
                TextButton(onClick = { showQuickTestDialog = false }) {
                    Text("取消")
                }
            },
        )
    }
}

private fun readUriLines(context: Context, uri: Uri): List<String> {
    val lines = runCatching {
        context.contentResolver.openInputStream(uri)
            ?.bufferedReader()
            ?.use { it.readLines() }
            ?: emptyList()
    }.getOrElse { emptyList() }
    return lines.map { it.trim() }.filter { it.isNotEmpty() }
}

private fun formatEta(etaMs: Long): String {
    val totalSeconds = (etaMs / 1000).coerceAtLeast(0)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return if (minutes > 0) "预计还需 $minutes 分 $seconds 秒" else "预计还需 $seconds 秒"
}
