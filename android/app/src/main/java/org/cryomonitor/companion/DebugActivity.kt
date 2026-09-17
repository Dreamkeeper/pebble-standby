package org.cryomonitor.companion

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import org.cryomonitor.companion.Ui.caption
import org.cryomonitor.companion.Ui.dataCard
import org.cryomonitor.companion.Ui.title
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Everything debug- and feasibility-related in one place (owner request,
 * M0 test round): debug toggle, latency drill (S1), the guided S4
 * sensor lab with recording + share, live S5 DataLogging stats, and the
 * S6 battery-drain estimate.
 */
class DebugActivity : AppCompatActivity() {


    private lateinit var settings: SettingsStore

    // ---- S4 sensor lab state ----
    private data class Stage(val key: String, val instruction: String,
                             val seconds: Int)

    private val stages = listOf(
        Stage("worn_moving", "Wear the watch snugly.\nMove your arm normally.", 120),
        Stage("worn_still", "Keep wearing it.\nRest your arm — perfectly still.", 180),
        Stage("strap_loose", "Loosen the strap by two holes.\nRest your arm again.", 120),
        Stage("table_flat", "Take the watch OFF.\nLay it screen-UP on the table.", 180),
        Stage("table_facedown", "Flip the watch screen-DOWN\n(sensor facing up).", 120),
        Stage("fabric", "Press the sensor side\nagainst clothing or fabric.", 120),
        Stage("air_dangle", "Hold the watch by the strap\nENDS, sensor toward a lit\nwall ~1 m away, arm raised.", 120))

    /**
     * Lab flow (owner feedback, round 7): instruct FIRST, measure only
     * after the wearer confirms the watch is in position — no scrambling
     * against a countdown, and setup movement never pollutes the data.
     * PREPARING doubles as a preflight: if the watch never streams a
     * sample, the lab says so loudly instead of recording six empty
     * stages (the n=0 failure mode of the first field run).
     */
    private enum class LabState { IDLE, PREPARING, BRIEFING, MEASURING, DONE }

    private var labState = LabState.IDLE
    private var stageIdx = -1
    private var stageEndsAt = 0L
    private var prepareDeadline = 0L
    private var prepareRetries = 0
    private var labStartedAt = 0L
    private val csv = StringBuilder()
    private val stageSamples = HashMap<String, MutableList<Int>>()
    private val stageAges = HashMap<String, MutableList<Int>>()
    private var minHeap = Int.MAX_VALUE
    private var lastResultFile: File? = null
    private val stageQuality = HashMap<String, MutableList<Int>>()
    private val stageFiltered = HashMap<String, MutableList<Int>>()

    private lateinit var labInstruction: TextView
    private lateinit var labLive: TextView
    private lateinit var labResult: TextView
    private lateinit var labBtn: Button
    private lateinit var abortBtn: Button
    private lateinit var shareBtn: Button
    private lateinit var s5Line: TextView
    private lateinit var s6Line: TextView
    private lateinit var dozeLine: TextView
    private lateinit var dozeBtn: Button

    // ---- soak & recovery (spec: companion-resilience) ----
    private lateinit var soak: SoakStats
    private lateinit var soakCard: TextView
    private lateinit var rebootLine: TextView
    private lateinit var outageLine: TextView
    private lateinit var outageBtn: Button

    private enum class OutageState { IDLE, WAIT_DISCONNECT, OFF_HOLD, WAIT_RECONNECT }
    private var outageState = OutageState.IDLE
    private var outageArmT = 0L
    private var outagePowerOnT = 0L
    private var outageDetectS = -1L

