package com.codexbridge.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.codexbridge.mobile.UiState
import com.codexbridge.mobile.data.ThreadSummary
import com.codexbridge.mobile.data.sortThreadsByActivity

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThreadsScreen(
    state: UiState,
    onRefresh: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenThread: (String) -> Unit,
    onSearchChange: (String) -> Unit,
    onSearchSubmit: () -> Unit,
    onCreateThread: (String, String) -> Unit,
) {
    var showNewThread by remember { mutableStateOf(false) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Codex", fontWeight = FontWeight.SemiBold)
                        Text(
                            text = buildString {
                                append(state.bridgeName.ifBlank { "CodexBridge" })
                                if (state.bridgeVersion.isNotBlank()) append(" · v").append(state.bridgeVersion)
                            },
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onRefresh, enabled = !state.manualRefreshing) {
                        // The icon and the spinner share one fixed-size slot, so
                        // the app bar never resizes while a sync runs.
                        Box(modifier = Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                            if (state.manualRefreshing) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                )
                            } else {
                                Icon(Icons.Filled.Refresh, contentDescription = "刷新")
                            }
                        }
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "设置")
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showNewThread = true }) {
                Icon(Icons.Filled.Add, contentDescription = "新建任务")
            }
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            Column(modifier = Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                QuotaRow(account = state.account)
                OutlinedTextField(
                    value = state.search,
                    onValueChange = onSearchChange,
                    placeholder = { Text("搜索任务") },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
                // One fixed-height row carries the sync state. The background
                // poll updates the text in place instead of mounting and
                // unmounting a spinner, which used to make the list blink.
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().height(28.dp),
                ) {
                    Text(
                        text = "共 ${state.threads.size} 个任务",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Box(modifier = Modifier.size(14.dp), contentAlignment = Alignment.Center) {
                        if (state.manualRefreshing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(12.dp),
                                strokeWidth = 1.5.dp,
                            )
                        } else {
                            Box(
                                modifier = Modifier
                                    .size(7.dp)
                                    .background(
                                        MaterialTheme.colorScheme.secondary,
                                        RoundedCornerShape(999.dp),
                                    ),
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (state.manualRefreshing) "正在刷新…" else syncLabel(state.lastRefreshedAt),
                        fontSize = 12.sp,
                        maxLines = 1,
                        color = if (state.manualRefreshing) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    TextButton(onClick = onSearchSubmit) { Text("搜索") }
                }
                state.error?.let { message ->
                    Text(text = message, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
                }
            }
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = 6.dp,
                    bottom = 96.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(sortThreadsByActivity(state.threads), key = { it.thread_id }) { thread ->
                    ThreadCard(thread = thread, onClick = { onOpenThread(thread.thread_id) })
                }
                if (state.threads.isEmpty()) {
                    item {
                        Text(
                            text = if (state.busy) "正在读取任务..." else "还没有任务，点右下角新建一个。",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(vertical = 32.dp),
                        )
                    }
                }
            }
        }
    }

    if (showNewThread) {
        NewThreadDialog(
            state = state,
            onDismiss = { showNewThread = false },
            onCreate = { cwd, model ->
                showNewThread = false
                onCreateThread(cwd, model)
            },
        )
    }
}

private fun syncLabel(millis: Long): String {
    if (millis <= 0) return "等待同步"
    val seconds = ((System.currentTimeMillis() - millis) / 1000).coerceAtLeast(0)
    return when {
        seconds < 3 -> "已同步 · 刚刚"
        seconds < 60 -> "已同步 · ${seconds} 秒前"
        seconds < 3600 -> "已同步 · ${seconds / 60} 分钟前"
        else -> "已同步 · ${seconds / 3600} 小时前"
    }
}

private fun isActiveStatus(status: String): Boolean =
    status == "working" || status == "waiting_user"

private fun threadsByActivity(threads: List<ThreadSummary>): List<ThreadSummary> =
    threads.sortedWith(
        compareBy<ThreadSummary> { if (isActiveStatus(it.status)) 0 else 1 }
            .thenByDescending { maxOf(it.recency_at, it.updated_at) },
    )

private fun workspaceLabel(thread: ThreadSummary): String {
    if (thread.workspace.isNotBlank()) return thread.workspace
    val parts = thread.cwd.trimEnd('\\', '/').split('\\', '/').filter { it.isNotBlank() }
    return parts.lastOrNull() ?: "默认目录"
}

@Composable
private fun ThreadCard(thread: ThreadSummary, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = thread.title.ifBlank { "未命名任务" },
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(modifier = Modifier.width(8.dp))
                StatusPill(thread.status)
            }
            if (thread.preview.isNotBlank()) {
                Text(
                    text = thread.preview,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("工作空间", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = workspaceLabel(thread),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = thread.model.ifBlank { "默认模型" },
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = relativeTime(thread.recency_at.takeIf { it > 0 } ?: thread.updated_at),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun NewThreadDialog(
    state: UiState,
    onDismiss: () -> Unit,
    onCreate: (String, String) -> Unit,
) {
    var cwd by remember { mutableStateOf(state.selectedCwd.ifBlank { state.projects.firstOrNull()?.cwd.orEmpty() }) }
    var model by remember {
        mutableStateOf(
            state.models.firstOrNull { it.slug == state.selectedModel && it.slug.isNotBlank() }?.slug
                ?: state.models.firstOrNull { it.is_default }?.slug
                ?: state.models.firstOrNull()?.slug
                ?: "",
        )
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新建任务") },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("项目", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (state.projects.isEmpty()) {
                    Text("使用默认目录", fontSize = 13.sp)
                }
                state.projects.take(8).forEach { project ->
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { cwd = project.cwd },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = cwd == project.cwd, onClick = { cwd = project.cwd })
                        Column(modifier = Modifier.weight(1f)) {
                            Text(project.name.ifBlank { project.cwd }, fontSize = 14.sp)
                            Text(
                                text = project.cwd,
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text("模型", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("默认（由电脑端决定）", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                state.models.take(10).forEach { option ->
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { model = option.slug },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = model == option.slug, onClick = { model = option.slug })
                        Text(option.label, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onCreate(cwd, model) }, enabled = !state.busy) {
                Text(if (state.busy) "创建中..." else "创建")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}