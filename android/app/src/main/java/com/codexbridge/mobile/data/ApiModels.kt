package com.codexbridge.mobile.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement

@Serializable
data class PingResponse(
    val ok: Boolean = false,
    val service: String = "",
    val name: String = "",
    val version: String = "",
    val port: Int = 0,
    val discovery_port: Int = 0,
    val requires_pairing: Boolean = false,
    @SerialName("cbdp_v1") val cbdpV1: CbdpCapability? = null,
)

@Serializable
data class CbdpCapability(
    val protocol: String = "",
    val version: String = "",
    @SerialName("version_major") val versionMajor: Int = 0,
    @SerialName("version_minor") val versionMinor: Int = 0,
    val transport: String = "",
    val paths: CbdpPaths = CbdpPaths(),
    val auth: String = "",
    @SerialName("auth_mode") val authMode: Int = 0,
    val enabled: Boolean = false,
    val port: Int = 0,
)

@Serializable
data class CbdpPaths(
    val websocket: String = "/cbdp/v1/ws",
    val events: String = "/cbdp/v1/events",
    val frame: String = "/cbdp/v1/frame",
)

@Serializable
data class BridgeInfo(
    val name: String = "",
    val version: String = "",
    val port: Int = 0,
)

@Serializable
data class PairResponse(
    val ok: Boolean = false,
    val phone_id: String = "",
    val token: String = "",
    val bridge: BridgeInfo = BridgeInfo(),
)

@Serializable
data class QueueItem(
    @SerialName("queue_id") val queue_id: String = "",
    @SerialName("thread_id") val thread_id: String = "",
    val text: String = "",
    val model: String = "",
    val effort: String = "",
    val status: String = "pending",
    val revision: Long = 0,
    // Bridge stamps these with wall-clock time, so they carry a fractional part.
    @SerialName("created_at") val createdAt: Double = 0.0,
    @SerialName("updated_at") val updatedAt: Double = 0.0,
    @SerialName("created_by") val createdBy: String = "",
    @SerialName("created_by_kind") val createdByKind: String = "",
    val error: String = "",
)

@Serializable
data class QueueResponse(
    val ok: Boolean = false,
    @SerialName("thread_id") val threadId: String = "",
    @SerialName("items") val queue: List<QueueItem> = emptyList(),
    val revision: Long = 0,
    val paused: Boolean = false,
)

@Serializable
data class QueueSnapshot(
    @SerialName("thread_id") val threadId: String = "",
    @SerialName("items") val items: List<QueueItem> = emptyList(),
    val revision: Long = 0,
    val paused: Boolean = false,
)

fun applyQueueSnapshot(
    current: List<QueueItem>,
    threadId: String,
    snapshot: List<QueueItem>,
): List<QueueItem> {
    val otherThreads = current.filterNot { it.thread_id == threadId }
    return otherThreads + snapshot.filter { it.thread_id == threadId }.sortedBy { it.createdAt }
}

fun applyQueueUpdate(
    current: List<QueueItem>,
    threadId: String,
    item: QueueItem,
    removed: Boolean,
): List<QueueItem> {
    if (item.thread_id != threadId) return current
    if (removed) return current.filterNot { it.queue_id == item.queue_id }
    val updated = current.filterNot { it.queue_id == item.queue_id } + item
    return updated.sortedBy { it.createdAt }
}

@Serializable
data class SendResponse(
    val ok: Boolean = false,
    val thread_id: String = "",
    val turn_id: String = "",
    val status: String = "",
    val read_at: Long = 0,
    // Set when the desktop held the original thread, so the reply continued in
    // a forked copy that shares its history.
    val continued_as: String = "",
)

@Serializable
data class CreateThreadResponse(
    val ok: Boolean = false,
    val thread: ThreadSummary = ThreadSummary(),
)

@Serializable
data class ActionResponse(
    val ok: Boolean = false,
    val resolved: Boolean = false,
    val removed: Boolean = false,
)

@Serializable
data class Account(
    val plan_type: String = "",
    val rate_limit_reached: String = "",
    val five_hour_available: Boolean = false,
    val five_hour_remaining_percent: Int = 0,
    val five_hour_reset_date: String = "",
    val weekly_available: Boolean = false,
    val weekly_remaining_percent: Int = 0,
    val weekly_used_percent: Int = 0,
    val reset_date: String = "",
    val weekly_tokens_available: Boolean = false,
    val weekly_tokens: Long = 0,
    val weekly_tokens_as_of: String = "",
)

