package com.pulseloop.remote

import com.pulseloop.data.entity.ActivityBucketEntity
import com.pulseloop.data.entity.ActivityDailyEntity
import com.pulseloop.data.entity.BatterySampleEntity
import com.pulseloop.data.entity.DeviceEntity
import com.pulseloop.data.entity.MeasurementDeletionEntity
import com.pulseloop.data.entity.MeasurementEntity
import com.pulseloop.data.entity.SleepSessionEntity
import com.pulseloop.data.entity.SleepStageBlockEntity
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The ring-hub delta pass against an in-memory source that answers the same keyset queries the
 * Room DAOs do (rows strictly after (stamp, key), in (stamp, key) order).
 */
class RingHubSyncTest {

    private class MemoryWatermarks : RingHubWatermarkStore {
        val marks = mutableMapOf<RingHubStream, Long>()
        override fun watermark(stream: RingHubStream): Long? = marks[stream]
        override fun advance(stream: RingHubStream, value: Long) {
            val existing = marks[stream]
            if (existing == null || value > existing) marks[stream] = value
        }
    }

    private class MemorySource : RingHubSource {
        var device: DeviceEntity? = DeviceEntity(id = "ring-1", name = "R09_E607", deviceTypeRaw = "COLMI_R02", createdAt = 1_000, updatedAt = 1_000)
        val measurements = mutableListOf<MeasurementEntity>()
        val deletions = mutableListOf<MeasurementDeletionEntity>()
        val days = mutableListOf<ActivityDailyEntity>()
        val buckets = mutableListOf<ActivityBucketEntity>()
        val battery = mutableListOf<BatterySampleEntity>()
        val sessions = mutableListOf<SleepSessionEntity>()
        val blocks = mutableListOf<SleepStageBlockEntity>()

        private fun <T> page(rows: List<T>, ts: Long, key: String, limit: Int, stamp: (T) -> Long, id: (T) -> String) =
            rows.filter { stamp(it) > ts || (stamp(it) == ts && id(it) > key) }
                .sortedWith(compareBy<T>(stamp).thenBy(id)).take(limit)

        override suspend fun deviceId() = device?.id
        override suspend fun devicesUpdatedSince(ts: Long) = listOfNotNull(device).filter { it.updatedAt > ts }
        override suspend fun measurementsAfter(ts: Long, key: String, limit: Int) = page(measurements, ts, key, limit, { it.createdAt }, { it.id })
        override suspend fun deletionsAfter(ts: Long, key: String, limit: Int) = page(deletions, ts, key, limit, { it.deletedAt }, { it.measurementId })
        override suspend fun activityDailyAfter(ts: Long, key: String, limit: Int) = page(days, ts, key, limit, { it.updatedAt }, { it.id })
        override suspend fun activityBucketsAfter(ts: Long, startEpoch: Long, limit: Int) =
            buckets.filter { it.updatedAt > ts || (it.updatedAt == ts && it.startEpoch > startEpoch) }
                .sortedWith(compareBy<ActivityBucketEntity>({ it.updatedAt }, { it.startEpoch })).take(limit)
        override suspend fun batterySamplesAfter(ts: Long, key: String, limit: Int) = page(battery, ts, key, limit, { it.createdAt }, { it.id })
        override suspend fun sleepSessionsAfter(ts: Long, key: String, limit: Int) = page(sessions, ts, key, limit, { it.updatedAt }, { it.id })
        override suspend fun stageBlocksFor(sessionIds: List<String>) = blocks.filter { it.sessionId in sessionIds }
    }

    private fun hr(id: String, createdAt: Long, ts: Long = createdAt) = MeasurementEntity(
        id = id, kindRaw = "HEART_RATE", value = 64.0, unit = "bpm", timestamp = ts, sourceRaw = "history", createdAt = createdAt,
    )

    private fun session(id: String, updatedAt: Long) = SleepSessionEntity(
        id = id, date = 1_792_447_200_000, startAt = 1_792_400_000_000, endAt = 1_792_425_000_000,
        totalMinutes = 400, createdAt = updatedAt, updatedAt = updatedAt,
    )

    private fun blocksFor(sessionId: String, n: Int) = (0 until n).map {
        SleepStageBlockEntity(
            id = "$sessionId-b$it", sessionId = sessionId, startAt = 1_792_400_000_000 + it * 60_000L,
            startMinute = it, durationMinutes = 1, stageRaw = "LIGHT", recordStartAt = 1_792_400_000_000,
        )
    }

    private fun sync(source: RingHubSource, marks: RingHubWatermarkStore, sent: MutableList<RingHubBatch>, max: Int = 2000, failAfter: Int = Int.MAX_VALUE) =
        RingHubSync(source, marks, "test", send = {
            if (sent.size >= failAfter) throw RingHubException(RingHubException.Kind.UNREACHABLE, null, "PC off")
            sent += it
        }, maxRowsPerRequest = max, now = { 5_000_000 })

