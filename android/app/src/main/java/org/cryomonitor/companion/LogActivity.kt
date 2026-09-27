package org.cryomonitor.companion

import android.app.NotificationManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.util.Date
import java.util.Locale

/**
 * In-app log viewer plus the two ways diagnostics leave the phone
 * (spec: diagnostics-sharing): Share (any app, via the chooser) and Send
 * to the wearer's own server. Both use the same redacted bundle, and
 * both happen only from a tap here — a server request merely waits in
 * the banner until the wearer answers it.
 */
class LogActivity : AppCompatActivity() {

    private lateinit var text: TextView
    private lateinit var scroll: ScrollView
    private lateinit var banner: TextView
    private lateinit var settings: SettingsStore
    private lateinit var server: ServerClient
    private val scope = MainScope()
    private var askedThisVisit = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = SettingsStore(this)
        server = ServerClient(settings)

        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row1.addView(Button(this).apply {
            text = "Share…"; setOnClickListener { share() }
        })
        row1.addView(Button(this).apply {
            text = "Send to my server…"; setOnClickListener { send(null, null) }
        })
        val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row2.addView(Button(this).apply {
            text = "Refresh"; setOnClickListener { refresh() }
        })
        row2.addView(Button(this).apply {
            text = "Clear"
            setOnClickListener {
                AlertDialog.Builder(this@LogActivity)
                    .setTitle("Clear all logs?")
                    .setMessage("Deletes the in-app log and the on-disk daily files. This cannot be undone.")
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Clear") { _, _ -> CmLog.clear(); refresh() }
                    .show()
            }
        })
        col.addView(row1)
        col.addView(row2)

        banner = TextView(this).apply {
            val pad = Ui.dp(context, 12)
            setPadding(pad, pad, pad, pad)
            visibility = View.GONE
            setOnClickListener { answerRequest() }
        }
        col.addView(banner)

