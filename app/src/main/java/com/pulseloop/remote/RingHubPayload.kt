package com.pulseloop.remote

import com.pulseloop.data.ActivityBucketDTO
import com.pulseloop.data.ActivityDailyDTO
import com.pulseloop.data.BatterySampleDTO
import com.pulseloop.data.DeviceDTO
import com.pulseloop.data.MeasurementDTO
import com.pulseloop.data.MeasurementDeletionDTO
import com.pulseloop.data.SleepSessionDTO
import com.pulseloop.data.SleepStageBlockDTO
import com.pulseloop.data.entity.ActivityBucketEntity
import com.pulseloop.data.entity.ActivityLabelEntity
import com.pulseloop.data.entity.ActivityTransitionEntity
import com.pulseloop.data.entity.AdaptiveTransitionEntity
import com.pulseloop.data.entity.DiaryEntryEntity
import com.pulseloop.data.entity.LocationPointEntity
import com.pulseloop.data.entity.PhoneUsageHourlyEntity
import com.pulseloop.data.entity.ZoneEntity
import com.pulseloop.data.entity.ActivityDailyEntity
import com.pulseloop.data.entity.BatterySampleEntity
import com.pulseloop.data.entity.DeviceEntity
import com.pulseloop.data.entity.MeasurementDeletionEntity
import com.pulseloop.data.entity.MeasurementEntity
import com.pulseloop.data.entity.SleepSessionEntity
import com.pulseloop.data.entity.SleepStageBlockEntity
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Body of ring-hub's `POST /api/v1/ingest/batch` (`IngestPayload` in ringhub/schemas.py): any
 * subset of the data-archive lists, with the archive's own DTOs, so the server parses a delta
 * and a full export with the same schema. The server upserts every row by the app's id.
 */
@Serializable
data class RingHubBatch(
    val formatVersion: Int = 1,
    val exportedAt: Long,
    val appVersion: String,
    /** The paired ring the readings belong to; null when no real ring is paired. */
    val deviceId: String?,
    val devices: List<DeviceDTO> = emptyList(),
    val measurements: List<MeasurementDTO> = emptyList(),
    val measurementDeletions: List<MeasurementDeletionDTO> = emptyList(),
    val activityDaily: List<ActivityDailyDTO> = emptyList(),
    val activityBuckets: List<ActivityBucketDTO> = emptyList(),
    val batterySamples: List<BatterySampleDTO> = emptyList(),
    val sleepSessions: List<SleepSessionDTO> = emptyList(),
    val sleepStageBlocks: List<SleepStageBlockDTO> = emptyList(),
    // v0.2 context streams (ringhub/schemas.py: same names, all optional on the server).
    val adaptiveTransitions: List<AdaptiveTransitionDTO> = emptyList(),
    val activityLabels: List<ActivityLabelDTO> = emptyList(),
    val diaryEntries: List<DiaryEntryDTO> = emptyList(),
    val phoneUsageHourly: List<PhoneUsageHourlyDTO> = emptyList(),
    val locationPoints: List<LocationPointDTO> = emptyList(),
    val activityTransitions: List<ActivityTransitionDTO> = emptyList(),
    val zones: List<ZoneDTO> = emptyList(),
) {
    val rowCount: Int
        get() = devices.size + measurements.size + measurementDeletions.size + activityDaily.size +
            activityBuckets.size + batterySamples.size + sleepSessions.size + sleepStageBlocks.size +
            adaptiveTransitions.size + activityLabels.size + diaryEntries.size + phoneUsageHourly.size +
            locationPoints.size + activityTransitions.size + zones.size
}

// v0.2 DTOs: the Room entity fields one for one (ContextEntities.kt).

@Serializable
data class AdaptiveTransitionDTO(
    val id: String, val timestamp: Long, val fromState: String, val toState: String, val reason: String,
    val episodeId: String? = null, val valuesJson: String? = null, val createdAt: Long,
)

