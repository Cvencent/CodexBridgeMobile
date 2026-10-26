package com.codexbridge.mobile

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.RingtoneManager
import android.media.AudioManager
import android.provider.Settings
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.codexbridge.mobile.data.ApiClient
import com.codexbridge.mobile.data.ConnectionRoute
import com.codexbridge.mobile.data.ConnectionSelector
import com.codexbridge.mobile.data.CompletionNotificationTracker
import com.codexbridge.mobile.data.SettingsStore
import com.codexbridge.mobile.data.notificationCompletedAt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.random.Random

private const val LISTEN_CHANNEL = "codexbridge_listen"
private const val COMPLETION_CHANNEL = "codexbridge_completion_v5"
private const val LISTEN_NOTIFICATION_ID = 4101
private const val POLL_MS = 30_000L

class CompletionNotificationService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private lateinit var completions: CompletionNotificationTracker
    private var baselineReady = false
    @Volatile private var cachedClient: ApiClient? = null

    override fun onCreate() {
        super.onCreate()
        createChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val settings = SettingsStore(this)
        if (!settings.isPaired) {
            stopSelf()
            return START_NOT_STICKY
        }
        startAsForeground()
        holdConnectionLocks()
        if (job?.isActive != true) {
            job = scope.launch {
                seedCompletionBaseline(settings)
                launch { listenEvents(settings, completions) }
                launch { pollCompletions(settings, completions) }
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        job?.cancel()
        scope.cancel()
        releaseConnectionLocks()
        resetCachedClient()
        super.onDestroy()
    }

    /**
     * Doze suspends the network of an idle phone, so with the screen off the
     * event stream and the poll loop stop delivering completions. A partial
     * wake lock plus a high performance Wi-Fi lock keep the LAN socket alive
     * behind the ongoing notification.
     */
    private fun holdConnectionLocks() {
        if (wakeLock == null) {
            try {
                val power = getSystemService(PowerManager::class.java)
                wakeLock = power.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "CodexBridge:completion",
                ).apply {
                    setReferenceCounted(false)
                    acquire()
                }
            } catch (_: Exception) {
                wakeLock = null
            }
        }
        if (wifiLock == null) {
            try {
                val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                wifiLock = wifi.createWifiLock(
                    WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                    "CodexBridge:completion",
                ).apply {
                    setReferenceCounted(false)
                    acquire()
                }
            } catch (_: Exception) {
                wifiLock = null
            }
        }
    }

    private fun releaseConnectionLocks() {
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (_: Exception) {
        }
        wakeLock = null
        try {
            if (wifiLock?.isHeld == true) wifiLock?.release()
        } catch (_: Exception) {
        }
        wifiLock = null
    }

    private fun startAsForeground() {
        val launch = PendingIntent.getActivity(
            this,
            100,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(this, LISTEN_CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("CodexBridge")
            .setContentText("正在监听 Codex 任务完成")
            .setContentIntent(launch)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this,
                LISTEN_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(LISTEN_NOTIFICATION_ID, notification)
        }
    }

    /**
     * Drop the cached client, closing its long-lived CBDP stream first.
     *
     * Assigning null alone leaked a live event stream every time the listener
     * hit an error and rebuilt the client.
     */
    private fun resetCachedClient() {
        val previous = cachedClient
        cachedClient = null
        previous?.close()
    }

    private suspend fun clientFor(settings: SettingsStore): ApiClient {
        cachedClient?.let { return it }
        val candidates = ConnectionRoute.candidatesFor(
            legacyUrl = settings.baseUrl,
            lanUrl = settings.lanUrl,
            wanUrl = settings.wanUrl,
            onWifi = isOnWifi(),
        )
        val selected = ConnectionSelector.select(candidates) { endpoint ->
            ApiClient(endpoint.url, settings.token, settings.phoneId).ping()
        }
        if (selected != null) {
            settings.lastSuccessfulUrl = selected.url
            settings.baseUrl = selected.url
            return ApiClient(selected.url, settings.token, settings.phoneId).also { cachedClient = it }
        }
        return ApiClient(settings.baseUrl, settings.token, settings.phoneId).also { cachedClient = it }
    }

    private fun isOnWifi(): Boolean {
        val manager = getSystemService(ConnectivityManager::class.java) ?: return true
        val network = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }

    private suspend fun seedCompletionBaseline(settings: SettingsStore) {
        completions = CompletionNotificationTracker(settings)
        try {
            val startedAt = System.currentTimeMillis() / 1000
            val client = clientFor(settings)
            completions.seed(
                client.threads().threads,
                beforeOrAt = startedAt,
            )
            baselineReady = true
        } catch (_: Exception) {
            baselineReady = false
        }
    }

    private suspend fun listenEvents(
        settings: SettingsStore,
        completions: CompletionNotificationTracker,
    ) {
        var attempt = 0
        while (scope.isActive) {
            if (isAppForeground()) {
                delay(2_000L)
                continue
            }
            try {
                val client = clientFor(settings)
                var streamFailed = false
                client.events().collect { event ->
                    if (event.type == "stream_error") {
                        streamFailed = true
                        return@collect
                    }
                    attempt = 0
                    when (event.type) {
                        "turn_completed" -> {
                            if (event.thread_id.isBlank()) return@collect
                            // Any device opening the task publishes an idle thread
                            // status, and completions that another device already
                            // read arrive with read=true: never alert twice there.
                            if (event.read) {
                                cancelCompletion(event.thread_id)
                                settings.clearPendingCompletion(event.thread_id)
                                return@collect
                            }
                            val completedAt = epochSeconds(event.completed_at)
                            if (event.turn_id.isBlank() && completedAt <= 0) return@collect
                            val failed = event.status == "failed"
                            if (!completions.shouldNotify(
                                    event.thread_id,
                                    event.turn_id,
                                    completedAt,
                                )
                            ) return@collect
                            notifyCompletion(
                                event.thread_id,
                                failed,
                                completedAt.takeIf { it > 0 } ?: System.currentTimeMillis() / 1000,
                            )
                        }
                        "thread_status" -> {
                            if (event.thread_id.isBlank()) return@collect
                            if (event.status == "idle") {
                                // Another client (phone or ESP device) marked the
                                // task read, so drop this device's pending alert.
                                cancelCompletion(event.thread_id)
                                settings.clearPendingCompletion(event.thread_id)
                            } else if (event.status == "active" || event.active_flags.isNotEmpty()) {
                                settings.clearPendingCompletion(event.thread_id)
                            } else if (event.status == "completed" || event.status == "failed") {
                                // Terminal summaries arrive here in real time; the
                                // payload has no timestamp, so read the list once to
                                // get completed_at instead of waiting for the poll.
                                scope.launch {
                                    runCatching { checkCompletions(settings, completions) }
                                }
                            }
                        }
                    }
                }
                if (streamFailed) resetCachedClient()
                attempt += 1
                delay(backoffMillis(attempt))
            } catch (_: Exception) {
                resetCachedClient()
                attempt += 1
                delay(backoffMillis(attempt))
            }
        }
    }

    /**
     * Events can be missed while the phone sleeps or the stream reconnects.
     * Poll the thread list as a safety net so a finished task still alerts.
     */
    private suspend fun pollCompletions(
        settings: SettingsStore,
        completions: CompletionNotificationTracker,
    ) {
        var attempt = 0
        while (scope.isActive) {
            delay(POLL_MS)
            if (isAppForeground()) continue
            try {
                checkCompletions(settings, completions)
                attempt = 0
            } catch (_: Exception) {
                resetCachedClient()
                attempt += 1
                delay(backoffMillis(attempt))
            }
        }
    }

    /**
     * Alert for every thread whose completion is newer than the last one seen.
     *
     * The thread status cannot be trusted here: the Bridge reports "idle" as soon
     * as any device reads the task, which is usually before the next poll runs.
     * `completed_at` is the only stable signal, so a finished task is detected
     * even when it is already idle by the time this snapshot arrives.
     */
    private suspend fun checkCompletions(
        settings: SettingsStore,
        completions: CompletionNotificationTracker,
    ) {
        val client = clientFor(settings)
        val threads = client.threads().threads
        if (!baselineReady) {
            // The first snapshot is history, not a completion. If the service
            // started while offline, establish the baseline on the first
            // successful poll instead of replaying it.
            val startedAt = System.currentTimeMillis() / 1000
            completions.seed(threads, beforeOrAt = startedAt)
            baselineReady = true
            return
        }
        threads.forEach { thread ->
            val completedAt = notificationCompletedAt(thread)
            if (completedAt <= 0) return@forEach
            val failed = thread.status == "failed"
            if (completions.shouldNotify(thread.thread_id, thread.turn_id, completedAt)) {
                notifyCompletion(thread.thread_id, failed, completedAt)
            }
        }
    }

    /**
     * CBDP carries `*_at` values in milliseconds while the rest of the app (and
     * the persisted watermarks) use epoch seconds. Normalise before comparing.
     */
    private fun epochSeconds(value: Long): Long =
        if (value > 100_000_000_000L) value / 1000 else value

    private fun isAppForeground(): Boolean {
        return AppForeground.visible
    }

    private fun notifyCompletion(threadId: String, failed: Boolean, completedAt: Long) {
        SettingsStore(this).recordPendingCompletion(
            threadId,
            if (failed) "failed" else "completed",
            completedAt,
        )
        postCompletionNotification(this, threadId, failed)
    }

    private fun cancelCompletion(threadId: String) {
        if (threadId.isBlank()) return
        NotificationManagerCompat.from(this).cancel(completionId(threadId))
    }

    companion object {
        const val EXTRA_THREAD_ID = "thread_id"

        fun start(context: Context) {
            val intent = Intent(context, CompletionNotificationService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, CompletionNotificationService::class.java))
        }

        // Tapping a completion notification re-arms the foreground listener.
        fun restoreListening(context: Context) {
            start(context)
        }

        /** Used by the settings screen to check the sound while the screen is off. */
        fun showTestNotification(context: Context): String {
            createChannels(context)
            val manager = context.getSystemService(NotificationManager::class.java)
            val channel = manager.getNotificationChannel(COMPLETION_CHANNEL)
            val audio = context.getSystemService(AudioManager::class.java)
            if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) {
                return "通知权限已关闭，请在通知设置中允许通知"
            }
            if (channel.importance < NotificationManager.IMPORTANCE_DEFAULT || channel.sound == null) {
                return "任务完成提醒渠道已静音，请在通知设置中开启声音"
            }
            if (audio.ringerMode != AudioManager.RINGER_MODE_NORMAL ||
                audio.getStreamVolume(AudioManager.STREAM_NOTIFICATION) == 0
            ) return "手机处于静音或振动模式，或通知音量为零"
            if (manager.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL) {
                return "勿扰模式可能阻止提醒声音，请检查手机勿扰设置"
            }
            if (channel.sound == RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION) &&
                RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_NOTIFICATION) == null
            ) return "系统默认通知铃声为静音，请在通知设置中选择铃声"
            postCompletionNotification(context, "", false)
            return "已发送测试通知；若仍无声音，请检查通知设置中的铃声及系统声音限制"
        }

        fun openNotificationSettings(context: Context) {
            createChannels(context)
            context.startActivity(Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                .putExtra(Settings.EXTRA_CHANNEL_ID, COMPLETION_CHANNEL)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}

private fun backoffMillis(attempt: Int): Long {
    val base = (2_000L shl attempt.coerceIn(0, 5)).coerceAtMost(60_000L)
    return base + Random.nextLong(0L, 1_000L)
}

private fun completionId(threadId: String): Int =
    5000 + Math.floorMod(threadId.hashCode(), 100000)

private fun postCompletionNotification(context: Context, threadId: String, failed: Boolean) {
    val intent = Intent(context, MainActivity::class.java)
        .putExtra(CompletionNotificationService.EXTRA_THREAD_ID, threadId)
        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    val pending = PendingIntent.getActivity(
        context,
        completionId(threadId),
        intent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
    val builder = NotificationCompat.Builder(context, COMPLETION_CHANNEL)
        .setSmallIcon(android.R.drawable.stat_notify_chat)
        .setContentTitle(if (failed) "Codex 任务失败" else "Codex 任务已完成")
        .setContentText(if (failed) "点开查看错误详情" else "点开查看结果")
        .setContentIntent(pending)
        .setAutoCancel(true)
        .setCategory(NotificationCompat.CATEGORY_STATUS)
        .setPriority(NotificationCompat.PRIORITY_HIGH)
    // Android 13+ requires POST_NOTIFICATIONS at runtime. MainActivity asks for
    // it, but the user can deny or revoke it later, and this path also runs from
    // a background service, so check before notifying and never let a rejected
    // permission crash the listener.
    if (android.os.Build.VERSION.SDK_INT >= 33 &&
        context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
        android.content.pm.PackageManager.PERMISSION_GRANTED
    ) {
        return
    }
    try {
        NotificationManagerCompat.from(context).notify(completionId(threadId), builder.build())
    } catch (_: SecurityException) {
        // Revoked between the check and the post; dropping the notification is
        // preferable to taking the foreground service down with it.
    }
}

private fun createChannels(context: Context) {
    val manager = context.getSystemService(NotificationManager::class.java)
    manager.createNotificationChannel(
        NotificationChannel(LISTEN_CHANNEL, "Codex 后台连接", NotificationManager.IMPORTANCE_LOW).apply {
            description = "保持 Codex 完成后台通知连接"
        },
    )
    // A new channel id is required because Android will not change the sound of
    // an existing channel after it has been created on the device. The system
    // channel owns the single sound/vibration event; do not also play a
    // Ringtone manually.
    manager.createNotificationChannel(
        NotificationChannel(COMPLETION_CHANNEL, "任务完成提醒", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Codex 任务完成提醒"
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 250, 150, 250)
            setSound(
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
                android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION)
                    .build(),
            )
        },
    )
}
