package org.cryomonitor.companion

/**
 * Store-app mode (OpenSpec store-app-mode, 2026-09-16): the stock Pebble
 * app does not forward worker DataLogging to companions (upstream PR
 * mobileapp#386 pending), so on the stock app the companion never sees
 * worker records. Everything that treated "no records" as "worker dead"
 * has to know which app is on the phone. Pure functions, unit-tested.
 */
object PebbleAppPolicy {
    enum class Mode { AUTO, PATCHED, STORE }

    const val STORE_SYNC_DEFAULT_MIN = 60

    fun parse(s: String?): Mode = when (s?.trim()?.lowercase()) {
        "patched" -> Mode.PATCHED
        "store" -> Mode.STORE
        else -> Mode.AUTO
    }

    /** Auto resolves to store mode until the first worker record proves
     *  the phone's Pebble app forwards DataLogging. */
    fun storeMode(mode: Mode, dlEverSeen: Boolean): Boolean = when (mode) {
        Mode.STORE -> true
        Mode.PATCHED -> false
        Mode.AUTO -> !dlEverSeen
    }

    /** In store mode the periodic sync launch is the ONLY worker liveness
     *  signal, so "off" falls back to hourly rather than to nothing. */
    fun effectiveSyncMin(storeMode: Boolean, settingMin: Int): Int =
        if (storeMode && settingMin <= 0) STORE_SYNC_DEFAULT_MIN else settingMin

    /** Reference time for the sync clock: last watch data, else service
     *  start (a fresh store-mode install must still get its first sync). */
    fun syncDueAt(lastWatchDataT: Long, serviceStartedT: Long, syncMin: Int): Long =
        (if (lastWatchDataT > 0) lastWatchDataT else serviceStartedT) + syncMin * 60_000L

    /** Provisioning fault: link up, no worker proof of ANY kind (record or
     *  open-app message) since service start, past the grace, and not
     *  already raised. Proof of any kind ends it for good — the old rule
     *  keyed on records only and re-fired after every self-heal launch on
     *  the stock app. */
    fun provisioningFault(linkUp: Boolean, lastProofT: Long, serviceStartedT: Long,
                          nowT: Long, graceS: Long, alreadyFaulted: Boolean): Boolean =
        linkUp && lastProofT == 0L && serviceStartedT > 0 &&
            (nowT - serviceStartedT) / 1000 > graceS && !alreadyFaulted

    /** Store mode has no eviction watchdog; instead two consecutive sync
     *  launches that produce no watch data raise one fault. */
    fun syncMissFault(consecutiveMisses: Int): Boolean = consecutiveMisses >= 2

    fun describe(mode: Mode, dlEverSeen: Boolean, syncMin: Int): String {
        val store = storeMode(mode, dlEverSeen)
        val how = when (mode) {
            Mode.AUTO -> if (dlEverSeen) "auto: worker records seen" else "auto: no worker records yet"
            Mode.PATCHED -> "set: patched Pebble app"
            Mode.STORE -> "set: stock Pebble app"
        }
        return if (store)
            "Pebble app mode: STORE ($how). Worker telemetry unavailable; " +
                "liveness via a brief watchapp sync every ${syncMin} min; " +
                "DataLogging alarm recovery off. Alarms still arrive via the " +
                "watchapp launch path."
        else "Pebble app mode: PATCHED ($how). Worker records expected."
    }
}
