package com.codexbridge.mobile

import android.app.Application
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.codexbridge.mobile.data.Account
import com.codexbridge.mobile.data.ApiClient
import com.codexbridge.mobile.data.ApiException
import com.codexbridge.mobile.data.BridgeCapabilities
import com.codexbridge.mobile.data.PendingAttachment
import com.codexbridge.mobile.data.ConnectionRoute
import com.codexbridge.mobile.data.ConnectionSelector
import com.codexbridge.mobile.data.cbdp.CbdpTag
import com.codexbridge.mobile.data.cbdp.CbdpTlv
import com.codexbridge.mobile.data.cbdp.u8
import com.codexbridge.mobile.data.cbdp.u32
import com.codexbridge.mobile.data.ApprovalRequest
import com.codexbridge.mobile.data.DiscoveredBridge
import com.codexbridge.mobile.data.Discovery
import com.codexbridge.mobile.data.MessageDetail
import com.codexbridge.mobile.data.MessageItem
import com.codexbridge.mobile.data.ModelInfo
import com.codexbridge.mobile.data.ProjectInfo
import com.codexbridge.mobile.data.QueueItem
import com.codexbridge.mobile.data.applyQueueSnapshot
import com.codexbridge.mobile.data.applyQueueUpdate
import com.codexbridge.mobile.data.SettingsStore
import com.codexbridge.mobile.data.StreamEvent
import com.codexbridge.mobile.data.ThreadDetailResponse
import com.codexbridge.mobile.data.TaskSnapshot
import com.codexbridge.mobile.data.ThreadSummary
import com.codexbridge.mobile.data.ACTIVE_TASK_STATUSES
import com.codexbridge.mobile.data.TERMINAL_TASK_STATUSES
import com.codexbridge.mobile.data.coherentTaskStatus
import com.codexbridge.mobile.data.applyThreadStatusEvent
import com.codexbridge.mobile.data.applyThreadRead
import com.codexbridge.mobile.data.normalizeUnconfirmedFailure
import com.codexbridge.mobile.data.confirmedPendingCompletionIds
import com.codexbridge.mobile.data.mergePendingCompletions
import com.codexbridge.mobile.data.sortThreadsByActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import java.util.UUID
import kotlin.random.Random

enum class AppScreen { Pair, Threads, Chat, Settings }

data class UiState(
    val screen: AppScreen = AppScreen.Pair,
    val baseUrl: String = "",
    val lanUrl: String = "",
    val wanUrl: String = "",
    val activeRouteRole: String = "",
    val bridgeName: String = "",
    val bridgeVersion: String = "",
    val phoneId: String = "",
    val paired: Boolean = false,
    val connected: Boolean = false,
    val busy: Boolean = false,
    // True while any task-list request is in flight. The list keeps this
    // mounted so the layout never jumps.
    val refreshing: Boolean = false,
    // True only for a refresh the user asked for, which is the only case that
    // shows a spinner. The 2s background poll would otherwise blink.
    val manualRefreshing: Boolean = false,
    val lastRefreshedAt: Long = 0,
    val error: String? = null,
    val notice: String? = null,
    val threads: List<ThreadSummary> = emptyList(),
    val models: List<ModelInfo> = emptyList(),
    val projects: List<ProjectInfo> = emptyList(),
    val selectedModel: String = "",
    val selectedEffort: String = "",
    val selectedCwd: String = "",
    val activeThread: ThreadSummary? = null,
    // Non-empty when replies to this task continue in a hidden copy because the
    // desktop currently holds the original thread.
    val messages: List<MessageItem> = emptyList(),
    val streaming: Map<String, String> = emptyMap(),
    val approvals: List<ApprovalRequest> = emptyList(),
    val task: TaskSnapshot = TaskSnapshot(),
    val account: Account = Account(),
    val working: Boolean = false,
    val search: String = "",
    val darkTheme: Boolean = true,
    val messageCount: Int = 0,
    val truncated: Boolean = false,
    val queue: List<QueueItem> = emptyList(),
    val queuePaused: Boolean = false,
    val queuePauseReason: String = "",
    val sending: Boolean = false,
    val canWrite: Boolean = false,
    val approvalMode: com.codexbridge.mobile.data.cbdp.ApprovalMode =
        com.codexbridge.mobile.data.cbdp.ApprovalMode.RequestApproval,
    val approvalModeChanging: Boolean = false,
    val bridgeCapabilities: BridgeCapabilities = BridgeCapabilities(),
    val pendingAttachments: List<PendingAttachment> = emptyList(),
    val ownerState: String = "",
    val leaseGeneration: Long = 0,
    val deviceRef: String = "",
    val activeThreadId: String = "",
    val activeDeviceId: String = "",
    val restoreInput: String = "",
    val discovered: List<DiscoveredBridge> = emptyList(),
    val discovering: Boolean = false,
    val discoveryPort: Int = ApiClient.DEFAULT_DISCOVERY_PORT,
) {
    val modelLabel: String
        get() = models.firstOrNull { it.slug == selectedModel || it.id == selectedModel }?.label
            ?: selectedModel.ifBlank { "默认模型" }

    val chatLoading: Boolean
        get() = busy && messages.isEmpty()

    val activeRouteLabel: String
        get() = when (activeRouteRole) {
            "wan" -> "外网"
            "lan" -> "内网"
            else -> ""
        }
}

private data class ReconciledThreadDetail(
    val thread: ThreadSummary,
    val task: TaskSnapshot,
)

