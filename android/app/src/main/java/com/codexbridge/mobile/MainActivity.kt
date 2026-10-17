package com.codexbridge.mobile

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.codexbridge.mobile.ui.ChatScreen
import com.codexbridge.mobile.ui.CodexBridgeTheme
import com.codexbridge.mobile.ui.PairScreen
import com.codexbridge.mobile.ui.SettingsScreen
import com.codexbridge.mobile.ui.ThreadsScreen

class MainActivity : ComponentActivity() {
    private val viewModel: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestNotificationPermission()
        openThreadFromIntent(intent)
        if (viewModel.state.value.paired) CompletionNotificationService.start(this)
        enableEdgeToEdge()
        setContent {
            val state by viewModel.state.collectAsState()
            CodexBridgeTheme(darkTheme = state.darkTheme) {
                AppRoot(state = state, viewModel = viewModel)
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        openThreadFromIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        viewModel.setForeground(true)
    }

    override fun onPause() {
        viewModel.setForeground(false)
        super.onPause()
    }

    private fun openThreadFromIntent(intent: android.content.Intent?) {
        val threadId = intent?.getStringExtra(CompletionNotificationService.EXTRA_THREAD_ID).orEmpty()
        if (threadId.isNotBlank()) {
            viewModel.openThread(threadId)
            CompletionNotificationService.restoreListening(this)
        }
    }

    private fun requestNotificationPermission() {
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(
                arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
                NOTIFICATION_PERMISSION_REQUEST,
            )
        }
    }

    companion object {
        private const val NOTIFICATION_PERMISSION_REQUEST = 4102
    }
}

@Composable
private fun AppRoot(state: UiState, viewModel: AppViewModel) {
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(state.error, state.notice) {
        val message = state.error ?: state.notice
        if (message != null) {
            val isError = state.error != null
            snackbarHostState.showSnackbar(message)
            if (isError) viewModel.dismissError() else viewModel.dismissNotice()
        }
    }

    BackHandler(enabled = state.screen == AppScreen.Chat || state.screen == AppScreen.Settings) {
        when (state.screen) {
            AppScreen.Chat -> viewModel.closeThread()
            AppScreen.Settings -> viewModel.closeSettings()
            else -> Unit
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        when (state.screen) {
            AppScreen.Pair -> PairScreen(
                state = state,
                onDiscover = viewModel::discoverBridges,
                onDiscoveryPort = viewModel::setDiscoveryPort,
                onPair = viewModel::pair,
            )
            AppScreen.Threads -> ThreadsScreen(
                state = state,
                onRefresh = viewModel::refresh,
                onOpenSettings = viewModel::openSettings,
                onOpenThread = viewModel::openThread,
                onSearchChange = viewModel::setSearch,
                onSearchSubmit = viewModel::submitSearch,
                onCreateThread = viewModel::newThread,
            )
            AppScreen.Chat -> ChatScreen(
                state = state,
                onBack = viewModel::closeThread,
                onSend = viewModel::send,
                onConsumeRestoreInput = viewModel::consumeRestoreInput,
                onStop = viewModel::stop,
                onPickModel = { slug -> viewModel.selectModel(slug) },
                onResolveApproval = viewModel::resolveApproval,
                onRespondApproval = viewModel::respondApproval,
                onSteerQueue = viewModel::steerQueue,
                onEditQueue = viewModel::editQueue,
                onDeleteQueue = viewModel::deleteQueue,
            )
            AppScreen.Settings -> SettingsScreen(
                state = state,
                onBack = viewModel::closeSettings,
                onToggleTheme = viewModel::setDarkTheme,
                onDiscoveryPort = viewModel::setDiscoveryPort,
                onUpdateRoutes = viewModel::updateConnectionRoutes,
                onUnpair = viewModel::unpair,
                onTestNotification = {
                    val result = CompletionNotificationService.showTestNotification(context)
                    scope.launch { snackbarHostState.showSnackbar(result) }
                },
                onOpenNotificationSettings = { CompletionNotificationService.openNotificationSettings(context) },
                onOpenBackgroundSettings = { openBatteryOptimizationSettings(context) },
            )
        }
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
        if (state.paired) BackgroundPermissionDialog()
    }
}

/**
 * Xiaomi and friends freeze an idle app long before stock Android would, and a
 * frozen app cannot play the completion sound. Ask once per launch, but only
 * while the exemption is actually missing.
 */
@Composable
private fun BackgroundPermissionDialog() {
    val context = LocalContext.current
    var visible by remember { mutableStateOf(!ignoringBatteryOptimizations(context)) }
    if (!visible) return
    AlertDialog(
        onDismissRequest = { visible = false },
        title = { Text("允许后台运行") },
        text = {
            Text(
                "屏幕熄灭后，系统会暂停后台应用，任务完成提醒可能收不到。" +
                    "请允许 CodexBridge 在后台运行（关闭电池优化）。"
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    visible = false
                    openBatteryOptimizationSettings(context)
                },
            ) { Text("去设置") }
        },
        dismissButton = {
            TextButton(onClick = { visible = false }) { Text("稍后") }
        },
    )
}

private fun ignoringBatteryOptimizations(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
    val power = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return true
    return power.isIgnoringBatteryOptimizations(context.packageName)
}

private fun openBatteryOptimizationSettings(context: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
    val request = Intent(
        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
        Uri.parse("package:${context.packageName}"),
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(request)
        return
    } catch (_: Exception) {
    }
    val details = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.parse("package:${context.packageName}"),
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(details)
    } catch (_: Exception) {
    }
}
