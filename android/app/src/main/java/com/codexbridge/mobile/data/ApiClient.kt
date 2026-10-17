package com.codexbridge.mobile.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
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
            if (!response.isSuccessful) {
                throw parseError(response, text)
            }
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

    suspend fun status(): StatusResponse =
        json.decodeFromString(StatusResponse.serializer(), getText("/api/companion/status"))

    suspend fun threads(search: String = ""): ThreadsResponse {
        val query = if (search.isBlank()) "" else "?search=" + java.net.URLEncoder.encode(search, "UTF-8")
        return json.decodeFromString(ThreadsResponse.serializer(), getText("/api/companion/threads$query"))
    }

    suspend fun thread(threadId: String): ThreadDetailResponse =
        json.decodeFromString(
            ThreadDetailResponse.serializer(),
            getText("/api/companion/threads/" + java.net.URLEncoder.encode(threadId, "UTF-8")),
        )

    suspend fun models(): ModelsResponse =
        json.decodeFromString(ModelsResponse.serializer(), getText("/api/companion/models"))

    suspend fun projects(): ProjectsResponse =
        json.decodeFromString(ProjectsResponse.serializer(), getText("/api/companion/projects"))

    suspend fun createThread(cwd: String, model: String): CreateThreadResponse =
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

    suspend fun sendMessage(
        threadId: String,
        text: String,
        model: String = "",
        effort: String = "",
        steer: Boolean = false,
    ): SendResponse =
        json.decodeFromString(
            SendResponse.serializer(),
            postText(
                "/api/companion/threads/" + java.net.URLEncoder.encode(threadId, "UTF-8") + "/messages",
                buildJsonObject {
                    put("text", text)
                    put("model", model)
                    put("effort", effort)
                    put("mode", if (steer) "steer" else "start")
                },
            ),
        )

    suspend fun queue(threadId: String): QueueResponse =
        json.decodeFromString(
            QueueResponse.serializer(),
            command(
                "queue.list",
                buildJsonObject { put("thread_id", threadId) },
            ),
        )

    suspend fun enqueueMessage(
        threadId: String,
        text: String,
        model: String = "",
        effort: String = "",
    ): SendResponse =
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

    suspend fun steerQueue(queueId: String, expectedRevision: Long): SendResponse =
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

    suspend fun editQueue(
        queueId: String,
        text: String,
        expectedRevision: Long,
        model: String = "",
        effort: String = "",
    ): SendResponse =
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

    suspend fun deleteQueue(queueId: String, expectedRevision: Long): SendResponse =
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

    suspend fun markThreadRead(threadId: String): SendResponse =
        json.decodeFromString(
            SendResponse.serializer(),
            postText(
                "/api/companion/threads/" + java.net.URLEncoder.encode(threadId, "UTF-8") + "/read",
                buildJsonObject { },
            ),
        )

    suspend fun interrupt(threadId: String, turnId: String = ""): SendResponse =
        json.decodeFromString(
            SendResponse.serializer(),
            postText(
                "/api/companion/threads/" + java.net.URLEncoder.encode(threadId, "UTF-8") + "/interrupt",
                buildJsonObject { put("turn_id", turnId) },
            ),
        )

    suspend fun resolveApproval(requestId: Int, decision: String): ActionResponse =
        json.decodeFromString(
            ActionResponse.serializer(),
            postText(
                "/api/companion/approvals/$requestId",
                buildJsonObject { put("decision", decision) },
            ),
        )

    suspend fun respondApproval(requestId: Int, response: JsonObject): ActionResponse =
        json.decodeFromString(
            ActionResponse.serializer(),
            command(
                "approval.respond",
                buildJsonObject { put("approval_id", requestId); put("response", response) },
            ),
        )

    fun events(threadId: String? = null): Flow<StreamEvent> = callbackFlow {
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
                val parsed = decodeStreamEvent(data)
                if (parsed != null) {
                    trySend(parsed)
                }
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                trySend(StreamEvent(type = "stream_error", message = t?.message ?: "事件流已断开"))
                close()
            }

            override fun onClosed(eventSource: EventSource) {
                close()
            }
        }
        val source = EventSources.createFactory(client).newEventSource(request, listener)
        awaitClose { source.cancel() }
    }

    companion object {
        const val DEFAULT_PORT = 8568
        const val DEFAULT_DISCOVERY_PORT = 8569
        private const val LEGACY_DEFAULT_PORT = 8790
        private const val LEGACY_COMPANION_PORT = 8791
        private const val LEGACY_DISCOVERY_PORT = 8766

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
            if (text.isEmpty()) {
                throw ApiException("请填写电脑地址")
            }
            if (!text.startsWith("http://") && !text.startsWith("https://")) {
                text = "http://$text"
            }
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
            if (!text.startsWith("http://") && !text.startsWith("https://")) {
                text = "http://$text"
            }
            val schemeEnd = text.indexOf("://") + 3
            val rest = text.substring(schemeEnd)
            val hostPart = rest.substringBefore('/')
            val tail = rest.substring(hostPart.length)
            val host = hostPart.substringBeforeLast(':', hostPart)
            return (text.substring(0, schemeEnd) + host + ":$port" + tail).trimEnd('/')
        }
    }
}
