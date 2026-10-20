package com.codexbridge.mobile.data.cbdp

import com.codexbridge.mobile.data.ActionResponse
import com.codexbridge.mobile.data.Account
import com.codexbridge.mobile.data.ApprovalRequest
import com.codexbridge.mobile.data.CreateThreadResponse
import com.codexbridge.mobile.data.MessageDetail
import com.codexbridge.mobile.data.MessageItem
import com.codexbridge.mobile.data.ModelEffort
import com.codexbridge.mobile.data.ModelInfo
import com.codexbridge.mobile.data.ModelsResponse
import com.codexbridge.mobile.data.ProjectInfo
import com.codexbridge.mobile.data.ProjectsResponse
import com.codexbridge.mobile.data.QueueItem
import com.codexbridge.mobile.data.QueueResponse
import com.codexbridge.mobile.data.QueueSnapshot
import com.codexbridge.mobile.data.SendResponse
import com.codexbridge.mobile.data.StatusResponse
import com.codexbridge.mobile.data.StreamEvent
import com.codexbridge.mobile.data.TaskSnapshot
import com.codexbridge.mobile.data.ThreadDetailResponse
import com.codexbridge.mobile.data.ThreadSummary
import com.codexbridge.mobile.data.ThreadsResponse
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

internal object CbdpMapper {
    const val OBJECT_THREAD = 0
    const val OBJECT_TURN = 1
    const val OBJECT_MESSAGE = 2
    const val OBJECT_APPROVAL = 3
    const val OBJECT_DEVICE = 4
    const val OBJECT_QUEUE_ITEM = 5
    const val OBJECT_MODEL = 9
    const val OBJECT_PROJECT = 10

    fun ref(value: Long?): String = value?.takeIf { it > 0 }?.toString().orEmpty()

    fun threadRef(selector: String): Long = selector.toLongOrNull() ?: stableRef(selector)

    fun threadId(value: Long?, fallback: String = ""): String =
        value?.takeIf { it > 0 }?.let { "thread:$it" } ?: fallback

    fun messageId(value: Long?, fallback: String = ""): String =
        value?.takeIf { it > 0 }?.let { "message:$it" } ?: fallback

    fun turnId(value: Long?, fallback: String = ""): String =
        value?.takeIf { it > 0 }?.let { "turn:$it" } ?: fallback

    fun stableRef(value: String): Long {
        if (value.isBlank()) return 0
        var hash = 0x811C9DC5L
        value.forEach { ch ->
            hash = hash xor (ch.code.toLong() and 0xFF)
            hash = (hash * 0x01000193L) and 0xFFFF_FFFFL
        }
        return (hash and 0x0FFF_FFFFL).takeIf { it > 0 } ?: 1
    }

    fun threadFromObject(tlvs: List<CbdpTlv>): ThreadSummary {
        val ref = tlvs.u32(CbdpTag.THREAD_REF)
        val state = tlvs.u16(CbdpTag.STATUS) ?: tlvs.u8(CbdpTag.THREAD_STATE) ?: 6
        val activity = tlvs.u64(CbdpTag.LAST_ACTIVITY_AT_MS) ?: 0
        return ThreadSummary(
            thread_id = ref(ref),
            topic_id = tlvs.u32(CbdpTag.TOPIC_ID) ?: 0,
            title = tlvs.utf8(CbdpTag.TITLE).orEmpty(),
            preview = tlvs.utf8(CbdpTag.SUMMARY).orEmpty(),
            status = threadState(state),
            model = tlvs.utf8(CbdpTag.MODEL_NAME).orEmpty(),
            cwd = tlvs.utf8(CbdpTag.CWD).orEmpty(),
            workspace = tlvs.utf8(CbdpTag.CWD).orEmpty(),
            source = tlvs.utf8(CbdpTag.SOURCE_KIND).orEmpty(),
            created_at = tlvs.u64(CbdpTag.CREATED_AT_MS) ?: 0,
            updated_at = tlvs.u64(CbdpTag.UPDATED_AT_MS) ?: activity,
            recency_at = activity,
            completed_at = tlvs.u64(CbdpTag.COMPLETED_AT_MS) ?: 0,
            turn_id = turnId(tlvs.u32(CbdpTag.TURN_REF)),
            tokens = tlvs.tlv(CbdpTag.TOKEN_USAGE)?.u32(CbdpTag.TOKEN_TOTAL)?.toLong() ?: 0,
            ephemeral = boolean(tlvs, CbdpTag.EPHEMERAL),
            error_message = tlvs.utf8(CbdpTag.ERROR_MESSAGE).orEmpty(),
            owner_state = tlvs.u8(CbdpTag.OWNER_STATE)?.let(::ownerState).orEmpty(),
            owner_device_ref = ref(tlvs.u32(CbdpTag.OWNER_DEVICE_REF)),
            lease_generation = tlvs.u32(CbdpTag.LEASE_GENERATION)?.toLong() ?: 0,
        )
    }

