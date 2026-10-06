package org.cryomonitor.companion

import android.app.KeyguardManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.cryomonitor.companion.ui.CmTheme

/**
 * The screen MonitorService launches from the background to prove that
 * the alarm screen could appear (PermissionChecks). It records the moment
 * its window gets focus and whether the phone was locked at that moment;
 * no sound, no alert, nobody is told. Same window flags as AlarmActivity
 * so the test exercises the same path.
 */
class PermissionTestActivity : ComponentActivity() {

    private var noted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        getSystemService(NotificationManager::class.java).cancel(MonitorService.NOTIF_PERM_TEST_ID)
        setContent {
            CmTheme {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(32.dp),
                           horizontalAlignment = Alignment.CenterHorizontally,
                           verticalArrangement = Arrangement.Center) {
                        Text(stringResource(R.string.perm_test_screen_title),
                             style = MaterialTheme.typography.headlineLarge,
                             color = MaterialTheme.colorScheme.primary, textAlign = TextAlign.Center)
                        Spacer(Modifier.height(16.dp))
                        Text(stringResource(R.string.perm_test_screen_body),
                             style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
                        Spacer(Modifier.height(32.dp))
                        Button(onClick = { backToStandby() }) {
                            Text(stringResource(R.string.perm_test_screen_back))
                        }
                    }
                }
            }
        }
    }

    /** Focus is the proof: a window behind the keyguard never gets it. */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus || noted) return
        noted = true
        val kind = runCatching {
            PermissionChecks.Kind.valueOf(intent.getStringExtra(EXTRA_KIND) ?: "")
        }.getOrDefault(PermissionChecks.Kind.POPUP)
        val locked = getSystemService(KeyguardManager::class.java).isKeyguardLocked
        PermissionChecks(this).noteAppeared(kind, locked)
        CmLog.i("PermTest", "test screen appeared kind=$kind locked=$locked")
    }

    private fun backToStandby() {
        startActivity(Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(MainActivity.EXTRA_ROUTE, MainActivity.ROUTE_PERMISSIONS))
        finish()
    }

    companion object {
        const val EXTRA_KIND = "kind"

        fun intent(c: Context, kind: PermissionChecks.Kind): Intent =
            Intent(c, PermissionTestActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(EXTRA_KIND, kind.name)
    }
}
