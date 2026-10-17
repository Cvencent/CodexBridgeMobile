package com.codexbridge.mobile.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException

@Serializable
data class DiscoveryPayload(
    val service: String = "",
    val name: String = "",
    val version: String = "",
    val port: Int = ApiClient.DEFAULT_PORT,
    val discovery_port: Int = ApiClient.DEFAULT_DISCOVERY_PORT,
    val requires_pairing: Boolean = true,
)

data class DiscoveredBridge(
    val host: String,
    val port: Int,
    val name: String,
    val version: String,
) {
    val baseUrl: String get() = "http://$host:$port"
}

object Discovery {
    private const val TIMEOUT_MS = 3500L
    private val requestBytes = "CODEXBRIDGE_COMPANION_DISCOVER".toByteArray(Charsets.UTF_8)
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    suspend fun discover(
        discoveryPort: Int = ApiClient.DEFAULT_DISCOVERY_PORT,
        timeoutMs: Long = TIMEOUT_MS,
    ): List<DiscoveredBridge> =
        withContext(Dispatchers.IO) {
            val socket = DatagramSocket()
            try {
                socket.broadcast = true
                socket.soTimeout = 400

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
                                        requestBytes,
                                        requestBytes.size,
                                        InetAddress.getByName(target),
                                        discoveryPort,
                                    ),
                                )
                            }
                        }
                        nextSend = now + 650L
                    }
                    val response = DatagramPacket(ByteArray(1024), 1024)
                    try {
                        socket.receive(response)
                    } catch (_error: SocketTimeoutException) {
                        continue
                    }
                    val text = String(response.data, 0, response.length, Charsets.UTF_8)
                    val payload = runCatching {
                        json.decodeFromString(DiscoveryPayload.serializer(), text)
                    }.getOrNull() ?: continue
                    if (payload.service != "codexbridge-companion") continue
                    val host = response.address?.hostAddress ?: continue
                    found[host] = DiscoveredBridge(
                        host = host,
                        port = payload.port,
                        name = payload.name,
                        version = payload.version,
                    )
                }
                found.values.toList()
            } catch (_error: Exception) {
                emptyList()
            } finally {
                socket.close()
            }
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