class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val appContext: Application = application
    private val settings = SettingsStore(application)
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var api: ApiClient? = null
    private var routeRefreshJob: Job? = null
    private var streamJob: Job? = null
    private var screenStreamJob: Job? = null
    private var pollJob: Job? = null
    private var threadsJob: Job? = null
    private val refreshLock = Mutex()
    private var foreground = false
    private val failureConfirmations = mutableMapOf<String, Job>()

    fun setForeground(visible: Boolean) {
        if (!visible) {
            foreground = false
            threadsJob?.cancel()
            threadsJob = null
            screenStreamJob?.cancel()
            screenStreamJob = null
            streamJob?.cancel()
            streamJob = null
            pollJob?.cancel()
            pollJob = null
            routeRefreshJob?.cancel()
            routeRefreshJob = null
            return
        }
        foreground = visible
        val current = _state.value
        val mergedThreads = mergePendingThreads(current.threads)
        val mergedActive = current.activeThread?.let { active ->
            mergedThreads.firstOrNull { it.thread_id == active.thread_id } ?: active
        }
        _state.update { state ->
            state.copy(
                threads = mergedThreads,
                activeThread = mergedActive,
                task = if (mergedActive != null && state.task.thread_id == mergedActive.thread_id) {
                    state.task.copy(
                        status = mergedActive.status,
                        completed_at_epoch = maxOf(state.task.completed_at_epoch, mergedActive.completed_at),
                    )
                } else {
                    state.task
                },
                working = if (mergedActive != null) {
                    mergedActive.status in ACTIVE_TASK_STATUSES
                } else {
                    state.working
                },
            )
        }
        refreshConnectionRoute()
        when (_state.value.screen) {
            AppScreen.Threads -> startThreadsRefresh()
            AppScreen.Chat -> _state.value.activeThread?.thread_id?.takeIf { it.isNotBlank() }?.let(::observeThread)
            else -> Unit
        }
    }

    init {
        val base = settings.lastSuccessfulUrl.ifBlank { settings.baseUrl }
        val lanUrl = settings.lanUrl
        val wanUrl = settings.wanUrl
        val paired = settings.isPaired
        val pendingThreads = mergePendingThreads(emptyList())
        _state.update {
            it.copy(
                baseUrl = base,
                lanUrl = lanUrl,
                wanUrl = wanUrl,
                activeRouteRole = when {
                    base.isBlank() -> ""
                    base == lanUrl -> "lan"
                    base == wanUrl -> "wan"
                    else -> ""
                },
                bridgeName = settings.bridgeName,
                bridgeVersion = settings.bridgeVersion,
                phoneId = settings.phoneId,
                paired = paired,
                screen = if (paired) AppScreen.Threads else AppScreen.Pair,
                threads = pendingThreads,
                selectedModel = settings.selectedModel,
                selectedEffort = settings.selectedEffort,
                selectedCwd = settings.selectedCwd,
                darkTheme = settings.darkTheme,
                discoveryPort = settings.discoveryPort,
            )
        }
        if (paired) {
            api = ApiClient(base, settings.token, settings.phoneId)
            CompletionNotificationService.start(appContext)
            startThreadsRefresh()
            refreshConnectionRoute()
            refresh()
        }
    }

    fun refreshConnectionRoute() {
        val current = _state.value
        if (!foreground || !current.paired || routeRefreshJob?.isActive == true) return
        routeRefreshJob = viewModelScope.launch {
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
                val previous = _state.value.baseUrl
                settings.lastSuccessfulUrl = selected.url
                settings.baseUrl = selected.url
                val role = selected.endpoint.role.name.lowercase()
                if (previous != selected.url || api == null || _state.value.activeRouteRole != role) {
                    api = ApiClient(selected.url, settings.token, settings.phoneId)
                    _state.update { state ->
                        state.copy(
                            baseUrl = selected.url,
                            activeRouteRole = role,
                            connected = true,
                        )
                    }
                    val threadId = _state.value.activeThread?.thread_id
                    if (_state.value.screen == AppScreen.Chat && !threadId.isNullOrBlank()) {
                        observeThread(threadId)
                    }
                }
            }
        }
    }

    private fun isOnWifi(): Boolean {
        val manager = appContext.getSystemService(ConnectivityManager::class.java) ?: return true
        val network = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }

    // -------------------------------------------------------------- pairing
    fun discoverBridges() {
        if (_state.value.discovering) return
        _state.update { it.copy(discovering = true, error = null) }
        viewModelScope.launch {
            val found = Discovery.discover(
                discoveryPort = _state.value.discoveryPort,
                deviceId = settings.phoneId.ifBlank { settings.installationId },
            )
            _state.update {
                it.copy(
                    discovered = found,
                    discovering = false,
                    notice = if (found.isEmpty()) "没有发现电脑，请确认手机和电脑在同一 Wi-Fi" else null,
                )
            }
        }
    }

    fun useDiscoveredBridge(bridge: DiscoveredBridge) {
        val base = ApiClient.withPort(bridge.baseUrl, bridge.port)
        _state.update { it.copy(baseUrl = base, error = null) }
    }

    fun pair(lanUrl: String, wanUrl: String, code: String) {
        if (_state.value.busy) return
        val enteredLan = lanUrl.trim()
        val enteredWan = wanUrl.trim()
        val enteredCandidates = ConnectionRoute.candidates(
            lanUrl = enteredLan,
            wanUrl = enteredWan,
            onWifi = isOnWifi(),
        )
        _state.update { it.copy(busy = true, error = null, notice = null) }
        viewModelScope.launch {
            try {
                val paired = ConnectionSelector.select(enteredCandidates) { endpoint ->
                    val candidate = ApiClient(endpoint.url, phoneId = settings.phoneId.ifBlank { settings.installationId })
                    candidate.ping().takeIf { it.ok && it.service == "codexbridge-companion" }
                } ?: throw ApiException("无法连接电脑，请检查地址和网络")
                val client = ApiClient(paired.url, phoneId = settings.phoneId.ifBlank { settings.installationId })
                val ping = client.ping()
                val response = withContext(Dispatchers.IO) {
                    client.pair(code.trim(), phoneName(), settings.installationId)
                }
                if (!response.ok || response.token.isBlank()) {
                    throw ApiException("配对失败，请重新生成配对码")
                }
                val resolved = client
                val resolvedLan = if (enteredLan.isBlank()) "" else ConnectionRoute.endpointForConfiguredRoute(enteredLan)
                val resolvedWan = if (enteredWan.isBlank()) "" else ConnectionRoute.endpointForConfiguredRoute(enteredWan)
                val activeRole = if (resolvedLan.isNotBlank() && resolved.base == resolvedLan) "lan" else "wan"
                settings.baseUrl = resolved.base
                settings.lanUrl = resolvedLan
                settings.wanUrl = resolvedWan
                settings.lastSuccessfulUrl = resolved.base
                settings.token = response.token
                settings.phoneId = response.phone_id
                settings.bridgeName = response.bridge.name.ifBlank { ping.name }
                settings.bridgeVersion = response.bridge.version.ifBlank { ping.version }
                settings.phoneId = response.phone_id
                api = ApiClient(resolved.base, response.token, response.phone_id)
                _state.update {
                    it.copy(
                        busy = false,
                        paired = true,
                        baseUrl = resolved.base,
                        lanUrl = settings.lanUrl,
                        wanUrl = settings.wanUrl,
                        activeRouteRole = activeRole,
                        phoneId = response.phone_id,
                        bridgeName = response.bridge.name.ifBlank { ping.name },
                        bridgeVersion = response.bridge.version.ifBlank { ping.version },
                        connected = true,
                        screen = AppScreen.Threads,
                        notice = "已连接 ${response.bridge.name.ifBlank { ping.name }}",
                    )
                }
                CompletionNotificationService.start(appContext)
                refresh()
                startThreadsRefresh()
            } catch (error: Exception) {
                _state.update { it.copy(busy = false, error = friendly(error)) }
            }
        }
    }

    private fun phoneName(): String {
        val manufacturer = android.os.Build.MANUFACTURER.orEmpty()
        val model = android.os.Build.MODEL.orEmpty()
        return listOf(manufacturer, model).filter { it.isNotBlank() }.joinToString(" ").ifBlank { "Android" }
    }

    fun unpair() {
        CompletionNotificationService.stop(appContext)
        threadsJob?.cancel()
        threadsJob = null
        settings.clearPairing()
        closeThread()
        api = null
        _state.update {
            it.copy(
                paired = false,
                connected = false,
                screen = AppScreen.Pair,
                threads = emptyList(),
                messages = emptyList(),
                approvals = emptyList(),
                activeThread = null,
            )
        }
    }

    // -------------------------------------------------------------- refresh
    fun refresh() {
        val client = api ?: return
        if (!foreground) return
        _state.update { it.copy(manualRefreshing = true) }
        viewModelScope.launch {
            try {
                withTimeout(REFRESH_TIMEOUT_MS) {
                    val status = client.status()
                    _state.update {
                        it.copy(
                            connected = true,
                            account = status.account,
                            approvals = mergeApprovals(it.approvals, status.pending_approvals),
                            task = if (it.activeThread == null) status.task else it.task,
                            working = if (it.activeThread == null) status.task.isWorking else it.working,
                        )
                    }
                }
                if (!refreshThreads()) refreshConnectionRoute()
                loadProjects()
                loadModels()
            } catch (error: Exception) {
                handleError(error)
            } finally {
                _state.update { it.copy(manualRefreshing = false) }
            }
        }
    }

    fun refreshThreadsNow() {
        // Called on resume and when leaving a chat: refresh quietly so the
        // spinner only appears when the user actually taps 刷新.
        if (!foreground || _state.value.screen != AppScreen.Threads) return
        viewModelScope.launch {
            if (!refreshThreads()) refreshConnectionRoute()
            if (_state.value.models.isEmpty()) loadModels()
            if (_state.value.projects.isEmpty()) loadProjects()
        }
    }

    private fun startThreadsRefresh() {
        threadsJob?.cancel()
        if (!foreground || _state.value.screen != AppScreen.Threads) return
        startScreenStream()
        if (_state.value.models.isEmpty()) loadModels()
        if (_state.value.projects.isEmpty()) loadProjects()
        threadsJob = viewModelScope.launch {
            routeRefreshJob?.join()
            var attempt = 0
            while (foreground && _state.value.screen == AppScreen.Threads && api != null) {
                val ok = refreshThreads()
                if (ok) {
                    attempt = 0
                    delay(THREADS_REFRESH_MS)
                } else {
                    attempt += 1
                    if (foreground) refreshConnectionRoute()
                    delay(backoffMillis(attempt))
                }
            }
        }
    }

    private fun startScreenStream() {
        screenStreamJob?.cancel()
        if (!foreground || _state.value.screen != AppScreen.Threads) return
        screenStreamJob = viewModelScope.launch {
            routeRefreshJob?.join()
            var attempt = 0
            while (foreground && _state.value.screen == AppScreen.Threads && api != null) {
                val client = api ?: break
                var streamFailed = false
                try {
                    client.events().collect { event ->
                        if (event.type == "stream_error") {
                            streamFailed = true
                            _state.update { it.copy(connected = false) }
                        } else {
                            _state.update { it.copy(connected = true) }
                            applyEvent(event)
                        }
                    }
                } catch (_: Exception) {
                    streamFailed = true
                    _state.update { it.copy(connected = false) }
                }
                if (!foreground || _state.value.screen != AppScreen.Threads) break
                attempt = if (streamFailed || !_state.value.connected) {
                    refreshConnectionRoute()
                    attempt + 1
                } else {
                    1
                }
                delay(backoffMillis(attempt))
            }
        }
    }

    private suspend fun refreshThreads(): Boolean = refreshLock.withLock {
        val client = api ?: return@withLock false
        _state.update { it.copy(refreshing = true) }

        suspend fun fetchAndUpdate(): Boolean = runCatching {
            withTimeout(REFRESH_TIMEOUT_MS) { client.threads(_state.value.search) }
        }
            .fold(
                onSuccess = { response ->
                    val listed = response.threads.map { it.thread_id }.toSet()
                    // A phone-created thread stays invisible server-side until
                    // its first turn; keep showing it until the list catches up.
                    settings.pendingCreatedThreads().forEach { local ->
                        if (local.thread_id in listed) {
                            settings.clearPendingCreatedThread(local.thread_id)
                        }
                    }
                    val carried = settings.pendingCreatedThreads()
                        .filterNot { it.thread_id in listed }
                    val visible = mergePendingThreads(carried + response.threads, clearConfirmed = true)
                    val normalized = visible.map(::normalizeUnconfirmedFailure).map { thread ->
                        val readOverride = settings.threadReadOverrideAt(thread.thread_id)
                        if (
                            readOverride > 0 &&
                            thread.status in TERMINAL_TASK_STATUSES &&
                            maxOf(thread.updated_at, thread.completed_at, thread.recency_at) <= readOverride
                        ) {
                            thread.copy(status = "idle")
                        } else {
                            thread
                        }
                    }
                    _state.update { state ->
                        state.copy(
                            threads = normalized,
                            connected = true,
                            refreshing = false,
                            manualRefreshing = false,
                            lastRefreshedAt = System.currentTimeMillis(),
                        )
                    }
                    true
                },
                onFailure = {
                    _state.update { it.copy(refreshing = false, manualRefreshing = false, connected = false) }
                    false
                },
            )

        val ok = fetchAndUpdate()

        // The Bridge's app-server can return thread stubs with blank titles
        // right after a restart while it hydrates metadata from rollout logs.
        // Retry once so the user doesn't see a list of unnamed tasks.
        val threads = _state.value.threads
        if (ok && threads.isNotEmpty() && threads.all { it.title.isBlank() }) {
            delay(3000)
            fetchAndUpdate()
        }

        ok
    }

    private fun loadModels() {
        val client = api ?: return
        viewModelScope.launch {
            runCatching { client.models() }
                .onSuccess { response ->
                    val selected = _state.value.selectedModel
                    val chosen = response.models.firstOrNull { it.slug == selected || it.id == selected }
                        ?: response.models.firstOrNull { it.is_default }
                        ?: response.models.firstOrNull()
                    _state.update {
                        it.copy(
                            models = response.models,
                            selectedModel = chosen?.slug ?: selected,
                            selectedEffort = it.selectedEffort.ifBlank { chosen?.default_effort ?: "" },
                        )
                    }
                    chosen?.slug?.let { slug -> settings.selectedModel = slug }
                }
        }
    }

    private fun refreshAccount() {
        val client = api ?: return
        viewModelScope.launch {
            runCatching { client.account() }
                .onSuccess { account ->
                    _state.update { it.copy(account = account) }
                }
        }
    }

    private fun loadProjects() {
        val client = api ?: return
        viewModelScope.launch {
            runCatching { client.projects() }
                .onSuccess { response ->
                    val cwd = _state.value.selectedCwd.ifBlank { response.default_cwd }
                    _state.update { it.copy(projects = response.projects, selectedCwd = cwd) }
                    settings.selectedCwd = cwd
                }
        }
    }

    fun setSearch(value: String) {
        _state.update { it.copy(search = value) }
    }

    fun submitSearch() {
        val client = api ?: return
        viewModelScope.launch {
            runCatching { client.threads(_state.value.search) }
                .onSuccess { response ->
                    val visible = mergePendingThreads(response.threads)
                    _state.update {
                        it.copy(
                            threads = visible,
                        )
                    }
                }
                .onFailure { handleError(it) }
        }
    }
    // ------------------------------------------------------------- threads
    fun openThread(threadId: String) {
        val client = api ?: return
        threadsJob?.cancel()
        threadsJob = null
        screenStreamJob?.cancel()
        screenStreamJob = null
        val summary = _state.value.threads.firstOrNull { it.thread_id == threadId }
        val visibleSummary = summary ?: ThreadSummary(thread_id = threadId)
        val seededTask = _state.value.task.takeIf { it.thread_id == threadId }
            ?: TaskSnapshot(
                status = visibleSummary.status,
                thread_id = threadId,
                title = visibleSummary.title,
            )
        _state.update { state ->
            state.copy(
                screen = AppScreen.Chat,
                activeThread = visibleSummary,
                threads = state.threads,
                messages = emptyList(),
                streaming = emptyMap(),
                approvals = emptyList(),
                queue = emptyList(),
                queuePaused = false,
                queuePauseReason = "",
                busy = true,
                canWrite = false,
                ownerState = "",
                leaseGeneration = 0,
                activeThreadId = "",
                activeDeviceId = "",
                task = seededTask,
                working = seededTask.isWorking || visibleSummary.status in ACTIVE_TASK_STATUSES,
            )
        }
        viewModelScope.launch {
            try {
                val detail = client.thread(threadId)
                val reconciled = reconcileThreadDetail(
                    detail,
                    _state.value.activeThread?.status.orEmpty(),
                )
                val status = reconciled.thread.status
                _state.update { state ->
                    state.copy(
                        activeThread = reconciled.thread,
                        threads = state.threads.map { thread ->
                            if (thread.thread_id == threadId) reconciled.thread else thread
                        },
                        messages = detail.messages,
                        task = reconciled.task,
                        approvals = detail.pending_approvals,
                        working = status in ACTIVE_TASK_STATUSES,
                        messageCount = detail.message_count,
                        truncated = detail.truncated,
                        queue = applyQueueSnapshot(
                            state.queue,
                            threadId,
                            detail.queue.items,
                        ),
                        queuePaused = detail.queue.paused,
                        queuePauseReason = detail.queue.items.firstOrNull { it.error.isNotBlank() }?.error.orEmpty(),
                        busy = false,
                    )
                }
                // Mark only after the authoritative detail lands; otherwise a
                // slower detail snapshot can overwrite the read-induced idle
                // state with the stale completed row.
                    runCatching { client.markThreadRead(threadId) }.onSuccess { response ->
                    applyThreadReadState(threadId, response.status.ifBlank { "idle" })
                }
                observeThread(threadId)
                refreshWriterState(threadId)
                refreshBridgeCapabilities(threadId)
                refreshThreadSettings(threadId)
            } catch (error: Exception) {
                _state.update { it.copy(busy = false) }
                handleError(error)
            }
        }
    }

    fun closeThread() {
        streamJob?.cancel()
        streamJob = null
        pollJob?.cancel()
        pollJob = null
        _state.update {
            it.copy(
                screen = AppScreen.Threads,
                activeThread = null,
                messages = emptyList(),
                streaming = emptyMap(),
                queue = emptyList(),
                queuePaused = false,
                queuePauseReason = "",
                sending = false,
                canWrite = false,
                ownerState = "",
                leaseGeneration = 0,
                activeThreadId = "",
                activeDeviceId = "",
            )
        }
        if (foreground) startThreadsRefresh() else refreshThreadsNow()
    }

    private fun observeThread(threadId: String) {
        streamJob?.cancel()
        streamJob = viewModelScope.launch {
            routeRefreshJob?.join()
            var attempt = 0
            while (foreground && _state.value.screen == AppScreen.Chat &&
                _state.value.activeThread?.thread_id == threadId
            ) {
                val client = api ?: break
                var streamFailed = false
                try {
                    var topicId = _state.value.activeThread
                        ?.takeIf { it.thread_id == threadId }
                        ?.topic_id
                        ?.takeIf { it > 0 }
                    if (topicId == null) {
                        val detail = runCatching { client.thread(threadId) }.getOrNull()
                        topicId = detail?.thread?.topic_id?.takeIf { it > 0 }
                        if (topicId != null && detail != null) {
                            _state.update { state ->
                                state.copy(
                                    activeThread = if (state.activeThread?.thread_id == threadId) {
                                        detail.thread
                                    } else {
                                        state.activeThread
                                    },
                                    threads = state.threads.map { thread ->
                                        if (thread.thread_id == threadId) detail.thread else thread
                                    },
                                )
                            }
                        }
                    }
                    client.events(threadId, topicId).collect { event ->
                        if (event.type == "stream_error") {
                            streamFailed = true
                            _state.update { it.copy(connected = false) }
                        } else {
                            _state.update { it.copy(connected = true) }
                            applyEvent(event)
                        }
                    }
                } catch (_: Exception) {
                    streamFailed = true
                    _state.update { it.copy(connected = false) }
                }
                if (!foreground || _state.value.screen != AppScreen.Chat) break
                attempt = if (streamFailed || !_state.value.connected) {
                    refreshConnectionRoute()
                    attempt + 1
                } else {
                    1
                }
                delay(backoffMillis(attempt))
            }
        }
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            var attempt = 0
            while (foreground && _state.value.screen == AppScreen.Chat &&
                _state.value.activeThread?.thread_id == threadId
            ) {
                delay(THREAD_POLL_MS)
                val client = api ?: continue
                val detail = runCatching { client.thread(threadId) }.getOrNull()
                if (detail == null) {
                    attempt += 1
                    _state.update { it.copy(connected = false) }
                    refreshConnectionRoute()
                    delay(backoffMillis(attempt))
                    continue
                }
                attempt = 0
                runCatching {
                        val reconciled = reconcileThreadDetail(
                            detail,
                            _state.value.activeThread?.status.orEmpty(),
                        )
                        _state.update { state ->
                            state.copy(
                                activeThread = if (state.activeThread?.thread_id == threadId) reconciled.thread else state.activeThread,
                                threads = state.threads.map { thread ->
                                    if (thread.thread_id == threadId) reconciled.thread else thread
                                },
                                messages = detail.messages,
                                task = reconciled.task,
                                approvals = detail.pending_approvals,
                                working = reconciled.thread.status in ACTIVE_TASK_STATUSES,
                                messageCount = detail.message_count,
                                queue = applyQueueSnapshot(state.queue, threadId, detail.queue.items),
                            )
                        }
                    }
            }
        }
    }

    fun send(text: String) {
        val client = api ?: return
        val threadId = _state.value.activeThread?.thread_id ?: return
        val content = text.trim()
        val pending = _state.value.pendingAttachments
        if (content.isEmpty() && pending.isEmpty()) return
        if (!_state.value.canWrite) {
            _state.update { it.copy(error = "当前线程没有写入权限，请先接管线程") }
            return
        }
        settings.clearPendingCompletion(threadId)
        _state.update { it.copy(error = null, sending = true) }
        viewModelScope.launch {
            try {
                val uploaded = mutableListOf<com.codexbridge.mobile.data.UploadedAttachment>()
                for (item in pending) {
                    val existing = item.uploaded
                    if (existing != null) {
                        uploaded += existing
                        continue
                    }
                    val bytes = withContext(Dispatchers.IO) { readAttachmentBytes(item.uri) }
                    if (bytes == null) {
                        throw ApiException("无法读取附件：" + item.filename, "attachment_read_failed")
                    }
                    updateAttachment(item.localId) { it.copy(state = com.codexbridge.mobile.data.AttachmentState.Uploading) }
                    val result = client.uploadAttachment(
                        threadId = threadId,
                        kind = item.kind,
                        filename = item.filename,
                        mimeType = item.mimeType,
                        bytes = bytes,
                        onProgress = { done ->
                            updateAttachment(item.localId) { it.copy(uploadedBytes = done) }
                        },
                    )
                    uploaded += result
                    updateAttachment(item.localId) {
                        it.copy(state = com.codexbridge.mobile.data.AttachmentState.Uploaded, uploaded = result)
                    }
                }
                val parts = com.codexbridge.mobile.data.attachmentInputParts(content, uploaded)
                client.sendMessage(
                    threadId = threadId,
                    text = content,
                    model = _state.value.selectedModel,
                    effort = _state.value.selectedEffort,
                    inputParts = parts,
                    mode = if (_state.value.working) "steer" else "start",
                )
                _state.update { it.copy(pendingAttachments = emptyList()) }
                refreshQueue(threadId)
                runCatching { client.thread(threadId) }.onSuccess { detail ->
                    val status = coherentTaskStatus(
                        _state.value.activeThread?.status.orEmpty(),
                        detail.task.status,
                    )
                    _state.update { state ->
                        state.copy(
                            messages = detail.messages,
                            messageCount = detail.message_count,
                            task = detail.task.copy(status = status),
                            working = status in ACTIVE_TASK_STATUSES,
                        )
                    }
                }
            } catch (error: Exception) {
                _state.update { state ->
                    state.copy(
                        restoreInput = if (content.isNotEmpty()) content else state.restoreInput,
                        sending = false,
                        pendingAttachments = state.pendingAttachments.map { item ->
                            if (item.state == com.codexbridge.mobile.data.AttachmentState.Uploading) {
                                item.copy(state = com.codexbridge.mobile.data.AttachmentState.Failed)
                            } else {
                                item
                            }
                        },
                    )
                }
                handleError(error)
            }
        }
    }

    private fun updateAttachment(
        localId: String,
        transform: (com.codexbridge.mobile.data.PendingAttachment) -> com.codexbridge.mobile.data.PendingAttachment,
    ) {
        _state.update { state ->
            state.copy(
                pendingAttachments = state.pendingAttachments.map { item ->
                    if (item.localId == localId) transform(item) else item
                },
            )
        }
    }

    private fun readAttachmentBytes(uri: String): ByteArray? =
        runCatching {
            getApplication<Application>().contentResolver
                .openInputStream(android.net.Uri.parse(uri))
                ?.use { it.readBytes() }
        }.getOrNull()

    fun steerQueue(item: QueueItem) {
        val client = api ?: return
        val threadId = _state.value.activeThread?.thread_id ?: return
        if (!_state.value.canWrite) {
            _state.update { it.copy(error = "当前线程没有写入权限，请先接管线程") }
            return
        }
        viewModelScope.launch {
            runCatching { client.steerQueue(item.queue_id, item.revision) }
                .onSuccess { refreshQueue(threadId) }
                .onFailure { handleError(it) }
        }
    }

    fun editQueue(item: QueueItem, text: String) {
        val client = api ?: return
        val threadId = _state.value.activeThread?.thread_id ?: return
        if (!_state.value.canWrite) {
            _state.update { it.copy(error = "当前线程没有写入权限，请先接管线程") }
            return
        }
        val content = text.trim()
        if (content.isEmpty() && item.text.isBlank()) return
        val resolvedText = content.ifBlank { item.text }
        viewModelScope.launch {
            runCatching {
                client.editQueue(
                    queueId = item.queue_id,
                    text = resolvedText,
                    expectedRevision = item.revision,
                    model = _state.value.selectedModel,
                    effort = _state.value.selectedEffort,
                )
            }
                .onSuccess { refreshQueue(threadId) }
                .onFailure { handleError(it) }
        }
    }

    fun deleteQueue(item: QueueItem) {
        val client = api ?: return
        val threadId = _state.value.activeThread?.thread_id ?: return
        if (!_state.value.canWrite) {
            _state.update { it.copy(error = "当前线程没有写入权限，请先接管线程") }
            return
        }
        viewModelScope.launch {
            runCatching { client.deleteQueue(item.queue_id, item.revision) }
                .onSuccess { refreshQueue(threadId) }
                .onFailure { handleError(it) }
        }
    }

    private fun refreshQueue(threadId: String) {
        val client = api ?: return
        viewModelScope.launch {
            runCatching { client.queue(threadId) }
                .onSuccess { response ->
                    _state.update { state ->
                        state.copy(
                            queue = applyQueueSnapshot(
                                state.queue,
                                threadId,
                                response.queue,
                            ),
                            queuePaused = response.paused,
                            queuePauseReason = response.queue.firstOrNull { it.error.isNotBlank() }?.error.orEmpty(),
                            sending = false,
                        )
                    }
                }
                .onFailure {
                    _state.update { state -> state.copy(sending = false) }
                    handleError(it)
                }
        }
    }

    fun claimThread(force: Boolean = false) {
        val client = api ?: return
        val threadId = _state.value.activeThread?.thread_id ?: return
        viewModelScope.launch {
            runCatching {
                client.claimThread(threadId, force)
            }.onSuccess {
                refreshWriterState(threadId)
            }.onFailure { handleError(it) }
        }
    }

    fun releaseThread() {
        val client = api ?: return
        val threadId = _state.value.activeThread?.thread_id ?: return
        val lease = _state.value.leaseGeneration
        if (lease <= 0) return
        viewModelScope.launch {
            runCatching {
                client.releaseThread(threadId, lease)
            }.onSuccess {
                refreshWriterState(threadId)
            }.onFailure { handleError(it) }
        }
    }

    private fun refreshWriterState(threadId: String) {
        val client = api ?: return
        viewModelScope.launch {
            if (!client.supportsWriterLease) {
                _state.update { state ->
                    if (state.activeThread?.thread_id != threadId) {
                        state
                    } else {
                        state.copy(
                            canWrite = true,
                            ownerState = "legacy",
                            leaseGeneration = 0,
                            activeDeviceId = "",
                        )
                    }
                }
                return@launch
            }
            val currentDevice = runCatching { client.deviceRef() }.getOrDefault("")
            val owner = runCatching {
                client.thread(threadId).thread
            }.getOrNull()
            if (currentDevice.isNotBlank()) {
                _state.update { it.copy(deviceRef = currentDevice) }
            }
            if (owner == null) return@launch
            applyWriterState(
                threadId = threadId,
                ownerState = owner.owner_state,
                leaseGeneration = owner.lease_generation,
                activeDeviceId = owner.owner_device_ref,
            )
        }
    }

    private fun applyWriterState(
        threadId: String,
        ownerState: String,
        leaseGeneration: Long,
        activeDeviceId: String,
    ) {
        val normalized = ownerState.lowercase()
        val currentThread = _state.value.activeThread?.thread_id.orEmpty()
        val matchesThread = threadId.isBlank() || currentThread.isBlank() || threadId == currentThread
        val currentDevice = _state.value.deviceRef.ifBlank { "" }
        val ownerMatches = activeDeviceId.isNotBlank() && currentDevice.isNotBlank() && activeDeviceId == currentDevice
        // CodexBridge does not publish a writer lease on the CBDP path yet:
        // owner_state / owner_device_ref arrive empty. Treat "no lease announced"
        // as writable so a task the phone just created is usable without the
        // manual "接管线程" step. An explicit claimed/managed lease still has to
        // name this device.
        val leaseAnnounced = normalized.isNotBlank() && normalized != "unmanaged"
        val writable = if (leaseAnnounced) {
            normalized in setOf("claimed", "managed") && ownerMatches
        } else {
            true
        }
        _state.update { state ->
            if (!matchesThread) return@update state
            state.copy(
                canWrite = writable,
                ownerState = ownerState,
                leaseGeneration = leaseGeneration,
                activeDeviceId = activeDeviceId,
            )
        }
    }

    fun stop() {
        val client = api ?: return
        val threadId = _state.value.activeThread?.thread_id ?: return
        if (!_state.value.canWrite) {
            _state.update { it.copy(error = "当前线程没有写入权限，请先接管线程") }
            return
        }
        viewModelScope.launch {
            runCatching { client.interrupt(threadId, _state.value.task.turn_id) }
                .onSuccess { _state.update { it.copy(working = false, notice = "已请求中断") } }
                .onFailure { handleError(it) }
        }
    }

    fun newThread(cwd: String, model: String) {
        val client = api ?: return
        if (_state.value.busy) return
        // The create-thread dialog also owns the project picker, so keep its (cwd, model)
        // order here. The previous (model, cwd) order silently sent the project path as
        // the model name, which made the desktop reject the new task.
        val projectCwd = cwd.trim()
        val safeModel = model.takeIf { it.isNotBlank() && !it.contains("\\") && !Regex("^[A-Za-z]:[\\/]").containsMatchIn(it) }.orEmpty()
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                val created = client.createThread(projectCwd, safeModel)
                val thread = created.thread
                settings.selectedCwd = projectCwd
                if (safeModel.isNotBlank()) {
                    settings.selectedModel = safeModel
                }
                // The Bridge cannot list a phone-created thread until its first
                // turn writes the rollout file, so remember it locally or the
                // next list refresh makes it vanish.
                settings.recordPendingCreatedThread(thread)
                val notice = if (created.desktop_handoff == "pending") {
                    "新会话已创建；电脑端暂未接管，发出第一条消息后会自动同步"
                } else {
                    null
                }
                _state.update {
                    it.copy(
                        busy = false,
                        selectedModel = safeModel.ifBlank { it.selectedModel },
                        selectedCwd = projectCwd,
                        notice = notice,
                        threads = listOf(thread) + it.threads.filterNot { row -> row.thread_id == thread.thread_id },
                    )
                }
                openThread(thread.thread_id)
            } catch (error: Exception) {
                _state.update { it.copy(busy = false) }
                handleError(error)
            }
        }
    }

    fun resolveApproval(requestId: Int, decision: String) {
        val client = api ?: return
        viewModelScope.launch {
            runCatching { client.resolveApproval(requestId, decision) }
                .onSuccess {
                    _state.update { state ->
                        state.copy(approvals = state.approvals.filterNot { it.request_id == requestId })
                    }
                }
                .onFailure { handleError(it) }
        }
    }

    fun respondApproval(requestId: Int, response: kotlinx.serialization.json.JsonObject) {
        val client = api ?: return
        viewModelScope.launch {
            runCatching { client.respondApproval(requestId, response) }
                .onSuccess { result ->
                    if (result.resolved) {
                        _state.update { state ->
                            state.copy(approvals = state.approvals.filterNot { it.request_id == requestId })
                        }
                    }
                }
                .onFailure { handleError(it) }
        }
    }

    fun addAttachments(items: List<com.codexbridge.mobile.data.PendingAttachment>) {
        if (items.isEmpty()) return
        if (!_state.value.bridgeCapabilities.attachments) {
            _state.update { it.copy(error = "电脑端暂不支持附件，请更新电脑端") }
            return
        }
        _state.update { state ->
            val existing = state.pendingAttachments
            val room = (com.codexbridge.mobile.data.MAX_ATTACHMENTS_PER_THREAD - existing.size)
                .coerceAtLeast(0)
            state.copy(pendingAttachments = existing + items.take(room))
        }
    }

    fun addPickedAttachments(uris: List<android.net.Uri>) {
        if (uris.isEmpty()) return
        val resolver = getApplication<Application>().contentResolver
        val added = uris.mapNotNull { uri ->
            runCatching {
                val mime = resolver.getType(uri) ?: "application/octet-stream"
                var name = ""
                var size = 0L
                resolver.query(uri, null, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                        val sizeIndex = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
                        if (nameIndex >= 0) name = cursor.getString(nameIndex).orEmpty()
                        if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex)
                    }
                }
                if (size <= 0) {
                    size = resolver.openInputStream(uri)?.use { it.available().toLong() } ?: 0L
                }
                if (size > com.codexbridge.mobile.data.MAX_ATTACHMENT_BYTES) return@runCatching null
                val kind = if (mime.startsWith("image/")) "image" else "file"
                com.codexbridge.mobile.data.PendingAttachment(
                    localId = java.util.UUID.randomUUID().toString(),
                    uri = uri.toString(),
                    filename = name.ifBlank { "附件" },
                    mimeType = mime,
                    kind = kind,
                    byteSize = size,
                )
            }.getOrNull()
        }
        if (added.isEmpty()) {
            _state.update { it.copy(error = "没有可发送的附件，或附件超过 32 MiB") }
            return
        }
        if (!_state.value.bridgeCapabilities.attachments) {
            _state.update { it.copy(error = "电脑端暂不支持附件，请更新电脑端") }
            return
        }
        _state.update { state ->
            val room = (com.codexbridge.mobile.data.MAX_ATTACHMENTS_PER_THREAD - state.pendingAttachments.size)
                .coerceAtLeast(0)
            state.copy(pendingAttachments = state.pendingAttachments + added.take(room))
        }
    }

    fun removeAttachment(localId: String) {
        _state.update { state ->
            state.copy(pendingAttachments = state.pendingAttachments.filterNot { it.localId == localId })
        }
    }

    fun setApprovalMode(mode: com.codexbridge.mobile.data.cbdp.ApprovalMode) {
        val client = api ?: return
        val threadId = _state.value.activeThread?.thread_id ?: return
        if (!_state.value.bridgeCapabilities.threadSettings) {
            _state.update { it.copy(error = "电脑端版本较旧，暂不支持切换审批模式") }
            return
        }
        if (!_state.value.canWrite) {
            _state.update { it.copy(error = "当前线程没有写入权限，请先接管线程") }
            return
        }
        val previous = _state.value.approvalMode
        if (previous == mode) return
        _state.update { it.copy(approvalModeChanging = true, error = null) }
        viewModelScope.launch {
            runCatching {
                client.updateThreadSettings(
                    threadId = threadId,
                    approvalPolicy = mode.approvalPolicy,
                    sandboxPolicy = mode.sandboxPolicy,
                )
            }.onSuccess {
                val message = if (_state.value.working) {
                    "已切换为" + mode.label + "；运行中的任务下一轮生效"
                } else {
                    "已切换为" + mode.label
                }
                _state.update {
                    it.copy(
                        approvalMode = mode,
                        approvalModeChanging = false,
                        notice = message,
                    )
                }
            }.onFailure { error ->
                // A bridge can advertise thread.settings.update yet still have
                // the app-server reject it (experimentalApi). Drop the
                // capability so the switch stops being offered, instead of
                // leaving a control that always fails.
                val text = (error as? ApiException)?.message.orEmpty()
                // Only a genuinely unsupported bridge hides the control.
                // "thread not found" means this particular thread is owned by
                // the desktop and the bridge can't route settings to it yet;
                // other threads on the same bridge still work.
                val unsupported = text.contains("experimentalApi", ignoreCase = true) ||
                    text.contains("requires experimentalApi", ignoreCase = true) ||
                    text.contains("thread/settings/update", ignoreCase = true) &&
                    text.contains("unsupported", ignoreCase = true)
                val desktopOwned = text.contains("thread not found", ignoreCase = true)
                _state.update {
                    it.copy(
                        approvalMode = previous,
                        approvalModeChanging = false,
                        bridgeCapabilities = if (unsupported) {
                            it.bridgeCapabilities.copy(threadSettings = false)
                        } else {
                            it.bridgeCapabilities
                        },
                    )
                }
                if (unsupported) {
                    _state.update {
                        it.copy(error = "当前电脑端暂不支持切换审批模式，请更新电脑端")
                    }
                } else if (desktopOwned) {
                    _state.update {
                        it.copy(error = "这个会话由桌面端持有，暂时无法从手机切换审批模式；手机新建的会话可以")
                    }
                } else {
                    handleError(error)
                }
            }
        }
    }

    fun selectModel(model: String, effort: String = "") {
        settings.selectedModel = model
        settings.selectedEffort = effort
        _state.update { it.copy(selectedModel = model, selectedEffort = effort) }
    }

    fun selectEffort(effort: String) {
        settings.selectedEffort = effort
        _state.update { it.copy(selectedEffort = effort) }
    }

    fun selectProject(cwd: String) {
        settings.selectedCwd = cwd
        _state.update { it.copy(selectedCwd = cwd) }
    }

    fun openSettings() {
        threadsJob?.cancel()
        threadsJob = null
        screenStreamJob?.cancel()
        screenStreamJob = null
        _state.update { it.copy(screen = AppScreen.Settings) }
    }

    fun closeSettings() {
        _state.update { it.copy(screen = if (it.activeThread != null) AppScreen.Chat else AppScreen.Threads) }
        if (foreground && _state.value.activeThread == null) startThreadsRefresh()
    }

    fun setDarkTheme(enabled: Boolean) {
        settings.darkTheme = enabled
        _state.update { it.copy(darkTheme = enabled) }
    }

    fun updateConnectionRoutes(lanUrl: String, wanUrl: String) {
        val requestedLan = lanUrl.trim()
        val requestedWan = wanUrl.trim()
        if (requestedLan.isBlank() && requestedWan.isBlank()) {
            _state.update { it.copy(error = "内网地址和外网地址至少要填写一个") }
            return
        }
        val normalizedLan = if (requestedLan.isBlank()) "" else runCatching {
            ApiClient.normalizeBase(requestedLan)
        }.getOrElse { settings.lanUrl }
        val normalizedWan = if (requestedWan.isBlank()) "" else runCatching {
            ApiClient.normalizeBase(requestedWan)
        }.getOrElse { settings.wanUrl }
        settings.lanUrl = normalizedLan
        settings.wanUrl = normalizedWan
        _state.update { it.copy(lanUrl = normalizedLan, wanUrl = normalizedWan, error = null, notice = "连接地址已保存") }
        refreshConnectionRoute()
    }

    fun setDiscoveryPort(port: Int) {
        if (port !in 1..65535) return
        settings.discoveryPort = port
        _state.update { it.copy(discoveryPort = port) }
    }

    fun dismissError() {
        _state.update { it.copy(error = null) }
    }

    fun dismissNotice() {
        _state.update { it.copy(notice = null) }
    }

    fun consumeRestoreInput() {
        _state.update { it.copy(restoreInput = "") }
    }

    // -------------------------------------------------------------- events
    private fun applyEvent(event: StreamEvent) {
        try {
            applyEventBody(event)
        } finally {
            if (event.cbdpTopicId > 0 && event.cbdpCursor > 0) {
                viewModelScope.launch {
                    runCatching { api?.acknowledgeEvent(event) }
                }
            }
        }
    }

    private fun applyEventBody(event: StreamEvent) {
        when (event.type) {
            "agent_delta" -> {
                if (event.item_id.isBlank()) return
                _state.update { state ->
                    val current = state.streaming[event.item_id].orEmpty()
                    state.copy(streaming = state.streaming + (event.item_id to current + event.delta))
                }
            }
            "item_started", "item_completed" -> {
                val item = event.item ?: return
                val resolved = if (item.kind == "agent" && item.text.isBlank()) {
                    item.copy(text = _state.value.streaming[item.id].orEmpty())
                } else {
                    item
                }
                _state.update { state ->
                    val base = if (resolved.kind == "user") {
                        state.messages.filterNot { it.id.startsWith("local-") && it.text == resolved.text }
                    } else {
                        state.messages
                    }
                    state.copy(
                        messages = upsert(base, resolved),
                        streaming = state.streaming - item.id,
                        messageCount = state.messageCount + (if (base.any { it.id == resolved.id }) 0 else 1),
                    )
                }
            }
            "turn_started" -> {
                val threadId = event.thread_id.ifBlank { _state.value.activeThread?.thread_id.orEmpty() }
                if (threadId.isNotBlank()) settings.clearPendingCompletion(threadId)
                cancelTurnFailureConfirmation(threadId)
                _state.update { it.copy(working = true) }
            }
            "turn_completed" -> {
                val threadId = event.thread_id.ifBlank { _state.value.activeThread?.thread_id.orEmpty() }
                val completionStatus = if (event.status == "failed") "failed" else "completed"
                val completedAt = event.completed_at.takeIf { it > 0 } ?: System.currentTimeMillis() / 1000
                if (completionStatus == "failed") {
                    // Model/transport retries can surface a transient failure
                    // event. Confirm against thread state before retiring a
                    // task that may still be running.
                    confirmTurnFailure(threadId, completedAt, event.error_message)
                    return
                }
                cancelTurnFailureConfirmation(threadId)
                val viewing = foreground && _state.value.screen == AppScreen.Chat && _state.value.activeThread?.thread_id == threadId
                if (viewing && threadId.isNotBlank() && !event.read) {
                    viewModelScope.launch { runCatching { api?.markThreadRead(threadId) } }
                }
                val visibleStatus = if (event.read || viewing) "idle" else completionStatus
                _state.update { state ->
                    state.copy(
                        working = if (state.activeThread?.thread_id == threadId) {
                            visibleStatus in ACTIVE_TASK_STATUSES
                        } else {
                            state.working
                        },
                        threads = sortThreadsByActivity(
                            state.threads.map { thread ->
                                if (thread.thread_id == threadId) {
                                    thread.copy(status = visibleStatus, completed_at = completedAt)
                                } else {
                                    thread
                                }
                            },
                        ),
                        activeThread = state.activeThread?.let { thread ->
                            if (thread.thread_id == threadId) {
                                thread.copy(status = visibleStatus, completed_at = completedAt)
                            } else {
                                thread
                            }
                        },
                        task = if (state.activeThread?.thread_id == threadId) {
                            state.task.copy(status = visibleStatus, errorMessage = event.error_message)
                        } else {
                            state.task
                        },
                    )
                }
                if (!threadId.isNullOrBlank()) {
                    api?.let { client ->
                        viewModelScope.launch {
                            runCatching { client.thread(threadId) }.onSuccess { detail ->
                                val status = coherentTaskStatus(
                                    _state.value.activeThread?.status.orEmpty(),
                                    detail.task.status,
                                )
                                val visibleThread = detail.thread.copy(status = status)
                                val visibleTask = detail.task.copy(status = status)
                                _state.update { state ->
                                    state.copy(
                                        activeThread = if (state.activeThread?.thread_id == threadId) visibleThread else state.activeThread,
                                        threads = state.threads.map { thread ->
                                            if (thread.thread_id == threadId) visibleThread else thread
                                        },
                                        messages = detail.messages,
                                        task = visibleTask,
                                        approvals = detail.pending_approvals,
                                        working = status in ACTIVE_TASK_STATUSES,
                                        messageCount = detail.message_count,
                                    )
                                }
                            }
                            refresh()
                        }
                    }
                }
            }
            "turn_interrupted" -> _state.update { it.copy(working = false) }
            "turn_steered" -> Unit
            "thread_status" -> {
                val threadId = event.thread_id
                if (event.status == "deleted") {
                    _state.update { state ->
                        val removedActive = state.activeThread?.thread_id == threadId
                        state.copy(
                            threads = state.threads.filterNot { it.thread_id == threadId },
                            activeThread = state.activeThread?.takeUnless { it.thread_id == threadId },
                            messages = if (removedActive) emptyList() else state.messages,
                            queue = if (removedActive) emptyList() else state.queue,
                            messageCount = if (removedActive) 0 else state.messageCount,
                            working = if (removedActive) false else state.working,
                        )
                    }
                    if (threadId.isNotBlank()) refreshThreadsNow()
                    return
                }
                if (
                    threadId.isNotBlank() &&
                    (event.status == "active" || event.status == "idle" || event.active_flags.isNotEmpty())
                ) {
                    settings.clearPendingCompletion(threadId)
                }
                if (event.status == "active" || event.active_flags.isNotEmpty()) {
                    cancelTurnFailureConfirmation(threadId)
                }
                _state.update { state ->
                    val activeMatches = threadId.isBlank() || state.activeThread?.thread_id == threadId
                    val current = when {
                        state.activeThread?.thread_id == threadId -> state.activeThread.status
                        state.task.thread_id == threadId -> state.task.status
                        else -> state.threads.firstOrNull { it.thread_id == threadId }?.status.orEmpty()
                    }
                    val status = applyThreadStatusEvent(current, event.status, event.active_flags)
                    state.copy(
                        activeThread = state.activeThread?.let { thread ->
                            if (thread.thread_id == threadId || threadId.isBlank()) thread.copy(status = status) else thread
                        },
                        threads = sortThreadsByActivity(
                            state.threads.map { thread ->
                                if (thread.thread_id == threadId || threadId.isBlank()) thread.copy(status = status) else thread
                            },
                        ),
                        task = if (activeMatches) state.task.copy(status = status) else state.task,
                        working = if (activeMatches) status in ACTIVE_TASK_STATUSES else state.working,
                    )
                }
            }
            "token_usage" -> _state.update {
                it.copy(task = it.task.copy(tokenCount = event.total_tokens, tokenCountAvailable = event.total_tokens > 0))
            }
            "account_changed" -> refreshAccount()
            "writer_state" -> {
                val threadId = event.thread_id.ifBlank { _state.value.activeThread?.thread_id.orEmpty() }
                applyWriterState(
                    threadId = threadId,
                    ownerState = event.owner_state,
                    leaseGeneration = event.lease_generation,
                    activeDeviceId = event.active_device_id,
                )
            }
            "active_state" -> _state.update {
                it.copy(
                    activeThreadId = event.active_thread_id,
                    activeDeviceId = event.active_device_id,
                )
            }
            "models_changed" -> loadModels()
            "projects_changed" -> loadProjects()
            "message_deleted" -> {
                if (event.item_id.isBlank()) return
                _state.update { state ->
                    val remaining = state.messages.filterNot { it.id == event.item_id }
                    state.copy(
                        messages = remaining,
                        messageCount = remaining.size,
                        streaming = state.streaming - event.item_id,
                    )
                }
            }
            "thread_name" -> {
                val title = event.title
                if (title.isNotBlank()) {
                    _state.update { it.copy(activeThread = it.activeThread?.copy(title = title)) }
                }
            }
            "queue_snapshot" -> {
                val threadId = event.thread_id.ifBlank {
                    _state.value.activeThread?.thread_id.orEmpty()
                }
                if (threadId.isNotBlank()) {
                    _state.update { state ->
                        state.copy(
                            queue = applyQueueSnapshot(state.queue, threadId, event.queue)
                        )
                    }
                }
            }
            "queue_item_added",
            "queue_item_updated",
            "queue_item_removed" -> {
                val item = event.queueItem
                val threadId = item?.thread_id
                    ?: event.thread_id.ifBlank { _state.value.activeThread?.thread_id.orEmpty() }
                if (item != null && threadId.isNotBlank()) {
                    _state.update { state ->
                        state.copy(
                            queue = applyQueueUpdate(
                                state.queue,
                                threadId,
                                item.copy(thread_id = threadId),
                                removed = event.type == "queue_item_removed",
                            )
                        )
                    }
                } else if (threadId.isNotBlank()) {
                    refreshQueue(threadId)
                }
            }
            "queue_paused" -> {
                val threadId = event.thread_id.ifBlank {
                    _state.value.activeThread?.thread_id.orEmpty()
                }
                if (threadId.isNotBlank()) {
                    _state.update { it.copy(queuePaused = true, queuePauseReason = event.message) }
                    refreshQueue(threadId)
                }
            }
            "resync_required" -> {
                // The desktop side reconnected, so the local view is only a
                // guess until the authoritative snapshot lands.
                val threadId = _state.value.activeThread?.thread_id.orEmpty()
                if (threadId.isNotBlank()) {
                    refreshQueue(threadId)
                    viewModelScope.launch {
                        val active = api ?: return@launch
                        runCatching { active.thread(threadId) }.onSuccess { detail ->
                            val reconciled = reconcileThreadDetail(
                                detail,
                                _state.value.activeThread?.status.orEmpty(),
                            )
                            _state.update { state ->
                                state.copy(
                                    activeThread = if (state.activeThread?.thread_id == threadId) {
                                        reconciled.thread
                                    } else {
                                        state.activeThread
                                    },
                                    messages = detail.messages,
                                    task = reconciled.task,
                                    approvals = detail.pending_approvals,
                                    working = reconciled.thread.status in ACTIVE_TASK_STATUSES,
                                    messageCount = detail.message_count,
                                    queue = applyQueueSnapshot(state.queue, threadId, detail.queue.items),
                                )
                            }
                        }
                    }
                } else {
                    refreshThreadsNow()
                }
            }
            "approval_request" -> {
                val approval = ApprovalRequest(
                    request_id = event.request_id,
                    kind = event.kind,
                    thread_id = event.thread_id,
                    turn_id = event.turn_id,
                    item_id = event.item_id,
                    command = event.command,
                    cwd = event.cwd,
                    reason = event.reason,
                    created_at = event.created_at,
                    expires_at = event.expires_at,
                    raw = event.raw,
                )
                _state.update { state ->
                    state.copy(
                        working = true,
                        approvals = state.approvals.filterNot { it.request_id == approval.request_id } + approval,
                    )
                }
            }
            "approval_resolved" -> _state.update { state ->
                state.copy(approvals = state.approvals.filterNot { it.request_id == event.request_id })
            }
            "error" -> if (event.message.isNotBlank()) {
                _state.update { it.copy(error = event.message) }
            }
        }
    }

    private fun mergePendingThreads(
        threads: List<ThreadSummary>,
        clearConfirmed: Boolean = false,
    ): List<ThreadSummary> {
        val pending = settings.pendingCompletions()
        val confirmed = if (clearConfirmed) {
            confirmedPendingCompletionIds(threads, pending)
        } else {
            emptySet()
        }
        confirmed.forEach(settings::clearPendingCompletion)
        val remaining = pending.filterNot { it.threadId in confirmed }
        return sortThreadsByActivity(mergePendingCompletions(threads, remaining))
    }

    private fun reconcileThreadDetail(
        detail: com.codexbridge.mobile.data.ThreadDetailResponse,
        currentStatus: String,
    ): ReconciledThreadDetail {
        val unconfirmedFailure =
            detail.task.status == "failed" &&
                detail.thread.completed_at <= 0 &&
                detail.task.completed_at_epoch <= 0 &&
                detail.thread.turn_id.isNotBlank() &&
                detail.thread.error_message.isBlank()
        val normalizedDetail = if (unconfirmedFailure) {
            detail.copy(
                thread = detail.thread.copy(status = "working"),
                task = detail.task.copy(status = "working"),
            )
        } else {
            detail
        }
        val serverStatus = coherentTaskStatus(
            currentStatus,
            normalizedDetail.task.status.ifBlank { normalizedDetail.thread.status },
        )
        val completedAt = maxOf(
            normalizedDetail.thread.completed_at,
            normalizedDetail.task.completed_at_epoch,
        )
        val baseThread = normalizedDetail.thread.copy(
            status = serverStatus,
            completed_at = completedAt,
            updated_at = maxOf(detail.thread.updated_at, completedAt),
        )
        val mergedThreads = mergePendingThreads(listOf(baseThread), clearConfirmed = true)
        val visibleThread = mergedThreads
            .firstOrNull { baseThread.thread_id.isNotBlank() && it.thread_id == baseThread.thread_id }
            ?: mergedThreads.firstOrNull()
            ?: baseThread
        return ReconciledThreadDetail(
            thread = visibleThread,
            task = normalizedDetail.task.copy(
                status = visibleThread.status,
                completed_at_epoch = maxOf(normalizedDetail.task.completed_at_epoch, completedAt),
            ),
        )
    }

    private fun upsert(messages: List<MessageItem>, item: MessageItem): List<MessageItem> {
        val index = messages.indexOfFirst { it.id == item.id }
        if (index < 0) return messages + item
        val updated = messages.toMutableList()
        updated[index] = item
        return updated
    }

    private fun mergeApprovals(
        current: List<ApprovalRequest>,
        incoming: List<ApprovalRequest>,
    ): List<ApprovalRequest> {
        val merged = current.associateBy { it.request_id }.toMutableMap()
        incoming.forEach { merged[it.request_id] = it }
        return merged.values.sortedBy { it.request_id }
    }

    private fun cancelTurnFailureConfirmation(threadId: String) {
        if (threadId.isBlank()) return
        failureConfirmations.remove(threadId)?.cancel()
    }

    private fun confirmTurnFailure(threadId: String, failedAt: Long, errorMessage: String) {
        if (threadId.isBlank()) return
        cancelTurnFailureConfirmation(threadId)
        failureConfirmations[threadId] = viewModelScope.launch {
            try {
                delay(FAILURE_CONFIRMATION_DELAY_MS)
                val client = api ?: return@launch
                val detail = runCatching { client.thread(threadId) }.getOrNull() ?: return@launch
                val serverStatus = detail.task.status.ifBlank { detail.thread.status }
                val serverCompletedAt = maxOf(
                    detail.thread.completed_at,
                    detail.task.completed_at_epoch,
                )
                if (serverStatus != "failed" || (serverCompletedAt > 0 && serverCompletedAt >= failedAt)) {
                    applyThreadDetail(threadId, detail)
                }
            } finally {
                failureConfirmations.remove(threadId)
            }
        }
    }

    private fun applyThreadDetail(threadId: String, detail: ThreadDetailResponse) {
        val reconciled = reconcileThreadDetail(
            detail,
            _state.value.activeThread?.status.orEmpty(),
        )
        val status = reconciled.thread.status
        _state.update { state ->
            state.copy(
                activeThread = if (state.activeThread?.thread_id == threadId) reconciled.thread else state.activeThread,
                threads = state.threads.map { thread ->
                    if (thread.thread_id == threadId) reconciled.thread else thread
                },
                messages = detail.messages,
                task = reconciled.task,
                approvals = detail.pending_approvals,
                working = status in ACTIVE_TASK_STATUSES,
                messageCount = detail.message_count,
                queue = applyQueueSnapshot(state.queue, threadId, detail.queue.items),
            )
        }
    }

    private fun applyThreadReadState(threadId: String, readStatus: String) {
        if (threadId.isBlank()) return
        _state.update { state ->
            val activeMatches = state.activeThread?.thread_id == threadId
            val current = when {
                activeMatches -> state.activeThread?.status.orEmpty()
                state.task.thread_id == threadId -> state.task.status
                else -> state.threads.firstOrNull { it.thread_id == threadId }?.status.orEmpty()
            }
            val status = applyThreadRead(current, readStatus)
            if (current in TERMINAL_TASK_STATUSES) {
                val threadUpdatedAt = state.threads
                    .firstOrNull { it.thread_id == threadId }?.updated_at ?: 0
                val activeUpdatedAt = state.activeThread?.updated_at ?: 0
                settings.markThreadReadOverride(
                    threadId,
                    maxOf(System.currentTimeMillis(), threadUpdatedAt, activeUpdatedAt),
                )
            }
            if (status == current) return@update state
            state.copy(
                activeThread = state.activeThread?.let { thread ->
                    if (thread.thread_id == threadId) thread.copy(status = status) else thread
                },
                threads = state.threads.map { thread ->
                    if (thread.thread_id == threadId) thread.copy(status = status) else thread
                },
                task = if (state.task.thread_id == threadId) state.task.copy(status = status) else state.task,
                working = if (activeMatches) status in ACTIVE_TASK_STATUSES else state.working,
            )
        }
    }

    private fun refreshBridgeCapabilities(threadId: String = "") {
        val client = api ?: return
        viewModelScope.launch {
            val enabled = client.declareSupportedCapabilities()
            runCatching { client.bridgeCapabilities(threadId, enabled) }
                .onSuccess { capabilities ->
                    _state.update { it.copy(bridgeCapabilities = capabilities) }
                }
        }
    }

    private fun refreshThreadSettings(threadId: String) {
        val client = api ?: return
        viewModelScope.launch {
            runCatching { client.threadSettings(threadId) }
                .onSuccess { settings ->
                    // Some bridge versions do not report approval policy in
                    // thread/read (the fields come back empty). Only trust a
                    // non-empty answer; never reset the user's last choice to
                    // the default because the read was incomplete.
                    val resolved = when {
                        settings.approvalPolicy == "never" ||
                            settings.sandboxPolicy == "danger-full-access" ->
                            com.codexbridge.mobile.data.cbdp.ApprovalMode.FullAccess
                        settings.approvalPolicy == "on-request" ||
                            settings.sandboxPolicy == "workspace-write" ->
                            com.codexbridge.mobile.data.cbdp.ApprovalMode.RequestApproval
                        else -> null
                    }
                    if (resolved != null) {
                        _state.update { it.copy(approvalMode = resolved) }
                    }
                    if (settings.cwd.isNotBlank()) {
                        _state.update { it.copy(selectedCwd = settings.cwd) }
                    }
                }
        }
    }

    private fun handleError(error: Throwable) {
        val unauthorized = error is ApiException && error.unauthorized
        if (unauthorized && _state.value.screen != AppScreen.Pair) {
            // The pairing token is genuinely dead (bridge store reset, phone
            // record deleted, or rotated past the grace window). Navigation to
            // the Pair screen is the only useful response — retrying with the
            // same token will keep failing. Saved addresses are pre-filled so
            // the user just needs a new pairing code.
            streamJob?.cancel()
            pollJob?.cancel()
            threadsJob?.cancel()
            screenStreamJob?.cancel()
            threadsJob = null
            screenStreamJob = null
            _state.update {
                it.copy(
                    error = friendly(error),
                    connected = false,
                    screen = AppScreen.Pair,
                    activeThread = null,
                    messages = emptyList(),
                    streaming = emptyMap(),
                    approvals = emptyList(),
                    busy = false,
                    sending = false,
                )
            }
            return
        }
        _state.update {
            it.copy(
                error = friendly(error),
                connected = if (unauthorized) false else it.connected,
            )
        }
    }

    private fun friendly(error: Throwable): String = when (error) {
        is ApiException -> error.message ?: "请求失败"
        else -> error.message ?: "网络不可用，请检查手机与电脑是否在同一 Wi-Fi"
    }

    private fun backoffMillis(attempt: Int): Long {
        val base = (2_000L shl attempt.coerceIn(0, 5)).coerceAtMost(60_000L)
        return base + Random.nextLong(0L, 1_000L)
    }

    companion object {
        private const val THREADS_REFRESH_MS = 30_000L
        private const val THREAD_POLL_MS = 30_000L
        private const val FAILURE_CONFIRMATION_DELAY_MS = 2_500L
        private const val REFRESH_TIMEOUT_MS = 20_000L
    }
}
