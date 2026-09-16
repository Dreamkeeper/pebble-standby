package org.cryomonitor.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PebbleAppPolicyTest {

    @Test
    fun `auto is store mode until a worker record has been seen`() {
        assertTrue(PebbleAppPolicy.storeMode(PebbleAppPolicy.Mode.AUTO, dlEverSeen = false))
        assertFalse(PebbleAppPolicy.storeMode(PebbleAppPolicy.Mode.AUTO, dlEverSeen = true))
        assertTrue(PebbleAppPolicy.storeMode(PebbleAppPolicy.Mode.STORE, dlEverSeen = true))
        assertFalse(PebbleAppPolicy.storeMode(PebbleAppPolicy.Mode.PATCHED, dlEverSeen = false))
    }

    @Test
    fun `parse is lenient`() {
        assertEquals(PebbleAppPolicy.Mode.STORE, PebbleAppPolicy.parse(" Store "))
        assertEquals(PebbleAppPolicy.Mode.PATCHED, PebbleAppPolicy.parse("patched"))
        assertEquals(PebbleAppPolicy.Mode.AUTO, PebbleAppPolicy.parse(null))
        assertEquals(PebbleAppPolicy.Mode.AUTO, PebbleAppPolicy.parse("bogus"))
    }

    @Test
    fun `store mode never runs without a sync clock`() {
        assertEquals(60, PebbleAppPolicy.effectiveSyncMin(storeMode = true, settingMin = 0))
        assertEquals(30, PebbleAppPolicy.effectiveSyncMin(storeMode = true, settingMin = 30))
        assertEquals(0, PebbleAppPolicy.effectiveSyncMin(storeMode = false, settingMin = 0))
    }

    @Test
    fun `first sync on a fresh install counts from service start`() {
        val start = 1_000_000L
        assertEquals(start + 60 * 60_000L, PebbleAppPolicy.syncDueAt(0, start, 60))
        assertEquals(5_000_000L + 60 * 60_000L, PebbleAppPolicy.syncDueAt(5_000_000L, start, 60))
    }

    @Test
    fun `provisioning fault fires once and any proof ends it`() {
        val start = 1_000_000L
        val grace = 900L
        // before grace: no fault
        assertFalse(PebbleAppPolicy.provisioningFault(true, 0, start, start + 800_000, grace, false))
        // past grace, no proof: fault
        assertTrue(PebbleAppPolicy.provisioningFault(true, 0, start, start + 1_000_000, grace, false))
        // already raised: not again
        assertFalse(PebbleAppPolicy.provisioningFault(true, 0, start, start + 2_000_000, grace, true))
        // an open-app message counted as proof: never again, even after a
        // self-heal launch cleared the flag (the old rule re-fired here)
        assertFalse(PebbleAppPolicy.provisioningFault(true, start + 1_100_000, start, start + 3_000_000, grace, false))
        // link down: nothing to say
        assertFalse(PebbleAppPolicy.provisioningFault(false, 0, start, start + 2_000_000, grace, false))
    }

    @Test
    fun `two silent sync launches are a fault`() {
        assertFalse(PebbleAppPolicy.syncMissFault(0))
        assertFalse(PebbleAppPolicy.syncMissFault(1))
        assertTrue(PebbleAppPolicy.syncMissFault(2))
    }

    @Test
    fun `describe names the mode and the sync cadence`() {
        val s = PebbleAppPolicy.describe(PebbleAppPolicy.Mode.AUTO, dlEverSeen = false, syncMin = 60)
        assertTrue(s.startsWith("Pebble app mode: STORE"))
        assertTrue(s.contains("every 60 min"))
        assertTrue(PebbleAppPolicy.describe(PebbleAppPolicy.Mode.AUTO, dlEverSeen = true, syncMin = 60)
            .startsWith("Pebble app mode: PATCHED"))
    }
}