    fun threadDetails(tlvs: List<CbdpTlv>, status: CbdpCommand): ThreadDetailResponse {
        val resultObjects = objects(status.result)
        val threadObject = resultObjects.firstOrNull { it.u8(CbdpTag.OBJECT_TYPE) == OBJECT_THREAD }
        val thread = threadObject?.let(::threadFromObject) ?: threadFromObject(tlvs)
        val messages = resultObjects.mapNotNull { objectTlvs ->
            when (objectTlvs.u8(CbdpTag.OBJECT_TYPE)) {
                OBJECT_MESSAGE -> messageFromObject(objectTlvs)
                else -> null
            }
        }
        val approvals = resultObjects.mapNotNull { objectTlvs ->
            when (objectTlvs.u8(CbdpTag.OBJECT_TYPE)) {
                OBJECT_APPROVAL -> approvalFromObject(objectTlvs)
                else -> null
            }
        }
        return ThreadDetailResponse(
            ok = true,
            thread = thread.copy(thread_id = thread.thread_id.ifBlank { ref(tlvs.u32(CbdpTag.THREAD_REF)) }),
            task = TaskSnapshot(
                status = thread.status,
                thread_id = thread.thread_id,
                turn_id = thread.turn_id,
                title = thread.title,
                current_action = tlvs.utf8(CbdpTag.CURRENT_ACTION).orEmpty(),
                tokenCount = thread.tokens,
                tokenCountAvailable = true,
                started_at_epoch = tlvs.u64(CbdpTag.STARTED_AT_MS) ?: 0,
                last_updated_epoch = thread.updated_at,
                completed_at_epoch = thread.completed_at,
                errorMessage = thread.error_message,
            ),
            messages = messages,
            message_count = messages.size,
            pending_approvals = approvals,
            queue = QueueSnapshot(threadId = thread.thread_id),
        )
    }

    fun threadsFromCommand(status: CbdpCommand): ThreadsResponse {
        val threads = objects(status.result).mapNotNull { objectTlvs ->
            if (objectTlvs.u8(CbdpTag.OBJECT_TYPE) == OBJECT_THREAD) threadFromObject(objectTlvs) else null
        }
        return ThreadsResponse(ok = true, threads = threads)
    }

    fun messageFromObject(tlvs: List<CbdpTlv>): MessageItem {
        val kind = tlvs.u8(CbdpTag.KIND) ?: 7
        val status = tlvs.u8(CbdpTag.STATUS) ?: 2
        val created = tlvs.u64(CbdpTag.CREATED_AT_MS) ?: 0
        val summary = tlvs.utf8(CbdpTag.SUMMARY).orEmpty()
        return MessageItem(
            id = ref(tlvs.u32(CbdpTag.MESSAGE_REF)),
            kind = messageKind(kind),
            text = tlvs.utf8(CbdpTag.BODY).orEmpty().ifBlank { summary },
            detail = MessageDetail(
                status = messageStatus(status),
                body_ref = tlvs.utf8(CbdpTag.BODY_REF).orEmpty(),
                cwd = tlvs.utf8(CbdpTag.CWD).orEmpty(),
                output = tlvs.utf8(CbdpTag.TOOL_OUTPUT).orEmpty(),
                result = tlvs.utf8(CbdpTag.TOOL_RESULT_SUMMARY).orEmpty(),
                message = tlvs.utf8(CbdpTag.ERROR_MESSAGE).orEmpty(),
            ),
            turn_id = turnId(tlvs.u32(CbdpTag.TURN_REF)),
            created_at = created,
        )
    }

    fun approvalFromObject(tlvs: List<CbdpTlv>): ApprovalRequest {
        val kind = tlvs.u8(CbdpTag.APPROVAL_KIND) ?: 6
        val ref = tlvs.u32(CbdpTag.APPROVAL_REF) ?: 0
        return ApprovalRequest(
            request_id = ref.toInt(),
            kind = approvalKind(kind),
            thread_id = ref(tlvs.u32(CbdpTag.THREAD_REF)),
            turn_id = turnId(tlvs.u32(CbdpTag.TURN_REF)),
            command = tlvs.utf8(CbdpTag.PROMPT).orEmpty(),
            cwd = tlvs.utf8(CbdpTag.CWD).orEmpty(),
            reason = tlvs.utf8(CbdpTag.APPROVAL_DETAIL).orEmpty(),
            created_at = (tlvs.u64(CbdpTag.CREATED_AT_MS) ?: 0).toDouble(),
            expires_at = (tlvs.u64(CbdpTag.EXPIRES_AT_MS) ?: 0).toDouble(),
            raw = MessageDetail(message = tlvs.utf8(CbdpTag.PROMPT).orEmpty()),
        )
    }

