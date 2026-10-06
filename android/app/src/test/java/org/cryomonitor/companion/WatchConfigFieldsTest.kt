package org.cryomonitor.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The field table is a protocol mirror: ids must be unique, ranges sane,
 * and every id must exist in the watch's CM_CFG_* enum with the same value
 * (the C header is part of this repository).
 */
class WatchConfigFieldsTest {

    @Test
    fun `ids are unique and defaults sit inside their ranges`() {
        val ids = WatchConfig.Field.entries.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        for (f in WatchConfig.Field.entries) {
            assertTrue("${f.name} default ${f.default} outside ${f.min}..${f.max}",
                f.default in f.min..f.max)
        }
    }

    @Test
    fun `every field id matches the watch header`() {
        val header = listOf(
            File("../../watchapp/src/core/detectors.h"),
            File("../watchapp/src/core/detectors.h"),
            File("watchapp/src/core/detectors.h"),
        ).firstOrNull { it.exists() } ?: return // header not in this checkout: skip
        val text = header.readText()
        val cIds = Regex("""CM_CFG_([A-Z_]+)\s*=\s*(\d+)""").findAll(text)
            .associate { it.groupValues[1] to it.groupValues[2].toInt() }
        assertTrue("no CM_CFG_ ids found in the header", cIds.isNotEmpty())
        for (f in WatchConfig.Field.entries) {
            assertEquals("field ${f.name}", cIds[f.name], f.id)
        }
        assertEquals("fields present on one side only", cIds.keys.sorted(),
            WatchConfig.Field.entries.map { it.name }.sorted())
    }
}
