package org.cryomonitor.companion

import android.app.AppOpsManager
import android.content.Context
import android.os.Build
import android.os.Process

/**
 * Evidence for the "Let it run" rows that Android has no API for: the two
 * HyperOS permissions (pop-up windows in the background, show on the lock
 * screen) and starting after a reboot. Instead of "check manually", the
 * app performs the real action — MonitorService launches the test screen
 * from the background the way an alarm would — and records whether the
 * screen actually appeared. The verdicts are pure functions so they are
 * unit-tested; the storage is one small SharedPreferences file.
 */
class PermissionChecks(context: Context) {
    private val p = context.getSharedPreferences("perm_checks", Context.MODE_PRIVATE)

    enum class Kind(val key: String) { POPUP("popup"), LOCK("lock") }

    sealed class Result {
        object NotTested : Result()
        object Testing : Result()
        data class Passed(val at: Long) : Result()
        data class Failed(val at: Long) : Result()
        /** Lock test: the screen came up, but the phone was not locked. */
        data class Inconclusive(val at: Long) : Result()
    }

    /** The service is about to launch the test screen for [k]. */
    fun noteAttempt(k: Kind, now: Long = System.currentTimeMillis()) =
        p.edit().putLong("${k.key}_attempt_at", now).apply()

    /** The test screen appeared (its window got focus). */
    fun noteAppeared(k: Kind, locked: Boolean, now: Long = System.currentTimeMillis()) =
        p.edit().putLong("${k.key}_appeared_at", now)
            .putBoolean("${k.key}_locked", locked).apply()

    fun result(k: Kind, now: Long = System.currentTimeMillis()): Result = verdict(
        attemptAt = p.getLong("${k.key}_attempt_at", 0),
        appearedAt = p.getLong("${k.key}_appeared_at", 0),
        appearedLocked = p.getBoolean("${k.key}_locked", false),
        needsLock = k == Kind.LOCK,
        now = now)

    sealed class Boot {
        /** The boot receiver ran after the last restart; [delayS] is -1 when
         *  the service start was not classified (older app version). */
        data class Started(val delayS: Long, val bootAt: Long) : Boot()
        data class DidNotStart(val bootAt: Long) : Boot()
        /** No restart since install: nothing to judge yet. */
        object NotYet : Boot()
        /** The wearer pressed Check; waiting for them to restart the phone. */
        object Waiting : Boot()
    }

    companion object {
        /** The screen must appear within this after the launch attempt. */
        const val TEST_TIMEOUT_MS = 20_000L
        /** Android lets an app start activities for ~10 s after it leaves
         *  the foreground; the test waits longer so the launch is judged on
         *  the permission alone, as a real alarm would be. */
        const val TEST_DELAY_MS = 15_000L
        private const val CLOCK_SLACK_MS = 5_000L

        /** HyperOS / MIUI AppOps ids, read by reflection for the instant
         *  (pre-test) reading; null when the device does not expose them. */
        const val MIUI_OP_BACKGROUND_START = 10021
        const val MIUI_OP_SHOW_WHEN_LOCKED = 10020

        fun verdict(attemptAt: Long, appearedAt: Long, appearedLocked: Boolean,
                    needsLock: Boolean, now: Long): Result {
            if (attemptAt == 0L) return Result.NotTested
            if (appearedAt >= attemptAt)
                return if (needsLock && !appearedLocked) Result.Inconclusive(appearedAt)
                       else Result.Passed(appearedAt)
            return if (now - attemptAt < TEST_TIMEOUT_MS) Result.Testing
                   else Result.Failed(attemptAt)
        }

        /**
         * The wall clock is continuous across a reboot while elapsedRealtime
         * restarts at zero, so `now - elapsed` is the moment of the last
         * boot. If that boot is after install (or after the wearer armed a
         * check), the boot receiver either ran after it or it did not.
         */
        fun bootVerdict(nowMs: Long, elapsedRealtimeMs: Long, installedAtMs: Long,
                        armedAtMs: Long, bootReceiverAtMs: Long,
                        bootRecoveryAtMs: Long, bootDelayS: Long): Boot {
            val bootAt = nowMs - elapsedRealtimeMs
            val since = maxOf(installedAtMs, armedAtMs)
            if (bootAt < since + CLOCK_SLACK_MS)
                return if (armedAtMs > 0) Boot.Waiting else Boot.NotYet
            val recovered = bootRecoveryAtMs >= bootAt - CLOCK_SLACK_MS
            if (recovered) return Boot.Started(bootDelayS, bootAt)
            if (bootReceiverAtMs >= bootAt - CLOCK_SLACK_MS) return Boot.Started(-1, bootAt)
            return Boot.DidNotStart(bootAt)
        }

        fun isHyperOs(): Boolean =
            Build.MANUFACTURER.lowercase() in setOf("xiaomi", "redmi", "poco")

        /** true/false when the vendor op can be read, null otherwise. */
        fun miuiOpAllowed(c: Context, op: Int): Boolean? = try {
            val aom = c.getSystemService(AppOpsManager::class.java)
            val m = AppOpsManager::class.java.getMethod("checkOpNoThrow",
                Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, String::class.java)
            (m.invoke(aom, op, Process.myUid(), c.packageName) as Int) == AppOpsManager.MODE_ALLOWED
        } catch (_: Throwable) { null }
    }
}
