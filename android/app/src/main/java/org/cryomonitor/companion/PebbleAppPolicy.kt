package org.cryomonitor.companion

/**
 * How the phone learns the watch is alive. The stock Pebble app forwards
 * the worker's DataLogging records since PebbleKit2 1.3.0 (mobileapp#378,
 * 2026-09); before that only a patched build did, and a "patched / stock"
 * setting told the phone which it faced. The setting is gone (owner
 * decision 2026-10-11): the one fact that mattered — have worker records
 * ever arrived? — is observed directly. Until they have, liveness comes
 * from the periodic watchapp sync instead. Pure functions, unit-tested.
 */
object PebbleAppPolicy {
    const val STORE_SYNC_DEFAULT_MIN = 60

    /** Sync-only mode until the first worker record proves the phone's
     *  Pebble app forwards DataLogging. */
    fun storeMode(dlEverSeen: Boolean): Boolean = !dlEverSeen

    /** In sync-only mode the periodic sync launch is the ONLY worker
     *  liveness signal, so "off" falls back to hourly rather than to nothing. */
    fun effectiveSyncMin(storeMode: Boolean, settingMin: Int): Int =
        if (storeMode && settingMin <= 0) STORE_SYNC_DEFAULT_MIN else settingMin

    /** Reference time for the sync clock: last watch data, else service
     *  start (a fresh install must still get its first sync). */
    fun syncDueAt(lastWatchDataT: Long, serviceStartedT: Long, syncMin: Int): Long =
        (if (lastWatchDataT > 0) lastWatchDataT else serviceStartedT) + syncMin * 60_000L

    /** Provisioning fault: link up, no worker proof of ANY kind (record or
     *  open-app message) since service start, past the grace, and not
     *  already raised. Proof of any kind ends it for good. */
    fun provisioningFault(linkUp: Boolean, lastProofT: Long, serviceStartedT: Long,
                          nowT: Long, graceS: Long, alreadyFaulted: Boolean): Boolean =
        linkUp && lastProofT == 0L && serviceStartedT > 0 &&
            (nowT - serviceStartedT) / 1000 > graceS && !alreadyFaulted

    /** Sync-only mode has no eviction watchdog; instead two consecutive
     *  sync launches that produce no watch data raise one fault. */
    fun syncMissFault(consecutiveMisses: Int): Boolean = consecutiveMisses >= 2

    fun describe(dlEverSeen: Boolean, syncMin: Int): String =
        if (storeMode(dlEverSeen))
            "Watch reports: none yet. Liveness via a brief watchapp sync every " +
                "${syncMin} min; DataLogging alarm recovery off. Alarms still " +
                "arrive via the watchapp launch path."
        else "Watch reports: every minute (worker records seen)."
}
