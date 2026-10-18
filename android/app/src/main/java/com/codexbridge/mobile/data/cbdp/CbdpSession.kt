package com.codexbridge.mobile.data.cbdp

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicLong

class CbdpSession(
    private val deviceId: String,
    private val model: String,
    private val firmware: String,
    private val tokenProvider: () -> ByteArray,
    private var clientNonce: ByteArray = randomBytes(16),
    private val requestIdProvider: () -> Long = AtomicLong(0)::incrementAndGet,
    initialDeviceSeq: Long = 0,
) {
    var state: CbdpSessionState = CbdpSessionState.DISCONNECTED
        private set
    var effectiveCaps: Long = 0
        private set
    var deviceRef: Long = 0
        private set
    var maxFrameBytes: Int = 65536
        private set
    var maxTextBytes: Int = 0
        private set
    var maxChunkBytes: Int = 16384
        private set
    var heartbeatSeconds: Int = 10
        private set
    var writerLeaseSeconds: Int = 300
        private set
    var sessionIdBytes: ByteArray = ByteArray(0)
        private set
    var clientNonceBytes: ByteArray = clientNonce
        private set
    var deviceSeq: Long = initialDeviceSeq
        private set
    var bridgeSeq: Long = 0
        private set
    var bridgeAckPrefix: Long = 0
        private set

    private var sessionId: ByteArray = ByteArray(0)
    private var serverNonce: ByteArray = ByteArray(0)
    private var authMode: Int = CbdpAuthMode.HMAC_SHA256
    private var authRequestId: Long = 0
    private val outboundAckPrefix = AtomicLong(0)
    private val acknowledgedByPeer = mutableSetOf<Long>()

    fun start(): CbdpFrame {
        if (state != CbdpSessionState.DISCONNECTED && state != CbdpSessionState.CONNECTED) {
            throw CbdpSessionException("session already started")
        }
        // HELLO is the first ACK-tracked frame in the protocol's transport
        // sequence space. Keeping it at seq=1 also makes reconnects
        // deterministic after the session sequence is reset.
        val frame = buildFrame(
            messageType = CbdpMessageType.HELLO,
            flags = CbdpFlags.REQUEST,
            seq = nextSequence(),
            qos = CbdpQos.CRITICAL,
            tlvs = listOf(
                CbdpTlv(CbdpTag.VERSION_MINOR, CbdpCodec.u16(0)),
                CbdpTlv(CbdpTag.DEVICE_ID, deviceId.toByteArray(Charsets.UTF_8)),
                CbdpTlv(CbdpTag.MODEL, model.toByteArray(Charsets.UTF_8)),
                CbdpTlv(CbdpTag.FIRMWARE, firmware.toByteArray(Charsets.UTF_8)),
                CbdpTlv(CbdpTag.PROFILE, CbdpCodec.u8(CbdpProfile.FULL)),
                CbdpTlv(CbdpTag.SUPPORTED_CAPS, CbdpCodec.u64(CbdpCapabilities.FULL_MOBILE)),
                CbdpTlv(CbdpTag.TRANSPORT_SECURITY, CbdpCodec.u8(CbdpTransportSecurity.PLAIN_LAN)),
                CbdpTlv(CbdpTag.CLIENT_NONCE, clientNonce),
                CbdpTlv(CbdpTag.REQUEST_ID, CbdpCodec.u32(requestIdProvider())),
            ),
        )
        state = CbdpSessionState.HELLO_SENT
        return frame
    }

    fun accept(frame: CbdpFrame): List<CbdpFrame> {
        if (frame.header.flags and CbdpFlags.COMPRESSED != 0) {
            throw CbdpSessionException("compressed frames are unsupported")
        }
        if (frame.header.flags and CbdpFlags.MORE_FRAGMENTS != 0) {
            throw CbdpSessionException("fragmented frames must be reassembled first")
        }
        if (frame.header.messageType == CbdpMessageType.ACK) {
            acceptPeerAck(frame.header.ackSeq)
            return emptyList()
        }
        if (frame.header.messageType == CbdpMessageType.RESYNC) {
            markStale()
            return emptyList()
        }
        if (frame.header.messageType == CbdpMessageType.CAPABILITY_UPDATE) {
            acceptCapabilityUpdate(frame)
            return emptyList()
        }
        if (frame.header.messageType == CbdpMessageType.DEVICE_STATUS) {
            return emptyList()
        }
        if (frame.header.messageType == CbdpMessageType.FRAGMENT) {
            return acceptFragment(frame)
        }
        val tracked = shouldTrackInbound(frame)
        val acks = mutableListOf<CbdpFrame>()
        if (tracked) {
            when (acceptInboundSequence(frame.header.seq)) {
                InboundResult.DUPLICATE -> return listOf(ack(bridgeAckPrefix))
                InboundResult.GAP -> return listOf(resync())
                InboundResult.ACCEPTED -> acks += ack(bridgeAckPrefix)
            }
        }
        val responses = when (frame.header.messageType) {
            CbdpMessageType.WELCOME -> listOf(acceptWelcome(frame))
            CbdpMessageType.AUTH_OK -> {
                acceptAuthOk(frame)
                emptyList()
            }
            CbdpMessageType.PING -> listOf(buildPong(frame))
            CbdpMessageType.PONG,
            CbdpMessageType.DELTA,
            CbdpMessageType.SNAPSHOT,
            CbdpMessageType.RESULT,
            CbdpMessageType.ERROR,
            CbdpMessageType.APPROVAL_REQUEST,
            CbdpMessageType.CLOSE -> emptyList()
            else -> throw CbdpSessionException("unexpected message ${frame.header.messageType}")
        }
        return acks + responses
    }

    fun subscribe(topicId: Long, scope: Int, cursor: Long = 0, requestId: Long = requestIdProvider()): CbdpFrame {
        validateReady()
        return buildFrame(
            messageType = CbdpMessageType.SUBSCRIBE,
            flags = CbdpFlags.REQUEST,
            topicId = topicId,
            seq = nextSequence(),
            qos = CbdpQos.STATE,
            tlvs = listOf(
                CbdpTlv(CbdpTag.TOPIC_ID, CbdpCodec.u32(topicId)),
                CbdpTlv(CbdpTag.SCOPE, CbdpCodec.u8(scope)),
                CbdpTlv(CbdpTag.QOS, CbdpCodec.u8(CbdpQos.STATE)),
                CbdpTlv(CbdpTag.CURSOR, CbdpCodec.u64(cursor)),
                CbdpTlv(CbdpTag.REQUEST_ID, CbdpCodec.u32(requestId)),
            ),
        )
    }

    fun unsubscribe(topicId: Long, scope: Int, requestId: Long = requestIdProvider()): CbdpFrame {
        validateReady()
        return buildFrame(
            messageType = CbdpMessageType.UNSUBSCRIBE,
            flags = CbdpFlags.REQUEST,
            topicId = topicId,
            seq = nextSequence(),
            qos = CbdpQos.STATE,
            tlvs = listOf(
                CbdpTlv(CbdpTag.TOPIC_ID, CbdpCodec.u32(topicId)),
                CbdpTlv(CbdpTag.SCOPE, CbdpCodec.u8(scope)),
                CbdpTlv(CbdpTag.REQUEST_ID, CbdpCodec.u32(requestId)),
            ),
        )
    }

    fun command(methodId: Int, params: ByteArray, qos: Int = CbdpQos.STATE): CbdpFrame {
        validateReady()
        val requestId = requestIdProvider()
        val canonicalParams = canonicalizeParams(params)
        val idem = deriveIdem(deviceId, requestId, methodId, canonicalParams)
        return buildFrame(
            messageType = CbdpMessageType.COMMAND,
            flags = CbdpFlags.REQUEST or CbdpFlags.COMMAND,
            seq = nextSequence(),
            qos = qos,
            tlvs = listOf(
                CbdpTlv(CbdpTag.REQUEST_ID, CbdpCodec.u32(requestId)),
                CbdpTlv(CbdpTag.IDEM, idem),
                CbdpTlv(CbdpTag.METHOD_ID, CbdpCodec.u16(methodId)),
                CbdpTlv(CbdpTag.PARAMS, canonicalParams),
            ),
        )
    }

    fun ack(ackSeq: Long, cursor: Long = 0, topicId: Long = 0, scope: Int = 0): CbdpFrame {
        val tlvs = mutableListOf<CbdpTlv>()
        if (cursor > 0) {
            tlvs += CbdpTlv(CbdpTag.TOPIC_ID, CbdpCodec.u32(topicId))
            tlvs += CbdpTlv(CbdpTag.SCOPE, CbdpCodec.u8(scope))
            tlvs += CbdpTlv(CbdpTag.CURSOR, CbdpCodec.u64(cursor))
        }
        return buildFrame(
            messageType = CbdpMessageType.ACK,
            flags = CbdpFlags.NO_ACK,
            seq = 0,
            ackSeq = ackSeq,
            qos = CbdpQos.CRITICAL,
            tlvs = tlvs,
        )
    }

    fun ping(pingId: Long): CbdpFrame = buildFrame(
        messageType = CbdpMessageType.PING,
        flags = CbdpFlags.NO_ACK,
        seq = 0,
        qos = CbdpQos.TELEMETRY,
        tlvs = listOf(
            CbdpTlv(CbdpTag.PING_ID, CbdpCodec.u32(pingId)),
            CbdpTlv(CbdpTag.SERVER_TIME_MS, CbdpCodec.u64(System.currentTimeMillis())),
        ),
    )

    fun nextSequence(): Long {
        if (deviceSeq >= 0xFFFF_FFFFL) {
            deviceSeq = 1
        } else {
            deviceSeq += 1
        }
        return deviceSeq
    }

    fun acceptPeerAck(ackSeq: Long) {
        if (ackSeq <= outboundAckPrefix.get()) return
        outboundAckPrefix.set(ackSeq)
        synchronized(acknowledgedByPeer) { acknowledgedByPeer.removeIf { it <= ackSeq } }
    }

    fun isAcknowledged(seq: Long): Boolean =
        seq <= outboundAckPrefix.get() || synchronized(acknowledgedByPeer) { seq in acknowledgedByPeer }

    fun ackPrefix(): Long = outboundAckPrefix.get()

    fun inboundAckPrefix(): Long = bridgeAckPrefix

    fun maxChunkBytes(): Int = maxChunkBytes

    internal fun overrideMaxFrameBytesForTest(value: Int) {
        require(value >= 28)
        maxFrameBytes = value
    }

    internal fun overrideBridgeSeqForTest(value: Long) {
        bridgeSeq = value
        bridgeAckPrefix = value
    }

    fun acceptCapabilityUpdate(frame: CbdpFrame) {
        effectiveCaps = frame.tlvs.u64(CbdpTag.EFFECTIVE_CAPS) ?: effectiveCaps
    }

    fun reset() {
        clientNonce = randomBytes(16)
        clientNonceBytes = clientNonce
        state = CbdpSessionState.DISCONNECTED
        effectiveCaps = 0
        deviceRef = 0
        maxFrameBytes = 65536
        maxTextBytes = 0
        maxChunkBytes = 16384
        heartbeatSeconds = 10
        writerLeaseSeconds = 300
        sessionId = ByteArray(0)
        sessionIdBytes = ByteArray(0)
        serverNonce = ByteArray(0)
        authRequestId = 0
        deviceSeq = 0
        bridgeSeq = 0
        bridgeAckPrefix = 0
        outboundAckPrefix.set(0)
        synchronized(acknowledgedByPeer) { acknowledgedByPeer.clear() }
    }

    fun markDisconnected() {
        reset()
    }

    fun markStale() {
        if (state == CbdpSessionState.READY || state == CbdpSessionState.AUTH_SENT) {
            state = CbdpSessionState.STALE
        }
    }

    private enum class InboundResult { ACCEPTED, DUPLICATE, GAP }

    private fun shouldTrackInbound(frame: CbdpFrame): Boolean =
        frame.header.seq > 0 &&
            frame.header.flags and CbdpFlags.NO_ACK == 0 &&
            frame.header.messageType !in setOf(
                CbdpMessageType.PING,
                CbdpMessageType.PONG,
                CbdpMessageType.ACK,
                CbdpMessageType.CLOSE,
                CbdpMessageType.DISCOVERY,
                CbdpMessageType.DISCOVERY_REPLY,
            )

    private fun acceptInboundSequence(seq: Long): InboundResult {
        val expected = if (bridgeSeq == 0xFFFF_FFFFL) 1L else bridgeSeq + 1
        if (seq == expected) {
            bridgeSeq = seq
            bridgeAckPrefix = seq
            return InboundResult.ACCEPTED
        }
        if (seq <= bridgeSeq) return InboundResult.DUPLICATE
        return InboundResult.GAP
    }

    fun acceptFragment(frame: CbdpFrame): List<CbdpFrame> {
        if (frame.header.messageType != CbdpMessageType.FRAGMENT) return emptyList()
        if (frame.header.flags and CbdpFlags.COMPRESSED != 0) {
            throw CbdpSessionException("compressed frames are unsupported")
        }
        if (!shouldTrackInbound(frame)) return emptyList()
        return when (acceptInboundSequence(frame.header.seq)) {
            InboundResult.DUPLICATE -> listOf(ack(bridgeAckPrefix))
            InboundResult.GAP -> listOf(resync())
            InboundResult.ACCEPTED -> listOf(ack(bridgeAckPrefix))
        }
    }

    private fun resync(): CbdpFrame = buildFrame(
        messageType = CbdpMessageType.RESYNC,
        flags = CbdpFlags.REQUEST,
        seq = 0,
        qos = CbdpQos.CRITICAL,
        tlvs = listOf(
            CbdpTlv(CbdpTag.RESYNC_REASON, CbdpCodec.u8(CbdpResyncReason.GAP)),
            CbdpTlv(CbdpTag.REQUEST_ID, CbdpCodec.u32(requestIdProvider())),
        ),
    )

    private fun acceptWelcome(frame: CbdpFrame): CbdpFrame {
        if (state != CbdpSessionState.HELLO_SENT) throw CbdpSessionException("WELCOME before HELLO")
        if (frame.header.flags and CbdpFlags.RESPONSE == 0) {
            throw CbdpSessionException("WELCOME must be a response")
        }
        sessionId = frame.tlvs.bytes(CbdpTag.SESSION_ID)
            ?: throw CbdpSessionException("WELCOME missing session_id")
        sessionIdBytes = sessionId
        serverNonce = frame.tlvs.bytes(CbdpTag.SERVER_NONCE)
            ?: throw CbdpSessionException("WELCOME missing server_nonce")
        authMode = frame.tlvs.u8(CbdpTag.AUTH_MODE)
            ?: throw CbdpSessionException("WELCOME missing auth_mode")
        if (authMode != CbdpAuthMode.HMAC_SHA256 && authMode != CbdpAuthMode.PAIRING_TOKEN) {
            throw CbdpSessionException("unsupported auth mode $authMode")
        }
        maxFrameBytes = frame.tlvs.u32(CbdpTag.MAX_FRAME_BYTES)?.toInt() ?: 65536
        maxTextBytes = frame.tlvs.u32(CbdpTag.MAX_TEXT_BYTES)?.toInt() ?: 0
        maxChunkBytes = frame.tlvs.u16(CbdpTag.MAX_CHUNK_BYTES) ?: 16384
        heartbeatSeconds = frame.tlvs.u16(CbdpTag.HEARTBEAT_SECONDS) ?: 10
        writerLeaseSeconds = frame.tlvs.u32(CbdpTag.WRITER_LEASE_SECONDS)?.toInt() ?: 300
        authRequestId = requestIdProvider()
        val proof = if (authMode == CbdpAuthMode.HMAC_SHA256) {
            CbdpAuth.hmacProof(tokenProvider(), sessionId, clientNonce, serverNonce, deviceId)
        } else {
            CbdpAuth.tokenProof(tokenProvider(), sessionId, clientNonce, serverNonce, deviceId)
        }
        state = CbdpSessionState.AUTH_SENT
        return buildFrame(
            messageType = CbdpMessageType.AUTH,
            flags = CbdpFlags.REQUEST,
            seq = nextSequence(),
            qos = CbdpQos.CRITICAL,
            tlvs = listOf(
                CbdpTlv(CbdpTag.SESSION_ID, sessionId),
                CbdpTlv(CbdpTag.AUTH_PROOF, proof),
                CbdpTlv(CbdpTag.AUTH_MODE, CbdpCodec.u8(authMode)),
                CbdpTlv(CbdpTag.REQUEST_ID, CbdpCodec.u32(authRequestId)),
            ),
        )
    }

    private fun acceptAuthOk(frame: CbdpFrame) {
        if (state != CbdpSessionState.AUTH_SENT) throw CbdpSessionException("AUTH_OK before AUTH")
        val responseId = frame.tlvs.u32(CbdpTag.REQUEST_ID) ?: 0
        if (responseId != 0L && responseId != authRequestId) {
            throw CbdpSessionException("AUTH_OK request_id mismatch")
        }
        val returnedDeviceId = frame.tlvs.utf8(CbdpTag.DEVICE_ID)
            ?: throw CbdpSessionException("AUTH_OK missing device_id")
        if (returnedDeviceId != deviceId) throw CbdpSessionException("AUTH_OK device_id mismatch")
        effectiveCaps = frame.tlvs.u64(CbdpTag.EFFECTIVE_CAPS)
            ?: throw CbdpSessionException("AUTH_OK missing effective_caps")
        deviceRef = frame.tlvs.u32(CbdpTag.DEVICE_REF)
            ?: throw CbdpSessionException("AUTH_OK missing device_ref")
        state = CbdpSessionState.READY
    }

    private fun buildPong(ping: CbdpFrame): CbdpFrame {
        val echo = ping.tlvs.u32(CbdpTag.PING_ID) ?: 0
        return buildFrame(
            messageType = CbdpMessageType.PONG,
            flags = CbdpFlags.NO_ACK,
            seq = 0,
            qos = CbdpQos.TELEMETRY,
            tlvs = listOf(
                CbdpTlv(CbdpTag.ECHO, CbdpCodec.u32(echo)),
                CbdpTlv(CbdpTag.SERVER_TIME_MS, CbdpCodec.u64(System.currentTimeMillis())),
            ),
        )
    }

    private fun validateReady() {
        if (state != CbdpSessionState.READY) throw CbdpSessionException("session is not ready")
    }

    /**
     * 3.1.1 permits HTTP Companion clients to use the Bearer-authenticated
     * transport without a separate CBDP AUTH. Bridge 2.0.4 still answers the
     * AUTH frame, so accept a short AUTH_OK grace period and otherwise treat
     * the already-authenticated HTTP session as ready.
     */
    fun acceptHttpAuthTimeout() {
        if (state == CbdpSessionState.AUTH_SENT) {
            state = CbdpSessionState.READY
        }
    }

    private fun canonicalizeParams(params: ByteArray): ByteArray {
        val decoded = CbdpCodec.decodeTlvs(params)
        val sorted = decoded.sortedWith(
            compareBy<CbdpTlv>({ it.tag and 0xFFFF }, { it.value.contentHashCode() }),
        )
        return CbdpCodec.encodeTlvs(sorted)
    }

    private fun buildFrame(
        messageType: Int,
        flags: Int = 0,
        topicId: Long = 0,
        seq: Long = 0,
        ackSeq: Long = 0,
        code: Int = 0,
        qos: Int = CbdpQos.CRITICAL,
        tlvs: List<CbdpTlv> = emptyList(),
        payload: ByteArray = ByteArray(0),
    ) = CbdpFrame(
        header = CbdpHeader(
            messageType = messageType,
            flags = flags,
            topicId = topicId,
            seq = seq,
            ackSeq = ackSeq,
            code = code,
            qos = qos,
        ),
        tlvs = tlvs,
        payload = payload,
    )

    companion object {
        private val secureRandom = SecureRandom()

        private fun randomBytes(size: Int): ByteArray = ByteArray(size).also(secureRandom::nextBytes)

        fun deriveIdem(
            deviceId: String,
            requestId: Long,
            methodId: Int,
            canonicalParams: ByteArray,
        ): ByteArray {
            val digest = MessageDigest.getInstance("SHA-256")
            digest.update("CBDP1-IDEM".toByteArray(Charsets.US_ASCII))
            digest.update(deviceId.toByteArray(Charsets.UTF_8))
            digest.update(CbdpCodec.u32(requestId))
            digest.update(CbdpCodec.u16(methodId))
            digest.update(canonicalParams)
            return digest.digest().copyOf(16)
        }
    }
}
