package org.cryomonitor.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CoverageStateTest {

    private val now = 1_800_000_000_000L
    private val covered = CoverageFacts(
        onboardingDone = true, serverConfigured = true, noDeliverableContacts = false,
        watchConnected = true, lastWatchDataT = now - 60_000, lastWorkerProofT = now - 120_000,
        serverReachable = true, coveredSinceT = now - 3_600_000)

    private fun v(f: CoverageFacts) = CoverageState.compute(f, now)

    @Test
    fun `everything fine is Covered with no action`() {
        val r = v(covered)
        assertEquals(Coverage.COVERED, r.state)
        assertEquals("Covered", r.title)
        assertEquals("Since 1 h.", r.reason)
        assertNull(r.action)
    }

    @Test
    fun `alarm beats everything, countdown is a check-in`() {
        val alarm = v(covered.copy(alertStage = 2, alertDetector = "impact", watchConnected = false,
                                   noDeliverableContacts = true))
        assertEquals(Coverage.ALARM, alarm.state)
        assertEquals("Hard impact, then no movement.", alarm.reason)
        assertEquals(CoverageAction.CANCEL_ALARM, alarm.action)
        val pre = v(covered.copy(alertStage = 1, alertDetector = "pulse"))
        assertEquals(Coverage.CHECK_IN, pre.state)
        assertEquals("Are you OK?", pre.title)
        assertEquals("No pulse signal while still.", pre.reason)
    }

    @Test
    fun `not set up until onboarding or a destination exists`() {
        assertEquals(Coverage.NOT_SET_UP, v(CoverageFacts()).state)
        assertEquals(Coverage.NOT_SET_UP,
            v(covered.copy(serverConfigured = false, fallbackConfigured = false)).state)
        // fallback-only install counts as set up
        val fb = v(covered.copy(serverConfigured = false, fallbackConfigured = true,
                                noDeliverableContacts = null))
        assertEquals(Coverage.COVERED, fb.state)
    }

    @Test
    fun `needs attention, worst first`() {
        val none = v(covered.copy(noDeliverableContacts = true, watchConnected = false))
        assertEquals(Coverage.NEEDS_ATTENTION, none.state)
        assertEquals("Alerts reach nobody: no contacts yet.", none.reason)
        assertEquals(CoverageAction.ADD_CONTACT, none.action)

        val link = v(covered.copy(watchConnected = false, serverReachable = false))
        assertEquals("The watch is not connected to this phone.", link.reason)
        assertEquals(CoverageAction.OPEN_BLUETOOTH, link.action)

        val nag = v(covered.copy(lastNagT = now - 60_000, lastNagKind = "notworn"))
        assertEquals("The watch thinks it is not being worn.", nag.reason)
        val oldNag = v(covered.copy(lastNagT = now - CoverageState.NAG_FRESH_MS - 1, lastNagKind = "notworn"))
        assertEquals(Coverage.COVERED, oldNag.state)

        val silent = v(covered.copy(lastWorkerProofT = now - CoverageState.WORKER_STALE_MS - 1))
        assertEquals("The watch has not reported for a while.", silent.reason)
        // store mode: no worker records expected
        assertEquals(Coverage.COVERED,
            v(covered.copy(storeMode = true, lastWorkerProofT = now - 10 * 3_600_000)).state)

        val srv = v(covered.copy(serverReachable = false, serverLastResult = "token rejected (401)"))
        assertEquals("Your server no longer accepts this phone. Re-enrol.", srv.reason)
        assertEquals(CoverageAction.OPEN_SERVER, srv.action)
    }

    @Test
    fun `paused states are calm and never say Suspended`() {
        val chg = v(covered.copy(chargingHold = true))
        assertEquals(Coverage.PAUSED, chg.state)
        assertNull(chg.action)
        val susp = v(covered.copy(suspendedUntilT = now + 25 * 60_000))
        assertEquals(Coverage.PAUSED, susp.state)
        assertEquals("Paused for 26 min more.", susp.reason)
        assertEquals(CoverageAction.RESUME, susp.action)
        assertTrue(!susp.title.contains("Suspend") && !susp.reason.contains("Suspend"))
        // attention outranks paused
        assertEquals(Coverage.NEEDS_ATTENTION,
            v(covered.copy(chargingHold = true, watchConnected = false)).state)
    }

    @Test
    fun `notification line is the verdict in one line`() {
        val line = CoverageState.notificationLine(v(covered), covered, 82, now)
        assertEquals("Covered · watch 82% · your server reachable", line)
        val f = covered.copy(watchConnected = false)
        assertEquals("Needs attention · The watch is not connected to this phone.",
            CoverageState.notificationLine(v(f), f, 82, now))
    }
}
