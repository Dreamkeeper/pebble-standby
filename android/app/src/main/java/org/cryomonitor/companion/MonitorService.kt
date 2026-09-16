package org.cryomonitor.companion

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The always-on hub (OpenSeizureDetector SdServer pattern):
 * PebbleTransport in, server heartbeats out, watch watchdog in between,
 * alarms to AlarmActivity + server escalation with phone-direct fallback.
 */
class MonitorService : Service(), PebbleTransport.Listener {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var watchLink: WatchLink
    private lateinit var settings: SettingsStore
    private lateinit var server: ServerClient
    private lateinit var escalator: Escalator
    private lateinit var soak: SoakStats

    @Volatile private var lastWatchDataT = 0L
    @Volatile private var watchBattery: Int? = null
    @Volatile private var drillT0 = 0L   // S1 latency drill start (epoch ms)
    @Volatile private var chargingHold = false  // watch on charger = paused
    @Volatile private var workerLastRecT = 0L   // last DataLogging record (arrival)
    @Volatile private var lastSelfHealT = 0L    // throttle watchapp relaunches
    @Volatile private var watchDownSince = 0L   // when the link last dropped
    @Volatile private var labActive = false     // S4 lab in progress
    private val dataLogReceiver = DataLogReceiver()
    @Volatile private var workerFaultNotified = false
    @Volatile private var workerProvisionFaulted = false
    @Volatile private var serviceStartedT = 0L
    @Volatile private var heapWarned = false
    private val dlFlushLatencies = ArrayDeque<Long>() // S5 stats, last 30
    @Volatile private var watchConnected = false
    @Volatile private var faultNotified = false
    @Volatile private var serverReachable = true
    @Volatile private var activeEscalationId: String? = null
    @Volatile private var degradedNotified = false
    @Volatile private var suspendedUntilT = 0L
    @Volatile private var sirenOn = false
    private var tone: ToneGenerator? = null

    override fun onCreate() {
        super.onCreate()
        settings = SettingsStore(this)
        CmLog.init(this)
        soak = SoakStats(this)
        soak.noteServiceStart()
        serviceStartedT = System.currentTimeMillis()
        server = ServerClient(settings)
        escalator = Escalator(this, settings)
        CmLog.i(TAG, "service starting, server=${settings.serverUrl.isNotEmpty()}")
        startForegroundSafely(buildNotification("Starting…"))

        watchLink = WatchLink(context = this, scope = scope, listener = this)
        watchLink.start()
        // The DL broadcasts are implicit: Android 8+ delivers them only to
        // runtime-registered receivers (the manifest entry alone is dead
        // weight). RECEIVER_EXPORTED: the sender is the Pebble phone app.
        runCatching {
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(
                dataLogReceiver,
                android.content.IntentFilter().apply {
                    addAction(DataLogReceiver.ACTION_RECEIVE_DATA)
                    addAction(DataLogReceiver.ACTION_FINISH_SESSION)
                }, RECEIVER_EXPORTED)
            else @Suppress("UnspecifiedRegisterReceiverFlag") registerReceiver(
                dataLogReceiver,
                android.content.IntentFilter().apply {
                    addAction(DataLogReceiver.ACTION_RECEIVE_DATA)
                    addAction(DataLogReceiver.ACTION_FINISH_SESSION)
                })
        }
        // NOTE: no unconditional self-heal launch here. A fresh service
        // creation (START_STICKY revive, boot) used to always launch the
        // watchapp, and during a Core workout transition the active-app
        // query could read "no foreign app" before the workout became
        // authoritative — stealing the wearer's screen (review 2026-08-29
        // finding 15, matching the field report). The watchapp is launched
        // only for an explicit user test/sync or a verified worker-silent
        // fault, both of which re-check foreignAppActive at fire time.

        scope.launch { watchdogLoop() }
        scheduleHeartbeat(5_000)
    }

