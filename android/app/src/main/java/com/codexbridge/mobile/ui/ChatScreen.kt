package com.codexbridge.mobile.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import kotlinx.coroutines.launch
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.codexbridge.mobile.UiState
import com.codexbridge.mobile.data.ApprovalRequest
import com.codexbridge.mobile.data.MessageItem
import com.codexbridge.mobile.data.QueueItem
import java.util.Locale
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    state: UiState,
    onBack: () -> Unit,
    onSend: (String) -> Unit,
    onConsumeRestoreInput: () -> Unit = {},
    onStop: () -> Unit,
    onClaimThread: (Boolean) -> Unit = {},
    onPickModel: (String) -> Unit,
    onResolveApproval: (Int, String) -> Unit,
    onRespondApproval: (Int, JsonObject) -> Unit,
    onSetApprovalMode: (com.codexbridge.mobile.data.cbdp.ApprovalMode) -> Unit = {},
    onPickAttachments: () -> Unit = {},
    onRemoveAttachment: (String) -> Unit = {},
    onSteerQueue: (QueueItem) -> Unit = {},
    onEditQueue: (QueueItem, String) -> Unit = { _, _ -> },
    onDeleteQueue: (QueueItem) -> Unit = {},
) {
    var input by remember { mutableStateOf("") }
    var showModels by remember { mutableStateOf(false) }
    var showApprovalMode by remember { mutableStateOf(false) }
    var confirmFullAccess by remember { mutableStateOf(false) }
    var editingQueue by remember { mutableStateOf<QueueItem?>(null) }
    var editingText by remember { mutableStateOf("") }
    var deletingQueue by remember { mutableStateOf<QueueItem?>(null) }
    LaunchedEffect(state.restoreInput) {
        if (state.restoreInput.isNotBlank()) {
            input = state.restoreInput
            onConsumeRestoreInput()
        }
    }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val threadKey = state.activeThread?.thread_id.orEmpty()
    val itemCount = state.messages.size + state.approvals.size
    var lastBottomId by remember(threadKey) { mutableStateOf("") }
    var userScrolledUp by remember(threadKey) { mutableStateOf(false) }
    val pinnedToBottom by remember {
        derivedStateOf {
            val layout = listState.layoutInfo
            val last = layout.visibleItemsInfo.lastOrNull()
            last == null || last.index >= layout.totalItemsCount - 1 ||
                listState.canScrollForward.not()
        }
    }
    val showJumpToBottom by remember {
        derivedStateOf {
            listState.canScrollBackward && (!pinnedToBottom || userScrolledUp)
        }
    }

    // Opening a thread jumps straight to the newest message.
    LaunchedEffect(threadKey) {
        if (threadKey.isNotBlank()) {
            listState.scrollToItem(0)
            if (itemCount > 0) listState.scrollToItem(itemCount - 1)
        }
    }
    // A newly loaded message scrolls to the bottom instantly; deltas only
    // follow while the user is already reading the bottom of the list.
    LaunchedEffect(threadKey, itemCount) {
        val lastId = state.messages.lastOrNull()?.id
            ?: state.approvals.lastOrNull()?.let { "approval-" + it.request_id }
            ?: ""
        if (lastId.isNotBlank() && lastId != lastBottomId) {
            lastBottomId = lastId
            listState.scrollToItem(itemCount - 1)
        }
    }
    LaunchedEffect(threadKey, state.streaming.size, state.working, state.task.current_action) {
        if (itemCount > 0 && pinnedToBottom) {
            listState.scrollToItem(itemCount - 1)
        }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = state.activeThread?.title?.ifBlank { "Codex 任务" } ?: "Codex 任务",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            fontWeight = FontWeight.Medium,
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = statusLabel(state.task.status.ifBlank { state.activeThread?.status ?: "idle" }),
                                fontSize = 12.sp,
                                color = statusColor(state.task.status.ifBlank { state.activeThread?.status ?: "idle" }),
                            )
                            if (state.task.tokenCount > 0) {
                                Text(
                                    text = " · ${formatTokens(state.task.tokenCount)} tokens",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    AssistChip(
                        onClick = { showModels = true },
                        label = { Text(state.modelLabel, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        enabled = state.canWrite,
                    )
                    if (state.bridgeCapabilities.threadSettings) {
                        AssistChip(
                            onClick = { showApprovalMode = true },
                            label = { Text(state.approvalMode.label) },
                            enabled = state.canWrite && !state.approvalModeChanging,
                        )
                    }
                    if (state.working) {
                        IconButton(onClick = onStop, enabled = state.canWrite) {
                            Icon(Icons.Filled.Close, contentDescription = "中断", tint = MaterialTheme.colorScheme.error)
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).imePadding()) {
            state.error?.let { message ->
                Text(
                    text = message,
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                )
            }
            if (state.task.status == "failed" && state.task.errorMessage.isNotBlank()) {
                ChatBanner(
                    text = "上一轮失败：" + state.task.errorMessage,
                    accent = MaterialTheme.colorScheme.error,
                )
            }
            if (!state.canWrite) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = if (state.ownerState.isBlank()) {
                            "当前线程没有写入权限"
                        } else {
                            "当前写入者：${state.ownerState}"
                        },
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { onClaimThread(false) }) { Text("接管线程") }
                }
            }
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(state.messages, key = { it.id.ifBlank { it.hashCode().toString() } }) { item ->
                        MessageRow(item = item, streaming = state.streaming[item.id].orEmpty())
                    }
                    items(state.approvals, key = { "approval-" + it.request_id }) { approval ->
                        ApprovalCard(
                            approval = approval,
                            writable = state.canWrite,
                            structuredWritable = state.bridgeCapabilities.approvalResponse,
                            onDecision = onResolveApproval,
                            onResponse = onRespondApproval,
                        )
                        if (!state.bridgeCapabilities.approvalResponse) {
                            Text(
                                text = "电脑端版本较旧，暂时只能拒绝或取消该请求",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 4.dp),
                            )
                        }
                    }
                }
                if (state.chatLoading) {
                    Column(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(28.dp))
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = "正在加载会话…",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (showJumpToBottom) {
                    Surface(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 10.dp)
                            .clickable {
                                val target = state.messages.size + state.approvals.size
                                if (target > 0) scope.launch { listState.scrollToItem(target - 1) }
                            },
                        shape = RoundedCornerShape(999.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        tonalElevation = 4.dp,
                    ) {
                        Text(
                            text = "回到底部",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        )
                    }
                }
            }
            if (state.queue.isNotEmpty() || state.queuePaused) {
                QueuePanel(
                    items = state.queue,
                    paused = state.queuePaused,
                    pauseReason = state.queuePauseReason,
                    working = state.working,
                    writable = state.canWrite,
                    onSteer = onSteerQueue,
                    onRetry = { item -> onEditQueue(item, item.text) },
                    onEdit = { item ->
                        editingQueue = item
                        editingText = item.text
                    },
                    onDelete = { item -> deletingQueue = item },
                )
            }
            if (state.working) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .background(MaterialTheme.colorScheme.secondary, RoundedCornerShape(999.dp)),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = state.task.currentActionOrFallback(),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (state.pendingAttachments.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp)
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    state.pendingAttachments.forEach { item ->
                        AssistChip(
                            onClick = { onRemoveAttachment(item.localId) },
                            label = {
                                Text(
                                    text = when (item.state) {
                                        com.codexbridge.mobile.data.AttachmentState.Uploading ->
                                            item.filename + " " + progressPercent(item) + "%"
                                        com.codexbridge.mobile.data.AttachmentState.Failed ->
                                            item.filename + " 失败"
                                        else -> item.filename
                                    },
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                        )
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                if (state.bridgeCapabilities.attachments) {
                    IconButton(
                        onClick = onPickAttachments,
                        enabled = !state.sending,
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = "添加附件")
                    }
                }
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    enabled = state.canWrite,
                    placeholder = {
                        Text(
                            if (state.working) "引导正在运行的对话…" else "发送消息",
                            maxLines = 1,
                        )
                    },
                    maxLines = 5,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.weight(1f),
                )
                Spacer(modifier = Modifier.width(8.dp))
                IconButton(
                    onClick = {
                        val text = input.trim()
                        if (text.isNotEmpty() || state.pendingAttachments.isNotEmpty()) {
                            input = ""
                            onSend(text)
                        }
                    },
                    enabled = state.canWrite && !state.sending &&
                        (input.isNotBlank() || state.pendingAttachments.isNotEmpty()),
                    modifier = Modifier
                        .size(48.dp)
                        .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(16.dp)),
                ) {
                    if (state.sending) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                    } else {
                        Icon(
                            Icons.Filled.Send,
                            contentDescription = "发送",
                            tint = MaterialTheme.colorScheme.onPrimary,
                        )
                    }
                }
            }
        }
    }

    if (showApprovalMode) {
        AlertDialog(
            onDismissRequest = { showApprovalMode = false },
            title = { Text("审批模式") },
            text = {
                Column {
                    Text(
                        text = if (state.working) {
                            "当前任务运行中，切换将在下一轮生效。"
                        } else {
                            "选择 Codex 在本会话中向你询问的频率。"
                        },
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    com.codexbridge.mobile.data.cbdp.ApprovalMode.entries.forEach { mode ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    if (mode == com.codexbridge.mobile.data.cbdp.ApprovalMode.FullAccess) {
                                        confirmFullAccess = true
                                    } else {
                                        showApprovalMode = false
                                        onSetApprovalMode(mode)
                                    }
                                }
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            androidx.compose.material3.RadioButton(
                                selected = state.approvalMode == mode,
                                onClick = {
                                    if (mode == com.codexbridge.mobile.data.cbdp.ApprovalMode.FullAccess) {
                                        confirmFullAccess = true
                                    } else {
                                        showApprovalMode = false
                                        onSetApprovalMode(mode)
                                    }
                                },
                            )
                            Column {
                                Text(mode.label, fontWeight = FontWeight.Medium)
                                Text(
                                    text = if (mode == com.codexbridge.mobile.data.cbdp.ApprovalMode.RequestApproval) {
                                        "执行命令、修改文件和网络操作前询问你"
                                    } else {
                                        "不再询问普通操作；工具表单仍需要你确认"
                                    },
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showApprovalMode = false }) { Text("取消") }
            },
        )
    }

    if (confirmFullAccess) {
        AlertDialog(
            onDismissRequest = { confirmFullAccess = false },
            title = { Text("切换到完全允许？") },
            text = {
                Text(
                    "Codex 执行命令、修改文件和访问网络时将不再逐次询问。" +
                        "请确认你知道这会让电脑端承担更大的操作风险。"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmFullAccess = false
                    showApprovalMode = false
                    onSetApprovalMode(com.codexbridge.mobile.data.cbdp.ApprovalMode.FullAccess)
                }) { Text("仍要切换") }
            },
            dismissButton = {
                TextButton(onClick = { confirmFullAccess = false }) { Text("取消") }
            },
        )
    }
    deletingQueue?.let { target ->
        AlertDialog(
            onDismissRequest = { deletingQueue = null },
            title = { Text("删除队列项") },
            text = { Text(target.text, maxLines = 4, overflow = TextOverflow.Ellipsis) },
            confirmButton = {
                TextButton(
                    onClick = {
                        deletingQueue = null
                        onDeleteQueue(target)
                    },
                ) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { deletingQueue = null }) { Text("取消") }
            },
        )
    }

    if (editingQueue != null) {
        EditQueueDialog(
            text = editingText,
            onTextChange = { editingText = it },
            onSave = {
                val target = editingQueue
                if (target != null && editingText.trim().isNotEmpty()) {
                    onEditQueue(target, editingText.trim())
                }
                editingQueue = null
            },
            onDismiss = { editingQueue = null },
        )
    }

    if (showModels) {
        ModelPickerSheet(
            models = state.models,
            selectedModel = state.selectedModel,
            enabled = state.canWrite,
            onSelect = { model ->
                showModels = false
                onPickModel(model.slug)
            },
            onDismiss = { showModels = false },
        )
    }
}

