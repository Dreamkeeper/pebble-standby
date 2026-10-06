package org.cryomonitor.companion

/**
 * Every wearer-facing word that is produced in Kotlin rather than in
 * strings.xml (spec companion-ui: "One vocabulary and palette"; design
 * D4). The jargon-audit test scans this file and strings.xml together.
 * Detector ids never leave this object.
 */
object WearerWords {
    // States (DESIGN §3.1)
    const val COVERED = "Covered"
    const val PAUSED = "Paused"
    const val NEEDS_ATTENTION = "Needs attention"
    const val ALARM = "Alarm"
    const val ALARM_SOON = "Are you OK?"
    const val NOT_SET_UP = "Not set up"

    // Reasons
    const val REASON_NOT_SET_UP = "Finish setting up so your people can be alerted."
    const val REASON_NO_CONTACTS = "Alerts reach nobody: no contacts yet."
    const val REASON_WATCH_LINK = "The watch is not connected to this phone."
    const val REASON_WATCH_SILENT = "The watch has not reported for a while."
    const val REASON_NOT_WORN = "The watch thinks it is not being worn."
    const val REASON_SENSOR = "No pulse signal while the watch moves — sensor or strap."
    const val REASON_CHARGING = "On the charger. Monitoring resumes when you put it on."
    const val REASON_CARRY = "Carrying the watch; timer only."

    // Actions
    const val ACTION_IM_OK = "I'm OK"
    const val ACTION_SET_UP = "Set up"
    const val ACTION_ADD_CONTACT = "Add a contact"
    const val ACTION_OPEN_BLUETOOTH = "Open Bluetooth"
    const val ACTION_CHECK_WATCH = "Check the watch"
    const val ACTION_RE_WEAR = "Put the watch on"
    const val ACTION_CHECK_SERVER = "Check your server"
    const val ACTION_RESUME = "Resume monitoring"

    /** Protocol detector id -> what the wearer reads (DESIGN §3.2). */
    fun detector(id: String): String = when (id) {
        "pulse" -> "No pulse signal"
        "impact" -> "Hard impact"
        "nonmotion" -> "No movement"
        "checkin" -> "Missed check-in"
        "notworn" -> "Watch not worn?"
        "sos" -> "SOS"
        else -> "Alert"
    }

    fun alarmReason(detectorId: String): String = when (detectorId) {
        "pulse" -> "No pulse signal while still."
        "impact" -> "Hard impact, then no movement."
        "nonmotion" -> "No movement for a long time."
        "checkin" -> "A scheduled check-in was missed."
        "sos" -> "SOS pressed on the watch."
        else -> "The watch raised an alert."
    }

    fun serverReason(lastResult: String): String = when {
        lastResult.contains("401") -> "Your server no longer accepts this phone. Re-enrol."
        lastResult.startsWith("unreachable") -> "Your server cannot be reached right now."
        else -> "Your server reported a problem."
    }

    fun pausedReason(leftMs: Long): String {
        val min = (leftMs / 60_000) + 1
        return if (min >= 60) "Paused for ${min / 60} h ${min % 60} min more."
        else "Paused for $min min more."
    }

    fun coveredSince(sinceT: Long, nowMs: Long): String {
        if (sinceT <= 0) return "Watch, phone and your people are in place."
        val age = nowMs - sinceT
        return when {
            age < 90_000 -> "Since just now."
            age < 3_600_000 -> "Since ${age / 60_000} min."
            age < 172_800_000 -> "Since ${age / 3_600_000} h."
            else -> "Since ${age / 86_400_000} days."
        }
    }

    /** Human age for cards: "4 min ago", "2 h ago". */
    fun ago(t: Long, nowMs: Long): String {
        if (t <= 0) return "never"
        val d = nowMs - t
        return when {
            d < 90_000 -> "just now"
            d < 3_600_000 -> "${d / 60_000} min ago"
            d < 172_800_000 -> "${d / 3_600_000} h ago"
            else -> "${d / 86_400_000} days ago"
        }
    }
}
