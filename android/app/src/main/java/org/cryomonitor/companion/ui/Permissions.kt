package org.cryomonitor.companion.ui

import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import org.cryomonitor.companion.R

/**
 * The "Let it run" rows, as data: both the Settings summary ("4 of 6
 * granted · 2 to check") and the permissions screen read this list.
 */
object Permissions {
    enum class State { GRANTED, MISSING, UNKNOWN }

    data class Row(val title: Int, val sub: Int, val state: State, val open: List<Intent>)

    fun rows(c: Context): List<Row> {
        val pkg = c.packageName
        val nm = c.getSystemService(NotificationManager::class.java)
        val details = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg"))
        return listOf(
            Row(R.string.perm_battery, R.string.perm_battery_sub,
                if (c.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(pkg))
                    State.GRANTED else State.MISSING,
                listOf(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$pkg")))),
            Row(R.string.perm_fullscreen, R.string.perm_fullscreen_sub,
                if (Build.VERSION.SDK_INT < 34 || nm.canUseFullScreenIntent()) State.GRANTED else State.MISSING,
                listOfNotNull(
                    if (Build.VERSION.SDK_INT >= 34) Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
                        Uri.parse("package:$pkg")) else null,
                    details)),
            Row(R.string.perm_popup, R.string.perm_popup_sub, State.UNKNOWN,
                listOf(Intent("miui.intent.action.APP_PERM_EDITOR").apply {
                    setClassName("com.miui.securitycenter",
                        "com.miui.permcenter.permissions.PermissionsEditorActivity")
                    putExtra("extra_pkgname", pkg)
                }, details)),
            Row(R.string.perm_autostart, R.string.perm_autostart_sub, State.UNKNOWN,
                listOf(Intent().setClassName("com.miui.securitycenter",
                    "com.miui.permcenter.autostart.AutoStartManagementActivity"), details)),
            Row(R.string.perm_exact, R.string.perm_exact_sub,
                if (Build.VERSION.SDK_INT < 31 ||
                    c.getSystemService(AlarmManager::class.java).canScheduleExactAlarms())
                    State.GRANTED else State.MISSING,
                listOfNotNull(
                    if (Build.VERSION.SDK_INT >= 31) Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                        Uri.parse("package:$pkg")) else null,
                    details)),
            Row(R.string.perm_notifications, R.string.perm_notifications_sub,
                if (nm.areNotificationsEnabled()) State.GRANTED else State.MISSING,
                listOf(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, pkg), details)),
        )
    }

    /** "4 of 6 granted · 2 to check" */
    fun summary(c: Context): String {
        val r = rows(c)
        val granted = r.count { it.state == State.GRANTED }
        val missing = r.count { it.state == State.MISSING }
        val unknown = r.count { it.state == State.UNKNOWN }
        return buildString {
            append(c.getString(R.string.perm_summary_granted, granted, r.size))
            if (missing > 0) append(" · ").append(c.getString(R.string.perm_summary_missing, missing))
            if (unknown > 0) append(" · ").append(c.getString(R.string.perm_summary_check, unknown))
        }
    }

    fun openFirst(c: Context, intents: List<Intent>) {
        for (i in intents) {
            try { c.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return }
            catch (_: Exception) { /* next */ }
        }
    }
}
