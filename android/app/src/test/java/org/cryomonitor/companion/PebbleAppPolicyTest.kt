package org.cryomonitor.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PebbleAppPolicyTest {

    @Test
    fun `sync-only until a worker record has been seen`() {
        assertTrue(PebbleAppPolicy.storeMode(dlEverSeen = false))
        assertFalse(PebbleAppPolicy.storeMode(dlEverSeen = true))
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
    fun `describe names the cadence`() {
        val s = PebbleAppPolicy.describe(dlEverSeen = false, syncMin = 60)
        assertTrue(s, s.contains("every 60 min"))
        assertTrue(PebbleAppPolicy.describe(dlEverSeen = true, syncMin = 60).contains("every minute"))
    }
}
