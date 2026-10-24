package com.pulseloop.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.pulseloop.context.ContextPermissions
import com.pulseloop.data.PulseLoopDatabase
import com.pulseloop.data.entity.DiaryEntryEntity
import com.pulseloop.diary.DiaryAutoDerive
import com.pulseloop.diary.DiaryChip
import com.pulseloop.diary.DiaryDeliveryMode
import com.pulseloop.diary.DiaryLogic
import com.pulseloop.diary.DiaryPrefs
import com.pulseloop.diary.DiaryReminders
import com.pulseloop.ui.theme.PulseColors
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val DAY_FMT = DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.ITALIAN)

/**
 * Saves every edit immediately (upsert by day); nothing is ever written as an implicit "no". The
 * change is applied to the row as stored, one edit at a time, so two quick taps on a one-tap chip
 * cycle sì → no instead of both reading the same not-yet-refreshed state.
 */
private class DiaryEditor(private val context: android.content.Context, private val scope: kotlinx.coroutines.CoroutineScope) {
    private val dao = PulseLoopDatabase.getInstance(context).diaryEntryDao()
    fun save(date: LocalDate, change: (DiaryEntryEntity) -> DiaryEntryEntity) {
        scope.launch {
            writes.withLock {
                val now = System.currentTimeMillis()
                val base = dao.byDate(date.toString()) ?: DiaryLogic.empty(date, now)
                dao.upsert(change(base).copy(updatedAt = now))
            }
            com.pulseloop.remote.RingHubSyncWorker.enqueue(context)
        }
    }

    companion object {
        private val writes = kotlinx.coroutines.sync.Mutex()
    }
}

@Composable
private fun OptionalTimeRow(label: String, value: String?, onChange: (String?) -> Unit) {
    val context = LocalContext.current
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        OutlinedButton(onClick = {
            val parts = value?.split(':')?.mapNotNull { it.toIntOrNull() }
            android.app.TimePickerDialog(context, { _, h, m -> onChange("%02d:%02d".format(h, m)) },
                parts?.getOrNull(0) ?: 20, parts?.getOrNull(1) ?: 0, true).show()
        }) { Text(value ?: "--:--") }
        if (value != null) TextButton(onClick = { onChange(null) }) { Text("×") }
    }
}

@Composable
private fun QuickFields(entry: DiaryEntryEntity?, supplements: List<String>, onChange: ((DiaryEntryEntity) -> DiaryEntryEntity) -> Unit) {
    val now = System.currentTimeMillis()
    DiaryChip.entries.forEach { chip ->
        TriAnswerRow(chip.label, DiaryLogic.chipValue(entry, chip)) { v -> onChange { DiaryLogic.setChip(it, chip, v, now) } }
    }
    supplements.forEach { name ->
        TriAnswerRow(DiaryLogic.supplementLabel(name), DiaryLogic.supplementValue(entry, name)) { v ->
            onChange { DiaryLogic.setSupplement(it, name, v, now) }
        }
    }
    ScaleRow("Umore", entry?.mood, { v -> onChange { it.copy(mood = v) } })
}

