package com.pulseloop.diary

import com.pulseloop.data.entity.DiaryEntryEntity
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/** The three one-tap evening chips. Tapping one answers "sì"; untouched stays unknown. */
enum class DiaryChip(val code: String, val label: String) {
    LATE_CAFFEINE("late_caffeine", "Caffè dopo le 15"),
    ALCOHOL("alcohol", "Alcol"),
    LATE_MEAL("late_meal", "Pasto tardi");

    companion object {
        fun of(code: String?) = entries.firstOrNull { it.code == code }
    }
}

/** How the evening diary reaches the user. */
enum class DiaryDeliveryMode(val code: String, val label: String) {
    /** Full-screen intent: the short diary opens by itself on a locked/idle phone, heads-up otherwise. */
    AUTO_OPEN("auto_open", "Apri automaticamente"),
    /** A normal notification with the three chips as action buttons. */
    NOTIFICATION("notification", "Solo notifica");

    companion object {
        fun of(code: String?) = entries.firstOrNull { it.code == code }
    }
}

object DiaryLogic {
    private val HHMM = Regex("^([01]\\d|2[0-3]):[0-5]\\d$")

    fun idFor(date: LocalDate) = DiaryEntryEntity.idFor(date.toString())

    fun empty(date: LocalDate, now: Long) =
        DiaryEntryEntity(id = idFor(date), date = date.toString(), createdAt = now, updatedAt = now)

    /** A chip tap: that answer becomes "sì" (and implies the general one), nothing else changes. */
    fun applyChip(entry: DiaryEntryEntity, chip: DiaryChip, now: Long): DiaryEntryEntity = when (chip) {
        DiaryChip.LATE_CAFFEINE -> entry.copy(lateCaffeine = true, caffeine = true, updatedAt = now)
        DiaryChip.ALCOHOL -> entry.copy(alcohol = true, updatedAt = now)
        DiaryChip.LATE_MEAL -> entry.copy(lateMeal = true, updatedAt = now)
    }

    fun chipValue(entry: DiaryEntryEntity?, chip: DiaryChip): Boolean? = when (chip) {
        DiaryChip.LATE_CAFFEINE -> entry?.lateCaffeine
        DiaryChip.ALCOHOL -> entry?.alcohol
        DiaryChip.LATE_MEAL -> entry?.lateMeal
    }

    fun setChip(entry: DiaryEntryEntity, chip: DiaryChip, value: Boolean?, now: Long): DiaryEntryEntity = when (chip) {
        DiaryChip.LATE_CAFFEINE -> entry.copy(lateCaffeine = value, caffeine = if (value == true) true else entry.caffeine, updatedAt = now)
        DiaryChip.ALCOHOL -> entry.copy(alcohol = value, updatedAt = now)
        DiaryChip.LATE_MEAL -> entry.copy(lateMeal = value, updatedAt = now)
    }

    fun isValidTime(text: String?): Boolean = text == null || HHMM.matches(text)

    /** Normalises `9:5`, `0930`, `21.30` to `HH:mm`; null when blank or not a time. */
    fun parseTime(raw: String?): String? {
        val t = raw?.trim().orEmpty()
        if (t.isEmpty()) return null
        val digits = t.replace('.', ':')
        val (h, m) = when {
            digits.contains(':') -> digits.split(':').let { it[0] to it.getOrElse(1) { "0" } }
            digits.length in 3..4 -> digits.dropLast(2) to digits.takeLast(2)
            else -> digits to "0"
        }
        val hour = h.toIntOrNull() ?: return null
        val minute = m.toIntOrNull() ?: return null
        if (hour !in 0..23 || minute !in 0..59) return null
        return "%02d:%02d".format(hour, minute)
    }

    fun clampScale(v: Int?, range: IntRange): Int? = v?.takeIf { it in range }

    /** True once the evening part has at least one answer (so tonight's reminder is not needed). */
    fun eveningAnswered(e: DiaryEntryEntity?): Boolean = e != null && listOf(
        e.caffeine, e.lateCaffeine, e.alcohol, e.lateMeal, e.sport, e.screensInBed, e.mood, e.perceivedStress, e.illness,
    ).any { it != null }

    fun morningAnswered(e: DiaryEntryEntity?): Boolean = e?.morningMood != null && e.morningFeeling != null

    /** The next instant (strictly after [now]) the local clock reads [minuteOfDay]. */
    fun nextTrigger(now: Long, minuteOfDay: Int, zone: ZoneId = ZoneId.systemDefault()): Long {
        val nowZ = Instant.ofEpochMilli(now).atZone(zone)
        val time = LocalTime.of(minuteOfDay / 60, minuteOfDay % 60)
        var candidate: ZonedDateTime = nowZ.toLocalDate().atTime(time).atZone(zone)
        if (!candidate.toInstant().isAfter(nowZ.toInstant())) {
            candidate = nowZ.toLocalDate().plusDays(1).atTime(time).atZone(zone)
        }
        return candidate.toInstant().toEpochMilli()
    }

    /**
     * The diary day an evening reminder at [at] is about: after 04:00 it is that calendar day; in
     * the small hours (a reminder delivered late, or set after midnight) it is still yesterday.
     */
    fun diaryDateFor(at: Long, zone: ZoneId = ZoneId.systemDefault()): LocalDate {
        val z = Instant.ofEpochMilli(at).atZone(zone)
        return if (z.hour < 4) z.toLocalDate().minusDays(1) else z.toLocalDate()
    }

    /** Which mechanism to use for the evening reminder. */
    enum class Scheduler { EXACT_ALARM, INEXACT_WORK }

    fun scheduler(canScheduleExact: Boolean): Scheduler =
        if (canScheduleExact) Scheduler.EXACT_ALARM else Scheduler.INEXACT_WORK

    /**
     * The mode actually used: the user's choice, or AUTO_OPEN when they never chose and the
     * full-screen-intent permission is available; AUTO_OPEN without the permission degrades to a
     * plain notification (Android would show it as one anyway).
     */
    fun effectiveMode(chosen: DiaryDeliveryMode?, canUseFullScreenIntent: Boolean): DiaryDeliveryMode = when {
        chosen == null -> if (canUseFullScreenIntent) DiaryDeliveryMode.AUTO_OPEN else DiaryDeliveryMode.NOTIFICATION
        chosen == DiaryDeliveryMode.AUTO_OPEN && !canUseFullScreenIntent -> DiaryDeliveryMode.NOTIFICATION
        else -> chosen
    }
}
