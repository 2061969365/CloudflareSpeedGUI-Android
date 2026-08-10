@file:OptIn(ExperimentalMaterial3Api::class)

package com.cfst.android.ui.screens.scan

import android.content.Context
import android.net.Uri
import android.widget.Toast
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
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.runtime.rememberCoroutineScope
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
private const val MAX_IPS = 500_000

@Composable
fun ScanScreen(modifier: Modifier = Modifier, onScanFinished: () -> Unit = {}) {
    val vm: ScanViewModel = viewModel()
    val state by vm.uiState.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var speedExpanded by remember { mutableStateOf(false) }
    var regionExpanded by remember { mutableStateOf(false) }
    var showQuickTestDialog by remember { mutableStateOf(false) }
    var quickIpText by remember { mutableStateOf("") }
    var quickPortText by remember { mutableStateOf("443") }
    var quickTestError by remember { mutableStateOf("") }
    val logListState = rememberLazyListState()

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                val lines = withContext(Dispatchers.IO) { readUriLines(context, uri) }
                vm.setCustomLines(lines)
                Toast.makeText(context, "已导入 ${lines.size} 条", Toast.LENGTH_SHORT).show()
            }
        } else {
            vm.revertCustomSource()
        }
    }

    LaunchedEffect(Unit) {
        vm.scanFinished.collect { onScanFinished() }
    }

    LaunchedEffect(state.log.size) {
        if (state.log.isNotEmpty()) {
            val lastVisible = logListState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            if (!logListState.isScrollInProgress && lastVisible >= state.log.lastIndex - 1) {
                logListState.scrollToItem(state.log.lastIndex)
            }
        }
    }

    val paramsLocked = state.running
    val customEmpty = state.source == IpSource.CUSTOM && state.customLines.isEmpty()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(text = "测速", style = MaterialTheme.typography.headlineMedium)

        if (paramsLocked) {
            Text(
                text = "扫描进行中，参数已锁定，完成后可再次修改",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        Text(text = "场景", style = MaterialTheme.typography.titleMedium)
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            SCENE_OPTIONS.forEachIndexed { index, (scene, label) ->
                SegmentedButton(
                    enabled = !paramsLocked,
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
                    enabled = !paramsLocked,
                    selected = state.source == source,
                    onClick = {
                        if (source == IpSource.CUSTOM) {
                            vm.selectCustomSource()
                            filePicker.launch(arrayOf("*/*"))
                        } else {
                            vm.setSource(source)
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
                    enabled = !paramsLocked,
                    selected = port in state.ports,
                    onClick = { vm.togglePort(port) },
                    label = { Text(port.toString()) },
                )
            }
        }

        OutlinedTextField(
            value = state.maxIps.toString(),
            onValueChange = { raw ->
                vm.setMaxIps((raw.filter { it.isDigit() }.toIntOrNull() ?: 0).coerceAtMost(MAX_IPS))
            },
            enabled = !paramsLocked,
            label = { Text("抽取IP数 (0 = 采样, 上限 $MAX_IPS)") },
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
                enabled = !paramsLocked,
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
                enabled = !paramsLocked,
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
            Switch(
                checked = state.multiPortBest,
                onCheckedChange = vm::setMultiPortBest,
                enabled = !paramsLocked,
            )
            Spacer(modifier = Modifier.width(16.dp))
            Text(text = "全量扫描", modifier = Modifier.weight(1f))
            Switch(
                checked = state.fullScan,
                onCheckedChange = vm::setFullScan,
                enabled = !paramsLocked,
            )
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "下载测速",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier
                            .weight(1f)
                            .clickable(enabled = !paramsLocked) { speedExpanded = !speedExpanded },
                    )
                    Switch(
                        checked = state.speedEnabled,
                        onCheckedChange = vm::setSpeedEnabled,
                        enabled = !paramsLocked,
                    )
                }
                if (speedExpanded) {
                    Spacer(modifier = Modifier.height(8.dp))
                    ExposedDropdownMenuBox(
                        expanded = regionExpanded && !paramsLocked,
                        onExpandedChange = { if (!paramsLocked) regionExpanded = it },
                    ) {
                        OutlinedTextField(
                            value = ColoRegionMapper.map(state.region),
                            onValueChange = {},
                            readOnly = true,
                            enabled = !paramsLocked,
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
                                enabled = !paramsLocked,
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
            enabled = if (state.running) true else !customEmpty,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
        ) {
            Text(if (state.running) "取消" else "开始扫描")
        }

        if (customEmpty) {
            Text(
                text = "自定义来源需先导入 IP 文件才能开始扫描",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        OutlinedButton(
            onClick = {
                quickIpText = ""
                quickPortText = "443"
                quickTestError = ""
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
                itemsIndexed(state.log, key = { index, _ -> index }) { _, line ->
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
                        isError = quickTestError.isNotEmpty(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = quickPortText,
                        onValueChange = { raw ->
                            quickPortText = raw.filter { it.isDigit() }
                        },
                        label = { Text("端口 (1-65535)") },
                        isError = quickTestError.isNotEmpty(),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (quickTestError.isNotEmpty()) {
                        Text(
                            text = quickTestError,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val ip = quickIpText.trim()
                        val port = quickPortText.toIntOrNull() ?: 443
                        if (isValidIpText(ip) && port in 1..65535) {
                            quickTestError = ""
                            vm.quickTest(ip, port)
                            showQuickTestDialog = false
                        } else {
                            quickTestError = when {
                                !isValidIpText(ip) && port !in 1..65535 ->
                                    "IP 地址格式不正确，且端口需在 1-65535 之间"
                                !isValidIpText(ip) -> "IP 地址格式不正确"
                                else -> "端口需在 1-65535 之间"
                            }
                        }
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

private fun isValidIpText(text: String): Boolean {
    val s = text.trim()
    if (s.isEmpty()) return false
    return if (':' in s) isValidIpv6(s) else isValidIpv4(s)
}

private fun isValidIpv4(s: String): Boolean {
    val parts = s.split('.')
    if (parts.size != 4) return false
    return parts.all { part ->
        part.isNotEmpty() &&
            part.length <= 3 &&
            part.all { it.isDigit() } &&
            (part.toIntOrNull()?.let { it in 0..255 } == true)
    }
}

private fun isValidIpv6(s: String): Boolean {
    if (s.count { it == ':' } > 7) return false
    val parts = s.split(':')
    if (parts.size > 8) return false
    var emptySeen = false
    var groups = 0
    for (part in parts) {
        if (part.isEmpty()) {
            if (emptySeen) return false
            emptySeen = true
        } else {
            if (part.length > 4 || !part.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }) {
                return false
            }
            groups++
        }
    }
    return if (emptySeen) groups <= 7 else groups == 8
}

private fun formatEta(etaMs: Long): String {
    val totalSeconds = (etaMs / 1000).coerceAtLeast(0)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return if (minutes > 0) "预计还需 $minutes 分 $seconds 秒" else "预计还需 $seconds 秒"
}
