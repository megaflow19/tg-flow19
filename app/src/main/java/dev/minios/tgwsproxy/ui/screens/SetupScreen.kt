package dev.minios.tgwsproxy.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import dev.minios.tgwsproxy.R
import dev.minios.tgwsproxy.data.SetupPreferences
import dev.minios.tgwsproxy.power.BackgroundProtection
import dev.minios.tgwsproxy.ui.icons.TablerIcons
import dev.minios.tgwsproxy.ui.theme.StatusGreen

/**
 * First-run setup: the steps a new user otherwise has to discover (notifications, battery
 * optimization, the phone maker's app-launch screen, adding the proxy to Telegram). Steps the
 * app can verify tick themselves; the others are confirmed by the user. Shown on first launch
 * and from Settings.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupScreen(
    onRequestNotifications: () -> Unit,
    onConnectTelegram: () -> Unit,
    onDone: () -> Unit,
    showBack: Boolean,
) {
    val context = LocalContext.current
    val setup = remember { SetupPreferences(context) }

    var notificationsOn by remember { mutableStateOf(NotificationManagerCompat.from(context).areNotificationsEnabled()) }
    var batteryOk by remember { mutableStateOf(BackgroundProtection.isIgnoringBatteryOptimizations(context)) }
    val vendorAvailable = remember { BackgroundProtection.vendorAutostartComponent(context) != null }
    var vendorDone by remember { mutableStateOf(setup.vendorAutostartDone) }
    var telegramDone by remember { mutableStateOf(setup.telegramDone) }

    // The user grants these in system screens and comes back: re-check on every resume.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                notificationsOn = NotificationManagerCompat.from(context).areNotificationsEnabled()
                batteryOk = BackgroundProtection.isIgnoringBatteryOptimizations(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.setup_title)) },
                navigationIcon = {
                    if (showBack) {
                        IconButton(onClick = onDone) {
                            Icon(TablerIcons.ArrowLeft, contentDescription = stringResource(R.string.navigate_back))
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary,
                ),
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            Column(
                modifier = Modifier.widthIn(max = 640.dp).fillMaxSize()
                    .verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = stringResource(R.string.setup_intro),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                SetupStep(
                    number = 1, icon = TablerIcons.Bell, done = notificationsOn,
                    title = stringResource(R.string.setup_notifications_title),
                    text = stringResource(R.string.setup_notifications_text),
                    action = stringResource(R.string.setup_allow),
                    onAction = onRequestNotifications,
                )
                SetupStep(
                    number = 2, icon = TablerIcons.BatteryEco, done = batteryOk,
                    title = stringResource(R.string.setup_battery_title),
                    text = stringResource(R.string.setup_battery_text),
                    action = stringResource(R.string.background_fix),
                    onAction = { BackgroundProtection.openBatteryOptimizationRequest(context) },
                )
                if (vendorAvailable) {
                    SetupStep(
                        number = 3, icon = TablerIcons.Settings, done = vendorDone,
                        title = stringResource(R.string.setup_vendor_title, BackgroundProtection.manufacturerName()),
                        text = stringResource(R.string.setup_vendor_text),
                        action = stringResource(R.string.background_open),
                        onAction = { BackgroundProtection.openVendorAutostart(context) },
                        confirm = stringResource(R.string.setup_mark_done),
                        onConfirm = { vendorDone = it; setup.vendorAutostartDone = it },
                    )
                }
                SetupStep(
                    number = if (vendorAvailable) 4 else 3, icon = TablerIcons.ExternalLink, done = telegramDone,
                    title = stringResource(R.string.setup_telegram_title),
                    text = stringResource(R.string.setup_telegram_text),
                    action = stringResource(R.string.setup_telegram_action),
                    onAction = {
                        onConnectTelegram()
                        telegramDone = true
                        setup.telegramDone = true
                    },
                    confirm = stringResource(R.string.setup_mark_done),
                    onConfirm = { telegramDone = it; setup.telegramDone = it },
                )
                SetupStep(
                    number = if (vendorAvailable) 5 else 4, icon = TablerIcons.AdjustmentsHorizontal, done = null,
                    title = stringResource(R.string.setup_more_title),
                    text = stringResource(R.string.setup_more_text),
                )

                Button(
                    onClick = {
                        setup.completed = true
                        onDone()
                    },
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                ) { Text(stringResource(R.string.setup_finish)) }
            }
        }
    }
}

/**
 * One setup step. [done] = null for an informational step without a status.
 * [confirm] adds a user-confirmed checkbox for steps the app cannot verify.
 */
@Composable
private fun SetupStep(
    number: Int,
    icon: ImageVector,
    done: Boolean?,
    title: String,
    text: String,
    action: String? = null,
    onAction: () -> Unit = {},
    confirm: String? = null,
    onConfirm: (Boolean) -> Unit = {},
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(
                    imageVector = if (done == true) TablerIcons.CircleCheck else icon,
                    contentDescription = null,
                    tint = if (done == true) StatusGreen else MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = "$number. $title",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                if (done != null) {
                    Text(
                        text = stringResource(if (done) R.string.setup_status_done else R.string.setup_status_todo),
                        style = MaterialTheme.typography.labelMedium,
                        color = if (done) StatusGreen else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (action != null || confirm != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (confirm != null) {
                        Checkbox(checked = done == true, onCheckedChange = onConfirm)
                        Text(confirm, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    } else {
                        Spacer(Modifier.weight(1f))
                    }
                    if (action != null) {
                        FilledTonalButton(onClick = onAction) { Text(action) }
                    }
                }
            }
        }
    }
}