    fun queueFromCommand(status: CbdpCommand, threadId: String): QueueResponse {
        val items = objects(status.result).mapNotNull { objectTlvs ->
            if (objectTlvs.u8(CbdpTag.OBJECT_TYPE) == OBJECT_QUEUE_ITEM) {
                queueItemFromObject(objectTlvs, threadId)
            } else {
                null
            }
        }
        return QueueResponse(ok = true, threadId = threadId, queue = items)
    }

    fun queueItemFromObject(tlvs: List<CbdpTlv>, threadId: String): QueueItem {
        val ref = tlvs.u32(CbdpTag.QUEUE_ITEM_REF) ?: 0
        val created = (tlvs.u64(CbdpTag.CREATED_AT_MS) ?: 0).toDouble()
        val updated = (tlvs.u64(CbdpTag.UPDATED_AT_MS) ?: 0).toDouble()
        return QueueItem(
            queue_id = ref.toInt().toString(),
            thread_id = threadId.ifBlank { ref(tlvs.u32(CbdpTag.THREAD_REF)) },
            text = inputText(tlvs),
            model = tlvs.utf8(CbdpTag.MODEL_NAME).orEmpty(),
            effort = tlvs.utf8(CbdpTag.EFFORT).orEmpty(),
            status = queueState(tlvs.u8(CbdpTag.QUEUE_STATE) ?: 0),
            revision = tlvs.u64(CbdpTag.REVISION) ?: 0,
            createdAt = created,
            updatedAt = updated,
        )
    }

    fun modelsFromCommand(status: CbdpCommand): ModelsResponse {
        val models = objects(status.result).mapNotNull { objectTlvs ->
            if (objectTlvs.u8(CbdpTag.OBJECT_TYPE) != OBJECT_MODEL) return@mapNotNull null
            val slug = objectTlvs.utf8(CbdpTag.MODEL_NAME).orEmpty()
            if (slug.isBlank()) return@mapNotNull null
            val efforts = decodeArrayElements(objectTlvs.find(CbdpTag.MODEL_CAPABILITIES)?.value ?: ByteArray(0))
                .map { capability ->
                    val kind = capability.u8(CbdpTag.MODEL_CAPABILITY_KIND)
                    val value = capability.utf8(CbdpTag.MODEL_CAPABILITY_VALUE).orEmpty()
                        .ifBlank { capability.utf8(CbdpTag.NAME).orEmpty() }
                    value.takeIf { it.isNotBlank() && (kind == null || kind == 0) }
                        ?.let { ModelEffort(effort = it) }
                }
                .filterNotNull()
            ModelInfo(
                id = slug,
                model = slug,
                display_name = slug,
                default_effort = efforts.firstOrNull()?.effort.orEmpty(),
                efforts = efforts,
            )
        }
        return ModelsResponse(ok = true, models = models)
    }

    fun projectsFromCommand(status: CbdpCommand): ProjectsResponse {
        val projects = objects(status.result).mapNotNull { objectTlvs ->
            if (objectTlvs.u8(CbdpTag.OBJECT_TYPE) != OBJECT_PROJECT) return@mapNotNull null
            val id = objectTlvs.utf8(CbdpTag.PROJECT_ID).orEmpty()
                .ifBlank { ref(objectTlvs.u32(CbdpTag.PROJECT_REF)) }
            val cwd = objectTlvs.utf8(CbdpTag.CWD).orEmpty()
            ProjectInfo(
                project_id = id,
                name = objectTlvs.utf8(CbdpTag.TITLE).orEmpty().ifBlank { cwd },
                cwd = cwd,
                roots = listOfNotNull(cwd.takeIf { it.isNotBlank() }),
                updated_at = objectTlvs.u64(CbdpTag.UPDATED_AT_MS) ?: 0,
            )
        }
        return ProjectsResponse(ok = true, projects = projects, default_cwd = projects.firstOrNull()?.cwd.orEmpty())
    }

    fun statusFromCommand(
        status: CbdpCommand,
        account: Account = Account(),
    ): StatusResponse {
        val result = status.result
        val task = TaskSnapshot(
            status = if (result.u8(CbdpTag.ONLINE) != 0) "idle" else "offline",
            current_action = result.utf8(CbdpTag.CURRENT_ACTION).orEmpty(),
            last_updated_epoch = System.currentTimeMillis(),
        )
        return StatusResponse(
            ok = true,
            generated_at_epoch = System.currentTimeMillis(),
            account = account,
            task = task,
            tasks = listOf(task),
            result = task,
        )
    }

