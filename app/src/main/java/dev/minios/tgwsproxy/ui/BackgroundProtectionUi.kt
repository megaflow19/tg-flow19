package dev.minios.tgwsproxy.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import dev.minios.tgwsproxy.R
import dev.minios.tgwsproxy.power.BackgroundProtection
import dev.minios.tgwsproxy.ui.icons.TablerIcons

data class BackgroundProtectionState(
    val batteryOptimizationIgnored: Boolean,
    val vendorAutostartAvailable: Boolean,
)

/** Re-read on every resume: the user changes these switches in system settings and comes back. */
@Composable
fun rememberBackgroundProtectionState(): BackgroundProtectionState {
    val context = LocalContext.current
    fun read() = BackgroundProtectionState(
        batteryOptimizationIgnored = BackgroundProtection.isIgnoringBatteryOptimizations(context),
        vendorAutostartAvailable = BackgroundProtection.vendorAutostartComponent(context) != null,
    )
    var state by remember { mutableStateOf(read()) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) state = read()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    return state
}

/** Main-screen warning while the OS may still freeze or kill the proxy in the background. */
@Composable
fun BackgroundRestrictedCard(vendorAutostartAvailable: Boolean) {
    val context = LocalContext.current
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    TablerIcons.AlertTriangle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = stringResource(R.string.background_restricted_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.background_restricted_message),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Row(modifier = Modifier.align(Alignment.End)) {
                val buttonColors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                )
                if (vendorAutostartAvailable) {
                    TextButton(
                        onClick = { BackgroundProtection.openVendorAutostart(context) },
                        colors = buttonColors,
                    ) { Text(stringResource(R.string.background_open_autostart)) }
                }
                TextButton(
                    onClick = { BackgroundProtection.openBatteryOptimizationRequest(context) },
                    colors = buttonColors,
                ) { Text(stringResource(R.string.background_fix)) }
            }
        }
    }
}

/** Settings rows: battery-optimization status with an action, and the vendor autostart screen. */
@Composable
fun BackgroundProtectionRows(state: BackgroundProtectionState) {
    val context = LocalContext.current
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.settings_battery_optimization),
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                text = stringResource(
                    if (state.batteryOptimizationIgnored) R.string.settings_battery_optimization_off
                    else R.string.settings_battery_optimization_on
                ),
                style = MaterialTheme.typography.bodySmall,
                color = if (state.batteryOptimizationIgnored) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.error,
            )
        }
        if (!state.batteryOptimizationIgnored) {
            TextButton(onClick = { BackgroundProtection.openBatteryOptimizationRequest(context) }) {
                Text(stringResource(R.string.background_fix))
            }
        }
    }
    if (state.vendorAutostartAvailable) {
        Spacer(modifier = Modifier.height(8.dp))
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.settings_vendor_autostart, BackgroundProtection.manufacturerName()),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = stringResource(R.string.settings_vendor_autostart_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = { BackgroundProtection.openVendorAutostart(context) }) {
                Text(stringResource(R.string.background_open))
            }
        }
    }
}
