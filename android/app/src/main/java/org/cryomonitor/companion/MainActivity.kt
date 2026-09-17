package org.cryomonitor.companion

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.cryomonitor.companion.Ui.caption
import org.cryomonitor.companion.Ui.title
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    private lateinit var settings: SettingsStore
    private lateinit var degradedBanner: TextView
    private lateinit var connState: TextView
    private lateinit var resolveBtn: Button
    @Volatile private var activeEscalationIds: List<String> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = SettingsStore(this)
        CmLog.init(this)
        requestPermissionsThenStartService()

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(context, 16), Ui.dp(context, 16), Ui.dp(context, 16), Ui.dp(context, 16))
        }

        // The safety-net fault banner: as loud as a FAULT, because an alarm
        // that reaches nobody is one (spec requirement).
        degradedBanner = TextView(this).apply {
            text = "⚠ ALARMS REACH NOBODY — no emergency contacts configured.\n" +
                "Tap 'Contacts & safety net' and add at least one."
            setBackgroundColor(Ui.errorContainer(this))
            setTextColor(Ui.onErrorContainer(this))
            title()
            setPadding(Ui.dp(context, 16), Ui.dp(context, 16),
                       Ui.dp(context, 16), Ui.dp(context, 16))
            visibility = android.view.View.GONE
            setOnClickListener {
                startActivity(Intent(this@MainActivity, ContactsActivity::class.java))
            }
        }
        col.addView(degradedBanner)

        connState = TextView(this).apply {
            caption()
            setPadding(0, Ui.dp(context, 8), 0, Ui.dp(context, 16))
        }
        col.addView(connState)

        // Visible only while escalations are active: one tap declares
        // "I'm OK" for all of them — same semantics as resolving each as
        // a false alarm from the dashboard, recorded in the audit log.
        resolveBtn = Button(this).apply {
            visibility = android.view.View.GONE
            setBackgroundColor(Ui.errorContainer(this))
            setTextColor(Ui.onErrorContainer(this))
            setOnClickListener {
                val ids = activeEscalationIds
                if (ids.isEmpty()) return@setOnClickListener
                thread {
                    val client = ServerClient(settings)
                    val okAll = ids.map { client.resolve(it, "false_alarm") }
                        .all { it }
                    runOnUiThread {
                        Toast.makeText(this@MainActivity,
                            if (okAll) "Escalation(s) resolved as false alarm"
                            else "Some resolves failed — check the dashboard",
                            Toast.LENGTH_LONG).show()
                        refreshStatus()
                    }
                }
            }
        }
        col.addView(resolveBtn)

        fun header(t: String) = col.addView(TextView(this).apply {
            text = t
            title()
            setPadding(0, Ui.dp(context, 24), 0, Ui.dp(context, 8))
        })

        header("Setup")
        col.addView(Button(this).apply {
            text = "Enroll with a code (recommended)"
            setOnClickListener {
                startActivity(Intent(this@MainActivity, EnrollActivity::class.java))
            }
        })
        col.addView(Button(this).apply {
            text = "Contacts & safety net"
            setOnClickListener {
                startActivity(Intent(this@MainActivity, ContactsActivity::class.java))
            }
        })
        col.addView(Button(this).apply {
            text = "Allow running in background (battery exemption)"
            setOnClickListener {
                val pm = getSystemService(PowerManager::class.java)
                if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                    startActivity(Intent(
                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:$packageName")))
                } else Toast.makeText(this@MainActivity,
                    "Already exempt", Toast.LENGTH_SHORT).show()
            }
        })
        col.addView(Button(this).apply {
            text = "Allow alarm over the lock screen"
            setOnClickListener { openAlarmDisplaySettings() }
        })

        header("Diagnostics")
        col.addView(Button(this).apply {
            text = "Debug & feasibility tests"
            setOnClickListener {
                startActivity(Intent(this@MainActivity, DebugActivity::class.java))
            }
        })

        // Manual configuration is the fallback path, not the front door.
        header("Advanced")
        val advanced = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = android.view.View.GONE
        }
        col.addView(Button(this).apply {
            text = "Show manual configuration"
            setOnClickListener {
                advanced.visibility = if (advanced.visibility ==
                    android.view.View.GONE) android.view.View.VISIBLE
                else android.view.View.GONE
            }
        })
        col.addView(advanced)

        fun advLabel(t: String) = advanced.addView(TextView(this).apply { text = t })
        fun advField(hint: String, value: String): EditText {
            val e = EditText(this).apply { setHint(hint); setText(value) }
            advanced.addView(e)
            return e
        }

        advLabel("Server URL")
        val fUrl = advField("https://cm.example.com", settings.serverUrl)
        advLabel("API token (enrollment sets this automatically)")
        val fTok = advField("token", settings.apiToken)
        advLabel("SMS contacts (comma-separated numbers, phone-direct fallback)")
        val fSms = advField("+491701234567",
                            settings.smsContacts.joinToString(","))
        advLabel("Telegram bot token (phone-direct fallback channel)")
        val fTg = advField("12345:ABC…", settings.telegramBotToken)
        advLabel("Telegram chat ids (phone-direct fallback)")
        val fTgIds = advField("1122334455", settings.telegramChatIds.joinToString(","))
        advanced.addView(Button(this).apply {
            text = "Test fallback bot (sends a [TEST] message now)"
            setOnClickListener {
                // Test what is typed, not what was last saved.
                settings.telegramBotToken = fTg.text.toString().trim()
                settings.telegramChatIds = fTgIds.text.toString().split(',')
                    .map { it.trim() }.filter { it.isNotEmpty() }
                isEnabled = false
                Thread {
                    val lines = Escalator(this@MainActivity, settings).testTelegramDirect()
                    runOnUiThread {
                        isEnabled = true
                        androidx.appcompat.app.AlertDialog.Builder(this@MainActivity)
                            .setTitle("Fallback bot test")
                            .setMessage(lines.joinToString("\n\n") +
                                "\n\nThis channel is used only when an alarm fires and " +
                                "the server cannot be reached.")
                            .setPositiveButton("OK", null).show()
                    }
                }.start()
            }
        })
        advLabel("Emergency number for one-tap dial")
        val fEmg = advField("112 / 911", settings.emergencyNumber)
        advLabel("Wearer name (used in alert texts)")
        val fName = advField("name", settings.wearerName)
        advLabel("Watch sync interval, minutes (0 = off). The watchapp " +
            "briefly opens to refresh liveness + battery when data is " +
            "older than this.")
        val fSync = advField("60", settings.watchSyncIntervalMin.toString())
        advLabel("Pebble app on this phone: auto / patched / store. " +
            "'store' = stock Pebble app (no worker telemetry; liveness via " +
            "sync launches, hourly if the interval above is 0). Auto " +
            "switches to 'patched' once worker records arrive.")
        val fMode = advField("auto", settings.pebbleAppMode)

        advanced.addView(Button(this).apply {
            text = "Save"
            setOnClickListener {
                settings.serverUrl = fUrl.text.toString().trim()
                settings.apiToken = fTok.text.toString().trim()
                settings.smsContacts = fSms.text.toString().split(',')
                    .map { it.trim() }.filter { it.isNotEmpty() }
                settings.telegramBotToken = fTg.text.toString().trim()
                settings.telegramChatIds = fTgIds.text.toString().split(',')
                    .map { it.trim() }.filter { it.isNotEmpty() }
                settings.emergencyNumber = fEmg.text.toString().trim()
                    .ifEmpty { "112" }
                settings.wearerName = fName.text.toString().trim()
                settings.watchSyncIntervalMin =
                    fSync.text.toString().trim().toIntOrNull() ?: 60
                settings.pebbleAppMode = fMode.text.toString().trim().lowercase()
                    .takeIf { it in setOf("auto", "patched", "store") } ?: "auto"
                startService(Intent(this@MainActivity, MonitorService::class.java)
                    .setAction(MonitorService.ACTION_HEARTBEAT_NOW))
                Toast.makeText(this@MainActivity,
                    "Saved — check the notification for server status",
                    Toast.LENGTH_LONG).show()
            }
        })

        val root = ScrollView(this).apply { addView(col) }
        Ui.applySystemInsets(root)
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun refreshStatus() {
        if (settings.serverUrl.isEmpty()) {
            connState.text = "Not connected to a server yet — enroll below."
            return
        }
        thread {
            val client = ServerClient(settings)
            val s = client.fetchStatus()
            runOnUiThread {
                if (s == null) {
                    connState.text = "Server: ${client.lastResult}"
                    return@runOnUiThread
                }
                degradedBanner.visibility =
                    if (s.degraded) android.view.View.VISIBLE
                    else android.view.View.GONE
                connState.text = "Server connected · phone state '${s.phone}'" +
                    if (s.activeEscalations > 0)
                        " · ${s.activeEscalations} ACTIVE ESCALATION(S)" else ""
                activeEscalationIds = s.escalationIds
                resolveBtn.text =
                    "I'M OK — resolve ${s.activeEscalations} escalation(s)"
                resolveBtn.visibility = if (s.activeEscalations > 0)
                    android.view.View.VISIBLE else android.view.View.GONE
            }
        }
    }

    /**
     * The screen-takeover alarm needs OEM blessings that no runtime dialog
     * can request. On Xiaomi (MIUI/HyperOS) the gate is "Show on lock
     * screen" + "Display pop-up windows while running in the background"
     * inside the app's Other-permissions page; the MIUI permission editor
     * intent lands there directly. Fallbacks: the stock full-screen-intent
     * page (Android 14+), then plain app details.
     */
    private fun openAlarmDisplaySettings() {
        Toast.makeText(this,
            "Enable 'Show on lock screen' and 'Display pop-up windows " +
            "while running in the background'", Toast.LENGTH_LONG).show()
        val attempts = mutableListOf(
            Intent("miui.intent.action.APP_PERM_EDITOR").apply {
                setClassName("com.miui.securitycenter",
                    "com.miui.permcenter.permissions.PermissionsEditorActivity")
                putExtra("extra_pkgname", packageName)
            })
        if (Build.VERSION.SDK_INT >= 34) {
            attempts += Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
                Uri.parse("package:$packageName"))
        }
        attempts += Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:$packageName"))
        for (intent in attempts) {
            try {
                startActivity(intent)
                CmLog.i("MainActivity", "alarm-display settings via ${intent.action}")
                return
            } catch (_: Exception) { /* next fallback */ }
        }
    }

    private fun requestPermissionsThenStartService() {
        val wanted = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 31) wanted += Manifest.permission.BLUETOOTH_CONNECT
        if (Build.VERSION.SDK_INT >= 33) wanted += Manifest.permission.POST_NOTIFICATIONS
        wanted += Manifest.permission.ACCESS_FINE_LOCATION
        val missing = wanted.filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) startMonitorService()
        else requestPermissions(missing.toTypedArray(), REQ_PERMS)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_PERMS) startMonitorService()
    }

    private fun startMonitorService() {
        try {
            startForegroundService(Intent(this, MonitorService::class.java))
        } catch (e: Exception) {
            CmLog.e("MainActivity", "failed to start MonitorService", e)
        }
    }

    private companion object { const val REQ_PERMS = 41 }
}