        text = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setPadding(Ui.dp(context, 12), Ui.dp(context, 12),
                       Ui.dp(context, 12), Ui.dp(context, 12))
        }
        scroll = ScrollView(this).apply { addView(text) }
        col.addView(scroll)
        Ui.applySystemInsets(col)
        setContentView(col)
    }

    override fun onResume() {
        super.onResume()
        refresh()
        val req = pendingRequest()
        if (req != null && !askedThisVisit) {
            askedThisVisit = true
            answerRequest()
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun refresh() {
        text.text = CmLog.dump().ifEmpty { "(no log lines yet)" }
        scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
        val req = pendingRequest()
        banner.visibility = if (req == null) View.GONE else View.VISIBLE
        if (req != null) {
            banner.text = "Your server asked for the last ${req.days} day(s) of logs " +
                "(${whenText(req.receivedAtMs)}). Tap to answer."
            banner.setBackgroundColor(Ui.primaryContainer(banner))
            banner.setTextColor(Ui.onPrimaryContainer(banner))
        }
    }

    private fun pendingRequest(): DiagnosticsBundle.Request? =
        DiagnosticsBundle.Request.decode(settings.pendingDiagRequest)

    // ---- share ----

    private fun share() = pickRange("Share logs") { days ->
        buildBundle(days) { r ->
            Ui.shareFile(this, r.file, "Standby diagnostics", "Share diagnostics",
                         mime = "application/zip")
        }
    }

    // ---- send to the wearer's own server ----

    private fun send(requestId: String?, days: Int?) {
        if (!server.configured) {
            toast("No server is set up — use Share instead.")
            return
        }
        if (days == null) {
            pickRange("Send logs to your server") { d -> send(requestId, d) }
            return
        }
        buildBundle(days) { r -> confirmSend(r, requestId) }
    }

    private fun confirmSend(r: DiagnosticsBundle.Result, requestId: String?) {
        val host = runCatching { Uri.parse(settings.serverUrl).host }.getOrNull() ?: settings.serverUrl
        val msg = buildString {
            append("Range: last ${r.days} day(s), ${r.from} to ${r.to}\n")
            append("Contents: ${r.logFiles.size} daily log file(s), the soak report and a manifest\n")
            append("Size: ${kb(r.file.length())} compressed (${kb(r.rawBytes)} of logs)\n")
            append("Goes to: $host\n\n")
            append("Removed on this phone before sending:\n")
            DiagnosticsBundle.RULES.forEach { append("• $it\n") }
            append("\nYour server keeps it for 30 days by default; only its admins can open it.")
        }
        AlertDialog.Builder(this)
            .setTitle(if (requestId != null) "Send requested diagnostics?" else "Send diagnostics?")
            .setMessage(msg)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Send") { _, _ -> upload(r, requestId) }
            .show()
    }

    private fun upload(r: DiagnosticsBundle.Result, requestId: String?) {
        toast("Sending…")
        scope.launch {
            val res = withContext(Dispatchers.IO) {
                server.uploadDiagnostics(r.file, r.days, requestId)
            }
            when (res) {
                is ServerClient.UploadResult.Ok -> {
                    CmLog.i(TAG, "diagnostics sent: ${r.days} day(s), ${r.file.length()} B, " +
                        "request=${requestId ?: "-"}")
                    if (requestId != null) clearRequest(requestId)
                    toast("Sent to your server.")
                }
                is ServerClient.UploadResult.Failed -> {
                    CmLog.w(TAG, "diagnostics upload failed: ${res.why}")
                    AlertDialog.Builder(this@LogActivity)
                        .setTitle("Not sent")
                        .setMessage("The server did not take the bundle: ${res.why}. " +
                            "Nothing was lost; try again later or use Share.")
                        .setPositiveButton("OK", null)
                        .show()
                }
            }
            refresh()
        }
    }

    // ---- a request from the server ----

    private fun answerRequest() {
        val req = pendingRequest() ?: return
        AlertDialog.Builder(this)
            .setTitle("Your server asks for diagnostics")
            .setMessage("${whenText(req.receivedAtMs).replaceFirstChar { it.uppercase() }}, " +
                "your Standby server asked for the last ${req.days} day(s) of logs.\n\n" +
                "Nothing has been sent. Review shows exactly what would go before " +
                "anything leaves the phone.")
            .setNeutralButton("Later", null)
            .setNegativeButton("Decline") { _, _ -> decline(req) }
            .setPositiveButton("Review…") { _, _ -> send(req.id, req.days) }
            .show()
    }

    private fun decline(req: DiagnosticsBundle.Request) {
        scope.launch {
            val ok = withContext(Dispatchers.IO) { server.declineDiagnostics(req.id) }
            if (ok) {
                CmLog.i(TAG, "diagnostics request ${req.id} declined")
                clearRequest(req.id)
                toast("Declined. Your server has been told.")
            } else {
                toast("Could not reach your server — the request stays open.")
            }
            refresh()
        }
    }

    private fun clearRequest(id: String) {
        if (pendingRequest()?.id == id) settings.pendingDiagRequest = ""
        getSystemService(NotificationManager::class.java)
            .cancel(MonitorService.NOTIF_DIAG_ID)
    }

    // ---- shared helpers ----

    private fun pickRange(title: String, onPick: (Int) -> Unit) {
        val labels = DiagnosticsBundle.RANGES.map { if (it == 1) "Today" else "Last $it days" }
        var chosen = DiagnosticsBundle.RANGES.indexOf(DiagnosticsBundle.DEFAULT_DAYS)
        AlertDialog.Builder(this)
            .setTitle(title)
            .setSingleChoiceItems(labels.toTypedArray(), chosen) { _, i -> chosen = i }
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Continue") { _, _ -> onPick(DiagnosticsBundle.RANGES[chosen]) }
            .show()
    }

    private fun buildBundle(days: Int, then: (DiagnosticsBundle.Result) -> Unit) {
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    val dir = File(cacheDir, "share").apply { mkdirs() }
                    dir.listFiles()?.forEach { it.delete() }
                    val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
                    DiagnosticsBundle.build(
                        out = File(dir, "standby-diagnostics-$stamp.zip"),
                        logDir = CmLog.logDirectory(),
                        days = days,
                        today = LocalDate.now(),
                        secrets = listOf(settings.apiToken, settings.telegramBotToken),
                        header = header(),
                        soak = SoakStats(this@LogActivity).render(this@LogActivity, settings))
                }.onFailure { CmLog.e(TAG, "diagnostics bundle failed", it) }.getOrNull()
            }
            if (r == null) toast("Could not build the diagnostics bundle.") else then(r)
        }
    }

    private fun header(): String {
        val pi = runCatching { packageManager.getPackageInfo(packageName, 0) }.getOrNull()
        return buildString {
            append("app: Standby companion ${pi?.versionName ?: "?"}\n")
            append("device: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} " +
                "(SDK ${Build.VERSION.SDK_INT})\n")
            append("generated: ${Date()}\n")
        }
    }

    private fun whenText(ms: Long): String =
        if (ms <= 0) "earlier" else "on " + DateFormat.getDateTimeInstance(
            DateFormat.MEDIUM, DateFormat.SHORT).format(Date(ms))

    private fun kb(bytes: Long): String =
        if (bytes < 1024 * 1024) "${(bytes + 1023) / 1024} KB"
        else String.format(Locale.US, "%.1f MB", bytes / 1048576.0)

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private companion object { const val TAG = "LogActivity" }
}