/**
 * A one-tap answer chip: tap = sì, tap again = no (then back to sì), long-press = non so. The
 * state is spelled out in the label, not only in the colour.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun OneTapChip(label: String, value: Boolean?, onTap: () -> Unit, onClear: () -> Unit) {
    val haptics = LocalHapticFeedback.current
    val colors = MaterialTheme.colorScheme
    val (container, content) = when (value) {
        true -> colors.primary to colors.onPrimary
        false -> colors.surfaceVariant to colors.onSurfaceVariant
        null -> colors.surface to colors.onSurface
    }
    val text = when (value) {
        true -> "✓ $label: sì"
        false -> "✗ $label: no"
        null -> label
    }
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = container,
        contentColor = content,
        border = if (value == null) BorderStroke(1.dp, colors.outline) else null,
        modifier = Modifier
            .semantics { stateDescription = when (value) { true -> "sì"; false -> "no"; null -> "non indicato" } }
            .combinedClickable(
                onClick = onTap,
                onLongClickLabel = "Cancella",
                onLongClick = if (value != null) {
                    { haptics.performHapticFeedback(HapticFeedbackType.LongPress); onClear() }
                } else null,
            ),
    ) {
        Text(text, Modifier.padding(horizontal = 12.dp, vertical = 8.dp), style = MaterialTheme.typography.labelLarge)
    }
}

/** Today screen: "Diario di oggi", one tap per answer, nothing mandatory, saved immediately. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TodayDiaryCard(onOpenDiary: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val db = remember { PulseLoopDatabase.getInstance(context) }
    val editor = remember { DiaryEditor(context, scope) }
    val settings by remember { DiaryPrefs.get(context) }.settings.collectAsState()
    // Same day key as the diary tab: before 04:00 it is still yesterday's evening.
    val date = remember { DiaryLogic.diaryDateFor(System.currentTimeMillis()) }
    val entry by db.diaryEntryDao().byDateFlow(date.toString()).collectAsState(initial = null)
    val update: ((DiaryEntryEntity) -> DiaryEntryEntity) -> Unit = { change -> editor.save(date, change) }
    val compiled = DiaryLogic.anyAnswered(entry)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Diario di oggi", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    if (compiled) "✓ Compilato" else "Facoltativo",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (compiled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                "Tocca = sì · di nuovo = no · tieni premuto = cancella",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                DiaryChip.entries.forEach { chip ->
                    OneTapChip(chip.label, DiaryLogic.chipValue(entry, chip),
                        onTap = { update { DiaryLogic.tapChip(it, chip, System.currentTimeMillis()) } },
                        onClear = { update { DiaryLogic.setChip(it, chip, null, System.currentTimeMillis()) } })
                }
                settings.quickSupplements.forEach { name ->
                    OneTapChip(DiaryLogic.supplementLabel(name), DiaryLogic.supplementValue(entry, name),
                        onTap = { update { DiaryLogic.tapSupplement(it, name, System.currentTimeMillis()) } },
                        onClear = { update { DiaryLogic.setSupplement(it, name, null, System.currentTimeMillis()) } })
                }
            }
            ScaleRow("Umore", entry?.mood, { v -> update { it.copy(mood = v) } })
            TextButton(onClick = onOpenDiary, modifier = Modifier.align(Alignment.End)) { Text("Apri diario") }
        }
    }
}

/** The full diary: today's (or any day's) entry, reminder settings, history. */
@Composable
fun DiaryScreen(onBack: (() -> Unit)?, initialDate: String? = null) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val db = remember { PulseLoopDatabase.getInstance(context) }
    val editor = remember { DiaryEditor(context, scope) }
    var date by remember { mutableStateOf(initialDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: DiaryLogic.diaryDateFor(System.currentTimeMillis())) }
    val entry by db.diaryEntryDao().byDateFlow(date.toString()).collectAsState(initial = null)
    val history by db.diaryEntryDao().recentFlow(30).collectAsState(initial = emptyList())
    val prefs = remember { DiaryPrefs.get(context) }
    val settings by prefs.settings.collectAsState()
    var showDetails by remember { mutableStateOf(false) }
    val update: ((DiaryEntryEntity) -> DiaryEntryEntity) -> Unit = { change -> editor.save(date, change) }

    val derived by produceState<String?>(initialValue = null, date) {
        val zone = ZoneId.systemDefault()
        val from = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val to = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val transitions = db.activityTransitionDao().between(from - 6 * 3_600_000L, to)
        val workouts = db.adaptiveReadDao().workoutsSince(from).filter { it.startedAt < to }
            .map { it.startedAt to (it.endedAt ?: it.startedAt) }
        val sport = DiaryAutoDerive.sportMinutes(transitions, workouts, from, to)
        val night = db.sleepSessionDao().inRangeReal(to, to + 24 * 3_600_000L).minByOrNull { it.startAt }
        val usage = db.phoneUsageHourlyDao().between(from, to + 24 * 3_600_000L)
        val lastUse = night?.let { DiaryAutoDerive.lastUseBefore(usage, it.startAt) }
        value = buildString {
            append("Sport rilevato: $sport min")
            if (lastUse != null && night != null) {
                val t = java.time.Instant.ofEpochMilli(lastUse).atZone(zone).toLocalTime()
                val s = java.time.Instant.ofEpochMilli(night.startAt).atZone(zone).toLocalTime()
                append(" · ultimo uso del telefono ${"%02d:%02d".format(t.hour, t.minute)}, addormentato ${"%02d:%02d".format(s.hour, s.minute)}")
            }
        }
    }

    val content: @Composable ColumnScope.() -> Unit = {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { date = date.minusDays(1) }) { Text("‹") }
            Text(date.format(DAY_FMT).replaceFirstChar { it.uppercase() }, Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
            if (date < LocalDate.now()) TextButton(onClick = { date = date.plusDays(1) }) { Text("›") }
        }
        SectionCard("Stasera", "Facoltativo. Tocca solo ciò che vale: quello che lasci vuoto resta \"non so\".") {
            QuickFields(entry, settings.quickSupplements, update)
            derived?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            TextButton(onClick = { showDetails = !showDetails }) { Text(if (showDetails) "Nascondi dettagli" else "Altri dettagli (facoltativi)") }
            if (showDetails) DetailFields(entry, update)
        }
        SectionCard("Mattina") {
            ScaleRow("Umore al risveglio", entry?.morningMood, { v -> update { it.copy(morningMood = v) } })
            ScaleRow("Come ti senti", entry?.morningFeeling, { v -> update { it.copy(morningFeeling = v) } })
        }
        ReminderSettings(settings, prefs)
        QuickSupplementsSettings(settings, prefs)
        SectionCard("Storico") {
            if (history.isEmpty()) Text("Nessuna voce.", style = MaterialTheme.typography.bodySmall)
            history.forEach { e ->
                Column(Modifier.fillMaxWidth().clickable { date = LocalDate.parse(e.date) }.padding(vertical = 6.dp)) {
                    Text(LocalDate.parse(e.date).format(DAY_FMT), style = MaterialTheme.typography.bodyMedium)
                    Text(summary(e), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                HorizontalDivider()
            }
        }
    }
    if (onBack != null) {
        SettingsSubScreen(title = "Diario", onBack = onBack, content = content)
    } else {
        // The "Diario" tab: the route wrapper already pads for the shared header and tab bar.
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            content()
        }
    }
}

