package com.codexbridge.mobile

import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import com.codexbridge.mobile.data.StreamEvent
import com.codexbridge.mobile.data.ThreadSummary

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class BackgroundReadTest {
    @Test
    fun backgroundCompletionIsNotConsideredRead() {
        val model = AppViewModel(RuntimeEnvironment.getApplication())
        val stateField = AppViewModel::class.java.getDeclaredField("_state").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val state = stateField.get(model) as kotlinx.coroutines.flow.MutableStateFlow<UiState>
        state.value = UiState(screen = AppScreen.Chat, activeThread = ThreadSummary(thread_id = "t1"))
        val apply = AppViewModel::class.java.getDeclaredMethod("applyEvent", StreamEvent::class.java)
        apply.isAccessible = true
        apply.invoke(model, StreamEvent(type = "turn_completed", thread_id = "t1", turn_id = "turn1"))
        assertFalse("Background chat must retain unread completion", model.state.value.activeThread?.status == "idle")
    }
}
