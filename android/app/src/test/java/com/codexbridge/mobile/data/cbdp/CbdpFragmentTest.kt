package com.codexbridge.mobile.data.cbdp

import okhttp3.OkHttpClient
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CbdpFragmentTest {
    @Test
    fun outOfOrderFragmentsReassembleOriginalFrame() {
        val client = testClient()
        val originalTlvs = listOf(
            CbdpTlv(CbdpTag.EVENT_TYPE, CbdpCodec.u16(CbdpEvent.MESSAGE_APPENDED)),
            CbdpTlv(CbdpTag.MESSAGE_REF, CbdpCodec.u32(9)),
        )
        val payload = ByteArray(180) { (it % 251).toByte() }
        val header = CbdpCodec.encodeTlvs(originalTlvs)
        val fragments = listOf(
            fragment(1, 1, 3, payload.copyOfRange(60, 120), CbdpMessageType.DELTA, 0, originalPayloadLen = payload.size.toLong()),
            fragment(1, 0, 3, payload.copyOfRange(0, 60), CbdpMessageType.DELTA, 0, header, originalPayloadLen = payload.size.toLong(), topicId = 0x1000_002A, qos = CbdpQos.TELEMETRY),
            fragment(1, 2, 3, payload.copyOfRange(120, 180), CbdpMessageType.DELTA, 0, originalPayloadLen = payload.size.toLong()),
        )
        assertNull(client.reassemble(fragments[0]))
        assertNull(client.reassemble(fragments[1]))
        val restored = client.reassemble(fragments[2])
        assertNotNull(restored)
        assertEquals(originalTlvs.size, restored!!.tlvs.size)
        assertArrayEquals(payload, restored.payload)
        assertEquals(CbdpMessageType.DELTA, restored.header.messageType)
        assertEquals(0x1000_002AL, restored.header.topicId)
        assertEquals(CbdpQos.TELEMETRY, restored.header.qos)
    }

    @Test
    fun duplicateAndConflictingFragmentsAreHandledSafely() {
        val client = testClient()
        val header = CbdpCodec.encodeTlvs(listOf(CbdpTlv(CbdpTag.EVENT_TYPE, CbdpCodec.u16(1))))
        assertNull(client.reassemble(fragment(7, 0, 2, byteArrayOf(1, 2), 1, 0, header, originalPayloadLen = 3)))
        assertNull(client.reassemble(fragment(7, 0, 2, byteArrayOf(9, 9), 1, 0, header, originalPayloadLen = 3)))
        val restored = client.reassemble(fragment(7, 1, 2, byteArrayOf(3), 1, 0, originalPayloadLen = 3))
        assertNotNull(restored)
        assertArrayEquals(byteArrayOf(1, 2, 3), restored!!.payload)
    }

    @Test
    fun missingOriginalHeaderFailsWholeStream() {
        val client = testClient()
        assertNull(client.reassemble(fragment(11, 0, 1, byteArrayOf(1), 1, 0)))
        val emptyHeader = client.reassemble(fragment(11, 0, 1, byteArrayOf(1), 1, 0, byteArrayOf()))
        assertNotNull(emptyHeader)
        assertTrue(emptyHeader!!.tlvs.isEmpty())
    }

    private fun testClient() = CbdpClient(
        baseUrl = "http://127.0.0.1:1",
        deviceId = "test",
        token = "token",
        model = "android",
        firmware = "test",
        client = OkHttpClient(),
        maxFrameBytes = 4096,
    )

    private fun fragment(
        streamId: Int,
        index: Int,
        total: Int,
        payload: ByteArray,
        originalType: Int,
        originalFlags: Int,
        originalHeader: ByteArray? = null,
        originalPayloadLen: Long = payload.size.toLong(),
        topicId: Long = 0,
        qos: Int = CbdpQos.CRITICAL,
    ): CbdpFrame {
        val tlvs = mutableListOf(
            CbdpTlv(CbdpTag.FRAGMENT_STREAM_ID, CbdpCodec.u32(streamId.toLong())),
            CbdpTlv(CbdpTag.FRAGMENT_INDEX, CbdpCodec.u16(index)),
            CbdpTlv(CbdpTag.FRAGMENT_TOTAL, CbdpCodec.u16(total)),
            CbdpTlv(CbdpTag.ORIGINAL_TYPE, CbdpCodec.u8(originalType)),
            CbdpTlv(CbdpTag.ORIGINAL_FLAGS, CbdpCodec.u16(originalFlags)),
            CbdpTlv(CbdpTag.ORIGINAL_PAYLOAD_LEN, CbdpCodec.u32(originalPayloadLen)),
        )
        if (originalHeader != null) tlvs += CbdpTlv(CbdpTag.ORIGINAL_HEADER_TLV, originalHeader)
        return CbdpFrame(
            CbdpHeader(messageType = CbdpMessageType.FRAGMENT, topicId = topicId, qos = qos),
            tlvs,
            payload,
        )
    }
}
