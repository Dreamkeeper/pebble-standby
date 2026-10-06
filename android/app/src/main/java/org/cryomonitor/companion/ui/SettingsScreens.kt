package org.cryomonitor.companion.ui

import android.content.Context
import android.content.Intent
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.cryomonitor.companion.EmergencyNumber
import org.cryomonitor.companion.Escalator
import org.cryomonitor.companion.LiveState
import org.cryomonitor.companion.MonitorService
import org.cryomonitor.companion.R
import org.cryomonitor.companion.ServerClient
import org.cryomonitor.companion.SettingsStore
import org.cryomonitor.companion.WatchConfig

/** Shared scaffold with a back arrow. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubScreen(title: String, onBack: () -> Unit, content: @Composable () -> Unit) {
    Scaffold(topBar = {
        TopAppBar(title = { Text(title) }, navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack,
                     contentDescription = stringResource(R.string.common_back))
            }
        })
    }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState())) {
            content()
            Spacer(Modifier.height(32.dp))
        }
    }
}

/** Re-run [block] when the screen comes back to the foreground. */
@Composable
fun onResumeTick(): Int {
    var tick by remember { mutableIntStateOf(0) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) tick++ }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    return tick
}

@Composable
private fun SectionHeader(text: Int) {
    Text(stringResource(text), style = MaterialTheme.typography.titleSmall,
         color = MaterialTheme.colorScheme.primary,
         modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 4.dp))
}

/** A settings row: title, its current state as the value line, optional switch. */
@Composable
private fun ValueRow(title: String, value: String, onClick: (() -> Unit)?,
                     trailing: (@Composable () -> Unit)? = null) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(value) },
        trailingContent = trailing,
        modifier = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier,
    )
}

/**
 * Settings (DESIGN §4.5): features first, state on every row, switches
 * inline. Nothing here describes itself; it states its state.
 */
@Composable
fun SettingsScreen(
    settings: SettingsStore,
    cfg: WatchConfig,
    version: String,
    onBack: () -> Unit,
    onDetector: (Detector) -> Unit,
    onWhenItAsks: () -> Unit,
    onContacts: () -> Unit,
    onAdvanced: () -> Unit,
    onServer: () -> Unit,
    onPermissions: () -> Unit,
    onPebble: () -> Unit,
    onDiagnostics: () -> Unit,
    onSetupAgain: () -> Unit,
) {
    val context = LocalContext.current
    val facts by LiveState.facts.collectAsStateWithLifecycle()
    val tick = onResumeTick()
    var revision by remember { mutableIntStateOf(0) }

    // People summary for the contacts row
    var people by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(facts.serverConfigured, tick) {
        people = if (!facts.serverConfigured) null else withContext(Dispatchers.IO) {
            runCatching { ServerClient(settings).fetchContacts() }.getOrNull()
        }?.let { p ->
            if (p.contacts.isEmpty()) context.getString(R.string.contacts_nobody_short)
            else p.contacts.joinToString(", ") { it.name } +
                " · " + context.getString(R.string.settings_tiers, p.tiers.size)
        }
    }

    SubScreen(stringResource(R.string.settings_title), onBack) {
        androidx.compose.runtime.key(revision, tick) {
            SectionHeader(R.string.settings_section_detectors)
            Detector.entries.forEach { d ->
                val enabled = cfg.detectorEnabled(d.enabledField)
                ValueRow(stringResource(d.title), detectorValueLine(d, cfg), onClick = { onDetector(d) }) {
                    Switch(checked = enabled, onCheckedChange = {
                        cfg.set(d.enabledField, if (it) 1 else 0)
                        notifyWatchConfigChanged(context)
                        revision++
                    })
                }
            }
            ValueRow(stringResource(R.string.det_sos), stringResource(R.string.state_always_on), onClick = null)

            SectionHeader(R.string.settings_section_when)
            ValueRow(stringResource(R.string.when_title), whenItAsksValue(cfg), onClick = onWhenItAsks)

            SectionHeader(R.string.settings_section_who)
            ValueRow(stringResource(R.string.settings_contacts),
                     people ?: if (facts.serverConfigured) stringResource(R.string.home_people_loading)
                               else stringResource(R.string.home_people_none),
                     onClick = onContacts)
            val country = countryIso(context)
            val emergency = EmergencyNumber.resolve(settings.emergencyNumber, country)
            ValueRow(stringResource(R.string.adv_emergency),
                     if (settings.emergencyNumber.isNotEmpty()) stringResource(R.string.emergency_override, emergency)
                     else stringResource(R.string.emergency_default, emergency, country?.uppercase() ?: "?"),
                     onClick = onAdvanced)

            SectionHeader(R.string.settings_section_server)
            val host = settings.serverUrl.removePrefix("https://").removePrefix("http://")
            ValueRow(stringResource(R.string.settings_server),
                     when {
                         !facts.serverConfigured -> stringResource(R.string.server_not_enrolled_short)
                         facts.serverReachable -> "$host · " + stringResource(R.string.home_server_reachable).lowercase()
                         else -> "$host · " + stringResource(R.string.home_server_unreachable).lowercase()
                     }, onClick = onServer)

            SectionHeader(R.string.settings_section_phone)
            ValueRow(stringResource(R.string.settings_permissions), Permissions.summary(context), onClick = onPermissions)
            ValueRow(stringResource(R.string.settings_pebble), pebbleModeValue(context, settings, facts.storeMode),
                     onClick = onPebble)

            SectionHeader(R.string.settings_section_more)
            ValueRow(stringResource(R.string.settings_advanced), stringResource(R.string.settings_advanced_sub), onClick = onAdvanced)
            ValueRow(stringResource(R.string.settings_diagnostics), stringResource(R.string.settings_diagnostics_sub), onClick = onDiagnostics)
            ValueRow(stringResource(R.string.settings_setup_again), stringResource(R.string.settings_setup_again_sub), onClick = onSetupAgain)
            Text(stringResource(R.string.settings_version, version),
                 style = MaterialTheme.typography.bodySmall,
                 color = MaterialTheme.colorScheme.onSurfaceVariant,
                 modifier = Modifier.padding(16.dp))
        }
    }
}