    fun accountFromCommands(
        account: CbdpCommand,
        rateLimits: CbdpCommand? = null,
        tokenUsage: CbdpCommand? = null,
    ): Account = accountFrom(
        account.result,
        rateLimits?.result.orEmpty(),
        tokenUsage?.result.orEmpty(),
    )

    fun createThreadResponse(status: CbdpCommand): CreateThreadResponse =
        CreateThreadResponse(ok = true, thread = threadFromObject(status.result))

    fun sendResponse(status: CbdpCommand, threadId: String): SendResponse =
        SendResponse(
            ok = true,
            thread_id = threadId.ifBlank { ref(status.result.u32(CbdpTag.THREAD_REF)) },
            turn_id = turnId(status.result.u32(CbdpTag.TURN_REF)),
            status = commandState(status.state),
        )

    fun actionResponse(status: CbdpCommand): ActionResponse =
        ActionResponse(ok = true, resolved = status.ok, removed = status.ok)

    fun streamEvent(event: CbdpEventEnvelope): StreamEvent? {
        val payload = event.payload
        return when (event.eventType) {
            CbdpEvent.THREAD_CREATED,
            CbdpEvent.THREAD_UPDATED -> StreamEvent(
                type = "thread_status",
                thread_id = ref(payload.u32(CbdpTag.THREAD_REF)),
                status = threadState(payload.u16(CbdpTag.STATUS) ?: payload.u8(CbdpTag.THREAD_STATE) ?: 6),
            )
            CbdpEvent.THREAD_DELETED -> StreamEvent(
                type = "thread_status",
                thread_id = ref(payload.u32(CbdpTag.THREAD_REF)),
                status = "deleted",
            )
            CbdpEvent.THREAD_TITLE_CHANGED -> StreamEvent(
                type = "thread_name",
                thread_id = ref(payload.u32(CbdpTag.THREAD_REF)),
                title = payload.utf8(CbdpTag.TITLE).orEmpty(),
            )
            CbdpEvent.THREAD_OWNER_CHANGED -> StreamEvent(
                type = "writer_state",
                thread_id = ref(payload.u32(CbdpTag.THREAD_REF)),
                owner_state = ownerState(payload.u8(CbdpTag.OWNER_STATE) ?: 0),
                lease_generation = payload.u32(CbdpTag.LEASE_GENERATION)?.toLong() ?: 0,
                lease_expires_at = payload.u64(CbdpTag.LEASE_EXPIRES_AT_MS) ?: 0,
                active_device_id = ref(payload.u32(CbdpTag.OWNER_DEVICE_REF)),
                reason = payload.utf8(CbdpTag.REASON).orEmpty(),
            )
            CbdpEvent.DEVICE_WRITER_CHANGED -> StreamEvent(
                type = "writer_state",
                owner_state = ownerState(payload.u8(CbdpTag.OWNER_STATE) ?: 0),
                lease_generation = payload.u32(CbdpTag.LEASE_GENERATION)?.toLong() ?: 0,
                lease_expires_at = payload.u64(CbdpTag.LEASE_EXPIRES_AT_MS) ?: 0,
                active_device_id = ref(payload.u32(CbdpTag.OWNER_DEVICE_REF)),
                message = payload.utf8(CbdpTag.REASON).orEmpty(),
            )
            CbdpEvent.THREAD_STATUS_CHANGED -> StreamEvent(
                type = "thread_status",
                thread_id = ref(payload.u32(CbdpTag.THREAD_REF)),
                status = threadState(payload.u16(CbdpTag.STATUS) ?: payload.u8(CbdpTag.THREAD_STATE) ?: 6),
            )
            CbdpEvent.THREAD_ARCHIVED,
            CbdpEvent.THREAD_UNARCHIVED -> StreamEvent(
                type = "thread_status",
                thread_id = ref(payload.u32(CbdpTag.THREAD_REF)),
                status = if (payload.bool(CbdpTag.ARCHIVED)) "archived" else "idle",
            )
            CbdpEvent.TURN_STARTED,
            CbdpEvent.TURN_UPDATED,
            CbdpEvent.TURN_PROGRESS -> StreamEvent(
                type = "turn_started",
                thread_id = ref(payload.u32(CbdpTag.THREAD_REF)),
                turn_id = turnId(payload.u32(CbdpTag.TURN_REF)),
                status = "working",
            )
            CbdpEvent.TURN_COMPLETED,
            CbdpEvent.TURN_FAILED,
            CbdpEvent.TURN_INTERRUPTED -> StreamEvent(
                type = "turn_completed",
                thread_id = ref(payload.u32(CbdpTag.THREAD_REF)),
                turn_id = turnId(payload.u32(CbdpTag.TURN_REF)),
                status = turnStatus(event.eventType),
                completed_at = payload.u64(CbdpTag.COMPLETED_AT_MS) ?: 0,
                error_message = payload.utf8(CbdpTag.ERROR_MESSAGE).orEmpty(),
            )
            CbdpEvent.MESSAGE_APPENDED,
            CbdpEvent.MESSAGE_UPDATED -> {
                val item = messageFromObject(payload).copy(
                    id = ref(payload.u32(CbdpTag.MESSAGE_REF)),
                    text = payload.utf8(CbdpTag.SUMMARY).orEmpty(),
                )
                StreamEvent(
                    type = if (event.eventType == CbdpEvent.MESSAGE_APPENDED) "item_started" else "item_completed",
                    thread_id = ref(payload.u32(CbdpTag.THREAD_REF)),
                    turn_id = turnId(payload.u32(CbdpTag.TURN_REF)),
                    item_id = item.id,
                    status = item.detail.status,
                    item = item,
                )
            }
            CbdpEvent.MESSAGE_DELETED -> StreamEvent(
                type = "message_deleted",
                thread_id = ref(payload.u32(CbdpTag.THREAD_REF)),
                item_id = ref(payload.u32(CbdpTag.MESSAGE_REF)),
                removed = true,
            )
            CbdpEvent.ITEM_STARTED -> StreamEvent(
                type = "item_started",
                thread_id = ref(payload.u32(CbdpTag.THREAD_REF)),
                turn_id = turnId(payload.u32(CbdpTag.TURN_REF)),
                item_id = ref(payload.u32(CbdpTag.ITEM_REF)),
                status = payload.utf8(CbdpTag.ITEM_STATUS).orEmpty(),
                item = itemFromEvent(payload),
            )
            CbdpEvent.ITEM_UPDATED,
            CbdpEvent.ITEM_COMPLETED,
            CbdpEvent.ITEM_FAILED,
            CbdpEvent.TOOL_COMPLETED,
            CbdpEvent.TOOL_FAILED -> StreamEvent(
                type = "item_completed",
                thread_id = ref(payload.u32(CbdpTag.THREAD_REF)),
                turn_id = turnId(payload.u32(CbdpTag.TURN_REF)),
                item_id = ref(payload.u32(CbdpTag.ITEM_REF)),
                status = payload.utf8(CbdpTag.ITEM_STATUS).orEmpty(),
                item = itemFromEvent(payload),
            )
            CbdpEvent.ACCOUNT_CHANGED -> StreamEvent(
                type = "account_changed",
                total_tokens = payload.tokenTotal(),
            )
            CbdpEvent.RATE_LIMITS_CHANGED -> StreamEvent(
                type = "account_changed",
                total_tokens = payload.tokenTotal(),
            )
            // Keep the existing telemetry event name for the phone UI while
            // translating from the CBDP v1 event payload.
            CbdpEvent.TOKEN_USAGE_CHANGED -> StreamEvent(
                type = "token_usage",
                total_tokens = payload.tokenTotal(),
            )
            CbdpEvent.MODEL_CATALOG_CHANGED -> StreamEvent(type = "models_changed")
            CbdpEvent.PROJECT_CATALOG_CHANGED -> StreamEvent(type = "projects_changed")
            CbdpEvent.MESSAGE_READ -> StreamEvent(
                type = "message_read",
                thread_id = ref(payload.u32(CbdpTag.THREAD_REF)),
                read = true,
            )
            CbdpEvent.APPROVAL_REQUESTED -> StreamEvent(
                type = "approval_request",
                thread_id = ref(payload.u32(CbdpTag.THREAD_REF)),
                turn_id = turnId(payload.u32(CbdpTag.TURN_REF)),
                request_id = (payload.u32(CbdpTag.APPROVAL_REF) ?: 0).toInt(),
                kind = approvalKind(payload.u8(CbdpTag.APPROVAL_KIND) ?: 6),
                command = payload.utf8(CbdpTag.PROMPT).orEmpty(),
                reason = payload.utf8(CbdpTag.APPROVAL_DETAIL).orEmpty(),
                expires_at = (payload.u64(CbdpTag.EXPIRES_AT_MS) ?: 0).toDouble(),
            )
            CbdpEvent.APPROVAL_RESOLVED,
            CbdpEvent.APPROVAL_EXPIRED -> StreamEvent(
                type = "approval_resolved",
                request_id = (payload.u32(CbdpTag.APPROVAL_REF) ?: 0).toInt(),
            )
            CbdpEvent.QUEUE_CHANGED,
            CbdpEvent.QUEUE_REORDERED -> StreamEvent(
                type = "queue_snapshot",
                thread_id = ref(payload.u32(CbdpTag.THREAD_REF)),
                queue = queueItems(payload, ref(payload.u32(CbdpTag.THREAD_REF))),
            )
            CbdpEvent.QUEUE_ITEM_ADDED,
            CbdpEvent.QUEUE_ITEM_UPDATED,
            CbdpEvent.QUEUE_ITEM_STARTED,
            CbdpEvent.QUEUE_ITEM_COMPLETED,
            CbdpEvent.QUEUE_ITEM_FAILED,
            CbdpEvent.QUEUE_ITEM_CANCELLED -> {
                val item = queueItem(payload, ref(payload.u32(CbdpTag.THREAD_REF)))
                StreamEvent(
                    type = when (event.eventType) {
                        CbdpEvent.QUEUE_ITEM_ADDED -> "queue_item_added"
                        CbdpEvent.QUEUE_ITEM_CANCELLED -> "queue_item_removed"
                        else -> "queue_item_updated"
                    },
                    thread_id = item.thread_id,
                    queueId = item.queue_id,
                    queueItem = item,
                    removed = event.eventType == CbdpEvent.QUEUE_ITEM_CANCELLED,
                )
            }
            CbdpEvent.ACTIVE_THREAD_CHANGED,
            CbdpEvent.ACTIVE_TURN_CHANGED -> StreamEvent(
                type = "active_state",
                active_thread_id = ref(payload.u32(CbdpTag.ACTIVE_THREAD_REF)),
                active_turn_id = turnId(payload.u32(CbdpTag.ACTIVE_TURN_REF)),
                active_device_id = ref(payload.u32(CbdpTag.ACTIVE_DEVICE_REF)),
                active_revision = payload.u32(CbdpTag.ACTIVE_REVISION)?.toLong() ?: 0,
                message = payload.utf8(CbdpTag.REASON).orEmpty(),
            )
            CbdpEvent.SYSTEM_RESYNC_REQUIRED,
            CbdpEvent.RESUME_REQUIRED -> StreamEvent(
                type = "resync_required",
                thread_id = ref(payload.u32(CbdpTag.RESUME_THREAD_REF)),
                message = "事件流需要重新同步",
            )
            CbdpEvent.SYSTEM_SHUTDOWN,
            CbdpEvent.DEVICE_OFFLINE -> StreamEvent(type = "stream_error", message = "电脑端连接已断开")
            else -> null
        }
            ?.copy(
                cbdpTopicId = event.topicId,
                cbdpCursor = event.cursor,
                cbdpScope = event.scope,
            )
    }

