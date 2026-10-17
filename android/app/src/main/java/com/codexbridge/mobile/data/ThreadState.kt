package com.codexbridge.mobile.data

val ACTIVE_TASK_STATUSES = setOf("working", "waiting_user")

data class PendingCompletion(
    val threadId: String,
    val status: String,
    val completedAt: Long,
)

fun mergePendingCompletions(
    threads: List<ThreadSummary>,
    pending: List<PendingCompletion>,
): List<ThreadSummary> {
    val terminal = pending
        .filter { it.threadId.isNotBlank() && it.status in setOf("completed", "failed") }
        .associateBy { it.threadId }
    if (terminal.isEmpty()) return threads

    val existingIds = threads.mapTo(mutableSetOf()) { it.thread_id }
    val merged = threads.map { thread ->
        val completion = terminal[thread.thread_id] ?: return@map thread
        val newestKnown = maxOf(thread.completed_at, thread.updated_at, thread.recency_at)
        if (completion.completedAt < newestKnown) {
            thread
        } else {
            thread.copy(
                status = completion.status,
                completed_at = maxOf(thread.completed_at, completion.completedAt),
                updated_at = maxOf(thread.updated_at, completion.completedAt),
                recency_at = maxOf(thread.recency_at, completion.completedAt),
            )
        }
    }
    val missing = terminal.values
        .filterNot { it.threadId in existingIds }
        .map { completion ->
            ThreadSummary(
                thread_id = completion.threadId,
                status = completion.status,
                completed_at = completion.completedAt,
                updated_at = completion.completedAt,
                recency_at = completion.completedAt,
            )
        }
    return merged + missing
}

fun confirmedPendingCompletionIds(
    threads: List<ThreadSummary>,
    pending: List<PendingCompletion>,
): Set<String> {
    val byId = threads.associateBy { it.thread_id }
    return pending.mapNotNullTo(mutableSetOf()) { completion ->
        val thread = byId[completion.threadId] ?: return@mapNotNullTo null
        val newestKnown = maxOf(thread.completed_at, thread.updated_at, thread.recency_at)
        completion.threadId.takeIf {
            thread.status in setOf("completed", "failed") &&
                newestKnown >= completion.completedAt
        }
    }
}

/**
 * Keep one thread's UI state coherent while a detail request is in flight.
 *
 * Desktop turns can report a stale failed snapshot in a detail response even
 * while the live rollout says the turn is still running, which used to make the
 * phone flip from "working" to "failed" the moment a thread was opened. An
 * incoming active state always wins; a completed/idle result wins over a stale
 * active state; an incoming failure is ignored while the thread is still active.
 */
fun coherentTaskStatus(current: String, incoming: String): String {
    val left = current.lowercase()
    val right = incoming.lowercase()
    return when {
        right in ACTIVE_TASK_STATUSES -> right
        left in ACTIVE_TASK_STATUSES && right == "failed" -> left
        left in ACTIVE_TASK_STATUSES && (right == "completed" || right == "idle") -> right
        right.isNotBlank() -> right
        left.isNotBlank() -> left
        else -> right
    }
}

/** Map a `thread/status/changed` payload onto the task status vocabulary. */
fun threadStatusToTaskStatus(status: String, activeFlags: List<String>, current: String): String =
    when {
        status == "active" || activeFlags.isNotEmpty() -> "working"
        status == "idle" -> "idle"
        status == "completed" -> "completed"
        status == "failed" -> "failed"
        status == "notloaded" -> current.ifBlank { "idle" }
        else -> current
    }

fun applyThreadStatusEvent(current: String, status: String, activeFlags: List<String>): String =
    coherentTaskStatus(current, threadStatusToTaskStatus(status.lowercase(), activeFlags, current))

fun sortThreadsByActivity(threads: List<ThreadSummary>): List<ThreadSummary> =
    threads.sortedWith(
        compareBy<ThreadSummary> {
            when {
                it.status == "completed" || it.status == "failed" -> 0
                it.status in ACTIVE_TASK_STATUSES -> 1
                else -> 2
            }
        }
            .thenByDescending { maxOf(it.recency_at, it.updated_at) }
            .thenByDescending { it.thread_id },
    )

fun completionNotificationKey(threadId: String, turnId: String, completedAt: Long = 0): String =
    when {
        turnId.isNotBlank() -> "$threadId:turn-$turnId"
        completedAt > 0 -> "$threadId:at-$completedAt"
        else -> threadId
    }

interface CompletionNotificationState {
    fun completionWatermark(threadId: String): Long
    fun advanceCompletionWatermark(threadId: String, completedAt: Long)
    fun markCompletionSeen(threadId: String, notificationKey: String): Boolean
}

/**
 * Keep completion notifications monotonic per thread.
 *
 * The stream and the list poll can observe the same completion at different
 * moments, and the first list snapshot after a service restart contains every
 * historical finished task. Seed the watermark before listening, then accept
 * only completions newer than that watermark and only once.
 */
class CompletionNotificationTracker(private val state: CompletionNotificationState) {
    @Synchronized
    fun seed(threads: List<ThreadSummary>, beforeOrAt: Long = Long.MAX_VALUE) {
        threads.forEach { thread ->
            val completedAt = thread.completed_at
            if (
                completedAt > 0 &&
                thread.status in setOf("completed", "failed") &&
                completedAt <= beforeOrAt
            ) {
                state.advanceCompletionWatermark(thread.thread_id, completedAt)
                state.markCompletionSeen(
                    thread.thread_id,
                    completionNotificationKey(thread.thread_id, thread.turn_id, completedAt),
                )
            }
        }
    }

    @Synchronized
    fun shouldNotify(threadId: String, turnId: String, completedAt: Long): Boolean {
        if (threadId.isBlank()) return false
        val watermark = state.completionWatermark(threadId)
        if (completedAt > 0) {
            if (completedAt <= watermark) return false
        } else if (turnId.isBlank()) {
            return false
        }
        val unseen = state.markCompletionSeen(
            threadId,
            completionNotificationKey(threadId, turnId, completedAt),
        )
        if (completedAt > 0) {
            state.advanceCompletionWatermark(threadId, completedAt)
        }
        return unseen
    }
}
