package com.codexbridge.mobile.data.cbdp

object CbdpMessageType {
    const val HELLO = 0x01
    const val WELCOME = 0x02
    const val AUTH = 0x03
    const val AUTH_OK = 0x04
    const val PING = 0x05
    const val PONG = 0x06
    const val SUBSCRIBE = 0x07
    const val UNSUBSCRIBE = 0x08
    const val SNAPSHOT = 0x09
    const val DELTA = 0x0A
    const val ACK = 0x0B
    const val RESYNC = 0x0C
    const val COMMAND = 0x0D
    const val RESULT = 0x0E
    const val ERROR = 0x0F
    const val SEND_BEGIN = 0x10
    const val SEND_CHUNK = 0x11
    const val SEND_END = 0x12
    const val AUDIO_BEGIN = 0x13
    const val AUDIO_CHUNK = 0x14
    const val AUDIO_END = 0x15
    const val APPROVAL_REQUEST = 0x16
    const val APPROVAL_RESOLVE = 0x17
    const val DEVICE_STATUS = 0x18
    const val CAPABILITY_UPDATE = 0x19
    const val CLOSE = 0x1A
    const val FRAGMENT = 0x1B
    const val DISCOVERY = 0x1C
    const val DISCOVERY_REPLY = 0x1D
}

object CbdpFlags {
    const val MORE_FRAGMENTS = 0x0001
    const val COMPRESSED = 0x0002
    const val CRC32_PRESENT = 0x0004
    const val URGENT = 0x0008
    const val NO_ACK = 0x0010
    const val TEXT_TRUNCATED = 0x0020
    const val SNAPSHOT_PAGE = 0x0040
    const val RESUMED = 0x0080
    const val REQUEST = 0x0100
    const val RESPONSE = 0x0200
    const val EVENT = 0x0400
    const val COMMAND = 0x0800
}

object CbdpQos {
    const val CRITICAL = 0
    const val STATE = 1
    const val TELEMETRY = 2
}

object CbdpProfile {
    const val MICRO = 0
    const val PANEL = 1
    const val FULL = 2
}

object CbdpTransportSecurity {
    const val PLAIN_LAN = 0
    const val TLS = 1
}

data class CbdpHeader(
    val messageType: Int,
    val flags: Int = 0,
    val headerLength: Int = CbdpConstants.FIXED_HEADER_LENGTH,
    val payloadLength: Int = 0,
    val topicId: Long = 0,
    val seq: Long = 0,
    val ackSeq: Long = 0,
    val code: Int = 0,
    val qos: Int = CbdpQos.CRITICAL,
    val reserved: Int = 0,
)

data class CbdpTlv(
    val tag: Int,
    val value: ByteArray,
)

data class CbdpFrame(
    val header: CbdpHeader,
    val tlvs: List<CbdpTlv> = emptyList(),
    val payload: ByteArray = ByteArray(0),
)

class CbdpDecodeException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

data class CbdpFragmentedFrame(val frame: CbdpFrame, val originalFrameBytes: ByteArray)
