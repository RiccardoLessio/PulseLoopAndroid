package com.pulseloop.data

import androidx.room.withTransaction
import com.pulseloop.data.entity.SleepStageBlockEntity
import com.pulseloop.ring.RingDeviceType
import java.time.Instant
import java.time.ZoneId

/**
 * Removes the sleep a CRP ring's evening syncs stored one day early, before `CRPDecoder.decodeSleep`
 * learned that the ring's sleep day turns over at 20:00 (see AGENTS.md, "A CRP sleep day turns over
 * at 20:00, not midnight").
 *
 * Every sync after 8 PM filed that morning's night 24 h early. On a ring's first night that made a
 * whole night on a day the ring wasn't worn; on every later one the copy landed on the night before,
 * and the next morning's re-sync replaced only the real record's own span — so each night kept the
 * next night's earlier start and later end, and read long. The decoder fix stops new copies; this
 * removes the ones already stored, once.
 *
 * **What identifies a copy.** A block remembers the start of the ring record it came from
 * (`recordStartAt`, issue #68), and a copy keeps its source's, moved back by a day. So record R is a
 * copy of record G when G started one calendar day after R — 24 h, or 23/25 h across a DST change,
 * since the decoder anchors on local midnight — and **every** block of R lies inside a block of G
 * with the same stage once moved forward by that same amount. The re-sync that trimmed a copy only
 * ever cut it shorter, so a surviving fragment still fits inside its source. All or nothing per
 * record: two genuine nights that merely start at the same minute have different stage sequences and
 * are left alone. The earlier record of a pair is the copy, never the later one.
 *
 * **No tombstones.** With the decoder fixed the copies can't come back, and a tombstone keyed on
 * these block starts would sit waiting to suppress whatever genuine sleep later lands on them.
 */
object SleepDayEarlyCopies {

    /**
     * Find and remove every stored copy, restating each session it touched from what remains.
     * Returns the number of blocks removed, or null when no ring is paired (the caller retries then).
     * Idempotent: a second run finds nothing.
     *
     * **CRP rings only.** Stored sleep doesn't say which ring wrote it, and the match is not unique to
     * copies: a Jring stores every 15-minute `0x11` packet as its own record, so an all-LIGHT quarter
     * hour at 02:15 on Monday "fits inside" the one at 02:15 on Tuesday and would be deleted — every
     * single-stage quarter hour of every night but the newest (review of #110). Only a CRP ring made
     * these copies, so an install whose paired ring is anything else is left alone.
     */
    suspend fun repair(db: PulseLoopDatabase, zone: ZoneId = ZoneId.systemDefault()): Int? = db.withTransaction {
        val device = db.deviceDao().currentReal() ?: return@withTransaction null
        if (device.deviceTypeRaw != RingDeviceType.CRP.name) return@withTransaction 0
        val sessions = db.sleepSessionDao().all().filter { it.sourceRaw !in DEMO_SOURCES }
        if (sessions.isEmpty()) return@withTransaction 0
        val blocks = db.sleepStageBlockDao().forSessions(sessions.map { it.id })
        val strays = strayBlocks(blocks, zone)
        if (strays.isEmpty()) return@withTransaction 0
        val bySession = blocks.groupBy { it.sessionId }
        for (session in sessions) {
            val own = bySession[session.id] ?: continue
            if (own.none { it in strays }) continue
            SleepRecordDeletion.restateSession(db, session, own.filterNot { it in strays })
        }
        strays.size
    }

    /** The blocks that belong to a record copied a day early. Pure; see the class KDoc for the rule. */
    internal fun strayBlocks(
        blocks: List<SleepStageBlockEntity>,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Set<SleepStageBlockEntity> {
        // `recordStartAt == 0` is a pre-#68 row with no record identity, so it can't be matched.
        val records = blocks.filter { it.recordStartAt > 0L }.groupBy { it.recordStartAt }
        val strays = mutableSetOf<SleepStageBlockEntity>()
        for ((recordStart, recordBlocks) in records) {
            val isCopy = dayShifts(recordStart, zone).any { shift ->
                val source = records[recordStart + shift] ?: return@any false
                recordBlocks.all { block -> source.any { it.contains(block, shift) } }
            }
            if (isCopy) strays += recordBlocks
        }
        return strays
    }

    /**
     * The shifts one calendar day can be from a copy's start to its source's. The decoder anchors a
     * night on a local midnight plus its start offset (up to a day either way), so the shift is the
     * length of the local day holding the copy's anchor midnight: the start's own day or the next.
     * That is 24 h except across a DST change, so 23/25 h are tried only where one really falls —
     * not on every night, where they would widen the false-match window for nothing.
     */
    private fun dayShifts(recordStart: Long, zone: ZoneId): Set<Long> {
        val date = Instant.ofEpochMilli(recordStart).atZone(zone).toLocalDate()
        fun length(d: java.time.LocalDate) =
            d.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - d.atStartOfDay(zone).toInstant().toEpochMilli()
        return setOf(length(date), length(date.plusDays(1)), length(date.minusDays(1)))
    }

    /** Whether [block], moved forward by [shift], lies inside this block with the same stage. */
    private fun SleepStageBlockEntity.contains(block: SleepStageBlockEntity, shift: Long): Boolean {
        val start = block.startAt + shift
        return stageRaw == block.stageRaw &&
            startAt <= start &&
            startAt + durationMinutes * 60_000L >= start + block.durationMinutes * 60_000L
    }

    private val DEMO_SOURCES = setOf("demo", "mock")
}