@Serializable
data class ThreadSummary(
    val thread_id: String = "",
    val topic_id: Long = 0,
    val title: String = "",
    val preview: String = "",
    val status: String = "idle",
    val model: String = "",
    val cwd: String = "",
    val workspace: String = "",
    val source: String = "",
    val created_at: Long = 0,
    val updated_at: Long = 0,
    val recency_at: Long = 0,
    val completed_at: Long = 0,
    val turn_id: String = "",
    val tokens: Long = 0,
    val ephemeral: Boolean = false,
    val error_message: String = "",
    val owner_state: String = "",
    val owner_device_ref: String = "",
    val lease_generation: Long = 0,
)

@Serializable
data class ThreadsResponse(
    val ok: Boolean = false,
    val threads: List<ThreadSummary> = emptyList(),
    val next_cursor: String? = null,
)

@Serializable
data class PlanStep(
    val step: String = "",
    val status: String = "",
)

@Serializable
data class ApprovalQuestion(
    val id: String = "",
    val question: String = "",
    val options: List<String> = emptyList(),
)

@Serializable
data class MessageDetail(
    val status: String = "",
    val body_ref: String = "",
    val body: String = "",
    @SerialName("exit_code") val exitCode: Int? = null,
    val cwd: String = "",
    @SerialName("duration_ms") val durationMs: Long? = null,
    val output: String = "",
    val paths: List<String> = emptyList(),
    val diff: String = "",
    val result: String = "",
    val patch: String = "",
    val message: String = "",
    val steps: List<PlanStep> = emptyList(),
    val questions: List<ApprovalQuestion> = emptyList(),
)

@Serializable
data class MessageItem(
    val id: String = "",
    val kind: String = "",
    val text: String = "",
    val detail: MessageDetail = MessageDetail(),
    val turn_id: String = "",
    val created_at: Long = 0,
) {
    val isUser: Boolean get() = kind == "user"
    val isAssistant: Boolean get() = kind == "agent"
    val isCommentary: Boolean get() = kind == "commentary"
}

@Serializable
data class TaskSnapshot(
    val status: String = "",
    val thread_id: String = "",
    val turn_id: String = "",
    val title: String = "",
    val current_action: String = "",
    val last_user_message: String = "",
    val last_assistant_message: String = "",
    val conversation_mode: String = "",
    @SerialName("token_count") val tokenCount: Long = 0,
    @SerialName("token_count_available") val tokenCountAvailable: Boolean = false,
    val plan_completed: Int = 0,
    val plan_total: Int = 0,
    val started_at_epoch: Long = 0,
    val last_updated_epoch: Long = 0,
    val completed_at_epoch: Long = 0,
    @SerialName("error_message") val errorMessage: String = "",
) {
    val isWorking: Boolean get() = status == "working" || status == "waiting_user"
}

@Serializable
data class ApprovalRequest(
    val request_id: Int = 0,
    val kind: String = "",
    val method: String = "",
    val thread_id: String = "",
    val turn_id: String = "",
    val item_id: String = "",
    val command: String = "",
    val cwd: String = "",
    val reason: String = "",
    val created_at: Double = 0.0,
    val expires_at: Double = 0.0,
    val raw: MessageDetail = MessageDetail(),
) {
    val title: String
        get() = when (kind) {
            "command" -> "请求执行命令"
            "fileChange" -> "请求修改文件"
            "permissions" -> "请求额外权限"
            "input" -> "需要你的输入"
            "mcp" -> "工具请求确认"
            else -> "需要确认"
        }
}


@Serializable
data class ThreadSettings(
    @SerialName("model") val model: String = "",
    @SerialName("cwd") val cwd: String = "",
    @SerialName("effort") val effort: String = "",
    @SerialName("approval_policy") val approvalPolicy: String = "",
    @SerialName("sandbox_policy") val sandboxPolicy: String = "",
    @SerialName("approvals_reviewer") val approvalsReviewer: String = "",
    @SerialName("settings_revision") val settingsRevision: Long = 0,
    @SerialName("applied") val applied: Boolean = true,
)

@Serializable
data class StatusResponse(
    val ok: Boolean = false,
    val generated_at_epoch: Long = 0,
    val account: Account = Account(),
    val task: TaskSnapshot = TaskSnapshot(),
    val tasks: List<TaskSnapshot> = emptyList(),
    val result: TaskSnapshot = TaskSnapshot(),
    val pending_approvals: List<ApprovalRequest> = emptyList(),
)

