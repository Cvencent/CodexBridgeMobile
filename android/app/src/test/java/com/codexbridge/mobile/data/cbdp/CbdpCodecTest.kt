package com.codexbridge.mobile.data.cbdp

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CbdpCodecTest {
    private fun hex(value: String): ByteArray =
        value.replace(" ", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test
    fun decodesMinimalPingVector() {
        val frame = CbdpCodec.decode(
            hex(
                "CB 01 01 05 00 00 00 1C 00 00 00 00 " +
                    "00 00 00 00 00 00 00 00 00 00 00 00 00 00 02 00",
            ),
        )

        assertEquals(CbdpMessageType.PING, frame.header.messageType)
        assertEquals(0, frame.header.flags)
        assertEquals(28, frame.header.headerLength)
        assertEquals(0, frame.header.payloadLength)
        assertEquals(0, frame.header.topicId)
        assertEquals(0, frame.header.seq)
        assertEquals(0, frame.header.ackSeq)
        assertEquals(2, frame.header.qos)
        assertTrue(frame.tlvs.isEmpty())
        assertTrue(frame.payload.isEmpty())
    }

    @Test
    fun encodesMinimalPingVector() {
        val encoded = CbdpCodec.encode(
            CbdpFrame(header = CbdpHeader(messageType = CbdpMessageType.PING, qos = CbdpQos.TELEMETRY)),
        )

        assertArrayEquals(
            hex(
                "CB 01 01 05 00 00 00 1C 00 00 00 00 " +
                    "00 00 00 00 00 00 00 00 00 00 00 00 00 00 02 00",
            ),
            encoded,
        )
    }

    @Test
    fun parsesUnknownTlvIgnoringItsValue() {
        val encoded = CbdpCodec.encode(
            CbdpFrame(
                header = CbdpHeader(messageType = CbdpMessageType.PING),
                tlvs = listOf(
                    CbdpTlv(0x1234, byteArrayOf(1, 2, 3)),
                    CbdpTlv(CbdpTag.PING_ID, CbdpCodec.u32(7)),
                ),
            ),
        )

        val frame = CbdpCodec.decode(encoded)

        assertEquals(2, frame.tlvs.size)
        assertEquals(0x1234, frame.tlvs.single { it.tag == 0x1234 }.tag)
        assertEquals(7L, frame.tlvs.u32(CbdpTag.PING_ID))
    }

    @Test
    fun rejectsDuplicateKnownTlv() {
        val encoded = CbdpCodec.encode(
            CbdpFrame(
                header = CbdpHeader(messageType = CbdpMessageType.PING),
                tlvs = listOf(
                    CbdpTlv(CbdpTag.PING_ID, CbdpCodec.u32(1)),
                    CbdpTlv(CbdpTag.PING_ID, CbdpCodec.u32(2)),
                ),
            ),
        )

        val error = assertThrows(CbdpDecodeException::class.java) { CbdpCodec.decode(encoded) }
        assertTrue(error.message.orEmpty().contains("duplicate", ignoreCase = true))
    }

    @Test
    fun rejectsTruncatedTlv() {
        val encoded = hex(
            "CB 01 01 05 00 00 00 20 00 00 00 00 " +
                "00 00 00 00 00 00 00 00 00 00 00 00 00 00 02 00 " +
                "00 97 00 04 00 00",
        )

        assertThrows(CbdpDecodeException::class.java) { CbdpCodec.decode(encoded) }
    }

    @Test
    fun rejectsTrailingBytesAfterDeclaredFrame() {
        val encoded = CbdpCodec.encode(
            CbdpFrame(header = CbdpHeader(messageType = CbdpMessageType.PING)),
        ) + byteArrayOf(0x01)

        assertThrows(CbdpDecodeException::class.java) { CbdpCodec.decode(encoded) }
    }

    @Test
    fun splitsMultipleFramesInOneWebSocketMessage() {
        val ping = CbdpCodec.encode(
            CbdpFrame(header = CbdpHeader(messageType = CbdpMessageType.PING)),
        )
        val pong = CbdpCodec.encode(
            CbdpFrame(header = CbdpHeader(messageType = CbdpMessageType.PONG)),
        )

        val frames = CbdpCodec.decodeStream(ping + pong)

        assertEquals(2, frames.size)
        assertEquals(CbdpMessageType.PING, frames[0].header.messageType)
        assertEquals(CbdpMessageType.PONG, frames[1].header.messageType)
    }

    @Test
    fun derivesHandshakeAliases() {
        val frame = CbdpCodec.decode(
            CbdpCodec.encode(
                CbdpFrame(
                    header = CbdpHeader(
                        messageType = CbdpMessageType.HELLO,
                        flags = CbdpFlags.REQUEST,
                        qos = CbdpQos.CRITICAL,
                    ),
                    tlvs = listOf(
                        CbdpTlv(CbdpTag.VERSION_MINOR, CbdpCodec.u16(0)),
                        CbdpTlv(CbdpTag.DEVICE_ID, "phone-1".toByteArray()),
                        CbdpTlv(CbdpTag.MODEL, "android".toByteArray()),
                        CbdpTlv(CbdpTag.FIRMWARE, "0.3.6".toByteArray()),
                        CbdpTlv(CbdpTag.PROFILE, CbdpCodec.u8(CbdpProfile.FULL)),
                        CbdpTlv(CbdpTag.SUPPORTED_CAPS, CbdpCodec.u64(0x3F)),
                        CbdpTlv(CbdpTag.TRANSPORT_SECURITY, CbdpCodec.u8(CbdpTransportSecurity.PLAIN_LAN)),
                        CbdpTlv(CbdpTag.REQUEST_ID, CbdpCodec.u32(1)),
                    ),
                ),
            ),
        )

        assertEquals("phone-1", frame.tlvs.utf8(CbdpTag.DEVICE_ID))
        assertEquals(CbdpProfile.FULL, frame.tlvs.u8(CbdpTag.PROFILE))
        assertEquals(CbdpFlags.REQUEST, frame.header.flags and CbdpFlags.REQUEST)
    }

    @Test
    fun computesHmacAuthProofFromProtocolVector() {
        val proof = CbdpAuth.hmacProof(
            pairingToken = "0123456789abcdef0123456789abcdef".toByteArray(),
            sessionId = hex("0102030405060708090A0B0C0D0E0F10"),
            clientNonce = ByteArray(16),
            serverNonce = hex("1112131415161718191A1B1C1D1E1F20"),
            deviceId = "esp32-lab",
        )

        assertArrayEquals(
            hex(
                "7D F2 DE C2 FA E6 1A 43 D6 91 00 EA 21 7F 30 06 " +
                    "7E 05 93 7B F1 87 BC E0 2A AF 35 5F 7D C7 4A 92",
            ),
            proof,
        )
    }

    @Test
    fun readsBigEndianUnsignedValues() {
        assertArrayEquals(byteArrayOf(0, 0, 0, 7), CbdpCodec.u32(7))
        assertArrayEquals(byteArrayOf(0x12, 0x34, 0x56, 0x78), CbdpCodec.u32(0x12345678))
        assertEquals(0x12345678L, CbdpCodec.readU32(byteArrayOf(0x12, 0x34, 0x56, 0x78)))
    }
}