/** "Integratori rapidi": the supplement chips offered on Today, in the diary and the quick screen. */
@Composable
private fun QuickSupplementsSettings(settings: com.pulseloop.diary.DiarySettings, prefs: DiaryPrefs) {
    var draft by remember { mutableStateOf("") }
    val candidate = DiaryLogic.normalizeSupplement(draft)
    val canAdd = candidate != null && candidate !in settings.quickSupplements
    SectionCard("Integratori rapidi", "Pulsanti a un tocco nella scheda Oggi e nel diario. Toglierne uno non cancella lo storico.") {
        if (settings.quickSupplements.isEmpty()) Text("Nessuno.", style = MaterialTheme.typography.bodySmall)
        settings.quickSupplements.forEach { name ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(DiaryLogic.supplementLabel(name), Modifier.weight(1f))
                IconButton(onClick = { prefs.update { it.copy(quickSupplements = DiaryLogic.removeQuickSupplement(it.quickSupplements, name)) } }) {
                    Icon(Icons.Filled.Close, contentDescription = "Rimuovi ${DiaryLogic.supplementLabel(name)}")
                }
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = draft, onValueChange = { draft = it },
                label = { Text("Nuovo integratore") }, singleLine = true, modifier = Modifier.weight(1f),
            )
            Button(enabled = canAdd, onClick = {
                prefs.update { it.copy(quickSupplements = DiaryLogic.addQuickSupplement(it.quickSupplements, draft)) }
                draft = ""
            }) { Text("Aggiungi") }
        }
    }
}

