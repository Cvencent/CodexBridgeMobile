package com.codexbridge.mobile.data.cbdp

import java.nio.ByteBuffer
import java.nio.ByteOrder

object CbdpCodec {
    const val MAX_FRAME_BYTES: Int = 1 shl 20
    const val MAX_TLVS: Int = 256

    fun encode(frame: CbdpFrame): ByteArray {
        val tlvBytes = encodeTlvs(frame.tlvs)
        val headerLength = CbdpConstants.FIXED_HEADER_LENGTH + tlvBytes.size
        require(headerLength <= 0xFFFF) { "header too large" }
        require(frame.payload.size <= MAX_FRAME_BYTES) { "payload too large" }
        val header = frame.header.copy(
            headerLength = headerLength,
            payloadLength = frame.payload.size,
        )
        val buffer = ByteBuffer.allocate(headerLength + frame.payload.size)
            .order(ByteOrder.BIG_ENDIAN)
        buffer.putShort(CbdpConstants.MAGIC.toShort())
        buffer.put(CbdpConstants.VERSION_MAJOR.toByte())
        buffer.put(header.messageType.toByte())
        buffer.putShort(header.flags.toShort())
        buffer.putShort(header.headerLength.toShort())
        buffer.putInt(header.payloadLength)
        buffer.putInt(header.topicId.toInt())
        buffer.putInt(header.seq.toInt())
        buffer.putInt(header.ackSeq.toInt())
        buffer.putShort(header.code.toShort())
        buffer.put(header.qos.toByte())
        buffer.put(header.reserved.toByte())
        buffer.put(tlvBytes)
        buffer.put(frame.payload)
        return buffer.array()
    }

    fun decode(bytes: ByteArray): CbdpFrame {
        val header = decodeHeader(bytes)
        val total = header.headerLength + header.payloadLength
        if (total > MAX_FRAME_BYTES) throw CbdpDecodeException("frame exceeds max_frame_bytes")
        if (bytes.size != total) {
            throw CbdpDecodeException("frame length mismatch: expected $total, got ${bytes.size}")
        }
        val tlvStart = CbdpConstants.FIXED_HEADER_LENGTH
        val tlvBytes = bytes.copyOfRange(tlvStart, header.headerLength)
        val payload = bytes.copyOfRange(header.headerLength, total)
        return CbdpFrame(
            header = header,
            tlvs = decodeTlvs(tlvBytes, knownTags = true),
            payload = payload,
        )
    }

    fun decodeStream(bytes: ByteArray): List<CbdpFrame> {
        val frames = mutableListOf<CbdpFrame>()
        var offset = 0
        while (offset < bytes.size) {
            if (bytes.size - offset < CbdpConstants.FIXED_HEADER_LENGTH) {
                throw CbdpDecodeException("truncated fixed header")
            }
            val header = decodeHeader(bytes, offset)
            val total = header.headerLength + header.payloadLength
            if (total > MAX_FRAME_BYTES) {
                throw CbdpDecodeException("frame exceeds max_frame_bytes")
            }
            if (offset + total > bytes.size) {
                throw CbdpDecodeException("truncated frame payload")
            }
            frames += decode(bytes.copyOfRange(offset, offset + total))
            offset += total
        }
        return frames
    }

    fun decodeHeader(bytes: ByteArray, offset: Int = 0): CbdpHeader {
        if (bytes.size - offset < CbdpConstants.FIXED_HEADER_LENGTH) {
            throw CbdpDecodeException("truncated fixed header")
        }
        val buffer = ByteBuffer.wrap(bytes, offset, CbdpConstants.FIXED_HEADER_LENGTH)
            .order(ByteOrder.BIG_ENDIAN)
        if (buffer.short.toInt() and 0xFFFF != CbdpConstants.MAGIC) {
            throw CbdpDecodeException("invalid magic")
        }
        if (buffer.get().toInt() and 0xFF != CbdpConstants.VERSION_MAJOR) {
            throw CbdpDecodeException("unsupported version_major")
        }
        val messageType = buffer.get().toInt() and 0xFF
        val flags = buffer.short.toInt() and 0xFFFF
        val headerLength = buffer.short.toInt() and 0xFFFF
        val payloadLength = buffer.int
        val topicId = buffer.int.toLong() and 0xFFFF_FFFFL
        val seq = buffer.int.toLong() and 0xFFFF_FFFFL
        val ackSeq = buffer.int.toLong() and 0xFFFF_FFFFL
        val code = buffer.short.toInt() and 0xFFFF
        val qos = buffer.get().toInt() and 0xFF
        val reserved = buffer.get().toInt() and 0xFF
        if (headerLength < CbdpConstants.FIXED_HEADER_LENGTH) {
            throw CbdpDecodeException("header_len smaller than fixed header")
        }
        if (payloadLength < 0) {
            throw CbdpDecodeException("payload_len overflows")
        }
        if (headerLength + payloadLength < headerLength) {
            throw CbdpDecodeException("frame length overflow")
        }
        if (reserved != 0) {
            throw CbdpDecodeException("reserved must be zero")
        }
        return CbdpHeader(
            messageType = messageType,
            flags = flags,
            headerLength = headerLength,
            payloadLength = payloadLength,
            topicId = topicId,
            seq = seq,
            ackSeq = ackSeq,
            code = code,
            qos = qos,
            reserved = reserved,
        )
    }