    private fun List<CbdpTlv>.tokenTotal(): Long =
        tlv(CbdpTag.TOKEN_USAGE)?.u32(CbdpTag.TOKEN_TOTAL)?.toLong()
            ?: tlv(CbdpTag.TOKEN_USAGE)?.u64(CbdpTag.TOKEN_TOTAL)
            ?: u32(CbdpTag.TOKEN_TOTAL)?.toLong()
            ?: u64(CbdpTag.TOKEN_TOTAL)
            ?: 0


    fun objects(tlvs: List<CbdpTlv>): List<List<CbdpTlv>> {
        val array = tlvs.find(CbdpTag.OBJECTS)?.value ?: return emptyList()
        return decodeArray(array, CbdpTag.OBJECT)
    }

    private fun decodeArray(bytes: ByteArray, expectedTag: Int): List<List<CbdpTlv>> =
        decodeArrayElements(bytes)
            .filter { it.firstOrNull()?.tag == expectedTag }
            .mapNotNull { wrapper ->
                wrapper.firstOrNull()?.value?.let { value ->
                    runCatching { CbdpCodec.decodeTlvs(value) }.getOrNull()
                }
            }

    private fun decodeArrayElements(bytes: ByteArray): List<List<CbdpTlv>> {
        if (bytes.size < 2) return emptyList()
        val count = CbdpCodec.readU16(bytes.copyOfRange(0, 2))
        val items = mutableListOf<List<CbdpTlv>>()
        var offset = 2
        repeat(count) {
            if (offset + 4 > bytes.size) return@repeat
            val tag = CbdpCodec.readU16(bytes.copyOfRange(offset, offset + 2))
            val length = CbdpCodec.readU16(bytes.copyOfRange(offset + 2, offset + 4))
            offset += 4
            if (offset + length > bytes.size) return@repeat
            val value = bytes.copyOfRange(offset, offset + length)
            offset += length
            items += listOf(CbdpTlv(tag, value))
        }
        return items
    }

