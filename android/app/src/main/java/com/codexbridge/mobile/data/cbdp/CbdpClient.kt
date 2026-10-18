package com.codexbridge.mobile.data.cbdp

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import java.io.Closeable
import java.util.Base64
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

class CbdpClientException(
    message: String,
    val code: Int = CbdpErrorCode.INTERNAL,
    val unauthorized: Boolean = code == CbdpErrorCode.UNAUTHORIZED ||
        code == CbdpErrorCode.AUTH_EXPIRED ||
        code == CbdpErrorCode.TOKEN_ROTATED,
) : Exception(message)

class CbdpCommand(
    val requestId: Long,
    val methodId: Int,
    val state: Int,
    val result: List<CbdpTlv>,
    val payload: ByteArray,
    val errorCode: Int,
    val errorMessage: String,
) {
    val ok: Boolean get() = state == CbdpCommandState.COMPLETED && errorCode == CbdpErrorCode.OK
}

class CbdpEventEnvelope(
    val topicId: Long,
    val cursor: Long,
    val eventType: Int,
    val payload: List<CbdpTlv>,
    val scope: Int = CbdpScope.fromEvent(eventType),
    val rawType: String = "",
    val rawPayload: ByteArray = ByteArray(0),
)

class CbdpClient(
    private val baseUrl: String,
    private val deviceId: String,
    private val token: String,
    private val model: String = "android",
    private val firmware: String = "1.0.0",
    private val client: OkHttpClient = defaultHttpClient(),
) : Closeable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val session = CbdpSession(
        deviceId = deviceId,
        model = model,
        firmware = firmware,
        tokenProvider = { token.toByteArray(Charsets.UTF_8) },
    )
    internal constructor(
        baseUrl: String,
        deviceId: String,
        token: String,
        model: String,
        firmware: String,
        client: OkHttpClient,
        maxFrameBytes: Int,
    ) : this(baseUrl, deviceId, token, model, firmware, client) {
        session.overrideMaxFrameBytesForTest(maxFrameBytes)
    }

    private val connectLock = Mutex()
    private val sendLock = Mutex()
    private val requestIds = AtomicLong(0)
    private val pingIds = AtomicLong(0)
    private val fragmentStreamIds = AtomicLong(0)
    private val pending = mutableMapOf<Long, PendingCommand>()
    private val outbound = linkedMapOf<Long, OutboundFrame>()
    internal val fragments = mutableMapOf<Long, FragmentAssembly>()
    private val subscriptions = linkedMapOf<SubscriptionKey, Long>()
    private val cursors = linkedMapOf<SubscriptionKey, Long>()
    @Volatile private var sseCursor: Long = 0
    private val _events = MutableSharedFlow<CbdpEventEnvelope>(extraBufferCapacity = 256)
    val events: SharedFlow<CbdpEventEnvelope> = _events.asSharedFlow()

    @Volatile private var socket: WebSocket? = null
    @Volatile private var sseSource: EventSource? = null
    @Volatile private var sseMode = false
    @Volatile private var closed = false
    private var heartbeatJob: Job? = null

    val maxChunkBytes: Int get() = session.maxChunkBytes()
    val deviceRef: Long get() = session.deviceRef

    private data class PendingCommand(
        val methodId: Int,
        val completion: kotlinx.coroutines.CompletableDeferred<CbdpCommand>,
    )

    private data class OutboundFrame(
        val bytes: ByteArray,
        val frame: CbdpFrame,
        val queuedAt: Long = System.currentTimeMillis(),
        var attempts: Int = 0,
    )

    internal data class FragmentAssembly(
        val total: Int,
        val pieces: MutableMap<Int, ByteArray> = mutableMapOf(),
        var headerTlv: ByteArray? = null,
        var originalType: Int = 0,
        var originalFlags: Int = 0,
        var originalPayloadLength: Int = 0,
        var originalTopicId: Long = 0,
        var originalQos: Int = CbdpQos.CRITICAL,
        var updatedAt: Long = System.currentTimeMillis(),
    ) {
        fun isComplete(): Boolean = pieces.size == total && (0 until total).all { pieces.containsKey(it) }
    }

    internal data class SubscriptionKey(
        val topicId: Long,
        val scope: Int,
    )

    suspend fun connect() = connectLock.withLock {
        if (session.state == CbdpSessionState.READY && (!sseMode || sseSource != null)) return@withLock
        closed = false
        resetConnectionState()
        val wsUrl = toWebSocketUrl(baseUrl) + "/cbdp/v1/ws"
        val request = Request.Builder()
            .url(wsUrl)
            .apply { authHeaders().forEach { (key, value) -> header(key, value) } }
            .build()
        val ws = withContext(Dispatchers.IO) {
            runCatching { client.newWebSocket(request, webSocketListener()) }.getOrNull()
        }
        if (ws != null) {
            socket = ws
            if (runCatching { withTimeout(HANDSHAKE_TIMEOUT_MS) { awaitReady() } }.isSuccess) {
                sseMode = false
                replaySubscriptions()
                startHeartbeat()
                retransmitLoop()
                return@withLock
            }
            runCatching { ws.cancel() }
            socket = null
        }
        connectSse()
        replaySubscriptions()
        startHeartbeat()
        retransmitLoop()
    }

    private suspend fun awaitReady() {
        repeat((HANDSHAKE_TIMEOUT_MS / 20).toInt()) {
            if (session.state == CbdpSessionState.READY) return
            delay(20)
        }
        throw CbdpClientException("handshake timed out")
    }

    private fun webSocketListener(): WebSocketListener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            socket = webSocket
            scope.launch { runCatching { sendRaw(session.start()) }.onFailure(::failConnection) }
        }

        override fun onMessage(webSocket: WebSocket, bytes: okio.ByteString) = receive(bytes.toByteArray())

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(code, reason)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (socket === webSocket) socket = null
            if (!closed) scheduleReconnect()
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (socket === webSocket) socket = null
            if (!closed) scheduleReconnect()
        }
    }

    private suspend fun connectSse() {
        val request = Request.Builder()
            .url(baseUrl.trimEnd('/') + "/cbdp/v1/events")
            .header("Accept", "text/event-stream")
            .apply {
                authHeaders().forEach { (key, value) -> header(key, value) }
                sseCursor.takeIf { it > 0 }?.let { header("Last-Event-ID", it.toString()) }
            }
            .build()
        val listener = object : EventSourceListener() {
            override fun onOpen(eventSource: EventSource, response: Response) {
                scope.launch { runCatching { sendRaw(session.start()) }.onFailure(::failConnection) }
            }

            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                if (type != null && type != "cbdp") return
                if (data.isBlank()) return
                val bytes = runCatching { Base64.getDecoder().decode(data.trim()) }.getOrNull() ?: return
                id?.toLongOrNull()?.takeIf { it > 0 }?.let { cursor ->
                    sseCursor = maxOf(sseCursor, cursor)
                }
                receive(bytes)
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                if (sseSource === eventSource) sseSource = null
                if (!closed) scheduleReconnect()
            }

            override fun onClosed(eventSource: EventSource) {
                if (sseSource === eventSource) sseSource = null
                if (!closed) scheduleReconnect()
            }
        }
        sseMode = true
        val source = EventSources.createFactory(client).newEventSource(request, listener)
        sseSource = source
        withTimeout(HANDSHAKE_TIMEOUT_MS) { awaitReady() }
    }

    private fun receive(bytes: ByteArray) {
        runCatching { CbdpCodec.decodeStream(bytes) }.getOrNull()?.forEach(::receiveFrame)
    }

    private fun receiveFrame(frame: CbdpFrame) {
        if (frame.header.messageType == CbdpMessageType.FRAGMENT) {
            session.acceptFragment(frame)?.forEach { out ->
                scope.launch { runCatching { sendReliable(out) } }
            }
            val reassembled = reassemble(frame) ?: return
            receiveFrame(reassembled)
            return
        }
        val output = runCatching { session.accept(frame) }.getOrElse { return }
        output.forEach { out -> scope.launch { runCatching { sendReliable(out) } } }
        when (frame.header.messageType) {
            CbdpMessageType.RESULT -> resolveCommand(frame)
            CbdpMessageType.ERROR -> resolveError(frame)
            CbdpMessageType.DELTA -> emitDelta(frame)
            CbdpMessageType.SNAPSHOT -> emitSnapshot(frame)
            CbdpMessageType.APPROVAL_REQUEST -> emitApproval(frame)
            CbdpMessageType.CLOSE -> failConnection(CbdpClientException("Bridge closed the session"))
        }
    }

    internal fun reassemble(frame: CbdpFrame): CbdpFrame? {
        val streamId = frame.tlvs.u32(CbdpTag.FRAGMENT_STREAM_ID) ?: return null
        val index = frame.tlvs.u16(CbdpTag.FRAGMENT_INDEX) ?: return null
        val total = frame.tlvs.u16(CbdpTag.FRAGMENT_TOTAL) ?: return null
        if (total <= 0 || total > MAX_FRAGMENTS || index >= total) return null
        val assembly = synchronized(fragments) {
            val existing = fragments[streamId]
            if (existing != null && existing.total != total) {
                fragments.remove(streamId)
                return@synchronized null
            }
            fragments.getOrPut(streamId) { FragmentAssembly(total) }.also {
                it.pieces.putIfAbsent(index, frame.payload)
                it.updatedAt = System.currentTimeMillis()
                if (index == 0) {
                    it.headerTlv = frame.tlvs.bytes(CbdpTag.ORIGINAL_HEADER_TLV)
                        ?: return@synchronized null
                    it.originalType = frame.tlvs.u8(CbdpTag.ORIGINAL_TYPE) ?: 0
                    it.originalFlags = frame.tlvs.u16(CbdpTag.ORIGINAL_FLAGS) ?: 0
                    it.originalPayloadLength = frame.tlvs.u32(CbdpTag.ORIGINAL_PAYLOAD_LEN)?.toInt() ?: 0
                    it.originalTopicId = frame.header.topicId
                    it.originalQos = frame.header.qos
                }
            }
        } ?: return null
        if (!assembly.isComplete()) return null
        synchronized(fragments) { fragments.remove(streamId) }
        val payload = (0 until total).flatMap { assembly.pieces[it]?.asList().orEmpty() }.toByteArray()
        val headerTlv = assembly.headerTlv ?: return null
        val originalTlvs = runCatching { CbdpCodec.decodeTlvs(headerTlv) }.getOrNull() ?: return null
        if (payload.size != assembly.originalPayloadLength || payload.size > MAX_REASSEMBLED_BYTES) return null
        return CbdpFrame(
            CbdpHeader(
                messageType = assembly.originalType,
                flags = assembly.originalFlags,
                payloadLength = payload.size,
                topicId = assembly.originalTopicId,
                seq = frame.header.seq,
                qos = assembly.originalQos,
            ),
            originalTlvs,
            payload,
        )
    }
    private fun resolveCommand(frame: CbdpFrame) {
        val requestId = frame.tlvs.u32(CbdpTag.REQUEST_ID) ?: return
        val methodId = frame.tlvs.u16(CbdpTag.METHOD_ID) ?: 0
        val state = frame.tlvs.u8(CbdpTag.STATE) ?: CbdpCommandState.UNKNOWN
        val result = frame.tlvs.tlv(CbdpTag.RESULT).orEmpty()
        val waiter = synchronized(pending) { pending.remove(requestId) } ?: return
        waiter.completion.complete(
            CbdpCommand(
                requestId = requestId,
                methodId = methodId,
                state = state,
                result = result,
                payload = frame.payload,
                errorCode = frame.header.code,
                errorMessage = frame.tlvs.utf8(CbdpTag.ERROR_MESSAGE).orEmpty(),
            ),
        )
    }

    private fun resolveError(frame: CbdpFrame) {
        val requestId = frame.tlvs.u32(CbdpTag.REQUEST_ID)
        val message = frame.tlvs.utf8(CbdpTag.ERROR_MESSAGE).orEmpty()
        if (requestId == null) {
            if (frame.header.code == CbdpErrorCode.RESYNC_REQUIRED ||
                frame.header.code == CbdpErrorCode.STALE_CURSOR
            ) {
                session.markStale()
            }
            return
        }
        val waiter = synchronized(pending) { pending.remove(requestId) } ?: return
        waiter.completion.complete(
            CbdpCommand(
                requestId = requestId,
                methodId = waiter.methodId,
                state = CbdpCommandState.REJECTED,
                result = emptyList(),
                payload = ByteArray(0),
                errorCode = frame.header.code,
                errorMessage = message,
            ),
        )
    }

    private fun emitDelta(frame: CbdpFrame) {
        val cursor = frame.tlvs.u64(CbdpTag.GLOBAL_SEQ)
            ?: frame.tlvs.u64(CbdpTag.CURSOR)
            ?: frame.header.seq
        val eventType = frame.tlvs.u16(CbdpTag.EVENT_TYPE) ?: return
        val payload = frame.tlvs.tlv(CbdpTag.EVENT_PAYLOAD).orEmpty()
        _events.tryEmit(
            CbdpEventEnvelope(
                topicId = frame.header.topicId,
                cursor = cursor,
                eventType = eventType,
                payload = payload,
                scope = CbdpScope.fromEvent(eventType),
            ),
        )
    }

    suspend fun acknowledge(topicId: Long, cursor: Long, scope: Int) {
        if (topicId <= 0 || cursor <= 0) return
        synchronized(cursors) {
            val key = SubscriptionKey(topicId, scope)
            cursors[key] = maxOf(cursors[key] ?: 0L, cursor)
            subscriptions[key] = cursor
        }
        if (session.state != CbdpSessionState.READY) return
        sendReliable(
            session.ack(
                ackSeq = session.bridgeAckPrefix,
                cursor = cursor,
                topicId = topicId,
                scope = scope,
            ),
        )
    }

    private fun emitSnapshot(frame: CbdpFrame) {
        _events.tryEmit(
            CbdpEventEnvelope(
                topicId = frame.header.topicId,
                cursor = frame.tlvs.u64(CbdpTag.CURSOR) ?: 0,
                eventType = frame.tlvs.u16(CbdpTag.EVENT_TYPE) ?: 0,
                payload = frame.tlvs.array(CbdpTag.OBJECTS)
                    .flatMap { element -> element.firstOrNull()?.value?.let(CbdpCodec::decodeTlvs).orEmpty() },
            ),
        )
    }

    private fun emitApproval(frame: CbdpFrame) {
        _events.tryEmit(
            CbdpEventEnvelope(
                topicId = frame.header.topicId,
                cursor = 0,
                eventType = CbdpEvent.APPROVAL_REQUESTED,
                payload = frame.tlvs,
            ),
        )
    }

    private suspend fun sendReliable(frame: CbdpFrame) = sendLock.withLock {
        val encoded = CbdpCodec.encode(frame)
        if (encoded.size <= session.maxFrameBytes) {
            sendPhysical(frame, encoded)
            return@withLock
        }
        val payload = encoded.copyOfRange(frame.header.headerLength, encoded.size)
        val overhead = session.maxFrameBytes - 28
        val chunkSize = (overhead - 64).coerceAtLeast(1)
        if (payload.isEmpty()) {
            throw CbdpClientException("empty payload cannot be fragmented", CbdpErrorCode.FRAGMENT_ERROR)
        }
        val parts = payload.asList().chunked(chunkSize).map { it.toByteArray() }
        if (parts.size > MAX_FRAGMENTS) throw CbdpClientException("too many fragments", CbdpErrorCode.FRAGMENT_ERROR)
        val streamId = fragmentStreamIds.incrementAndGet().toInt()
        parts.forEachIndexed { index, part ->
            val fragmentTlvs = mutableListOf(
                CbdpTlv(CbdpTag.FRAGMENT_STREAM_ID, CbdpCodec.u32(streamId.toLong())),
                CbdpTlv(CbdpTag.FRAGMENT_INDEX, CbdpCodec.u16(index)),
                CbdpTlv(CbdpTag.FRAGMENT_TOTAL, CbdpCodec.u16(parts.size)),
                CbdpTlv(CbdpTag.ORIGINAL_TYPE, CbdpCodec.u8(frame.header.messageType)),
                CbdpTlv(CbdpTag.ORIGINAL_FLAGS, CbdpCodec.u16(frame.header.flags)),
                CbdpTlv(CbdpTag.ORIGINAL_PAYLOAD_LEN, CbdpCodec.u32(payload.size.toLong())),
            )
            if (index == 0) {
                fragmentTlvs += CbdpTlv(CbdpTag.ORIGINAL_HEADER_TLV, CbdpCodec.encodeTlvs(frame.tlvs))
            }
            val fragment = CbdpFrame(
                CbdpHeader(
                    messageType = CbdpMessageType.FRAGMENT,
                    flags = if (index < parts.lastIndex) CbdpFlags.MORE_FRAGMENTS else 0,
                    topicId = frame.header.topicId,
                    seq = session.nextSequence(),
                    qos = frame.header.qos,
                ),
                tlvs = fragmentTlvs,
                payload = part,
            )
            val fragmentBytes = CbdpCodec.encode(fragment)
            if (fragmentBytes.size > session.maxFrameBytes) {
                throw CbdpClientException("fragment exceeds max_frame_bytes", CbdpErrorCode.FRAGMENT_ERROR)
            }
            sendPhysical(fragment, fragmentBytes)
        }
    }

    private suspend fun sendRaw(frame: CbdpFrame) = sendReliable(frame)

    private suspend fun sendPhysical(frame: CbdpFrame, bytes: ByteArray) {
        if (frame.header.seq > 0 && frame.header.flags and CbdpFlags.NO_ACK == 0) {
            synchronized(outbound) { outbound[frame.header.seq] = OutboundFrame(bytes, frame) }
        }
        sendEncoded(bytes)
    }


    private suspend fun sendEncoded(bytes: ByteArray): Unit = withContext(Dispatchers.IO) {
        if (sseMode) {
            val request = Request.Builder()
                .url(baseUrl.trimEnd('/') + "/cbdp/v1/frame")
                .post(bytes.toRequestBody("application/octet-stream".toMediaType()))
                .apply { authHeaders().forEach { (key, value) -> header(key, value) } }
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw CbdpClientException("frame POST failed: HTTP ${response.code}", response.code)
                }
            }
        } else {
            val ws = socket ?: throw CbdpClientException("websocket is not connected")
            if (!ws.send(okio.ByteString.of(*bytes))) {
                throw CbdpClientException("websocket send failed")
            }
        }
    }

    suspend fun execute(methodId: Int, params: List<CbdpTlv>, timeoutMs: Long = 15000): CbdpCommand {
        connect()
        return executeConnected(methodId, CbdpCodec.encodeTlvs(params), timeoutMs)
    }

    private suspend fun executeConnected(methodId: Int, params: ByteArray, timeoutMs: Long): CbdpCommand {
        val requestId = requestIds.incrementAndGet()
        val canonical = canonicalize(params)
        val frame = buildCommandFrame(requestId, methodId, canonical)
        val waiter = PendingCommand(methodId, kotlinx.coroutines.CompletableDeferred())
        synchronized(pending) { pending[requestId] = waiter }
        try {
            sendReliable(frame)
            return withTimeout(timeoutMs) { waiter.completion.await() }
        } catch (error: Throwable) {
            synchronized(pending) { pending.remove(requestId) }
            throw error
        }
    }

    suspend fun contentRead(
        contentRef: String,
        offset: Long = 0,
        maxBytes: Int = 0,
        contentKind: Int = 0,
        timeoutMs: Long = 20000,
    ): CbdpCommand {
        connect()
        val params = mutableListOf<CbdpTlv>()
        params += CbdpTlv(CbdpTag.CONTENT_REF, contentRef.toByteArray(Charsets.UTF_8))
        params += CbdpTlv(CbdpTag.OFFSET, CbdpCodec.u32(offset))
        if (maxBytes > 0) params += CbdpTlv(CbdpTag.MAX_BYTES, CbdpCodec.u32(maxBytes.toLong()))
        if (contentKind > 0) params += CbdpTlv(CbdpTag.CONTENT_KIND, CbdpCodec.u8(contentKind))
        return executeConnected(CbdpMethod.CONTENT_READ, CbdpCodec.encodeTlvs(params), timeoutMs)
    }

    suspend fun subscribe(topicId: Long, scope: Int = CbdpScope.SUMMARY, cursor: Long = 0) {
        connect()
        val requestId = requestIds.incrementAndGet()
        val frame = session.subscribe(topicId, scope, cursor, requestId)
        synchronized(subscriptions) { subscriptions[SubscriptionKey(topicId, scope)] = cursor }
        sendReliable(frame)
    }

    suspend fun unsubscribe(topicId: Long) {
        val scopes = synchronized(subscriptions) {
            val matches = subscriptions.filterKeys { it.topicId == topicId }
            matches.keys.forEach(subscriptions::remove)
            matches.keys.map { it.scope }.distinct()
        }
        if (scopes.isEmpty()) return
        if (session.state != CbdpSessionState.READY) return
        scopes.forEach { scope ->
            val requestId = requestIds.incrementAndGet()
            sendReliable(session.unsubscribe(topicId, scope, requestId))
        }
    }

    suspend fun unsubscribeTopics(topicIds: Collection<Long>) {
        topicIds.forEach { unsubscribe(it) }
    }

    suspend fun claimThread(threadRef: Long, force: Boolean = false): CbdpCommand {
        val params = mutableListOf(CbdpTlv(CbdpTag.THREAD_REF, CbdpCodec.u32(threadRef)))
        if (force) params += CbdpTlv(CbdpTag.FORCE, CbdpCodec.u8(1))
        return execute(CbdpMethod.THREAD_CLAIM, params)
    }

    suspend fun releaseThread(threadRef: Long, leaseGeneration: Long): CbdpCommand {
        val params = mutableListOf(
            CbdpTlv(CbdpTag.THREAD_REF, CbdpCodec.u32(threadRef)),
            CbdpTlv(CbdpTag.LEASE_GENERATION, CbdpCodec.u32(leaseGeneration)),
        )
        return execute(CbdpMethod.THREAD_RELEASE, params)
    }

    suspend fun activeState(): CbdpCommand = execute(CbdpMethod.ACTIVE_STATE_READ, emptyList())

    suspend fun sendText(
        txId: Long,
        text: String,
        threadRef: Long = 0,
        model: String = "",
        effort: String = "",
    ): CbdpCommand {
        connect()
        val bytes = text.toByteArray(Charsets.UTF_8)
        val begin = CbdpFrame(
            CbdpHeader(CbdpMessageType.SEND_BEGIN, CbdpFlags.REQUEST, seq = session.nextSequence(), qos = CbdpQos.CRITICAL),
            tlvs = buildList {
                add(CbdpTlv(CbdpTag.TX_ID, CbdpCodec.u32(txId)))
                add(CbdpTlv(CbdpTag.IDEM, CbdpSession.deriveIdem(deviceId, txId, CbdpMethod.TURN_START, bytes)))
                add(CbdpTlv(CbdpTag.MODE, CbdpCodec.u8(0)))
                if (threadRef > 0) add(CbdpTlv(CbdpTag.THREAD_REF, CbdpCodec.u32(threadRef)))
                if (model.isNotBlank()) add(CbdpTlv(CbdpTag.MODEL_NAME, model.toByteArray()))
                if (effort.isNotBlank()) add(CbdpTlv(CbdpTag.EFFORT, effort.toByteArray()))
                add(CbdpTlv(CbdpTag.TOTAL_BYTES, CbdpCodec.u32(bytes.size.toLong())))
                add(CbdpTlv(CbdpTag.REQUEST_ID, CbdpCodec.u32(txId)))
            },
        )
        val waiter = PendingCommand(CbdpMethod.TURN_START, kotlinx.coroutines.CompletableDeferred())
        synchronized(pending) { pending[txId] = waiter }
        sendReliable(begin)
        var offset = 0
        while (offset < bytes.size) {
            val next = minOf(offset + session.maxChunkBytes, bytes.size)
            val chunk = CbdpFrame(
                CbdpHeader(CbdpMessageType.SEND_CHUNK, CbdpFlags.REQUEST, seq = session.nextSequence(), qos = CbdpQos.CRITICAL),
                tlvs = listOf(
                    CbdpTlv(CbdpTag.TX_ID, CbdpCodec.u32(txId)),
                    CbdpTlv(CbdpTag.OFFSET, CbdpCodec.u32(offset.toLong())),
                ),
                payload = bytes.copyOfRange(offset, next),
            )
            sendReliable(chunk)
            offset = next
        }
        val end = CbdpFrame(
            CbdpHeader(CbdpMessageType.SEND_END, CbdpFlags.REQUEST, seq = session.nextSequence(), qos = CbdpQos.CRITICAL),
            tlvs = listOf(
                CbdpTlv(CbdpTag.TX_ID, CbdpCodec.u32(txId)),
                CbdpTlv(CbdpTag.TOTAL_BYTES, CbdpCodec.u32(bytes.size.toLong())),
                CbdpTlv(CbdpTag.REQUEST_ID, CbdpCodec.u32(txId)),
            ),
        )
        sendReliable(end)
        return withTimeout(30000) { waiter.completion.await() }
    }

    private fun buildCommandFrame(requestId: Long, methodId: Int, params: ByteArray): CbdpFrame =
        CbdpFrame(
            CbdpHeader(
                messageType = CbdpMessageType.COMMAND,
                flags = CbdpFlags.REQUEST or CbdpFlags.COMMAND,
                seq = session.nextSequence(),
                qos = CbdpQos.STATE,
            ),
            tlvs = listOf(
                CbdpTlv(CbdpTag.REQUEST_ID, CbdpCodec.u32(requestId)),
                CbdpTlv(CbdpTag.IDEM, CbdpSession.deriveIdem(deviceId, requestId, methodId, params)),
                CbdpTlv(CbdpTag.METHOD_ID, CbdpCodec.u16(methodId)),
                CbdpTlv(CbdpTag.PARAMS, params),
            ),
        )

    private fun canonicalize(params: ByteArray): ByteArray =
        CbdpCodec.encodeTlvs(
            CbdpCodec.decodeTlvs(params).sortedWith(
                compareBy<CbdpTlv>({ it.tag and 0xFFFF }, { it.value.contentHashCode() }),
            ),
        )

    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (!closed && session.state == CbdpSessionState.READY) {
                delay(session.heartbeatSeconds.coerceAtLeast(5) * 1000L)
                runCatching { sendRaw(session.ping(pingIds.incrementAndGet())) }
            }
        }
    }

    private fun retransmitLoop() {
        scope.launch {
            while (!closed) {
                delay(500)
                val now = System.currentTimeMillis()
                val due = synchronized(outbound) {
                    outbound.values.filter {
                        !session.isAcknowledged(it.frame.header.seq) &&
                            now - it.queuedAt >= (RETRANSMIT_TIMEOUT_MS shl it.attempts.coerceAtMost(3))
                                .coerceAtMost(MAX_RETRANSMIT_MS)
                    }.toList()
                }
                due.forEach { entry ->
                    if (entry.attempts >= MAX_RETRIES) {
                        session.markStale()
                        synchronized(outbound) { outbound.remove(entry.frame.header.seq) }
                    } else {
                        entry.attempts += 1
                        runCatching { sendEncoded(entry.bytes) }
                    }
                }
                synchronized(outbound) {
                    outbound.entries.removeIf { session.isAcknowledged(it.key) }
                }
                synchronized(fragments) {
                    fragments.entries.removeIf { now - it.value.updatedAt > FRAGMENT_TIMEOUT_MS }
                }
            }
        }
    }

    private suspend fun replaySubscriptions() {
        val registrations = synchronized(subscriptions) { subscriptions.toList() }
        registrations.forEach { (registration, cursor) ->
            val topicId = registration.topicId
            val scope = registration.scope
            val requestId = requestIds.incrementAndGet()
            val latest = synchronized(cursors) { cursors[registration] ?: cursor }
            sendReliable(session.subscribe(topicId, scope, latest, requestId))
        }
    }

    private fun resetConnectionState() {
        session.reset()
        synchronized(outbound) { outbound.clear() }
        synchronized(fragments) { fragments.clear() }
        heartbeatJob?.cancel()
    }

    private fun scheduleReconnect() {
        if (closed) return
        scope.launch {
            delay(RECONNECT_DELAY_MS)
            runCatching { connect() }
        }
    }

    private fun failConnection(error: Throwable) {
        session.markDisconnected()
        val snapshot = synchronized(pending) {
            val values = pending.values.toList()
            pending.clear()
            values
        }
        snapshot.forEach { it.completion.completeExceptionally(error) }
        synchronized(outbound) { outbound.clear() }
        if (!closed) scheduleReconnect()
    }

    override fun close() {
        closed = true
        heartbeatJob?.cancel()
        socket?.close(1000, "bye")
        socket = null
        sseSource?.cancel()
        sseSource = null
        synchronized(pending) { pending.clear() }
        synchronized(outbound) { outbound.clear() }
    }

    private fun authHeaders(): Map<String, String> {
        if (token.isBlank()) return emptyMap()
        return mapOf(
            "Authorization" to "Bearer $token",
            "X-CBDP-Token" to token,
            "X-CBDP-Device-Id" to deviceId,
        )
    }

    private fun List<CbdpTlv>.tlvOrNull(): List<CbdpTlv>? =
        firstOrNull()?.value?.let { CbdpCodec.decodeTlvs(it) }

    private fun CbdpFrame.toJsonAdapter(): Unit = Unit

    companion object {
        private const val HANDSHAKE_TIMEOUT_MS = 12_000L
        private const val RECONNECT_DELAY_MS = 1_200L
        private const val RETRANSMIT_TIMEOUT_MS = 1_000L
        private const val MAX_RETRANSMIT_MS = 8_000L
        private const val MAX_RETRIES = 6
        private const val MAX_FRAGMENTS = 512
        private const val FRAGMENT_TIMEOUT_MS = 30_000L
        private const val MAX_REASSEMBLED_BYTES = 16 * 1024 * 1024

        private fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()

        private fun toWebSocketUrl(baseUrl: String): String {
            val trimmed = baseUrl.trimEnd('/')
            return when {
                trimmed.startsWith("https://") -> "wss://" + trimmed.removePrefix("https://")
                trimmed.startsWith("http://") -> "ws://" + trimmed.removePrefix("http://")
                else -> "ws://$trimmed"
            }
        }
    }
}
