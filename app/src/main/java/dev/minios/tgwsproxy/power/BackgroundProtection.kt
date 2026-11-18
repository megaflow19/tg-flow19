package dev.minios.tgwsproxy.power

import android.annotation.SuppressLint
import android.app.usage.UsageStatsManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

/**
 * Keeps the proxy alive under vendor power managers.
 *
 * Observed 2026-11-18 on Honor (MagicOS 10): iAware/PowerGenie froze the foreground-service
 * process for 20+ minutes (Telegram connections queued in the kernel until the user opened
 * the app) and twice killed it (`iAwareR[LowMem](fg-service)`). Two user-side switches stop
 * that: the Android battery-optimization exemption, and the vendor's own "app launch /
 * autostart" screen. Neither can be granted silently, so the app explains and links to them.
 */
object BackgroundProtection {

    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val power = context.getSystemService(PowerManager::class.java) ?: return true
        return power.isIgnoringBatteryOptimizations(context.packageName)
    }

    /** App standby bucket (5 = exempted, 10 = active, ... 45 = restricted), or null. */
    fun standbyBucket(context: Context): Int? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return null
        return context.getSystemService(UsageStatsManager::class.java)?.appStandbyBucket
    }

    /**
     * Standard system dialog "let the app always run in the background?". Allowed outside
     * Google Play (REQUEST_IGNORE_BATTERY_OPTIMIZATIONS is declared in the manifest).
     */
    @SuppressLint("BatteryLife")
    fun openBatteryOptimizationRequest(context: Context): Boolean =
        start(
            context,
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}")),
        ) || start(context, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) ||
            start(context, appDetails(context))

    /** Vendor "app launch / autostart" screens, most specific first. Package names are in <queries>. */
    internal val VENDOR_AUTOSTART: List<ComponentName> = listOf(
        // Honor MagicOS (verified on ELI-NX9, MagicOS 10)
        ComponentName("com.hihonor.systemmanager", "com.hihonor.systemmanager.appcontrol.activity.StartupAppControlActivity"),
        ComponentName("com.hihonor.systemmanager", "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
        // Huawei EMUI / HarmonyOS
        ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity"),
        ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
        // Xiaomi / Redmi / POCO (MIUI, HyperOS)
        ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
        // OPPO / realme / OnePlus (ColorOS)
        ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
        ComponentName("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"),
        ComponentName("com.oplus.safecenter", "com.oplus.safecenter.permission.startup.StartupAppListActivity"),
        // vivo / iQOO
        ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
        ComponentName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager"),
        // Samsung (battery / sleeping apps)
        ComponentName("com.samsung.android.lool", "com.samsung.android.sm.battery.ui.BatteryActivity"),
    )

    /** Phone brand for UI labels, e.g. "Honor", "Xiaomi" (from Build.MANUFACTURER). */
    fun manufacturerName(): String =
        Build.MANUFACTURER.trim().lowercase().replaceFirstChar { it.titlecase() }.ifEmpty { "Android" }

    /** The first vendor autostart screen this device can open, or null on stock Android. */
    fun vendorAutostartComponent(context: Context): ComponentName? =
        VENDOR_AUTOSTART.firstOrNull { isExportedActivity(context, it) }

    fun openVendorAutostart(context: Context): Boolean {
        val component = vendorAutostartComponent(context) ?: return start(context, appDetails(context))
        return start(context, Intent().setComponent(component)) || start(context, appDetails(context))
    }

    private fun isExportedActivity(context: Context, component: ComponentName): Boolean = try {
        @Suppress("DEPRECATION")
        context.packageManager.getActivityInfo(component, 0).exported
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    private fun appDetails(context: Context) =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))

    private fun start(context: Context, intent: Intent): Boolean = try {
        if (context !is android.app.Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }
}
