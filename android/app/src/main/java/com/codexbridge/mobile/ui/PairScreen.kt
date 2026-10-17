package com.codexbridge.mobile.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.codexbridge.mobile.UiState

@Composable
fun PairScreen(
    state: UiState,
    onDiscover: () -> Unit,
    onDiscoveryPort: (Int) -> Unit,
    onPair: (String, String, String) -> Unit,
) {
    var lanHost by remember(state.lanUrl, state.wanUrl, state.baseUrl) {
        mutableStateOf(
            state.lanUrl.ifBlank {
                if (state.wanUrl.isBlank()) state.baseUrl else ""
            },
        )
    }
    var wanHost by remember(state.wanUrl) { mutableStateOf(state.wanUrl) }
    var code by remember { mutableStateOf("") }
    var discoveryPort by remember(state.discoveryPort) { mutableStateOf(state.discoveryPort.toString()) }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 36.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Column {
            Text(
                text = "Codex 手机端",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "连接电脑上运行的 CodexBridge，随时查看任务并继续对话。",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 14.sp,
            )
        }

        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(14.dp),
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "同一 Wi-Fi 下自动发现",
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedButton(onClick = onDiscover, enabled = !state.discovering) {
                        if (state.discovering) {
                            CircularProgressIndicator(modifier = Modifier.width(16.dp).height(16.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.width(16.dp))
                        }
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("扫描")
                    }
                }
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
                if (state.discovered.isEmpty()) {
                    Text(
                        text = "电脑端打开 CodexBridge 后即可被扫描到。",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    state.discovered.forEach { bridge ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { lanHost = bridge.baseUrl }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(bridge.name.ifBlank { bridge.host }, fontWeight = FontWeight.Medium)
                                Text(
                                    text = bridge.baseUrl,
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Text("选择", color = MaterialTheme.colorScheme.primary, fontSize = 13.sp)
                        }
                    }
                }
            }
        }

        OutlinedTextField(
            value = lanHost,
            onValueChange = { lanHost = it },
            label = { Text("内网地址") },
            placeholder = { Text("192.168.1.10:8568") },
            supportingText = { Text("和电脑在同一 Wi-Fi 时优先使用。") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth(),
        )

        OutlinedTextField(
            value = wanHost,
            onValueChange = { wanHost = it },
            label = { Text("外网地址（可选）") },
            placeholder = { Text("tokenplanx.vip:8080") },
            supportingText = { Text("离开局域网后自动切换到这个地址。") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth(),
        )

        OutlinedTextField(
            value = code,
            onValueChange = { value -> code = value.filter { it.isDigit() }.take(6) },
            label = { Text("配对码") },
            placeholder = { Text("六位数字") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            modifier = Modifier.fillMaxWidth(),
        )

        Button(
            onClick = { onPair(lanHost, wanHost, code) },
            enabled = !state.busy && (lanHost.isNotBlank() || wanHost.isNotBlank()) && code.length == 6,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (state.busy) "正在连接..." else "连接电脑")
        }

        Text(
            text = "在电脑端打开 http://127.0.0.1:8788/settings，在“手机连接”里生成配对码。",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        state.error?.let { message ->
            Text(text = message, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
        }
        if (state.error == null) {
            state.notice?.let { message ->
                Text(text = message, color = MaterialTheme.colorScheme.secondary, fontSize = 13.sp)
            }
        }
    }
}
