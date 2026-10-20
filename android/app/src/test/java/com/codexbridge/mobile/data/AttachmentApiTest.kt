package com.codexbridge.mobile.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AttachmentApiTest {
    @Test
    fun chunkPlanNeverExceedsTwoMiB() {
        val plan = attachmentChunkPlan(5L * 1024 * 1024)
        assertTrue(plan.sumOf { it.second.toLong() } == 5L * 1024 * 1024)
        assertTrue(plan.all { it.second <= MAX_ATTACHMENT_CHUNK_BYTES })
        assertEquals(0L, plan.first().first)
    }

    @Test
    fun chunkPlanKeepsOrderAndOffsets() {
        val size = 2L * 1024 * 1024 + 10
        val plan = attachmentChunkPlan(size)
        var expected = 0L
        plan.forEach { (offset, length) ->
            assertEquals(expected, offset)
            expected += length
        }
        assertEquals(size, expected)
    }

    @Test
    fun inputPartsPutTextFirstThenImages() {
        val parts = attachmentInputParts(
            text = "看看这张图",
            uploaded = listOf(
                UploadedAttachment(attachmentId = "att_1", contentRef = "content:att_1", kind = "image"),
                UploadedAttachment(attachmentId = "att_2", contentRef = "content:att_2", kind = "image"),
            ),
        )
        assertEquals(3, parts.size)
        assertEquals("text", parts[0]["type"])
        assertEquals("看看这张图", parts[0]["text"])
        assertEquals("image", parts[1]["type"])
        assertEquals("att_1", parts[1]["attachment_id"])
        assertEquals("att_2", parts[2]["attachment_id"])
    }

    @Test
    fun imageOnlyMessageHasNoTextPart() {
        val parts = attachmentInputParts(
            text = "",
            uploaded = listOf(
                UploadedAttachment(attachmentId = "att_1", contentRef = "content:att_1", kind = "image"),
            ),
        )
        assertEquals(1, parts.size)
        assertEquals("image", parts[0]["type"])
    }

    @Test
    fun attachmentFlagComesFromCapabilityNegotiation() {
        val enabled = parseEnabledCapabilities(
            kotlinx.serialization.json.Json.parseToJsonElement(
                """{"ok":true,"enabled":["approval","attachment","control","queue","status","tasks"]}"""
            ) as kotlinx.serialization.json.JsonObject,
        )
        assertTrue(enabled.contains("attachment"))
        assertTrue(enabled.contains("control"))
    }

    @Test
    fun attachmentFlagAbsentWhenNotNegotiated() {
        val enabled = parseEnabledCapabilities(
            kotlinx.serialization.json.Json.parseToJsonElement(
                """{"ok":true,"enabled":["approval","control"]}"""
            ) as kotlinx.serialization.json.JsonObject,
        )
        assertTrue(!enabled.contains("attachment"))
    }
}
