package com.codexbridge.mobile.data

import com.codexbridge.mobile.data.cbdp.CbdpCodec
import com.codexbridge.mobile.data.cbdp.CbdpFrame
import com.codexbridge.mobile.data.cbdp.CbdpHeader
import com.codexbridge.mobile.data.cbdp.CbdpMessageType
import com.codexbridge.mobile.data.cbdp.CbdpProfile
import com.codexbridge.mobile.data.cbdp.CbdpTag
import com.codexbridge.mobile.data.cbdp.CbdpTlv
import com.codexbridge.mobile.data.cbdp.CbdpTransportSecurity
import com.codexbridge.mobile.data.cbdp.bytes
import com.codexbridge.mobile.data.cbdp.u16
import com.codexbridge.mobile.data.cbdp.utf8
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.security.SecureRandom

data class DiscoveredBridge(
    val host: String,
    val port: Int,
    val name: String,
    val version: String,
    val deviceId: String = "",
    val bridgeInstanceId: ByteArray = ByteArray(0),
) {
    val baseUrl: String get() = "http://$host:$port"

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DiscoveredBridge) return false
        return host == other.host &&
            port == other.port &&
            name == other.name &&
            version == other.version &&
            deviceId == other.deviceId &&
            bridgeInstanceId.contentEquals(other.bridgeInstanceId)
    }

    override fun hashCode(): Int {
        var result = host.hashCode()
        result = 31 * result + port
        result = 31 * result + name.hashCode()
        result = 31 * result + version.hashCode()
        result = 31 * result + deviceId.hashCode()
        result = 31 * result + bridgeInstanceId.contentHashCode()
        return result
    }
}

object Discovery {
    private const val TIMEOUT_MS = 3500L
    private const val MAX_DISCOVERY_BYTES = 1024
    private const val SCOPE_SUBNET = 1
    private val random = SecureRandom()

    suspend fun discover(
        discoveryPort: Int = ApiClient.DEFAULT_DISCOVERY_PORT,
        timeoutMs: Long = TIMEOUT_MS,
        deviceId: String = "",
        bridgePort: Int = ApiClient.DEFAULT_PORT,
    ): List<DiscoveredBridge> =
        withContext(Dispatchers.IO) {
            val socket = DatagramSocket()
            try {
                socket.broadcast = true
                socket.soTimeout = 400
                val nonce = ByteArray(16).also(random::nextBytes)
                val request = discoveryRequest(nonce, deviceId, bridgePort)
                val targets = broadcastTargets()
                val found = linkedMapOf<String, DiscoveredBridge>()
                val deadline = System.currentTimeMillis() + timeoutMs
                var nextSend = 0L
                while (System.currentTimeMillis() < deadline) {
                    val now = System.currentTimeMillis()
                    if (now >= nextSend) {
                        targets.forEach { target ->
                            runCatching {
                                socket.send(
                                    DatagramPacket(
                                        request,
                                        request.size,
                                        InetAddress.getByName(target),
                                        discoveryPort,
                                    ),
                                )
                            }
                        }
                        nextSend = now + 650L
                    }
                    val response = DatagramPacket(ByteArray(MAX_DISCOVERY_BYTES), MAX_DISCOVERY_BYTES)
                    try {
                        socket.receive(response)
                    } catch (_error: SocketTimeoutException) {
                        continue
                    }
                    val reply = parseReply(response.data.copyOfRange(0, response.length), nonce) ?: continue
                    val host = response.address?.hostAddress ?: continue
                    val bridge = DiscoveredBridge(
                        host = host,
                        port = reply.port,
                        name = reply.model.ifBlank { reply.deviceId },
                        version = reply.firmware,
                        deviceId = reply.deviceId,
                        bridgeInstanceId = reply.bridgeInstanceId,
                    )
                    found[dedupeKey(bridge)] = bridge
                }
                found.values.toList()
            } catch (_error: Exception) {
                emptyList()
            } finally {
                socket.close()
            }
        }

    internal fun discoveryRequest(
        nonce: ByteArray,
        deviceId: String = "",
        port: Int = ApiClient.DEFAULT_PORT,
    ): ByteArray {
        require(nonce.size == 16) { "discovery nonce must be 16 bytes" }
        val tlvs = mutableListOf(
            CbdpTlv(CbdpTag.PROFILE, CbdpCodec.u8(CbdpProfile.FULL)),
            CbdpTlv(CbdpTag.DISCOVERY_NONCE, nonce),
            CbdpTlv(CbdpTag.DISCOVERY_SCOPE, CbdpCodec.u8(SCOPE_SUBNET)),
            CbdpTlv(CbdpTag.PORT, CbdpCodec.u16(port)),
        )
        if (deviceId.isNotBlank()) {
            tlvs += CbdpTlv(CbdpTag.DEVICE_ID, deviceId.toByteArray(Charsets.UTF_8))
        }
        val encoded = CbdpCodec.encode(
            CbdpFrame(header = CbdpHeader(messageType = CbdpMessageType.DISCOVERY), tlvs = tlvs),
        )
        require(encoded.size <= MAX_DISCOVERY_BYTES) { "discovery request too large" }
        return encoded
    }

