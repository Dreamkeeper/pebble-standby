package org.cryomonitor.companion

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import org.cryomonitor.companion.ui.AlarmScreen
import org.cryomonitor.companion.ui.countryIso

/**
 * Full-screen alarm over the lock screen (spec companion-ui: "readable at
 * arm's length at night"). Stays an Activity for setShowWhenLocked /
 * setTurnScreenOn and the full-screen intent (design D2); the content is
 * Compose and theme-independent. The bystander siren is owned by
 * MonitorService so it sounds even if this screen never launches.
 * Cancelling either stage sends USER_OK to the watch and retracts.
 */
class AlarmActivity : ComponentActivity() {

    private lateinit var settings: SettingsStore

    private val cancelledReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) = finish() // watch cancelled
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        settings = SettingsStore(this)

        val detector = intent.getStringExtra("detector") ?: "alert"
        val preAlarm = intent.getBooleanExtra("preAlarm", false)
        val seconds = intent.getIntExtra("seconds", 0)
        val number = EmergencyNumber.resolve(settings.emergencyNumber, countryIso(this))

        setContent {
            AlarmScreen(
                detector = detector,
                preAlarm = preAlarm,
                countdownSeconds = seconds,
                emergencyNumber = number,
                onCancel = { cause -> confirmVibe(); sendCancel(cause) },
                onCall = { startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number"))) },
            )
        }

        val filter = IntentFilter(MonitorService.ACTION_ALERT_CANCELLED)
        if (Build.VERSION.SDK_INT >= 33)
            registerReceiver(cancelledReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(cancelledReceiver, filter)
    }

    /** A cancel is confirmed by a distinct vibration, not only by colour (DESIGN §7). */
    private fun confirmVibe() {
        val v: Vibrator? = if (Build.VERSION.SDK_INT >= 31)
            getSystemService(VibratorManager::class.java)?.defaultVibrator
        else @Suppress("DEPRECATION") getSystemService(Vibrator::class.java)
        runCatching {
            v?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 80, 60, 80), -1))
        }
    }

    private fun sendCancel(cause: String) {
        startService(Intent(this, MonitorService::class.java)
            .setAction(MonitorService.ACTION_USER_CANCEL)
            .putExtra("cause", cause))
        finish()
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(cancelledReceiver) }
        super.onDestroy()
    }

    companion object {
        fun launch(ctx: Context, detector: String, preAlarm: Boolean) {
            ctx.startActivity(Intent(ctx, AlarmActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra("detector", detector)
                putExtra("preAlarm", preAlarm)
            })
        }
    }
}
