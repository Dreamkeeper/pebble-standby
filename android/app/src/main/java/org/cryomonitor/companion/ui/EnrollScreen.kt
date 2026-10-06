package org.cryomonitor.companion.ui

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.cryomonitor.companion.MonitorService
import org.cryomonitor.companion.R
import org.cryomonitor.companion.ServerClient
import org.cryomonitor.companion.SettingsStore

/**
 * Enrol by code (spec companion-enrollment-and-contacts: the primary path).
 * Used as a settings screen and as onboarding step 3. Failure modes stay
 * distinct: unreachable, rejected code, malformed, rate limit, server error.
 */
@Composable
fun EnrollForm(settings: SettingsStore, onEnrolled: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf(settings.serverUrl) }
    var code by remember { mutableStateOf("") }
    var urlError by remember { mutableStateOf<String?>(null) }
    var codeError by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.enrol_intro), style = MaterialTheme.typography.bodyMedium,
             color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(value = url, onValueChange = { url = it; urlError = null },
            label = { Text(stringResource(R.string.adv_server_url)) },
            placeholder = { Text("https://standby.example.org") },
            isError = urlError != null, supportingText = urlError?.let { { Text(it) } },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth(), singleLine = true)
        OutlinedTextField(value = code, onValueChange = { code = it.uppercase(); codeError = null },
            label = { Text(stringResource(R.string.enrol_code)) },
            placeholder = { Text("XXXX-XXXX") },
            isError = codeError != null, supportingText = codeError?.let { { Text(it) } },
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
            modifier = Modifier.fillMaxWidth(), singleLine = true)
        Button(enabled = !busy, onClick = {
            val u = url.trim().trimEnd('/')
            val c = code.trim()
            if (!u.startsWith("http")) { urlError = context.getString(R.string.enrol_url_error); return@Button }
            if (c.replace("-", "").length != 8) { codeError = context.getString(R.string.enrol_code_error); return@Button }
            busy = true; status = context.getString(R.string.enrol_contacting)
            scope.launch {
                val r = withContext(Dispatchers.IO) { ServerClient(settings).enroll(u, c) }
                busy = false
                when (r) {
                    is ServerClient.EnrollResult.Success -> {
                        settings.serverUrl = u
                        settings.apiToken = r.token
                        context.startService(Intent(context, MonitorService::class.java)
                            .setAction(MonitorService.ACTION_HEARTBEAT_NOW))
                        status = context.getString(R.string.enrol_done, r.wearerId)
                        onEnrolled()
                    }
                    is ServerClient.EnrollResult.CodeRejected -> status = context.getString(R.string.enrol_rejected)
                    is ServerClient.EnrollResult.Malformed -> codeError = context.getString(R.string.enrol_code_error)
                    is ServerClient.EnrollResult.RateLimited -> status = context.getString(R.string.enrol_rate_limited)
                    is ServerClient.EnrollResult.Unreachable -> status = context.getString(R.string.enrol_unreachable, u, r.why)
                    is ServerClient.EnrollResult.ServerError -> status = context.getString(R.string.enrol_server_error, r.code)
                }
            }
        }) { Text(stringResource(R.string.enrol_button)) }
        status?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
    }
}

@Composable
fun EnrollScreen(settings: SettingsStore, onBack: () -> Unit) {
    SubScreen(stringResource(R.string.enrol_title), onBack) {
        EnrollForm(settings, onEnrolled = onBack)
    }
}