private fun summary(e: DiaryEntryEntity): String = listOfNotNull(
    e.lateCaffeine?.let { if (it) "caffè dopo le 15" else "niente caffè tardi" },
    e.alcohol?.let { if (it) "alcol" + (e.alcoholUnits?.let { u -> " ($u u)" } ?: "") else "niente alcol" },
    e.lateMeal?.let { if (it) "pasto tardi" else null },
    e.sport?.let { if (it) "sport" else null },
    e.supplements?.let { s -> if (s.isEmpty()) "nessun integratore" else s.joinToString(", ") },
    e.mood?.let { "umore $it/5" },
    e.morningFeeling?.let { "mattina $it/5" },
).joinToString(" · ").ifEmpty { "—" }

@Composable
private fun DetailFields(entry: DiaryEntryEntity?, update: ((DiaryEntryEntity) -> DiaryEntryEntity) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        TriAnswerRow("Caffè (in generale)", entry?.caffeine) { v -> update { it.copy(caffeine = v) } }
        OptionalTimeRow("Ultimo caffè", entry?.caffeineLastTime) { v -> update { it.copy(caffeineLastTime = v) } }
        var units by remember(entry?.id, entry?.alcoholUnits) { mutableStateOf(entry?.alcoholUnits?.toString().orEmpty()) }
        OutlinedTextField(
            value = units,
            onValueChange = { raw ->
                units = raw
                val v = raw.replace(',', '.').toDoubleOrNull()
                if (raw.isBlank() || (v != null && v in 0.0..50.0)) update { it.copy(alcoholUnits = v, alcohol = if (v != null && v > 0) true else it.alcohol) }
            },
            label = { Text("Unità di alcol") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.fillMaxWidth(),
        )
        OptionalTimeRow("Ultimo alcol", entry?.alcoholLastTime) { v -> update { it.copy(alcoholLastTime = v) } }
        OptionalTimeRow("Pasto tardi alle", entry?.lateMealTime) { v -> update { it.copy(lateMealTime = v, lateMeal = if (v != null) true else it.lateMeal) } }
        TriAnswerRow("Sport", entry?.sport) { v -> update { it.copy(sport = v) } }
        if (entry?.sport == true) {
            OptionalTimeRow("Inizio", entry.sportStart) { v -> update { it.copy(sportStart = v) } }
            var duration by remember(entry.id, entry.sportDurationMin) { mutableStateOf(entry.sportDurationMin?.toString().orEmpty()) }
            OutlinedTextField(
                value = duration,
                onValueChange = { raw ->
                    duration = raw
                    val v = raw.toIntOrNull()
                    if (raw.isBlank() || (v != null && v in 0..1440)) update { it.copy(sportDurationMin = v) }
                },
                label = { Text("Durata (min)") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
            ScaleRow("Intensità", entry.sportIntensity, { v -> update { it.copy(sportIntensity = v) } }, range = 1..3)
        }
        TriAnswerRow("Schermi a letto", entry?.screensInBed) { v -> update { it.copy(screensInBed = v) } }
        ScaleRow("Stress percepito", entry?.perceivedStress, { v -> update { it.copy(perceivedStress = v) } })
        TriAnswerRow("Malattia / indisposizione", entry?.illness) { v -> update { it.copy(illness = v) } }
        var notes by remember(entry?.id) { mutableStateOf(entry?.notes.orEmpty()) }
        OutlinedTextField(
            value = notes,
            onValueChange = { raw -> notes = raw; update { it.copy(notes = raw.ifBlank { null }) } },
            label = { Text("Note") }, modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun ReminderSettings(settings: com.pulseloop.diary.DiarySettings, prefs: DiaryPrefs) {
    val context = LocalContext.current
    val canFsi = ContextPermissions.canUseFullScreenIntent(context)
    val mode = DiaryLogic.effectiveMode(settings.chosenMode, canFsi)
    fun changed(transform: (com.pulseloop.diary.DiarySettings) -> com.pulseloop.diary.DiarySettings) {
        prefs.update(transform)
        DiaryReminders.reschedule(context)
    }
    SectionCard("Promemoria", "Una sola notifica al giorno; se la chiudi non torna.") {
        ToggleRow("Promemoria serale", settings.enabled, { on -> changed { it.copy(enabled = on) } })
        if (settings.enabled) {
            TimeButton("Ora", settings.eveningMinute) { m -> changed { it.copy(eveningMinute = m) } }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                DiaryDeliveryMode.entries.forEach { m ->
                    FilterChip(selected = mode == m, onClick = { changed { it.copy(modeCode = m.code) } }, label = { Text(m.label) })
                }
            }
            Text(
                if (mode == DiaryDeliveryMode.AUTO_OPEN) "Col telefono bloccato il diario si apre da solo; mentre lo usi compare come notifica in alto."
                else "Notifica normale con i tre pulsanti: si compila senza aprire l'app.",
                style = MaterialTheme.typography.bodySmall,
            )
            if (settings.chosenMode == DiaryDeliveryMode.AUTO_OPEN && !canFsi || settings.chosenMode == null && !canFsi) {
                OutlinedButton(onClick = { context.startActivity(ContextPermissions.fullScreenIntentSettings(context)) }) {
                    Text("Consenti notifiche a schermo intero")
                }
            }
            if (!ContextPermissions.canScheduleExactAlarms(context)) {
                Text("Orario non preciso: Android può ritardare il promemoria di qualche minuto.", style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = { runCatching { context.startActivity(ContextPermissions.exactAlarmSettings(context)) } }) {
                    Text("Consenti sveglie esatte")
                }
            }
        }
        ToggleRow("Controllo del mattino (2 tocchi)", settings.morningEnabled, { on -> changed { it.copy(morningEnabled = on) } })
        if (settings.morningEnabled) TimeButton("Ora", settings.morningMinute) { m -> changed { it.copy(morningMinute = m) } }
    }
}

/** The one-screen diary of the evening reminder / the morning 2-tap check (DiaryQuickActivity). */
@Composable
fun DiaryQuickScreen(date: LocalDate, morning: Boolean, onDone: () -> Unit, onOpenFullDiary: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val db = remember { PulseLoopDatabase.getInstance(context) }
    val editor = remember { DiaryEditor(context, scope) }
    val entry by db.diaryEntryDao().byDateFlow(date.toString()).collectAsState(initial = null)
    val update: ((DiaryEntryEntity) -> DiaryEntryEntity) -> Unit = { change -> editor.save(date, change) }
    val settings by remember { DiaryPrefs.get(context) }.settings.collectAsState()
    LaunchedEffect(entry?.morningMood, entry?.morningFeeling) {
        if (morning && DiaryLogic.morningAnswered(entry)) onDone()
    }
    Column(
        Modifier.fillMaxSize().background(PulseColors.background).safeDrawingPadding().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(if (morning) "Buongiorno" else "Diario di stasera", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        Text(date.format(DAY_FMT), style = MaterialTheme.typography.bodyMedium)
        if (morning) {
            ScaleRow("Umore", entry?.morningMood, { v -> update { it.copy(morningMood = v) } })
            ScaleRow("Come ti senti", entry?.morningFeeling, { v -> update { it.copy(morningFeeling = v) } })
        } else {
            Text("Tocca solo ciò che vale. Sport e schermi li rileva l'app.", style = MaterialTheme.typography.bodySmall)
            QuickFields(entry, settings.quickSupplements, update)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onDone) { Text("Fatto") }
            if (!morning) OutlinedButton(onClick = onOpenFullDiary) { Text("Diario completo") }
        }
    }
}
