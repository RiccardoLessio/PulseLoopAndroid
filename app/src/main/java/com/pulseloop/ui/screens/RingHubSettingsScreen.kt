package com.pulseloop.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.pulseloop.remote.RingHubClient
import com.pulseloop.remote.RingHubException
import com.pulseloop.remote.RingHubPrefsStore
import com.pulseloop.remote.RingHubSyncWorker
import com.pulseloop.remote.RingHubTokenStore
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/**
 * Settings → Server ring-hub: where the app pushes its data (self-hosted ring-hub, usually over
 * Tailscale), the token, the on/off switch, a connection test and the last pass's outcome.
 */
@Composable
fun RingHubSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val store = remember { RingHubPrefsStore.get(context) }
    val tokenStore = remember { RingHubTokenStore(context) }
    val prefs by store.prefs.collectAsState()
    val scope = rememberCoroutineScope()

    var url by remember { mutableStateOf(prefs.serverUrl) }
    var token by remember { mutableStateOf(tokenStore.token) }
    var testMessage by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }
    val urlProblem = RingHubClient.validate(url)

    /** Persist the typed URL and token; returns false when the URL can't be used. */
    fun save(): Boolean {
        if (urlProblem != null) return false
        val normalized = RingHubClient.normalize(url) ?: return false
        tokenStore.token = token
        store.update { it.copy(serverUrl = normalized) }
        url = normalized
        return true
    }

    SettingsSubScreen(title = "Server ring-hub", onBack = onBack) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Server", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    "I dati dell'anello vengono inviati al tuo server ring-hub dopo ogni sincronizzazione " +
                        "e ogni ora. Se il server non è raggiungibile restano sul telefono e partono dopo.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it; testMessage = null },
                    label = { Text("Indirizzo server") },
                    placeholder = { Text("http://pc-casa:8765") },
                    singleLine = true,
                    isError = url.isNotBlank() && urlProblem != null,
                    supportingText = {
                        if (url.isNotBlank() && urlProblem != null) Text(RingHubClient.message(urlProblem))
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it; testMessage = null },
                    label = { Text("Token") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Sincronizzazione automatica")
                    Switch(
                        checked = prefs.enabled,
                        enabled = prefs.enabled || (urlProblem == null && token.isNotBlank()),
                        onCheckedChange = { on ->
                            if (on) {
                                if (!save()) return@Switch
                                store.update { it.copy(enabled = true) }
                                RingHubSyncWorker.schedulePeriodic(context)
                                RingHubSyncWorker.enqueue(context, delaySeconds = 0)
                            } else {
                                store.update { it.copy(enabled = false) }
                                RingHubSyncWorker.cancelAll(context)
                            }
                        },
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        enabled = !testing && urlProblem == null && token.isNotBlank(),
                        onClick = {
                            if (!save()) return@OutlinedButton
                            testing = true
                            testMessage = "Verifica in corso..."
                            scope.launch {
                                testMessage = testConnection(url, token)
                                testing = false
                            }
                        },
                    ) { Text("Test connessione") }
                    Button(
                        enabled = prefs.enabled,
                        onClick = {
                            if (!save()) return@Button
                            RingHubSyncWorker.enqueue(context, delaySeconds = 0)
                            testMessage = "Sincronizzazione avviata"
                        },
                    ) { Text("Sincronizza ora") }
                }
                testMessage?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Ultima sincronizzazione", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                val fmt = remember { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT) }
                Text(
                    "Ultimo invio riuscito: " + (prefs.lastSuccessAt?.let { fmt.format(Date(it)) } ?: "mai"),
                    style = MaterialTheme.typography.bodySmall,
                )
                prefs.lastAttemptAt?.let {
                    Text("Ultimo tentativo: ${fmt.format(Date(it))}", style = MaterialTheme.typography.bodySmall)
                }
                prefs.lastStatus?.let {
                    Text(
                        "Esito: $it",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (prefs.lastOk == false) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(4.dp))
            }
        }
    }
}

/** `GET /health` (no auth) then `GET /sync/state` (auth), as one user-facing verdict. */
private suspend fun testConnection(url: String, token: String): String = try {
    val client = RingHubClient(url, token)
    val health = client.health()
    val state = client.syncState()
    "Connesso a ring-hub ${health.version}: token valido" +
        (state.ingestId?.let { ", ultimo import #$it" } ?: ", nessun dato ricevuto finora")
} catch (e: RingHubException) {
    when (e.kind) {
        RingHubException.Kind.UNREACHABLE -> "Server non raggiungibile (${e.message})"
        RingHubException.Kind.UNAUTHORIZED -> "Server raggiunto, ma il token è errato"
        RingHubException.Kind.SERVER ->
            if (e.httpCode == 503) "Server raggiunto, ma RINGHUB_TOKEN non è configurato sul server"
            else "Errore del server (HTTP ${e.httpCode})"
        RingHubException.Kind.REJECTED -> "Risposta inattesa (HTTP ${e.httpCode})"
    }
} catch (e: IllegalArgumentException) {
    "Indirizzo non valido"
} catch (e: kotlinx.serialization.SerializationException) {
    "Il server risponde, ma non sembra ring-hub"
}
