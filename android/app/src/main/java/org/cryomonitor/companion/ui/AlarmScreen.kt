package org.cryomonitor.companion.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.clickable
import kotlinx.coroutines.delay
import org.cryomonitor.companion.WearerWords

/**
 * The 3 a.m. screen (DESIGN §4.2, §7): state word, cause in wearer words,
 * display-size countdown, a 72 dp full-width cancel in the bottom third,
 * the emergency number below. Theme-independent colours from
 * [AlarmPalette]; no dependence on wallpaper or dark mode.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlarmScreen(
    detector: String,
    preAlarm: Boolean,
    countdownSeconds: Int,
    emergencyNumber: String,
    onCancel: (cause: String) -> Unit,
    onCall: () -> Unit,
) {
    var left by remember { mutableIntStateOf(countdownSeconds) }
    LaunchedEffect(preAlarm, countdownSeconds) {
        while (preAlarm && left > 0) { delay(1000); left-- }
    }
    var askCause by remember { mutableStateOf(false) }

    val bg = if (preAlarm) AlarmPalette.countdownBackground else AlarmPalette.alarmBackground
    val fg = AlarmPalette.onAlarm

    Box(Modifier.fillMaxSize().background(bg)) {
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(24.dp))
            Text(
                text = if (preAlarm) WearerWords.ALARM_SOON else WearerWords.ALARM,
                color = fg, style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = WearerWords.alarmReason(detector),
                color = fg, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center,
            )
            Spacer(Modifier.weight(1f))
            if (preAlarm) {
                Text(
                    text = if (left > 0) "$left" else "…",
                    color = fg, fontSize = 112.sp, fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center, lineHeight = 120.sp,
                )
                Text(
                    text = if (left > 0) "seconds until your people are alerted"
                           else "alerting your people",
                    color = fg, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center,
                )
            } else {
                Text(
                    text = "Your people are being alerted.\nCancel if this is a false alarm.",
                    color = fg, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center,
                )
            }
            Spacer(Modifier.weight(1f))
            Button(
                onClick = { askCause = true },
                modifier = Modifier.fillMaxWidth().height(80.dp),
                shape = RoundedCornerShape(20.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = AlarmPalette.cancelContainer, contentColor = AlarmPalette.onCancel),
            ) {
                Text("I'M OK — CANCEL", fontSize = 26.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(12.dp))
            OutlinedButton(
                onClick = onCall,
                modifier = Modifier.fillMaxWidth().height(56.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = fg),
            ) { Text("Call $emergencyNumber", fontSize = 20.sp) }
            Spacer(Modifier.height(8.dp))
        }
    }

    if (askCause) {
        ModalBottomSheet(onDismissRequest = { askCause = false }) {
            Column(Modifier.padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
                Text("Cancelled. What happened?", style = MaterialTheme.typography.titleLarge,
                     modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp))
                Text("Your answer helps tell false alarms apart from real ones. Nobody else sees it.",
                     style = MaterialTheme.typography.bodyMedium,
                     color = MaterialTheme.colorScheme.onSurfaceVariant,
                     modifier = Modifier.padding(horizontal = 24.dp))
                Spacer(Modifier.height(8.dp))
                listOf("Loose strap", "Slept on my arm", "Took the watch off",
                       "Real event, but I'm fine now", "Something else").forEach { cause ->
                    ListItem(
                        headlineContent = { Text(cause, style = MaterialTheme.typography.titleMedium) },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        modifier = Modifier.clickable { askCause = false; onCancel(cause) })
                }
                TextButton(onClick = { askCause = false; onCancel("skipped") },
                           modifier = Modifier.padding(horizontal = 16.dp)) { Text("Skip") }
            }
        }
    }
}
