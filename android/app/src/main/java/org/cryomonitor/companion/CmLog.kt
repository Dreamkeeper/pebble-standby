package org.cryomonitor.companion

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * App-wide logging with a debug toggle.
 *
 * - INFO/WARN/ERROR ("events"): always kept in the ring buffer and file —
 *   alarms, escalations, faults must be reconstructable after the fact.
 * - DEBUG ("chatter"): message dumps, heartbeats, HTTP results — only when
 *   the settings toggle is on.
 *
 * Sink: 4000-line ring buffer (log viewer) + daily file in the app's
 * external files dir (survives restarts, readable without root at
 * Android/data/org.cryomonitor.companion/files/logs/) + logcat.
 */
object CmLog {
    private const val MAX_LINES = 4000
    private val ring = ArrayDeque<String>()
    private var logDir: File? = null
    private val ts = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    private val day = SimpleDateFormat("yyyyMMdd", Locale.US)

    @Volatile var debugEnabled = false

    fun init(context: Context) {
        debugEnabled = SettingsStore(context).debugLogging
        logDir = (context.getExternalFilesDir("logs") ?: File(context.filesDir, "logs"))
            .apply { mkdirs() }
        i("CmLog", "log init, debug=$debugEnabled, dir=$logDir")
    }

    fun d(tag: String, msg: String) { if (debugEnabled) write("D", tag, msg) }
    fun i(tag: String, msg: String) = write("I", tag, msg)
    fun w(tag: String, msg: String) = write("W", tag, msg)
    fun e(tag: String, msg: String, t: Throwable? = null) =
        write("E", tag, msg + (t?.let { " :: ${Log.getStackTraceString(it)}" } ?: ""))

    @Synchronized
    private fun write(level: String, tag: String, msg: String) {
        val line = "${ts.format(Date())} $level/$tag: $msg"
        when (level) {
            "D" -> Log.d(tag, msg); "I" -> Log.i(tag, msg)
            "W" -> Log.w(tag, msg); else -> Log.e(tag, msg)
        }
        ring.addLast(line)
        while (ring.size > MAX_LINES) ring.removeFirst()
        runCatching {
            logDir?.let {
                File(it, "cm-${day.format(Date())}.log").appendText(line + "\n")
            }
        }
    }

    @Synchronized
    fun dump(): String = ring.joinToString("\n")

    /**
     * Write the log to a file for sharing. Sharing the ring as an Intent
     * text extra crashed the whole process on large logs (Binder
     * transaction limit ~1 MB, field 2026-09-07): the chooser never
     * appeared, the in-memory ring died with the process — and so did the
     * monitor service. Files have no such limit. Prefers the on-disk daily
     * files (complete history) and falls back to the ring; capped to the
     * last [maxBytes] so a week of debug chatter still shares quickly.
     */
    @Synchronized
    fun exportForShare(context: Context, maxBytes: Int = 2_000_000): File? = runCatching {
        val dir = File(context.cacheDir, "share").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
        val out = File(dir, "standby-log-$stamp.txt")
        val files = logDir?.listFiles { f -> f.name.startsWith("cm-") && f.name.endsWith(".log") }
            ?.sortedBy { it.name } ?: emptyList()
        val sb = StringBuilder()
        if (files.isNotEmpty()) files.forEach { sb.append(it.readText()) } else sb.append(dump())
        val text = if (sb.length > maxBytes) {
            val cut = sb.indexOf("\n", sb.length - maxBytes) + 1
            "[... ${sb.length - cut} earlier bytes omitted ...]\n" + sb.substring(cut)
        } else sb.toString()
        out.writeText(text)
        out
    }.getOrNull()

    @Synchronized
    fun clear() {
        ring.clear()
        logDir?.listFiles()?.forEach { it.delete() }
    }
}
