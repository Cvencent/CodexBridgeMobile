package com.codexbridge.mobile.data

import android.content.Context
import android.content.SharedPreferences
import java.util.UUID

class SettingsStore(context: Context) : CompletionNotificationState {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("codexbridge_mobile", Context.MODE_PRIVATE)

    var baseUrl: String
        get() = migrateLegacyPort(prefs.getString("base_url", "") ?: "")
        set(value) = prefs.edit().putString("base_url", value).apply()

    /**
     * LAN and WAN addresses are stored independently. The legacy base_url is
     * still the fallback for installations created before dual routing.
     */
    var lanUrl: String
        get() {
            val stored = migrateLegacyPort(prefs.getString("lan_url", "") ?: "")
            if (stored.isNotBlank()) return stored
            // A legacy single address predates the WAN field. Once a WAN
            // address exists, an empty LAN address must stay empty.
            return if (wanUrl.isBlank()) baseUrl else ""
        }
        set(value) = prefs.edit().putString("lan_url", value).apply()

    var wanUrl: String
        get() = migrateLegacyPort(prefs.getString("wan_url", "") ?: "")
        set(value) = prefs.edit().putString("wan_url", value).apply()

    var lastSuccessfulUrl: String
        get() = migrateLegacyPort(prefs.getString("last_successful_url", "") ?: "")
        set(value) = prefs.edit().putString("last_successful_url", value).apply()

    var token: String
        get() = prefs.getString("token", "") ?: ""
        set(value) = prefs.edit().putString("token", value).apply()

    var phoneId: String
        get() = prefs.getString("phone_id", "") ?: ""
        set(value) = prefs.edit().putString("phone_id", value).apply()

    /**
     * Stable identity for this app installation. Pairing may be renewed with
     * a new token, but the bridge can then update the existing phone record
     * instead of creating a second identical device.
     */
    val installationId: String
        get() {
            val existing = prefs.getString("installation_id", "") ?: ""
            if (existing.isNotBlank()) return existing
            // Upgrade path: the previous app version stored the bridge-assigned
            // phone_id but no installation identity. Reuse it once so the
            // bridge can claim the existing record instead of creating a twin.
            val legacyPhoneId = phoneId
            val generated = legacyPhoneId.ifBlank { UUID.randomUUID().toString() }
            prefs.edit().putString("installation_id", generated).commit()
            return generated
        }

    var bridgeName: String
        get() = prefs.getString("bridge_name", "") ?: ""
        set(value) = prefs.edit().putString("bridge_name", value).apply()

    var bridgeVersion: String
        get() = prefs.getString("bridge_version", "") ?: ""
        set(value) = prefs.edit().putString("bridge_version", value).apply()

    var selectedModel: String
        get() = prefs.getString("selected_model", "") ?: ""
        set(value) = prefs.edit().putString("selected_model", value).apply()

    var selectedEffort: String
        get() = prefs.getString("selected_effort", "") ?: ""
        set(value) = prefs.edit().putString("selected_effort", value).apply()

    var selectedCwd: String
        get() = prefs.getString("selected_cwd", "") ?: ""
        set(value) = prefs.edit().putString("selected_cwd", value).apply()

    var darkTheme: Boolean
        get() = prefs.getBoolean("dark_theme", true)
        set(value) = prefs.edit().putBoolean("dark_theme", value).apply()

    var discoveryPort: Int
        get() = prefs.getInt("discovery_port", ApiClient.DEFAULT_DISCOVERY_PORT)
        set(value) = prefs.edit().putInt("discovery_port", value).apply()

    /**
     * Bridge can emit completed rows without a completion timestamp. The user
     * opening the thread is still a read acknowledgement, so remember it until
     * Bridge reports a newer update for the same thread.
     */
    fun markThreadReadOverride(threadId: String, seenAt: Long) {
        if (threadId.isBlank() || seenAt <= 0) return
        prefs.edit().putLong("read_idle_override_$threadId", seenAt).apply()
    }

    fun threadReadOverrideAt(threadId: String): Long =
        if (threadId.isBlank()) 0L
        else prefs.getLong("read_idle_override_$threadId", 0L)

    override fun markCompletionSeen(threadId: String, notificationKey: String): Boolean {
        if (threadId.isBlank() || notificationKey.isBlank()) return false
        val key = "notified_$notificationKey"
        if (prefs.getBoolean(key, false)) return false
        prefs.edit().putBoolean(key, true).apply()
        return true
    }

    override fun completionWatermark(threadId: String): Long =
        if (threadId.isBlank()) 0L
        else prefs.getLong("completion_watermark_$threadId", 0L)

    override fun advanceCompletionWatermark(threadId: String, completedAt: Long) {
        if (threadId.isBlank() || completedAt <= completionWatermark(threadId)) return
        prefs.edit().putLong("completion_watermark_$threadId", completedAt).apply()
    }

    fun recordPendingCompletion(threadId: String, status: String, completedAt: Long) {
        if (threadId.isBlank() || status !in setOf("completed", "failed") || completedAt <= 0) return
        prefs.edit()
            .putString("pending_completion_status_$threadId", status)
            .putLong("pending_completion_at_$threadId", completedAt)
            .apply()
    }

    fun pendingCompletions(): List<PendingCompletion> =
        prefs.all.keys
            .asSequence()
            .filter { it.startsWith("pending_completion_status_") }
            .mapNotNull { key ->
                val threadId = key.removePrefix("pending_completion_status_")
                val status = prefs.getString(key, "").orEmpty()
                val completedAt = prefs.getLong("pending_completion_at_$threadId", 0L)
                if (
                    threadId.isBlank() ||
                    status !in setOf("completed", "failed") ||
                    completedAt <= 0
                ) null
                else PendingCompletion(threadId, status, completedAt)
            }
            .toList()

    fun clearPendingCompletion(threadId: String) {
        if (threadId.isBlank()) return
        prefs.edit()
            .remove("pending_completion_status_$threadId")
            .remove("pending_completion_at_$threadId")
            .apply()
    }

    val isPaired: Boolean
        get() = baseUrl.isNotBlank() && token.isNotBlank()

    var notificationWatermark: Long
        get() = prefs.getLong("notification_watermark", 0L)
        set(value) = prefs.edit().putLong("notification_watermark", value).apply()

    fun clearPairing() {
        val editor = prefs.edit().remove("token").remove("phone_id").remove("notification_watermark")
        prefs.all.keys
            .filter {
                it.startsWith("completion_seen_") ||
                    it.startsWith("completion_watermark_") ||
                    it.startsWith("pending_completion_") ||
                    it.startsWith("read_idle_override_") ||
                    it.startsWith("notified_")
            }
            .forEach(editor::remove)
        editor.apply()
    }

    fun clearAll() {
        prefs.edit().clear().apply()
    }

    private fun migrateLegacyPort(value: String): String =
        ApiClient.migrateStoredBase(value.trim())
}
