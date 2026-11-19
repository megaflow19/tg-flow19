package dev.minios.tgwsproxy.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.collectLatest
import dev.minios.tgwsproxy.R
import dev.minios.tgwsproxy.proxy.ProxyConfig
import dev.minios.tgwsproxy.ui.screens.MainScreen
import dev.minios.tgwsproxy.ui.screens.SettingsScreen
import dev.minios.tgwsproxy.ui.screens.AboutScreen
import dev.minios.tgwsproxy.ui.screens.HelpScreen
import dev.minios.tgwsproxy.ui.screens.LogScreen
import dev.minios.tgwsproxy.ui.screens.SetupScreen
import dev.minios.tgwsproxy.data.SetupPreferences
import dev.minios.tgwsproxy.service.ProxyServiceState
import dev.minios.tgwsproxy.ui.theme.TgWsProxyTheme

class MainActivity : ComponentActivity() {
    companion object {
        const val ACTION_SHOW_UPDATE = "dev.minios.tgwsproxy.SHOW_UPDATE"
    }

    private var pendingUpdateOpen by mutableStateOf(false)

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            // Denied for good (the system no longer shows the dialog): from the setup wizard,
            // send the user to the app's notification settings instead of doing nothing.
            if (notificationRequestFromSetup && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                !shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)
            ) {
                openNotificationSettings()
            } else {
                Toast.makeText(this, R.string.notification_permission_required, Toast.LENGTH_LONG).show()
            }
        }
        notificationRequestFromSetup = false
    }
    private var notificationRequestFromSetup = false

    /** Setup wizard step: ask for the permission, or open notification settings if it can't be asked. */
    internal fun requestNotificationsFromSetup() {
        val needsRuntimePermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        if (needsRuntimePermission) {
            notificationRequestFromSetup = true
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            openNotificationSettings()
        }
    }

    private fun openNotificationSettings() {
        val intent = Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, packageName)
        try {
            startActivity(intent)
        } catch (_: Exception) {
            startActivity(
                Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(android.net.Uri.parse("package:$packageName"))
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingUpdateOpen = savedInstanceState?.getBoolean("pending_update_open") ?: false
        consumeUpdateIntent(intent)

        // Request notification permission on Android 13+. Until first-run setup is done, the
        // setup wizard asks for it as its first step instead, so the dialog is not shown twice.
        if (SetupPreferences(this).completed && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this, Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        setContent {
            val viewModel: MainViewModel = viewModel()
            val config by viewModel.config.collectAsState()
            val configLoaded by viewModel.configLoaded.collectAsState()
            val systemDark = isSystemInDarkTheme()
            TgWsProxyTheme(
                darkTheme = when (config.appTheme) {
                    "light" -> false
                    "dark" -> true
                    else -> systemDark
                },
                dynamicColor = config.dynamicColor,
            ) {
                if (configLoaded) {
                    AppNavigation(
                        viewModel, pendingUpdateOpen,
                        onUpdateOpened = { pendingUpdateOpen = false },
                        onRequestNotifications = ::requestNotificationsFromSetup,
                    )
                } else {
                    androidx.compose.foundation.layout.Box(
                        modifier = androidx.compose.ui.Modifier.fillMaxSize(),
                        contentAlignment = androidx.compose.ui.Alignment.Center,
                    ) {
                        androidx.compose.material3.CircularProgressIndicator()
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumeUpdateIntent(intent)
    }

    private fun consumeUpdateIntent(intent: Intent?) {
        if (intent?.action == ACTION_SHOW_UPDATE) {
            pendingUpdateOpen = true
            intent.action = null
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("pending_update_open", pendingUpdateOpen)
        super.onSaveInstanceState(outState)
    }
}

@Composable
private fun AppNavigation(
    viewModel: MainViewModel,
    pendingUpdateOpen: Boolean,
    onUpdateOpened: () -> Unit,
    onRequestNotifications: () -> Unit,
) {
    val config by viewModel.config.collectAsState()
    val serviceState by viewModel.serviceState.collectAsState()
    val proxyEnabled by viewModel.proxyEnabled.collectAsState()
    val commandFailure by viewModel.commandFailure.collectAsState()
    val connectivity by viewModel.connectivityState.collectAsState()
    val runtimeRouteMode by viewModel.runtimeRouteMode.collectAsState()
    val startFailure by viewModel.startFailure.collectAsState()
    val stats by viewModel.stats.collectAsState()
    val cfTestResult by viewModel.cfTestResult.collectAsState()
    val diagnosticState by viewModel.diagnosticState.collectAsState()
    val diagnosticEntries by viewModel.diagnosticEntries.collectAsState()
    val connectionAdvice by viewModel.connectionAdvice.collectAsState()
    val updateState by viewModel.updateState.collectAsState()
    val powerProfileNoticePending by viewModel.powerProfileNoticePending.collectAsState()

    // Handle transient messages from the view model.
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        viewModel.toastMessage.collectLatest { message ->
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    }

    // First launch opens the setup wizard; from Settings it returns to Settings.
    var currentScreen by rememberSaveable {
        mutableStateOf(if (SetupPreferences(context).completed) Screen.Main else Screen.Setup)
    }
    var setupFromSettings by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(pendingUpdateOpen, currentScreen) {
        // Keep the current Settings draft and its normal save/restart exit path intact.
        if (pendingUpdateOpen && currentScreen != Screen.Settings && currentScreen != Screen.Logs) {
            viewModel.refreshCachedUpdate()
            currentScreen = Screen.About
            onUpdateOpened()
        }
    }
    if (powerProfileNoticePending) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text(androidx.compose.ui.res.stringResource(R.string.power_profile_update_title)) },
            text = {
                Text(
                    text = androidx.compose.ui.res.stringResource(R.string.power_profile_update_message),
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                )
            },
            confirmButton = {
                TextButton(onClick = viewModel::dismissPowerProfileNotice) {
                    Text(androidx.compose.ui.res.stringResource(R.string.understood))
                }
            },
        )
    }
    val closeSettings: (ProxyConfig) -> Unit = { latestConfig ->
        viewModel.finishSettings(latestConfig)
        viewModel.clearCfTestResult()
        currentScreen = Screen.Main
    }

    when (currentScreen) {
        Screen.Main -> {
            MainScreen(
                config = config,
                serviceState = serviceState,
                proxyEnabled = proxyEnabled,
                commandFailed = commandFailure != null,
                connectivity = connectivity,
                runtimeRouteMode = runtimeRouteMode,
                startFailure = startFailure,
                stats = stats,
                connectionAdvice = connectionAdvice,
                updateState = updateState,
                onStart = { viewModel.startProxy() },
                onStop = { viewModel.stopProxy() },
                onOpenInTelegram = { viewModel.openInTelegram() },
                onCopyLink = { viewModel.copyProxyLink() },
                onOpenSettings = {
                    viewModel.beginSettings()
                    currentScreen = Screen.Settings
                },
                onOpenHelp = { currentScreen = Screen.Help },
                onOpenAbout = { currentScreen = Screen.About },
                onOpenUpdate = { viewModel.openAvailableUpdate() },
            )
        }
        Screen.Settings -> {
            LaunchedEffect(Unit) { viewModel.beginSettings() }
            SettingsScreen(
                config = config,
                cfTestResult = cfTestResult,
                onConfigChange = { viewModel.saveConfig(it) },
                onExit = closeSettings,
                onRegenerateSecret = { viewModel.regenerateSecret() },
                onCopySecret = { viewModel.copySecret(it) },
                onTestCfProxy = { viewModel.testCfProxy() },
                onAppearanceChange = { appTheme, dynamicColor ->
                    viewModel.updateAppearance(appTheme, dynamicColor)
                },
                onOpenLogs = { currentScreen = Screen.Logs },
                onOpenSetup = {
                    setupFromSettings = true
                    currentScreen = Screen.Setup
                },
            )
        }
        Screen.Setup -> {
            val leaveSetup = {
                currentScreen = if (setupFromSettings) Screen.Settings else Screen.Main
                setupFromSettings = false
            }
            if (setupFromSettings) BackHandler { leaveSetup() }
            SetupScreen(
                onRequestNotifications = onRequestNotifications,
                onConnectTelegram = {
                    if (serviceState == ProxyServiceState.STOPPED) viewModel.startProxy()
                    viewModel.openInTelegram()
                },
                onDone = leaveSetup,
                showBack = setupFromSettings,
            )
        }
        Screen.About -> {
            BackHandler { currentScreen = Screen.Main }
            AboutScreen(
                updateState = updateState,
                onCheckForUpdates = { viewModel.checkForUpdates() },
                onOpenUpdate = { viewModel.openAvailableUpdate() },
                onBack = { currentScreen = Screen.Main },
            )
        }
        Screen.Logs -> {
            BackHandler { currentScreen = Screen.Settings }
            LogScreen(
                entries = diagnosticEntries,
                diagnosticState = diagnosticState,
                onExportDiagnostics = { viewModel.exportDiagnostics() },
                onClearDiagnostics = { viewModel.clearDiagnostics() },
                onBack = { currentScreen = Screen.Settings },
            )
        }
        Screen.Help -> {
            BackHandler { currentScreen = Screen.Main }
            HelpScreen(onBack = { currentScreen = Screen.Main })
        }
    }
}

private enum class Screen { Main, Settings, About, Logs, Help, Setup }
