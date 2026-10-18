package com.codexbridge.mobile.data

import com.codexbridge.mobile.data.cbdp.CbdpApprovalDecision
import com.codexbridge.mobile.data.cbdp.CbdpClient
import com.codexbridge.mobile.data.cbdp.CbdpCommand
import com.codexbridge.mobile.data.cbdp.CbdpErrorCode
import com.codexbridge.mobile.data.cbdp.CbdpMapper
import com.codexbridge.mobile.data.cbdp.CbdpMethod
import com.codexbridge.mobile.data.cbdp.CbdpScope
import com.codexbridge.mobile.data.cbdp.CbdpTag
import com.codexbridge.mobile.data.cbdp.CbdpTopic
import com.codexbridge.mobile.data.cbdp.CbdpCodec
import com.codexbridge.mobile.data.cbdp.CbdpContentKind
import com.codexbridge.mobile.data.cbdp.CbdpTlv
import com.codexbridge.mobile.data.cbdp.tlv
import com.codexbridge.mobile.data.cbdp.u32
import com.codexbridge.mobile.data.cbdp.u8
import com.codexbridge.mobile.data.cbdp.u64
import com.codexbridge.mobile.data.cbdp.utf8
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import java.util.concurrent.TimeUnit

class ApiException(
    message: String,
    val code: String = "",
    val unauthorized: Boolean = false,
) : Exception(message)