@Serializable
data class ActivityLabelDTO(
    val id: String, val timestamp: Long, val category: String, val text: String? = null, val suggested: String? = null,
    val source: String, val episodeId: String? = null, val transitionId: String? = null,
    val createdAt: Long, val updatedAt: Long, val deletedAt: Long? = null,
)

@Serializable
data class DiaryEntryDTO(
    val id: String, val date: String,
    val caffeine: Boolean? = null, val lateCaffeine: Boolean? = null, val caffeineLastTime: String? = null,
    val alcohol: Boolean? = null, val alcoholUnits: Double? = null, val alcoholLastTime: String? = null,
    val lateMeal: Boolean? = null, val lateMealTime: String? = null,
    val sport: Boolean? = null, val sportStart: String? = null, val sportDurationMin: Int? = null, val sportIntensity: Int? = null,
    val screensInBed: Boolean? = null, val mood: Int? = null, val perceivedStress: Int? = null, val illness: Boolean? = null,
    val notes: String? = null, val morningMood: Int? = null, val morningFeeling: Int? = null,
    val createdAt: Long, val updatedAt: Long,
)

@Serializable
data class PhoneUsageHourlyDTO(
    val id: String, val hourStart: Long, val screenOnMinutes: Double, val unlocks: Int, val lastScreenOffAt: Long? = null, val updatedAt: Long,
)

@Serializable
data class LocationPointDTO(
    val id: String, val timestamp: Long, val latitude: Double, val longitude: Double,
    val accuracy: Float? = null, val speed: Float? = null, val provider: String? = null, val createdAt: Long,
)

@Serializable
data class ActivityTransitionDTO(val id: String, val timestamp: Long, val activityType: String, val transition: String, val createdAt: Long)

@Serializable
data class ZoneDTO(
    val id: String, val name: String, val category: String, val latitude: Double, val longitude: Double,
    val radiusMeters: Double, val createdAt: Long, val updatedAt: Long, val deletedAt: Long? = null,
)

internal fun AdaptiveTransitionEntity.toDto() = AdaptiveTransitionDTO(id, timestamp, fromState, toState, reason, episodeId, valuesJson, createdAt)

internal fun ActivityLabelEntity.toDto() = ActivityLabelDTO(id, timestamp, category, text, suggested, source, episodeId, transitionId, createdAt, updatedAt, deletedAt)

internal fun DiaryEntryEntity.toDto() = DiaryEntryDTO(
    id = id, date = date, caffeine = caffeine, lateCaffeine = lateCaffeine, caffeineLastTime = caffeineLastTime,
    alcohol = alcohol, alcoholUnits = alcoholUnits, alcoholLastTime = alcoholLastTime, lateMeal = lateMeal,
    lateMealTime = lateMealTime, sport = sport, sportStart = sportStart, sportDurationMin = sportDurationMin,
    sportIntensity = sportIntensity, screensInBed = screensInBed, mood = mood, perceivedStress = perceivedStress,
    illness = illness, notes = notes, morningMood = morningMood, morningFeeling = morningFeeling,
    createdAt = createdAt, updatedAt = updatedAt,
)

internal fun PhoneUsageHourlyEntity.toDto() = PhoneUsageHourlyDTO(id, hourStart, screenOnMinutes, unlocks, lastScreenOffAt, updatedAt)

internal fun LocationPointEntity.toDto() = LocationPointDTO(id, timestamp, latitude, longitude, accuracy, speed, provider, createdAt)

internal fun ActivityTransitionEntity.toDto() = ActivityTransitionDTO(id, timestamp, activityType, transition, createdAt)

internal fun ZoneEntity.toDto() = ZoneDTO(id, name, category, latitude, longitude, radiusMeters, createdAt, updatedAt, deletedAt)

internal fun ZoneDTO.toEntity() = ZoneEntity(id, name, category, latitude, longitude, radiusMeters, createdAt, updatedAt, deletedAt)

