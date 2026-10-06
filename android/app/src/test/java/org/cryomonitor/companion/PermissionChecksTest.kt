package org.cryomonitor.companion

import org.cryomonitor.companion.PermissionChecks.Boot
import org.cryomonitor.companion.PermissionChecks.Result
import org.junit.Assert.assertEquals
import org.junit.Test

/** The "Let it run" self-test verdicts (pure, no Android). */
class PermissionChecksTest {

    private val t0 = 1_700_000_000_000L

    @Test
    fun `screen test verdicts`() {
        assertEquals(Result.NotTested, PermissionChecks.verdict(0, 0, false, false, t0))
        // launched 3 s ago, nothing yet: still testing
        assertEquals(Result.Testing, PermissionChecks.verdict(t0, 0, false, false, t0 + 3_000))
        // launched 25 s ago, nothing: failed
        assertEquals(Result.Failed(t0), PermissionChecks.verdict(t0, 0, false, false, t0 + 25_000))
        // appeared after the attempt: passed
        assertEquals(Result.Passed(t0 + 2_000),
            PermissionChecks.verdict(t0, t0 + 2_000, false, false, t0 + 60_000))
        // an appearance from an OLDER attempt does not count for this one
        assertEquals(Result.Failed(t0 + 100_000),
            PermissionChecks.verdict(t0 + 100_000, t0 + 2_000, true, false, t0 + 130_000))
    }

    @Test
    fun `lock test needs the phone locked`() {
        assertEquals(Result.Inconclusive(t0 + 2_000),
            PermissionChecks.verdict(t0, t0 + 2_000, false, true, t0 + 5_000))
        assertEquals(Result.Passed(t0 + 2_000),
            PermissionChecks.verdict(t0, t0 + 2_000, true, true, t0 + 5_000))
    }

    @Test
    fun `boot verdicts`() {
        val installed = t0
        val now = t0 + 10 * 86_400_000L
        // phone up for 30 days: no restart since install
        assertEquals(Boot.NotYet, PermissionChecks.bootVerdict(
            now, 30 * 86_400_000L, installed, 0, 0, 0, 0))
        // armed, no restart yet
        assertEquals(Boot.Waiting, PermissionChecks.bootVerdict(
            now, 30 * 86_400_000L, installed, now - 60_000, 0, 0, 0))
        // restarted 2 h ago, service classified a boot start 40 s after boot
        val bootAt = now - 7_200_000
        assertEquals(Boot.Started(40, bootAt), PermissionChecks.bootVerdict(
            now, 7_200_000, installed, 0, bootAt + 20_000, bootAt + 40_000, 40))
        // restarted 2 h ago, receiver fired but the start was not classified (older build)
        assertEquals(Boot.Started(-1, bootAt), PermissionChecks.bootVerdict(
            now, 7_200_000, installed, 0, bootAt + 20_000, 0, 0))
        // restarted 2 h ago, nothing fired since: autostart blocked
        assertEquals(Boot.DidNotStart(bootAt), PermissionChecks.bootVerdict(
            now, 7_200_000, installed, 0, bootAt - 86_400_000, bootAt - 86_400_000, 30))
        // armed after a successful boot: back to waiting until the next restart
        assertEquals(Boot.Waiting, PermissionChecks.bootVerdict(
            now, 7_200_000, installed, now - 60_000, bootAt + 20_000, bootAt + 40_000, 40))
    }
}
