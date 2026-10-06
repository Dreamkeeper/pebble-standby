package org.cryomonitor.companion

import android.content.Context
import android.os.Build
import android.os.SystemClock
import java.util.Date

/**
 * Persistent soak counters + recovery-drill markers (spec:
 * companion-resilience). Events are rare (worker records at most
 * 1/min), so a SharedPreferences write per event is fine. Reset only
 * from the Debug screen.
 */
class SoakStats(context: Context) {
    private val p = context.getSharedPreferences("soak_stats", Context.MODE_PRIVATE)

    fun inc(key: String) = p.edit().putLong(key, get(key) + 1).apply()
    fun add(key: String, delta: Long) = p.edit().putLong(key, get(key) + delta).apply()
    fun set(key: String, v: Long) = p.edit().putLong(key, v).apply()
    fun get(key: String): Long = p.getLong(key, 0)

    /** The soak report shown on the debug screen, shared by text and
     *  included in every diagnostics bundle (consented-diagnostics). */
    fun render(context: Context, settings: SettingsStore): String {
        val resetAt = get(SoakStats.RESET_AT)
        val days = if (resetAt == 0L) 0.0
                   else (System.currentTimeMillis() - resetAt) / 86_400_000.0
        return buildString {
            val ver = runCatching {
                context.packageManager.getPackageInfo(context.packageName, 0).versionName
            }.getOrNull() ?: "?"
            append("# standby soak ${Date()} (${Build.MODEL}, companion v$ver)\n")
            append("window: ${"%.1f".format(days)} days since " +
                "${if (resetAt == 0L) "-" else Date(resetAt).toString()}\n")
            append("service starts: boot=${get(SoakStats.STARTS_BOOT)} " +
                "update=${get(SoakStats.STARTS_UPDATE)} " +
                "other=${get(SoakStats.STARTS_OTHER)}\n")
            append("watch link: disconnects=${get(SoakStats.DISCONNECTS)} " +
                "downtime=${get(SoakStats.DOWNTIME_S) / 60}m " +
                "link-faults=${get(SoakStats.LINK_FAULTS)} " +
                "self-heals=${get(SoakStats.SELF_HEALS)}\n")
            val storeTag = if (PebbleAppPolicy.storeMode(
                    PebbleAppPolicy.parse(settings.pebbleAppMode), settings.dlEverSeen))
                " (store-app mode)" else ""
            append("worker: dl-records=${get(SoakStats.DL_RECORDS)}$storeTag " +
                "faults=${get(SoakStats.WORKER_FAULTS)} " +
                "sensor-faults=${get(SoakStats.SENSOR_FAULTS)} " +
                "notworn-nags=${get(SoakStats.NOTWORN_NAGS)}\n")
            if (get(SoakStats.WORKER_HEAP_LAST) > 0)
                append("worker heap: last=${get(SoakStats.WORKER_HEAP_LAST)}B " +
                    "min=${get(SoakStats.WORKER_HEAP_MIN)}B " +
                    "(gate: warn <512B)\n")
            append("alarms: pre=${get(SoakStats.PREALARMS)} " +
                "full=${get(SoakStats.ALARMS)} " +
                "server-fails=${get(SoakStats.SERVER_FAILS)}\n")
            if (get(SoakStats.OUTAGE_AT) > 0)
                append("outage drill: detect=${get(SoakStats.OUTAGE_DETECT_S)}s " +
                    "reconnect=${get(SoakStats.OUTAGE_RECONNECT_S)}s\n")
            if (get(SoakStats.BOOT_RECOVERY_AT) > 0)
                append("last boot recovery: " +
                    "${get(SoakStats.BOOT_RECOVERY_DELAY_S)}s after boot\n")
        }
    }

    /** BootReceiver marks its firing BEFORE starting the service, so the
     *  service-start classification works in either start order. */
    fun noteBootReceiverFired(reason: String) {
        set(RECEIVER_FIRED_AT, System.currentTimeMillis())
        set(RECEIVER_WAS_BOOT, if (reason == "boot") 1 else 0)
        if (reason == "boot") set(BOOT_RECEIVER_AT, System.currentTimeMillis())
    }

