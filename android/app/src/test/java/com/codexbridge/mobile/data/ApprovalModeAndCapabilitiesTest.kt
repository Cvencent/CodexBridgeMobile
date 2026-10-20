package com.codexbridge.mobile.data

import com.codexbridge.mobile.data.cbdp.ApprovalMode
import com.codexbridge.mobile.data.cbdp.CbdpMethod
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ApprovalModeAndCapabilitiesTest {
    @Test
    fun requestApprovalMapsToSafeCodexDefaults() {
        val policy = ApprovalMode.RequestApproval
        assertEquals("on-request", policy.approvalPolicy)
        assertEquals("workspace-write", policy.sandboxPolicy)
        assertEquals("请求批准", policy.label)
    }

    @Test
    fun fullAccessMapsToDangerFullAccess() {
        val policy = ApprovalMode.FullAccess
        assertEquals("never", policy.approvalPolicy)
        assertEquals("danger-full-access", policy.sandboxPolicy)
        assertEquals("完全允许", policy.label)
    }

    @Test
    fun sendMethodUsesTurnStartWhenQueueIsNotNeeded() {
        assertEquals(0x0005, CbdpMethod.TURN_START)
    }

    @Test
    fun capabilitySetParsesProtocolCatalog() {
        val catalog = Json.parseToJsonElement(
            """{"ok":true,"methods":[{"method":"thread.settings.update"},{"method":"approval.respond"},{"method":"turn.start"}]}"""
        ).jsonObject
        val caps = parseCommandCatalog(catalog)
        assertTrue(caps.threadSettings)
        assertTrue(caps.approvalResponse)
        assertFalse(caps.attachments)
    }

    @Test
    fun capabilitySetDetectsAttachmentsWhenBridgeAddsThem() {
        val catalog = Json.parseToJsonElement(
            """{"ok":true,"methods":[{"method":"attachment.upload.begin"},{"method":"attachment.add"},{"method":"turn.start"}]}"""
        ).jsonObject
        val caps = parseCommandCatalog(catalog)
        assertTrue(caps.attachments)
    }

    @Test
    fun rejectsUnappliedThreadSettings() {
        requireAppliedThreadSettings(ThreadSettings(applied = true))
        assertThrows(ApiException::class.java) {
            requireAppliedThreadSettings(ThreadSettings(applied = false))
        }
    }
}