    @Test
    fun `first pass sends every stream in chunks and advances each watermark`() = runTest {
        val source = MemorySource().apply {
            (1..5).forEach { measurements += hr("history:hr:$it", createdAt = it * 1_000L) }
            deletions += MeasurementDeletionEntity("history:hr:9", "HEART_RATE", 9_000, deletedAt = 7_000)
            days += ActivityDailyEntity(id = "d1", date = 0, steps = 1234, source = "ring", createdAt = 1, updatedAt = 3_000)
            buckets += ActivityBucketEntity(startEpoch = 900_000, date = 0, steps = 50, updatedAt = 4_000)
            battery += BatterySampleEntity(id = "b1", percent = 80, timestamp = 2_000, createdAt = 2_000)
        }
        val marks = MemoryWatermarks()
        val sent = mutableListOf<RingHubBatch>()

        val result = sync(source, marks, sent, max = 2).run()

        // devices, deletions, measurements x3 (2+2+1), daily, buckets, battery
        assertEquals(8, sent.size)
        assertEquals(listOf(2, 2, 1), sent.filter { it.measurements.isNotEmpty() }.map { it.measurements.size })
        assertTrue(sent.all { it.rowCount <= 2 })
        assertTrue(sent.all { it.deviceId == "ring-1" })
        assertEquals(5, result.rows[RingHubStream.MEASUREMENTS])
        assertEquals(5_000L, marks.marks[RingHubStream.MEASUREMENTS])
        assertEquals(7_000L, marks.marks[RingHubStream.MEASUREMENT_DELETIONS])
        assertEquals(3_000L, marks.marks[RingHubStream.ACTIVITY_DAILY])
        assertEquals(4_000L, marks.marks[RingHubStream.ACTIVITY_BUCKETS])
        assertEquals(2_000L, marks.marks[RingHubStream.BATTERY_SAMPLES])
        assertEquals(1_000L, marks.marks[RingHubStream.DEVICES])
    }

    @Test
    fun `rows sharing one millisecond are not lost at a chunk boundary`() = runTest {
        // A history sync stamps hundreds of rows in the same millisecond.
        val source = MemorySource().apply { (1..5).forEach { measurements += hr("history:hr:$it", createdAt = 10_000) } }
        val sent = mutableListOf<RingHubBatch>()
        sync(source, MemoryWatermarks(), sent, max = 2).run()

        val ids = sent.flatMap { b -> b.measurements.map { it.id } }
        assertEquals((1..5).map { "history:hr:$it" }, ids)
    }

    @Test
    fun `next pass sends only new rows plus the overlap window`() = runTest {
        val source = MemorySource().apply {
            measurements += hr("old", createdAt = 1_000)
            measurements += hr("recent", createdAt = 1_000_000)
        }
        val marks = MemoryWatermarks()
        sync(source, marks, mutableListOf()).run()

        // A re-synced history row keeps its id with a fresh createdAt; one row lands late with a
        // stamp just behind the watermark (built before, committed after the previous pass).
        source.measurements += hr("old", createdAt = 2_000_000)
        source.measurements += hr("late", createdAt = 1_000_000 - 30_000)
        val sent = mutableListOf<RingHubBatch>()
        sync(source, marks, sent).run()

        val ids = sent.flatMap { b -> b.measurements.map { it.id } }.toSet()
        assertEquals(setOf("recent", "late", "old"), ids)
        assertEquals(2_000_000L, marks.marks[RingHubStream.MEASUREMENTS])
    }

    @Test
    fun `unreachable server keeps the watermark at the last acknowledged chunk`() = runTest {
        val source = MemorySource().apply { (1..6).forEach { measurements += hr("m$it", createdAt = it * 1_000L) } }
        source.device = null
        val marks = MemoryWatermarks()
        val sent = mutableListOf<RingHubBatch>()

        val error = runCatching { sync(source, marks, sent, max = 2, failAfter = 1).run() }.exceptionOrNull()
        assertTrue(error is RingHubException && error.retryable)
        assertEquals(2_000L, marks.marks[RingHubStream.MEASUREMENTS])

        // PC back on: the next pass resumes after m2 (re-reading only the overlap window).
        val resumed = mutableListOf<RingHubBatch>()
        sync(source, marks, resumed, max = 2).run()
        val ids = resumed.flatMap { b -> b.measurements.map { it.id } }
        assertEquals(listOf("m1", "m2", "m3", "m4", "m5", "m6"), ids)  // m1/m2 inside the 2-minute overlap
        assertEquals(6_000L, marks.marks[RingHubStream.MEASUREMENTS])
        assertNull(sent.firstOrNull()?.deviceId)
    }