    /** Called once from MonitorService.onCreate. A create within 30 s of
     *  the boot receiver firing is a boot/update recovery start; anything
     *  else (launcher, enrollment, DL broadcast, START_STICKY revive) is
     *  "other". */
    fun noteServiceStart() {
        val now = System.currentTimeMillis()
        if (get(RESET_AT) == 0L) set(RESET_AT, now)
        val sinceReceiver = now - get(RECEIVER_FIRED_AT)
        if (get(RECEIVER_FIRED_AT) > 0 && sinceReceiver in 0..30_000) {
            if (get(RECEIVER_WAS_BOOT) == 1L) {
                inc(STARTS_BOOT)
                set(BOOT_RECOVERY_AT, now)
                set(BOOT_RECOVERY_DELAY_S, SystemClock.elapsedRealtime() / 1000)
            } else inc(STARTS_UPDATE)
        } else inc(STARTS_OTHER)
    }

    /** Clears the counters but keeps drill verdicts: the protocol runs
     *  the drills FIRST, then resets for a clean soak window — the PASS
     *  evidence must survive into the week's report. */
    fun reset() {
        val keep = listOf(RECEIVER_FIRED_AT, RECEIVER_WAS_BOOT, BOOT_RECEIVER_AT,
            BOOT_RECOVERY_AT, BOOT_RECOVERY_DELAY_S, REBOOT_ARMED_AT,
            OUTAGE_AT, OUTAGE_DETECT_S, OUTAGE_RECONNECT_S)
            .associateWith { get(it) }
        val e = p.edit().clear()
        keep.forEach { (k, v) -> if (v != 0L) e.putLong(k, v) }
        e.putLong(RESET_AT, System.currentTimeMillis())
        e.apply()
    }

    companion object {
        const val RESET_AT = "reset_at"
        const val STARTS_BOOT = "starts_boot"
        const val STARTS_UPDATE = "starts_update"
        const val STARTS_OTHER = "starts_other"
        const val DISCONNECTS = "disconnects"
        const val DOWNTIME_S = "downtime_s"
        const val DL_RECORDS = "dl_records"
        const val WORKER_FAULTS = "worker_faults"
        const val SENSOR_FAULTS = "sensor_faults"
        const val NOTWORN_NAGS = "notworn_nags"
        const val WORKER_HEAP_LAST = "worker_heap_last"
        const val WORKER_HEAP_MIN = "worker_heap_min"
        const val LINK_FAULTS = "link_faults"
        const val SELF_HEALS = "self_heals"
        const val PREALARMS = "prealarms"
        const val ALARMS = "alarms"
        const val SERVER_FAILS = "server_fails"
        const val LAST_DISCONNECT_AT = "last_disconnect_at"
        const val LAST_RECONNECT_AT = "last_reconnect_at"
        const val RECEIVER_FIRED_AT = "receiver_fired_at"
        const val RECEIVER_WAS_BOOT = "receiver_was_boot"
        /** Last BOOT_COMPLETED the receiver saw (never overwritten by an update). */
        const val BOOT_RECEIVER_AT = "boot_receiver_at"
        const val BOOT_RECOVERY_AT = "boot_recovery_at"
        const val BOOT_RECOVERY_DELAY_S = "boot_recovery_delay_s"
        const val REBOOT_ARMED_AT = "reboot_armed_at"
        const val OUTAGE_AT = "outage_at"
        const val OUTAGE_DETECT_S = "outage_detect_s"
        const val OUTAGE_RECONNECT_S = "outage_reconnect_s"
    }
}

/**
 * Reboot-drill verdict, pure so it is unit-testable: the wall clock is
 * continuous across a reboot (RTC), while elapsedRealtime restarts at
 * zero — so "now - elapsedRealtime" is the boot moment, and a boot
 * after arming is detectable without any state written during the
 * reboot itself.
 */
object RebootDrill {
    sealed class Verdict {
        object NotArmed : Verdict()
        object WaitingForReboot : Verdict()
        data class Pass(val bootToServiceS: Long) : Verdict()
        /** Receiver fired but the service never classified a boot start. */
        object FailServiceStart : Verdict()
        /** Receiver never fired: OEM autostart blocked (HyperOS default). */
        object FailAutostart : Verdict()
    }

    fun verdict(
        armedAtMs: Long,
        nowMs: Long,
        elapsedRealtimeMs: Long,
        receiverFiredAtMs: Long,
        bootRecoveryAtMs: Long,
        bootDelayS: Long,
    ): Verdict {
        if (armedAtMs == 0L) return Verdict.NotArmed
        val bootAtMs = nowMs - elapsedRealtimeMs
        if (bootAtMs < armedAtMs + CLOCK_SLACK_MS) return Verdict.WaitingForReboot
        if (bootRecoveryAtMs >= armedAtMs) return Verdict.Pass(bootDelayS)
        return if (receiverFiredAtMs >= armedAtMs) Verdict.FailServiceStart
               else Verdict.FailAutostart
    }

    private const val CLOCK_SLACK_MS = 5_000L
}
