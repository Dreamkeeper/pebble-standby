package org.cryomonitor.companion

import android.content.Context

/**
 * Detector settings the phone owns and the watch applies live (change
 * watch-settings-sync, design D1/D5). Field ids and ranges mirror
 * CM_CFG_* in watchapp/src/core/detectors.h — the two MUST change together.
 *
 * Bookkeeping per field: the wearer's value, and the value the watch last
 * acknowledged. A field is pending while they differ; nothing is pending
 * on a fresh install until the first push, which sends every field.
 */
class WatchConfig(context: Context) {
    private val p = context.getSharedPreferences("watch_config", Context.MODE_PRIVATE)

    /** Protocol field ids. Keep in sync with CM_CFG_* on the watch. */
    enum class Field(val id: Int, val default: Int, val min: Int, val max: Int) {
        PULSE_ENABLED(1, 1, 0, 1),
        IMPACT_ENABLED(2, 1, 0, 1),
        NONMOTION_ENABLED(3, 1, 0, 1),
        CHECKIN_ENABLED(4, 0, 0, 1),
        NOTWORN_ENABLED(5, 1, 0, 1),
        SENSOR_ENABLED(6, 1, 0, 1),
        PULSE_LOST_AFTER_S(10, 150, 60, 600),
        PULSE_FLAT_AFTER_S(11, 300, 120, 900),
        PULSE_SNOOZE_MIN(12, 10, 1, 120),
        IMPACT_IMMOBILE_S(20, 60, 30, 300),
        NONMOTION_DAY_MIN(30, 40, 10, 240),
        NONMOTION_NIGHT_MIN(31, 90, 10, 480),
        NIGHT_START_HOUR(32, 23, 0, 23),
        NIGHT_END_HOUR(33, 7, 0, 23),
        NOTWORN_AFTER_MIN(40, 3, 1, 60),
        SENSOR_FAULT_AFTER_MIN(41, 10, 1, 60),
        CHECKIN_INTERVAL_MIN(50, 240, 30, 1440),
        CHECKIN_GRACE_MIN(51, 15, 1, 60),
        CHECKIN_REMIND_MIN(52, 5, 0, 30),
        CHECKIN_UI_S(60, 30, 10, 120),
        COUNTDOWN_S(61, 30, 10, 120),
        COUNTDOWN_IMPACT_S(62, 20, 10, 60);

        companion object {
            fun byId(id: Int): Field? = entries.firstOrNull { it.id == id }
        }
    }

    /** The wearer's value (default until changed). */
    fun get(f: Field): Int = p.getInt("v_${f.id}", f.default)

    /** The value the watch last acknowledged, or null if never. */
    fun acked(f: Field): Int? = if (p.contains("a_${f.id}")) p.getInt("a_${f.id}", 0) else null

    fun isPending(f: Field): Boolean = acked(f) != get(f)

    /** Set a value; out-of-range values are rejected (returns false). */
    fun set(f: Field, value: Int): Boolean {
        if (value < f.min || value > f.max) return false
        p.edit().putInt("v_${f.id}", value).apply()
        return true
    }

    /**
     * Record the watch's answer. The watch echoes the value in force: when
     * it refused ours (ok=false) we adopt the watch's value so the screen
     * never shows a lie (design D2).
     */
    fun onAck(fieldId: Int, valueInForce: Int, ok: Boolean) {
        val f = Field.byId(fieldId) ?: return
        val e = p.edit().putInt("a_${f.id}", valueInForce)
        if (!ok) e.putInt("v_${f.id}", valueInForce)
        e.apply()
    }

    /** Fields to send when the watchapp opens: everything until first ack, then the pending ones. */
    fun toSend(): List<Pair<Field, Int>> {
        val neverSynced = Field.entries.none { acked(it) != null }
        return Field.entries
            .filter { neverSynced || isPending(it) }
            .map { it to get(it) }
    }

    fun pendingCount(): Int = Field.entries.count { isPending(it) }

    fun detectorEnabled(f: Field): Boolean = get(f) == 1

    /** For Diagnostics / tests: wipe the ack record so everything is re-sent. */
    fun forgetAcks() {
        val e = p.edit()
        Field.entries.forEach { e.remove("a_${it.id}") }
        e.apply()
    }
}
