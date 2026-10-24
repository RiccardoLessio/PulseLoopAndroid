package com.pulseloop.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import com.pulseloop.data.entity.ActivityBucketEntity
import com.pulseloop.data.entity.ActivityLabelEntity
import com.pulseloop.data.entity.ActivityTransitionEntity
import com.pulseloop.data.entity.AdaptiveTransitionEntity
import com.pulseloop.data.entity.DiaryEntryEntity
import com.pulseloop.data.entity.LocationPointEntity
import com.pulseloop.data.entity.MeasurementEntity
import com.pulseloop.data.entity.PhoneUsageHourlyEntity
import com.pulseloop.data.entity.ZoneEntity
import kotlinx.coroutines.flow.Flow

// Every `…After` query is the ring-hub keyset page: rows strictly after (stamp, id), in that order.

@Dao
interface AdaptiveTransitionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(row: AdaptiveTransitionEntity)

    @Query("SELECT * FROM adaptive_transitions ORDER BY timestamp DESC LIMIT :limit")
    fun recentFlow(limit: Int = 50): Flow<List<AdaptiveTransitionEntity>>

    @Query("SELECT * FROM adaptive_transitions WHERE timestamp >= :since ORDER BY timestamp ASC")
    suspend fun since(since: Long): List<AdaptiveTransitionEntity>

    @Query(
        "SELECT * FROM adaptive_transitions WHERE (createdAt > :createdAt OR (createdAt = :createdAt AND id > :id)) " +
            "ORDER BY createdAt ASC, id ASC LIMIT :limit",
    )
    suspend fun createdAfter(createdAt: Long, id: String, limit: Int): List<AdaptiveTransitionEntity>
}

@Dao
interface ActivityLabelDao {
    @Upsert
    suspend fun upsert(row: ActivityLabelEntity)

    @Query("SELECT * FROM activity_labels WHERE id = :id")
    suspend fun byId(id: String): ActivityLabelEntity?

    @Query("SELECT * FROM activity_labels WHERE deletedAt IS NULL AND timestamp >= :since ORDER BY timestamp DESC")
    fun recentFlow(since: Long): Flow<List<ActivityLabelEntity>>

    @Query("SELECT * FROM activity_labels WHERE episodeId = :episodeId AND deletedAt IS NULL LIMIT 1")
    suspend fun forEpisode(episodeId: String): ActivityLabelEntity?

    @Query(
        "SELECT * FROM activity_labels WHERE (updatedAt > :updatedAt OR (updatedAt = :updatedAt AND id > :id)) " +
            "ORDER BY updatedAt ASC, id ASC LIMIT :limit",
    )
    suspend fun updatedAfter(updatedAt: Long, id: String, limit: Int): List<ActivityLabelEntity>
}

@Dao
interface DiaryEntryDao {
    @Upsert
    suspend fun upsert(row: DiaryEntryEntity)

    @Query("SELECT * FROM diary_entries WHERE date = :date")
    suspend fun byDate(date: String): DiaryEntryEntity?

    @Query("SELECT * FROM diary_entries WHERE date = :date")
    fun byDateFlow(date: String): Flow<DiaryEntryEntity?>

    @Query("SELECT * FROM diary_entries ORDER BY date DESC LIMIT :limit")
    fun recentFlow(limit: Int = 60): Flow<List<DiaryEntryEntity>>

    @Query(
        "SELECT * FROM diary_entries WHERE (updatedAt > :updatedAt OR (updatedAt = :updatedAt AND id > :id)) " +
            "ORDER BY updatedAt ASC, id ASC LIMIT :limit",
    )
    suspend fun updatedAfter(updatedAt: Long, id: String, limit: Int): List<DiaryEntryEntity>
}

@Dao
interface PhoneUsageHourlyDao {
    @Upsert
    suspend fun upsertAll(rows: List<PhoneUsageHourlyEntity>)

    @Query("SELECT * FROM phone_usage_hourly WHERE hourStart >= :from AND hourStart < :to ORDER BY hourStart")
    suspend fun between(from: Long, to: Long): List<PhoneUsageHourlyEntity>

    @Query(
        "SELECT * FROM phone_usage_hourly WHERE (updatedAt > :updatedAt OR (updatedAt = :updatedAt AND id > :id)) " +
            "ORDER BY updatedAt ASC, id ASC LIMIT :limit",
    )
    suspend fun updatedAfter(updatedAt: Long, id: String, limit: Int): List<PhoneUsageHourlyEntity>
}

