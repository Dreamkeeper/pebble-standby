package org.cryomonitor.companion

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Restarts monitoring after a phone reboot or an app update — a safety
 * monitor must self-recover, not wait for the wearer to notice it died
 * (spec: companion-resilience). The server dead-man still catches the
 * case where this never runs (HyperOS/MIUI suppresses boot broadcasts
 * unless the user grants the vendor "Autostart" permission — the
 * recovery lab in Debug verifies it on-device).
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val reason = when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> "boot"
            Intent.ACTION_MY_PACKAGE_REPLACED -> "update"
            else -> return
        }
        // Recorded before the configured check: the firing itself is the
        // evidence the "Start after reboot" row shows (PermissionChecks).
        SoakStats(context).noteBootReceiverFired(reason)
        val s = SettingsStore(context)
        val configured = s.serverUrl.isNotEmpty() ||
            s.smsContacts.isNotEmpty() || s.telegramChatIds.isNotEmpty()
        if (!configured) return
        CmLog.init(context)
        // connectedDevice/specialUse FGS types may be started from
        // BOOT_COMPLETED on Android 14/15 (unlike e.g. dataSync); the
        // service's own typed-fallback chain handles the rest.
        runCatching {
            context.startForegroundService(Intent(context, MonitorService::class.java))
        }.onFailure {
            CmLog.e("BootReceiver", "startForegroundService($reason) failed", it)
        }
        CmLog.i("BootReceiver", "monitoring restart requested (reason=$reason)")
    }
}