@Composable
private fun QueuePanel(
    items: List<QueueItem>,
    paused: Boolean = false,
    pauseReason: String = "",
    working: Boolean,
    writable: Boolean,
    onSteer: (QueueItem) -> Unit,
    onRetry: (QueueItem) -> Unit = {},
    onEdit: (QueueItem) -> Unit,
    onDelete: (QueueItem) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = "待发送队列 · ${items.size}",
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (paused) {
                Text(
                    text = if (pauseReason.isBlank()) "队列已暂停，点“重新派发”恢复" else "队列已暂停：$pauseReason",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            items.forEach { item ->
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(item.text, fontSize = 13.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (working && item.status == "pending" && item.revision > 0) {
                            TextButton(onClick = { onSteer(item) }, enabled = writable) { Text("引导") }
                        }
                        if (item.status == "pending" || item.status == "failed") {
                            if (paused || item.status == "failed") {
                                TextButton(onClick = { onRetry(item) }, enabled = writable) { Text("重新派发") }
                            }
                            TextButton(onClick = { onEdit(item) }, enabled = writable) { Text("编辑") }
                            TextButton(onClick = { onDelete(item) }, enabled = writable) { Text("删除") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EditQueueDialog(
    text: String,
    onTextChange: (String) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑队列项") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = onTextChange,
                label = { Text("内容") },
                minLines = 3,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = onSave, enabled = text.trim().isNotEmpty()) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

@Composable
private fun ChatBanner(text: String, accent: androidx.compose.ui.graphics.Color) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp),
        shape = RoundedCornerShape(10.dp),
        color = accent.copy(alpha = 0.14f),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.5f)),
    ) {
        Text(
            text = text,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
        )
    }
}

private fun com.codexbridge.mobile.data.TaskSnapshot.currentActionOrFallback(): String =
    current_action.ifBlank { if (status == "waiting_user") "等待你的确认" else "Codex 正在处理…" }

@Composable
private fun MessageRow(item: MessageItem, streaming: String) {
    when (item.kind) {
        "user" -> UserBubble(item.text)
        "agent" -> AgentBubble(item = item, streaming = streaming)
        "commentary" -> QuietBlock(text = item.text, label = null, italic = true)
        "reasoning" -> QuietBlock(text = item.text, label = "思考", italic = false)
        "command" -> CommandBlock(item)
        "fileChange" -> FileChangeBlock(item)
        "tool" -> ToolBlock(item)
        "plan" -> PlanBlock(item)
        else -> NoticeLine(item.text.ifBlank { item.detail.message })
    }
}

@Composable
private fun UserBubble(text: String) {
    if (text.isBlank()) return
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Box(
            modifier = Modifier
                .widthIn(max = 320.dp)
                .background(
                    MaterialTheme.colorScheme.primaryContainer,
                    RoundedCornerShape(16.dp, 16.dp, 4.dp, 16.dp),
                )
                .padding(horizontal = 12.dp, vertical = 9.dp),
        ) {
            Text(
                text = text,
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}

@Composable
private fun AgentBubble(item: MessageItem, streaming: String) {
    val live = streaming.isNotEmpty() && item.text.isBlank()
    val body = item.text.ifBlank { streaming }
    if (body.isBlank()) return
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
        Card(
            modifier = Modifier.widthIn(max = 340.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(4.dp, 16.dp, 16.dp, 16.dp),
        ) {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "Codex",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                    if (live) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "正在输出",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(text = body, fontSize = 14.sp)
            }
        }
    }
}

@Composable
private fun QuietBlock(text: String, label: String?, italic: Boolean) {
    if (text.isBlank()) return
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
        Column(modifier = Modifier.widthIn(max = 340.dp).padding(start = 4.dp)) {
            if (label != null) {
                Text(
                    text = label,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(modifier = Modifier.height(2.dp))
            }
            Text(
                text = text,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontStyle = if (italic) FontStyle.Italic else FontStyle.Normal,
            )
        }
    }
}

@Composable
private fun CommandBlock(item: MessageItem) {
    val detail = item.detail
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "命令",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                val footer = commandFooter(detail.status, detail.exitCode, detail.durationMs)
                if (footer.isNotBlank()) {
                    Text(text = footer, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            val command = item.text.ifBlank { detail.message }
            if (command.isNotBlank()) {
                MonoBlock("$ " + command.trim())
            }
            if (detail.cwd.isNotBlank()) {
                Text(
                    text = detail.cwd,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (detail.output.isNotBlank()) {
                MonoBlock(detail.output.take(6000))
            }
        }
    }
}

@Composable
private fun FileChangeBlock(item: MessageItem) {
    val detail = item.detail
    val diff = detail.diff.ifBlank { detail.patch }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "文件改动",
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            detail.paths.forEach { path ->
                Text(
                    text = path,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            if (diff.isNotBlank()) {
                MonoBlock(diff.take(6000))
            } else if (item.text.isNotBlank() || detail.message.isNotBlank()) {
                Text(
                    text = item.text.ifBlank { detail.message },
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ToolBlock(item: MessageItem) {
    val detail = item.detail
    val body = item.text.ifBlank { detail.message }
    val result = detail.result.ifBlank { detail.output }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "工具调用",
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (body.isNotBlank()) {
                Text(text = body, fontSize = 13.sp)
            }
            if (result.isNotBlank()) {
                MonoBlock(result.take(6000))
            }
        }
    }
}

@Composable
private fun PlanBlock(item: MessageItem) {
    val steps = item.detail.steps
    if (steps.isEmpty() && item.text.isBlank()) return
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = "计划",
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (steps.isEmpty()) {
                Text(text = item.text, fontSize = 13.sp)
            }
            steps.forEach { step ->
                val done = step.status == "completed"
                val active = step.status == "in_progress" || step.status == "inProgress"
                val marker = when {
                    done -> "✓"
                    active -> "◐"
                    else -> "○"
                }
                val color = when {
                    done -> MaterialTheme.colorScheme.secondary
                    active -> MaterialTheme.colorScheme.tertiary
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                }
                Row(verticalAlignment = Alignment.Top) {
                    Text(text = marker, fontSize = 13.sp, color = color, modifier = Modifier.width(18.dp))
                    Text(
                        text = step.step,
                        fontSize = 13.sp,
                        color = if (done) MaterialTheme.colorScheme.onSurfaceVariant else color,
                    )
                }
            }
        }
    }
}

@Composable
private fun NoticeLine(text: String) {
    if (text.isBlank()) return
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.Center,
    ) {
        Text(
            text = text,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun ApprovalCard(
    approval: ApprovalRequest,
    writable: Boolean,
    structuredWritable: Boolean = true,
    onDecision: (Int, String) -> Unit,
    onResponse: (Int, JsonObject) -> Unit,
) {
    val detail = approval.raw
    var answers by remember(approval.request_id) { mutableStateOf(mapOf<String, String>()) }
    var structured by remember(approval.request_id) { mutableStateOf("{}") }
    val diff = detail.diff.ifBlank { detail.patch }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.45f)),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = approval.title,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
            }
            if (approval.command.isNotBlank()) {
                MonoBlock("$ " + approval.command.trim())
            }
            if (detail.paths.isNotEmpty()) {
                Text(
                    text = detail.paths.joinToString("\n"),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (diff.isNotBlank()) {
                MonoBlock(diff.take(4000))
            }
            detail.questions.forEach { question ->
                Column {
                    if (question.question.isNotBlank()) {
                        Text(text = question.question, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    }
                    if (question.options.isNotEmpty()) {
                        question.options.forEach { option ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                androidx.compose.material3.RadioButton(
                                    selected = answers[question.id] == option,
                                    onClick = { answers = answers + (question.id to option) })
                                Text(option, modifier = Modifier.weight(1f))
                            }
                        }
                    }
                    OutlinedTextField(value = answers[question.id].orEmpty(),
                        onValueChange = { answers = answers + (question.id to it) },
                        label = { Text("回答") }, modifier = Modifier.fillMaxWidth())
                }
            }
            if (approval.reason.isNotBlank()) {
                Text(
                    text = approval.reason,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (
                approval.command.isBlank() &&
                detail.paths.isEmpty() &&
                diff.isBlank() &&
                detail.questions.isEmpty() &&
                approval.reason.isBlank() &&
                detail.message.isNotBlank()
            ) {
                Text(text = detail.message, fontSize = 13.sp)
            }
            if (approval.kind == "permissions" || approval.kind == "mcp") {
                OutlinedTextField(value = structured, onValueChange = { structured = it },
                    label = { Text(if (approval.kind == "permissions") "授权范围 JSON" else "表单内容 JSON") },
                    modifier = Modifier.fillMaxWidth(), minLines = 3, enabled = writable)
            }
            if (approval.kind in setOf("input", "permissions", "mcp")) {
                val parsed = runCatching { Json.parseToJsonElement(structured) as? JsonObject }.getOrNull()
                Button(onClick = {
                    val response = buildJsonObject {
                        when (approval.kind) {
                            "input" -> put("answers", buildJsonObject {
                                answers.forEach { (id, answer) -> put(id, buildJsonObject {
                                    put("answers", JsonArray(listOf(JsonPrimitive(answer))))
                                }) }
                            })
                            "permissions" -> { put("permissions", parsed!!); put("scope", "turn") }
                            "mcp" -> { put("action", "accept"); put("content", parsed!!) }
                        }
                    }
                    onResponse(approval.request_id, response)
                }, enabled = writable && structuredWritable && if (approval.kind == "input")
                    detail.questions.all { !answers[it.id].isNullOrBlank() } else parsed != null) {
                    Text("提交")
                }
            } else Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = { onDecision(approval.request_id, "accept") },
                    enabled = writable,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("允许")
                }
                OutlinedButton(
                    onClick = { onDecision(approval.request_id, "acceptForSession") },
                    enabled = writable,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("本会话允许")
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextButton(
                    onClick = { onDecision(approval.request_id, "decline") },
                    enabled = writable,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("拒绝")
                }
                TextButton(
                    onClick = { onDecision(approval.request_id, "cancel") },
                    enabled = writable,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("取消")
                }
            }
        }
    }
}

private fun progressPercent(item: com.codexbridge.mobile.data.PendingAttachment): Int {
    if (item.byteSize <= 0) return 0
    return ((item.uploadedBytes * 100) / item.byteSize).toInt().coerceIn(0, 100)
}

private fun commandFooter(status: String, exitCode: Int?, durationMs: Long?): String {
    val parts = mutableListOf<String>()
    if (status.isNotBlank()) parts += status
    if (exitCode != null) parts += if (exitCode == 0) "退出码 0" else "退出码 $exitCode"
    val duration = formatDuration(durationMs)
    if (duration.isNotBlank()) parts += duration
    return parts.joinToString(" · ")
}

private fun formatDuration(ms: Long?): String {
    if (ms == null || ms <= 0) return ""
    return when {
        ms < 1000 -> "${ms}ms"
        ms < 60_000 -> String.format(Locale.US, "%.1fs", ms / 1000.0)
        else -> "${ms / 60_000}m${(ms % 60_000) / 1000}s"
    }
}
