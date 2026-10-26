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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

class BridgeIdentityMatchTest {
    private fun bridge(
        host: String,
        port: Int = 8568,
        name: String = "DESKTOP-A",
        version: String = "2.1.33",
        instance: String = "aa",
    ) = DiscoveredBridge(
        host = host,
        port = port,
        name = name,
        version = version,
        deviceId = "bridge-$instance",
        bridgeInstanceId = ByteArray(16) { instance.toInt(16).toByte() },
    )

    @Test
    fun instanceIdIsAuthoritativeEvenWhenTheNameChanged() {
        val discovered = listOf(bridge("192.168.0.49", name = "renamed"))
        val match = matchDiscoveredBridge(
            discovered = discovered,
            storedInstanceId = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            storedName = "DESKTOP-A",
            storedVersion = "2.1.33",
            storedPort = 8568,
        )
        assertEquals("192.168.0.49", match?.bridge?.host)
        assertEquals(false, match?.adoptedIdentity)
    }

    @Test
    fun sameNameButDifferentInstanceIsNeverAdopted() {
        val discovered = listOf(bridge("192.168.0.50", instance = "bb"))
        val match = matchDiscoveredBridge(
            discovered = discovered,
            storedInstanceId = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            storedName = "DESKTOP-A",
            storedVersion = "2.1.33",
            storedPort = 8568,
        )
        assertNull(match)
    }

    @Test
    fun legacyRecordIsUpgradedWhenExactlyOneCandidateMatches() {
        val discovered = listOf(bridge("192.168.0.49", instance = "cc"))
        val match = matchDiscoveredBridge(
            discovered = discovered,
            storedInstanceId = "",
            storedName = "DESKTOP-A",
            storedVersion = "2.1.33",
            storedPort = 8568,
        )
        assertEquals("192.168.0.49", match?.bridge?.host)
        assertEquals(true, match?.adoptedIdentity)
    }

    @Test
    fun legacyRecordStaysUnmatchedWhenTheMatchIsAmbiguous() {
        val discovered = listOf(
            bridge("192.168.0.49", instance = "cc"),
            bridge("192.168.0.50", instance = "dd"),
        )
        val match = matchDiscoveredBridge(
            discovered = discovered,
            storedInstanceId = "",
            storedName = "DESKTOP-A",
            storedVersion = "2.1.33",
            storedPort = 8568,
        )
        assertNull(match)
    }

    @Test
    fun legacyRecordWithoutAnyCandidateStaysUnmatched() {
        val discovered = listOf(bridge("192.168.0.49", name = "OTHER", instance = "cc"))
        val match = matchDiscoveredBridge(
            discovered = discovered,
            storedInstanceId = "",
            storedName = "DESKTOP-A",
            storedVersion = "2.1.33",
            storedPort = 8568,
        )
        assertNull(match)
    }
}