    private val sampleReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            intent ?: return
            onLabSample(
                intent.getIntExtra("bpm", 0),
                intent.getIntExtra("quality", 255),
                intent.getIntExtra("filtered", 0),
                intent.getIntExtra("event_age_s", -1),
                intent.getIntExtra("heap", 0))
        }
    }

    /** quality_enc: 0=OffWrist 1=Worst 2=Poor 3=Acceptable 4=Good
     *  5=Excellent 255=n/a (plain lab / stock firmware). */
    private fun qualityLabel(q: Int): String =
        arrayOf("OW", "W", "P", "A", "G", "E").getOrNull(q) ?: "-"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = SettingsStore(this)
        soak = SoakStats(this)

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(context, 16), Ui.dp(context, 16),
                       Ui.dp(context, 16), Ui.dp(context, 16))
        }
        fun header(t: String) = col.addView(TextView(this).apply {
            text = t; title()
            setPadding(0, Ui.dp(context, 24), 0, Ui.dp(context, 8))
        })

        header("Diagnostics")
        @Suppress("UseSwitchCompatOrMaterialCode")
        col.addView(Switch(this).apply {
            text = "Debug mode (extensive logs, phone + watch)"
            isChecked = settings.debugLogging
            setOnCheckedChangeListener { _, on ->
                settings.debugLogging = on
                startService(Intent(this@DebugActivity, MonitorService::class.java)
                    .setAction(MonitorService.ACTION_SET_DEBUG)
                    .putExtra("enabled", on))
            }
        })
        col.addView(Button(this).apply {
            text = "View logs"
            setOnClickListener {
                startActivity(Intent(this@DebugActivity, LogActivity::class.java))
            }
        })
        dozeLine = TextView(this).apply { caption() }
        col.addView(dozeLine)
        // Same system dialog as the main screen's onboarding button —
        // shown here only while the exemption is MISSING, so the healthy
        // state is just the ✓ line (owner feedback 2026-08-29).
        dozeBtn = Button(this).apply {
            text = "Request battery-optimization exemption"
            setOnClickListener {
                @Suppress("BatteryLife")
                runCatching {
                    startActivity(Intent(
                        android.provider.Settings
                            .ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        android.net.Uri.parse("package:$packageName")))
                }.onFailure {
                    Toast.makeText(this@DebugActivity,
                        "Open Settings > Battery manually.", Toast.LENGTH_LONG).show()
                }
            }
        }
        col.addView(dozeBtn)
        @Suppress("UseSwitchCompatOrMaterialCode")
        col.addView(Switch(this).apply {
            text = "Raw HR quality metric (hr-quality-diag firmware ONLY — " +
                "the watchapp crashes on stock firmware). Enables the " +
                "liveness quality gate and per-sample quality in the lab."
            isChecked = settings.labQualityMetric
            setOnCheckedChangeListener { _, on ->
                settings.labQualityMetric = on
                // Push to the worker now (also re-synced on every watchapp
                // heartbeat, since AppMessage needs an open inbox).
                startService(Intent(this@DebugActivity, MonitorService::class.java)
                    .setAction(MonitorService.ACTION_SET_QMETRIC)
                    .putExtra("enabled", on))
            }
        })

        header("S1 — alarm-path latency drill")
        col.addView(TextView(this).apply {
            caption()
            text = "Measured 71 ms on Time 2. Re-run on new phones; results " +
                "land in the logs and the server event feed."
        })
        col.addView(Button(this).apply {
            text = "Run latency drill"
            setOnClickListener {
                startService(Intent(this@DebugActivity, MonitorService::class.java)
                    .setAction(MonitorService.ACTION_LATENCY_DRILL))
                Toast.makeText(this@DebugActivity,
                    "Watchapp opens, closes, relaunches with a buzz in ~13 s.",
                    Toast.LENGTH_LONG).show()
            }
        })

        header("S4 — guided HR sensor lab (~15 min)")
        col.addView(TextView(this).apply {
            caption()
            text = "Answers: what does the raw HR sensor report when worn, " +
                "still, loose, off-wrist, and dangling in air? Records raw " +
                "bpm, per-sample quality (diag firmware), and the filtered " +
                "bpm. Burst sampling; detectors held — no alarms."
        })
        labInstruction = TextView(this).apply {
            title()
            setPadding(0, Ui.dp(context, 8), 0, Ui.dp(context, 8))
            text = "Not running."
        }
        col.addView(labInstruction)
        // M3 role separation: live measurements are DATA — monospace
        // tabular figures on a container surface, never body prose.
        labLive = TextView(this).apply {
            dataCard()
            visibility = android.view.View.GONE
        }
        col.addView(labLive, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = Ui.dp(this@DebugActivity, 4)
                bottomMargin = Ui.dp(this@DebugActivity, 8) })
        labResult = TextView(this).apply {
            dataCard()
            setBackgroundColor(Ui.surfaceVariant(this))
            setTextColor(Ui.onSurfaceVariant(this))
            textSize = 13f
            visibility = android.view.View.GONE
        }
        col.addView(labResult, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = Ui.dp(this@DebugActivity, 8) })
        labBtn = Button(this).apply {
            text = "Start sensor lab"
            setOnClickListener {
                when (labState) {
                    LabState.IDLE, LabState.DONE -> startLab()
                    LabState.BRIEFING -> beginMeasuring()
                    else -> { /* hidden in other states */ }
                }
            }
        }
        col.addView(labBtn)
        abortBtn = Button(this).apply {
            text = "Abort lab"
            visibility = android.view.View.GONE
            setOnClickListener { abortLab() }
        }
        col.addView(abortBtn)
        shareBtn = Button(this).apply {
            text = "Share last lab results"
            visibility = android.view.View.GONE
            setOnClickListener { shareResults() }
        }
        col.addView(shareBtn)

        header("S5 — worker DataLogging liveness (passive)")
        s5Line = TextView(this).apply { caption() }
        col.addView(s5Line)

        header("S6 — battery drain (passive)")
        s6Line = TextView(this).apply { caption() }
        col.addView(s6Line)

        header("S7 — PebbleKit2 end-to-end")
        col.addView(TextView(this).apply {
            caption()
            text = "PASS (field-proven transport). Every latency drill is " +
                "also an automated S7 regression: it exercises the full PK2 " +
                "round trip and records the result per phone model."
        })

        header("Soak & recovery")
        col.addView(TextView(this).apply {
            caption()
            text = "Counters since reset — the evidence base for the 7-day " +
                "soak protocol (docs/SOAK-TEST.md). Alarms here are the " +
                "false-alarm ledger unless a real event happened."
        })
        soakCard = TextView(this).apply { dataCard() }
        col.addView(soakCard, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = Ui.dp(this@DebugActivity, 4)
                bottomMargin = Ui.dp(this@DebugActivity, 8) })
        col.addView(Button(this).apply {
            text = "Share soak report"
            setOnClickListener {
                startActivity(Intent.createChooser(
                    Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_SUBJECT, "standby-soak")
                        putExtra(Intent.EXTRA_TEXT, renderSoak())
                    }, "Share soak report"))
            }
        })
        col.addView(Button(this).apply {
            text = "Reset soak counters"
            setOnClickListener { soak.reset(); refreshPassiveCards() }
        })

        col.addView(TextView(this).apply {
            caption()
            setPadding(0, Ui.dp(context, 16), 0, 0)
            text = "Phone-reboot drill: arm, reboot the phone, then come " +
                "back here — the verdict is automatic. On HyperOS the app " +
                "needs the Autostart permission (Security app) or boot " +
                "recovery is blocked by the OS."
        })
        rebootLine = TextView(this).apply { caption() }
        col.addView(rebootLine)
        col.addView(Button(this).apply {
            text = "Arm reboot drill"
            setOnClickListener {
                soak.set(SoakStats.REBOOT_ARMED_AT, System.currentTimeMillis())
                Toast.makeText(this@DebugActivity,
                    "Armed. Reboot the phone now; check back after boot.",
                    Toast.LENGTH_LONG).show()
                refreshPassiveCards()
            }
        })
        col.addView(Button(this).apply {
            text = "Open Autostart settings (HyperOS)"
            setOnClickListener {
                val ok = runCatching {
                    startActivity(Intent().setClassName(
                        "com.miui.securitycenter",
                        "com.miui.permcenter.autostart.AutoStartManagementActivity"))
                }.isSuccess
                if (!ok) Toast.makeText(this@DebugActivity,
                    "Not a HyperOS device? Open Security → Autostart manually.",
                    Toast.LENGTH_LONG).show()
            }
        })

        col.addView(TextView(this).apply {
            caption()
            setPadding(0, Ui.dp(context, 16), 0, 0)
            text = "Watch-outage drill: measures how fast a dead watch is " +
                "noticed and how fast the link returns after power-on."
        })
        outageLine = TextView(this).apply { caption() }
        col.addView(outageLine)
        outageBtn = Button(this).apply {
            text = "Start watch-outage drill"
            setOnClickListener { onOutageButton() }
        }
        col.addView(outageBtn)

        // Version footer: the first thing to quote in any bug report.
        col.addView(TextView(this).apply {
            caption()
            setPadding(0, Ui.dp(context, 24), 0, Ui.dp(context, 8))
            val pi = runCatching {
                packageManager.getPackageInfo(packageName, 0)
            }.getOrNull()
            val code = pi?.let {
                if (Build.VERSION.SDK_INT >= 28) it.longVersionCode
                else @Suppress("DEPRECATION") it.versionCode.toLong()
            } ?: 0
            text = "Companion v${pi?.versionName ?: "?"} ($code) · " +
                "${Build.MANUFACTURER} ${Build.MODEL} · " +
                "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
        })

        val root = ScrollView(this).apply { addView(col) }
        Ui.applySystemInsets(root)
        setContentView(root)
    }

    // ---- watch-outage drill state machine ----

    private fun onOutageButton() {
        when (outageState) {
            OutageState.IDLE -> {
                outageState = OutageState.WAIT_DISCONNECT
                outageArmT = System.currentTimeMillis()
                outageDetectS = -1
                outageBtn.text = "Abort outage drill"
                Toast.makeText(this, "Power the watch OFF now (hold the " +
                    "back button → Shut Down).", Toast.LENGTH_LONG).show()
            }
            OutageState.OFF_HOLD -> {
                outageState = OutageState.WAIT_RECONNECT
                outagePowerOnT = System.currentTimeMillis()
                outageBtn.text = "Abort outage drill"
            }
            else -> { // abort from either waiting state
                outageState = OutageState.IDLE
                outageBtn.text = "Start watch-outage drill"
            }
        }
        refreshPassiveCards()
    }

    /** Runs from the 1 s ticker: advances the outage drill on link events
     *  recorded by MonitorService, and renders both drill lines. */
    private fun refreshRecovery() {
        when (outageState) {
            OutageState.WAIT_DISCONNECT -> {
                val disc = soak.get(SoakStats.LAST_DISCONNECT_AT)
                if (disc > outageArmT) {
                    outageDetectS = (disc - outageArmT) / 1000
                    outageState = OutageState.OFF_HOLD
                    outageBtn.text = "WATCH IS POWERING ON NOW"
                    buzz()
                } else outageLine.text = "Waiting for the disconnect… " +
                    "(${(System.currentTimeMillis() - outageArmT) / 1000}s " +
                    "since arm — power the watch off)"
            }
            OutageState.OFF_HOLD -> {
                val offFor = (System.currentTimeMillis() - outageArmT) / 1000
                outageLine.text = "Disconnect detected in ${outageDetectS}s. " +
                    "Leave the watch OFF ≥5 min (${offFor / 60}m elapsed), " +
                    "then power it on and press the button."
            }
            OutageState.WAIT_RECONNECT -> {
                val rec = soak.get(SoakStats.LAST_RECONNECT_AT)
                if (rec > outagePowerOnT) {
                    val reconnectS = (rec - outagePowerOnT) / 1000
                    soak.set(SoakStats.OUTAGE_AT, System.currentTimeMillis())
                    soak.set(SoakStats.OUTAGE_DETECT_S, outageDetectS)
                    soak.set(SoakStats.OUTAGE_RECONNECT_S, reconnectS)
                    outageState = OutageState.IDLE
                    outageBtn.text = "Start watch-outage drill"
                    buzz(); buzz()
                } else outageLine.text = "Waiting for the link to return… " +
                    "(${(System.currentTimeMillis() - outagePowerOnT) / 1000}s " +
                    "since power-on)"
            }
            OutageState.IDLE -> {
                outageLine.text = if (soak.get(SoakStats.OUTAGE_AT) == 0L)
                    "Not run yet."
                else "Last run: disconnect detected in " +
                    "${soak.get(SoakStats.OUTAGE_DETECT_S)}s · power-on → " +
                    "link up in ${soak.get(SoakStats.OUTAGE_RECONNECT_S)}s " +
                    "(includes watch boot)"
            }
        }

        rebootLine.text = when (val v = RebootDrill.verdict(
            soak.get(SoakStats.REBOOT_ARMED_AT),
            System.currentTimeMillis(),
            android.os.SystemClock.elapsedRealtime(),
            soak.get(SoakStats.RECEIVER_FIRED_AT),
            soak.get(SoakStats.BOOT_RECOVERY_AT),
            soak.get(SoakStats.BOOT_RECOVERY_DELAY_S))) {
            RebootDrill.Verdict.NotArmed -> "Not armed."
            RebootDrill.Verdict.WaitingForReboot ->
                "ARMED — reboot the phone now, then come back here."
            is RebootDrill.Verdict.Pass ->
                "PASS — monitoring was back ${v.bootToServiceS}s after boot, " +
                "no user action."
            RebootDrill.Verdict.FailServiceStart ->
                "FAIL — boot receiver ran but the service did not start " +
                "(check the logs)."
            RebootDrill.Verdict.FailAutostart ->
                "FAIL — the boot broadcast never arrived: enable Autostart " +
                "for this app (Security app), then re-arm."
        }
    }

    private fun renderSoak(): String {
        val resetAt = soak.get(SoakStats.RESET_AT)
        val days = if (resetAt == 0L) 0.0
                   else (System.currentTimeMillis() - resetAt) / 86_400_000.0
        return buildString {
            val ver = runCatching {
                packageManager.getPackageInfo(packageName, 0).versionName
            }.getOrNull() ?: "?"
            append("# standby soak ${Date()} (${Build.MODEL}, companion v$ver)\n")
            append("window: ${"%.1f".format(days)} days since " +
                "${if (resetAt == 0L) "-" else Date(resetAt).toString()}\n")
            append("service starts: boot=${soak.get(SoakStats.STARTS_BOOT)} " +
                "update=${soak.get(SoakStats.STARTS_UPDATE)} " +
                "other=${soak.get(SoakStats.STARTS_OTHER)}\n")
            append("watch link: disconnects=${soak.get(SoakStats.DISCONNECTS)} " +
                "downtime=${soak.get(SoakStats.DOWNTIME_S) / 60}m " +
                "link-faults=${soak.get(SoakStats.LINK_FAULTS)} " +
                "self-heals=${soak.get(SoakStats.SELF_HEALS)}\n")
            val storeTag = if (PebbleAppPolicy.storeMode(
                    PebbleAppPolicy.parse(settings.pebbleAppMode), settings.dlEverSeen))
                " (store-app mode)" else ""
            append("worker: dl-records=${soak.get(SoakStats.DL_RECORDS)}$storeTag " +
                "faults=${soak.get(SoakStats.WORKER_FAULTS)} " +
                "sensor-faults=${soak.get(SoakStats.SENSOR_FAULTS)} " +
                "notworn-nags=${soak.get(SoakStats.NOTWORN_NAGS)}\n")
            if (soak.get(SoakStats.WORKER_HEAP_LAST) > 0)
                append("worker heap: last=${soak.get(SoakStats.WORKER_HEAP_LAST)}B " +
                    "min=${soak.get(SoakStats.WORKER_HEAP_MIN)}B " +
                    "(gate: warn <512B)\n")
            append("alarms: pre=${soak.get(SoakStats.PREALARMS)} " +
                "full=${soak.get(SoakStats.ALARMS)} " +
                "server-fails=${soak.get(SoakStats.SERVER_FAILS)}\n")
            if (soak.get(SoakStats.OUTAGE_AT) > 0)
                append("outage drill: detect=${soak.get(SoakStats.OUTAGE_DETECT_S)}s " +
                    "reconnect=${soak.get(SoakStats.OUTAGE_RECONNECT_S)}s\n")
            if (soak.get(SoakStats.BOOT_RECOVERY_AT) > 0)
                append("last boot recovery: " +
                    "${soak.get(SoakStats.BOOT_RECOVERY_DELAY_S)}s after boot\n")
        }
    }

    override fun onResume() {
        super.onResume()
        if (Build.VERSION.SDK_INT >= 33)
            registerReceiver(sampleReceiver,
                IntentFilter(MonitorService.ACTION_HR_SAMPLE),
                Context.RECEIVER_NOT_EXPORTED)
        else @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(sampleReceiver,
                IntentFilter(MonitorService.ACTION_HR_SAMPLE))
        refreshPassiveCards()
        labInstruction.postDelayed(ticker, 1000)
    }

    override fun onPause() {
        super.onPause()
        runCatching { unregisterReceiver(sampleReceiver) }
        labInstruction.removeCallbacks(ticker)
    }

    override fun onDestroy() {
        if (labState != LabState.IDLE && labState != LabState.DONE) stopLabOnWatch()
        super.onDestroy()
    }

    // ---- lab engine ----

    private val ticker = object : Runnable {
        override fun run() {
            when (labState) {
                LabState.PREPARING -> {
                    // Auto-open can race or fail silently (PK2 start refused,
                    // lab-on sent before the inbox registered): retry the
                    // whole start twice before giving up.
                    val elapsed = System.currentTimeMillis() -
                        (prepareDeadline - PREFLIGHT_MS)
                    if (elapsed > (prepareRetries + 1) * 8_000L &&
                        prepareRetries < 2) {
                        prepareRetries++
                        CmLog.i("S4Lab", "no samples yet — retry #$prepareRetries")
                        startService(Intent(this@DebugActivity,
                            MonitorService::class.java)
                            .setAction(MonitorService.ACTION_HR_LAB)
                            .putExtra("on", true))
                    }
                    if (System.currentTimeMillis() > prepareDeadline) failPreflight()
                }
                LabState.MEASURING -> {
                    val left = ((stageEndsAt - System.currentTimeMillis()) / 1000)
                        .coerceAtLeast(0)
                    if (left == 0L) endStage() else updateMeasuringHeader(left)
                }
                else -> {}
            }
            refreshPassiveCards()
            labInstruction.postDelayed(this, 1000)
        }
    }

    private fun startLab() {
        labState = LabState.PREPARING
        stageIdx = -1
        csv.clear()
        csv.append("t_rel_s,stage,bpm,quality,filtered_bpm,event_age_s,heap_bytes\n")
        stageSamples.clear(); stageAges.clear()
        stageQuality.clear(); stageFiltered.clear()
        minHeap = Int.MAX_VALUE
        prepareRetries = 0
        labStartedAt = System.currentTimeMillis()
        prepareDeadline = labStartedAt + PREFLIGHT_MS
        labBtn.visibility = android.view.View.GONE
        abortBtn.visibility = android.view.View.VISIBLE
        shareBtn.visibility = android.view.View.GONE
        labResult.visibility = android.view.View.GONE
        labLive.visibility = android.view.View.GONE
        labLive.text = ""
        startService(Intent(this, MonitorService::class.java)
            .setAction(MonitorService.ACTION_HR_LAB).putExtra("on", true))
        labInstruction.text = "Starting sensor lab on the watch…\n" +
            "(the watchapp opens by itself; waiting for the first sample)"
    }

    /** No samples within the preflight window: name the likely cause. */
    private fun failPreflight() {
        stopLabOnWatch()
        labState = LabState.IDLE
        labBtn.text = "Start sensor lab"
        labBtn.visibility = android.view.View.VISIBLE
        abortBtn.visibility = android.view.View.GONE
        labLive.visibility = android.view.View.GONE
        labInstruction.text = "NO SAMPLES from the watch — lab aborted.\n" +
            "Most likely the watchapp is not v0.4.0+ (check the version in " +
            "the Core app), the watch is disconnected, or the watchapp " +
            "could not open. Nothing was recorded."
        CmLog.w("S4Lab", "preflight failed: no samples within 25 s")
    }

    private fun showBriefing() {
        labState = LabState.BRIEFING
        val st = stages[stageIdx]
        labInstruction.text =
            "Prepare stage ${stageIdx + 1}/${stages.size} " +
            "(${st.seconds}s):\n${st.instruction}\n\n" +
            "Set the watch up, then press START — measurement begins only " +
            "after you confirm."
        labBtn.text = "WATCH IS IN POSITION — START STAGE ${stageIdx + 1}"
        labBtn.visibility = android.view.View.VISIBLE
        buzz()
    }

    private fun beginMeasuring() {
        labState = LabState.MEASURING
        labBtn.visibility = android.view.View.GONE
        stageEndsAt = System.currentTimeMillis() + stages[stageIdx].seconds * 1000L
        updateMeasuringHeader(stages[stageIdx].seconds.toLong())
    }

    private fun endStage() {
        buzz()
        if (stageIdx + 1 >= stages.size) finishLab()
        else { stageIdx++; showBriefing() }
    }

    private fun updateMeasuringHeader(left: Long) {
        val st = stages[stageIdx]
        labInstruction.text = "MEASURING stage ${stageIdx + 1}/${stages.size} " +
            "— ${left}s left\n${st.instruction.replace('\n', ' ')}\n" +
            "(hold this condition until the buzz)"
    }

    private fun onLabSample(bpm: Int, quality: Int, filtered: Int, age: Int, heap: Int) {
        if (labState == LabState.IDLE || labState == LabState.DONE) return
        if (heap in 1 until minHeap) minHeap = heap
        labLive.visibility = android.view.View.VISIBLE
        labLive.text = String.format(Locale.US,
            "raw %3d q:%-2s filt %3d\nage %3d s   heap %4d B",
            bpm, qualityLabel(quality), filtered, age, heap)
        if (labState == LabState.PREPARING) {
            // First sample = the watch is in lab mode: brief stage 1.
            stageIdx = 0
            showBriefing()
            return
        }
        if (labState != LabState.MEASURING) return  /* setup time: not recorded */
        val st = stages.getOrNull(stageIdx) ?: return
        val tRel = (System.currentTimeMillis() - labStartedAt) / 1000
        csv.append("$tRel,${st.key},$bpm,${qualityLabel(quality)},$filtered,$age,$heap\n")
        stageSamples.getOrPut(st.key) { mutableListOf() }.add(bpm)
        stageAges.getOrPut(st.key) { mutableListOf() }.add(age)
        stageQuality.getOrPut(st.key) { mutableListOf() }.add(quality)
        stageFiltered.getOrPut(st.key) { mutableListOf() }.add(filtered)
    }

    private fun finishLab() {
        stopLabOnWatch()
        labState = LabState.DONE
        labBtn.text = "Start sensor lab"
        labBtn.visibility = android.view.View.VISIBLE
        abortBtn.visibility = android.view.View.GONE
        buzz(); buzz()
        val summary = buildSummary()
        val dir = getExternalFilesDir("logs") ?: File(filesDir, "logs")
        dir.mkdirs()
        val f = File(dir,
            "s4-hr-lab-${SimpleDateFormat("yyyyMMdd-HHmmss",
                Locale.US).format(Date())}.csv")
        runCatching { f.writeText(summary + "\n" + csv.toString()) }
        lastResultFile = f
        labInstruction.text = "DONE — results below."
        labLive.visibility = android.view.View.GONE
        labResult.visibility = android.view.View.VISIBLE
        labResult.text = summary.trimEnd()
        shareBtn.visibility = android.view.View.VISIBLE
        CmLog.i("S4Lab", "lab complete, ${csv.lines().size} lines -> ${f.name}")
    }

    private fun abortLab() {
        stopLabOnWatch()
        labState = LabState.IDLE
        labBtn.text = "Start sensor lab"
        labBtn.visibility = android.view.View.VISIBLE
        abortBtn.visibility = android.view.View.GONE
        labLive.visibility = android.view.View.GONE
        labLive.text = ""
        labResult.visibility = android.view.View.GONE
        labInstruction.text = "Aborted — nothing shared, partial file not kept."
    }

    private fun stopLabOnWatch() {
        startService(Intent(this, MonitorService::class.java)
            .setAction(MonitorService.ACTION_HR_LAB).putExtra("on", false))
    }

    private fun buildSummary(): String {
        val sb = StringBuilder("# S4 HR sensor lab ${Date()}\n")
        sb.append("# model=${Build.MODEL} minWorkerHeap=" +
            "${if (minHeap == Int.MAX_VALUE) "-" else "${minHeap}B"}\n")
        for (st in stages) {
            val s = stageSamples[st.key] ?: emptyList()
            val nz = s.filter { it > 0 }
            val ages = stageAges[st.key] ?: emptyList()
            val qs = (stageQuality[st.key] ?: emptyList()).filter { it != 255 }
            val qDist = if (qs.isEmpty()) "" else " q[" +
                (0..5).mapNotNull { q ->
                    val n = qs.count { it == q }
                    if (n > 0) "${qualityLabel(q)}×$n" else null
                }.joinToString(" ") + "]"
            val filt = stageFiltered[st.key] ?: emptyList()
            val fNz = filt.count { it > 0 }
            val fInfo = if (filt.isEmpty()) ""
                else " filtNonzero=${fNz * 100 / filt.size}%"
            sb.append("# ${st.key}: n=${s.size} " +
                "nonzero=${nz.size} (${if (s.isEmpty()) 0
                    else nz.size * 100 / s.size}%) " +
                (if (nz.isEmpty()) "bpm=-" else
                    "bpm=${nz.min()}..${nz.sorted()[nz.size / 2]}..${nz.max()}") +
                " medianEventAge=${if (ages.isEmpty()) "-" else
                    "${ages.sorted()[ages.size / 2]}s"}$qDist$fInfo\n")
        }
        return sb.toString()
    }

    private fun shareResults() {
        val f = lastResultFile ?: return
        Ui.shareFile(this, f, f.name, "Share S4 lab results")
    }

    private companion object { const val PREFLIGHT_MS = 30_000L }

    private fun buzz() {
        runCatching {
            @Suppress("DEPRECATION")
            (getSystemService(VIBRATOR_SERVICE) as Vibrator)
                .vibrate(VibrationEffect.createOneShot(400,
                    VibrationEffect.DEFAULT_AMPLITUDE))
        }
    }

    // ---- passive cards ----

    private fun refreshPassiveCards() {
        // Doze posture: both must be green or heartbeats stall (2026-08-29:
        // all-day late-flapping + one phone_silent advisory from deferral).
        val pm = getSystemService(android.os.PowerManager::class.java)
        val am = getSystemService(android.app.AlarmManager::class.java)
        val exempt = pm.isIgnoringBatteryOptimizations(packageName)
        val exact = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
        dozeLine.text =
            (if (exempt) "Battery optimization: EXEMPT ✓"
             else "Battery optimization: NOT EXEMPT — heartbeats will " +
                 "stall in Doze (use the button below)") + "\n" +
            (if (exact) "Exact alarms: allowed ✓"
             else "Exact alarms: BLOCKED — Settings > Apps > Special " +
                 "app access > Alarms & reminders")
        dozeBtn.visibility =
            if (exempt) android.view.View.GONE else android.view.View.VISIBLE
        soakCard.text = renderSoak().lines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .joinToString("\n")
        refreshRecovery()
        val mode = PebbleAppPolicy.parse(settings.pebbleAppMode)
        val storeNow = PebbleAppPolicy.storeMode(mode, settings.dlEverSeen)
        val syncMin = PebbleAppPolicy.effectiveSyncMin(storeNow, settings.watchSyncIntervalMin)
        s5Line.text = if (MonitorService.s5RecordCount == 0)
            PebbleAppPolicy.describe(mode, settings.dlEverSeen, syncMin) +
            (if (storeNow) "" else " No records yet: keep the watchapp CLOSED and " +
                "wear the watch ≥1 h; the patched Pebble app delivers them in ~5 min batches.")
        else {
            val age = (System.currentTimeMillis() -
                MonitorService.s5LastRecT) / 1000
            // Hardware truth (2026-08-28): the watch spools DataLogging in
            // ~4 min batches; per-minute delivery is not on offer. Any
            // record flow at all = the channel works.
            val m = MonitorService.s5MedianFlushS
            val verdict = when {
                m in 0..90 -> "→ DELIVERING (near-realtime)"
                m > 90 -> "→ DELIVERING (batched, ~${(m + 30) / 60} min)"
                else -> ""
            }
            "records=${MonitorService.s5RecordCount} via ${MonitorService.s5Transport} · median flush=${m}s · " +
                "last ${age}s ago $verdict"
        }

        val pts = getSharedPreferences("batt_hist", MODE_PRIVATE)
            .getString("points", "")!!.split(';').filter { it.isNotEmpty() }
            .mapNotNull { p ->
                p.split(':').takeIf { it.size == 2 }?.let {
                    (it[0].toLongOrNull() ?: return@mapNotNull null) to
                        (it[1].toIntOrNull() ?: return@mapNotNull null)
                }
            }
        // Sum only DESCENDING segments: any rise = a charge, and naive
        // first-minus-last across a charge inflates the projection
        // wildly (field: "62.9 days" from a window with a charge in it).
        var dropPct = 0
        var dischargeH = 0.0
        for (i in 1 until pts.size) {
            val dt = (pts[i].first - pts[i - 1].first) / 3600.0
            val dp = pts[i - 1].second - pts[i].second
            if (dp > 0 && dt < 48) { dropPct += dp; dischargeH += dt }
        }
        s6Line.text = if (dropPct == 0 || dischargeH < 6)
            "Collecting discharge data (${dropPct}% over " +
            "${"%.1f".format(dischargeH)} h so far; charge segments " +
            "excluded; need ≥6 h). Also on the dashboard battery trail."
        else {
            val perH = dropPct / dischargeH
            "${"%.2f".format(perH)} %/h over ${"%.1f".format(dischargeH)} h " +
                "of discharge (charging excluded) → " +
                "${"%.1f".format(100 / perH / 24)} days projected " +
                if (100 / perH / 24 >= 7) "→ GO (gate ≥7 days)"
                else "→ under the 7-day gate"
        }
    }
}
