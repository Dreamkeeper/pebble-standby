package org.cryomonitor.companion

import android.annotation.SuppressLint
import android.content.Context
import android.location.LocationManager
import android.telephony.SmsManager
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * Phone-direct escalation: SMS + direct Telegram. Primary escalation runs on
 * the server; this layer fires when the server is unreachable — and the SMS
 * part also fires alongside the server for redundancy (SMS reaches contacts
 * with no data connectivity).
 *
 * TODO(M2): sequential auto-calls with TTS; delivery-report tracking.
 * TODO(M4): gateway role — execute send-SMS/place-call commands from the
 * server when running on a spare handset (sideload flavor only).
 */
class Escalator(private val context: Context, private val settings: SettingsStore) {

    private val http = OkHttpClient()

    fun fire(detector: String, isTest: Boolean, serverReachable: Boolean) {
        val loc = lastKnownLocation()
        val mapsLink = loc?.let { "https://maps.google.com/?q=${it.first},${it.second}" }
        val prefix = if (isTest) "[TEST] " else ""
        val text = prefix + "STANDBY ALERT: ${settings.wearerName} — " +
            "$detector alarm, wearer unresponsive. " +
            (mapsLink ?: "Location pending.") +
            " Please respond / call them now."
        sendSmsToAll(text)
        // Spec (escalation-and-deadman): the server owns Telegram (ACK
        // buttons, tiers, retries); phone-direct Telegram fires ONLY when
        // the server is unreachable. It used to fire always: duplicates
        // for any wearer with both a server and a phone bot token.
        if (serverReachable) {
            CmLog.i(TAG, "server reachable: it owns Telegram, phone-direct skipped")
        } else {
            settings.telegramDirectFired = true
            sendTelegramToAll(text)
        }
    }

    fun cancel(reason: String) {
        val text = "Standby: previous alert CANCELLED ($reason). " +
            "${settings.wearerName} is OK."
        sendSmsToAll(text)
        // Retract only on channels that actually fired (persisted: the
        // process may have restarted between the alarm and the cancel).
        if (settings.telegramDirectFired) {
            settings.telegramDirectFired = false
            sendTelegramToAll(text)
        }
    }

    /**
     * Send a [TEST] message through the phone-direct fallback bot and report
     * Telegram's answer per chat id. The fallback only fires when the server
     * is unreachable, so without this the first real use would also be the
     * first test. Blocking: call off the main thread.
     */
    fun testTelegramDirect(): List<String> {
        val token = settings.telegramBotToken
        if (token.isEmpty()) return listOf("No fallback bot token is set.")
        if (settings.telegramChatIds.isEmpty()) return listOf("No fallback chat ids are set.")
        val text = "[TEST] Standby fallback channel: this is how an alert looks " +
            "when the server cannot be reached. Wearer: ${settings.wearerName}."
        return settings.telegramChatIds.map { chatId ->
            runCatching {
                val body = JSONObject().put("chat_id", chatId).put("text", text)
                http.newCall(Request.Builder()
                    .url("https://api.telegram.org/bot$token/sendMessage")
                    .post(body.toString().toRequestBody("application/json".toMediaType()))
                    .build()).execute().use { resp ->
                        val why = runCatching {
                            JSONObject(resp.body?.string() ?: "{}").optString("description")
                        }.getOrDefault("")
                        CmLog.i(TAG, "fallback test to $chatId -> ${resp.code} $why")
                        when (resp.code) {
                            200 -> "$chatId: delivered"
                            401, 404 -> "$chatId: bot token rejected (${resp.code}) — check the token"
                            403 -> "$chatId: blocked — this person has not pressed Start on the fallback bot"
                            400 -> "$chatId: $why — check the chat id"
                            else -> "$chatId: HTTP ${resp.code} $why"
                        }
                    }
            }.getOrElse { "$chatId: network error — ${it.javaClass.simpleName}" }
        }
    }

    private fun sendSmsToAll(text: String) {
        val sms = runCatching { context.getSystemService(SmsManager::class.java) }
            .getOrNull() ?: return
        for (number in settings.smsContacts) {
            try {
                sms.sendMultipartTextMessage(
                    number, null, sms.divideMessage(text), null, null)
                CmLog.i(TAG, "SMS sent to $number (${text.length} chars)")
            } catch (e: SecurityException) {
                CmLog.w(TAG, "SMS not permitted (play flavor?): $e")
            } catch (e: Exception) {
                CmLog.e(TAG, "SMS to $number failed", e)
            }
        }
    }

    private fun sendTelegramToAll(text: String) {
        val token = settings.telegramBotToken
        if (token.isEmpty()) return
        for (chatId in settings.telegramChatIds) {
            val body = JSONObject().put("chat_id", chatId).put("text", text)
            runCatching {
                http.newCall(Request.Builder()
                    .url("https://api.telegram.org/bot$token/sendMessage")
                    .post(body.toString()
                        .toRequestBody("application/json".toMediaType()))
                    .build()).execute().use {
                        CmLog.i(TAG, "Telegram to $chatId -> ${it.code}")
                    }
            }.onFailure { CmLog.e(TAG, "Telegram to $chatId failed", it) }
        }
    }

    @SuppressLint("MissingPermission")
    fun lastKnownLocation(): Pair<Double, Double>? = runCatching {
        val lm = context.getSystemService(LocationManager::class.java)
        val loc = lm.getProviders(true)
            .mapNotNull { lm.getLastKnownLocation(it) }
            .maxByOrNull { it.time } ?: return null
        Pair(loc.latitude, loc.longitude)
    }.getOrNull()

    companion object { private const val TAG = "Escalator" }
}
