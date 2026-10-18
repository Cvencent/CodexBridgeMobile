package com.codexbridge.mobile.data.cbdp

import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object CbdpAuth {
    private const val HMAC_PREFIX = "CBDP1-AUTH"
    private const val TOKEN_PREFIX = "CBDP1-TOKEN"

    fun hmacProof(
        pairingToken: ByteArray,
        sessionId: ByteArray,
        clientNonce: ByteArray,
        serverNonce: ByteArray,
        deviceId: String,
    ): ByteArray {
        require(sessionId.size == 16) { "session_id must be 16 bytes" }
        require(clientNonce.size == 16) { "client_nonce must be 16 bytes" }
        require(serverNonce.size == 16) { "server_nonce must be 16 bytes" }
        val data = byteArrayOf(*HMAC_PREFIX.toByteArray(Charsets.US_ASCII)) +
            sessionId + clientNonce + serverNonce + deviceId.toByteArray(Charsets.UTF_8)
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(pairingToken, "HmacSHA256"))
        return mac.doFinal(data)
    }

    fun tokenProof(
        pairingToken: ByteArray,
        sessionId: ByteArray,
        clientNonce: ByteArray,
        serverNonce: ByteArray,
        deviceId: String,
    ): ByteArray {
        require(sessionId.size == 16) { "session_id must be 16 bytes" }
        require(clientNonce.size == 16) { "client_nonce must be 16 bytes" }
        require(serverNonce.size == 16) { "server_nonce must be 16 bytes" }
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(TOKEN_PREFIX.toByteArray(Charsets.US_ASCII))
        digest.update(pairingToken)
        digest.update(sessionId)
        digest.update(clientNonce)
        digest.update(serverNonce)
        digest.update(deviceId.toByteArray(Charsets.UTF_8))
        return digest.digest()
    }
}
