package dev.minios.tgwsproxy.data

import android.content.Context

/**
 * First-run setup state (see ui/screens/SetupScreen). Kept apart from the proxy config so the
 * setup wizard never touches runtime settings. Steps the app can check by itself (notification
 * permission, battery optimization) are not stored; only user-confirmed steps are.
 */
class SetupPreferences(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("first_run_setup", Context.MODE_PRIVATE)

    var completed: Boolean
        get() = prefs.getBoolean(KEY_COMPLETED, false)
        set(value) = prefs.edit().putBoolean(KEY_COMPLETED, value).apply()

    /** The user opened the phone maker's app-launch screen and confirmed it is set up. */
    var vendorAutostartDone: Boolean
        get() = prefs.getBoolean(KEY_VENDOR_AUTOSTART, false)
        set(value) = prefs.edit().putBoolean(KEY_VENDOR_AUTOSTART, value).apply()

    /** The proxy link was sent to Telegram (cannot be verified from here). */
    var telegramDone: Boolean
        get() = prefs.getBoolean(KEY_TELEGRAM, false)
        set(value) = prefs.edit().putBoolean(KEY_TELEGRAM, value).apply()

    private companion object {
        const val KEY_COMPLETED = "completed"
        const val KEY_VENDOR_AUTOSTART = "vendor_autostart_done"
        const val KEY_TELEGRAM = "telegram_done"
    }
}
