package org.cryomonitor.companion.ui

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.cryomonitor.companion.MonitorService
import org.cryomonitor.companion.R
import org.cryomonitor.companion.ServerClient
import org.cryomonitor.companion.SettingsStore

/**
 * Contacts & safety net (DESIGN §4.4): tiers explained in place, channel
 * icons instead of protocol words, inline help for the chat id, and the
 * fire drill next to the configuration it verifies. The server stays the
 * source of truth; every edit is an API call with inline field errors.
 */
@Composable
fun ContactsScreen(settings: SettingsStore, onBack: () -> Unit) {
    val context = LocalContext.current
    val client = remember { ServerClient(settings) }
    val scope = rememberCoroutineScope()
    var payload by remember { mutableStateOf<ServerClient.ContactsPayload?>(null) }
    var status by remember { mutableStateOf<ServerClient.WearerStatus?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableStateOf(0) }
    var editing by remember { mutableStateOf<ServerClient.Contact?>(null) }
    var selfEditing by remember { mutableStateOf(false) }
    var drillSent by remember { mutableStateOf(false) }

    LaunchedEffect(reload) {
        loadError = null
        val (p, s) = withContext(Dispatchers.IO) { client.fetchContacts() to client.fetchStatus() }
        payload = p; status = s
        if (p == null) loadError = client.lastResult
    }

    SubScreen(stringResource(R.string.contacts_title), onBack) {
        val p = payload
        if (p == null) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(if (loadError == null) stringResource(R.string.contacts_loading)
                     else stringResource(R.string.contacts_load_failed, loadError ?: ""))
                if (loadError != null) OutlinedButton(onClick = { reload++ }) {
                    Text(stringResource(R.string.contacts_retry))
                }
            }
            return@SubScreen
        }
        val tiers = p.tiers.sortedBy { it.position }

        if (status?.degraded == true) {
            val caution = CmColors.caution
            Card(Modifier.padding(16.dp).fillMaxWidth(),
                 colors = CardDefaults.cardColors(containerColor = caution.cautionContainer,
                                                  contentColor = caution.onCautionContainer)) {
                Text(stringResource(R.string.contacts_nobody), Modifier.padding(16.dp),
                     style = MaterialTheme.typography.titleMedium)
            }
        }

        // Tiers, explained in place
        Text(stringResource(R.string.contacts_order), style = MaterialTheme.typography.titleMedium,
             modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp))
        tiers.forEachIndexed { idx, tier ->
            val members = p.contacts.filter { it.tierName == tier.name }
            val names = if (members.isEmpty()) stringResource(R.string.contacts_nobody_short)
                        else members.joinToString(", ") { it.name }
            val timing = if (idx == 0) stringResource(R.string.contacts_tier_first)
                         else stringResource(R.string.contacts_tier_next, tiers[idx - 1].promoteAfterS / 60)
            ListItem(
                headlineContent = { Text("${idx + 1}. ${tier.name}: $names") },
                supportingContent = { Text(timing + " " +
                    stringResource(R.string.contacts_tier_repeat, tier.repeatAfterS / 60)) })
        }
        HorizontalDivider(Modifier.padding(vertical = 8.dp))

        // Contacts
        Text(stringResource(R.string.contacts_people), style = MaterialTheme.typography.titleMedium,
             modifier = Modifier.padding(horizontal = 16.dp))
        p.contacts.forEach { c ->
            ListItem(
                headlineContent = { Text(c.name) },
                supportingContent = { Text(channelsLine(c) + " · " + c.tierName) },
                modifier = Modifier.clickable { editing = c })
        }
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = {
                editing = ServerClient.Contact(null, "", tiers.firstOrNull()?.name ?: "primary",
                                               null, null, null)
            }) { Text(stringResource(R.string.contacts_add)) }
            OutlinedButton(onClick = { selfEditing = true }) {
                Text(stringResource(R.string.contacts_self))
            }
        }
        HorizontalDivider(Modifier.padding(vertical = 8.dp))

        // Verify: the drill lives next to what it verifies
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.contacts_verify), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.contacts_verify_explain),
                 style = MaterialTheme.typography.bodyMedium,
                 color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = {
                context.startService(Intent(context, MonitorService::class.java)
                    .setAction(MonitorService.ACTION_TEST_ALARM))
                drillSent = true
            }) { Text(stringResource(R.string.home_fire_drill)) }
            if (drillSent) Text(stringResource(R.string.contacts_drill_sent),
                                style = MaterialTheme.typography.bodyMedium)
        }
    }

    editing?.let { c ->
        ContactEditor(c, tiers = payload?.tiers?.sortedBy { it.position }?.map { it.name } ?: listOf("primary"),
            onDismiss = { editing = null },
            onSave = { candidate, onErrors ->
                scope.launch {
                    when (val r = withContext(Dispatchers.IO) { client.saveContact(candidate) }) {
                        is ServerClient.SaveResult.Ok -> { editing = null; reload++ }
                        is ServerClient.SaveResult.FieldErrors -> onErrors(r.fields)
                        is ServerClient.SaveResult.Failed -> onErrors(mapOf("name" to r.why))
                    }
                }
            },
            onDelete = if (c.id == null) null else { {
                scope.launch {
                    withContext(Dispatchers.IO) { client.deleteContact(c.id) }
                    editing = null; reload++
                }
            } })
    }
    if (selfEditing) {
        SelfNotifyEditor(onDismiss = { selfEditing = false }, onSave = { tg, ntfy, email, onErrors ->
            scope.launch {
                when (val r = withContext(Dispatchers.IO) { client.setSelfNotify(tg, ntfy, email) }) {
                    is ServerClient.SaveResult.Ok -> selfEditing = false
                    is ServerClient.SaveResult.FieldErrors -> onErrors(r.fields)
                    is ServerClient.SaveResult.Failed -> onErrors(mapOf("telegram_chat_id" to r.why))
                }
            }
        })
    }
}

