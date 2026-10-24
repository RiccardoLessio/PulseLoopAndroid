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

/**
 * The delta streams pushed to ring-hub, each with its own local watermark. The names are the
 * server's `Watermark.latest` keys. Append-style rows watermark on `createdAt`, rows the app edits
 * in place on `updatedAt` — the same split ring-hub documents.
 */
enum class RingHubStream(val wireName: String) {
    DEVICES("devices"),
    MEASUREMENT_DELETIONS("measurementDeletions"),
    MEASUREMENTS("measurements"),
    ACTIVITY_DAILY("activityDaily"),
    ACTIVITY_BUCKETS("activityBuckets"),
    BATTERY_SAMPLES("batterySamples"),
    SLEEP_SESSIONS("sleepSessions"),
    // v0.2
    ADAPTIVE_TRANSITIONS("adaptiveTransitions"),
    ACTIVITY_LABELS("activityLabels"),
    DIARY_ENTRIES("diaryEntries"),
    PHONE_USAGE_HOURLY("phoneUsageHourly"),
    LOCATION_POINTS("locationPoints"),
    ACTIVITY_TRANSITIONS("activityTransitions"),
    ZONES("zones"),
    ;

    /** Added in v0.2: a v0.1 server silently ignores them (pydantic `extra="ignore"`). */
    val sinceV02: Boolean get() = ordinal >= ADAPTIVE_TRANSITIONS.ordinal
}

/** Per-stream "everything up to here has landed" marks, in epoch ms. */
interface RingHubWatermarkStore {
    fun watermark(stream: RingHubStream): Long?

    /** Moves a watermark forward; a lower value is ignored. */
    fun advance(stream: RingHubStream, value: Long)
}

/**
 * Reads what a pass sends. Every `…After` query is a keyset page: rows strictly after
 * `(ts, key)` in `(ts, key)` order, demo/mock rows excluded. `key == ""` means "from the start of
 * `ts`".
 */
interface RingHubSource {
    suspend fun deviceId(): String?
    suspend fun devicesUpdatedSince(ts: Long): List<DeviceEntity>
    suspend fun measurementsAfter(ts: Long, key: String, limit: Int): List<MeasurementEntity>
    suspend fun deletionsAfter(ts: Long, key: String, limit: Int): List<MeasurementDeletionEntity>
    suspend fun activityDailyAfter(ts: Long, key: String, limit: Int): List<ActivityDailyEntity>
    suspend fun activityBucketsAfter(ts: Long, startEpoch: Long, limit: Int): List<ActivityBucketEntity>
    suspend fun batterySamplesAfter(ts: Long, key: String, limit: Int): List<BatterySampleEntity>
    suspend fun sleepSessionsAfter(ts: Long, key: String, limit: Int): List<SleepSessionEntity>
    suspend fun stageBlocksFor(sessionIds: List<String>): List<SleepStageBlockEntity>
    // v0.2: append-style rows on createdAt, rows edited in place on updatedAt.
    suspend fun adaptiveTransitionsAfter(ts: Long, key: String, limit: Int): List<AdaptiveTransitionEntity>
    suspend fun activityLabelsAfter(ts: Long, key: String, limit: Int): List<ActivityLabelEntity>
    suspend fun diaryEntriesAfter(ts: Long, key: String, limit: Int): List<DiaryEntryEntity>
    suspend fun phoneUsageAfter(ts: Long, key: String, limit: Int): List<PhoneUsageHourlyEntity>
    suspend fun locationPointsAfter(ts: Long, key: String, limit: Int): List<LocationPointEntity>
    suspend fun activityTransitionsAfter(ts: Long, key: String, limit: Int): List<ActivityTransitionEntity>
    suspend fun zonesAfter(ts: Long, key: String, limit: Int): List<ZoneEntity>
}

/**
 * What one pass sent, per stream (rows) and in total (requests).
 *
 * [pending]: streams the server answered for without acknowledging them (no `counts` entry, or
 * fewer rows `received` than sent) - an older ring-hub that drops lists it doesn't know. Their
 * watermarks stayed put, so the rows go again once the server is updated.
 * [rejected]: v0.2 streams whose request the server refused (HTTP 4xx), with the reason; skipped
 * for this pass without stopping the other streams, watermark kept.
 */
data class RingHubPassResult(
    val rows: Map<RingHubStream, Int>,
    val requests: Int,
    val pending: Set<RingHubStream> = emptySet(),
    val rejected: Map<RingHubStream, String> = emptyMap(),
) {
    val totalRows: Int get() = rows.values.sum()

    /** One Italian line for the "Server ring-hub" screen. */
    fun statusLine(): String {
        val parts = mutableListOf(
            if (totalRows == 0) "Nessun dato nuovo" else "Inviate $totalRows righe in $requests richieste",
        )
        if (pending.isNotEmpty()) parts += "Server non aggiornato: alcuni dati in attesa"
        if (rejected.isNotEmpty()) parts += "Dati rifiutati dal server: " + rejected.keys.joinToString { it.wireName }
        return parts.joinToString(" · ")
    }
}

