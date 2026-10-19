package com.codexbridge.mobile

import android.content.Context
import com.codexbridge.mobile.data.ConnectionRoute
import com.codexbridge.mobile.data.SettingsStore
import com.codexbridge.mobile.data.ThreadSummary
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AppViewModelTest {
    @Test
    fun foregroundAppliesPendingCompletionBeforeNetworkRefresh() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("codexbridge_mobile", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        SettingsStore(context).recordPendingCompletion("thread-1", "completed", 20)

        val model = AppViewModel(context)
        stateOf(model).value = UiState(
            screen = AppScreen.Threads,
            threads = listOf(
                ThreadSummary(thread_id = "thread-1", status = "working", updated_at = 10),
                ThreadSummary(thread_id = "thread-2", status = "working", updated_at = 30),
            ),
        )

        model.setForeground(true)

        val visible = model.state.value.threads
        assertEquals("completed", visible.first().status)
        assertEquals("thread-1", visible.first().thread_id)
        assertEquals("working", visible.last().status)
    }

    @Test
    fun unannouncedWriterLeaseStaysWritable() {
        val context = RuntimeEnvironment.getApplication()
        val model = AppViewModel(context)
        val state = stateOf(model)
        state.value = UiState(
            screen = AppScreen.Chat,
            activeThread = ThreadSummary(thread_id = "thread-1"),
            deviceRef = "7",
        )
        invokeApplyWriterState(model, "thread-1", "", 0, "")

        assertEquals(true, state.value.canWrite)
    }

    @Test
    fun explicitForeignLeaseIsNotWritable() {
        val context = RuntimeEnvironment.getApplication()
        val model = AppViewModel(context)
        val state = stateOf(model)
        state.value = UiState(
            screen = AppScreen.Chat,
            activeThread = ThreadSummary(thread_id = "thread-1"),
            deviceRef = "7",
        )
        invokeApplyWriterState(model, "thread-1", "claimed", 3, "99")

        assertEquals(false, state.value.canWrite)
    }

    private fun invokeApplyWriterState(
        model: AppViewModel,
        threadId: String,
        ownerState: String,
        leaseGeneration: Long,
        activeDeviceId: String,
    ) {
        val method = AppViewModel::class.java.getDeclaredMethod(
            "applyWriterState",
            String::class.java,
            String::class.java,
            Long::class.javaPrimitiveType,
            String::class.java,
        ).apply { isAccessible = true }
        method.invoke(model, threadId, ownerState, leaseGeneration, activeDeviceId)
    }

    @Suppress("UNCHECKED_CAST")
    private fun stateOf(model: AppViewModel): MutableStateFlow<UiState> {
        val field = AppViewModel::class.java.getDeclaredField("_state").apply { isAccessible = true }
        return field.get(model) as MutableStateFlow<UiState>
    }
    @Test
    fun stateExposesDualRoutesAndActiveRoleLabel() {
        val state = UiState(
            lanUrl = "http://192.168.0.42:8568",
            wanUrl = "http://tokenplanx.vip:8568",
            baseUrl = "http://tokenplanx.vip:8568",
            activeRouteRole = "wan",
        )

        assertEquals("外网", state.activeRouteLabel)
    }

    @Test
    fun activeRouteLabelFallsBackToLan() {
        val state = UiState(
            lanUrl = "http://192.168.0.42:8568",
            wanUrl = "",
            baseUrl = "http://192.168.0.42:8568",
            activeRouteRole = "lan",
        )

        assertEquals("内网", state.activeRouteLabel)
    }

}
