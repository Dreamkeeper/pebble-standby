package org.cryomonitor.companion

import android.content.Context

/** SharedPreferences-backed settings. TODO(M3): real settings UI + validation. */
class SettingsStore(context: Context) {
    private val p = context.getSharedPreferences("cm_settings", Context.MODE_PRIVATE)

    var serverUrl: String
        get() = p.getString("server_url", "") ?: ""
        set(v) = p.edit().putString("server_url", v.trimEnd('/')).apply()

    var apiToken: String
        get() = p.getString("api_token", "") ?: ""
        set(v) = p.edit().putString("api_token", v).apply()

    /** Comma-separated phone numbers for SMS fallback escalation. */
    var smsContacts: List<String>
        get() = (p.getString("sms_contacts", "") ?: "")
            .split(',').map { it.trim() }.filter { it.isNotEmpty() }
        set(v) = p.edit().putString("sms_contacts", v.joinToString(",")).apply()

    var telegramBotToken: String
        get() = p.getString("tg_token", "") ?: ""
        set(v) = p.edit().putString("tg_token", v).apply()

    /** Comma-separated Telegram chat ids for direct (server-less) alerts. */
    var telegramChatIds: List<String>
        get() = (p.getString("tg_chats", "") ?: "")
            .split(',').map { it.trim() }.filter { it.isNotEmpty() }
        set(v) = p.edit().putString("tg_chats", v.joinToString(",")).apply()

    /** Local emergency number offered to the WEARER for one-tap dialing. */
    var emergencyNumber: String
        get() = p.getString("emergency_number", "112") ?: "112"
        set(v) = p.edit().putString("emergency_number", v).apply()

    var wearerName: String
        get() = p.getString("wearer_name", "the wearer") ?: "the wearer"
        set(v) = p.edit().putString("wearer_name", v).apply()

    /** Extensive debug logging (phone ring/file log + watch APP_LOG). */
    var debugLogging: Boolean
        get() = p.getBoolean("debug_logging", false)
        set(v) = p.edit().putBoolean("debug_logging", v).apply()

    /**
     * Sensor lab reads the raw HR quality metric — ONLY valid on the
     * hr-quality-diag fork firmware (stock firmware asserts on the
     * unknown metric and the watchapp would crash mid-lab). Off by
     * default; the owner's Time 2 runs the diag build.
     */
    var labQualityMetric: Boolean
        get() = p.getBoolean("lab_quality_metric", false)
        set(v) = p.edit().putBoolean("lab_quality_metric", v).apply()

    /** Ring of the last 8 escalated ladder episode ids: the same episode
     *  never escalates twice regardless of channel (delivery hardening). */
    var escalatedEpisodes: List<Int>
        get() = (p.getString("escalated_eps", "") ?: "")
            .split(',').mapNotNull { it.toIntOrNull() }
        set(v) = p.edit().putString("escalated_eps",
            v.takeLast(8).joinToString(",")).apply()

    /**
     * S5 fallback (owner decision 2026-08-27): the companion may
     * periodically launch the watchapp for a brief sync — screen flashes
     * for a few seconds, watch data age + battery refresh. Minutes; 0 = off.
     */
    var watchSyncIntervalMin: Int
        get() = p.getInt("watch_sync_interval_min", 60)
        set(v) = p.edit().putInt("watch_sync_interval_min", v.coerceIn(0, 1440)).apply()

    /** "auto" | "patched" | "store" — which Pebble app is on the phone
     *  (see PebbleAppPolicy). Auto resolves from dlEverSeen. */
    var pebbleAppMode: String
        get() = p.getString("pebble_app_mode", "auto") ?: "auto"
        set(v) = p.edit().putString("pebble_app_mode", v).apply()

    /** Set once the first worker DataLogging record arrives: proof the
     *  phone's Pebble app forwards worker telemetry. */
    var dlEverSeen: Boolean
        get() = p.getBoolean("dl_ever_seen", false)
        set(v) = p.edit().putBoolean("dl_ever_seen", v).apply()
}
