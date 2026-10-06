package org.cryomonitor.companion.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import org.cryomonitor.companion.SettingsStore

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

@Composable
fun SettingsScreen(
    version: String,
    onBack: () -> Unit,
    onServer: () -> Unit,
    onPebble: () -> Unit,
    onPermissions: () -> Unit,
    onContacts: () -> Unit,
    onAdvanced: () -> Unit,
    onDiagnostics: () -> Unit,
    onSetupAgain: () -> Unit,
) {
    SubScreen(stringResource(R.string.settings_title), onBack) {
        SettingsRow(R.string.settings_server, R.string.settings_server_sub, onServer)
        SettingsRow(R.string.settings_pebble, R.string.settings_pebble_sub, onPebble)
        SettingsRow(R.string.settings_permissions, R.string.settings_permissions_sub, onPermissions)
        SettingsRow(R.string.settings_contacts, R.string.settings_contacts_sub, onContacts)
        HorizontalDivider()
        SettingsRow(R.string.settings_advanced, R.string.settings_advanced_sub, onAdvanced)
        SettingsRow(R.string.settings_diagnostics, R.string.settings_diagnostics_sub, onDiagnostics)
        SettingsRow(R.string.settings_setup_again, R.string.settings_setup_again_sub, onSetupAgain)
        HorizontalDivider()
        Text(stringResource(R.string.settings_version, version),
             style = MaterialTheme.typography.bodySmall,
             color = MaterialTheme.colorScheme.onSurfaceVariant,
             modifier = Modifier.padding(16.dp))
    }
}

@Composable
private fun SettingsRow(title: Int, sub: Int, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(stringResource(title)) },
        supportingContent = { Text(stringResource(sub)) },
        modifier = Modifier.clickable(onClick = onClick),
    )
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
                listOf("auto" to R.string.pebble_mode_auto, "patched" to R.string.pebble_mode_patched,
                       "store" to R.string.pebble_mode_store).forEach { (v, label) ->
                    FilterChip(selected = mode == v, onClick = { mode = v },
                               label = { Text(stringResource(label).substringBefore(" (")) })
                }
            }
            Text(stringResource(
                when (mode) { "patched" -> R.string.pebble_mode_patched
                              "store" -> R.string.pebble_mode_store
                              else -> R.string.pebble_mode_auto }),
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

private enum class PermState { GRANTED, MISSING, UNKNOWN }

@Composable
fun PermissionsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var tick by remember { mutableStateOf(0) }
    // Re-check on every resume: the wearer comes back from a system page.
    val owner = LocalLifecycleOwner.current
    LaunchedEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) tick++ }
        owner.lifecycle.addObserver(obs)
    }
    SubScreen(stringResource(R.string.perm_title), onBack) {
        Text(stringResource(R.string.perm_intro), style = MaterialTheme.typography.bodyMedium,
             color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp))
        key(tick) {
            PermRow(R.string.perm_battery, R.string.perm_battery_sub,
                    state = if (context.getSystemService(PowerManager::class.java)
                        .isIgnoringBatteryOptimizations(context.packageName)) PermState.GRANTED
                    else PermState.MISSING) {
                context.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:${context.packageName}")))
            }
            PermRow(R.string.perm_fullscreen, R.string.perm_fullscreen_sub,
                    state = if (Build.VERSION.SDK_INT >= 34) {
                        if (context.getSystemService(android.app.NotificationManager::class.java)
                            .canUseFullScreenIntent()) PermState.GRANTED else PermState.MISSING
                    } else PermState.GRANTED) {
                openFirst(context, listOfNotNull(
                    if (Build.VERSION.SDK_INT >= 34) Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
                        Uri.parse("package:${context.packageName}")) else null,
                    appDetails(context)))
            }
            PermRow(R.string.perm_popup, R.string.perm_popup_sub, state = PermState.UNKNOWN) {
                openFirst(context, listOf(
                    Intent("miui.intent.action.APP_PERM_EDITOR").apply {
                        setClassName("com.miui.securitycenter",
                            "com.miui.permcenter.permissions.PermissionsEditorActivity")
                        putExtra("extra_pkgname", context.packageName)
                    }, appDetails(context)))
            }
            PermRow(R.string.perm_autostart, R.string.perm_autostart_sub, state = PermState.UNKNOWN) {
                openFirst(context, listOf(
                    Intent().setClassName("com.miui.securitycenter",
                        "com.miui.permcenter.autostart.AutoStartManagementActivity"),
                    appDetails(context)))
            }
            PermRow(R.string.perm_exact, R.string.perm_exact_sub,
                    state = if (Build.VERSION.SDK_INT >= 31) {
                        if (context.getSystemService(android.app.AlarmManager::class.java)
                            .canScheduleExactAlarms()) PermState.GRANTED else PermState.MISSING
                    } else PermState.GRANTED) {
                openFirst(context, listOfNotNull(
                    if (Build.VERSION.SDK_INT >= 31) Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                        Uri.parse("package:${context.packageName}")) else null,
                    appDetails(context)))
            }
            PermRow(R.string.perm_notifications, R.string.perm_notifications_sub,
                    state = if (context.getSystemService(android.app.NotificationManager::class.java)
                        .areNotificationsEnabled()) PermState.GRANTED else PermState.MISSING) {
                openFirst(context, listOf(
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
                    appDetails(context)))
            }
        }
    }
}

@Composable
private fun key(k: Int, content: @Composable () -> Unit) {
    androidx.compose.runtime.key(k) { content() }
}

@Composable
private fun PermRow(title: Int, sub: Int, state: PermState, onOpen: () -> Unit) {
    val caution = CmColors.caution
    ListItem(
        headlineContent = { Text(stringResource(title)) },
        supportingContent = { Text(stringResource(sub)) },
        trailingContent = {
            when (state) {
                PermState.GRANTED -> Text(stringResource(R.string.perm_granted),
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelLarge)
                PermState.MISSING -> Text(stringResource(R.string.perm_missing),
                    color = caution.caution, style = MaterialTheme.typography.labelLarge)
                PermState.UNKNOWN -> Text(stringResource(R.string.perm_unknown),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelLarge)
            }
        },
        modifier = Modifier.clickable(onClick = onOpen),
    )
}

private fun appDetails(context: Context) =
    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))

private fun openFirst(context: Context, intents: List<Intent>) {
    for (i in intents) {
        try { context.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return }
        catch (_: Exception) { /* next */ }
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
