package com.pulseloop.ring

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What a Colmi ring actually did with the last all-day temperature write.
 *
 * The `0x3A` record carries an interval byte, but the firmware default is 30 minutes and it is not
 * known whether every firmware (the R09's RT09 build in particular) honours less. So after every
 * write [ColmiSyncEngine] reads the pref back and records the outcome here; the Measurement
 * settings screen shows it next to the temperature slider. Process-lifetime only: a fresh
 * connection re-writes and re-reads it.
 */
object ColmiTempPrefReadback {

    enum class Verdict {
        /** The ring reports the interval (and on/off state) that was written. */
        APPLIED,
        /** The ring is on, but reports a different interval than the one written. */
        CLAMPED,
        /** The ring is on, but its read reply carries no interval byte. */
        NOT_REPORTED,
        /** Temperature was asked on, and the ring reports it off. */
        DISABLED_BY_RING,
        /** The ring answered the write with an error frame. */
        REJECTED,
    }

    data class Status(
        val requestedEnabled: Boolean,
        val requestedMinutes: Int,
        val reportedEnabled: Boolean?,
        val reportedMinutes: Int?,
        val verdict: Verdict,
        /** True when this status follows the one-shot retry at the firmware default (30 min). */
        val fellBack: Boolean,
        val at: Long,
    )

    /** A temperature write waiting for its read-back. */
    internal data class Request(val enabled: Boolean, val intervalMinutes: Int, val fallback: Boolean)

    private val _status = MutableStateFlow<Status?>(null)
    val status: StateFlow<Status?> = _status.asStateFlow()

    internal fun publish(status: Status) {
        _status.value = status
    }

    /** The interval [ColmiEncoder.writeTempPref] actually puts on the wire for [minutes]. */
    internal fun wireMinutes(minutes: Int): Int = ((minutes / 5) * 5).coerceIn(5, 60)

    /** Judge a read-back against the write it follows. Pure, so it is unit-tested directly. */
    internal fun evaluate(request: Request, readout: ColmiTempPrefReadout): Verdict = when {
        !request.enabled -> if (readout.enabled) Verdict.CLAMPED else Verdict.APPLIED
        !readout.enabled -> Verdict.DISABLED_BY_RING
        readout.intervalMinutes == null -> Verdict.NOT_REPORTED
        readout.intervalMinutes == wireMinutes(request.intervalMinutes) -> Verdict.APPLIED
        else -> Verdict.CLAMPED
    }

    /**
     * Whether to retry once at the firmware default: only when the ring refused the write outright
     * or switched temperature off, and the request was not already that retry or already at 30.
     */
    internal fun needsFallback(request: Request, verdict: Verdict): Boolean =
        !request.fallback && request.enabled &&
            wireMinutes(request.intervalMinutes) != ColmiEncoder.FIRMWARE_TEMP_INTERVAL_MINUTES &&
            (verdict == Verdict.REJECTED || verdict == Verdict.DISABLED_BY_RING)
}
