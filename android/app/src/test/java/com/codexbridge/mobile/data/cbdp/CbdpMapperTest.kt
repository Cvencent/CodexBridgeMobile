package com.codexbridge.mobile.data.cbdp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CbdpMapperTest {
    @Test
    fun accountReadCombinesPlanRateLimitsAndTokenUsage() {
        val account = CbdpMapper.accountFromCommands(
            account = command(
                CbdpMethod.ACCOUNT_READ,
                listOf(CbdpTlv(CbdpTag.PLAN_TYPE, "pro".toByteArray())),
            ),
            rateLimits = command(
                CbdpMethod.ACCOUNT_RATE_LIMITS_READ,
                listOf(
                    CbdpTlv(
                        CbdpTag.RATE_LIMITS,
                        CbdpCodec.encodeArray(
                            CbdpTlv(
                                0x00FF,
                                CbdpCodec.encodeTlvs(
                                    listOf(
                                        CbdpTlv(CbdpTag.RATE_LIMIT_WINDOW, "5h".toByteArray()),
                                        CbdpTlv(CbdpTag.REMAINING_PERCENT, CbdpCodec.u8(42)),
                                        CbdpTlv(CbdpTag.RESET_AT_MS, CbdpCodec.u64(1234)),
                                    ),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
            tokenUsage = command(
                CbdpMethod.ACCOUNT_TOKEN_USAGE_READ,
                listOf(
                    CbdpTlv(
                        CbdpTag.TOKEN_USAGE,
                        CbdpCodec.encodeTlvs(
                            listOf(CbdpTlv(CbdpTag.TOKEN_TOTAL, CbdpCodec.u32(900))),
                        ),
                    ),
                ),
            ),
        )

        assertEquals("pro", account.plan_type)
        assertTrue(account.five_hour_available)
        assertEquals(42, account.five_hour_remaining_percent)
        assertEquals("1234", account.five_hour_reset_date)
        assertTrue(account.weekly_tokens_available)
        assertEquals(900, account.weekly_tokens)
    }

    @Test
    fun writerAndActiveStateEventsKeepLeaseAndOwnerFields() {
        val writer = CbdpMapper.streamEvent(
            event(
                CbdpEvent.THREAD_OWNER_CHANGED,
                listOf(
                    CbdpTlv(CbdpTag.THREAD_REF, CbdpCodec.u32(7)),
                    CbdpTlv(CbdpTag.OWNER_STATE, CbdpCodec.u8(2)),
                    CbdpTlv(CbdpTag.LEASE_GENERATION, CbdpCodec.u32(11)),
                    CbdpTlv(CbdpTag.LEASE_EXPIRES_AT_MS, CbdpCodec.u64(555)),
                    CbdpTlv(CbdpTag.OWNER_DEVICE_REF, CbdpCodec.u32(4)),
                ),
            ),
        )
        assertNotNull(writer)
        assertEquals("writer_state", writer!!.type)
        assertEquals("7", writer.thread_id)
        assertEquals("claimed", writer.owner_state)
        assertEquals(11L, writer.lease_generation)
        assertEquals(555L, writer.lease_expires_at)
        assertEquals("4", writer.active_device_id)

        val active = CbdpMapper.streamEvent(
            event(
                CbdpEvent.ACTIVE_THREAD_CHANGED,
                listOf(
                    CbdpTlv(CbdpTag.ACTIVE_THREAD_REF, CbdpCodec.u32(7)),
                    CbdpTlv(CbdpTag.ACTIVE_TURN_REF, CbdpCodec.u32(9)),
                    CbdpTlv(CbdpTag.ACTIVE_DEVICE_REF, CbdpCodec.u32(4)),
                    CbdpTlv(CbdpTag.ACTIVE_REVISION, CbdpCodec.u32(3)),
                ),
            ),
        )
        assertNotNull(active)
        assertEquals("active_state", active!!.type)
        assertEquals("7", active.active_thread_id)
        assertEquals("turn:9", active.active_turn_id)
        assertEquals("4", active.active_device_id)
        assertEquals(3L, active.active_revision)
    }

    @Test
    fun catalogAndDeletionEventsMapToUiEvents() {
        assertEquals(
            "models_changed",
            CbdpMapper.streamEvent(event(CbdpEvent.MODEL_CATALOG_CHANGED, emptyList()))?.type,
        )
        assertEquals(
            "projects_changed",
            CbdpMapper.streamEvent(event(CbdpEvent.PROJECT_CATALOG_CHANGED, emptyList()))?.type,
        )
        val removed = CbdpMapper.streamEvent(
            event(
                CbdpEvent.MESSAGE_DELETED,
                listOf(
                    CbdpTlv(CbdpTag.THREAD_REF, CbdpCodec.u32(7)),
                    CbdpTlv(CbdpTag.MESSAGE_REF, CbdpCodec.u32(12)),
                ),
            ),
        )
        assertNotNull(removed)
        assertEquals("message_deleted", removed!!.type)
        assertEquals("12", removed.item_id)
        assertFalse(removed.item_id.isBlank())
    }

    private fun command(methodId: Int, result: List<CbdpTlv>) = CbdpCommand(
        requestId = 1,
        methodId = methodId,
        state = CbdpCommandState.COMPLETED,
        result = result,
        payload = ByteArray(0),
        errorCode = CbdpErrorCode.OK,
        errorMessage = "",
    )

    private fun event(type: Int, payload: List<CbdpTlv>) = CbdpEventEnvelope(
        topicId = 1,
        cursor = 1,
        eventType = type,
        payload = payload,
    )
}
