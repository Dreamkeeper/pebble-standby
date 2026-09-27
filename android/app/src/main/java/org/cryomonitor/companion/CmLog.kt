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

    /** Directory holding the daily cm-YYYYMMDD.log files (the source of
     *  every diagnostics bundle — see DiagnosticsBundle). */
    fun logDirectory(): File? = logDir

    @Synchronized
    fun clear() {
        ring.clear()
        logDir?.listFiles()?.forEach { it.delete() }
    }
}
