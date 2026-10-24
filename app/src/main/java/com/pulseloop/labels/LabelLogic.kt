package com.pulseloop.labels

import com.pulseloop.context.ZoneMatcher
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId

/** The "Cosa stai facendo?" categories, in button order. */
enum class LabelCategory(val code: String, val label: String) {
    MEETING("meeting", "Riunione"),
    TRAFFIC("traffic", "Traffico"),
    ARGUMENT("argument", "Discussione"),
    WORK("work", "Lavoro"),
    SPORT("sport", "Sport"),
    NOTHING("nothing", "Niente"),
    OTHER("other", "Altro…");

    companion object {
        fun of(code: String?): LabelCategory? = entries.firstOrNull { it.code == code }
        /** The six one-tap buttons (OTHER is the free-text action). */
        val buttons = entries.filter { it != OTHER }
    }
}

/** What the phone knows about the moment, for the pre-suggested label. */
data class LabelContext(
    val at: Long,
    /** Activity Recognition's current activity (STILL, WALKING, RUNNING, ON_BICYCLE, IN_VEHICLE). */
    val activity: String? = null,
    /** Zone / auto-place category: home, work, gym, custom; null = unknown place. */
    val placeCategory: String? = null,
    /** Last location fix speed, m/s. */
    val speedMps: Float? = null,
)

object LabelSuggester {
    private val workDays = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)
    const val TRAVEL_SPEED_MPS = 4f

    /**
     * Best guess for one-tap confirmation, or null when context says nothing useful:
     * moving in a vehicle → Traffico; running/cycling or at the gym → Sport; at work on weekday
     * working hours → Riunione within 15 min after a full or half hour (meetings start there),
     * otherwise Lavoro.
     */
    fun suggest(ctx: LabelContext, zone: ZoneId = ZoneId.systemDefault()): LabelCategory? {
        val travelling = ctx.activity == "IN_VEHICLE" || (ctx.speedMps ?: 0f) >= TRAVEL_SPEED_MPS && ctx.activity != "ON_BICYCLE"
        if (travelling) return LabelCategory.TRAFFIC
        if (ctx.activity == "RUNNING" || ctx.activity == "ON_BICYCLE") return LabelCategory.SPORT
        if (ctx.placeCategory == ZoneMatcher.CATEGORY_GYM) return LabelCategory.SPORT
        if (ctx.placeCategory == ZoneMatcher.CATEGORY_WORK) {
            val local = Instant.ofEpochMilli(ctx.at).atZone(zone)
            val workHours = local.dayOfWeek in workDays && local.hour in 8..18
            if (!workHours) return LabelCategory.WORK
            return if (local.minute % 30 < 15) LabelCategory.MEETING else LabelCategory.WORK
        }
        return null
    }

    /** Human description of the current place/activity, for the notification text. */
    fun describe(ctx: LabelContext, placeName: String?): String? {
        val activity = when (ctx.activity) {
            "IN_VEHICLE" -> "in viaggio"
            "ON_BICYCLE" -> "in bici"
            "RUNNING" -> "di corsa"
            "WALKING" -> "a piedi"
            else -> null
        }
        return listOfNotNull(placeName, activity).joinToString(" · ").ifBlank { null }
    }
}

/** Prompt rate limiting: at most one per [minIntervalMinutes], none inside the quiet hours. */
data class LabelPromptPolicy(
    val enabled: Boolean = true,
    val minIntervalMinutes: Int = 30,
    /** Minutes of the day; quiet window may wrap midnight (23:00 → 07:00 by default). */
    val quietStartMinute: Int = 23 * 60,
    val quietEndMinute: Int = 7 * 60,
) {
    fun inQuietHours(at: Long, zone: ZoneId = ZoneId.systemDefault()): Boolean {
        val t = Instant.ofEpochMilli(at).atZone(zone)
        val minute = t.hour * 60 + t.minute
        if (quietStartMinute == quietEndMinute) return false
        return if (quietStartMinute < quietEndMinute) minute in quietStartMinute until quietEndMinute
        else minute >= quietStartMinute || minute < quietEndMinute
    }

    fun shouldPrompt(at: Long, lastPromptAt: Long?, alreadyLabelled: Boolean, zone: ZoneId = ZoneId.systemDefault()): Boolean {
        if (!enabled || alreadyLabelled) return false
        if (inQuietHours(at, zone)) return false
        if (lastPromptAt != null && at - lastPromptAt < minIntervalMinutes * 60_000L) return false
        return true
    }
}
