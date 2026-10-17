package com.codexbridge.mobile

import com.codexbridge.mobile.data.MessageItem
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UiStateTest {
    @Test
    fun emptyChatShowsLoadingWhileDetailRequestIsInFlight() {
        assertTrue(UiState(busy = true).chatLoading)
        assertFalse(UiState(busy = true, messages = listOf(MessageItem(id = "m1"))).chatLoading)
        assertFalse(UiState(busy = false).chatLoading)
    }
}