    @Test
    fun `a sleep session travels with all of its blocks`() = runTest {
        val source = MemorySource().apply {
            sessions += session("s1", updatedAt = 1_000); blocks += blocksFor("s1", 3)
            sessions += session("s2", updatedAt = 2_000); blocks += blocksFor("s2", 3)
            sessions += session("s3", updatedAt = 3_000); blocks += blocksFor("s3", 9)
        }
        source.device = null
        val marks = MemoryWatermarks()
        val sent = mutableListOf<RingHubBatch>()
        sync(source, marks, sent, max = 8).run()

        assertEquals(2, sent.size)
        for (batch in sent) {
            val ids = batch.sleepSessions.map { it.id }.toSet()
            assertTrue(batch.sleepStageBlocks.all { it.sessionId in ids })
            for (id in ids) assertEquals(source.blocks.count { it.sessionId == id }, batch.sleepStageBlocks.count { it.sessionId == id })
        }
        assertEquals(listOf("s1", "s2"), sent[0].sleepSessions.map { it.id })
        assertEquals(listOf("s3"), sent[1].sleepSessions.map { it.id })  // 10 rows > 8: alone
        assertEquals(3_000L, marks.marks[RingHubStream.SLEEP_SESSIONS])
    }

    @Test
    fun `sleep chunking packs sessions greedily without splitting one`() {
        val sessions = listOf(session("a", 1), session("b", 2), session("c", 3))
        val blocks = mapOf("a" to blocksFor("a", 1), "b" to blocksFor("b", 1), "c" to blocksFor("c", 1))
        assertEquals(listOf(listOf("a", "b"), listOf("c")), RingHubSync.sleepChunks(sessions, blocks, 4).map { c -> c.map { it.id } })
        assertEquals(listOf(listOf("a", "b", "c")), RingHubSync.sleepChunks(sessions, blocks, 6).map { c -> c.map { it.id } })
        assertTrue(RingHubSync.sleepChunks(emptyList(), emptyMap(), 4).isEmpty())
    }

    @Test
    fun `batch json uses the archive field names ring-hub expects`() = runTest {
        val source = MemorySource().apply {
            measurements += hr("history:hr:1792485015000", createdAt = 1_792_485_016_000, ts = 1_792_485_015_000)
            deletions += MeasurementDeletionEntity("history:hr:1792480000000", "HEART_RATE", 1_792_480_000_000, deletedAt = 1_792_485_000_000)
            days += ActivityDailyEntity(id = "d1", date = 1_792_447_200_000, steps = 5432, calories = 210.0, source = "ring", createdAt = 1_792_447_300_000, updatedAt = 1_792_485_000_000)
            buckets += ActivityBucketEntity(startEpoch = 1_792_480_000_000, date = 1_792_447_200_000, steps = 120, distanceMeters = 90.0, updatedAt = 1_792_485_000_000)
            battery += BatterySampleEntity(id = "bat-1", percent = 77, timestamp = 1_792_485_000_000, createdAt = 1_792_485_000_000)
            sessions += session("sleep-1", updatedAt = 1_792_485_000_000); blocks += blocksFor("sleep-1", 2)
        }
        source.device = source.device!!.copy(updatedAt = 1_792_485_000_000, createdAt = 1_792_000_000_000)
        val sent = mutableListOf<RingHubBatch>()
        sync(source, MemoryWatermarks(), sent).run()

        // One combined body, as the server would accept it, for the Python schema check.
        val combined = RingHubBatch(
            exportedAt = 5_000_000, appVersion = "test", deviceId = "ring-1",
            devices = sent.flatMap { it.devices }, measurements = sent.flatMap { it.measurements },
            measurementDeletions = sent.flatMap { it.measurementDeletions }, activityDaily = sent.flatMap { it.activityDaily },
            activityBuckets = sent.flatMap { it.activityBuckets }, batterySamples = sent.flatMap { it.batterySamples },
            sleepSessions = sent.flatMap { it.sleepSessions }, sleepStageBlocks = sent.flatMap { it.sleepStageBlocks },
        )
        val text = ringHubJson.encodeToString(RingHubBatch.serializer(), combined)
        val obj = ringHubJson.parseToJsonElement(text).jsonObject
        assertEquals("ring-1", obj["deviceId"]!!.jsonPrimitive.content)
        val m = obj["measurements"]!!.jsonArray.single().jsonObject
        for (field in listOf("id", "kindRaw", "value", "unit", "timestamp", "sourceRaw", "confidenceRaw", "createdAt")) {
            assertTrue("measurement.$field", field in m)
        }
        assertEquals(1_792_485_015_000L, m["timestamp"]!!.jsonPrimitive.long)
        val block = obj["sleepStageBlocks"]!!.jsonArray.first().jsonObject
        assertEquals("sleep-1", block["sessionId"]!!.jsonPrimitive.content)
        for (list in listOf("devices", "measurementDeletions", "activityDaily", "activityBuckets", "batterySamples", "sleepSessions")) {
            assertFalse("$list is empty", (obj[list]!!.jsonArray).isEmpty())
        }
        assertTrue(obj["devices"]!!.jsonArray.first() is JsonObject)

        // Left in the build dir for scripts/validate (not part of the assertion).
        File("build/ringhub-sample-batch.json").apply { parentFile?.mkdirs() }.writeText(text)
    }
}
