package org.cryomonitor.companion.ui

import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import org.cryomonitor.companion.PermissionChecks
import org.cryomonitor.companion.PermissionChecks.Boot
import org.cryomonitor.companion.PermissionChecks.Result
import org.cryomonitor.companion.R
import org.cryomonitor.companion.SoakStats
import org.cryomonitor.companion.WearerWords

/**
 * The "Let it run" rows, as data: both the Settings summary ("4 of 6
 * granted · 1 not tested") and the permissions screen read this list.
 * Rows Android cannot answer carry a [Check]: the screen offers a Test
 * (or Check) button that performs the real action and the row then
 * states what happened (PermissionChecks).
 */
object Permissions {
    enum class State { GRANTED, MISSING, UNKNOWN, TESTING, WAITING }
    enum class Check { POPUP, LOCK, REBOOT }

    data class Row(val title: Int, val sub: String, val state: State,
                   val open: List<Intent>, val check: Check? = null)

    fun rows(c: Context, now: Long = System.currentTimeMillis()): List<Row> {
        val pkg = c.packageName
        val nm = c.getSystemService(NotificationManager::class.java)
        val checks = PermissionChecks(c)
        val hyperOs = PermissionChecks.isHyperOs()
        val details = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg"))
        val miuiEditor = Intent("miui.intent.action.APP_PERM_EDITOR").apply {
            setClassName("com.miui.securitycenter",
                "com.miui.permcenter.permissions.PermissionsEditorActivity")
            putExtra("extra_pkgname", pkg)
        }
        val miuiAutostart = Intent().setClassName("com.miui.securitycenter",
            "com.miui.permcenter.autostart.AutoStartManagementActivity")

        val rows = mutableListOf<Row>()
        rows += Row(R.string.perm_battery, c.getString(R.string.perm_battery_sub),
            if (c.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(pkg))
                State.GRANTED else State.MISSING,
            listOf(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$pkg"))))

        rows += lockRow(c, now, checks, nm, hyperOs, listOfNotNull(
            if (Build.VERSION.SDK_INT >= 34) Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
                Uri.parse("package:$pkg")) else null,
            if (hyperOs) miuiEditor else null, details))

        if (hyperOs) rows += popupRow(c, now, checks, listOf(miuiEditor, details))

        rows += bootRow(c, now, listOfNotNull(if (hyperOs) miuiAutostart else null, details))

        rows += Row(R.string.perm_exact, c.getString(R.string.perm_exact_sub),
            if (Build.VERSION.SDK_INT < 31 ||
                c.getSystemService(AlarmManager::class.java).canScheduleExactAlarms())
                State.GRANTED else State.MISSING,
            listOfNotNull(
                if (Build.VERSION.SDK_INT >= 31) Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                    Uri.parse("package:$pkg")) else null,
                details))
        rows += Row(R.string.perm_notifications, c.getString(R.string.perm_notifications_sub),
            if (nm.areNotificationsEnabled()) State.GRANTED else State.MISSING,
            listOf(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, pkg), details))
        return rows
    }

    /** Full-screen alarm permission (Android 14+) and, on HyperOS, "show on
     *  lock screen"; the lock test is the proof either way. */
    private fun lockRow(c: Context, now: Long, checks: PermissionChecks, nm: NotificationManager,
                        hyperOs: Boolean, open: List<Intent>): Row {
        val fsiOk = Build.VERSION.SDK_INT < 34 || nm.canUseFullScreenIntent()
        val op = if (hyperOs) PermissionChecks.miuiOpAllowed(c, PermissionChecks.MIUI_OP_SHOW_WHEN_LOCKED) else null
        val (state, sub) = when (val r = checks.result(PermissionChecks.Kind.LOCK, now)) {
            is Result.Passed -> State.GRANTED to c.getString(R.string.perm_lock_passed, WearerWords.ago(r.at, now))
            is Result.Failed -> State.MISSING to c.getString(R.string.perm_lock_failed, WearerWords.ago(r.at, now))
            is Result.Inconclusive -> State.UNKNOWN to c.getString(R.string.perm_lock_inconclusive)
            Result.Testing -> State.TESTING to c.getString(R.string.perm_lock_testing)
            Result.NotTested -> when {
                !fsiOk -> State.MISSING to c.getString(R.string.perm_fullscreen_sub)
                op == false -> State.MISSING to c.getString(R.string.perm_hyperos_off)
                else -> State.GRANTED to c.getString(R.string.perm_allowed_untested)
            }
        }
        // A missing Android permission overrides an old pass.
        val st = if (!fsiOk && state == State.GRANTED) State.MISSING else state
        val stSub = if (st != state) c.getString(R.string.perm_fullscreen_sub) else sub
        return Row(R.string.perm_fullscreen, stSub, st, open, Check.LOCK)
    }

    private fun popupRow(c: Context, now: Long, checks: PermissionChecks, open: List<Intent>): Row {
        val op = PermissionChecks.miuiOpAllowed(c, PermissionChecks.MIUI_OP_BACKGROUND_START)
        val (state, sub) = when (val r = checks.result(PermissionChecks.Kind.POPUP, now)) {
            is Result.Passed -> State.GRANTED to c.getString(R.string.perm_popup_passed, WearerWords.ago(r.at, now))
            is Result.Failed -> State.MISSING to c.getString(R.string.perm_popup_failed, WearerWords.ago(r.at, now))
            is Result.Inconclusive -> State.UNKNOWN to c.getString(R.string.perm_not_tested)
            Result.Testing -> State.TESTING to c.getString(R.string.perm_popup_testing)
            Result.NotTested -> when (op) {
                true -> State.GRANTED to c.getString(R.string.perm_allowed_untested)
                false -> State.MISSING to c.getString(R.string.perm_hyperos_off)
                null -> State.UNKNOWN to c.getString(R.string.perm_not_tested)
            }
        }
        return Row(R.string.perm_popup, sub, state, open, Check.POPUP)
    }

    private fun bootRow(c: Context, now: Long, open: List<Intent>): Row {
        val soak = SoakStats(c)
        val installedAt = runCatching {
            c.packageManager.getPackageInfo(c.packageName, 0).firstInstallTime
        }.getOrDefault(0L)
        val (state, sub) = when (val b = PermissionChecks.bootVerdict(
            now, SystemClock.elapsedRealtime(), installedAt,
            soak.get(SoakStats.REBOOT_ARMED_AT), soak.get(SoakStats.BOOT_RECEIVER_AT),
            soak.get(SoakStats.BOOT_RECOVERY_AT), soak.get(SoakStats.BOOT_RECOVERY_DELAY_S))) {
            is Boot.Started -> State.GRANTED to
                if (b.delayS >= 0) c.getString(R.string.perm_boot_started, b.delayS, WearerWords.ago(b.bootAt, now))
                else c.getString(R.string.perm_boot_started_unknown_delay, WearerWords.ago(b.bootAt, now))
            is Boot.DidNotStart -> State.MISSING to c.getString(R.string.perm_boot_failed, WearerWords.ago(b.bootAt, now))
            Boot.Waiting -> State.WAITING to c.getString(R.string.perm_boot_waiting)
            Boot.NotYet -> State.UNKNOWN to c.getString(R.string.perm_boot_not_yet)
        }
        return Row(R.string.perm_autostart, sub, state, open, Check.REBOOT)
    }

    /** "4 of 6 granted · 1 to grant · 1 not tested" */
    fun summary(c: Context): String {
        val r = rows(c)
        val granted = r.count { it.state == State.GRANTED }
        val missing = r.count { it.state == State.MISSING }
        val unknown = r.size - granted - missing
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
