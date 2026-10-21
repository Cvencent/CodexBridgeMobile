package com.codexbridge.mobile.data

import com.codexbridge.mobile.data.cbdp.CbdpApprovalDecision
import com.codexbridge.mobile.data.cbdp.CbdpClient
import com.codexbridge.mobile.data.cbdp.CbdpClientException
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
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

data class BridgeCapabilities(
    val threadSettings: Boolean = false,
    val approvalResponse: Boolean = false,
    val attachments: Boolean = false,
)

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

    private val probeClient = client.newBuilder()
        .readTimeout(3, TimeUnit.SECONDS)
        .build()

    private enum class Transport { UNKNOWN, CBDP, COMPANION }

    private val transportLock = Mutex()

    @Volatile
    private var transport: Transport = Transport.UNKNOWN

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

    private suspend fun command(method: String, params: JsonObject): String =
        commandPayload(
            postText(
                "/api/companion/commands",
                buildJsonObject {
                    put("request_id", java.util.UUID.randomUUID().toString())
                    put("method", method)
                    put("params", params)
                },
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
                firmware = "1.1.3",
            )
            cbdp = created
            return created
        }
    }

    val supportsWriterLease: Boolean
        get() = transport != Transport.COMPANION

    private suspend fun resolveTransport(): Transport {
        val known = transport
        if (known != Transport.UNKNOWN) return known
        return transportLock.withLock {
            val current = transport
            if (current != Transport.UNKNOWN) return@withLock current
            val detected = if (probeCbdp()) Transport.CBDP else Transport.COMPANION
            transport = detected
            detected
        }
    }

    private suspend fun probeCbdp(): Boolean {
        val direct = runCatching {
            json.decodeFromString(CbdpCapability.serializer(), getText("/cbdp/v1"))
        }.getOrNull()
        if (direct?.enabled == true && direct.versionMajor >= 1) return true
        val ping = runCatching { ping() }.getOrNull()
        return ping?.cbdpV1?.enabled == true && (ping.cbdpV1?.versionMajor ?: 0) >= 1
    }

    private suspend fun <T> withTransport(
        cbdpCall: suspend () -> T,
        companionCall: suspend () -> T,
    ): T = when (resolveTransport()) {
        Transport.CBDP -> try {
            cbdpCall()
        } catch (error: CbdpClientException) {
            if (error.unauthorized || error.code == CbdpErrorCode.NOT_FOUND) {
                fallBackToCompanion()
                companionCall()
            } else {
                throw error
            }
        } catch (error: ApiException) {
            if (error.code == "cbdp_timeout") {
                fallBackToCompanion()
                companionCall()
            } else {
                throw error
            }
        }
        Transport.COMPANION -> companionCall()
        Transport.UNKNOWN -> error("transport not resolved")
    }

    private suspend fun <T : Any> withCbdpList(
        cbdpCall: suspend () -> T?,
        companionCall: suspend () -> T,
    ): T = when (resolveTransport()) {
        Transport.CBDP -> try {
            cbdpCall() ?: run {
                // Bridge 2.0.4 exposes CBDP HTTP routes but its generic command
                // result bridge does not yet encode object arrays. Keep the UI
                // usable with the legacy companion data until that is complete.
                fallBackToCompanion()
                companionCall()
            }
        } catch (error: CbdpClientException) {
            if (error.unauthorized || error.code == CbdpErrorCode.NOT_FOUND) {
                fallBackToCompanion()
                companionCall()
            } else {
                throw error
            }
        }
        Transport.COMPANION -> companionCall()
        Transport.UNKNOWN -> error("transport not resolved")
    }

    private fun hasObjectArray(tlvs: List<CbdpTlv>): Boolean =
        tlvs.any { it.tag and 0xFFFF == CbdpTag.OBJECTS }

    private fun fallBackToCompanion() {
        transport = Transport.COMPANION
        cbdp?.close()
        cbdp = null
    }

    private suspend fun execute(methodId: Int, params: List<CbdpTlv> = emptyList()): CbdpCommand =
        try {
            ensureCbdp().execute(methodId, params)
        } catch (error: Exception) {
            if (error is ApiException) throw error
            throw ApiException(
                error.message ?: "CBDP 请求失败",
                if (error is kotlinx.coroutines.TimeoutCancellationException) "cbdp_timeout" else "cbdp_error",
            )
        }

    private fun requireThreadRef(threadId: String): Long = CbdpMapper.threadRef(threadId)

    suspend fun acknowledgeEvent(event: StreamEvent) {
        if (resolveTransport() != Transport.CBDP) return
        ensureCbdp().acknowledge(
            topicId = event.cbdpTopicId,
            cursor = event.cbdpCursor,
            scope = event.cbdpScope,
        )
    }

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

    suspend fun status(): StatusResponse = withTransport(
        cbdpCall = { CbdpMapper.statusFromCommand(execute(CbdpMethod.STATUS_READ)) },
        companionCall = {
            json.decodeFromString(StatusResponse.serializer(), getText("/api/companion/status"))
        },
    )

    suspend fun account(): Account = withTransport(
        cbdpCall = {
            val account = execute(CbdpMethod.ACCOUNT_READ)
            val rateLimits = runCatching { execute(CbdpMethod.ACCOUNT_RATE_LIMITS_READ) }.getOrNull()
            val tokenUsage = runCatching { execute(CbdpMethod.ACCOUNT_TOKEN_USAGE_READ) }.getOrNull()
            CbdpMapper.accountFromCommands(account, rateLimits, tokenUsage)
        },
        companionCall = { status().account },
    )

    suspend fun threads(search: String = ""): ThreadsResponse = withCbdpList(
        cbdpCall = { cbdpThreads(search) },
        companionCall = {
            val query = if (search.isBlank()) "" else "?search=" + java.net.URLEncoder.encode(search, "UTF-8")
            json.decodeFromString(ThreadsResponse.serializer(), getText("/api/companion/threads$query"))
        },
    )

    private suspend fun cbdpThreads(search: String): ThreadsResponse? {
        val collected = mutableListOf<com.codexbridge.mobile.data.ThreadSummary>()
        var cursor = 0L
        var guard = 0
        do {
            val params = mutableListOf<CbdpTlv>()
            if (search.isNotBlank()) {
                params += CbdpTlv(CbdpTag.SEARCH_SCOPE, CbdpCodec.u8(0))
                params += CbdpTlv(CbdpTag.TITLE, search.toByteArray())
            }
            if (cursor > 0) params += CbdpTlv(CbdpTag.CURSOR, CbdpCodec.u64(cursor))
            val command = execute(CbdpMethod.THREADS_LIST, params)
            if (!hasObjectArray(command.result)) return null
            val page = CbdpMapper.threadsFromCommand(command)
            collected += page.threads
            cursor = command.result.u64(CbdpTag.NEXT_CURSOR) ?: 0L
            guard += 1
        } while (cursor > 0 && guard < MAX_LIST_PAGES)
        return ThreadsResponse(ok = true, threads = collected)
    }

    suspend fun thread(threadId: String): ThreadDetailResponse = withTransport(
        cbdpCall = { cbdpThread(threadId) },
        companionCall = {
            json.decodeFromString(
                ThreadDetailResponse.serializer(),
                getText("/api/companion/threads/" + java.net.URLEncoder.encode(threadId, "UTF-8")),
            )
        },
    )

    private suspend fun cbdpThread(threadId: String): ThreadDetailResponse {
        val threadRef = requireThreadRef(threadId)
        val detail = cbdpReadDetail(threadRef)
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

    private suspend fun cbdpReadDetail(threadRef: Long): ThreadDetailResponse {
        val messages = mutableListOf<com.codexbridge.mobile.data.MessageItem>()
        var cursor = 0L
        var guard = 0
        var first: ThreadDetailResponse? = null
        do {
            val params = mutableListOf(CbdpTlv(CbdpTag.THREAD_REF, CbdpCodec.u32(threadRef)))
            if (cursor > 0) params += CbdpTlv(CbdpTag.CURSOR, CbdpCodec.u64(cursor))
            val command = execute(CbdpMethod.THREAD_READ, params)
            val page = CbdpMapper.threadDetails(command.result, command)
            if (first == null) {
                first = page
            }
            messages += page.messages
            cursor = command.result.u64(CbdpTag.NEXT_CURSOR) ?: 0L
            guard += 1
        } while (cursor > 0 && guard < MAX_LIST_PAGES)
        val base = first ?: throw ApiException("thread.read returned no detail", "invalid_command_result")
        return base.copy(messages = messages, message_count = messages.size)
    }

    suspend fun models(): ModelsResponse = withCbdpList(
        cbdpCall = {
            val command = execute(CbdpMethod.MODELS_LIST)
            val models = CbdpMapper.modelsFromCommand(command)
            models.takeIf { hasObjectArray(command.result) && models.models.isNotEmpty() }
        },
        companionCall = {
            json.decodeFromString(ModelsResponse.serializer(), getText("/api/companion/models"))
        },
    )

    suspend fun projects(): ProjectsResponse = withCbdpList(
        cbdpCall = {
            val command = execute(CbdpMethod.PROJECTS_LIST)
            val projects = CbdpMapper.projectsFromCommand(command)
            projects.takeIf { hasObjectArray(command.result) && projects.projects.isNotEmpty() }
        },
        companionCall = {
            json.decodeFromString(ProjectsResponse.serializer(), getText("/api/companion/projects"))
        },
    )

    suspend fun createThread(cwd: String, model: String): CreateThreadResponse = withTransport(
        cbdpCall = { cbdpCreateThread(cwd, model) },
        companionCall = {
            json.decodeFromString(
                CreateThreadResponse.serializer(),
                postText(
                    "/api/companion/threads",
                    buildJsonObject {
                        put("cwd", cwd)
                        put("model", model)
                    },
                ),
            )
        },
    )

    private suspend fun cbdpCreateThread(cwd: String, model: String): CreateThreadResponse {
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
        inputParts: List<Map<String, Any>> = emptyList(),
        mode: String = "start",
    ): SendResponse = withTransport(
        cbdpCall = {
            if (mode == "steer" || inputParts.isNotEmpty()) {
                postCompanionMessage(threadId, text, model, effort, inputParts, mode)
            } else {
                cbdpSendMessage(threadId, text, model, effort)
            }
        },
        companionCall = { postCompanionMessage(threadId, text, model, effort, inputParts, mode) },
    )

    private suspend fun postCompanionMessage(
        threadId: String,
        text: String,
        model: String,
        effort: String,
        inputParts: List<Map<String, Any>>,
        mode: String = "start",
    ): SendResponse = json.decodeFromString(
        SendResponse.serializer(),
        postText(
            "/api/companion/threads/" + java.net.URLEncoder.encode(threadId, "UTF-8") + "/messages",
            buildJsonObject {
                if (text.isNotBlank()) put("text", text)
                if (model.isNotBlank()) put("model", model)
                if (effort.isNotBlank()) put("effort", effort)
                put("mode", mode)
                if (inputParts.isNotEmpty()) {
                    put("input_parts", buildJsonArray {
                        inputParts.forEach { part ->
                            add(buildJsonObject {
                                part.forEach { (key, value) ->
                                    if (value is String) put(key, value)
                                }
                            })
                        }
                    })
                }
            },
        ),
    )

    /**
     * Upload a finished file through the Companion three-step flow.
     * Chunks are base64 JSON bodies; offsets are absolute and must match the
     * Bridge progress exactly or the call returns UPLOAD_OFFSET_MISMATCH.
     */
    suspend fun uploadAttachment(
        threadId: String,
        kind: String,
        filename: String,
        mimeType: String,
        bytes: ByteArray,
        onProgress: (uploaded: Long) -> Unit = {},
    ): UploadedAttachment {
        require(bytes.isNotEmpty()) { "attachment is empty" }
        require(bytes.size.toLong() <= MAX_ATTACHMENT_BYTES) { "attachment is larger than 32 MiB" }
        val begin = postJson(
            "/api/companion/threads/" + java.net.URLEncoder.encode(threadId, "UTF-8") + "/attachments",
            buildJsonObject {
                put("kind", kind)
                put("byte_size", bytes.size)
                put("filename", filename)
                put("mime_type", mimeType)
            },
        )
        val uploadId = begin["upload_id"]?.jsonPrimitive?.content.orEmpty()
        if (uploadId.isBlank()) throw ApiException("电脑端没有返回 upload_id", "invalid_upload")
        var uploaded = 0L
        for ((offset, length) in attachmentChunkPlan(bytes.size.toLong())) {
            val chunk = bytes.copyOfRange(offset.toInt(), offset.toInt() + length)
            val encoded = android.util.Base64.encodeToString(chunk, android.util.Base64.NO_WRAP)
            val response = postJson(
                "/api/companion/uploads/" + java.net.URLEncoder.encode(uploadId, "UTF-8") + "/chunk",
                buildJsonObject {
                    put("offset", offset)
                    put("data", encoded)
                },
            )
            uploaded = response["upload_offset"]?.jsonPrimitive?.longOrNull ?: (offset + length)
            onProgress(uploaded)
        }
        val end = postJson(
            "/api/companion/uploads/" + java.net.URLEncoder.encode(uploadId, "UTF-8") + "/end",
            buildJsonObject { put("byte_size", bytes.size) },
        )
        val attachmentId = end["attachment_id"]?.jsonPrimitive?.content.orEmpty()
        if (attachmentId.isBlank()) throw ApiException("电脑端没有返回 attachment_id", "invalid_upload")
        return UploadedAttachment(
            attachmentId = attachmentId,
            contentRef = end["content_ref"]?.jsonPrimitive?.content.orEmpty(),
            kind = end["kind"]?.jsonPrimitive?.content ?: kind,
            filename = end["filename"]?.jsonPrimitive?.content ?: filename,
            mimeType = end["mime_type"]?.jsonPrimitive?.content ?: mimeType,
            byteSize = end["byte_size"]?.jsonPrimitive?.longOrNull ?: bytes.size.toLong(),
        )
    }

    suspend fun cancelUpload(uploadId: String) {
        runCatching {
            postJson(
                "/api/companion/uploads/" + java.net.URLEncoder.encode(uploadId, "UTF-8") + "/cancel",
                buildJsonObject { },
            )
        }
    }

    private suspend fun postJson(path: String, payload: JsonObject): JsonObject =
        json.decodeFromString(JsonObject.serializer(), postText(path, payload))

    private suspend fun cbdpSendMessage(
        threadId: String,
        text: String,
        model: String,
        effort: String,
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

    suspend fun queue(threadId: String): QueueResponse = withTransport(
        cbdpCall = {
            CbdpMapper.queueFromCommand(
                execute(CbdpMethod.QUEUE_LIST, listOf(CbdpTlv(CbdpTag.THREAD_REF, CbdpCodec.u32(requireThreadRef(threadId))))),
                threadId,
            )
        },
        companionCall = {
            json.decodeFromString(
                QueueResponse.serializer(),
                command("queue.list", buildJsonObject { put("thread_id", threadId) }),
            )
        },
    )

    suspend fun enqueueMessage(
        threadId: String,
        text: String,
        model: String = "",
        effort: String = "",
    ): SendResponse = withTransport(
        cbdpCall = { cbdpEnqueueMessage(threadId, text, model, effort) },
        companionCall = {
            json.decodeFromString(
                SendResponse.serializer(),
                command(
                    "queue.enqueue",
                    buildJsonObject {
                        put("thread_id", threadId)
                        put("text", text)
                        if (model.isNotBlank()) put("model", model)
                        if (effort.isNotBlank()) put("effort", effort)
                    },
                ),
            )
        },
    )

    private suspend fun cbdpEnqueueMessage(
        threadId: String,
        text: String,
        model: String,
        effort: String,
    ): SendResponse {
        val params = mutableListOf(
            CbdpTlv(CbdpTag.THREAD_REF, CbdpCodec.u32(requireThreadRef(threadId))),
            CbdpTlv(CbdpTag.BODY, text.toByteArray()),
        )
        if (model.isNotBlank()) params += CbdpTlv(CbdpTag.MODEL_NAME, model.toByteArray())
        if (effort.isNotBlank()) params += CbdpTlv(CbdpTag.EFFORT, effort.toByteArray())
        return CbdpMapper.sendResponse(execute(CbdpMethod.QUEUE_ADD, params), threadId)
    }

    suspend fun steerQueue(queueId: String, expectedRevision: Long): SendResponse = withTransport(
        cbdpCall = { cbdpSteerQueue(queueId, expectedRevision) },
        companionCall = {
            json.decodeFromString(
                SendResponse.serializer(),
                command(
                    "queue.steer",
                    buildJsonObject {
                        put("queue_id", queueId)
                        put("expected_revision", expectedRevision)
                    },
                ),
            )
        },
    )

    private suspend fun cbdpSteerQueue(queueId: String, expectedRevision: Long): SendResponse {
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
    ): SendResponse = withTransport(
        cbdpCall = { cbdpEditQueue(queueId, text, expectedRevision, model, effort) },
        companionCall = {
            json.decodeFromString(
                SendResponse.serializer(),
                command(
                    "queue.edit",
                    buildJsonObject {
                        put("queue_id", queueId)
                        put("text", text)
                        put("expected_revision", expectedRevision)
                        if (model.isNotBlank()) put("model", model)
                        if (effort.isNotBlank()) put("effort", effort)
                    },
                ),
            )
        },
    )

    private suspend fun cbdpEditQueue(
        queueId: String,
        text: String,
        expectedRevision: Long,
        model: String,
        effort: String,
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

    suspend fun deleteQueue(queueId: String, expectedRevision: Long): SendResponse = withTransport(
        cbdpCall = { cbdpDeleteQueue(queueId, expectedRevision) },
        companionCall = {
            json.decodeFromString(
                SendResponse.serializer(),
                command(
                    "queue.delete",
                    buildJsonObject {
                        put("queue_id", queueId)
                        put("expected_revision", expectedRevision)
                    },
                ),
            )
        },
    )

    private suspend fun cbdpDeleteQueue(queueId: String, expectedRevision: Long): SendResponse {
        val params = mutableListOf(
            CbdpTlv(CbdpTag.QUEUE_ITEM_REF, CbdpCodec.u32(queueId.toLongOrNull() ?: CbdpMapper.stableRef(queueId))),
        )
        if (expectedRevision > 0) params += CbdpTlv(CbdpTag.EXPECTED_REVISION, CbdpCodec.u64(expectedRevision))
        return CbdpMapper.sendResponse(execute(CbdpMethod.QUEUE_DELETE, params), "")
    }

    /**
     * Thread-level read state has no supported CBDP method in Bridge 2.1.x.
     * The HTTP companion route is the authority and works for every transport.
     */
    suspend fun markThreadRead(threadId: String): SendResponse =
        json.decodeFromString(
            SendResponse.serializer(),
            postText(
                "/api/companion/threads/" + java.net.URLEncoder.encode(threadId, "UTF-8") + "/read",
                buildJsonObject { },
            ),
        )

    suspend fun claimThread(threadId: String, force: Boolean = false): ActionResponse = withTransport(
        cbdpCall = {
            CbdpMapper.actionResponse(ensureCbdp().claimThread(requireThreadRef(threadId), force))
        },
        companionCall = { ActionResponse(ok = true, resolved = true) },
    )

    suspend fun releaseThread(threadId: String, leaseGeneration: Long): ActionResponse = withTransport(
        cbdpCall = {
            CbdpMapper.actionResponse(ensureCbdp().releaseThread(requireThreadRef(threadId), leaseGeneration))
        },
        companionCall = { ActionResponse(ok = true, resolved = true) },
    )

    suspend fun interrupt(threadId: String, turnId: String = ""): SendResponse = withTransport(
        cbdpCall = {
            val params = mutableListOf(CbdpTlv(CbdpTag.THREAD_REF, CbdpCodec.u32(requireThreadRef(threadId))))
            turnId.toLongOrNull()?.takeIf { it > 0 }?.let { params += CbdpTlv(CbdpTag.TURN_REF, CbdpCodec.u32(it)) }
            CbdpMapper.sendResponse(execute(CbdpMethod.TURN_INTERRUPT, params), threadId)
        },
        companionCall = {
            json.decodeFromString(
                SendResponse.serializer(),
                postText(
                    "/api/companion/threads/" + java.net.URLEncoder.encode(threadId, "UTF-8") + "/interrupt",
                    buildJsonObject { put("turn_id", turnId) },
                ),
            )
        },
    )

    suspend fun resolveApproval(requestId: Int, decision: String): ActionResponse = withTransport(
        cbdpCall = {
            CbdpMapper.actionResponse(
                ensureCbdp().resolveApproval(
                    approvalRef = requestId.toLong(),
                    decision = decisionValue(decision),
                ),
            )
        },
        companionCall = {
            json.decodeFromString(
                ActionResponse.serializer(),
                postText(
                    "/api/companion/approvals/$requestId",
                    buildJsonObject { put("decision", decision) },
                ),
            )
        },
    )

    /**
     * Approval ids are transport-specific: CBDP hands out a compact
     * ``approval_ref`` and its APPROVAL_RESOLVE frame only carries MCP
     * action/content, while Companion uses the real request id and can pass a
     * full structured response. Never flatten an unsupported payload into a
     * decline, because that silently discards the user's answers.
     */
    suspend fun respondApproval(requestId: Int, response: JsonObject): ActionResponse {
        val action = response["action"]?.jsonPrimitive?.content.orEmpty()
        val content = response["content"]?.toString().orEmpty()
        if (resolveTransport() == Transport.CBDP) {
            if (action.isBlank()) {
                throw ApiException(
                    "当前电脑端暂不支持提交这类审批内容，请更新电脑端",
                    "structured_approval_unsupported",
                )
            }
            val fields = buildList {
                add(CbdpTlv(CbdpTag.MCP_ACTION, action.toByteArray(Charsets.UTF_8)))
                if (content.isNotBlank()) {
                    add(CbdpTlv(CbdpTag.MCP_CONTENT, content.toByteArray(Charsets.UTF_8)))
                }
            }
            return CbdpMapper.actionResponse(
                ensureCbdp().resolveApproval(
                    approvalRef = requestId.toLong(),
                    responseFields = fields,
                ),
            )
        }
        return json.decodeFromString(
            ActionResponse.serializer(),
            postText(
                "/api/companion/approvals/$requestId",
                buildJsonObject { put("response", response) },
            ),
        )
    }

    /**
     * Capabilities come from the live command catalog *and* from a real read.
     * A command that is merely listed can still be rejected by the app-server
     * (for example \`thread/settings/update\` needs experimentalApi), so the
     * UI must probe the read path before offering the approval switch.
     */
    /**
     * Ask the Bridge to enable attachment for this phone. Phones paired before
     * the attachment capability existed default to the older set, so the app
     * negotiates explicitly instead of relying on the pairing-time defaults.
     */
    suspend fun declareSupportedCapabilities(): Set<String> = runCatching {
        parseEnabledCapabilities(
            postJson(
                "/api/companion/capabilities",
                buildJsonObject {
                    put("supported", buildJsonArray {
                        listOf("status", "tasks", "control", "approval", "queue", "attachment")
                            .forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) }
                    })
                },
            )
        )
    }.getOrDefault(emptySet())

    suspend fun bridgeCapabilities(
        threadId: String = "",
        enabledCapabilities: Set<String> = emptySet(),
    ): BridgeCapabilities {
        val listed = runCatching {
            parseCommandCatalog(
                json.decodeFromString(JsonObject.serializer(), getText("/api/companion/protocol")),
            )
        }.getOrElse { BridgeCapabilities() }
        // Attachment is a REST capability, not a command-catalog entry, so it
        // only appears in the /capabilities negotiation result.
        val attachments = listed.attachments ||
            enabledCapabilities.contains("attachment") ||
            enabledCapabilities.contains("file_reference") ||
            enabledCapabilities.contains("image_input")
        val settings = if (!listed.threadSettings || threadId.isBlank()) {
            listed.threadSettings
        } else {
            runCatching { threadSettings(threadId) }.isSuccess
        }
        return listed.copy(threadSettings = settings, attachments = attachments)
    }

    suspend fun threadSettings(threadId: String): ThreadSettings =
        json.decodeFromString(
            ThreadSettings.serializer(),
            command("thread.settings.read", buildJsonObject { put("thread_id", threadId) }),
        )

    suspend fun updateThreadSettings(
        threadId: String,
        model: String = "",
        effort: String = "",
        approvalPolicy: String = "",
        sandboxPolicy: String = "",
        approvalsReviewer: String = "",
    ): ThreadSettings {
        val payload = buildJsonObject {
            put("thread_id", threadId)
            if (model.isNotBlank()) put("model", model)
            if (effort.isNotBlank()) put("effort", effort)
            if (approvalPolicy.isNotBlank()) put("approval_policy", approvalPolicy)
            if (sandboxPolicy.isNotBlank()) put("sandbox_policy", sandboxPolicy)
            if (approvalsReviewer.isNotBlank()) put("approvals_reviewer", approvalsReviewer)
        }
        return requireAppliedThreadSettings(
            json.decodeFromString(
                ThreadSettings.serializer(),
                command("thread.settings.update", payload),
            ),
        )
    }

    private fun decisionValue(decision: String): Int = when (decision) {
        "accept", "approved", "approve" -> CbdpApprovalDecision.ACCEPT
        "acceptForSession", "accept_for_session" -> CbdpApprovalDecision.ACCEPT_FOR_SESSION
        "cancel" -> CbdpApprovalDecision.CANCEL
        else -> CbdpApprovalDecision.DECLINE
    }

    private fun companionEvents(
        threadId: String?,
        sink: (StreamEvent) -> Unit,
        onTerminal: () -> Unit,
    ): EventSource {
        val path = buildString {
            append("/api/companion/events")
            if (!threadId.isNullOrBlank()) {
                append("?thread_id=")
                append(java.net.URLEncoder.encode(threadId, "UTF-8"))
            }
        }
        val request = Request.Builder()
            .url(url(path))
            .header("Accept", "text/event-stream")
            .apply { if (!token.isNullOrBlank()) header("Authorization", "Bearer $token") }
            .build()
        val listener = object : EventSourceListener() {
            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                if (data.isBlank()) return
                decodeStreamEvent(data)?.let { event ->
                    sink(event)
                }
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                sink(StreamEvent(type = "stream_error", message = t?.message ?: "事件流已断开"))
                onTerminal()
            }

            override fun onClosed(eventSource: EventSource) {
                sink(StreamEvent(type = "stream_error", message = "事件流已断开"))
                onTerminal()
            }
        }
        return EventSources.createFactory(client).newEventSource(request, listener)
    }

    fun events(threadId: String? = null, topicId: Long? = null): Flow<StreamEvent> = callbackFlow {
        if (resolveTransport() == Transport.COMPANION) {
            val source = companionEvents(
                threadId = threadId,
                sink = { event -> trySend(event) },
                onTerminal = { close() },
            )
            awaitClose {
                source.cancel()
            }
            return@callbackFlow
        }
        val client = ensureCbdp()
        val activeTopic = topicId?.takeIf { it > 0 }
        val job = launch {
            try {
                client.subscribe(CbdpTopic.SYSTEM_THREAD_SUMMARIES, CbdpScope.SUMMARY)
                if (activeTopic != null) {
                    client.subscribe(activeTopic, CbdpScope.LIVE)
                    client.subscribe(activeTopic, CbdpScope.THREAD)
                    client.subscribe(activeTopic, CbdpScope.SUMMARY)
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
            if (activeTopic != null) {
                cleanupScope.launch {
                    runCatching { client.unsubscribeTopics(listOf(activeTopic)) }
                }
            }
        }
    }

    companion object {
        const val DEFAULT_PORT = 8568
        const val DEFAULT_DISCOVERY_PORT = 8569
        private const val LEGACY_DEFAULT_PORT = 8790
        private const val LEGACY_COMPANION_PORT = 8791
        private const val LEGACY_DISCOVERY_PORT = 8766
        private const val MAX_CONTENT_READ_PAGES = 4096
        private const val MAX_LIST_PAGES = 128

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

internal fun requireAppliedThreadSettings(settings: ThreadSettings): ThreadSettings {
    if (!settings.applied) throw ApiException("电脑端没有应用这次设置变更", "settings_not_applied")
    return settings
}

internal fun parseCommandCatalog(catalog: JsonObject): BridgeCapabilities {
    val methods = catalog["methods"] as? kotlinx.serialization.json.JsonArray ?: return BridgeCapabilities()
    val names = methods.mapNotNull {
        it.jsonObject["method"]?.jsonPrimitive?.content
    }.toSet()
    return BridgeCapabilities(
        threadSettings = "thread.settings.update" in names,
        approvalResponse = "approval.respond" in names,
        attachments = "attachment.upload.begin" in names && "attachment.add" in names,
    )
}

internal fun parseEnabledCapabilities(envelope: JsonObject): Set<String> {
    val items = envelope["enabled"] as? kotlinx.serialization.json.JsonArray ?: return emptySet()
    return items.mapNotNull { item ->
        (item as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
    }.toSet()
}
