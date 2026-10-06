package org.cryomonitor.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The jargon audit (spec companion-ui; DESIGN §9): wearer-facing text in
 * strings.xml and WearerWords.kt contains none of the engineering words.
 * Diagnostics strings are in strings_diagnostics.xml and are not scanned.
 */
class WearerWordsAuditTest {

    private val jargon = listOf(
        "worker", "DataLogging", "data logging", "heap", "flush", "escalation",
        "degraded", "PRE_ALARM", "PRE-ALARM", "nonmotion", "notworn", "checkin",
        "S1 ", "S4 ", "S5 ", "S6 ", "S7 ", "PebbleKit", "DL ", "watchdog",
    )

    /** `"id" ->` keys of the detector mapping are the translation's input, not wearer text. */
    private val mappingKey = Regex("^\"[a-z]+\" ->")

    private fun sources(): Map<String, String> {
        // Gradle runs unit tests with the module directory as the working directory.
        val base = listOf(File("src/main"), File("app/src/main")).first { it.exists() }
        val words = File(base, "java/org/cryomonitor/companion/WearerWords.kt").readText()
            .lines().filterNot { line ->
                val t = line.trim()
                t.startsWith("//") || t.startsWith("*") || t.startsWith("/*") ||
                    mappingKey.containsMatchIn(t)
            }.joinToString("\n")
        return mapOf(
            "strings.xml" to File(base, "res/values/strings.xml").readText(),
            "WearerWords.kt" to words,
        )
    }

    @Test
    fun `no jargon in wearer-facing text`() {
        val hits = mutableListOf<String>()
        for ((name, text) in sources()) {
            val lower = text.lowercase()
            for (word in jargon) {
                if (lower.contains(word.lowercase())) hits += "$name: '$word'"
            }
        }
        assertEquals("jargon found in wearer-facing text: $hits", emptyList<String>(), hits)
    }

    @Test
    fun `detector ids map to wearer words and never leak`() {
        for (id in Protocol.DETECTOR_NAMES) {
            val w = WearerWords.detector(id)
            assertTrue("$id -> $w", w.isNotEmpty() && w != id && w[0].isUpperCase())
        }
        assertEquals("Alert", WearerWords.detector("something-new"))
    }

    @Test
    fun `no medical claims`() {
        val text = sources().values.joinToString("\n").lowercase().replace("diagnostics", "")
        for (banned in listOf("cardiac", "heart attack", "medical", "arrest", "diagnos")) {
            assertTrue("'$banned' in wearer text", !text.contains(banned))
        }
    }
}