    private data class DiscoveryReply(
        val deviceId: String,
        val model: String,
        val firmware: String,
        val port: Int,
        val bridgeInstanceId: ByteArray,
    )

    internal fun isMatchingReplyForTest(bytes: ByteArray, expectedNonce: ByteArray): Boolean =
        parseReply(bytes, expectedNonce) != null

    internal fun discoveryReply(
        nonce: ByteArray,
        deviceId: String = "bridge-device",
        model: String = "Codex Bridge",
        firmware: String = "1.0.0",
        port: Int = ApiClient.DEFAULT_PORT,
        bridgeInstanceId: ByteArray = ByteArray(16) { 0x44 },
    ): ByteArray = CbdpCodec.encode(
        CbdpFrame(
            header = CbdpHeader(messageType = CbdpMessageType.DISCOVERY_REPLY),
            tlvs = listOf(
                CbdpTlv(CbdpTag.DEVICE_ID, deviceId.toByteArray(Charsets.UTF_8)),
                CbdpTlv(CbdpTag.MODEL, model.toByteArray(Charsets.UTF_8)),
                CbdpTlv(CbdpTag.FIRMWARE, firmware.toByteArray(Charsets.UTF_8)),
                CbdpTlv(CbdpTag.PROFILE, CbdpCodec.u8(CbdpProfile.FULL)),
                CbdpTlv(CbdpTag.SUPPORTED_CAPS, CbdpCodec.u64(0)),
                CbdpTlv(CbdpTag.PORT, CbdpCodec.u16(port)),
                CbdpTlv(CbdpTag.BRIDGE_INSTANCE_ID, bridgeInstanceId),
                CbdpTlv(CbdpTag.DISCOVERY_NONCE, nonce),
                CbdpTlv(CbdpTag.TRANSPORT_SECURITY, CbdpCodec.u8(CbdpTransportSecurity.PLAIN_LAN)),
            ),
        ),
    )

    private fun parseReply(bytes: ByteArray, expectedNonce: ByteArray): DiscoveryReply? {
        if (bytes.size > MAX_DISCOVERY_BYTES) return null
        val frame = runCatching { CbdpCodec.decode(bytes) }.getOrNull() ?: return null
        if (frame.header.messageType != CbdpMessageType.DISCOVERY_REPLY) return null
        val returnedNonce = frame.tlvs.bytes(CbdpTag.DISCOVERY_NONCE) ?: return null
        if (!returnedNonce.contentEquals(expectedNonce)) return null
        val deviceId = frame.tlvs.utf8(CbdpTag.DEVICE_ID).orEmpty()
        if (deviceId.isBlank()) return null
        val port = frame.tlvs.u16(CbdpTag.PORT) ?: ApiClient.DEFAULT_PORT
        if (port !in 1..65535) return null
        return DiscoveryReply(
            deviceId = deviceId,
            model = frame.tlvs.utf8(CbdpTag.MODEL).orEmpty(),
            firmware = frame.tlvs.utf8(CbdpTag.FIRMWARE).orEmpty(),
            port = port,
            bridgeInstanceId = frame.tlvs.bytes(CbdpTag.BRIDGE_INSTANCE_ID) ?: ByteArray(0),
        )
    }

    private fun dedupeKey(bridge: DiscoveredBridge): String {
        val instance = bridge.bridgeInstanceId.joinToString("") { it.toUByte().toString(16).padStart(2, '0') }
        return "$instance:${bridge.deviceId}"
    }

    private fun broadcastTargets(): List<String> {
        val targets = linkedSetOf("255.255.255.255")
        runCatching {
            NetworkInterface.getNetworkInterfaces().toList().forEach { network ->
                if (!network.isUp || network.isLoopback) return@forEach
                network.interfaceAddresses.forEach { address ->
                    val source = address.address
                    val broadcast = address.broadcast
                    if (source is Inet4Address && broadcast is Inet4Address) {
                        targets.add(broadcast.hostAddress ?: return@forEach)
                    }
                }
            }
        }
        return targets.toList()
    }
}
