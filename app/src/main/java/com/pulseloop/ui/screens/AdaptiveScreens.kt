package com.pulseloop.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.pulseloop.adaptive.AdaptiveController
import com.pulseloop.adaptive.AdaptivePrefs
import com.pulseloop.adaptive.AdaptiveState
import com.pulseloop.adaptive.AdaptiveStateMachine
import com.pulseloop.context.ContextPermissions
import com.pulseloop.context.MonitorService
import com.pulseloop.data.PulseLoopDatabase
import com.pulseloop.data.entity.ActivityLabelEntity
import com.pulseloop.labels.LabelCategory
import com.pulseloop.util.TimeUtil
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date
import kotlin.math.roundToInt

private val STATE_IT = mapOf(
    AdaptiveState.BASELINE to "Normale (registrazione automatica ogni 5 min)",
    AdaptiveState.WATCH to "Verifica in corso",
    AdaptiveState.ESCALATED to "Monitoraggio intenso",
    AdaptiveState.COOLDOWN to "Pausa dopo un episodio",
)

private val REASON_IT = mapOf(
    "confirmed" to "confermato", "unconfirmed" to "non confermato", "recovered" to "rientrato",
    "cap" to "limite 20 min", "rest_lost" to "movimento", "battery_low" to "batteria anello bassa",
    "asleep" to "sonno", "budget_exhausted" to "budget esaurito", "interrupted" to "interrotto",
    "disabled" to "disattivato", "cooldown_done" to "fine pausa", "link_unavailable" to "anello non raggiungibile",
    "link_lost" to "connessione persa", "fgs_denied" to "avvio in background negato", "error" to "errore",
)

private fun reasonText(reason: String): String {
    if (reason.startsWith(AdaptiveStateMachine.Reason.SUPPRESSED_PREFIX)) {
        return "ignorato: " + (REASON_IT[reason.removePrefix(AdaptiveStateMachine.Reason.SUPPRESSED_PREFIX)] ?: reason)
    }
    return reason.split('+').joinToString(" + ") {
        when (it) { "hr_high" -> "FC alta"; "stress_high" -> "stress alto"; "hrv_drop" -> "calo HRV"; else -> REASON_IT[it] ?: it }
    }
}