/**
 * One delta pass to ring-hub.
 *
 * - **Watermarks move only after a request landed and was acknowledged**: the reply's `counts`
 *   must list the stream with at least the rows sent. A v0.1 server ignores the v0.2 lists and
 *   leaves them out of `counts`; those watermarks stay put ([RingHubPassResult.pending]).
 *   Moves are one chunk at a time, so a pass interrupted by
 *   the PC going offline resumes from the last chunk the server acknowledged. A failed [send]
 *   propagates and leaves everything after it for the next pass: the data stays on the phone.
 * - **Each pass re-reads [OVERLAP_MS] behind every watermark.** `createdAt`/`updatedAt` are stamped
 *   when an entity is built, not when its transaction commits, so a row can land with a stamp just
 *   below a watermark another row already advanced. The server upserts by id, so the overlap costs
 *   a few duplicate rows and nothing else. The same idempotence covers re-synced ring history,
 *   which keeps its id but gets a fresh `createdAt` and so reappears in the next delta.
 * - **Paging is keyset on (stamp, id)**, so rows sharing one millisecond — a history sync builds
 *   hundreds — can't be skipped at a chunk boundary.
 * - **A sleep session travels with all of its stage blocks**, never split across requests: the
 *   server treats the blocks it receives for a session as that night's complete set, and skips a
 *   block whose session it has not stored yet.
 */
