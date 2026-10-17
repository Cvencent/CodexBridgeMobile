package com.codexbridge.mobile

import android.app.NotificationManager
import android.content.Context
import android.media.AudioManager
import com.codexbridge.mobile.data.PendingCompletion
import com.codexbridge.mobile.data.SettingsStore
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.Robolectric

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class CompletionNotificationServiceTest {
    @Test
    fun realCompletionNotificationPersistsStateForImmediateUiMerge() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("codexbridge_mobile", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        val service = Robolectric.buildService(CompletionNotificationService::class.java).create().get()
        val notify = CompletionNotificationService::class.java.getDeclaredMethod(
            "notifyCompletion",
            String::class.java,
            Boolean::class.javaPrimitiveType,
            Long::class.javaPrimitiveType,
        ).apply { isAccessible = true }

        notify.invoke(service, "thread-1", false, 123L)

        assertEquals(
            listOf(PendingCompletion("thread-1", "completed", 123)),
            SettingsStore(context).pendingCompletions(),
        )
    }

    @Test
    fun testAlertReportsZeroNotificationVolume() {
        val context = RuntimeEnvironment.getApplication()
        val audio = context.getSystemService(AudioManager::class.java)
        audio.setStreamVolume(AudioManager.STREAM_NOTIFICATION, 0, 0)
        assertTrue(CompletionNotificationService.showTestNotification(context).toString().contains("音量"))
    }

    @Test
    fun testAlertReportsDisabledNotificationsInsteadOfSilentlyPosting() {
        val context = RuntimeEnvironment.getApplication()
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        org.robolectric.Shadows.shadowOf(manager).setNotificationsEnabled(false)
        assertTrue(CompletionNotificationService.showTestNotification(context).toString().contains("通知权限"))
    }

    @Test
    fun testAlertReportsMutedChannel() {
        val context = RuntimeEnvironment.getApplication()
        CompletionNotificationService.showTestNotification(context)
        val manager = context.getSystemService(NotificationManager::class.java)
        val channel = manager.notificationChannels.first { it.id != "codexbridge_listen" }
        channel.setSound(null, null)
        manager.deleteNotificationChannel(channel.id)
        manager.createNotificationChannel(channel)
        assertTrue(CompletionNotificationService.showTestNotification(context).toString().contains("静音"))
    }
}
