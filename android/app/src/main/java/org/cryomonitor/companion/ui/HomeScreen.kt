package org.cryomonitor.companion.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.cryomonitor.companion.Coverage
import org.cryomonitor.companion.CoverageAction
import org.cryomonitor.companion.CoverageState
import org.cryomonitor.companion.CoverageVerdict
import org.cryomonitor.companion.LiveState
import org.cryomonitor.companion.R
import org.cryomonitor.companion.ServerClient
import org.cryomonitor.companion.SettingsStore
import org.cryomonitor.companion.WearerWords

/** Home: the coverage verdict, then watch, people and server (DESIGN §4.1). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    settings: SettingsStore,
    onAction: (CoverageAction) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenContacts: () -> Unit,
    onOpenServer: () -> Unit,
    onOpenWatch: () -> Unit,
    onFireDrill: () -> Unit,
    onPause: (Long) -> Unit = {},
    nowMs: () -> Long = { System.currentTimeMillis() },
) {
    val facts by LiveState.facts.collectAsStateWithLifecycle()
    val battery by LiveState.watchBattery.collectAsStateWithLifecycle()
    val now = nowMs()
    val verdict = CoverageState.compute(facts, now)

    var people by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(facts.serverConfigured, facts.serverReachable, facts.noDeliverableContacts) {
        people = when {
            !facts.serverConfigured && facts.fallbackConfigured -> null
            !facts.serverConfigured -> null
            else -> withContext(Dispatchers.IO) {
                runCatching { ServerClient(settings).fetchContacts() }.getOrNull()
            }?.let { p ->
                val tiers = p.contacts.map { it.tierName }.distinct().size
                if (p.contacts.isEmpty()) null else "${p.contacts.size}|$tiers"
            } ?: "?"
        }
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(stringResource(R.string.home_title)) },
            actions = {
                IconButton(onClick = onOpenSettings) {
                    Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.home_settings))
                }
            })
    }) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            VerdictCard(verdict, onAction)
            // A planned pause (planned-pause): offered whenever nothing is
            // ringing and nothing is already paused.
            var pauseSheet by remember { mutableStateOf(false) }
            if (verdict.state == Coverage.COVERED || verdict.state == Coverage.NEEDS_ATTENTION) {
                FilledTonalButton(onClick = { pauseSheet = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.home_pause))
                }
            }
            if (pauseSheet) PauseSheet(now, onDismiss = { pauseSheet = false },
                                       onPick = { t -> pauseSheet = false; onPause(t) })

            InfoCard(
                title = stringResource(R.string.home_watch),
                headline = if (facts.watchConnected) stringResource(R.string.home_watch_linked)
                           else stringResource(R.string.home_watch_not_linked),
                lines = listOfNotNull(
                    battery?.takeIf { facts.lastWatchDataT > 0 && now - facts.lastWatchDataT < 3_600_000 }
                        ?.let { stringResource(R.string.home_watch_battery, it, WearerWords.ago(facts.lastWatchDataT, now)) },
                    facts.lastWorkerProofT.takeIf { it > 0 }
                        ?.let { stringResource(R.string.home_watch_last_report, WearerWords.ago(it, now)) },
                    if (facts.storeMode) stringResource(R.string.home_watch_store_mode) else null,
                ),
                onClick = onOpenWatch,
            )

            val peopleHeadline = when {
                !facts.serverConfigured && facts.fallbackConfigured -> stringResource(R.string.home_people_fallback)
                !facts.serverConfigured -> stringResource(R.string.home_people_none)
                facts.noDeliverableContacts == true -> stringResource(R.string.home_people_none)
                people == null -> stringResource(R.string.home_people_loading)
                people == "?" -> stringResource(R.string.home_people_unavailable)
                else -> {
                    val (n, t) = people!!.split('|').map { it.toInt() }
                    stringResource(R.string.home_people_count, n, t)
                }
            }
            InfoCard(
                title = stringResource(R.string.home_people),
                headline = peopleHeadline,
                lines = emptyList(),
                onClick = onOpenContacts,
                trailing = {
                    if (facts.serverConfigured) TextButton(onClick = onFireDrill) {
                        Text(stringResource(R.string.home_fire_drill))
                    }
                },
            )

            InfoCard(
                title = stringResource(R.string.home_server),
                headline = when {
                    !facts.serverConfigured -> stringResource(R.string.home_server_none)
                    facts.serverReachable -> stringResource(R.string.home_server_reachable)
                    else -> stringResource(R.string.home_server_unreachable)
                },
                lines = listOfNotNull(settings.serverUrl.takeIf { it.isNotEmpty() }
                    ?.removePrefix("https://")?.removePrefix("http://")),
                onClick = onOpenServer,
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun VerdictCard(v: CoverageVerdict, onAction: (CoverageAction) -> Unit) {
    val caution = CmColors.caution
    val scheme = MaterialTheme.colorScheme
    val (bg, fg) = when (v.state) {
        Coverage.COVERED -> scheme.primaryContainer to scheme.onPrimaryContainer
        Coverage.PAUSED -> scheme.tertiaryContainer to scheme.onTertiaryContainer
        Coverage.NEEDS_ATTENTION, Coverage.CHECK_IN -> caution.cautionContainer to caution.onCautionContainer
        Coverage.ALARM -> scheme.errorContainer to scheme.onErrorContainer
        Coverage.NOT_SET_UP -> scheme.surfaceVariant to scheme.onSurfaceVariant
    }
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = bg, contentColor = fg),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(v.title, style = MaterialTheme.typography.headlineMedium)
            Text(v.reason, style = MaterialTheme.typography.bodyLarge)
            if (v.action != null && v.actionLabel != null) {
                Spacer(Modifier.height(4.dp))
                Button(
                    onClick = { onAction(v.action) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = fg, contentColor = bg),
                ) { Text(v.actionLabel) }
            }
        }
    }
}

@Composable
private fun InfoCard(
    title: String,
    headline: String,
    lines: List<String>,
    onClick: () -> Unit,
    trailing: @Composable (() -> Unit)? = null,
) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.padding(16.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.labelLarge,
                     color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(headline, style = MaterialTheme.typography.titleMedium)
                lines.forEach {
                    Text(it, style = MaterialTheme.typography.bodyMedium,
                         color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            trailing?.invoke()
        }
    }
}

/**
 * "Pause monitoring": presets a wearer thinks in (a couple of hours, the
 * rest of today, until tomorrow morning) plus a clock for anything else.
 * Every row names the time it ends, so the choice is never a guess.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PauseSheet(nowMs: Long, onDismiss: () -> Unit, onPick: (Long) -> Unit) {
    val context = LocalContext.current
    val zone = java.time.ZoneId.systemDefault()
    val now = java.time.Instant.ofEpochMilli(nowMs).atZone(zone)
    fun clock(t: java.time.ZonedDateTime) = "%02d:%02d".format(t.hour, t.minute)
    fun ms(t: java.time.ZonedDateTime) = t.toInstant().toEpochMilli()
    val in2h = now.plusHours(2)
    val in4h = now.plusHours(4)
    val midnight = now.toLocalDate().atTime(23, 59).atZone(zone)
    val morning = now.toLocalDate().plusDays(1).atTime(8, 0).atZone(zone)
    val options = listOfNotNull(
        Triple(R.string.pause_2h, clock(in2h), ms(in2h)),
        Triple(R.string.pause_4h, clock(in4h), ms(in4h)),
        if (midnight.isAfter(in2h)) Triple(R.string.pause_until_midnight, clock(midnight), ms(midnight)) else null,
        Triple(R.string.pause_until_morning, clock(morning), ms(morning)),
    )
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(bottom = 24.dp)) {
            Text(stringResource(R.string.pause_title), style = MaterialTheme.typography.titleLarge,
                 modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
            Text(stringResource(R.string.pause_explain), style = MaterialTheme.typography.bodyMedium,
                 color = MaterialTheme.colorScheme.onSurfaceVariant,
                 modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp))
            options.forEach { (label, at, t) ->
                ListItem(
                    headlineContent = { Text(stringResource(label)) },
                    supportingContent = { Text(stringResource(R.string.pause_until, at)) },
                    modifier = Modifier.clickable { onPick(t) },
                )
            }
            ListItem(
                headlineContent = { Text(stringResource(R.string.pause_pick)) },
                modifier = Modifier.clickable {
                    android.app.TimePickerDialog(context, { _, h, m ->
                        var t = now.toLocalDate().atTime(h, m).atZone(zone)
                        if (!t.isAfter(now.plusMinutes(5))) t = t.plusDays(1)
                        onPick(ms(t))
                    }, now.hour, now.minute, true).show()
                },
            )
        }
    }
}
