package org.cryomonitor.companion

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * HTTPS client for the self-hosted server (server/ in this repo).
 * All calls are blocking — invoke from a coroutine on Dispatchers.IO or a
 * worker thread. Tokens travel in the Authorization header, never in URLs
 * (reverse proxies log full request lines).
 *
 * Constructed from providers rather than Android types so JVM unit tests
 * can drive it against MockWebServer without a Context.
 */
class ServerClient(
    private val urlProvider: () -> String,
    private val tokenProvider: () -> String,
) {
    constructor(settings: SettingsStore) :
        this({ settings.serverUrl }, { settings.apiToken })

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .build()

    /** A week of logs is a few hundred KB compressed, but a slow uplink
     *  must not hit the 20 s budget meant for heartbeats. */
    private val uploadHttp = http.newBuilder()
        .callTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    val configured: Boolean get() = urlProvider().isNotEmpty()

    /** Human-readable outcome of the last call — "cannot reach" and
     *  "token rejected" are different problems with different fixes. */
    @Volatile
    var lastResult: String = "not tried yet"
        private set

    // ---- enrollment (pre-configuration: explicit URL, no stored token) ----

    sealed class EnrollResult {
        data class Success(val token: String, val wearerId: String) : EnrollResult()
        object CodeRejected : EnrollResult()   // invalid, expired, or used (410)
        object Malformed : EnrollResult()      // not a valid code shape (422)
        object RateLimited : EnrollResult()    // 429
        data class Unreachable(val why: String) : EnrollResult()
        data class ServerError(val code: Int) : EnrollResult()
    }

    fun enroll(serverUrl: String, code: String): EnrollResult {
        val body = JSONObject().put("code", code)
        val req = Request.Builder()
            .url(serverUrl.trimEnd('/') + "/api/v1/enroll")
            .post(body.toString().toRequestBody(JSON))
            .build()
        return try {
            http.newCall(req).execute().use { r ->
                CmLog.i(TAG, "enroll -> ${r.code}")
                when {
                    r.isSuccessful -> {
                        val j = JSONObject(r.body?.string() ?: "{}")
                        EnrollResult.Success(j.getString("token"),
                                             j.optString("wearer_id"))
                    }
                    r.code == 410 -> EnrollResult.CodeRejected
                    r.code == 422 -> EnrollResult.Malformed
                    r.code == 429 -> EnrollResult.RateLimited
                    else -> EnrollResult.ServerError(r.code)
                }
            }
        } catch (e: IOException) {
            CmLog.i(TAG, "enroll failed: $e")
            EnrollResult.Unreachable(e.javaClass.simpleName)
        }
    }

    // ---- contacts, tiers, self-notify ----

    data class Contact(val id: String?, val name: String, val tierName: String,
                       val telegramChatId: String?, val ntfyTopic: String?,
                       val email: String?) {
        fun toJson(): JSONObject = JSONObject().apply {
            if (id != null) put("id", id)
            put("name", name)
            put("tier_name", tierName)
            put("telegram_chat_id", telegramChatId ?: JSONObject.NULL)
            put("ntfy_topic", ntfyTopic ?: JSONObject.NULL)
            put("email", email ?: JSONObject.NULL)
        }
    }

    data class TierInfo(val name: String, val position: Int,
                        val repeatAfterS: Int, val promoteAfterS: Int)

    data class ContactsPayload(val contacts: List<Contact>,
                               val tiers: List<TierInfo>)

    /** Field name -> message for a 422; empty map = other failure. */
    sealed class SaveResult {
        object Ok : SaveResult()
        data class FieldErrors(val fields: Map<String, String>) : SaveResult()
        data class Failed(val why: String) : SaveResult()
    }

    fun fetchContacts(): ContactsPayload? {
        val resp = call("GET", "/api/v1/contacts") ?: return null
        val contacts = resp.optJSONArray("contacts") ?: JSONArray()
        val tiers = resp.optJSONArray("tiers") ?: JSONArray()
        return ContactsPayload(
            contacts = (0 until contacts.length()).map {
                val c = contacts.getJSONObject(it)
                Contact(id = c.optString("id"),
                        name = c.optString("name"),
                        tierName = c.optString("tier_name"),
                        telegramChatId = c.optStringOrNull("telegram_chat_id"),
                        ntfyTopic = c.optStringOrNull("ntfy_topic"),
                        email = c.optStringOrNull("email"))
            },
            tiers = (0 until tiers.length()).map {
                val t = tiers.getJSONObject(it)
                TierInfo(t.optString("name"), t.optInt("position"),
                         t.optInt("repeat_after_s"), t.optInt("promote_after_s"))
            })
    }

    fun saveContact(contact: Contact): SaveResult =
        postForSave("/api/v1/contacts", contact.toJson())

    fun deleteContact(id: String): Boolean =
        request("DELETE", "/api/v1/contacts/$id", null).first in 200..299

    fun setSelfNotify(telegramChatId: String?, ntfyTopic: String?,
                      email: String?): SaveResult =
        postForSave("/api/v1/self-notify", JSONObject().apply {
            put("telegram_chat_id", telegramChatId ?: JSONObject.NULL)
            put("ntfy_topic", ntfyTopic ?: JSONObject.NULL)
            put("email", email ?: JSONObject.NULL)
        }, method = "PUT")

    data class WearerStatus(val phone: String, val degraded: Boolean,
                            val activeEscalations: Int,
                            val escalationIds: List<String>)

    fun fetchStatus(): WearerStatus? {
        val resp = call("GET", "/api/v1/status") ?: return null
        val esc = resp.optJSONObject("active_escalations")
        val ids = esc?.keys()?.asSequence()?.toList() ?: emptyList()
        return WearerStatus(
            phone = resp.optString("phone"),
            degraded = resp.optBoolean("degraded"),
            activeEscalations = ids.size,
            escalationIds = ids)
    }

    // ---- monitoring-path calls (unchanged behavior) ----

    fun heartbeat(phoneBatteryPct: Int, watchBatteryPct: Int?, watchDataAgeS: Int?,
                  lowBatteryWarning: Boolean, commandAck: String? = null): HeartbeatAck? {
        val resp = call("POST", "/api/v1/heartbeat", JSONObject().apply {
            put("phone_battery_pct", phoneBatteryPct)
            watchBatteryPct?.let { put("watch_battery_pct", it) }
            watchDataAgeS?.let { put("watch_data_age_s", it) }
            put("low_battery_warning", lowBatteryWarning)
            commandAck?.let { put("command_ack", it) }
        }) ?: return null
        return HeartbeatAck(resp.optString("state"), resp.optBoolean("degraded"),
                            resp.optString("command").ifEmpty { null })
    }

    data class HeartbeatAck(val state: String, val degraded: Boolean,
                            val command: String? = null)

    /** S1 latency drill result -> server event log (per phone model). */
    fun drillResult(launchMs: Int, rttMs: Long?, watchMs: Int,
                    transportMs: Long?, model: String): Boolean =
        call("POST", "/api/v1/drill-result", JSONObject().apply {
            put("launch_ms", launchMs)
            rttMs?.let { put("rtt_ms", it) }
            if (watchMs > 0) put("watch_ms", watchMs)
            transportMs?.let { put("transport_ms", it) }
            put("phone_model", model)
        }) != null

    fun alarm(detector: String, kind: String, lat: Double?, lon: Double?): String? {
        val resp = call("POST", "/api/v1/alarm", JSONObject().apply {
            put("detector", detector)
            put("kind", kind)
            lat?.let { put("lat", it) }
            lon?.let { put("lon", it) }
        }) ?: return null
        return resp.optString("escalation_id").ifEmpty { null }
    }

    fun resolve(escalationId: String, resolution: String): Boolean =
        request("POST", "/api/v1/alarm/$escalationId/resolve?resolution=$resolution",
                JSONObject()).first in 200..299

    /** Resolve every open watch-origin escalation for this wearer without
     *  needing its id — the cancel safety net when the id was lost to a
     *  dropped response or a service restart (review finding 8). */
    fun resolveOpenAlarms(): Boolean =
        request("POST", "/api/v1/alarm/resolve-open", JSONObject()).first in 200..299

    fun offlineWindow(durationS: Int): Boolean =
        call("POST", "/api/v1/offline-window",
             JSONObject().put("duration_s", durationS)) != null

    // ---- diagnostics (consented-diagnostics D3/D4) ----

    sealed class UploadResult {
        data class Ok(val id: String) : UploadResult()
        data class Failed(val why: String) : UploadResult()
    }

    /** Upload a bundle the wearer confirmed. The only path by which logs
     *  reach the server; there is no server-initiated pull. */
    fun uploadDiagnostics(bundle: File, days: Int, requestId: String?): UploadResult {
        if (!configured) return UploadResult.Failed("no server URL set")
        val q = "?days=$days" + (requestId?.let {
            "&request_id=" + java.net.URLEncoder.encode(it, "UTF-8") } ?: "")
        val url = urlProvider().trimEnd('/') + "/api/v1/diagnostics" + q
        val req = Request.Builder().url(url)
            .header("Authorization", "Bearer ${tokenProvider()}")
            .post(bundle.asRequestBody(ZIP))
            .build()
        return try {
            uploadHttp.newCall(req).execute().use { r ->
                CmLog.i(TAG, "POST $url -> ${r.code} (${bundle.length()} B)")
                when {
                    r.isSuccessful -> UploadResult.Ok(runCatching {
                        JSONObject(r.body?.string() ?: "{}").optString("id")
                    }.getOrDefault(""))
                    r.code == 401 -> UploadResult.Failed("token rejected (401)")
                    r.code == 413 -> UploadResult.Failed("too large for this server (413)")
                    else -> UploadResult.Failed("HTTP ${r.code}")
                }
            }
        } catch (e: IOException) {
            CmLog.i(TAG, "POST $url failed: $e")
            UploadResult.Failed("unreachable: ${e.javaClass.simpleName}")
        }
    }

    fun declineDiagnostics(requestId: String): Boolean =
        request("POST", "/api/v1/diagnostics/requests/" +
                java.net.URLEncoder.encode(requestId, "UTF-8") + "/decline",
                JSONObject()).first in 200..299

    // ---- plumbing ----

    private fun postForSave(path: String, body: JSONObject,
                            method: String = "POST"): SaveResult {
        val (code, text) = request(method, path, body)
        return when {
            code in 200..299 -> SaveResult.Ok
            code == 422 -> {
                val fields = runCatching {
                    val f = JSONObject(text).getJSONObject("detail")
                        .getJSONObject("fields")
                    f.keys().asSequence().associateWith { f.getString(it) }
                }.getOrDefault(emptyMap())
                if (fields.isNotEmpty()) SaveResult.FieldErrors(fields)
                else SaveResult.Failed("validation failed")
            }
            code < 0 -> SaveResult.Failed(lastResult)
            else -> SaveResult.Failed("HTTP $code")
        }
    }

    private fun call(method: String, path: String,
                     body: JSONObject? = null): JSONObject? {
        val (code, text) = request(method, path, body)
        return if (code in 200..299) runCatching { JSONObject(text) }.getOrNull()
        else null
    }

    /** Returns (statusCode, bodyText); statusCode -1 on transport failure. */
    private fun request(method: String, path: String,
                        body: JSONObject?): Pair<Int, String> {
        if (!configured) {
            lastResult = "no server URL set"
            return -1 to ""
        }
        val url = urlProvider().trimEnd('/') + path
        val builder = Request.Builder().url(url)
            .header("Authorization", "Bearer ${tokenProvider()}")
        when (method) {
            "GET" -> builder.get()
            "DELETE" -> builder.delete()
            else -> builder.method(method,
                (body ?: JSONObject()).toString().toRequestBody(JSON))
        }
        return try {
            http.newCall(builder.build()).execute().use { r ->
                lastResult = when {
                    r.isSuccessful -> "ok"
                    r.code == 401 -> "token rejected (401)"
                    else -> "HTTP ${r.code}"
                }
                CmLog.i(TAG, "$method $url -> ${r.code}")
                r.code to (r.body?.string() ?: "")
            }
        } catch (e: IOException) {
            lastResult = "unreachable: ${e.javaClass.simpleName}"
            CmLog.i(TAG, "$method $url failed: $e")
            -1 to ""
        }
    }

    private companion object {
        const val TAG = "ServerClient"
        val JSON = "application/json".toMediaType()
        val ZIP = "application/zip".toMediaType()
    }
}

private fun JSONObject.optStringOrNull(key: String): String? =
    if (isNull(key)) null else optString(key).ifEmpty { null }