@Dao
interface LocationPointDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(row: LocationPointEntity)

    @Query("SELECT * FROM location_points ORDER BY timestamp DESC LIMIT 1")
    suspend fun latest(): LocationPointEntity?

    @Query("SELECT COUNT(*) FROM location_points WHERE timestamp >= :since")
    fun countSinceFlow(since: Long): Flow<Int>

    @Query(
        "SELECT * FROM location_points WHERE (createdAt > :createdAt OR (createdAt = :createdAt AND id > :id)) " +
            "ORDER BY createdAt ASC, id ASC LIMIT :limit",
    )
    suspend fun createdAfter(createdAt: Long, id: String, limit: Int): List<LocationPointEntity>
}

@Dao
interface ActivityTransitionDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(row: ActivityTransitionEntity)

    /** The most recent ENTER: the activity the phone believes is going on now. */
    @Query("SELECT * FROM activity_transitions WHERE transition = 'ENTER' ORDER BY timestamp DESC LIMIT 1")
    suspend fun latestEnter(): ActivityTransitionEntity?

    @Query("SELECT * FROM activity_transitions WHERE timestamp >= :from AND timestamp < :to ORDER BY timestamp")
    suspend fun between(from: Long, to: Long): List<ActivityTransitionEntity>

    @Query(
        "SELECT * FROM activity_transitions WHERE (createdAt > :createdAt OR (createdAt = :createdAt AND id > :id)) " +
            "ORDER BY createdAt ASC, id ASC LIMIT :limit",
    )
    suspend fun createdAfter(createdAt: Long, id: String, limit: Int): List<ActivityTransitionEntity>
}

@Dao
interface ZoneDao {
    @Upsert
    suspend fun upsert(row: ZoneEntity)

    @Query("SELECT * FROM zones WHERE id = :id")
    suspend fun byId(id: String): ZoneEntity?

    @Query("SELECT * FROM zones WHERE deletedAt IS NULL ORDER BY createdAt")
    suspend fun active(): List<ZoneEntity>

    @Query("SELECT * FROM zones WHERE deletedAt IS NULL ORDER BY createdAt")
    fun activeFlow(): Flow<List<ZoneEntity>>

    @Query(
        "SELECT * FROM zones WHERE (updatedAt > :updatedAt OR (updatedAt = :updatedAt AND id > :id)) " +
            "ORDER BY updatedAt ASC, id ASC LIMIT :limit",
    )
    suspend fun updatedAfter(updatedAt: Long, id: String, limit: Int): List<ZoneEntity>
}

/** Read-only queries over existing tables that adaptive sampling needs (real rows only). */
@Dao
interface AdaptiveReadDao {
    @Query("SELECT * FROM measurements WHERE kindRaw = :kind AND timestamp >= :from AND sourceRaw NOT IN ('demo','mock') ORDER BY timestamp")
    suspend fun readings(kind: String, from: Long): List<MeasurementEntity>

    /** The auto-logged grid without the app's own escalated spots (biased towards high values). */
    @Query("SELECT * FROM measurements WHERE kindRaw = :kind AND timestamp >= :from AND sourceRaw NOT IN ('demo','mock','adaptive_spot') ORDER BY timestamp")
    suspend fun baselineReadings(kind: String, from: Long): List<MeasurementEntity>

    @Query("SELECT * FROM activity_buckets WHERE startEpoch >= :from AND source NOT IN ('demo','mock') ORDER BY startEpoch")
    suspend fun buckets(from: Long): List<ActivityBucketEntity>

    @Query("SELECT MAX(endAt) FROM sleep_sessions WHERE sourceRaw NOT IN ('demo','mock')")
    suspend fun lastSleepEnd(): Long?

    @Query("SELECT percent FROM battery_samples WHERE timestamp >= :since ORDER BY timestamp DESC LIMIT 1")
    suspend fun latestBattery(since: Long): Int?

    @Query("SELECT * FROM activity_sessions WHERE startedAt >= :from ORDER BY startedAt")
    suspend fun workoutsSince(from: Long): List<com.pulseloop.data.entity.ActivitySessionEntity>
}
