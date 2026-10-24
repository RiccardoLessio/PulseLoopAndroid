package com.pulseloop.data.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.util.UUID

/*
 * v0.2 context tables (DB v30). None of them is part of the data-archive export or of
 * PulseLoopDatabase.ALL_TABLES (an archive restore wipes ALL_TABLES and would lose these without
 * restoring them); "delete all data" clears them through CONTEXT_TABLES. All are pushed to
 * ring-hub by RingHubSync.
 */

/** One adaptive-sampling state change, or a suppressed trigger (fromState == toState). */
@Entity(
    tableName = "adaptive_transitions",
    indices = [Index("timestamp"), Index("createdAt")],
)
data class AdaptiveTransitionEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val timestamp: Long,
    val fromState: String,
    val toState: String,
    val reason: String,
    val episodeId: String? = null,
    /** The inputs the decision saw (hr, medians, stress, hrv, steps, battery, budget), JSON. */
    val valuesJson: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
)

/** An answer to "Cosa stai facendo?" (notification or in-app). Soft-deleted so the server hears it. */
@Entity(
    tableName = "activity_labels",
    indices = [Index("timestamp"), Index("updatedAt")],
)
data class ActivityLabelEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    /** When the labelled moment happened (the episode start), not when the user answered. */
    val timestamp: Long,
    /** meeting | traffic | argument | work | sport | nothing | other */
    val category: String,
    /** Free text for "Altro…". */
    val text: String? = null,
    /** The category the notification pre-suggested from context, if any. */
    val suggested: String? = null,
    /** notification | manual */
    val source: String = "notification",
    val episodeId: String? = null,
    val transitionId: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val deletedAt: Long? = null,
)

/**
 * One evening diary (+ optional morning check) per local day. Every answer is nullable: a field
 * the user did not touch is unknown, never "no".
 */
@Entity(
    tableName = "diary_entries",
    indices = [Index(value = ["date"], unique = true), Index("updatedAt")],
)
@TypeConverters(StringListConverter::class)
data class DiaryEntryEntity(
    @PrimaryKey val id: String,
    /** Local day, ISO `yyyy-MM-dd`. */
    val date: String,
    val caffeine: Boolean? = null,
    /** "Caffè dopo le 15?" chip. */
    val lateCaffeine: Boolean? = null,
    /** `HH:mm` local. */
    val caffeineLastTime: String? = null,
    val alcohol: Boolean? = null,
    val alcoholUnits: Double? = null,
    val alcoholLastTime: String? = null,
    val lateMeal: Boolean? = null,
    val lateMealTime: String? = null,
    val sport: Boolean? = null,
    val sportStart: String? = null,
    val sportDurationMin: Int? = null,
    /** 1–3 */
    val sportIntensity: Int? = null,
    val screensInBed: Boolean? = null,
    /** 1–5 */
    val mood: Int? = null,
    /** 1–5 */
    val perceivedStress: Int? = null,
    val illness: Boolean? = null,
    val notes: String? = null,
    /** Morning 2-tap check, 1–5 each. */
    val morningMood: Int? = null,
    val morningFeeling: Int? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    /**
     * v31: supplements taken that day (normalised names, e.g. "teanina"). Null = never touched
     * (unknown); a list = the ones taken, so an empty list means "none of them". Last column on
     * purpose: `ALTER TABLE ADD COLUMN` appends, and this keeps Room's CREATE identical to it.
     */
    val supplements: List<String>? = null,
) {
    companion object {
        fun idFor(date: String) = "diary:$date"
    }
}

/** `List<String>?` <-> JSON array text; null stays SQL NULL (unknown), never `[]`. */
class StringListConverter {
    private val serializer = ListSerializer(String.serializer())

    @TypeConverter
    fun fromList(value: List<String>?): String? = value?.let { Json.encodeToString(serializer, it) }

    @TypeConverter
    fun toList(value: String?): List<String>? =
        value?.let { runCatching { Json.decodeFromString(serializer, it) }.getOrNull() }
}

/** Screen-on minutes per hour from UsageStatsManager; id `usage:<hourStart>`. */
@Entity(tableName = "phone_usage_hourly", indices = [Index("updatedAt")])
data class PhoneUsageHourlyEntity(
    @PrimaryKey val id: String,
    val hourStart: Long,
    val screenOnMinutes: Double,
    val unlocks: Int = 0,
    /** Last moment the screen went off inside this hour (the "last use before sleep" source). */
    val lastScreenOffAt: Long? = null,
    val updatedAt: Long = System.currentTimeMillis(),
) {
    companion object {
        fun idFor(hourStart: Long) = "usage:$hourStart"
    }
}

/** A raw location fix from the automatic tracker. Leaves the phone only towards ring-hub. */
@Entity(tableName = "location_points", indices = [Index("timestamp"), Index("createdAt")])
data class LocationPointEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val timestamp: Long,
    val latitude: Double,
    val longitude: Double,
    val accuracy: Float? = null,
    val speed: Float? = null,
    /** fused | gps | network | passive */
    val provider: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
)

/** An Activity Recognition transition: activityType STILL/WALKING/RUNNING/ON_BICYCLE/IN_VEHICLE. */
@Entity(tableName = "activity_transitions", indices = [Index("timestamp"), Index("createdAt")])
data class ActivityTransitionEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val timestamp: Long,
    val activityType: String,
    /** ENTER | EXIT */
    val transition: String,
    val createdAt: Long = System.currentTimeMillis(),
)

/** A user zone (map pin + radius). category: home | work | gym | custom. Soft-deleted. */
@Entity(tableName = "zones", indices = [Index("updatedAt")])
data class ZoneEntity(
    @PrimaryKey val id: String = "zone-" + UUID.randomUUID().toString(),
    val name: String,
    val category: String,
    val latitude: Double,
    val longitude: Double,
    val radiusMeters: Double,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val deletedAt: Long? = null,
)