@Serializable
data class ThreadDetailResponse(
    val ok: Boolean = false,
    val thread: ThreadSummary = ThreadSummary(),
    val task: TaskSnapshot = TaskSnapshot(),
    val messages: List<MessageItem> = emptyList(),
    val message_count: Int = 0,
    val truncated: Boolean = false,
    val continued_as: String = "",
    val pending_approvals: List<ApprovalRequest> = emptyList(),
    val queue: QueueSnapshot = QueueSnapshot(),
)

@Serializable
data class ModelEffort(
    val effort: String = "",
    val description: String = "",
)

@Serializable
data class ModelInfo(
    val id: String = "",
    val model: String = "",
    val display_name: String = "",
    val description: String = "",
    val is_default: Boolean = false,
    val default_effort: String = "",
    val efforts: List<ModelEffort> = emptyList(),
    val input_modalities: List<String> = emptyList(),
) {
    val label: String get() = display_name.ifBlank { model.ifBlank { id } }
    val slug: String get() = model.ifBlank { id }
}

@Serializable
data class ModelsResponse(
    val ok: Boolean = false,
    val models: List<ModelInfo> = emptyList(),
)

@Serializable
data class ProjectInfo(
    val project_id: String = "",
    val name: String = "",
    val cwd: String = "",
    val roots: List<String> = emptyList(),
    val updated_at: Long = 0,
)

@Serializable
data class ProjectsResponse(
    val ok: Boolean = false,
    val projects: List<ProjectInfo> = emptyList(),
    val default_cwd: String = "",
)

/** Parsed CBDP v1 event payload. */
@Serializable
data class StreamEvent(
    val type: String = "",
    @Transient val cbdpTopicId: Long = 0,
    @Transient val cbdpCursor: Long = 0,
    @Transient val cbdpScope: Int = 0,
    val thread_id: String = "",
    val turn_id: String = "",
    val item_id: String = "",
    val delta: String = "",
    val status: String = "",
    val completed_at: Long = 0,
    val read: Boolean = false,
    val title: String = "",
    val message: String = "",
    val decision: String = "",
    val request_id: Int = 0,
    val total_tokens: Long = 0,
    val context_window: Long = 0,
    val duration_ms: Long? = null,
    val active_flags: List<String> = emptyList(),
    val continued_as: String = "",
    val error_message: String = "",
    val item: MessageItem? = null,
    // Queue snapshots carry their rows under `items`; single-item queue events
    // reuse the `item` key, which is why they go through decodeStreamEvent().
    @SerialName("items") val queue: List<QueueItem> = emptyList(),
    @SerialName("queue_id") val queueId: String = "",
    val queueItem: QueueItem? = null,
    @SerialName("expected_revision") val expectedRevision: Long = 0,
    val removed: Boolean = false,
    val paused: Boolean = false,
    // Approval payload fields.
    val kind: String = "",
    val command: String = "",
    val cwd: String = "",
    val reason: String = "",
    val expires_at: Double = 0.0,
    val created_at: Double = 0.0,
    val raw: MessageDetail = MessageDetail(),
    // CBDP v1 writer lease and cross-device activity state.
    val owner_state: String = "",
    val writable: Boolean = false,
    val lease_generation: Long = 0,
    val lease_expires_at: Long = 0,
    val active_thread_id: String = "",
    val active_turn_id: String = "",
    val active_device_id: String = "",
    val active_revision: Long = 0,
)

private val streamEventJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    explicitNulls = false
}

/**
 * The Bridge publishes message items and queue items under the same `item`
 * field, so the event type has to decide how it is parsed. Queue events keep
 * their row in `queueItem`; everything else decodes normally.
 */
fun decodeStreamEvent(raw: String): StreamEvent? {
    val element = runCatching { Json.parseToJsonElement(raw) }.getOrNull() as? JsonObject
        ?: return null
    val type = (element["type"] as? JsonPrimitive)?.contentOrNull.orEmpty()
    if (!type.startsWith("queue_")) {
        return runCatching {
            streamEventJson.decodeFromString(StreamEvent.serializer(), raw)
        }.getOrNull()
    }
    val body = JsonObject(element - "item")
    val base = runCatching {
        streamEventJson.decodeFromString(StreamEvent.serializer(), body.toString())
    }.getOrNull() ?: return null
    val item = element["item"]?.let { value ->
        runCatching {
            streamEventJson.decodeFromJsonElement(QueueItem.serializer(), value)
        }.getOrNull()
    }
    return base.copy(queueItem = item)
}

@Serializable
data class ErrorEnvelope(
    val ok: Boolean = false,
    val error: String = "",
    val message: String = "",
)