class RingHubSync(
    private val source: RingHubSource,
    private val watermarks: RingHubWatermarkStore,
    private val appVersion: String,
    private val send: suspend (RingHubBatch) -> RingHubIngestResult,
    private val maxRowsPerRequest: Int = MAX_ROWS_PER_REQUEST,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val sent = mutableMapOf<RingHubStream, Int>()
    private val pending = mutableSetOf<RingHubStream>()
    private val rejected = mutableMapOf<RingHubStream, String>()
    private var requests = 0
    private var deviceId: String? = null

    suspend fun run(): RingHubPassResult {
        sent.clear()
        pending.clear()
        rejected.clear()
        requests = 0
        deviceId = source.deviceId()

        val devices = source.devicesUpdatedSince(startTs(RingHubStream.DEVICES))
        if (devices.isNotEmpty()) {
            if (post(RingHubStream.DEVICES, devices.size, devices.size, batch().copy(devices = devices.map { it.toDto() }))) {
                watermarks.advance(RingHubStream.DEVICES, devices.maxOf { it.updatedAt })
            }
        }
        pump(RingHubStream.MEASUREMENT_DELETIONS, source::deletionsAfter, { it.deletedAt }, { it.measurementId }) {
            batch().copy(measurementDeletions = it.map { row -> row.toDto() })
        }
        pump(RingHubStream.MEASUREMENTS, source::measurementsAfter, { it.createdAt }, { it.id }) {
            batch().copy(measurements = it.map { row -> row.toDto() })
        }
        pump(RingHubStream.ACTIVITY_DAILY, source::activityDailyAfter, { it.updatedAt }, { it.id }) {
            batch().copy(activityDaily = it.map { row -> row.toDto() })
        }
        pump(
            RingHubStream.ACTIVITY_BUCKETS,
            { ts, key, limit -> source.activityBucketsAfter(ts, key.toLongOrNull() ?: Long.MIN_VALUE, limit) },
            { it.updatedAt },
            { it.startEpoch.toString() },
        ) { batch().copy(activityBuckets = it.map { row -> row.toDto() }) }
        pump(RingHubStream.BATTERY_SAMPLES, source::batterySamplesAfter, { it.createdAt }, { it.id }) {
            batch().copy(batterySamples = it.map { row -> row.toDto() })
        }
        pumpSleep()
        pump(RingHubStream.ADAPTIVE_TRANSITIONS, source::adaptiveTransitionsAfter, { it.createdAt }, { it.id }) {
            batch().copy(adaptiveTransitions = it.map { row -> row.toDto() })
        }
        pump(RingHubStream.ACTIVITY_LABELS, source::activityLabelsAfter, { it.updatedAt }, { it.id }) {
            batch().copy(activityLabels = it.map { row -> row.toDto() })
        }
        pump(RingHubStream.DIARY_ENTRIES, source::diaryEntriesAfter, { it.updatedAt }, { it.id }) {
            batch().copy(diaryEntries = it.map { row -> row.toDto() })
        }
        pump(RingHubStream.PHONE_USAGE_HOURLY, source::phoneUsageAfter, { it.updatedAt }, { it.id }) {
            batch().copy(phoneUsageHourly = it.map { row -> row.toDto() })
        }
        pump(RingHubStream.ACTIVITY_TRANSITIONS, source::activityTransitionsAfter, { it.createdAt }, { it.id }) {
            batch().copy(activityTransitions = it.map { row -> row.toDto() })
        }
        // Zones before location points: the server rebuilds its places when zones arrive, so the
        // points that follow are assigned to the right zone straight away.
        pump(RingHubStream.ZONES, source::zonesAfter, { it.updatedAt }, { it.id }) {
            batch().copy(zones = it.map { row -> row.toDto() })
        }
        pump(RingHubStream.LOCATION_POINTS, source::locationPointsAfter, { it.createdAt }, { it.id }) {
            batch().copy(locationPoints = it.map { row -> row.toDto() })
        }
        return RingHubPassResult(sent.toMap(), requests, pending.toSet(), rejected.toMap())
    }

    private fun startTs(stream: RingHubStream): Long =
        watermarks.watermark(stream)?.let { it - OVERLAP_MS } ?: Long.MIN_VALUE

    private fun batch() = RingHubBatch(exportedAt = now(), appVersion = appVersion, deviceId = deviceId)

    /**
     * Sends [batch] and reports whether the server acknowledged [ackRows] rows of [stream]'s main
     * list. Unacknowledged streams are marked [pending]; the caller then keeps the watermark.
     */
    private suspend fun post(stream: RingHubStream, rows: Int, ackRows: Int, batch: RingHubBatch): Boolean {
        val reply = send(batch)
        requests++
        sent[stream] = (sent[stream] ?: 0) + rows
        val received = reply.counts[stream.wireName]?.received
        val acknowledged = received != null && received >= ackRows
        if (!acknowledged) pending += stream
        return acknowledged
    }

    private suspend fun <T> pump(
        stream: RingHubStream,
        fetch: suspend (Long, String, Int) -> List<T>,
        stampOf: (T) -> Long,
        keyOf: (T) -> String,
        toBatch: (List<T>) -> RingHubBatch,
    ) {
        if (!stream.sinceV02) return pumpChunks(stream, fetch, stampOf, keyOf, toBatch)
        // v0.2 streams are independent: a 4xx on one (a row the server's validation refuses)
        // skips that stream for this pass and lets the others through. Outages, auth and 5xx
        // still stop the whole pass, as for the v0.1 streams.
        try {
            pumpChunks(stream, fetch, stampOf, keyOf, toBatch)
        } catch (e: RingHubException) {
            if (e.kind != RingHubException.Kind.REJECTED) throw e
            rejected[stream] = e.message ?: "HTTP ${e.httpCode}"
        }
    }

    private suspend fun <T> pumpChunks(
        stream: RingHubStream,
        fetch: suspend (Long, String, Int) -> List<T>,
        stampOf: (T) -> Long,
        keyOf: (T) -> String,
        toBatch: (List<T>) -> RingHubBatch,
    ) {
        var ts = startTs(stream)
        var key = ""
        while (true) {
            val rows = fetch(ts, key, maxRowsPerRequest)
            if (rows.isEmpty()) return
            if (!post(stream, rows.size, rows.size, toBatch(rows))) return
            val last = rows.last()
            watermarks.advance(stream, stampOf(last))
            ts = stampOf(last)
            key = keyOf(last)
            if (rows.size < maxRowsPerRequest) return
        }
    }

    private suspend fun pumpSleep() {
        val stream = RingHubStream.SLEEP_SESSIONS
        var ts = startTs(stream)
        var key = ""
        while (true) {
            val sessions = source.sleepSessionsAfter(ts, key, maxRowsPerRequest)
            if (sessions.isEmpty()) return
            val blocks = source.stageBlocksFor(sessions.map { it.id }).groupBy { it.sessionId }
            for (group in sleepChunks(sessions, blocks, maxRowsPerRequest)) {
                val groupBlocks = group.flatMap { blocks[it.id].orEmpty() }
                val acknowledged = post(
                    stream,
                    group.size + groupBlocks.size,
                    group.size,
                    batch().copy(
                        sleepSessions = group.map { it.toDto() },
                        sleepStageBlocks = groupBlocks.map { it.toDto() },
                    ),
                )
                if (!acknowledged) return
                watermarks.advance(stream, group.last().updatedAt)
            }
            val last = sessions.last()
            ts = last.updatedAt
            key = last.id
            if (sessions.size < maxRowsPerRequest) return
        }
    }

    companion object {
        /** Rows per request, sessions and their blocks counted together. */
        const val MAX_ROWS_PER_REQUEST = 2000

        /** How far behind each watermark a pass re-reads; see the class KDoc. */
        const val OVERLAP_MS = 2 * 60_000L

        /**
         * Splits [sessions] (in send order) into requests of at most [maxRows] rows, counting each
         * session plus its blocks. A session is never split from its blocks; one whose blocks alone
         * exceed [maxRows] goes in a request of its own.
         */
        internal fun sleepChunks(
            sessions: List<SleepSessionEntity>,
            blocks: Map<String, List<SleepStageBlockEntity>>,
            maxRows: Int,
        ): List<List<SleepSessionEntity>> {
            val chunks = mutableListOf<List<SleepSessionEntity>>()
            var current = mutableListOf<SleepSessionEntity>()
            var rows = 0
            for (session in sessions) {
                val weight = 1 + blocks[session.id].orEmpty().size
                if (current.isNotEmpty() && rows + weight > maxRows) {
                    chunks += current
                    current = mutableListOf()
                    rows = 0
                }
                current += session
                rows += weight
            }
            if (current.isNotEmpty()) chunks += current
            return chunks
        }
    }
}
