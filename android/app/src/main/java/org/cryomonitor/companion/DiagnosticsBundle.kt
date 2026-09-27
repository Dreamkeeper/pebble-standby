package org.cryomonitor.companion

import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * The one diagnostics bundle behind both Share and "Send to server"
 * (consented-diagnostics D1): the daily log files for a chosen range,
 * the soak report and a manifest, redacted line by line, zipped.
 *
 * Replaces the old 2 MB text tail, which silently dropped everything
 * older than about four days (field 2026-09-27: the day holding all 46
 * not-worn nags was missing). Pure JVM — no Android types — so the
 * range selection and redaction are unit-tested directly.
 */
object DiagnosticsBundle {
    val RANGES = listOf(1, 3, 7, 14)
    const val DEFAULT_DAYS = 7

    /** Plain-language redaction rules, shown in the confirmation dialog
     *  and written into the manifest (design D2). */
    val RULES = listOf(
        "the stored server token and fallback bot token",
        "anything shaped like a Telegram bot token, a Bearer credential or a token= value",
        "precise coordinates (rounded to 2 decimals, about 1 km)",
        "Telegram chat ids (only the last 3 digits are kept)",
    )

    data class Result(
        val file: File,
        val days: Int,
        val from: LocalDate,
        val to: LocalDate,
        val logFiles: List<String>,
        val rawBytes: Long,
        val redactedLines: Int,
    )

    private val DAY = DateTimeFormatter.ofPattern("yyyyMMdd", Locale.US)
    private val LOG_NAME = Regex("""cm-(\d{8})\.log""")

    /** A server request as carried by the leased command channel:
     *  "diag_request:<id>:<days>" (design D4). */
    data class Request(val id: String, val days: Int, val receivedAtMs: Long = 0L) {
        fun encode(): String = "$id|$days|$receivedAtMs"

        companion object {
            private val ID = Regex("""[A-Za-z0-9_-]{1,64}""")

            fun fromCommand(cmd: String, nowMs: Long): Request? {
                val parts = cmd.split(':')
                if (parts.size != 3 || parts[0] != "diag_request") return null
                if (!ID.matches(parts[1])) return null
                val days = parts[2].toIntOrNull()?.takeIf { it > 0 } ?: return null
                return Request(parts[1], normalizeDays(days), nowMs)
            }

            fun decode(stored: String): Request? {
                val p = stored.split('|')
                if (p.size != 3 || !ID.matches(p[0])) return null
                val days = p[1].toIntOrNull() ?: return null
                return Request(p[0], normalizeDays(days), p[2].toLongOrNull() ?: 0L)
            }
        }
    }

    /** Clamp an arbitrary day count (e.g. from a server request) to the
     *  nearest offered range, never exceeding the largest one. */
    fun normalizeDays(days: Int): Int =
        RANGES.firstOrNull { it >= days } ?: RANGES.last()

    /** Daily files dated today and the [days]-1 days before, oldest first.
     *  Chosen by the date in the name, not mtime (design D1). */
    fun selectFiles(logDir: File?, days: Int, today: LocalDate): List<File> {
        val from = today.minusDays((days - 1).toLong())
        return (logDir?.listFiles() ?: emptyArray())
            .mapNotNull { f ->
                val m = LOG_NAME.matchEntire(f.name) ?: return@mapNotNull null
                val d = runCatching { LocalDate.parse(m.groupValues[1], DAY) }
                    .getOrNull() ?: return@mapNotNull null
                if (d < from || d > today) null else d to f
            }
            .sortedBy { it.first }
            .map { it.second }
    }

    /** Line-level redaction (design D2). [secrets] are exact stored values;
     *  blanks and very short values are ignored so an empty setting can
     *  never blank out a log. */
    class Redactor(secrets: Collection<String>) {
        private val literal = secrets.map { it.trim() }.filter { it.length >= 8 }
            .distinct().sortedByDescending { it.length }

        fun apply(line: String): String {
            var s = line
            for (v in literal) s = s.replace(v, REDACTED)
            s = BOT_TOKEN.replace(s, REDACTED)
            s = BEARER.replace(s) { it.groupValues[1] + REDACTED }
            s = TOKEN_PARAM.replace(s) { it.groupValues[1] + REDACTED }
            s = COORD_PAIR.replace(s) {
                round2(it.groupValues[1]) + it.groupValues[2] + round2(it.groupValues[3])
            }
            s = LAT_LON.replace(s) { it.groupValues[1] + "=" + round2(it.groupValues[2]) }
            s = CHAT_ID.replace(s) {
                val id = it.groupValues[2]
                it.groupValues[1] + "***" + id.takeLast(3)
            }
            return s
        }

        private fun round2(v: String): String =
            v.toDoubleOrNull()?.let { String.format(Locale.US, "%.2f", it) } ?: v

        companion object {
            const val REDACTED = "[redacted]"
            private val BOT_TOKEN = Regex("""\b\d{6,12}:[A-Za-z0-9_-]{30,}""")
            private val BEARER = Regex("""(?i)(bearer\s+)[A-Za-z0-9._~+/=-]+""")
            private val TOKEN_PARAM = Regex("""(?i)([?&]token=)[^&\s]+""")
            private val COORD_PAIR =
                Regex("""(-?\d{1,3}\.\d{3,})(\s*,\s*)(-?\d{1,3}\.\d{3,})""")
            private val LAT_LON = Regex("""(?i)\b(lat|lon|lng)=(-?\d{1,3}\.\d{3,})""")
            private val CHAT_ID = Regex("""((?:Telegram|fallback test) to )(-?\d{4,})""")
        }
    }

    /**
     * Write the bundle to [out]. [header] is free text for the manifest
     * (app/device/Android versions, destination); [soak] is the soak report.
     */
    fun build(out: File, logDir: File?, days: Int, today: LocalDate,
              secrets: Collection<String>, header: String, soak: String): Result {
        val d = normalizeDays(days)
        val files = selectFiles(logDir, d, today)
        val redactor = Redactor(secrets)
        var redacted = 0
        var raw = 0L
        out.parentFile?.mkdirs()
        ZipOutputStream(out.outputStream().buffered()).use { zip ->
            for (f in files) {
                raw += f.length()
                zip.putNextEntry(ZipEntry("logs/${f.name}"))
                val w = zip.bufferedWriter()
                f.bufferedReader().useLines { lines ->
                    for (line in lines) {
                        val r = redactor.apply(line)
                        if (r != line) redacted++
                        w.write(r)
                        w.write("\n")
                    }
                }
                w.flush()
                zip.closeEntry()
            }
            zip.putNextEntry(ZipEntry("soak.txt"))
            zip.write(redactor.apply(soak).toByteArray())
            zip.closeEntry()
            val from = today.minusDays((d - 1).toLong())
            zip.putNextEntry(ZipEntry("manifest.txt"))
            zip.write(buildString {
                append("# Standby diagnostics bundle\n")
                append(redactor.apply(header).trimEnd()).append("\n")
                append("range: $d day(s), $from .. $today (phone local date)\n")
                append("files (${files.size}, ${raw} bytes before redaction and compression):\n")
                files.forEach { append("  logs/${it.name}  ${it.length()} B\n") }
                append("redacted on the phone before this bundle was written:\n")
                RULES.forEach { append("  - $it\n") }
                append("lines changed by redaction: $redacted\n")
            }.toByteArray())
            zip.closeEntry()
        }
        return Result(out, d, today.minusDays((d - 1).toLong()), today,
                      files.map { it.name }, raw, redacted)
    }
}