    private fun List<CbdpTlv>.tlvOrNull(): List<CbdpTlv>? =
        firstOrNull()?.value?.let { CbdpCodec.decodeTlvs(it) }

    private fun boolean(tlvs: List<CbdpTlv>, tag: Int): Boolean = tlvs.u8(tag) != 0

    private fun inputText(tlvs: List<CbdpTlv>): String {
        val parts = tlvs.find(CbdpTag.INPUT_PARTS)?.value?.let { bytes ->
            decodeArrayElements(bytes).mapNotNull { part ->
                val item = part.firstOrNull() ?: return@mapNotNull null
                CbdpCodec.decodeTlvs(item.value)
            }
        }.orEmpty()
        return parts.mapNotNull { part ->
            part.utf8(CbdpTag.ALT_TEXT).orEmpty().ifBlank { part.utf8(CbdpTag.REFERENCE_TEXT).orEmpty() }.ifBlank { part.utf8(CbdpTag.DRAFT_TEXT).orEmpty() }
        }.firstOrNull { it.isNotBlank() }
            ?: tlvs.utf8(CbdpTag.BODY).orEmpty()
    }

    private fun queueItems(tlvs: List<CbdpTlv>, threadId: String): List<QueueItem> {
        val fromArray = tlvs.find(CbdpTag.QUEUE_ITEMS)?.value?.let { bytes ->
            decodeArray(bytes, CbdpTag.QUEUE_ITEM).mapNotNull { item ->
                item.firstOrNull()?.value?.let { CbdpCodec.decodeTlvs(it) }
                    ?.let { queueItemFromObject(it, threadId) }
            }
        }.orEmpty()
        if (fromArray.isNotEmpty()) return fromArray
        return objects(tlvs).mapNotNull { objectTlvs ->
            if (objectTlvs.u8(CbdpTag.OBJECT_TYPE) == OBJECT_QUEUE_ITEM) {
                queueItemFromObject(objectTlvs, threadId)
            } else null
        }
    }