class ApiClient(
    baseUrl: String,
    private val token: String? = null,
    private val phoneId: String = "",
) {
    val base: String = normalizeBase(baseUrl)

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
        encodeDefaults = true
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    @Volatile
    private var cbdp: CbdpClient? = null

    private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun url(path: String): String = base + path

    private fun builder(path: String, method: String, body: RequestBody?): Request.Builder {
        val request = Request.Builder().url(url(path)).method(method, body)
        if (!token.isNullOrBlank()) {
            request.header("Authorization", "Bearer $token")
        }
        request.header("Accept", "application/json")
        return request
    }

    private suspend fun call(request: Request.Builder): String = withContext(Dispatchers.IO) {
        client.newCall(request.build()).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw parseError(response, text)
            text
        }
    }

    private fun parseError(response: Response, body: String): ApiException {
        val envelope = runCatching { json.decodeFromString(ErrorEnvelope.serializer(), body) }.getOrNull()
        val code = envelope?.error.orEmpty().ifBlank { "http_${response.code}" }
        val message = envelope?.message.orEmpty().ifBlank {
            when (code) {
                "unauthorized" -> "配对已失效，请在手机端重新配对"
                "companion_disabled" -> "电脑端已关闭手机连接"
                "codex_unavailable" -> "电脑端的 Codex 暂时不可用"
                else -> "请求失败（$code）"
            }
        }
        return ApiException(message, code, response.code == 401 || code == "unauthorized")
    }

    private suspend fun getText(path: String): String = call(builder(path, "GET", null))

    private suspend fun postText(path: String, payload: JsonObject): String =
        call(
            builder(
                path,
                "POST",
                payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()),
            ),
        )

    private fun ensureCbdp(): CbdpClient {
        val current = cbdp
        if (current != null) return current
        synchronized(this) {
            cbdp?.let { return it }
            val created = CbdpClient(
                baseUrl = base,
                deviceId = phoneId.ifBlank { "android" },
                token = token.orEmpty(),
                model = "android",
                firmware = "1.0.0",
            )
            cbdp = created
            return created
        }
    }

    private suspend fun execute(methodId: Int, params: List<CbdpTlv> = emptyList()): CbdpCommand =
        try {
            ensureCbdp().execute(methodId, params)
        } catch (error: Exception) {
            if (error is ApiException) throw error
            throw ApiException(error.message ?: "CBDP 请求失败", "cbdp_error")
        }

    private fun requireThreadRef(threadId: String): Long = CbdpMapper.threadRef(threadId)

    suspend fun deviceRef(): String {
        ensureCbdp().connect()
        return ensureCbdp().deviceRef.takeIf { it > 0 }?.toString().orEmpty()
    }

    suspend fun ping(): PingResponse =
        json.decodeFromString(PingResponse.serializer(), getText("/api/companion/ping"))

    suspend fun pair(code: String, name: String, clientId: String = ""): PairResponse =
        json.decodeFromString(
            PairResponse.serializer(),
            postText(
                "/api/companion/pair",
                buildJsonObject {
                    put("code", code)
                    put("name", name)
                    if (clientId.isNotBlank()) put("client_id", clientId)
                },
            ),
        )

    suspend fun status(): StatusResponse {
        val status = execute(CbdpMethod.STATUS_READ)
        return CbdpMapper.statusFromCommand(status)
    }

    suspend fun account(): Account {
        val account = execute(CbdpMethod.ACCOUNT_READ)
        val rateLimits = runCatching { execute(CbdpMethod.ACCOUNT_RATE_LIMITS_READ) }.getOrNull()
        val tokenUsage = runCatching { execute(CbdpMethod.ACCOUNT_TOKEN_USAGE_READ) }.getOrNull()
        return CbdpMapper.accountFromCommands(account, rateLimits, tokenUsage)
    }

    suspend fun threads(search: String = ""): ThreadsResponse {
        val params = mutableListOf<CbdpTlv>()
        if (search.isNotBlank()) {
            params += CbdpTlv(CbdpTag.SEARCH_SCOPE, CbdpCodec.u8(0))
            params += CbdpTlv(CbdpTag.TITLE, search.toByteArray())
        }
        return CbdpMapper.threadsFromCommand(execute(CbdpMethod.THREADS_LIST, params))
    }

    suspend fun thread(threadId: String): ThreadDetailResponse {
        val threadRef = requireThreadRef(threadId)
        val detail = CbdpMapper.threadDetails(
            emptyList(),
            execute(CbdpMethod.THREAD_READ, listOf(CbdpTlv(CbdpTag.THREAD_REF, CbdpCodec.u32(threadRef)))),
        )
        val loadedMessages = detail.messages.map { message ->
            val bodyRef = message.detail.body_ref
            if (bodyRef.isBlank()) return@map message
            var offset = 0L
            var contentKind = CbdpContentKind.MESSAGE_BODY
            val text = StringBuilder()
            var complete = false
            var guard = 0
            while (!complete && guard++ < MAX_CONTENT_READ_PAGES) {
                val command = ensureCbdp().contentRead(bodyRef, offset = offset, contentKind = contentKind)
                val result = command.result
                val responseRef = result.utf8(CbdpTag.CONTENT_REF)
                if (!responseRef.isNullOrBlank() && responseRef != bodyRef) {
                    throw ApiException("content_ref mismatch", "invalid_content_result")
                }
                contentKind = result.u8(CbdpTag.CONTENT_KIND) ?: contentKind
                if (command.payload.size > ensureCbdp().maxChunkBytes) {
                    throw ApiException("content chunk too large", "invalid_content_result")
                }
                val total = result.u32(CbdpTag.TOTAL_BYTES)
                val next = result.u32(CbdpTag.NEXT_OFFSET)
                if (total == null || next == null || next < offset || next > total) {
                    throw ApiException("invalid content pagination", "invalid_content_result")
                }
                text.append(command.payload.toString(Charsets.UTF_8))
                offset = next
                complete = offset == total
            }
            if (!complete) throw ApiException("content pagination limit reached", "invalid_content_result")
            message.copy(detail = message.detail.copy(body = text.toString()), text = text.toString())
        }
        return detail.copy(
            messages = loadedMessages,
            task = detail.task,
            queue = detail.queue.copy(items = detail.queue.items),
        )
    }

    suspend fun models(): ModelsResponse = CbdpMapper.modelsFromCommand(execute(CbdpMethod.MODELS_LIST))

    suspend fun projects(): ProjectsResponse = CbdpMapper.projectsFromCommand(execute(CbdpMethod.PROJECTS_LIST))

    suspend fun createThread(cwd: String, model: String): CreateThreadResponse {
        val params = mutableListOf<CbdpTlv>()
        if (cwd.isNotBlank()) params += CbdpTlv(CbdpTag.CWD, cwd.toByteArray())
        if (model.isNotBlank()) params += CbdpTlv(CbdpTag.MODEL_NAME, model.toByteArray())
        return CbdpMapper.createThreadResponse(execute(CbdpMethod.THREAD_START, params))
    }

    suspend fun sendMessage(
        threadId: String,
        text: String,
        model: String = "",
        effort: String = "",
    ): SendResponse {
        val txId = (System.nanoTime() / 1_000_000L).coerceAtLeast(1)
        val command = ensureCbdp().sendText(
            txId = txId,
            text = text,
            threadRef = requireThreadRef(threadId),
            model = model,
            effort = effort,
        )
        return CbdpMapper.sendResponse(command, threadId)
    }

    suspend fun queue(threadId: String): QueueResponse =
        CbdpMapper.queueFromCommand(
            execute(CbdpMethod.QUEUE_LIST, listOf(CbdpTlv(CbdpTag.THREAD_REF, CbdpCodec.u32(requireThreadRef(threadId))))),
            threadId,
        )

    suspend fun enqueueMessage(
        threadId: String,
        text: String,
        model: String = "",
        effort: String = "",
    ): SendResponse {
        val params = mutableListOf(
            CbdpTlv(CbdpTag.THREAD_REF, CbdpCodec.u32(requireThreadRef(threadId))),
            CbdpTlv(CbdpTag.BODY, text.toByteArray()),
        )
        if (model.isNotBlank()) params += CbdpTlv(CbdpTag.MODEL_NAME, model.toByteArray())
        if (effort.isNotBlank()) params += CbdpTlv(CbdpTag.EFFORT, effort.toByteArray())
        return CbdpMapper.sendResponse(execute(CbdpMethod.QUEUE_ADD, params), threadId)
    }

    suspend fun steerQueue(queueId: String, expectedRevision: Long): SendResponse {
        val params = mutableListOf(
            CbdpTlv(CbdpTag.QUEUE_ITEM_REF, CbdpCodec.u32(queueId.toLongOrNull() ?: CbdpMapper.stableRef(queueId))),
        )
        if (expectedRevision > 0) params += CbdpTlv(CbdpTag.EXPECTED_REVISION, CbdpCodec.u64(expectedRevision))
        return CbdpMapper.sendResponse(execute(CbdpMethod.QUEUE_START, params), "")
    }

    suspend fun editQueue(
        queueId: String,
        text: String,
        expectedRevision: Long,
        model: String = "",
        effort: String = "",
    ): SendResponse {
        val params = mutableListOf(
            CbdpTlv(CbdpTag.QUEUE_ITEM_REF, CbdpCodec.u32(queueId.toLongOrNull() ?: CbdpMapper.stableRef(queueId))),
            CbdpTlv(CbdpTag.BODY, text.toByteArray()),
        )
        if (expectedRevision > 0) params += CbdpTlv(CbdpTag.EXPECTED_REVISION, CbdpCodec.u64(expectedRevision))
        if (model.isNotBlank()) params += CbdpTlv(CbdpTag.MODEL_NAME, model.toByteArray())
        if (effort.isNotBlank()) params += CbdpTlv(CbdpTag.EFFORT, effort.toByteArray())
        return CbdpMapper.sendResponse(execute(CbdpMethod.QUEUE_UPDATE, params), "")
    }

    suspend fun deleteQueue(queueId: String, expectedRevision: Long): SendResponse {
        val params = mutableListOf(
            CbdpTlv(CbdpTag.QUEUE_ITEM_REF, CbdpCodec.u32(queueId.toLongOrNull() ?: CbdpMapper.stableRef(queueId))),
        )
        if (expectedRevision > 0) params += CbdpTlv(CbdpTag.EXPECTED_REVISION, CbdpCodec.u64(expectedRevision))
        return CbdpMapper.sendResponse(execute(CbdpMethod.QUEUE_DELETE, params), "")
    }

    suspend fun markThreadRead(threadId: String): SendResponse =
        CbdpMapper.sendResponse(
            execute(CbdpMethod.THREAD_READ_STATE_UPDATE, listOf(CbdpTlv(CbdpTag.THREAD_REF, CbdpCodec.u32(requireThreadRef(threadId))))),
            threadId,
        )

    suspend fun claimThread(threadId: String, force: Boolean = false): ActionResponse {
        val command = ensureCbdp().claimThread(requireThreadRef(threadId), force)
        return CbdpMapper.actionResponse(command)
    }

    suspend fun releaseThread(threadId: String, leaseGeneration: Long): ActionResponse {
        val command = ensureCbdp().releaseThread(requireThreadRef(threadId), leaseGeneration)
        return CbdpMapper.actionResponse(command)
    }

    suspend fun interrupt(threadId: String, turnId: String = ""): SendResponse {
        val params = mutableListOf(CbdpTlv(CbdpTag.THREAD_REF, CbdpCodec.u32(requireThreadRef(threadId))))
        turnId.toLongOrNull()?.takeIf { it > 0 }?.let { params += CbdpTlv(CbdpTag.TURN_REF, CbdpCodec.u32(it)) }
        return CbdpMapper.sendResponse(execute(CbdpMethod.TURN_INTERRUPT, params), threadId)
    }

    suspend fun resolveApproval(requestId: Int, decision: String): ActionResponse =
        CbdpMapper.actionResponse(
            execute(
                CbdpMethod.APPROVAL_RESOLVE,
                listOf(
                    CbdpTlv(CbdpTag.APPROVAL_REF, CbdpCodec.u32(requestId.toLong())),
                    CbdpTlv(CbdpTag.DECISION, CbdpCodec.u8(decisionValue(decision))),
                ),
            ),
        )

    suspend fun respondApproval(requestId: Int, response: JsonObject): ActionResponse =
        resolveApproval(requestId, response["decision"]?.jsonPrimitive?.content ?: "decline")

    private fun decisionValue(decision: String): Int = when (decision) {
        "accept", "approved", "approve" -> CbdpApprovalDecision.ACCEPT
        "acceptForSession", "accept_for_session" -> CbdpApprovalDecision.ACCEPT_FOR_SESSION
        "cancel" -> CbdpApprovalDecision.CANCEL
        else -> CbdpApprovalDecision.DECLINE
    }

    fun events(threadId: String? = null): Flow<StreamEvent> = callbackFlow {
        val client = ensureCbdp()
        val activeThread = threadId.orEmpty()
        val threadRef = CbdpMapper.threadRef(activeThread).takeIf { activeThread.isNotBlank() }
        val job = launch {
            try {
                client.subscribe(CbdpTopic.SYSTEM_THREAD_SUMMARIES, CbdpScope.SUMMARY)
                if (threadRef != null) {
                    client.subscribe(threadRef, CbdpScope.LIVE)
                    client.subscribe(threadRef, CbdpScope.THREAD)
                }
                client.events.collect { envelope ->
                    CbdpMapper.streamEvent(envelope)?.let { trySend(it) }
                }
            } catch (error: Throwable) {
                trySend(StreamEvent(type = "stream_error", message = error.message ?: "事件流已断开"))
                close()
            }
        }
        awaitClose {
            job.cancel()
            if (threadRef != null) {
                cleanupScope.launch {
                    runCatching { client.unsubscribeTopics(listOf(threadRef)) }
                }
            }
        }
    }

    companion object {
        const val DEFAULT_PORT = 8568
        const val DEFAULT_DISCOVERY_PORT = 8764
        private const val LEGACY_DEFAULT_PORT = 8790
        private const val LEGACY_COMPANION_PORT = 8791
        private const val LEGACY_DISCOVERY_PORT = 8766
        private const val MAX_CONTENT_READ_PAGES = 4096

        internal fun commandPayload(text: String): String {
            val envelope = Json.parseToJsonElement(text).jsonObject
            if (envelope["ok"]?.jsonPrimitive?.booleanOrNull != true) {
                val code = envelope["error"]?.jsonPrimitive?.content ?: "invalid_command_result"
                throw ApiException("请求未确认（$code）", code)
            }
            val result = envelope["result"] as? JsonObject
                ?: throw ApiException("无效的命令响应", "invalid_command_result")
            return JsonObject(result + ("ok" to kotlinx.serialization.json.JsonPrimitive(true))).toString()
        }

        fun migrateStoredBase(value: String): String {
            val text = value.trim()
            return if (text.isEmpty()) "" else normalizeBase(text)
        }

        fun normalizeBase(value: String): String {
            var text = value.trim()
            if (text.isEmpty()) throw ApiException("请填写电脑地址")
            if (!text.startsWith("http://") && !text.startsWith("https://")) text = "http://$text"
            val schemeEnd = text.indexOf("://") + 3
            val rest = text.substring(schemeEnd)
            val hostPart = rest.substringBefore('/')
            val tail = rest.substring(hostPart.length)
            if (hostPart.isNotBlank() && !hostPart.contains(':')) {
                text = text.substring(0, schemeEnd) + hostPart + ":$DEFAULT_PORT" + tail
            } else if (
                hostPart.endsWith(":$LEGACY_DEFAULT_PORT") ||
                hostPart.endsWith(":$LEGACY_COMPANION_PORT") ||
                hostPart.endsWith(":$LEGACY_DISCOVERY_PORT")
            ) {
                text = text.substring(0, schemeEnd) +
                    hostPart.substringBeforeLast(':') +
                    ":$DEFAULT_PORT" +
                    tail
            }
            return text.trimEnd('/')
        }

        fun withPort(value: String, port: Int): String {
            if (port !in 1..65535) throw ApiException("端口无效")
            var text = value.trim()
            if (text.isEmpty()) throw ApiException("请填写电脑地址")
            if (!text.startsWith("http://") && !text.startsWith("https://")) text = "http://$text"
            val schemeEnd = text.indexOf("://") + 3
            val rest = text.substring(schemeEnd)
            val hostPart = rest.substringBefore('/')
            val tail = rest.substring(hostPart.length)
            val host = hostPart.substringBeforeLast(':', hostPart)
            return (text.substring(0, schemeEnd) + host + ":$port" + tail).trimEnd('/')
        }
    }
}