@Composable
private fun channelsLine(c: ServerClient.Contact): String = listOfNotNull(
    c.telegramChatId?.let { stringResource(R.string.channel_telegram) },
    c.ntfyTopic?.let { stringResource(R.string.channel_ntfy) },
    c.email?.let { stringResource(R.string.channel_email) },
).joinToString(" · ")

@Composable
private fun ContactEditor(
    contact: ServerClient.Contact,
    tiers: List<String>,
    onDismiss: () -> Unit,
    onSave: (ServerClient.Contact, (Map<String, String>) -> Unit) -> Unit,
    onDelete: (() -> Unit)?,
) {
    var name by remember { mutableStateOf(contact.name) }
    var tg by remember { mutableStateOf(contact.telegramChatId ?: "") }
    var ntfy by remember { mutableStateOf(contact.ntfyTopic ?: "") }
    var email by remember { mutableStateOf(contact.email ?: "") }
    var tier by remember { mutableStateOf(contact.tierName) }
    var errors by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var confirmDelete by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (contact.id == null) R.string.contacts_add else R.string.contacts_edit)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it },
                    label = { Text(stringResource(R.string.contact_name)) },
                    isError = errors["name"] != null, supportingText = errors["name"]?.let { { Text(it) } },
                    singleLine = true)
                OutlinedTextField(value = tg, onValueChange = { tg = it },
                    label = { Text(stringResource(R.string.contact_telegram)) },
                    supportingText = { Text(errors["telegram_chat_id"] ?: errors["channels"]
                        ?: stringResource(R.string.contact_telegram_help)) },
                    isError = errors["telegram_chat_id"] != null || errors["channels"] != null,
                    singleLine = true)
                OutlinedTextField(value = ntfy, onValueChange = { ntfy = it },
                    label = { Text(stringResource(R.string.contact_ntfy)) },
                    isError = errors["ntfy_topic"] != null, supportingText = errors["ntfy_topic"]?.let { { Text(it) } },
                    singleLine = true)
                OutlinedTextField(value = email, onValueChange = { email = it },
                    label = { Text(stringResource(R.string.contact_email)) },
                    isError = errors["email"] != null, supportingText = errors["email"]?.let { { Text(it) } },
                    singleLine = true)
                Text(stringResource(R.string.contact_tier), style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    tiers.forEach { t -> FilterChip(selected = tier == t, onClick = { tier = t }, label = { Text(t) }) }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val candidate = contact.copy(
                    name = name.trim(), tierName = tier,
                    telegramChatId = tg.trim().ifEmpty { null },
                    ntfyTopic = ntfy.trim().ifEmpty { null },
                    email = email.trim().ifEmpty { null })
                if (candidate.name.isEmpty()) { errors = mapOf("name" to "Required"); return@TextButton }
                if (candidate.telegramChatId == null && candidate.ntfyTopic == null && candidate.email == null) {
                    errors = mapOf("channels" to "At least one way to reach them"); return@TextButton
                }
                onSave(candidate) { errors = it }
            }) { Text(stringResource(R.string.adv_save)) }
        },
        dismissButton = {
            Row {
                if (onDelete != null) TextButton(onClick = { confirmDelete = true }) {
                    Text(stringResource(R.string.contacts_remove))
                }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
            }
        })

    if (confirmDelete && onDelete != null) {
        AlertDialog(onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.contacts_remove_title, contact.name)) },
            text = { Text(stringResource(R.string.contacts_remove_explain)) },
            confirmButton = { TextButton(onClick = { confirmDelete = false; onDelete() }) {
                Text(stringResource(R.string.contacts_remove)) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) {
                Text(stringResource(R.string.common_cancel)) } })
    }
}

@Composable
private fun SelfNotifyEditor(
    onDismiss: () -> Unit,
    onSave: (String?, String?, String?, (Map<String, String>) -> Unit) -> Unit,
) {
    var tg by remember { mutableStateOf("") }
    var ntfy by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var errors by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.contacts_self_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.contacts_self_explain), style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(value = tg, onValueChange = { tg = it },
                    label = { Text(stringResource(R.string.contact_telegram_self)) },
                    isError = errors["telegram_chat_id"] != null,
                    supportingText = errors["telegram_chat_id"]?.let { { Text(it) } }, singleLine = true)
                OutlinedTextField(value = ntfy, onValueChange = { ntfy = it },
                    label = { Text(stringResource(R.string.contact_ntfy_self)) },
                    isError = errors["ntfy_topic"] != null,
                    supportingText = errors["ntfy_topic"]?.let { { Text(it) } }, singleLine = true)
                OutlinedTextField(value = email, onValueChange = { email = it },
                    label = { Text(stringResource(R.string.contact_email_self)) },
                    isError = errors["email"] != null,
                    supportingText = errors["email"]?.let { { Text(it) } }, singleLine = true)
                Text(stringResource(R.string.contacts_self_off), style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = {
            onSave(tg.trim().ifEmpty { null }, ntfy.trim().ifEmpty { null }, email.trim().ifEmpty { null }) { errors = it }
        }) { Text(stringResource(R.string.adv_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } })
}