    private fun queueItem(tlvs: List<CbdpTlv>, threadId: String): QueueItem =
        tlvs.tlv(CbdpTag.QUEUE_ITEM)?.let { queueItemFromObject(it, threadId) }
            ?: queueItemFromObject(tlvs, threadId)

    private fun itemFromEvent(tlvs: List<CbdpTlv>): MessageItem {
        val ref = tlvs.u32(CbdpTag.ITEM_REF)
        val kind = tlvs.utf8(CbdpTag.ITEM_TYPE).orEmpty().ifBlank { "system" }
        val text = tlvs.utf8(CbdpTag.TOOL_OUTPUT).orEmpty()
            .ifBlank { tlvs.utf8(CbdpTag.TOOL_INPUT).orEmpty() }
            .ifBlank { tlvs.utf8(CbdpTag.SUMMARY).orEmpty() }
        return MessageItem(
            id = ref(ref),
            kind = kind,
            text = text,
            detail = MessageDetail(
                status = tlvs.utf8(CbdpTag.ITEM_STATUS).orEmpty(),
                output = tlvs.utf8(CbdpTag.TOOL_OUTPUT).orEmpty(),
                message = tlvs.utf8(CbdpTag.TOOL_DISPLAY_NAME).orEmpty(),
                result = tlvs.utf8(CbdpTag.TOOL_RESULT_SUMMARY).orEmpty(),
            ),
        )
    }

    private fun accountFrom(
        tlvs: List<CbdpTlv>,
        rateLimitTlvs: List<CbdpTlv> = emptyList(),
        tokenTlvs: List<CbdpTlv> = emptyList(),
    ): Account {
        val plan = tlvs.utf8(CbdpTag.PLAN_TYPE).orEmpty()
        val reset = tlvs.u64(CbdpTag.RESET_AT_MS) ?: 0
        val windows = rateLimits(rateLimitTlvs)
        val fiveHour = windows.firstOrNull { it.window in setOf("5h", "5_hour", "five_hour") }
        val weekly = windows.firstOrNull { it.window in setOf("week", "weekly", "7d") }
        val totalTokens = tokenTlvs.tokenTotal()
        return Account(
            plan_type = plan,
            rate_limit_reached = windows.firstOrNull { it.remainingPercent <= 0 }?.window.orEmpty(),
            five_hour_available = fiveHour != null,
            five_hour_remaining_percent = fiveHour?.remainingPercent ?: 0,
            five_hour_reset_date = fiveHour?.resetAt?.takeIf { it > 0 }?.toString().orEmpty(),
            weekly_available = weekly != null,
            weekly_remaining_percent = weekly?.remainingPercent ?: 0,
            weekly_used_percent = weekly?.let { (100 - it.remainingPercent).coerceIn(0, 100) } ?: 0,
            reset_date = (weekly?.resetAt?.takeIf { it > 0 } ?: reset.takeIf { it > 0 })
                ?.toString().orEmpty(),
            weekly_tokens_available = totalTokens > 0,
            weekly_tokens = totalTokens,
        )
    }