/** The subset of ring-hub's `IngestResult` the app reads back. */
@Serializable
data class RingHubIngestResult(
    val ok: Boolean = true,
    val ingestId: Long? = null,
    val counts: Map<String, RingHubTableCounts> = emptyMap(),
)

@Serializable
data class RingHubTableCounts(
    val received: Int = 0,
    val inserted: Int = 0,
    val updated: Int = 0,
    val unchanged: Int = 0,
    val skipped: Int = 0,
    val removed: Int = 0,
)

/** `GET /api/v1/sync/state` (`Watermark` in ringhub/schemas.py). */
@Serializable
data class RingHubServerState(
    val ingestId: Long? = null,
    val serverTime: String = "",
    val latest: Map<String, Long?> = emptyMap(),
)

/** `GET /api/v1/health`. */
@Serializable
data class RingHubHealth(val status: String = "", val version: String = "")

internal val ringHubJson = Json {
    encodeDefaults = true
    ignoreUnknownKeys = true
}

// Entity → DTO, field for field (same mapping DataArchiveService.exportArchive applies to cursors).

internal fun DeviceEntity.toDto() = DeviceDTO(
    id = id, name = name, advertisedName = advertisedName,
    peripheralIdentifier = peripheralIdentifier, bleAddressHint = bleAddressHint,
    batteryPercent = batteryPercent, stateRaw = stateRaw, deviceTypeRaw = deviceTypeRaw,
    wearableModelID = wearableModelID, capabilitiesRaw = capabilitiesRaw,
    lastConnectedAt = lastConnectedAt, lastDisconnectedAt = lastDisconnectedAt,
    lastSyncAt = lastSyncAt, lastFullSyncAt = lastFullSyncAt,
    firmwareVersion = firmwareVersion, createdAt = createdAt, updatedAt = updatedAt,
)

internal fun MeasurementEntity.toDto() = MeasurementDTO(
    id = id, kindRaw = kindRaw, value = value, unit = unit, timestamp = timestamp,
    sourceRaw = sourceRaw, confidenceRaw = confidenceRaw,
    activitySessionId = activitySessionId, rawPacketId = rawPacketId, createdAt = createdAt,
)

internal fun MeasurementDeletionEntity.toDto() = MeasurementDeletionDTO(
    measurementId = measurementId, kindRaw = kindRaw, timestamp = timestamp, deletedAt = deletedAt,
)

internal fun ActivityDailyEntity.toDto() = ActivityDailyDTO(
    id = id, date = date, steps = steps, calories = calories, distanceMeters = distanceMeters,
    activeMinutes = activeMinutes, source = source, syncedAt = syncedAt,
    createdAt = createdAt, updatedAt = updatedAt,
    deletedSteps = deletedSteps, deletedDistanceMeters = deletedDistanceMeters,
    estimatedActiveCalories = estimatedActiveCalories,
)

internal fun ActivityBucketEntity.toDto() = ActivityBucketDTO(
    startEpoch = startEpoch, date = date, steps = steps, distanceMeters = distanceMeters,
    source = source, updatedAt = updatedAt,
)

internal fun BatterySampleEntity.toDto() = BatterySampleDTO(
    id = id, percent = percent, timestamp = timestamp, createdAt = createdAt,
)

internal fun SleepSessionEntity.toDto() = SleepSessionDTO(
    id = id, date = date, startAt = startAt, endAt = endAt, totalMinutes = totalMinutes,
    score = score, syncedAt = syncedAt, sourceRaw = sourceRaw,
    createdAt = createdAt, updatedAt = updatedAt,
)

internal fun SleepStageBlockEntity.toDto() = SleepStageBlockDTO(
    id = id, sessionId = sessionId, startAt = startAt, startMinute = startMinute,
    durationMinutes = durationMinutes, stageRaw = stageRaw, recordStartAt = recordStartAt,
)
