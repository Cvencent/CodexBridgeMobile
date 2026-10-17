package com.codexbridge.mobile.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.codexbridge.mobile.UiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    state: UiState,
    onBack: () -> Unit,
    onToggleTheme: (Boolean) -> Unit,
    onDiscoveryPort: (Int) -> Unit,
    onUpdateRoutes: (String, String) -> Unit,
    onUnpair: () -> Unit,
    onTestNotification: () -> Unit,
    onOpenNotificationSettings: () -> Unit,
    onOpenBackgroundSettings: () -> Unit,
) {
    var discoveryPort by remember(state.discoveryPort) { mutableStateOf(state.discoveryPort.toString()) }
    var lanUrl by remember(state.lanUrl) { mutableStateOf(state.lanUrl) }
    var wanUrl by remember(state.wanUrl) { mutableStateOf(state.wanUrl) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("连接", fontWeight = FontWeight.Medium)
                    SettingRow("当前线路", state.activeRouteLabel.ifBlank { "--" })
                    SettingRow("当前地址", state.baseUrl.ifBlank { "--" })
                    OutlinedTextField(
                        value = lanUrl,
                        onValueChange = { lanUrl = it },
                        label = { Text("内网地址") },
                        placeholder = { Text("192.168.0.42:8568") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = wanUrl,
                        onValueChange = { wanUrl = it },
                        label = { Text("外网地址") },
                        placeholder = { Text("tokenplanx.vip:8080") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedButton(
                        onClick = { onUpdateRoutes(lanUrl, wanUrl) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("保存并重新选择线路")
                    }
                    SettingRow("电脑名称", state.bridgeName.ifBlank { "--" })
                    SettingRow("CodexBridge 版本", state.bridgeVersion.ifBlank { "--" })
                    SettingRow("手机 ID", state.phoneId.ifBlank { "--" })
                    OutlinedTextField(
                        value = discoveryPort,
                        onValueChange = { value ->
                            val digits = value.filter { it.isDigit() }.take(5)
                            discoveryPort = digits
                            digits.toIntOrNull()?.let(onDiscoveryPort)
                        },
                        label = { Text("扫描端口") },
                        supportingText = { Text("电脑端改过手机发现端口时，这里要填相同值。") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("显示", fontWeight = FontWeight.Medium)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("深色主题", modifier = Modifier.weight(1f))
                        Switch(checked = state.darkTheme, onCheckedChange = onToggleTheme)
                    }
                }
            }

            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("完成提醒", fontWeight = FontWeight.Medium)
                    Text(
                        text = "屏幕熄灭时由后台监听推送提醒。收不到声音时，先点测试提醒确认，" +
                            "再把 CodexBridge 设为允许后台运行。",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(onClick = onTestNotification, modifier = Modifier.weight(1f)) {
                            Text("测试提醒")
                        }
                        OutlinedButton(onClick = onOpenBackgroundSettings, modifier = Modifier.weight(1f)) {
                            Text("后台运行设置")
                        }
                    }
                    OutlinedButton(onClick = onOpenNotificationSettings, modifier = Modifier.fillMaxWidth()) {
                        Text("通知声音设置")
                    }
                }
            }

            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("关于", fontWeight = FontWeight.Medium)
                    Text(
                        text = "Codex 手机端通过 CodexBridge 伴侣接口连接电脑，所有数据只在局域网内传输。" +
                            "配对令牌保存在手机本地，可在电脑端管理页面随时解除。",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))
            OutlinedButton(onClick = onUnpair, modifier = Modifier.fillMaxWidth()) {
                Text("解除配对")
            }
        }
    }
}

@Composable
private fun SettingRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(text = label, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
        Spacer(modifier = Modifier.weight(1f))
        Text(text = value, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}
