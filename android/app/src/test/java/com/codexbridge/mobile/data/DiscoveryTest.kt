package com.codexbridge.mobile.data

import com.codexbridge.mobile.data.cbdp.CbdpCodec
import com.codexbridge.mobile.data.cbdp.CbdpFrame
import com.codexbridge.mobile.data.cbdp.CbdpHeader
import com.codexbridge.mobile.data.cbdp.CbdpMessageType
import com.codexbridge.mobile.data.cbdp.CbdpTag
import com.codexbridge.mobile.data.cbdp.CbdpTlv
import com.codexbridge.mobile.data.cbdp.bytes
import com.codexbridge.mobile.data.cbdp.u16
import com.codexbridge.mobile.data.cbdp.utf8
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiscoveryTest {
    private val nonce = ByteArray(16) { it.toByte() }

    @Test
    fun discoveryRequestCarriesRequiredBinaryFields() {
        val bytes = Discovery.discoveryRequest(nonce, deviceId = "phone-1", port = 8568)
        val frame = CbdpCodec.decode(bytes)
        assertTrue(frame.header.messageType == CbdpMessageType.DISCOVERY)
        assertTrue(frame.tlvs.bytes(CbdpTag.DISCOVERY_NONCE)?.contentEquals(nonce) == true)
        assertTrue(frame.tlvs.u16(CbdpTag.PORT) == 8568)
        assertTrue(frame.tlvs.utf8(CbdpTag.DEVICE_ID) == "phone-1")
        assertTrue(bytes.size <= 1024)
    }

    @Test
    fun matchingReplyRequiresEchoedNonce() {
        val reply = Discovery.discoveryReply(nonce)
        assertTrue(Discovery.isMatchingReplyForTest(reply, nonce))
        assertFalse(Discovery.isMatchingReplyForTest(reply, ByteArray(16) { 0x7F }))
    }

    @Test
    fun legacyJsonDiscoveryReplyIsAccepted() {
        val reply = """{"service":"codexbridge-companion","name":"DESKTOP-TEST","version":"2.1.2","port":8568,"discovery_port":8569,"requires_pairing":true}"""
            .toByteArray(Charsets.UTF_8)
        assertTrue(Discovery.isMatchingReplyForTest(reply, nonce))
    }

    @Test
    fun wrongMessageTypeIsRejected() {
        val frame = CbdpFrame(
            header = CbdpHeader(messageType = CbdpMessageType.DELTA),
            tlvs = listOf(CbdpTlv(CbdpTag.DISCOVERY_NONCE, nonce)),
        )
        assertFalse(Discovery.isMatchingReplyForTest(CbdpCodec.encode(frame), nonce))
    }
}