/** Settings → Modalità adattiva. */
@Composable
fun AdaptiveSettingsScreen(onBack: () -> Unit, onOpenLabels: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { AdaptivePrefs.get(context) }
    val settings by prefs.settings.collectAsState()
    val memory by prefs.memory.collectAsState()
    val db = remember { PulseLoopDatabase.getInstance(context) }
    val transitions by db.adaptiveTransitionDao().recentFlow(30).collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(30_000); now = System.currentTimeMillis() } }
    val usedToday = AdaptiveStateMachine.budgetUsedMinutes(memory, now, { TimeUtil.startOfDayLocal(it) }, settings.config())
    val fmt = remember { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT) }

    SettingsSubScreen(title = "Modalità adattiva", onBack = onBack) {
        SectionCard(
            "Modalità adattiva",
            "L'anello continua a registrare da solo ogni 5 minuti. Se a riposo la frequenza o lo stress " +
                "salgono, l'app misura più spesso per qualche minuto (FC ogni minuto, HRV e stress ogni 3), " +
                "entro un budget giornaliero. Non è uno strumento medico.",
        ) {
            ToggleRow("Modalità adattiva", settings.enabled, onChange = { on ->
                prefs.updateSettings { it.copy(enabled = on) }
                scope.launch {
                    if (!on) AdaptiveController.disable(context)
                    MonitorService.refresh(context)
                }
            })
            Text("Stato: " + (STATE_IT[memory.state] ?: memory.state.name), style = MaterialTheme.typography.bodyMedium)
            Text("Oggi: $usedToday min di monitoraggio intenso su ${settings.dailyBudgetMinutes}", style = MaterialTheme.typography.bodyMedium)
            Text("Budget giornaliero: ${settings.dailyBudgetMinutes} min")
            Slider(
                value = settings.dailyBudgetMinutes.toFloat(),
                onValueChange = { v -> prefs.updateSettings { it.copy(dailyBudgetMinutes = (v / 15f).roundToInt() * 15) } },
                valueRange = AdaptivePrefs.MIN_BUDGET.toFloat()..AdaptivePrefs.MAX_BUDGET.toFloat(),
                steps = (AdaptivePrefs.MAX_BUDGET - AdaptivePrefs.MIN_BUDGET) / 15 - 1,
            )
            if (settings.enabled && !ContextPermissions.ignoresBatteryOptimizations(context)) {
                Text(
                    "Per funzionare con lo schermo spento l'app deve poter girare senza restrizioni: " +
                        "Impostazioni → App → PulseLoop → Batteria → \"Gestisci manualmente\" (avvio automatico, " +
                        "avvio secondario ed esecuzione in background attivi).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                OutlinedButton(onClick = {
                    runCatching { context.startActivity(ContextPermissions.batteryOptimizationIntent(context)) }
                        .onFailure { context.startActivity(ContextPermissions.appDetailsIntent(context)) }
                }) { Text("Togli le restrizioni batteria") }
            }
        }

        SectionCard("Cosa stai facendo?", "Quando inizia un episodio, una notifica propone l'etichetta più probabile: un tocco per confermare, oppure ignorala.") {
            ToggleRow("Chiedi un'etichetta", settings.labelPromptsEnabled, onChange = { on -> prefs.updateSettings { it.copy(labelPromptsEnabled = on) } })
            Text("Al massimo una ogni ${settings.labelMinIntervalMinutes} min")
            Slider(
                value = settings.labelMinIntervalMinutes.toFloat(),
                onValueChange = { v -> prefs.updateSettings { it.copy(labelMinIntervalMinutes = ((v / 15f).roundToInt() * 15).coerceAtLeast(15)) } },
                valueRange = 15f..180f, steps = 10,
            )
            Text("Mai tra le ${formatMinuteOfDay(settings.labelQuietStartMinute)} e le ${formatMinuteOfDay(settings.labelQuietEndMinute)}")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TimeButton("Dalle", settings.labelQuietStartMinute) { m -> prefs.updateSettings { it.copy(labelQuietStartMinute = m) } }
                TimeButton("Alle", settings.labelQuietEndMinute) { m -> prefs.updateSettings { it.copy(labelQuietEndMinute = m) } }
            }
            OutlinedButton(onClick = onOpenLabels) { Text("Etichette registrate") }
        }

        SectionCard("Ultimi cambi di stato") {
            if (transitions.isEmpty()) Text("Nessuno finora.", style = MaterialTheme.typography.bodySmall)
            transitions.forEach { t ->
                Text(
                    "${fmt.format(Date(t.timestamp))} · " +
                        (if (t.fromState == t.toState) "" else "${t.fromState} → ${t.toState} · ") + reasonText(t.reason),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

/** A button showing a time of day that opens the platform time picker. */
@Composable
internal fun TimeButton(label: String, minuteOfDay: Int, onPicked: (Int) -> Unit) {
    val context = LocalContext.current
    OutlinedButton(onClick = {
        android.app.TimePickerDialog(context, { _, h, m -> onPicked(h * 60 + m) }, minuteOfDay / 60, minuteOfDay % 60, true).show()
    }) { Text("$label ${formatMinuteOfDay(minuteOfDay)}") }
}

/** The "Cosa stai facendo?" answers, editable afterwards; new ones can be added by hand. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ActivityLabelsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val dao = remember { PulseLoopDatabase.getInstance(context).activityLabelDao() }
    val since = remember { System.currentTimeMillis() - 30L * 24 * 3_600_000L }
    val labels by dao.recentFlow(since).collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf<ActivityLabelEntity?>(null) }
    val fmt = remember { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT) }

    SettingsSubScreen(title = "Etichette attività", onBack = onBack) {
        SectionCard("Aggiungi ora", "Etichetta questo momento senza aspettare la notifica.") {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                LabelCategory.entries.forEach { c ->
                    FilterChip(selected = false, onClick = {
                        val now = System.currentTimeMillis()
                        val row = ActivityLabelEntity(timestamp = now, category = c.code, source = "manual", createdAt = now, updatedAt = now)
                        if (c == LabelCategory.OTHER) editing = row
                        else scope.launch { dao.upsert(row); com.pulseloop.remote.RingHubSyncWorker.enqueue(context) }
                    }, label = { Text(c.label) })
                }
            }
        }
        SectionCard("Ultimi 30 giorni") {
            if (labels.isEmpty()) Text("Nessuna etichetta.", style = MaterialTheme.typography.bodySmall)
            labels.forEach { l ->
                Column(Modifier.fillMaxWidth().clickable { editing = l }.padding(vertical = 6.dp)) {
                    val name = if (l.category == LabelCategory.OTHER.code) l.text ?: "Altro" else LabelCategory.of(l.category)?.label ?: l.category
                    Text(name, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        fmt.format(Date(l.timestamp)) + (if (l.episodeId != null) " · durante un episodio" else "") +
                            (l.suggested?.let { s -> " · suggerito: " + (LabelCategory.of(s)?.label ?: s) } ?: ""),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                HorizontalDivider()
            }
        }
    }

    editing?.let { current ->
        var category by remember(current.id) { mutableStateOf(LabelCategory.of(current.category) ?: LabelCategory.OTHER) }
        var text by remember(current.id) { mutableStateOf(current.text.orEmpty()) }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text("Etichetta") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        LabelCategory.entries.forEach { c ->
                            FilterChip(selected = category == c, onClick = { category = c }, label = { Text(c.label) })
                        }
                    }
                    if (category == LabelCategory.OTHER) {
                        OutlinedTextField(value = text, onValueChange = { text = it }, label = { Text("Descrizione") }, singleLine = true)
                    }
                }
            },
            confirmButton = {
                TextButton(enabled = category != LabelCategory.OTHER || text.isNotBlank(), onClick = {
                    val now = System.currentTimeMillis()
                    val row = current.copy(category = category.code, text = text.trim().takeIf { category == LabelCategory.OTHER && it.isNotEmpty() }, updatedAt = now)
                    scope.launch { dao.upsert(row); com.pulseloop.remote.RingHubSyncWorker.enqueue(context) }
                    editing = null
                }) { Text("Salva") }
            },
            dismissButton = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (labels.any { it.id == current.id }) {
                        TextButton(onClick = {
                            val now = System.currentTimeMillis()
                            scope.launch { dao.upsert(current.copy(deletedAt = now, updatedAt = now)); com.pulseloop.remote.RingHubSyncWorker.enqueue(context) }
                            editing = null
                        }) { Text("Elimina") }
                    }
                    TextButton(onClick = { editing = null }) { Text("Annulla") }
                }
            },
        )
    }
}
