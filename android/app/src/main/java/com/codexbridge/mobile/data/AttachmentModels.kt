package com.codexbridge.mobile.data

/** Bridge hard limit for one decoded upload chunk (2 MiB). */
const val MAX_ATTACHMENT_CHUNK_BYTES: Int = 2 * 1024 * 1024

/** Bridge hard limit for a single attachment (32 MiB). */
const val MAX_ATTACHMENT_BYTES: Long = 32L * 1024 * 1024

/** Bridge keeps only the newest N attachments per thread. */
const val MAX_ATTACHMENTS_PER_THREAD: Int = 64

/** One finished upload as returned by the Bridge end-upload call. */
data class UploadedAttachment(
    val attachmentId: String,
    val contentRef: String,
    val kind: String,
    val filename: String = "",
    val mimeType: String = "",
    val byteSize: Long = 0,
)

/**
 * Split [byteSize] into ordered (offset, length) chunks that never exceed the
 * Bridge limit. Offsets are absolute file offsets, matching the protocol.
 */
fun attachmentChunkPlan(
    byteSize: Long,
    chunkSize: Int = MAX_ATTACHMENT_CHUNK_BYTES,
): List<Pair<Long, Int>> {
    require(byteSize >= 0) { "byteSize must be non-negative" }
    require(chunkSize > 0) { "chunkSize must be positive" }
    if (byteSize == 0L) return emptyList()
    val plan = mutableListOf<Pair<Long, Int>>()
    var offset = 0L
    while (offset < byteSize) {
        val remaining = byteSize - offset
        val length = minOf(remaining, chunkSize.toLong()).toInt()
        plan += offset to length
        offset += length
    }
    return plan
}

/**
 * Build the input_parts array the Bridge expects. Text always comes first
 * because the Bridge merges text parts into a single leading text input.
 * Only images are forwarded to the model; other kinds are rejected upstream.
 */
fun attachmentInputParts(
    text: String,
    uploaded: List<UploadedAttachment>,
): List<Map<String, Any>> {
    val parts = mutableListOf<Map<String, Any>>()
    val trimmed = text.trim()
    if (trimmed.isNotEmpty()) {
        parts += mapOf("type" to "text", "text" to trimmed)
    }
    uploaded
        .filter { it.kind == "image" }
        .forEach { item ->
            val id = item.attachmentId.ifBlank { item.contentRef }
            if (id.isNotBlank()) {
                parts += mapOf("type" to "image", "attachment_id" to id)
            }
        }
    return parts
}


/** A picked file that has not finished uploading yet. */
data class PendingAttachment(
    val localId: String,
    val uri: String,
    val filename: String,
    val mimeType: String,
    val kind: String,
    val byteSize: Long,
    val state: AttachmentState = AttachmentState.Pending,
    val uploadedBytes: Long = 0,
    val uploaded: UploadedAttachment? = null,
    val error: String = "",
)

enum class AttachmentState { Pending, Uploading, Uploaded, Failed }
