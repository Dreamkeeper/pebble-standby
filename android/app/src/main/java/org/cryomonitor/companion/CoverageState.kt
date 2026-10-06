package org.cryomonitor.companion

/**
 * The one coverage model (spec companion-ui: "The home screen is a
 * coverage verdict"; design D1). Pure Kotlin: the service publishes
 * [CoverageFacts], this turns them into a [CoverageVerdict], and Home, the
 * ongoing notification and the Diagnostics card all render the same
 * verdict, so they can never disagree.
 */
enum class Coverage { ALARM, CHECK_IN, NOT_SET_UP, NEEDS_ATTENTION, PAUSED, COVERED }

/** What the single action on the verdict card does. */
enum class CoverageAction {
    CANCEL_ALARM, SET_UP, ADD_CONTACT, OPEN_BLUETOOTH, OPEN_SERVER, OPEN_PERMISSIONS,
    OPEN_WATCH, RE_WEAR, RESUME,
}

/** Facts the service knows; everything else is derived. Times are epoch ms. */
data class CoverageFacts(
    val onboardingDone: Boolean = false,
    val serverConfigured: Boolean = false,
    val fallbackConfigured: Boolean = false,   // phone-direct Telegram bot + chat ids
    val noDeliverableContacts: Boolean? = null, // server's "degraded"; null = unknown
    val watchConnected: Boolean = false,
    val lastWatchDataT: Long = 0L,              // watchapp spoke (battery etc.)
    val lastWorkerProofT: Long = 0L,            // worker record or sync proof
    val storeMode: Boolean = false,
    val chargingHold: Boolean = false,
    val suspendedUntilT: Long = 0L,
    val carryMode: Boolean = false,
    val serverReachable: Boolean = true,
    val serverLastResult: String = "",
    val alertStage: Int = 0,                    // 0 none, 1 countdown (pre-alarm), 2 alarm
    val alertDetector: String = "",
    val lastNagT: Long = 0L,                    // not-worn / sensor-fault nag received
    val lastNagKind: String = "",               // "notworn" | "sensor"
    val workerFault: Boolean = false,           // worker silent / provisioning fault
    val coveredSinceT: Long = 0L,
)

data class CoverageVerdict(
    val state: Coverage,
    val title: String,
    val reason: String,
    val action: CoverageAction?,
    val actionLabel: String?,
)

object CoverageState {
    /** A nag older than this is history, not a current fault. */
    const val NAG_FRESH_MS = 15 * 60_000L
    /** Worker proof older than this while the link is up means the worker is silent. */
    const val WORKER_STALE_MS = Protocol.WORKER_SILENT_AFTER_S * 1000L

    fun compute(f: CoverageFacts, nowMs: Long): CoverageVerdict {
        // Precedence (spec): Alarm, Check-in, Not set up, Needs attention, Paused, Covered.
        if (f.alertStage >= 2) return CoverageVerdict(
            Coverage.ALARM, WearerWords.ALARM,
            WearerWords.alarmReason(f.alertDetector),
            CoverageAction.CANCEL_ALARM, WearerWords.ACTION_IM_OK)
        if (f.alertStage == 1) return CoverageVerdict(
            Coverage.CHECK_IN, WearerWords.ALARM_SOON,
            WearerWords.alarmReason(f.alertDetector),
            CoverageAction.CANCEL_ALARM, WearerWords.ACTION_IM_OK)

        if (!f.onboardingDone || (!f.serverConfigured && !f.fallbackConfigured)) {
            return CoverageVerdict(
                Coverage.NOT_SET_UP, WearerWords.NOT_SET_UP,
                WearerWords.REASON_NOT_SET_UP, CoverageAction.SET_UP, WearerWords.ACTION_SET_UP)
        }

        // Needs attention: worst first within the band.
        if (f.serverConfigured && f.noDeliverableContacts == true) return attention(
            WearerWords.REASON_NO_CONTACTS, CoverageAction.ADD_CONTACT, WearerWords.ACTION_ADD_CONTACT)
        if (!f.watchConnected) return attention(
            WearerWords.REASON_WATCH_LINK, CoverageAction.OPEN_BLUETOOTH, WearerWords.ACTION_OPEN_BLUETOOTH)
        if (f.lastNagT > 0 && nowMs - f.lastNagT < NAG_FRESH_MS) {
            return if (f.lastNagKind == "sensor") attention(
                WearerWords.REASON_SENSOR, CoverageAction.OPEN_WATCH, WearerWords.ACTION_CHECK_WATCH)
            else attention(
                WearerWords.REASON_NOT_WORN, CoverageAction.RE_WEAR, WearerWords.ACTION_RE_WEAR)
        }
        if (f.workerFault || (!f.storeMode && f.lastWorkerProofT > 0 &&
                nowMs - f.lastWorkerProofT > WORKER_STALE_MS)) return attention(
            WearerWords.REASON_WATCH_SILENT, CoverageAction.OPEN_WATCH, WearerWords.ACTION_CHECK_WATCH)
        if (f.serverConfigured && !f.serverReachable) return attention(
            WearerWords.serverReason(f.serverLastResult), CoverageAction.OPEN_SERVER,
            WearerWords.ACTION_CHECK_SERVER)

        // Paused: deliberate, calm.
        val suspLeft = f.suspendedUntilT - nowMs
        if (f.chargingHold) return CoverageVerdict(
            Coverage.PAUSED, WearerWords.PAUSED, WearerWords.REASON_CHARGING, null, null)
        if (f.carryMode) return CoverageVerdict(
            Coverage.PAUSED, WearerWords.PAUSED, WearerWords.REASON_CARRY,
            CoverageAction.RESUME, WearerWords.ACTION_RESUME)
        if (suspLeft > 0) return CoverageVerdict(
            Coverage.PAUSED, WearerWords.PAUSED, WearerWords.pausedReason(suspLeft),
            CoverageAction.RESUME, WearerWords.ACTION_RESUME)

        return CoverageVerdict(
            Coverage.COVERED, WearerWords.COVERED, WearerWords.coveredSince(f.coveredSinceT, nowMs),
            null, null)
    }

    private fun attention(reason: String, action: CoverageAction, label: String) =
        CoverageVerdict(Coverage.NEEDS_ATTENTION, WearerWords.NEEDS_ATTENTION, reason, action, label)

    /** The ongoing notification's single line, derived from the verdict. */
    fun notificationLine(v: CoverageVerdict, f: CoverageFacts, watchBatteryPct: Int?,
                         nowMs: Long): String {
        val batt = if (watchBatteryPct != null && f.lastWatchDataT > 0 &&
            nowMs - f.lastWatchDataT < 3_600_000) " · watch $watchBatteryPct%" else ""
        return when (v.state) {
            Coverage.COVERED -> "${v.title}$batt · ${if (f.serverConfigured) "your server reachable" else "no server"}"
            else -> "${v.title} · ${v.reason}"
        }
    }
}
