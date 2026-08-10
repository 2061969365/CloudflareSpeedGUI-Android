package com.cfst.android.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

@Composable
fun SettingsScreen(modifier: Modifier = Modifier) {
    val vm: SettingsViewModel = viewModel()
    val state by vm.state.collectAsState()
    var showResetDialog by rememberSaveable { mutableStateOf(false) }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(text = "设置", style = MaterialTheme.typography.headlineMedium)
        }

        item { SectionTitle("下载测速") }
        item {
            NumberSetting(
                title = "下载测速数量",
                hint = "5-200",
                value = state.downloadCount,
                min = 5,
                max = 200,
                default = 50,
                onChange = vm::setDownloadCount,
            )
        }
        item {
            NumberSetting(
                title = "下载测速时间",
                hint = "5-30 秒",
                value = state.downloadTime,
                min = 5,
                max = 30,
                default = 10,
                onChange = vm::setDownloadTime,
            )
        }
        item {
            NumberSetting(
                title = "速度下限",
                hint = "0-100 MB/s（0 表示不限）",
                value = state.speedLimit,
                min = 0,
                max = 100,
                default = 0,
                onChange = vm::setSpeedLimit,
            )
        }
        item {
            NumberSetting(
                title = "延迟上限",
                hint = "0-10000 ms",
                value = state.latencyLimit,
                min = 0,
                max = 10000,
                default = 200,
                onChange = vm::setLatencyLimit,
            )
        }
        item {
            val keyboard = LocalSoftwareKeyboardController.current
            var urlText by rememberSaveable { mutableStateOf(state.downloadUrl) }
            LaunchedEffect(state.downloadUrl) {
                if (urlText != state.downloadUrl) urlText = state.downloadUrl
            }
            var urlWasFocused by remember { mutableStateOf(false) }
            val commitUrl = {
                vm.setDownloadUrl(urlText)
                keyboard?.hide()
            }
            OutlinedTextField(
                value = urlText,
                onValueChange = { urlText = it },
                label = { Text("测速地址") },
                supportingText = { Text("留空使用默认地址") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { commitUrl() }),
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusChanged { fs ->
                        if (fs.isFocused) {
                            urlWasFocused = true
                        } else if (urlWasFocused) {
                            urlWasFocused = false
                            vm.setDownloadUrl(urlText)
                        }
                    },
            )
        }

        item { SectionTitle("扫描") }
        item {
            NumberSetting(
                title = "普通探测数",
                hint = "100-50000",
                value = state.probeCount,
                min = 100,
                max = 50000,
                default = 500,
                onChange = vm::setProbeCount,
            )
        }
        item {
            NumberSetting(
                title = "全量探测数",
                hint = "100-200000",
                value = state.fullScanProbeCount,
                min = 100,
                max = 200000,
                default = 5000,
                onChange = vm::setFullScanProbeCount,
            )
        }

        item { SectionTitle("并发") }
        item {
            NumberSetting(
                title = "Ping 并发数",
                hint = "1-1500",
                value = state.pingConcurrency,
                min = 1,
                max = 1500,
                default = 8,
                onChange = vm::setPingConcurrency,
            )
        }
        item {
            NumberSetting(
                title = "测速并发数",
                hint = "1-32",
                value = state.speedConcurrency,
                min = 1,
                max = 32,
                default = 5,
                onChange = vm::setSpeedConcurrency,
            )
        }

        item { SectionTitle("其他") }
        item {
            NumberSetting(
                title = "历史保留天数",
                hint = "1-365",
                value = state.historyRetentionDays,
                min = 1,
                max = 365,
                default = 30,
                onChange = vm::setRetentionDays,
            )
        }
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "深色模式",
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = state.darkTheme,
                    onCheckedChange = vm::setDarkTheme,
                )
            }
        }

        item {
            Button(
                onClick = { showResetDialog = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
            ) {
                Text("恢复默认设置")
            }
        }

        item { SectionTitle("关于") }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(text = "CF测速", style = MaterialTheme.typography.titleMedium)
                Text(
                    text = "版本 1.0.0",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = "基于 CloudflareSpeedTest 的 CF IP 测速工具",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    if (showResetDialog) {
        AlertDialog(
            onDismissRequest = { showResetDialog = false },
            title = { Text("恢复所有设置为默认值？") },
            text = { Text("当前设置将被覆盖为默认值。") },
            confirmButton = {
                TextButton(onClick = {
                    vm.resetDefaults()
                    showResetDialog = false
                }) {
                    Text("恢复")
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetDialog = false }) {
                    Text("取消")
                }
            },
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(top = 8.dp),
    )
}

@Composable
private fun NumberSetting(
    title: String,
    hint: String,
    value: Int,
    min: Int,
    max: Int,
    default: Int,
    onChange: (Int) -> Unit,
) {
    // 本地 String buffer：输入期间不钳制、不写库，失焦（或 IME Done）时才提交，
    // 避免「边输边钳制」导致无法输入中间值（如先敲 1 再敲 0）。
    var text by rememberSaveable { mutableStateOf(value.toString()) }
    LaunchedEffect(value) {
        if (text.toIntOrNull() != value) text = value.toString()
    }
    val parsed = text.toIntOrNull()
    val inRange = parsed != null && parsed in min..max
    val error = text.isNotEmpty() && (parsed == null || !inRange)
    val keyboard = LocalSoftwareKeyboardController.current
    var wasFocused by remember { mutableStateOf(false) }

    val commit = {
        val clamped = text.toIntOrNull()?.coerceIn(min, max) ?: default
        text = clamped.toString()
        if (clamped != value) onChange(clamped)
    }

    OutlinedTextField(
        value = text,
        onValueChange = { text = it.filter(Char::isDigit).take(6) },
        isError = error,
        label = { Text(title) },
        supportingText = {
            Text(
                text = if (error) "请输入 ${min}~${max} 之间的整数" else hint,
                color = if (error) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = {
            keyboard?.hide()
            commit()
        }),
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { fs ->
                if (fs.isFocused) {
                    wasFocused = true
                } else if (wasFocused) {
                    wasFocused = false
                    commit()
                }
            },
    )
}
