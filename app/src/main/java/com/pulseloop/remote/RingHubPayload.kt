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
) {
    val rowCount: Int
        get() = devices.size + measurements.size + measurementDeletions.size + activityDaily.size +
            activityBuckets.size + batterySamples.size + sleepSessions.size + sleepStageBlocks.size
}

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