    fun encodeTlvs(tlvs: List<CbdpTlv>): ByteArray {
        require(tlvs.size <= MAX_TLVS) { "too many TLVs" }
        val sorted = tlvs.sortedBy { it.tag and 0xFFFF }
        val size = sorted.sumOf { 4 + it.value.size }
        val buffer = ByteBuffer.allocate(size).order(ByteOrder.BIG_ENDIAN)
        sorted.forEach { tlv ->
            require(tlv.tag in 0..0xFFFF) { "invalid TLV tag" }
            require(tlv.value.size <= 0xFFFF) { "TLV value too large" }
            buffer.putShort(tlv.tag.toShort())
            buffer.putShort(tlv.value.size.toShort())
            buffer.put(tlv.value)
        }
        return buffer.array()
    }

    fun decodeTlvs(bytes: ByteArray, knownTags: Boolean = false): List<CbdpTlv> {
        val result = mutableListOf<CbdpTlv>()
        val seen = mutableSetOf<Int>()
        var offset = 0
        while (offset < bytes.size) {
            if (bytes.size - offset < 4) throw CbdpDecodeException("truncated TLV header")
            val tag = ((bytes[offset].toInt() and 0xFF) shl 8) or
                (bytes[offset + 1].toInt() and 0xFF)
            val length = ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
                (bytes[offset + 3].toInt() and 0xFF)
            offset += 4
            if (length > bytes.size - offset) throw CbdpDecodeException("truncated TLV value")
            if (knownTags && !seen.add(tag)) {
                throw CbdpDecodeException("duplicate known TLV tag")
            }
            result += CbdpTlv(tag, bytes.copyOfRange(offset, offset + length))
            offset += length
            if (result.size > MAX_TLVS) throw CbdpDecodeException("too many TLVs")
        }
        return result
    }

    fun u8(value: Int): ByteArray = byteArrayOf(value.toByte())

    fun u16(value: Int): ByteArray =
        ByteBuffer.allocate(2).order(ByteOrder.BIG_ENDIAN).putShort(value.toShort()).array()

    fun u32(value: Long): ByteArray =
        ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(value.toInt()).array()

    fun u64(value: Long): ByteArray =
        ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(value).array()

    fun readU8(value: ByteArray): Int {
        if (value.size != 1) throw CbdpDecodeException("u8 TLV must be 1 byte")
        return value[0].toInt() and 0xFF
    }

    fun readU16(value: ByteArray): Int {
        if (value.size != 2) throw CbdpDecodeException("u16 TLV must be 2 bytes")
        return ByteBuffer.wrap(value).order(ByteOrder.BIG_ENDIAN).short.toInt() and 0xFFFF
    }

    fun readU32(value: ByteArray): Long {
        if (value.size != 4) throw CbdpDecodeException("u32 TLV must be 4 bytes")
        return ByteBuffer.wrap(value).order(ByteOrder.BIG_ENDIAN).int.toLong() and 0xFFFF_FFFFL
    }

    fun readU64(value: ByteArray): Long {
        if (value.size != 8) throw CbdpDecodeException("u64 TLV must be 8 bytes")
        return ByteBuffer.wrap(value).order(ByteOrder.BIG_ENDIAN).long
    }

    fun tlvs(vararg tlvs: CbdpTlv): ByteArray = encodeTlvs(tlvs.toList())

    fun encodeArray(vararg elements: CbdpTlv): ByteArray = encodeArray(elements.toList())

    fun encodeArray(elements: List<CbdpTlv>): ByteArray {
        require(elements.size <= 0xFFFF) { "too many array elements" }
        val encoded = encodeTlvs(elements)
        return u16(elements.size) + encoded
    }

    fun decodeArray(bytes: ByteArray): List<List<CbdpTlv>> {
        if (bytes.size < 2) return emptyList()
        val count = readU16(bytes.copyOfRange(0, 2))
        val elements = mutableListOf<List<CbdpTlv>>()
        var offset = 2
        repeat(count) {
            if (offset + 4 > bytes.size) return@repeat
            val tag = ((bytes[offset].toInt() and 0xFF) shl 8) or (bytes[offset + 1].toInt() and 0xFF)
            val length = ((bytes[offset + 2].toInt() and 0xFF) shl 8) or (bytes[offset + 3].toInt() and 0xFF)
            offset += 4
            if (offset + length > bytes.size) return@repeat
            elements += listOf(CbdpTlv(tag, bytes.copyOfRange(offset, offset + length)))
            offset += length
        }
        return elements
    }
}

fun List<CbdpTlv>.find(tag: Int): CbdpTlv? =
    firstOrNull { it.tag and 0xFFFF == tag and 0xFFFF }

fun List<CbdpTlv>.u8(tag: Int): Int? = find(tag)?.let { CbdpCodec.readU8(it.value) }

fun List<CbdpTlv>.u16(tag: Int): Int? = find(tag)?.let { CbdpCodec.readU16(it.value) }

fun List<CbdpTlv>.u32(tag: Int): Long? = find(tag)?.let { CbdpCodec.readU32(it.value) }

fun List<CbdpTlv>.u64(tag: Int): Long? = find(tag)?.let { CbdpCodec.readU64(it.value) }

fun List<CbdpTlv>.utf8(tag: Int): String? = find(tag)?.value?.toString(Charsets.UTF_8)

fun List<CbdpTlv>.bytes(tag: Int): ByteArray? = find(tag)?.value

fun List<CbdpTlv>.tlv(tag: Int): List<CbdpTlv>? =
    find(tag)?.value?.let { CbdpCodec.decodeTlvs(it) }

fun List<CbdpTlv>.bool(tag: Int): Boolean = u8(tag)?.let { it != 0 } == true

fun List<CbdpTlv>.array(tag: Int): List<List<CbdpTlv>> =
    find(tag)?.value?.let { CbdpCodec.decodeArray(it) }.orEmpty()
