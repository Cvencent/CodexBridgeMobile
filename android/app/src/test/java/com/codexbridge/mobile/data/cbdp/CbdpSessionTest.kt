package com.codexbridge.mobile.data.cbdp

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CbdpSessionTest {
    private val deviceId = "android-test"
    private val token = "0123456789abcdef0123456789abcdef".toByteArray()

    @Test
    fun handshakeBuildsHelloAuthenticatesWelcomeAndReachesReady() {
        val session = CbdpSession(
            deviceId = deviceId,
            model = "Pixel",
            firmware = "0.3.7",
            tokenProvider = { token },
            clientNonce = ByteArray(16) { 0x22 },
            requestIdProvider = sequenceOf(1L, 2L).iterator()::next,
        )

        val hello = session.start()
        assertEquals(CbdpMessageType.HELLO, hello.header.messageType)
        assertEquals(CbdpFlags.REQUEST, hello.header.flags and CbdpFlags.REQUEST)
        assertEquals(deviceId, hello.tlvs.utf8(CbdpTag.DEVICE_ID))
        assertEquals(CbdpProfile.FULL, hello.tlvs.u8(CbdpTag.PROFILE))
        assertEquals(CbdpSessionState.HELLO_SENT, session.state)

        val auth = session.accept(welcomeFrame()).single()
        assertEquals(CbdpMessageType.AUTH, auth.header.messageType)
        assertEquals(CbdpFlags.REQUEST, auth.header.flags and CbdpFlags.REQUEST)
        assertEquals(1, auth.tlvs.u8(CbdpTag.AUTH_MODE))
        assertEquals(CbdpSessionState.AUTH_SENT, session.state)
        assertArrayEquals(
            CbdpAuth.hmacProof(token, ByteArray(16) { 0x11 }, ByteArray(16) { 0x22 }, ByteArray(16) { 0x33 }, deviceId),
            auth.tlvs.bytes(CbdpTag.AUTH_PROOF),
        )

        session.accept(authOkFrame())
        assertEquals(CbdpSessionState.READY, session.state)
        assertEquals(0x3F.toLong(), session.effectiveCaps)
        assertEquals(7L, session.deviceRef)
    }

    @Test
    fun rejectsWelcomeBeforeHello() {
        val session = newSession()
        assertThrows(CbdpSessionException::class.java) {
            session.accept(CbdpFrame(header = CbdpHeader(messageType = CbdpMessageType.WELCOME)))
        }
    }

    @Test
    fun rejectsAuthModeThatWasNotAdvertised() {
        val session = newSession()
        session.start()
        assertThrows(CbdpSessionException::class.java) {
            session.accept(welcomeFrame(authMode = 2))
        }
    }

    @Test
    fun respondsToPingWithMatchingEcho() {
        val session = newSession()
        helloToReady(session)

        val output = session.accept(
            CbdpFrame(
                header = CbdpHeader(messageType = CbdpMessageType.PING, qos = CbdpQos.TELEMETRY),
                tlvs = listOf(CbdpTlv(CbdpTag.PING_ID, CbdpCodec.u32(42))),
            ),
        )

        assertEquals(CbdpMessageType.PONG, output.single().header.messageType)
        assertEquals(42L, output.single().tlvs.u32(CbdpTag.ECHO))
    }

    @Test
    fun readySessionBuildsSubscribeFrame() {
        val session = newSession()
        helloToReady(session)

        val frame = session.subscribe(0x50000003, CbdpScope.SUMMARY, cursor = 0)

        assertEquals(CbdpMessageType.SUBSCRIBE, frame.header.messageType)
        assertEquals(CbdpFlags.REQUEST, frame.header.flags and CbdpFlags.REQUEST)
        assertEquals(0x50000003L, frame.tlvs.u32(CbdpTag.TOPIC_ID))
        assertEquals(CbdpScope.SUMMARY, frame.tlvs.u8(CbdpTag.SCOPE))
    }

    @Test
    fun readySessionBuildsCommandWithIdempotencyAndParams() {
        val session = newSession()
        helloToReady(session)
        val params = CbdpCodec.encodeTlvs(listOf(CbdpTlv(CbdpTag.THREAD_ID, "t1".toByteArray())))

        val frame = session.command(CbdpMethod.THREADS_LIST, params)

        assertEquals(CbdpMessageType.COMMAND, frame.header.messageType)
        assertEquals(
            CbdpFlags.REQUEST or CbdpFlags.COMMAND,
            frame.header.flags and (CbdpFlags.REQUEST or CbdpFlags.COMMAND),
        )
        assertEquals(CbdpMethod.THREADS_LIST, frame.tlvs.u16(CbdpTag.METHOD_ID))
        assertTrue(frame.tlvs.bytes(CbdpTag.IDEM)?.size == 16)
        assertEquals("t1", frame.tlvs.tlv(CbdpTag.PARAMS)?.utf8(CbdpTag.THREAD_ID))
    }

    private fun newSession(deviceSeqStart: Long = 0) = CbdpSession(
        deviceId = deviceId,
        model = "Pixel",
        firmware = "0.3.7",
        tokenProvider = { token },
        clientNonce = ByteArray(16) { 0x22 },
        initialDeviceSeq = deviceSeqStart,
    )

    private fun helloToReady(session: CbdpSession) {
        session.start()
        session.accept(welcomeFrame())
        session.accept(authOkFrame())
    }

    private fun authOkFrame() = CbdpFrame(
        header = CbdpHeader(messageType = CbdpMessageType.AUTH_OK, flags = CbdpFlags.RESPONSE),
        tlvs = listOf(
            CbdpTlv(CbdpTag.DEVICE_ID, deviceId.toByteArray()),
            CbdpTlv(CbdpTag.EFFECTIVE_CAPS, CbdpCodec.u64(0x3F)),
            CbdpTlv(CbdpTag.POLICY_VERSION, CbdpCodec.u32(1)),
            CbdpTlv(CbdpTag.DEVICE_REF, CbdpCodec.u32(7)),
            CbdpTlv(CbdpTag.REQUEST_ID, CbdpCodec.u32(2)),
        ),
    )

    private fun welcomeFrame(authMode: Int = 1) = CbdpFrame(
        header = CbdpHeader(messageType = CbdpMessageType.WELCOME, flags = CbdpFlags.RESPONSE),
        tlvs = listOf(
            CbdpTlv(CbdpTag.VERSION_MINOR, CbdpCodec.u16(0)),
            CbdpTlv(CbdpTag.SESSION_ID, ByteArray(16) { 0x11 }),
            CbdpTlv(CbdpTag.SERVER_NONCE, ByteArray(16) { 0x33 }),
            CbdpTlv(CbdpTag.AUTH_MODE, CbdpCodec.u8(authMode)),
            CbdpTlv(CbdpTag.MAX_FRAME_BYTES, CbdpCodec.u32(65536)),
            CbdpTlv(CbdpTag.MAX_TEXT_CHARS, CbdpCodec.u32(100000)),
            CbdpTlv(CbdpTag.MAX_TEXT_BYTES, CbdpCodec.u32(100000)),
            CbdpTlv(CbdpTag.QUEUE_DEPTH, CbdpCodec.u16(256)),
            CbdpTlv(CbdpTag.HEARTBEAT_SECONDS, CbdpCodec.u16(10)),
            CbdpTlv(CbdpTag.MAX_CHUNK_BYTES, CbdpCodec.u16(16384)),
            CbdpTlv(CbdpTag.WRITER_LEASE_SECONDS, CbdpCodec.u32(300)),
            CbdpTlv(CbdpTag.REQUEST_ID, CbdpCodec.u32(1)),
            CbdpTlv(CbdpTag.SERVER_TIME_MS, CbdpCodec.u64(1234)),
        ),
    )

    @Test
    fun inboundAndOutboundSequenceCountersAreIndependent() {
        val session = newSession()
        helloToReady(session)
        assertEquals(3L, session.nextSequence())
        session.accept(deltaFrame(seq = 1, cursor = 11))
        assertEquals(3L, session.deviceSeq)
        assertEquals(1L, session.bridgeSeq)
        assertEquals(1L, session.bridgeAckPrefix)
        assertEquals(4L, session.nextSequence())
    }

    @Test
    fun cumulativeAckOnlyConfirmsContiguousPrefixAndDuplicateResendsAck() {
        val session = newSession()
        helloToReady(session)
        session.nextSequence()
        session.nextSequence()
        session.nextSequence()
        session.acceptPeerAck(2)
        assertTrue(session.isAcknowledged(1))
        assertTrue(session.isAcknowledged(2))
        assertTrue(!session.isAcknowledged(3))

        session.accept(deltaFrame(seq = 1, cursor = 1))
        val duplicateAck = session.accept(deltaFrame(seq = 1, cursor = 1)).single()
        assertEquals(CbdpMessageType.ACK, duplicateAck.header.messageType)
        assertEquals(1L, duplicateAck.header.ackSeq)

        val gapAck = session.accept(deltaFrame(seq = 3, cursor = 3)).single()
        assertEquals(CbdpMessageType.RESYNC, gapAck.header.messageType)
        assertEquals(1L, session.bridgeAckPrefix)
    }

    @Test
    fun sequenceWrapsFromMaxToFirstAckableValue() {
        val session = newSession(deviceSeqStart = 0xFFFF_FFFEL)
        assertEquals(0xFFFF_FFFFL, session.nextSequence())
        assertEquals(1L, session.nextSequence())
        assertEquals(2L, session.nextSequence())
    }

    @Test
    fun fragmentSequenceIsAckedOnceWithCumulativePrefix() {
        val session = newSession()
        helloToReady(session)

        val first = session.accept(fragmentFrame(seq = 1)).single()
        assertEquals(CbdpMessageType.ACK, first.header.messageType)
        assertEquals(1L, first.header.ackSeq)

        val duplicate = session.accept(fragmentFrame(seq = 1)).single()
        assertEquals(CbdpMessageType.ACK, duplicate.header.messageType)
        assertEquals(1L, duplicate.header.ackSeq)

        val gap = session.accept(fragmentFrame(seq = 3)).single()
        assertEquals(CbdpMessageType.RESYNC, gap.header.messageType)
        assertEquals(1L, session.bridgeAckPrefix)
    }

    @Test
    fun inboundSequenceWrapsFromMaxToFirstAckableValue() {
        val session = newSession()
        helloToReady(session)
        session.overrideBridgeSeqForTest(0xFFFF_FFFFL)
        session.accept(deltaFrame(seq = 1, cursor = 2))
        assertEquals(1L, session.bridgeAckPrefix)
    }

    @Test
    fun resetRotatesClientNonce() {
        val session = newSession()
        val first = session.clientNonceBytes.copyOf()
        session.start()
        session.reset()
        assertArrayEquals(first, first)
        assertTrue(!first.contentEquals(session.clientNonceBytes))
    }

    @Test
    fun rejectsCompressedFrames() {
        val session = newSession()
        helloToReady(session)
        assertThrows(CbdpSessionException::class.java) {
            session.accept(
                CbdpFrame(
                    header = CbdpHeader(
                        messageType = CbdpMessageType.DELTA,
                        flags = CbdpFlags.COMPRESSED,
                        seq = 1,
                    ),
                ),
            )
        }
    }

    private fun deltaFrame(seq: Long, cursor: Long) = CbdpFrame(
        header = CbdpHeader(messageType = CbdpMessageType.DELTA, seq = seq, topicId = CbdpTopic.SYSTEM_THREAD_SUMMARIES),
        tlvs = listOf(
            CbdpTlv(CbdpTag.EVENT_TYPE, CbdpCodec.u16(CbdpEvent.THREAD_UPDATED)),
            CbdpTlv(CbdpTag.GLOBAL_SEQ, CbdpCodec.u64(cursor)),
        ),
    )

    private fun fragmentFrame(seq: Long) = CbdpFrame(
        header = CbdpHeader(
            messageType = CbdpMessageType.FRAGMENT,
            seq = seq,
            topicId = CbdpTopic.SYSTEM_THREAD_SUMMARIES,
        ),
        tlvs = listOf(
            CbdpTlv(CbdpTag.FRAGMENT_STREAM_ID, CbdpCodec.u32(1)),
            CbdpTlv(CbdpTag.FRAGMENT_INDEX, CbdpCodec.u16(0)),
            CbdpTlv(CbdpTag.FRAGMENT_TOTAL, CbdpCodec.u16(1)),
            CbdpTlv(CbdpTag.ORIGINAL_HEADER_TLV, ByteArray(0)),
        ),
    )

}
