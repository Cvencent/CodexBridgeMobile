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
import com.codexbridge.mobile.data.ConnectionRoute
import com.codexbridge.mobile.data.ConnectionSelector
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
import com.codexbridge.mobile.data.TaskSnapshot
import com.codexbridge.mobile.data.ThreadSummary
import com.codexbridge.mobile.data.ACTIVE_TASK_STATUSES
import com.codexbridge.mobile.data.coherentTaskStatus
import com.codexbridge.mobile.data.applyThreadStatusEvent
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
import kotlinx.coroutines.withContext
import java.util.UUID

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
    val sending: Boolean = false,
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
    private var pollJob: Job? = null
    private var threadsJob: Job? = null
    private var foreground = false

    fun setForeground(visible: Boolean) {
        foreground = visible
        if (visible) {
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
            val threadId = _state.value.activeThread?.thread_id
            if (_state.value.screen == AppScreen.Chat && !threadId.isNullOrBlank()) {
                observeThread(threadId)
            }
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
            api = ApiClient(base, settings.token)
            CompletionNotificationService.start(appContext)
            startThreadsRefresh()
            refreshConnectionRoute()
            refresh()
        }
    }

    fun refreshConnectionRoute() {
        val current = _state.value
        if (!current.paired || routeRefreshJob?.isActive == true) return
        routeRefreshJob = viewModelScope.launch {
            val candidates = ConnectionRoute.candidatesFor(
                legacyUrl = settings.baseUrl,
                lanUrl = settings.lanUrl,
                wanUrl = settings.wanUrl,
                onWifi = isOnWifi(),
            )
            val selected = ConnectionSelector.select(candidates) { endpoint ->
                ApiClient(endpoint.url, settings.token).ping()
            }
            if (selected != null) {
                val previous = _state.value.baseUrl
                settings.lastSuccessfulUrl = selected.url
                settings.baseUrl = selected.url
                val role = selected.endpoint.role.name.lowercase()
                if (previous != selected.url || api == null || _state.value.activeRouteRole != role) {
                    api = ApiClient(selected.url, settings.token)
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
            val found = Discovery.discover(_state.value.discoveryPort)
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
                    val candidate = ApiClient(endpoint.url)
                    candidate.ping().takeIf { it.ok && it.service == "codexbridge-companion" }
                } ?: throw ApiException("无法连接电脑，请检查地址和网络")
                val client = ApiClient(paired.url)
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
                api = ApiClient(resolved.base, response.token)
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
        _state.update { it.copy(manualRefreshing = true) }
        refreshThreadsNow()
        viewModelScope.launch {
            try {
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
            } catch (error: Exception) {
                handleError(error)
            }
            try {
                val threads = client.threads(_state.value.search)
                val visible = mergePendingThreads(threads.threads, clearConfirmed = true)
                _state.update { state ->
                    state.copy(
                        threads = visible,
                        connected = true,
                    )
                }
            } catch (error: Exception) {
                handleError(error)
            }
            loadProjects()
            loadModels()
        }
    }

    fun refreshThreadsNow() {
        // Called on resume and when leaving a chat: refresh quietly so the
        // spinner only appears when the user actually taps 刷新.
        viewModelScope.launch { refreshThreads() }
    }

    private fun startThreadsRefresh() {
        threadsJob?.cancel()
        threadsJob = viewModelScope.launch {
            refreshThreads()
            while (true) {
                delay(THREADS_REFRESH_MS)
                if (_state.value.screen == AppScreen.Threads && api != null) refreshThreads()
            }
        }
    }

    private suspend fun refreshThreads() {
        val client = api ?: return
        _state.update { it.copy(refreshing = true) }
        runCatching { client.threads(_state.value.search) }
            .onSuccess { response ->
                val visible = mergePendingThreads(response.threads, clearConfirmed = true)
                _state.update { state ->
                    state.copy(
                        threads = visible,
                        connected = true,
                        refreshing = false,
                        manualRefreshing = false,
                        lastRefreshedAt = System.currentTimeMillis(),
                    )
                }
            }
            .onFailure {
                _state.update { it.copy(refreshing = false, manualRefreshing = false) }
                refreshConnectionRoute()
            }
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
                busy = true,
                task = seededTask,
                working = seededTask.isWorking || visibleSummary.status in ACTIVE_TASK_STATUSES,
            )
        }
        viewModelScope.launch {
            try {
                // Read acknowledgement must not delay the detail request. The
                // extra round trip was responsible for most of the blank chat.
                viewModelScope.launch { runCatching { client.markThreadRead(threadId) } }
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
                        busy = false,
                    )
                }
                observeThread(threadId)
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
                sending = false,
            )
        }
        refreshThreadsNow()
    }

    private fun observeThread(threadId: String) {
        streamJob?.cancel()
        streamJob = viewModelScope.launch {
            while (true) {
                val client = api ?: break
                var streamFailed = false
                client.events(threadId).collect { event ->
                    if (event.type == "stream_error") {
                        streamFailed = true
                        _state.update { it.copy(connected = false) }
                    } else {
                        _state.update { it.copy(connected = true) }
                        applyEvent(event)
                    }
                }
                if (streamFailed || !_state.value.connected) refreshConnectionRoute()
                delay(3000)
            }
        }
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            while (true) {
                delay(4000)
                val client = api ?: continue
                runCatching { client.thread(threadId) }
                    .onSuccess { detail ->
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
                    .onFailure { refreshConnectionRoute() }
            }
        }
    }

    fun send(text: String) {
        val client = api ?: return
        val threadId = _state.value.activeThread?.thread_id ?: return
        val content = text.trim()
        if (content.isEmpty()) return
        settings.clearPendingCompletion(threadId)
        _state.update { it.copy(error = null, sending = true) }
        viewModelScope.launch {
            try {
                client.enqueueMessage(
                    threadId = threadId,
                    text = content,
                    model = _state.value.selectedModel,
                    effort = _state.value.selectedEffort,
                )
                refreshQueue(threadId)
                // The bridge owns dispatch. A fresh thread starts immediately;
                // a busy thread stays pending until its turn completes.
                runCatching { client.thread(threadId) }.onSuccess { detail ->
                    val status = coherentTaskStatus(
                        _state.value.activeThread?.status.orEmpty(),
                        detail.task.status,
                    )
                    _state.update { state ->
                        state.copy(
                            task = detail.task.copy(status = status),
                            working = status in ACTIVE_TASK_STATUSES,
                        )
                    }
                }
            } catch (error: Exception) {
                _state.update { it.copy(restoreInput = content, sending = false) }
                handleError(error)
            }
        }
    }

    fun steerQueue(item: QueueItem) {
        val client = api ?: return
        val threadId = _state.value.activeThread?.thread_id ?: return
        viewModelScope.launch {
            runCatching { client.steerQueue(item.queue_id, item.revision) }
                .onSuccess { refreshQueue(threadId) }
                .onFailure { handleError(it) }
        }
    }

    fun editQueue(item: QueueItem, text: String) {
        val client = api ?: return
        val threadId = _state.value.activeThread?.thread_id ?: return
        val content = text.trim()
        if (content.isEmpty()) return
        viewModelScope.launch {
            runCatching {
                client.editQueue(
                    queueId = item.queue_id,
                    text = content,
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

    fun stop() {
        val client = api ?: return
        val threadId = _state.value.activeThread?.thread_id ?: return
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
                _state.update {
                    it.copy(
                        busy = false,
                        selectedModel = safeModel.ifBlank { it.selectedModel },
                        selectedCwd = projectCwd,
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
        _state.update { it.copy(screen = AppScreen.Settings) }
    }

    fun closeSettings() {
        _state.update { it.copy(screen = if (it.activeThread != null) AppScreen.Chat else AppScreen.Threads) }
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
                _state.update { it.copy(working = true) }
            }
            "turn_completed" -> {
                val threadId = event.thread_id.ifBlank { _state.value.activeThread?.thread_id.orEmpty() }
                val completionStatus = if (event.status == "failed") "failed" else "completed"
                val completedAt = event.completed_at.takeIf { it > 0 } ?: System.currentTimeMillis() / 1000
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
                if (
                    threadId.isNotBlank() &&
                    (event.status == "active" || event.status == "idle" || event.active_flags.isNotEmpty())
                ) {
                    settings.clearPendingCompletion(threadId)
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
                    if (event.message.isNotBlank()) {
                        _state.update { it.copy(error = event.message) }
                    }
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
        val serverStatus = coherentTaskStatus(
            currentStatus,
            detail.task.status.ifBlank { detail.thread.status },
        )
        val completedAt = maxOf(
            detail.thread.completed_at,
            detail.task.completed_at_epoch,
        )
        val baseThread = detail.thread.copy(
            status = serverStatus,
            completed_at = completedAt,
            updated_at = maxOf(detail.thread.updated_at, completedAt),
        )
        val visibleThread = mergePendingThreads(listOf(baseThread), clearConfirmed = true).single()
        return ReconciledThreadDetail(
            thread = visibleThread,
            task = detail.task.copy(
                status = visibleThread.status,
                completed_at_epoch = maxOf(detail.task.completed_at_epoch, completedAt),
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

    private fun handleError(error: Throwable) {
        // A 401 can be a transient bridge restart or a stale token. Keep the
        // saved address/token/installation identity so the next launch can
        // reconnect; only the explicit settings action clears pairing.
        val disconnected = error is ApiException && error.unauthorized
        _state.update {
            it.copy(
                error = friendly(error),
                connected = if (disconnected) false else it.connected,
            )
        }
    }

    private fun friendly(error: Throwable): String = when (error) {
        is ApiException -> error.message ?: "请求失败"
        else -> error.message ?: "网络不可用，请检查手机与电脑是否在同一 Wi-Fi"
    }

    companion object {
        private const val THREADS_REFRESH_MS = 2000L
    }
}
