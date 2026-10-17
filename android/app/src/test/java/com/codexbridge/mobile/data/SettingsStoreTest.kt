package com.codexbridge.mobile.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SettingsStoreTest {

    @Test
    fun dualAddressesSurviveStoreRecreation() {
        val context = RuntimeEnvironment.getApplication()
        SettingsStore(context).clearAll()
        val store = SettingsStore(context)
        store.baseUrl = "http://192.168.0.42:8568"
        store.lanUrl = "http://192.168.0.42:8568"
        store.wanUrl = "http://tokenplanx.vip:8568"
        store.lastSuccessfulUrl = "http://tokenplanx.vip:8568"

        val reloaded = SettingsStore(context)
        assertEquals("http://192.168.0.42:8568", reloaded.lanUrl)
        assertEquals("http://tokenplanx.vip:8568", reloaded.wanUrl)
        assertEquals("http://tokenplanx.vip:8568", reloaded.lastSuccessfulUrl)
    }

    @Test
    fun legacyBaseUrlBecomesLanWhenDualAddressIsEmpty() {
        val context = RuntimeEnvironment.getApplication()
        SettingsStore(context).clearAll()
        SettingsStore(context).baseUrl = "http://192.168.0.42:8568"

        val migrated = SettingsStore(context)
        assertEquals("http://192.168.0.42:8568", migrated.lanUrl)
        assertEquals("", migrated.wanUrl)
    }

    @Test
    fun legacyBaseUrlDoesNotBecomeLanWhenOnlyWanIsConfigured() {
        val context = RuntimeEnvironment.getApplication()
        SettingsStore(context).clearAll()
        val store = SettingsStore(context)
        store.baseUrl = "http://192.168.0.42:8568"
        store.wanUrl = "http://tokenplanx.vip:8568"

        val reloaded = SettingsStore(context)
        assertEquals("", reloaded.lanUrl)
        assertEquals("http://tokenplanx.vip:8568", reloaded.wanUrl)
    }

    @Test
    fun pendingCompletionSurvivesStoreRecreationAndCanBeCleared() {
        val context = RuntimeEnvironment.getApplication()
        val first = SettingsStore(context)
        first.recordPendingCompletion("thread-1", "completed", 123)

        val reloaded = SettingsStore(context)
        assertEquals(
            listOf(PendingCompletion("thread-1", "completed", 123)),
            reloaded.pendingCompletions(),
        )

        reloaded.clearPendingCompletion("thread-1")
        assertTrue(SettingsStore(context).pendingCompletions().isEmpty())
    }
}
