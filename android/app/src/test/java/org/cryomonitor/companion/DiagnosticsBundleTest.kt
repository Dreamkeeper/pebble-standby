package org.cryomonitor.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.LocalDate
import java.util.zip.ZipFile

class DiagnosticsBundleTest {

    @get:Rule val tmp = TemporaryFolder()

    private val today = LocalDate.of(2026, 9, 27)

    private fun logDir(days: Int): File {
        val dir = tmp.newFolder("logs")
        for (i in 0 until days) {
            val d = today.minusDays(i.toLong())
            File(dir, "cm-%04d%02d%02d.log".format(d.year, d.monthValue, d.dayOfMonth))
                .writeText("line for $d\n")
        }
        File(dir, "s4-hr-lab-20260827-151518.csv").writeText("not a log")
        File(dir, "cm-2026x927.log").writeText("bad name")
        return dir
    }

    @Test
    fun `range selects exactly the most recent days by file name, today inclusive`() {
        val dir = logDir(40)
        val picked = DiagnosticsBundle.selectFiles(dir, 14, today).map { it.name }
        assertEquals(14, picked.size)
        assertEquals("cm-20260914.log", picked.first())
        assertEquals("cm-20260927.log", picked.last())
        assertEquals(1, DiagnosticsBundle.selectFiles(dir, 1, today).size)
    }

    @Test
    fun `day counts from a request are clamped to the offered ranges`() {
        assertEquals(1, DiagnosticsBundle.normalizeDays(1))
        assertEquals(3, DiagnosticsBundle.normalizeDays(2))
        assertEquals(7, DiagnosticsBundle.normalizeDays(5))
        assertEquals(14, DiagnosticsBundle.normalizeDays(90))
    }

    @Test
    fun `stored secrets and token shapes are redacted`() {
        val r = DiagnosticsBundle.Redactor(listOf("s3cret-api-token-value", "", "short"))
        assertEquals("auth [redacted] ok", r.apply("auth s3cret-api-token-value ok"))
        assertEquals("bot [redacted] x",
            r.apply("bot 123456789:AAHdqTcvCH1vGWJxfSeofSAs0K5PALDsaw x"))
        assertEquals("Authorization: Bearer [redacted]",
            r.apply("Authorization: Bearer abc.def-123"))
        assertEquals("GET /api/v1/status?token=[redacted]&x=1",
            r.apply("GET /api/v1/status?token=abcdef&x=1"))
        // an empty or short secret never blanks a line
        assertEquals("short text stays", r.apply("short text stays"))
    }

    @Test
    fun `coordinates are rounded and chat ids masked`() {
        val r = DiagnosticsBundle.Redactor(emptyList())
        assertEquals("escalate det=pulse loc=(55.62, 37.74) serverEsc=null",
            r.apply("escalate det=pulse loc=(55.621867, 37.740187) serverEsc=null"))
        assertEquals("https://maps.google.com/?q=55.62,37.74",
            r.apply("https://maps.google.com/?q=55.6218,37.7401"))
        assertEquals("lat=-33.87 lon=151.21", r.apply("lat=-33.8688 lon=151.2093"))
        assertEquals("Telegram to ***789 -> 200", r.apply("Telegram to 123456789 -> 200"))
        assertEquals("fallback test to ***890 -> 403 x",
            r.apply("fallback test to -1001234567890 -> 403 x"))
        // timestamps and ordinary numbers are untouched
        val plain = "09-19 19:04:47.386 I/DataLog: bpm=0 flush-latency=158s heap=1600B"
        assertEquals(plain, r.apply(plain))
    }

    @Test
    fun `bundle holds the redacted logs, soak report and manifest`() {
        val dir = logDir(3)
        File(dir, "cm-20260927.log").appendText("Telegram to 123456789 -> 200\n")
        val out = File(tmp.root, "out/b.zip")
        val res = DiagnosticsBundle.build(out, dir, 7, today, listOf("tok-123456789"),
            header = "app: test", soak = "# standby soak\nwindow: 1.0 days\n")
        assertEquals(7, res.days)
        assertEquals(3, res.logFiles.size)
        assertEquals(1, res.redactedLines)
        ZipFile(out).use { z ->
            val names = z.entries().toList().map { it.name }.toSet()
            assertTrue(names.containsAll(listOf("manifest.txt", "soak.txt",
                "logs/cm-20260925.log", "logs/cm-20260926.log", "logs/cm-20260927.log")))
            val today = z.getInputStream(z.getEntry("logs/cm-20260927.log")).reader().readText()
            assertTrue(today.contains("Telegram to ***789"))
            assertFalse(today.contains("123456789"))
            val manifest = z.getInputStream(z.getEntry("manifest.txt")).reader().readText()
            assertTrue(manifest.contains("range: 7 day(s), 2026-09-21 .. 2026-09-27"))
            assertTrue(manifest.contains("lines changed by redaction: 1"))
        }
    }

    @Test
    fun `server request commands parse, clamp and round-trip`() {
        val r = DiagnosticsBundle.Request.fromCommand("diag_request:ab12-CD_3:5", 1000L)!!
        assertEquals("ab12-CD_3", r.id)
        assertEquals(7, r.days)
        assertEquals(r, DiagnosticsBundle.Request.decode(r.encode()))
        assertNull(DiagnosticsBundle.Request.fromCommand("latency_drill", 0))
        assertNull(DiagnosticsBundle.Request.fromCommand("diag_request:../x:7", 0))
        assertNull(DiagnosticsBundle.Request.fromCommand("diag_request:ok:0", 0))
        assertNull(DiagnosticsBundle.Request.decode(""))
    }
}
