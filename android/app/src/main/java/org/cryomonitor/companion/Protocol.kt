package org.cryomonitor.companion

/**
 * Watch <-> phone protocol. MUST stay in sync with
 * watchapp/src/core/protocol.h (PMSG_* values and message keys).
 */
object Protocol {
    const val WATCHAPP_UUID = "7f8e2c40-3a55-4d9b-9f21-6b1e0c2d4a90"
    /** CM_DL_TAG (protocol.h): the worker heartbeat DataLogging session. */
    const val DL_TAG = 0xC202L

    // MSG_TYPE values (AppMessage)
    const val PMSG_HEARTBEAT = 1
    const val PMSG_PRE_ALARM = 2
    const val PMSG_ALARM = 3
    const val PMSG_CANCEL = 4
    const val PMSG_SUSPENDED = 5
    const val PMSG_CONFIG = 6
    const val PMSG_CONFIG_ACK = 7
    const val PMSG_USER_OK_REMOTE = 8
    const val PMSG_SET_DEBUG = 9
    const val PMSG_NOTWORN = 10
    const val PMSG_DRILL = 11          // phone->watch: start S1 latency drill
    const val PMSG_DRILL_RESULT = 12   // watch->phone: SECONDS = launch ms,
                                       // HEARTBEAT_SEQ = watch arm->result ms
    const val PMSG_CHARGING = 13       // watch->phone: SECONDS 1=on charger
    const val PMSG_HR_LAB = 14         // phone->watch: SECONDS 1/0 lab on/off
    const val PMSG_ALARM_ACK = 18      // phone->watch: SECONDS = episode id;
                                       // app-level ACK for PRE_ALARM/ALARM/
                                       // CANCEL (the watch retries until it)
    const val PMSG_SET_QMETRIC = 17    // phone->watch: SECONDS 0/1 — diag
                                       // firmware installed, quality gate on
    const val PMSG_SENSOR_FAULT = 16   // watch->phone: no pulse signal while
                                       // motion continues (sensor dead or
                                       // carried off-wrist) — wearer-only
    const val PMSG_HR_SAMPLE = 15      // watch->phone: SECONDS=bpm,
                                       // HEARTBEAT_SEQ=event age s, DETECTOR=heap/64

    // Detectors (mirrors cm_detector)
    val DETECTOR_NAMES = listOf(
        "pulse", "impact", "nonmotion", "checkin", "notworn", "sos")

    // Watch heartbeat cadence; the watchdog fires after missing several.
    const val WATCH_HEARTBEAT_INTERVAL_S = 60
    const val WATCH_SILENT_AFTER_S = 300
    // Worker DataLogging records: fault after this much silence while the
    // link is up (only armed once records have ever arrived). Hardware
    // measurement 2026-08-28: the watch spools records in ~4 min batches
    // (median flush 236 s, gaps to ~11 min observed) — the threshold must
    // clear the batching, not the logging cadence.
    const val WORKER_SILENT_AFTER_S = 1500
}