    private data class RateLimitWindow(
        val window: String,
        val remainingPercent: Int,
        val resetAt: Long,
    )

    private fun rateLimits(tlvs: List<CbdpTlv>): List<RateLimitWindow> {
        val direct = tlvs.find(CbdpTag.RATE_LIMITS)?.value?.let { bytes ->
            decodeArrayElements(bytes).mapNotNull { element ->
                val item = element.firstOrNull()?.value?.let(CbdpCodec::decodeTlvs) ?: return@mapNotNull null
                rateLimitWindow(item)
            }
        }.orEmpty()
        if (direct.isNotEmpty()) return direct
        val repeated = tlvs.filter { it.tag == CbdpTag.RATE_LIMIT_WINDOW }.map { element ->
            RateLimitWindow(
                window = element.value.toString(Charsets.UTF_8),
                remainingPercent = 0,
                resetAt = 0,
            )
        }
        if (repeated.isNotEmpty()) return repeated
        val inline = rateLimitWindow(tlvs) ?: return emptyList()
        return listOf(inline)
    }

    private fun rateLimitWindow(tlvs: List<CbdpTlv>): RateLimitWindow? {
        val window = tlvs.utf8(CbdpTag.RATE_LIMIT_WINDOW).orEmpty()
            .ifBlank { tlvs.utf8(CbdpTag.NAME).orEmpty() }
            .ifBlank { tlvs.utf8(CbdpTag.TITLE).orEmpty() }
        val remaining = tlvs.u8(CbdpTag.REMAINING_PERCENT)
            ?: tlvs.u16(CbdpTag.REMAINING_PERCENT)
            ?: tlvs.u32(CbdpTag.REMAINING_PERCENT)?.toInt()
        val reset = tlvs.u64(CbdpTag.RESET_AT_MS)
        if (window.isBlank() && remaining == null && reset == null) return null
        return RateLimitWindow(window, remaining ?: 0, reset ?: 0)
    }

    private fun ownerState(value: Int): String = when (value) {
        0 -> "unmanaged"
        1 -> "observed"
        2 -> "claimed"
        3 -> "managed"
        else -> "unmanaged"
    }

    private fun threadState(value: Int): String = when (value) {
        0 -> "idle"
        1 -> "working"
        2 -> "completed"
        3 -> "failed"
        4 -> "interrupted"
        5 -> "archived"
        else -> "unknown"
    }

    private fun turnStatus(eventType: Int): String = when (eventType) {
        CbdpEvent.TURN_STARTED -> "working"
        CbdpEvent.TURN_UPDATED, CbdpEvent.TURN_PROGRESS -> "working"
        CbdpEvent.TURN_COMPLETED -> "completed"
        CbdpEvent.TURN_FAILED -> "failed"
        CbdpEvent.TURN_INTERRUPTED -> "interrupted"
        else -> "unknown"
    }

    private fun messageKind(value: Int): String = when (value) {
        0 -> "user"
        1 -> "agent"
        2 -> "commentary"
        3, 4 -> "tool"
        5 -> "error"
        6 -> "approval"
        else -> "system"
    }

    private fun messageStatus(value: Int): String = when (value) {
        0 -> "pending"
        1 -> "streaming"
        2 -> "completed"
        3 -> "failed"
        4 -> "interrupted"
        else -> "unknown"
    }

    private fun queueState(value: Int): String = when (value) {
        0 -> "pending"
        1 -> "starting"
        2 -> "running"
        3 -> "completed"
        4 -> "failed"
        5 -> "cancelled"
        else -> "pending"
    }

    private fun commandState(value: Int): String = when (value) {
        CbdpCommandState.ACCEPTED -> "accepted"
        CbdpCommandState.COMPLETED -> "completed"
        CbdpCommandState.FAILED -> "failed"
        CbdpCommandState.REJECTED -> "rejected"
        else -> "unknown"
    }

    private fun approvalKind(value: Int): String = when (value) {
        CbdpApprovalKind.COMMAND_EXECUTION -> "command"
        CbdpApprovalKind.FILE_CHANGE -> "fileChange"
        CbdpApprovalKind.PERMISSION, CbdpApprovalKind.NETWORK_ACCESS -> "permissions"
        CbdpApprovalKind.USER_INPUT, CbdpApprovalKind.MCP_ELICITATION -> "input"
        CbdpApprovalKind.MCP_TOOL -> "mcp"
        else -> "other"
    }
}