private fun pebbleModeValue(context: Context, settings: SettingsStore, storeMode: Boolean): String {
    val mode = when (settings.pebbleAppMode) {
        "patched" -> context.getString(R.string.pebble_mode_patched_short)
        "store" -> context.getString(R.string.pebble_mode_store_short)
        else -> context.getString(R.string.pebble_mode_auto)
    }
    val detected = if (storeMode) context.getString(R.string.pebble_detected_store)
                   else context.getString(R.string.pebble_detected_patched)
    return "$mode · $detected"
}

// ---- Your server ----

@Composable
fun ServerScreen(settings: SettingsStore, onBack: () -> Unit, onEnrol: () -> Unit) {
    val facts by LiveState.facts.collectAsStateWithLifecycle()
    val host = settings.serverUrl.removePrefix("https://").removePrefix("http://")
    SubScreen(stringResource(R.string.server_title), onBack) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(if (settings.serverUrl.isNotEmpty()) stringResource(R.string.server_enrolled, host)
                 else stringResource(R.string.server_not_enrolled),
                 style = MaterialTheme.typography.titleMedium)
            if (settings.serverUrl.isNotEmpty()) {
                Text(stringResource(R.string.server_status,
                    if (facts.serverReachable) stringResource(R.string.home_server_reachable)
                    else facts.serverLastResult.ifEmpty { stringResource(R.string.home_server_unreachable) }),
                    style = MaterialTheme.typography.bodyMedium)
            }
            Text(stringResource(R.string.server_explain), style = MaterialTheme.typography.bodyMedium,
                 color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = onEnrol) {
                Text(stringResource(if (settings.serverUrl.isEmpty()) R.string.server_enrol
                                    else R.string.server_reenrol))
            }
        }
    }
}

// ---- Pebble app on this phone ----

@Composable
fun PebbleScreen(settings: SettingsStore, onBack: () -> Unit) {
    val context = LocalContext.current
    var mode by remember { mutableStateOf(settings.pebbleAppMode) }
    var sync by remember { mutableStateOf(settings.watchSyncIntervalMin.toString()) }
    SubScreen(stringResource(R.string.pebble_title), onBack) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.pebble_mode), style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("auto" to R.string.pebble_mode_auto, "patched" to R.string.pebble_mode_patched_short,
                       "store" to R.string.pebble_mode_store_short).forEach { (v, label) ->
                    FilterChip(selected = mode == v, onClick = { mode = v }, label = { Text(stringResource(label)) })
                }
            }
            Text(stringResource(
                when (mode) { "patched" -> R.string.pebble_mode_patched
                              "store" -> R.string.pebble_mode_store
                              else -> R.string.pebble_mode_auto_explain }),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(value = sync, onValueChange = { sync = it.filter { c -> c.isDigit() } },
                              label = { Text(stringResource(R.string.pebble_sync)) },
                              modifier = Modifier.fillMaxWidth(), singleLine = true)
            Text(stringResource(R.string.pebble_explain), style = MaterialTheme.typography.bodyMedium,
                 color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = {
                settings.pebbleAppMode = mode
                settings.watchSyncIntervalMin = sync.toIntOrNull() ?: 60
                context.startService(Intent(context, MonitorService::class.java)
                    .setAction(MonitorService.ACTION_HEARTBEAT_NOW))
                onBack()
            }) { Text(stringResource(R.string.adv_save)) }
        }
    }
}

// ---- Let it run (permissions with live status) ----

