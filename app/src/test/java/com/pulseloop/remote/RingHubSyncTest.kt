package com.pulseloop.remote

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
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
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
        val transitions = mutableListOf<AdaptiveTransitionEntity>()
        val labels = mutableListOf<ActivityLabelEntity>()
        val diary = mutableListOf<DiaryEntryEntity>()
        val usage = mutableListOf<PhoneUsageHourlyEntity>()
        val points = mutableListOf<LocationPointEntity>()
        val activities = mutableListOf<ActivityTransitionEntity>()
        val zones = mutableListOf<ZoneEntity>()

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
        override suspend fun adaptiveTransitionsAfter(ts: Long, key: String, limit: Int) = page(transitions, ts, key, limit, { it.createdAt }, { it.id })
        override suspend fun activityLabelsAfter(ts: Long, key: String, limit: Int) = page(labels, ts, key, limit, { it.updatedAt }, { it.id })
        override suspend fun diaryEntriesAfter(ts: Long, key: String, limit: Int) = page(diary, ts, key, limit, { it.updatedAt }, { it.id })
        override suspend fun phoneUsageAfter(ts: Long, key: String, limit: Int) = page(usage, ts, key, limit, { it.updatedAt }, { it.id })
        override suspend fun locationPointsAfter(ts: Long, key: String, limit: Int) = page(points, ts, key, limit, { it.createdAt }, { it.id })
        override suspend fun activityTransitionsAfter(ts: Long, key: String, limit: Int) = page(activities, ts, key, limit, { it.createdAt }, { it.id })
        override suspend fun zonesAfter(ts: Long, key: String, limit: Int) = page(zones, ts, key, limit, { it.updatedAt }, { it.id })
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

    private fun sync(
        source: RingHubSource,
        marks: RingHubWatermarkStore,
        sent: MutableList<RingHubBatch>,
        max: Int = 2000,
        failAfter: Int = Int.MAX_VALUE,
        reply: (RingHubBatch) -> RingHubIngestResult = ::ackAll,
    ) =
        RingHubSync(source, marks, "test", send = {
            if (sent.size >= failAfter) throw RingHubException(RingHubException.Kind.UNREACHABLE, null, "PC off")
            sent += it
            reply(it)
        }, maxRowsPerRequest = max, now = { 5_000_000 })

    /** A v0.2 server's reply: every list in the body is counted, `received` = its size. */
    private fun ackAll(batch: RingHubBatch): RingHubIngestResult {
        val obj = ringHubJson.parseToJsonElement(ringHubJson.encodeToString(RingHubBatch.serializer(), batch)).jsonObject
        return RingHubIngestResult(counts = obj.filterValues { it is JsonArray }.mapValues { RingHubTableCounts(received = it.value.jsonArray.size) })
    }

    /** A v0.1 server's reply: it drops the v0.2 lists (`extra="ignore"`), so they never appear in `counts`. */
    private fun ackV01(batch: RingHubBatch): RingHubIngestResult {
        val v02 = RingHubStream.entries.filter { it.sinceV02 }.map { it.wireName }.toSet()
        val full = ackAll(batch)
        return full.copy(counts = full.counts.filterKeys { it !in v02 })
    }

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
        source.addContext()
        val sent = mutableListOf<RingHubBatch>()
        sync(source, MemoryWatermarks(), sent).run()

        // One combined body, as the server would accept it, for the Python schema check.
        val combined = RingHubBatch(
            exportedAt = 5_000_000, appVersion = "test", deviceId = "ring-1",
            devices = sent.flatMap { it.devices }, measurements = sent.flatMap { it.measurements },
            measurementDeletions = sent.flatMap { it.measurementDeletions }, activityDaily = sent.flatMap { it.activityDaily },
            activityBuckets = sent.flatMap { it.activityBuckets }, batterySamples = sent.flatMap { it.batterySamples },
            sleepSessions = sent.flatMap { it.sleepSessions }, sleepStageBlocks = sent.flatMap { it.sleepStageBlocks },
            adaptiveTransitions = sent.flatMap { it.adaptiveTransitions }, activityLabels = sent.flatMap { it.activityLabels },
            diaryEntries = sent.flatMap { it.diaryEntries }, phoneUsageHourly = sent.flatMap { it.phoneUsageHourly },
            locationPoints = sent.flatMap { it.locationPoints }, activityTransitions = sent.flatMap { it.activityTransitions },
            zones = sent.flatMap { it.zones },
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
        for (list in listOf("adaptiveTransitions", "activityLabels", "diaryEntries", "phoneUsageHourly", "locationPoints", "activityTransitions", "zones")) {
            assertFalse("$list is empty", (obj[list]!!.jsonArray).isEmpty())
        }
        val diary = obj["diaryEntries"]!!.jsonArray.single().jsonObject
        assertEquals("2026-10-20", diary["date"]!!.jsonPrimitive.content)
        assertEquals("true", diary["lateCaffeine"]!!.jsonPrimitive.content)
        assertEquals("null", diary["alcohol"].toString())   // unknown travels as null, never false
        assertEquals(listOf("teanina"), diary["supplements"]!!.jsonArray.map { it.jsonPrimitive.content })

        // Left in the build dir for scripts/validate (not part of the assertion).
        File("build/ringhub-sample-batch.json").apply { parentFile?.mkdirs() }.writeText(text)
    }

    private val T = 1_792_485_000_000L

    private fun MemorySource.addContext() {
        transitions += AdaptiveTransitionEntity(id = "tr-1", timestamp = T, fromState = "BASELINE", toState = "WATCH", reason = "hr_high", episodeId = "ep-1", valuesJson = "{\"hr\":104}", createdAt = T)
        labels += ActivityLabelEntity(id = "lab-1", timestamp = T, category = "meeting", suggested = "work", episodeId = "ep-1", transitionId = "tr-1", createdAt = T, updatedAt = T)
        diary += DiaryEntryEntity(id = "diary:2026-10-20", date = "2026-10-20", lateCaffeine = true, caffeine = true, mood = 4, supplements = listOf("teanina"), createdAt = T, updatedAt = T)
        usage += PhoneUsageHourlyEntity(id = "usage:1792483200000", hourStart = 1_792_483_200_000, screenOnMinutes = 12.5, unlocks = 3, lastScreenOffAt = T - 60_000, updatedAt = T)
        points += LocationPointEntity(id = "loc-1", timestamp = T, latitude = 45.07, longitude = 7.68, accuracy = 25f, speed = 0f, provider = "fused", createdAt = T)
        activities += ActivityTransitionEntity(id = "act-1", timestamp = T, activityType = "IN_VEHICLE", transition = "ENTER", createdAt = T)
        zones += ZoneEntity(id = "zone-home", name = "Casa", category = "home", latitude = 45.07, longitude = 7.68, radiusMeters = 150.0, createdAt = T, updatedAt = T)
    }

    @Test
    fun `v0_2 streams are sent with their own watermarks`() = runTest {
        val source = MemorySource().apply { device = null; addContext() }
        val marks = MemoryWatermarks()
        val sent = mutableListOf<RingHubBatch>()
        val result = sync(source, marks, sent).run()

        for (stream in listOf(
            RingHubStream.ADAPTIVE_TRANSITIONS, RingHubStream.ACTIVITY_LABELS, RingHubStream.DIARY_ENTRIES,
            RingHubStream.PHONE_USAGE_HOURLY, RingHubStream.LOCATION_POINTS, RingHubStream.ACTIVITY_TRANSITIONS, RingHubStream.ZONES,
        )) {
            assertEquals(stream.name, 1, result.rows[stream])
            assertEquals(stream.name, T, marks.marks[stream])
        }
        // One request per stream, each carrying only its own list.
        assertEquals(7, sent.size)
        assertTrue(sent.all { it.rowCount == 1 })
        // Zones go before location points (the server rebuilds places on zones).
        val zoneIdx = sent.indexOfFirst { it.zones.isNotEmpty() }
        val pointIdx = sent.indexOfFirst { it.locationPoints.isNotEmpty() }
        assertTrue(zoneIdx in 0 until pointIdx)
    }

    @Test
    fun `an edited label or diary day is re-sent, an old transition is not`() = runTest {
        val source = MemorySource().apply { device = null; addContext() }
        val marks = MemoryWatermarks()
        sync(source, marks, mutableListOf()).run()

        source.labels[0] = source.labels[0].copy(category = "traffic", updatedAt = T + 600_000)
        source.diary[0] = source.diary[0].copy(alcohol = true, updatedAt = T + 600_000)
        source.transitions.clear()
        source.transitions += AdaptiveTransitionEntity(id = "tr-0", timestamp = T - 3_600_000, fromState = "BASELINE", toState = "WATCH", reason = "hr_high", createdAt = T - 3_600_000)
        val sent = mutableListOf<RingHubBatch>()
        sync(source, marks, sent).run()

        assertEquals(listOf("traffic"), sent.flatMap { it.activityLabels }.map { it.category })
        assertEquals(listOf(true), sent.flatMap { it.diaryEntries }.map { it.alcohol })
        assertTrue(sent.flatMap { it.adaptiveTransitions }.isEmpty())   // behind the watermark + overlap
    }

    @Test
    fun `diary supplements travel as a list, unknown as null, none as an empty list`() {
        fun encode(supplements: List<String>?) = ringHubJson.parseToJsonElement(
            ringHubJson.encodeToString(
                DiaryEntryDTO.serializer(),
                DiaryEntryEntity(id = "diary:2026-10-20", date = "2026-10-20", supplements = supplements, createdAt = T, updatedAt = T).toDto(),
            ),
        ).jsonObject["supplements"]
        assertEquals("null", encode(null).toString())
        assertEquals("[]", encode(emptyList()).toString())
        assertEquals("[\"teanina\",\"ashwagandha\"]", encode(listOf("teanina", "ashwagandha")).toString())
        // A v0.2 payload without the field still decodes (unknown).
        val old = ringHubJson.decodeFromString(DiaryEntryDTO.serializer(), """{"id":"d","date":"2026-10-20","createdAt":1,"updatedAt":1}""")
        assertNull(old.supplements)
    }

    @Test
    fun `zone dto round-trips for the pull from the server`() {
        val zone = ZoneEntity(id = "z", name = "Palestra", category = "gym", latitude = 1.0, longitude = 2.0, radiusMeters = 120.0, createdAt = 1, updatedAt = 2, deletedAt = 3)
        assertEquals(zone, zone.toDto().toEntity())
        val parsed = ringHubJson.decodeFromString(
            kotlinx.serialization.builtins.ListSerializer(ZoneDTO.serializer()),
            """[{"id":"z","name":"Palestra","category":"gym","latitude":1.0,"longitude":2.0,"radiusMeters":120.0,"createdAt":1,"updatedAt":2,"deletedAt":null}]""",
        )
        assertEquals(null, parsed.single().deletedAt)
    }

    @Test
    fun `a v0_1 server that ignores the v0_2 lists does not move their watermarks`() = runTest {
        val source = MemorySource().apply { addContext(); measurements += hr("m1", createdAt = 1_000) }
        val marks = MemoryWatermarks()
        val result = sync(source, marks, mutableListOf(), reply = ::ackV01).run()

        assertEquals(1_000L, marks.marks[RingHubStream.MEASUREMENTS])
        assertEquals(1_000L, marks.marks[RingHubStream.DEVICES])
        val v02 = RingHubStream.entries.filter { it.sinceV02 }
        for (stream in v02) assertNull(stream.name, marks.marks[stream])
        assertEquals(v02.toSet(), result.pending)
        assertTrue(result.statusLine(), "Server non aggiornato: alcuni dati in attesa" in result.statusLine())

        // Server updated: the same rows go again and the watermarks move.
        val sent = mutableListOf<RingHubBatch>()
        val after = sync(source, marks, sent).run()
        assertTrue(after.pending.isEmpty())
        assertEquals(listOf("zone-home"), sent.flatMap { it.zones }.map { it.id })
        for (stream in v02) assertEquals(stream.name, T, marks.marks[stream])
        assertFalse("Server non aggiornato" in after.statusLine())
    }

    @Test
    fun `fewer rows received than sent keeps the watermark`() = runTest {
        val source = MemorySource().apply { device = null; (1..3).forEach { measurements += hr("m$it", createdAt = it * 1_000L) } }
        val marks = MemoryWatermarks()
        val short = { b: RingHubBatch -> ackAll(b).let { r -> r.copy(counts = r.counts + ("measurements" to RingHubTableCounts(received = 1))) } }
        val result = sync(source, marks, mutableListOf(), reply = short).run()
        assertNull(marks.marks[RingHubStream.MEASUREMENTS])
        assertEquals(setOf(RingHubStream.MEASUREMENTS), result.pending)
    }

    @Test
    fun `a rejected v0_2 stream is skipped without blocking the later ones`() = runTest {
        val source = MemorySource().apply { device = null; addContext() }
        val marks = MemoryWatermarks()
        val sent = mutableListOf<RingHubBatch>()
        val result = RingHubSync(source, marks, "test", send = {
            if (it.diaryEntries.isNotEmpty()) throw RingHubException(RingHubException.Kind.REJECTED, 422, "HTTP 422: mood")
            sent += it
            ackAll(it)
        }, now = { 5_000_000 }).run()

        assertNull(marks.marks[RingHubStream.DIARY_ENTRIES])
        assertEquals(setOf(RingHubStream.DIARY_ENTRIES), result.rejected.keys)
        for (stream in listOf(RingHubStream.PHONE_USAGE_HOURLY, RingHubStream.ACTIVITY_TRANSITIONS, RingHubStream.ZONES, RingHubStream.LOCATION_POINTS)) {
            assertEquals(stream.name, T, marks.marks[stream])
        }
        assertTrue("diaryEntries" in result.statusLine())
    }

    @Test
    fun `an outage during a v0_2 stream still stops the pass`() = runTest {
        val source = MemorySource().apply { device = null; addContext() }
        val error = runCatching {
            RingHubSync(source, MemoryWatermarks(), "test", send = {
                if (it.diaryEntries.isNotEmpty()) throw RingHubException(RingHubException.Kind.UNREACHABLE, null, "PC off")
                ackAll(it)
            }, now = { 5_000_000 }).run()
        }.exceptionOrNull()
        assertTrue(error is RingHubException && error.retryable)
    }

    @Test
    fun `zones and places decode from a real ring-hub response`() {
        // Captured from ring-hub v0.2 (GET /api/v1/zones and /places after a dashboard promotion).
        val zonesJson = """[{"id":"zone-home","name":"Casa","category":"home","latitude":45.07,"longitude":7.68,"radiusMeters":150.0,"createdAt":1792485015000,"updatedAt":1792485015000,"deletedAt":null},{"id":"zone-old","name":"Vecchia","category":"home","latitude":45.07,"longitude":7.68,"radiusMeters":150.0,"createdAt":1792485015000,"updatedAt":1792485020000,"deletedAt":1792485020000},{"id":"zone-62885c6d-b8c1-46c9-a9bc-fdc8c8e68a5e","name":"Palestra","category":"gym","latitude":45.06000680799706,"longitude":7.700021073823404,"radiusMeters":120.0,"createdAt":1792846210577,"updatedAt":1792846210577,"deletedAt":null}]"""
        val placesJson = """[{"id":1,"name":"Casa","autoLabel":null,"zoneId":"zone-home","latitude":45.07,"longitude":7.68,"visitCount":5,"totalMinutes":2905},{"id":2,"name":"Lavoro","autoLabel":"Lavoro","zoneId":null,"latitude":45.080000389255666,"longitude":7.660000234601298,"visitCount":5,"totalMinutes":2700},{"id":4,"name":"Palestra","autoLabel":null,"zoneId":"zone-62885c6d-b8c1-46c9-a9bc-fdc8c8e68a5e","latitude":45.06000680799706,"longitude":7.700021073823404,"visitCount":2,"totalMinutes":120}]"""
        val zones = ringHubJson.decodeFromString(kotlinx.serialization.builtins.ListSerializer(ZoneDTO.serializer()), zonesJson)
        assertEquals(listOf("zone-home", "zone-old", "zone-62885c6d-b8c1-46c9-a9bc-fdc8c8e68a5e"), zones.map { it.id })
        assertEquals(1_792_485_020_000L, zones[1].deletedAt)
        assertEquals(1_792_846_210_577L, zones[2].updatedAt)
        val places = ringHubJson.decodeFromString(kotlinx.serialization.builtins.ListSerializer(com.pulseloop.context.AutoPlace.serializer()), placesJson)
        assertEquals("zone-62885c6d-b8c1-46c9-a9bc-fdc8c8e68a5e", places.last().zoneId)

        // The v0.2.0 server's float epochs are what broke the pull: this must keep failing loudly
        // (the worker logs and skips) rather than be silently mis-decoded.
        val floatJson = zonesJson.replace("1792485015000,", "1792485015000.0,")
        assertTrue(runCatching { ringHubJson.decodeFromString(kotlinx.serialization.builtins.ListSerializer(ZoneDTO.serializer()), floatJson) }.isFailure)
    }

    @Test
    fun `ingest reply decodes from a real ring-hub response`() {
        val reply = """{"ok":true,"ingestId":3,"deviceId":"d","counts":{"devices":{"received":0,"inserted":0,"updated":0,"unchanged":0,"skipped":0,"removed":0},"zones":{"received":2,"inserted":1,"updated":0,"unchanged":1,"skipped":0,"removed":0}},"watermark":{"ingestId":3,"serverTime":"2026-10-24T12:50:10Z","latest":{"devices":1792846210424,"measurements":null,"zones":1792846210577}}}"""
        val parsed = ringHubJson.decodeFromString(RingHubIngestResult.serializer(), reply)
        assertEquals(2, parsed.counts["zones"]!!.received)
    }
}