    /**
     * Android 14+ enforces per-type prerequisites for foreground services:
     * connectedDevice requires a granted Bluetooth permission. If the user
     * hasn't granted BLUETOOTH_CONNECT yet, fall back to specialUse, then to
     * the untyped legacy call — a life-safety service must never crash the
     * app because a permission dialog hasn't been answered yet.
     */
    private fun startForegroundSafely(n: Notification) {
        if (Build.VERSION.SDK_INT < 34) {
            startForeground(NOTIF_ID, n)
            return
        }
        val attempts = listOf(
            "connectedDevice" to ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            "specialUse" to ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        for ((name, type) in attempts) {
            try {
                startForeground(NOTIF_ID, n, type)
                CmLog.i(TAG, "foreground started with type=$name")
                return
            } catch (e: Exception) {
                CmLog.w(TAG, "startForeground($name) failed: $e")
            }
        }
        try {
            startForeground(NOTIF_ID, n)
            CmLog.w(TAG, "foreground started untyped (legacy fallback)")
        } catch (e: Exception) {
            CmLog.e(TAG, "all startForeground attempts failed — running degraded", e)
        }
    }

    // ---- PebbleTransport.Listener ----

    override fun onAppMessage(data: Map<Int, Any>) {
        lastWatchDataT = System.currentTimeMillis()
        faultNotified = false
        // An open-app message is worker proof too — the worker launched the
        // app or answered a poll — so it clears the provisioning fault
        // (review 2026-08-29 finding 5).
        workerProvisionFaulted = false
        workerLastProofT = System.currentTimeMillis()
        (data[PebbleTransport.KEY_WATCH_BATTERY] as? Int)?.let { noteWatchBattery(it) }

        when (data[PebbleTransport.KEY_MSG_TYPE] as? Int) {
            Protocol.PMSG_HEARTBEAT -> {
                // Re-sync the debug flag while the watchapp is open: a
                // toggle flipped while the app was closed would otherwise
                // be lost (AppMessage needs an open inbox) — the field
                // finding behind "no debug line on the watch".
                watchLink.send(mapOf(
                    PebbleTransport.KEY_MSG_TYPE to Protocol.PMSG_SET_DEBUG,
                    PebbleTransport.KEY_SECONDS to
                        (if (settings.debugLogging) 1 else 0)))
                watchLink.send(mapOf(
                    PebbleTransport.KEY_MSG_TYPE to Protocol.PMSG_SET_QMETRIC,
                    PebbleTransport.KEY_SECONDS to
                        (if (qmetricEffective()) 1 else 0)))
                if (labActive) watchLink.send(mapOf(
                    PebbleTransport.KEY_MSG_TYPE to Protocol.PMSG_HR_LAB,
                    PebbleTransport.KEY_SECONDS to labModeValue()))
                CmLog.d(TAG, "watch heartbeat seq=${data[PebbleTransport.KEY_HEARTBEAT_SEQ]} " +
                    "batt=$watchBattery")
                updateNotification()
            }
            Protocol.PMSG_PRE_ALARM -> {
                val det = detectorName(data)
                val ep = (data[PebbleTransport.KEY_EPISODE] as? Int) ?: 0
                ackEpisode(ep)
                CmLog.i(TAG, "PRE-ALARM from watch: $det (ep=$ep)")
                // The watch retries until ACKed: refresh the UI only once
                // per episode so a delayed ACK cannot strobe the phone.
                if (ep == 0 || ep != lastPreAlarmEpisode) {
                    lastPreAlarmEpisode = ep
                    soak.inc(SoakStats.PREALARMS)
                    showAlarmUi(det, preAlarm = true)
                }
            }
            Protocol.PMSG_ALARM -> {
                val det = detectorName(data)
                val ep = (data[PebbleTransport.KEY_EPISODE] as? Int) ?: 0
                ackEpisode(ep)
                CmLog.i(TAG, "ALARM from watch: $det (ep=$ep)")
                if (ep != 0 && episodeEscalated(ep)) {
                    CmLog.i(TAG, "episode $ep already escalated — dedup")
                } else {
                    if (ep != 0) markEpisodeEscalated(ep)
                    soak.inc(SoakStats.ALARMS)
                    startSiren()
                    showAlarmUi(det, preAlarm = false)
                    scope.launch { escalate(det) }
                }
            }
            Protocol.PMSG_CANCEL -> {
                val ep = (data[PebbleTransport.KEY_EPISODE] as? Int) ?: 0
                ackEpisode(ep)
                CmLog.i(TAG, "watch cancelled alert (reason=" +
                    "${data[PebbleTransport.KEY_CANCEL_REASON]}, ep=$ep)")
                clearAlarmUi()
                sendBroadcast(Intent(ACTION_ALERT_CANCELLED).setPackage(packageName))
                scope.launch { retract("cancelled_on_watch") }
            }
            Protocol.PMSG_SUSPENDED -> {
                // SECONDS carries the suspension duration; 0 = ended
                // (expired or auto-resumed). Wall-clock deadline self-clears
                // even if the end message is lost.
                val secs = (data[PebbleTransport.KEY_SECONDS] as? Int) ?: 0
                suspendedUntilT = if (secs > 0)
                    System.currentTimeMillis() + secs * 1000L else 0L
                CmLog.i(TAG, "suspension from watch: " +
                    if (secs > 0) "for ${secs}s" else "ended")
                updateNotification()
            }
            Protocol.PMSG_DRILL_RESULT -> {
                val launchMs = (data[PebbleTransport.KEY_SECONDS] as? Int) ?: 0
                // The watch reports its own arm->result time; subtracting it
                // from our round trip leaves pure BT transport (both ways).
                // Never guess the countdown duration — it is tick-aligned.
                val watchMs = (data[PebbleTransport.KEY_HEARTBEAT_SEQ] as? Int) ?: 0
                val rtt = if (drillT0 > 0)
                    System.currentTimeMillis() - drillT0 else null
                val transport = if (rtt != null && watchMs > 0) rtt - watchMs else null
                drillT0 = 0
                CmLog.i(TAG, "LATENCY DRILL: worker->app launch=${launchMs}ms " +
                    "watch-total=${watchMs}ms rtt=${rtt}ms bt-transport=${transport}ms " +
                    "model=${Build.MODEL}")
                scope.launch {
                    if (server.configured)
                        server.drillResult(launchMs, rtt, watchMs, transport,
                                           Build.MODEL)
                }
            }
            Protocol.PMSG_HR_SAMPLE -> {
                // S4 sensor lab sample: relay to the DebugActivity.
                // SECONDS = raw bpm | quality_enc<<8 (0=OffWrist..5=Excellent,
                // 255=n/a); HEARTBEAT_SEQ = event age | filtered bpm<<8.
                val packed = (data[PebbleTransport.KEY_SECONDS] as? Int) ?: 0
                val packed2 = (data[PebbleTransport.KEY_HEARTBEAT_SEQ] as? Int) ?: 0
                sendBroadcast(Intent(ACTION_HR_SAMPLE)
                    .setPackage(packageName)
                    .putExtra("bpm", packed and 0xFF)
                    .putExtra("quality", (packed shr 8) and 0xFF)
                    .putExtra("filtered", (packed2 shr 8) and 0xFF)
                    .putExtra("event_age_s", packed2 and 0xFF)
                    .putExtra("heap",
                        ((data[PebbleTransport.KEY_DETECTOR] as? Int) ?: 0) * 64))
            }
            Protocol.PMSG_CHARGING -> {
                chargingHold = ((data[PebbleTransport.KEY_SECONDS] as? Int) ?: 0) > 0
                CmLog.i(TAG, "watch charging hold: $chargingHold")
                updateNotification()
            }
            Protocol.PMSG_NOTWORN -> {
                CmLog.w(TAG, "watch reports not worn")
                soak.inc(SoakStats.NOTWORN_NAGS)
                notifyFault("Watch appears OFF-WRIST (no pulse, no motion) " +
                    "without a suspension — monitoring is blind. Re-wear the " +
                    "watch or suspend monitoring. Contacts are NOT alerted.")
            }
            Protocol.PMSG_SENSOR_FAULT -> {
                CmLog.w(TAG, "watch reports sensor fault (no pulse, motion continues)")
                soak.inc(SoakStats.SENSOR_FAULTS)
                notifyFault("Watch reports NO PULSE SIGNAL while it keeps " +
                    "moving — the HR sensor may be dead, or the watch is " +
                    "carried off-wrist. Reboot the watch, or suspend " +
                    "monitoring if it is deliberately off. Contacts are " +
                    "NOT alerted.")
            }
        }
    }

    override fun onWatchappOpened() {
        // The lab must survive any open path: auto-launch that raced the
        // lab-on message, or the wearer opening the app by hand mid-lab.
        // Re-arm shortly after the inbox registers.
        if (labActive) scope.launch {
            delay(1_500)
            if (labActive) {
                CmLog.i(TAG, "watchapp opened during lab — re-arming lab mode")
                watchLink.send(mapOf(
                    PebbleTransport.KEY_MSG_TYPE to Protocol.PMSG_HR_LAB,
                    PebbleTransport.KEY_SECONDS to labModeValue()))
            }
        }
    }

    override fun onConnectionChanged(connected: Boolean) {
        val was = watchConnected
        watchConnected = connected
        if (connected && !was) {
            // Reconnect (or reboot) self-heal: relaunch the watchapp so the
            // worker restarts on fresh code. Only after a REAL outage —
            // brief provider flaps (workout start, state churn) must not
            // put the watchapp on the wearer's screen.
            val downFor = System.currentTimeMillis() - watchDownSince
            soak.set(SoakStats.LAST_RECONNECT_AT, System.currentTimeMillis())
            if (watchDownSince > 0) soak.add(SoakStats.DOWNTIME_S, downFor / 1000)
            if (watchDownSince > 0 && downFor >= 10_000) {
                selfHealLaunch("watch reconnected after ${downFor / 1000}s")
            } else {
                CmLog.i(TAG, "watch reconnected after ${downFor / 1000}s — " +
                    "flap, no relaunch")
            }
        } else if (!connected && was) {
            watchDownSince = System.currentTimeMillis()
            soak.inc(SoakStats.DISCONNECTS)
            soak.set(SoakStats.LAST_DISCONNECT_AT, watchDownSince)
            CmLog.w(TAG, "watch connection LOST")
        }
        updateNotification()
    }

    /**
     * Relaunch the watchapp to (re)start the worker — throttled, because
     * every launch briefly takes the watch screen. A flapping BT link or
     * repeated service restarts must not strobe the wearer's watchface
     * (field finding: the app "popping up by itself").
     *
     * When a foreign watchapp is on the screen the launch is DEFERRED, not
     * dropped: a pending flag re-attempts on the next watchdog tick once
     * the wearer returns to a watchface. A caller that must know whether
     * recovery actually happened (the worker-silent fault) keeps its fault
     * raised until a launch truly fires (review 2026-08-29 finding 6).
     */
    @Volatile private var selfHealPending: String? = null

    private fun selfHealLaunch(reason: String) {
        val now = System.currentTimeMillis()
        if (now - lastSelfHealT < SELF_HEAL_MIN_INTERVAL_MS) {
            CmLog.d(TAG, "self-heal ($reason) suppressed: throttled")
            return
        }
        scope.launch {
            // Never launch over an app the wearer is actively using —
            // ours would close it (one foreground app on Pebble). Defer.
            if (watchLink.foreignAppActive()) {
                selfHealPending = reason
                CmLog.i(TAG, "self-heal ($reason) DEFERRED: another watchapp " +
                    "is on the wearer's screen; will retry when it returns " +
                    "to a watchface")
                return@launch
            }
            lastSelfHealT = now
            selfHealPending = null
            CmLog.i(TAG, "self-heal ($reason): relaunching watchapp/worker")
            soak.inc(SoakStats.SELF_HEALS)
            watchLink.startWatchapp()
        }
    }

    /** PMSG_HR_LAB SECONDS: 2 = lab + raw-quality peeks (diag firmware
     *  only — stock asserts on the unknown metric), 1 = plain lab.
     *  Gated on the CONNECTED firmware being a dev build, not just the
     *  switch — a stale flag after a downgrade must stay harmless
     *  (review finding 7). */
    private fun qmetricEffective(): Boolean =
        settings.labQualityMetric && watchLink.firmwareIsDevBuild()

    private fun labModeValue(): Int = if (qmetricEffective()) 2 else 1

    // ---- episode dedup + ACK (delivery hardening D2/D3) ----

    @Volatile private var lastPreAlarmEpisode = 0
    @Volatile private var lastDlRecoveryT = 0L
    /** Last worker proof of ANY kind (DL record or open-app message). */
    @Volatile private var workerLastProofT = 0L
    private var storeSyncMisses = 0
    private var storeSyncFaultNotified = false
    private var lastSyncLaunchDataT = -1L

    fun storeMode(): Boolean = PebbleAppPolicy.storeMode(
        PebbleAppPolicy.parse(settings.pebbleAppMode), settings.dlEverSeen)

    private fun ackEpisode(ep: Int) {
        watchLink.send(mapOf(
            PebbleTransport.KEY_MSG_TYPE to Protocol.PMSG_ALARM_ACK,
            PebbleTransport.KEY_SECONDS to ep))
    }

    private fun episodeEscalated(ep: Int) = ep in settings.escalatedEpisodes

    private fun markEpisodeEscalated(ep: Int) {
        settings.escalatedEpisodes = settings.escalatedEpisodes + ep
    }

    private fun detectorName(data: Map<Int, Any>): String {
        val idx = data[PebbleTransport.KEY_DETECTOR] as? Int ?: return "unknown"
        return Protocol.DETECTOR_NAMES.getOrElse(idx) { "unknown" }
    }

    // ---- escalation ----

    private fun escalate(detector: String, isTest: Boolean = false) {
        val kind = if (isTest) "test" else "watch_alarm"
        val loc = escalator.lastKnownLocation()
        val escId = server.alarm(detector, kind, loc?.first, loc?.second)
        CmLog.i(TAG, "escalate det=$detector test=$isTest loc=$loc serverEsc=$escId")
        activeEscalationId = escId
        serverReachable = escId != null
        // SMS fires regardless (redundant path); Telegram-direct only when
        // the server (which owns Telegram with ACK buttons) is unreachable.
        escalator.fire(detector, isTest)
        updateNotification()
    }

    private fun retract(reason: String) {
        CmLog.i(TAG, "retract: $reason (esc=$activeEscalationId)")
        clearAlarmUi()
        // Resolve by id when we have it; ALSO sweep any open watch alarm on
        // the server, because a lost /alarm response or a service restart
        // can leave the server escalating with no id on this side — a
        // "successful" cancel that never reached contacts otherwise (review
        // 2026-08-29 finding 8).
        activeEscalationId?.let { server.resolve(it, "false_alarm") }
        server.resolveOpenAlarms()
        activeEscalationId = null
        escalator.cancel(reason)
    }

    // ---- alarm surface (T2 fix) ----
    //
    // startActivity() from a background service is silently discarded on
    // Android 10+ (background-activity-launch restriction) — the reason the
    // full-screen alarm never appeared during E2E T2 while Telegram and the
    // dashboard fired fine. The reliable path is a full-screen-intent
    // notification: the system itself launches AlarmActivity when the screen
    // is off/locked, and shows an urgent heads-up otherwise. The siren is
    // service-owned so a bystander hears it even if no UI ever launches.

    private fun showAlarmUi(detector: String, preAlarm: Boolean) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(
            CHANNEL_ALARM, "Alarms", NotificationManager.IMPORTANCE_HIGH))
        if (Build.VERSION.SDK_INT >= 34 && !nm.canUseFullScreenIntent()) {
            CmLog.w(TAG, "full-screen intents NOT permitted — alarm shows " +
                "as heads-up only (Settings > Apps > Special access)")
        }
        val pi = PendingIntent.getActivity(this, 0,
            Intent(this, AlarmActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra("detector", detector)
                .putExtra("preAlarm", preAlarm),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        nm.notify(NOTIF_ALARM_ID, Notification.Builder(this, CHANNEL_ALARM)
            .setContentTitle(if (preAlarm) "PRE-ALARM: $detector"
                             else "ALARM: $detector — contacts being alerted")
            .setContentText("Tap to open. Cancel there if you are OK.")
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setCategory(Notification.CATEGORY_ALARM)
            .setFullScreenIntent(pi, true)
            .setOngoing(true)
            .build())
        // Direct launch still works whenever we do hold launch privilege
        // (app visible or recently visible); harmless no-op otherwise.
        AlarmActivity.launch(this, detector, preAlarm)
    }

    private fun clearAlarmUi() {
        stopSiren()
        getSystemService(NotificationManager::class.java).cancel(NOTIF_ALARM_ID)
    }

    private fun startSiren() {
        if (sirenOn) return
        sirenOn = true
        tone = ToneGenerator(AudioManager.STREAM_ALARM, ToneGenerator.MAX_VOLUME)
        scope.launch {
            while (sirenOn) {
                runCatching {
                    tone?.startTone(ToneGenerator.TONE_CDMA_EMERGENCY_RINGBACK, 800)
                }
                delay(1000)
            }
        }
    }

    private fun stopSiren() {
        sirenOn = false
        tone?.stopTone()
        tone?.release()
        tone = null
    }

    // ---- loops ----

    private suspend fun watchdogLoop() {
        var flushTick = 0
        while (true) {
            // While Bluetooth is up, app-message silence is EXPECTED in
            // worker mode (the worker cannot send AppMessages; the phone
            // only hears the watch while the watchapp is open). A fault is
            // only a fault when the LINK is gone — or, once DataLogging
            // heartbeats have ever flowed, when they stop (worker evicted).
            val silentFor = (System.currentTimeMillis() - lastWatchDataT) / 1000
            if (!watchConnected && lastWatchDataT > 0 &&
                silentFor > Protocol.WATCH_SILENT_AFTER_S && !faultNotified) {
                faultNotified = true
                soak.inc(SoakStats.LINK_FAULTS)
                CmLog.w(TAG, "watch watchdog: link down, silent ${silentFor}s")
                notifyFault("Watch link lost — check Bluetooth and the " +
                    "watch battery. Monitoring on the watch continues, but " +
                    "alarms cannot reach this phone until the link returns.")
                selfHealLaunch("link down") // best-effort, lands on reconnect
            }
            // Worker-eviction watchdog: only armed once records have ever
            // arrived (so it stays quiet until S5 proves the channel).
            val workerSilent = (System.currentTimeMillis() - workerLastRecT) / 1000
            if (watchConnected && workerLastRecT > 0 &&
                workerSilent > Protocol.WORKER_SILENT_AFTER_S &&
                !workerFaultNotified) {
                workerFaultNotified = true
                soak.inc(SoakStats.WORKER_FAULTS)
                CmLog.w(TAG, "worker heartbeats stopped (${workerSilent}s) — evicted?")
                notifyFault("The watch background worker stopped reporting " +
                    "(possibly evicted by another background app). " +
                    "Relaunching the watchapp to restore monitoring.")
                selfHealLaunch("worker silent") // relaunch re-arms the worker
            }
            // Provisioning-liveness watchdog (review 2026-08-29 finding 5):
            // if the link is up but NO worker proof has EVER arrived — DL
            // records nor an open-app heartbeat — past the grace, the
            // worker may have died before it ever reported. The
            // eviction watchdog above can't fire (workerLastRecT==0), so
            // without this the phone looks healthy while no detector runs.
            if (PebbleAppPolicy.provisioningFault(watchConnected, workerLastProofT,
                    serviceStartedT, System.currentTimeMillis(),
                    WORKER_PROVISION_GRACE_S, workerProvisionFaulted)) {
                workerProvisionFaulted = true
                CmLog.w(TAG, "no worker proof within provisioning grace")
                notifyFault("No sign of the watch background worker since " +
                    "monitoring started. Open the watchapp once to confirm " +
                    "it is running, or reboot the watch. Detectors may not " +
                    "be active.")
                selfHealLaunch("worker never seen")
            }
            // Retry a self-heal that was deferred because a foreign
            // watchapp held the screen (review 2026-08-29 finding 6):
            // the fault stays visible until a launch truly fires.
            selfHealPending?.let { reason ->
                if (!watchLink.foreignAppActive()) selfHealLaunch(reason)
            }
            // Ask the phone's Pebble app to flush buffered worker records
            // once a minute (every 4th 15 s tick).
            if (flushTick++ % 4 == 0) DataLogReceiver.requestFlush(this)
            // S5 fallback (user-configurable): when watch data is older
            // than the sync interval and the link is up, launch the
            // watchapp briefly — it heartbeats fresh state and the
            // auto-launch guard returns the watchface in seconds.
            val store = storeMode()
            val syncMin = PebbleAppPolicy.effectiveSyncMin(store, settings.watchSyncIntervalMin)
            if (syncMin > 0 && watchConnected && serviceStartedT > 0 &&
                System.currentTimeMillis() >
                    PebbleAppPolicy.syncDueAt(lastWatchDataT, serviceStartedT, syncMin)) {
                // Store-app mode: the sync launch is the only liveness
                // signal, so two launches in a row that produce no watch
                // data are a fault (once) — the stock app cannot tell us
                // more than that.
                if (store) {
                    storeSyncMisses = if (lastWatchDataT == lastSyncLaunchDataT) storeSyncMisses + 1 else 0
                    lastSyncLaunchDataT = lastWatchDataT
                    if (PebbleAppPolicy.syncMissFault(storeSyncMisses) && !storeSyncFaultNotified) {
                        storeSyncFaultNotified = true
                        soak.inc(SoakStats.WORKER_FAULTS)
                        CmLog.w(TAG, "store-app mode: $storeSyncMisses sync launches without watch data")
                        notifyFault("The watch has not answered two sync launches in a " +
                            "row. Open the watchapp once to confirm it is running, or " +
                            "reboot the watch. (Stock Pebble app: no worker telemetry.)")
                    } else if (storeSyncMisses == 0) storeSyncFaultNotified = false
                }
                selfHealLaunch("periodic sync (${syncMin}m interval" +
                    (if (store) ", store-app mode)" else ")"))
            }
            updateNotification()
            delay(15_000) // also re-posts the notification (OSD pattern)
        }
    }

    /**
     * S6 battery-drain history: append a "epoch:pct" point whenever the
     * reported watch battery CHANGES (Pebble reports in 10 % steps, so
     * this stays tiny). The debug screen derives %/hour and projected
     * days from it — the phone-local complement of the server trail.
     */
    private fun noteWatchBattery(pct: Int) {
        if (pct == watchBattery) return
        watchBattery = pct
        runCatching {
            val prefs = getSharedPreferences("batt_hist", MODE_PRIVATE)
            val hist = (prefs.getString("points", "") ?: "")
                .split(';').filter { it.isNotEmpty() }
                .takeLast(199) + "${System.currentTimeMillis() / 1000}:$pct"
            prefs.edit().putString("points", hist.joinToString(";")).apply()
        }
    }

    private fun humanAge(ms: Long): String {
        val s = ms / 1000
        return when {
            s < 90 -> "${s}s"
            s < 5400 -> "${s / 60}m"
            else -> "%.1fh".format(s / 3600.0)
        }
    }

    // ---- server heartbeat (AlarmManager-driven) ----
    //
    // Field finding 2026-08-29: a coroutine delay() loop is silently
    // deferred by HyperOS Doze even in a foreground service with battery
    // optimization off — heartbeats arrived minutes late all day and one
    // 25-minute gap fired the server's phone_silent advisory. Exact
    // alarms (setExactAndAllowWhileIdle) are the one mechanism Android
    // commits to firing on time in Doze; each tick holds a short
    // wakelock so the network send completes before the device sleeps.

    private var hbFailures = 0

    private fun heartbeatTickIntent(): PendingIntent =
        PendingIntent.getForegroundService(this, 1,
            Intent(this, MonitorService::class.java)
                .setAction(ACTION_HEARTBEAT_TICK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun scheduleHeartbeat(delayMs: Long) {
        val am = getSystemService(android.app.AlarmManager::class.java)
        val at = System.currentTimeMillis() + delayMs
        try {
            if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) {
                am.setExactAndAllowWhileIdle(
                    android.app.AlarmManager.RTC_WAKEUP, at, heartbeatTickIntent())
            } else {
                am.setAndAllowWhileIdle(
                    android.app.AlarmManager.RTC_WAKEUP, at, heartbeatTickIntent())
                CmLog.w(TAG, "exact alarms NOT permitted — heartbeat timing " +
                    "is Doze-inexact (Settings > Apps > Special app access)")
            }
        } catch (e: Exception) {
            CmLog.e(TAG, "failed to schedule heartbeat alarm", e)
        }
    }

    private fun onHeartbeatTick() {
        val wl = getSystemService(android.os.PowerManager::class.java)
            .newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "cryomonitor:hb")
        runCatching { wl.acquire(45_000) }
        scope.launch {
            try {
                doServerHeartbeat()
            } finally {
                scheduleHeartbeat(if (hbFailures > 0) 60_000L else HEARTBEAT_INTERVAL_MS)
                runCatching { if (wl.isHeld) wl.release() }
            }
        }
    }

    @Volatile private var pendingCommandAck: String? = null

    private suspend fun doServerHeartbeat() {
        if (!server.configured) return
        val bm = getSystemService(BatteryManager::class.java)
        val pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val age = if (lastWatchDataT == 0L) null
                  else ((System.currentTimeMillis() - lastWatchDataT) / 1000).toInt()
        val ack = server.heartbeat(pct, watchBattery, age,
                                   lowBatteryWarning = pct in 1..15,
                                   commandAck = pendingCommandAck)
        CmLog.d(TAG, "server heartbeat ok=${ack != null} phoneBatt=$pct " +
            "watchAge=$age degraded=${ack?.degraded}")
        if (ack != null) {
            hbFailures = 0
            pendingCommandAck = null // the server saw our ack
            if (!serverReachable) {
                serverReachable = true
                CmLog.i(TAG, "server reachable again")
                updateNotification()
            }
            onDegradedState(ack.degraded)
            // The command is leased (redelivered until acked): ack it on the
            // next heartbeat so a lost response can't lose it (finding 14).
            if (ack.command != null) {
                pendingCommandAck = ack.command
                if (ack.command == "latency_drill") runLatencyDrill()
            }
        } else {
            // One miss is usually a transient (cell handover, Doze exit,
            // DNS blip): retry in a minute BEFORE declaring the server
            // down.
            hbFailures++
            soak.inc(SoakStats.SERVER_FAILS)
            CmLog.w(TAG, "server heartbeat failed x$hbFailures " +
                "(${server.lastResult})")
            if (hbFailures >= 2 && serverReachable) {
                serverReachable = false
                notifyFault("Server unreachable — phone-direct " +
                    "escalation active.")
                updateNotification()
            }
        }
    }

    // ---- commands from activities ----

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_USER_CANCEL -> {
                watchLink.send(mapOf(
                    PebbleTransport.KEY_MSG_TYPE to Protocol.PMSG_USER_OK_REMOTE))
                scope.launch { retract(intent.getStringExtra("cause") ?: "cancelled_on_phone") }
            }
            ACTION_TEST_ALARM -> {
                CmLog.i(TAG, "fire-drill TEST alarm requested")
                scope.launch { escalate("test", isTest = true) }
            }
            ACTION_HEARTBEAT_TICK -> onHeartbeatTick()
            ACTION_HEARTBEAT_NOW -> scope.launch {
                val bm = getSystemService(BatteryManager::class.java)
                val pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                val ack = if (server.configured)
                    server.heartbeat(pct, watchBattery, null,
                                     lowBatteryWarning = false) else null
                serverReachable = ack != null
                if (ack != null) onDegradedState(ack.degraded)
                CmLog.i(TAG, "manual heartbeat: ${if (ack != null) "OK" else "FAILED"} " +
                    "(url=${settings.serverUrl.ifEmpty { "unset" }}, " +
                    "result=${server.lastResult})")
                updateNotification()
            }
            ACTION_LATENCY_DRILL -> runLatencyDrill()
            ACTION_HR_LAB -> {
                val on = intent.getBooleanExtra("on", false)
                labActive = on
                CmLog.i(TAG, "S4 sensor lab ${if (on) "START" else "STOP"} " +
                    "(quality=${settings.labQualityMetric})")
                scope.launch {
                    if (on) { watchLink.startWatchapp(); delay(3_000) }
                    watchLink.send(mapOf(
                        PebbleTransport.KEY_MSG_TYPE to Protocol.PMSG_HR_LAB,
                        PebbleTransport.KEY_SECONDS to (if (on) labModeValue() else 0)))
                }
            }
            ACTION_WORKER_HEARTBEAT -> {
                // The background worker's DataLogging record made it over —
                // this is watch liveness WITHOUT the watchapp being open
                // (M0 S5). Treat it as watch data: the synced age, watch
                // battery, and the server's watch_data_age become honest.
                workerLastRecT = System.currentTimeMillis()
                lastWatchDataT = workerLastRecT
                workerLastProofT = workerLastRecT
                if (!settings.dlEverSeen) {
                    settings.dlEverSeen = true
                    CmLog.i(TAG, "first worker record: Pebble app forwards DataLogging (patched)")
                }
                soak.inc(SoakStats.DL_RECORDS)
                intent.getIntExtra("battery", -1)
                    .takeIf { it in 0..100 }?.let { noteWatchBattery(it) }
                // Records carry the worker's own state: sync display truth
                // that AppMessage can miss while the watchapp is closed
                // (field bug: SUSPENDED 20m shown long after auto-resume).
                if (intent.getIntExtra("suspended", -1) == 0) suspendedUntilT = 0
                intent.getIntExtra("flags", -1).takeIf { it >= 0 }?.let {
                    chargingHold = (it and 0x01) != 0
                }
                // Worker heap low-water (soak telemetry): a failed alloc
                // can crash the worker at the worst moment, so the margin
                // is trended, and critical readings raise a FAULT.
                val heap = intent.getIntExtra("worker_heap", 0)
                if (heap > 0) {
                    soak.set(SoakStats.WORKER_HEAP_LAST, heap.toLong())
                    val min = soak.get(SoakStats.WORKER_HEAP_MIN)
                    if (min == 0L || heap < min)
                        soak.set(SoakStats.WORKER_HEAP_MIN, heap.toLong())
                    if (heap < 512 && !heapWarned) {
                        heapWarned = true
                        notifyFault("Watch worker free heap critically low " +
                            "(${heap}B). A failed allocation can crash the " +
                            "worker. Reboot the watch; note the reading in " +
                            "the soak log.")
                    } else if (heap >= 1024) heapWarned = false
                }
                val flushS = intent.getLongExtra("flush_s", -1)
                if (flushS >= 0) {
                    synchronized(dlFlushLatencies) {
                        dlFlushLatencies.addLast(flushS)
                        while (dlFlushLatencies.size > 30) dlFlushLatencies.removeFirst()
                        val median = dlFlushLatencies.sorted()[dlFlushLatencies.size / 2]
                        s5RecordCount++
                        s5MedianFlushS = median
                        s5LastRecT = workerLastRecT
                        CmLog.i(TAG, "S5: worker record flush=${flushS}s " +
                            "median=${median}s over ${dlFlushLatencies.size}")
                    }
                }
                if (workerFaultNotified) {
                    workerFaultNotified = false
                    CmLog.i(TAG, "worker heartbeats resumed")
                }
                workerProvisionFaulted = false // worker proof has now arrived
                // Authoritative ALARM recovery from the spooled channel
                // (hardening D3): a v3 record reporting a latched ALARM for
                // an un-escalated episode runs the full alarm path — even
                // if the live AppMessage never arrived. COUNTDOWN is never
                // recovered from the spool (records arrive minutes late).
                val recStage = intent.getIntExtra("stage", -1)
                if (recStage == 3 /* CM_STAGE_ALARM */) {
                    val recEp = intent.getIntExtra("episode", 0)
                    val recDetIdx = intent.getIntExtra("detector", -1)
                    val recDet = Protocol.DETECTOR_NAMES.getOrElse(recDetIdx) { "unknown" }
                    val fresh = if (recEp != 0) !episodeEscalated(recEp)
                        else System.currentTimeMillis() - lastDlRecoveryT > 600_000
                    if (fresh) {
                        if (recEp != 0) markEpisodeEscalated(recEp)
                        lastDlRecoveryT = System.currentTimeMillis()
                        CmLog.w(TAG, "ALARM recovered from DataLogging " +
                            "record (ep=$recEp det=$recDet) — live channel " +
                            "never delivered it")
                        soak.inc(SoakStats.ALARMS)
                        startSiren()
                        showAlarmUi(recDet, preAlarm = false)
                        scope.launch { escalate(recDet) }
                    }
                }
                updateNotification()
            }
            ACTION_SET_DEBUG -> {
                val on = intent.getBooleanExtra("enabled", false)
                CmLog.debugEnabled = on
                CmLog.i(TAG, "debug logging ${if (on) "ENABLED" else "disabled"} " +
                    "(pushing to watch)")
                watchLink.send(mapOf(
                    PebbleTransport.KEY_MSG_TYPE to Protocol.PMSG_SET_DEBUG,
                    PebbleTransport.KEY_SECONDS to (if (on) 1 else 0)))
            }
            ACTION_SET_QMETRIC -> {
                val on = intent.getBooleanExtra("enabled", false) &&
                    watchLink.firmwareIsDevBuild()
                CmLog.i(TAG, "quality gate ${if (on) "ENABLED" else "disabled"} " +
                    "(pushing to watch; firmware dev=" +
                    "${watchLink.firmwareIsDevBuild()})")
                watchLink.send(mapOf(
                    PebbleTransport.KEY_MSG_TYPE to Protocol.PMSG_SET_QMETRIC,
                    PebbleTransport.KEY_SECONDS to (if (on) 1 else 0)))
            }
        }
        return START_STICKY
    }

    /**
     * S1 latency drill: measure the real worker->launch->AppMessage alarm
     * path. Opens the watchapp, arms the worker (which waits a fixed
     * DRILL_DELAY after the app exits, then fires a synthetic alert through
     * the genuine cold path), and times the round trip. Results land in
     * CmLog and the server event feed with the phone model attached.
     */
    private fun runLatencyDrill() {
        CmLog.i(TAG, "latency drill: starting (open watchapp, arm worker)")
        scope.launch {
            watchLink.startWatchapp()
            delay(3_000) // let the watchapp come up and register its inbox
            drillT0 = System.currentTimeMillis()
            watchLink.send(mapOf(
                PebbleTransport.KEY_MSG_TYPE to Protocol.PMSG_DRILL))
            // Result arrives as PMSG_DRILL_RESULT in ~DRILL_DELAY + latency.
        }
    }

    /** DEGRADED = alarms would reach nobody. That is a fault, treated like
     *  one: FAULT-channel notification on the transition into degraded. */
    private fun onDegradedState(degraded: Boolean) {
        if (degraded && !degradedNotified) {
            degradedNotified = true
            notifyFault("No emergency contacts configured — alarms currently " +
                "reach NOBODY except this phone. Open Contacts & safety net.")
        } else if (!degraded && degradedNotified) {
            degradedNotified = false
            CmLog.i(TAG, "degraded cleared: deliverable contacts exist")
        }
    }

    // ---- notifications ----

    private fun buildNotification(text: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(
            CHANNEL_ID, "Monitoring", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(
            CHANNEL_FAULT, "System faults", NotificationManager.IMPORTANCE_HIGH))
        // The status-bar glyph tells the state at a glance: heartbeat trace
        // = all well; bluetooth-off = watch link down (the critical leg);
        // cloud-off = server leg down (phone-direct fallback active).
        val icon = when {
            !watchConnected -> R.drawable.ic_stat_watch_off
            server.configured && !serverReachable -> R.drawable.ic_stat_server_off
            else -> R.drawable.ic_stat_monitor
        }
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Cryonics Monitor")
            .setContentText(text)
            .setSmallIcon(icon)
            .setOngoing(true)
            .setContentIntent(openAppIntent())
            .build()
    }

    private fun openAppIntent(): PendingIntent =
        PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE)

    private fun notifyFault(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_FAULT_ID, Notification.Builder(this, CHANNEL_FAULT)
            .setContentTitle("Cryonics Monitor FAULT")
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentIntent(openAppIntent())
            .build())
    }

    private fun updateNotification() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_ID, buildNotification(statusLine()))
    }

    private fun statusLine(): String {
        // "link" is live Bluetooth truth; "sync" is when the watchAPP last
        // spoke (only possible while it is open) — two different facts that
        // must not be conflated into one scary number.
        val link = if (watchConnected) "watch ✓" else "watch LINK DOWN"
        // A battery value older than an hour is fiction (it only updates
        // when the watchapp speaks) — showing 42% while the watch sits at
        // 85% misleads; drop it rather than lie.
        val battFresh = lastWatchDataT > 0 &&
            System.currentTimeMillis() - lastWatchDataT < 3_600_000
        val wb = if (battFresh) watchBattery?.let { " ${it}%" } ?: "" else ""
        val sync = if (lastWatchDataT == 0L) ""
                   else " · synced ${humanAge(
                       System.currentTimeMillis() - lastWatchDataT)} ago"
        val srv = when {
            !server.configured -> "no server"
            serverReachable -> "server ✓"
            else -> "SERVER: ${server.lastResult}"
        }
        val suspLeft = suspendedUntilT - System.currentTimeMillis()
        val susp = when {
            chargingHold -> "ON CHARGER · "
            suspLeft > 0 -> "SUSPENDED ${(suspLeft / 60000) + 1}m · "
            else -> ""
        }
        val mode = if (storeMode()) " · store app" else ""
        return "$susp$link$wb$sync · $srv$mode"
    }

    override fun onDestroy() {
        stopSiren()
        runCatching {
            getSystemService(android.app.AlarmManager::class.java)
                .cancel(heartbeatTickIntent())
        }
        runCatching { unregisterReceiver(dataLogReceiver) }
        watchLink.stop()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "MonitorService"
        const val CHANNEL_ID = "monitor"
        const val CHANNEL_FAULT = "faults"
        const val CHANNEL_ALARM = "alarms"
        const val NOTIF_ID = 1
        const val NOTIF_FAULT_ID = 2
        const val NOTIF_ALARM_ID = 3
        const val ACTION_USER_CANCEL = "org.cryomonitor.USER_CANCEL"
        const val ACTION_TEST_ALARM = "org.cryomonitor.TEST_ALARM"
        const val ACTION_ALERT_CANCELLED = "org.cryomonitor.ALERT_CANCELLED"
        const val ACTION_SET_DEBUG = "org.cryomonitor.SET_DEBUG"
        const val ACTION_SET_QMETRIC = "org.cryomonitor.SET_QMETRIC"
        const val ACTION_HEARTBEAT_NOW = "org.cryomonitor.HEARTBEAT_NOW"
        const val ACTION_HEARTBEAT_TICK = "org.cryomonitor.HEARTBEAT_TICK"
        private const val HEARTBEAT_INTERVAL_MS = 300_000L
        const val ACTION_LATENCY_DRILL = "org.cryomonitor.LATENCY_DRILL"
        const val ACTION_WORKER_HEARTBEAT = "org.cryomonitor.WORKER_HEARTBEAT"
        const val ACTION_HR_LAB = "org.cryomonitor.HR_LAB"
        const val ACTION_HR_SAMPLE = "org.cryomonitor.HR_SAMPLE"
        private const val SELF_HEAL_MIN_INTERVAL_MS = 60_000L
        // Grace after service start for the FIRST worker proof to appear
        // before faulting (DL spools in ~4-6 min batches; allow two).
        private const val WORKER_PROVISION_GRACE_S = 900L

        // S5 live stats, read by the debug screen.
        @Volatile var s5RecordCount = 0
        @Volatile var s5MedianFlushS = -1L
        @Volatile var s5LastRecT = 0L
    }
}