@Composable
fun PermissionsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val tick = onResumeTick()
    val caution = CmColors.caution
    SubScreen(stringResource(R.string.perm_title), onBack) {
        Text(stringResource(R.string.perm_intro), style = MaterialTheme.typography.bodyMedium,
             color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp))
        androidx.compose.runtime.key(tick) {
            Permissions.rows(context).forEach { row ->
                ListItem(
                    headlineContent = { Text(stringResource(row.title)) },
                    supportingContent = { Text(stringResource(row.sub)) },
                    trailingContent = {
                        when (row.state) {
                            Permissions.State.GRANTED -> Text(stringResource(R.string.perm_granted),
                                color = MaterialTheme.colorScheme.primary,
                                style = MaterialTheme.typography.labelLarge)
                            Permissions.State.MISSING -> Text(stringResource(R.string.perm_missing),
                                color = caution.caution, style = MaterialTheme.typography.labelLarge)
                            Permissions.State.UNKNOWN -> Text(stringResource(R.string.perm_unknown),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.labelLarge)
                        }
                    },
                    modifier = Modifier.clickable { Permissions.openFirst(context, row.open) },
                )
            }
        }
    }
}

// ---- Advanced ----

@Composable
fun AdvancedScreen(settings: SettingsStore, onBack: () -> Unit) {
    val context = LocalContext.current
    var name by remember { mutableStateOf(settings.wearerName.takeIf { it != "the wearer" } ?: "") }
    var emergency by remember { mutableStateOf(settings.emergencyNumber) }
    var tgToken by remember { mutableStateOf(settings.telegramBotToken) }
    var tgChats by remember { mutableStateOf(settings.telegramChatIds.joinToString(",")) }
    var url by remember { mutableStateOf(settings.serverUrl) }
    var token by remember { mutableStateOf(settings.apiToken) }
    var testResult by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }
    val countryDefault = EmergencyNumber.forCountry(countryIso(context))

    SubScreen(stringResource(R.string.adv_title), onBack) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(value = name, onValueChange = { name = it },
                label = { Text(stringResource(R.string.adv_wearer_name)) },
                modifier = Modifier.fillMaxWidth(), singleLine = true)
            OutlinedTextField(value = emergency, onValueChange = { emergency = it },
                label = { Text(stringResource(R.string.adv_emergency)) },
                supportingText = { Text(stringResource(R.string.adv_emergency_sub, countryDefault)) },
                modifier = Modifier.fillMaxWidth(), singleLine = true)

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Text(stringResource(R.string.adv_fallback_header), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.adv_fallback_explain), style = MaterialTheme.typography.bodyMedium,
                 color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(value = tgToken, onValueChange = { tgToken = it },
                label = { Text(stringResource(R.string.adv_tg_token)) },
                modifier = Modifier.fillMaxWidth(), singleLine = true)
            OutlinedTextField(value = tgChats, onValueChange = { tgChats = it },
                label = { Text(stringResource(R.string.adv_tg_chats)) },
                modifier = Modifier.fillMaxWidth(), singleLine = true)
            OutlinedButton(enabled = !testing, onClick = {
                settings.telegramBotToken = tgToken.trim()
                settings.telegramChatIds = tgChats.split(',').map { it.trim() }.filter { it.isNotEmpty() }
                testing = true
                Thread {
                    val lines = Escalator(context, settings).testTelegramDirect()
                    testResult = lines.joinToString("\n\n")
                    testing = false
                }.start()
            }) { Text(stringResource(R.string.adv_tg_test)) }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Text(stringResource(R.string.adv_manual_header), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.adv_manual_explain), style = MaterialTheme.typography.bodyMedium,
                 color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(value = url, onValueChange = { url = it },
                label = { Text(stringResource(R.string.adv_server_url)) },
                modifier = Modifier.fillMaxWidth(), singleLine = true)
            OutlinedTextField(value = token, onValueChange = { token = it },
                label = { Text(stringResource(R.string.adv_api_token)) },
                modifier = Modifier.fillMaxWidth(), singleLine = true)

            Button(onClick = {
                settings.wearerName = name.trim().ifEmpty { "the wearer" }
                settings.emergencyNumber = emergency
                settings.telegramBotToken = tgToken.trim()
                settings.telegramChatIds = tgChats.split(',').map { it.trim() }.filter { it.isNotEmpty() }
                settings.serverUrl = url.trim()
                settings.apiToken = token.trim()
                context.startService(Intent(context, MonitorService::class.java)
                    .setAction(MonitorService.ACTION_HEARTBEAT_NOW))
                onBack()
            }) { Text(stringResource(R.string.adv_save)) }
        }
    }
    testResult?.let { r ->
        AlertDialog(onDismissRequest = { testResult = null },
            title = { Text(stringResource(R.string.adv_tg_test_title)) },
            text = { Text(r) },
            confirmButton = { TextButton(onClick = { testResult = null }) { Text(stringResource(R.string.common_ok)) } })
    }
}

/** Network country, then SIM, then locale (design D6). */
fun countryIso(context: Context): String? {
    val tm = context.getSystemService(android.telephony.TelephonyManager::class.java)
    return tm?.networkCountryIso?.takeIf { it.isNotBlank() }
        ?: tm?.simCountryIso?.takeIf { it.isNotBlank() }
        ?: java.util.Locale.getDefault().country.takeIf { it.isNotBlank() }
}
