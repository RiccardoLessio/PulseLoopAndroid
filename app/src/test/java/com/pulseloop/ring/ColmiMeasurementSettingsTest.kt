package com.pulseloop.ring

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * iOS #19 parity: per-device measurement config + profile-backed user preferences.
 * Pure — no hardware; a recording writer captures the enqueued commands.
 */
class ColmiMeasurementSettingsTest {

    private class RecordingWriter : RingCommandWriter {
        val commands = mutableListOf<ByteArray>()
        override fun enqueue(command: ByteArray) { commands.add(command) }
    }

    // MARK: Encoder

    @Test
    fun `temp pref write mirrors the extra 0x03 framing byte`() {
        // Full 6-field record (QRing SugarLipidsSettingReq.getWriteInstance((byte)3, …)):
        // enable, interval 30, start 5, remind-interval 10, alert bits 0, custom byte 0xB9
        // (38.5 °C, inert with bits=0). The old 4-byte truncated form read as interval=0 on
        // RT-series firmware — ACKed but never armed background sampling.
        assertArrayEquals(
            byteArrayOf(0x3A, 0x03, 0x02, 0x01, 30, 5, 10, 0x00, 0xB9.toByte()),
            ColmiEncoder.writeTempPref(enabled = true))
        assertArrayEquals(
            byteArrayOf(0x3A, 0x03, 0x02, 0x00, 30, 5, 10, 0x00, 0xB9.toByte()),
            ColmiEncoder.writeTempPref(enabled = false))
    }

    @Test
    fun `temp pref write carries the requested interval on the 5-minute grid`() {
        assertArrayEquals(
            byteArrayOf(0x3A, 0x03, 0x02, 0x01, 5, 5, 10, 0x00, 0xB9.toByte()),
            ColmiEncoder.writeTempPref(enabled = true, intervalMinutes = 5))
        assertEquals(15.toByte(), ColmiEncoder.writeTempPref(enabled = true, intervalMinutes = 17)[4])
        assertEquals(5.toByte(), ColmiEncoder.writeTempPref(enabled = true, intervalMinutes = 1)[4])
        assertEquals(60.toByte(), ColmiEncoder.writeTempPref(enabled = true, intervalMinutes = 240)[4])
    }

    // MARK: UserProfileValues mapping

    @Test
    fun `profile values map sex to the colmi gender byte`() {
        assertEquals(0x00u.toUByte(), UserProfileValues.from(true, "Female", 30, 165.0, 60.0).gender)
        assertEquals(0x01u.toUByte(), UserProfileValues.from(true, "male", 30, 180.0, 80.0).gender)
        assertEquals(0x02u.toUByte(), UserProfileValues.from(true, null, 30, 180.0, 80.0).gender)
    }

    @Test
    fun `profile values clamp to byte ranges with neutral fallbacks`() {
        val defaults = UserProfileValues.from(metric = true, sex = null, age = null, heightCm = null, weightKg = null)
        assertEquals(25u.toUByte(), defaults.age)
        assertEquals(175u.toUByte(), defaults.heightCm)
        assertEquals(70u.toUByte(), defaults.weightKg)
        val clamped = UserProfileValues.from(metric = false, sex = "male", age = 300, heightCm = 300.0, weightKg = 300.0)
        assertEquals(255u.toUByte(), clamped.age)
        assertEquals(255u.toUByte(), clamped.heightCm)
        assertEquals(255u.toUByte(), clamped.weightKg)
    }

    // MARK: Sync engine

    @Test
    fun `startup emits the configured HR interval and vital toggles`() {
        val writer = RecordingWriter()
        val engine = ColmiSyncEngine(writer, ColmiDecoder)
        engine.setMeasurementSettings(MeasurementSettings(
            hrEnabled = true, hrIntervalMinutes = 15,
            spo2Enabled = true, stressEnabled = false, hrvEnabled = true, temperatureEnabled = false,
        ))
        engine.runStartup()
        engine.destroy()

        val autoHr = writer.commands.first { it[0] == 0x16.toByte() && it.size == 8 && it[1] == 0x02.toByte() }
        assertEquals(0x01.toByte(), autoHr[2])       // enabled
        assertEquals(15.toByte(), autoHr[3])         // configured interval
        val stress = writer.commands.first { it[0] == 0x36.toByte() && it[1] == 0x02.toByte() }
        assertEquals(0x00.toByte(), stress[2])       // stress off
        val temp = writer.commands.first { it[0] == 0x3A.toByte() && it.size == 9 && it[2] == 0x02.toByte() }
        assertEquals(0x00.toByte(), temp[3])         // temperature off
    }

    @Test
    fun `startup with no persisted config writes the 5-minute defaults`() {
        val writer = RecordingWriter()
        val engine = ColmiSyncEngine(writer, ColmiDecoder)
        engine.setMeasurementSettings(null)
        engine.runStartup()
        engine.destroy()

        // The ring's previous settings are still read (0x16 0x01 / 0x3A 0x03 0x01) for the log...
        assertTrue(writer.commands.any { it[0] == 0x16.toByte() && it[1] == 0x01.toByte() })
        assertTrue(writer.commands.any { it[0] == 0x3A.toByte() && it[2] == 0x01.toByte() })
        // ...but the defaults are written: all-day HR on every 5 min, temperature on every 5 min.
        val autoHr = writer.commands.single { it[0] == 0x16.toByte() && it[1] == 0x02.toByte() }
        assertEquals(0x01.toByte(), autoHr[2])
        assertEquals(5.toByte(), autoHr[3])
        val temp = writer.commands.single { it[0] == 0x3A.toByte() && it[2] == 0x02.toByte() }
        assertEquals(0x01.toByte(), temp[3])
        assertEquals(5.toByte(), temp[4])
        // SpO2/stress/HRV on — the history sync depends on them.
        for (pref in byteArrayOf(0x2C, 0x36, 0x38)) {
            val write = writer.commands.first { it[0] == pref && it[1] == 0x02.toByte() }
            assertEquals(0x01.toByte(), write[2])
        }
    }

    @Test
    fun `ring pref replies do not override the written defaults`() {
        val writer = RecordingWriter()
        val engine = ColmiSyncEngine(writer, ColmiDecoder)
        engine.setMeasurementSettings(null)
        engine.runStartup()
        // Ring reports all-day HR off at a 60-minute interval (read reply: 0x16 0x01 0x02 60).
        engine.handleRawNotify(ColmiPacket.frame(byteArrayOf(0x16, 0x01, 0x02, 60)))
        engine.destroy()

        val autoHr = writer.commands.filter { it[0] == 0x16.toByte() && it[1] == 0x02.toByte() }
        assertEquals(1, autoHr.size)
        assertEquals(5.toByte(), autoHr[0][3])
    }

    @Test
    fun `no persisted config seeds the defaults immediately`() {
        val writer = RecordingWriter()
        val engine = ColmiSyncEngine(writer, ColmiDecoder)
        var seeded: MeasurementSettings? = null
        engine.setMeasurementSettings(null)
        engine.setOnMeasurementConfigSeeded { seeded = it }
        engine.runStartup()
        engine.destroy()

        val settings = seeded!!
        assertEquals(MeasurementSettings.ALL_ON_DEFAULT, settings)
        assertEquals(5, settings.hrIntervalMinutes)
        assertEquals(5, settings.temperatureIntervalMinutes)
        assertTrue(settings.hrEnabled && settings.spo2Enabled && settings.stressEnabled && settings.hrvEnabled)
        assertTrue(settings.temperatureEnabled)
    }

    @Test
    fun `seeding ignores replies when a persisted config exists`() {
        val writer = RecordingWriter()
        val engine = ColmiSyncEngine(writer, ColmiDecoder)
        var seeded: MeasurementSettings? = null
        engine.setMeasurementSettings(MeasurementSettings.ALL_ON_DEFAULT)
        engine.setOnMeasurementConfigSeeded { seeded = it }
        engine.runStartup()
        engine.handleRawNotify(ColmiPacket.frame(byteArrayOf(0x16, 0x01, 0x02, 60)))
        engine.handleRawNotify(ColmiPacket.frame(byteArrayOf(0x3A, 0x03, 0x01, 0x00)))
        engine.destroy()

        assertEquals(null, seeded)
        // The persisted config was written exactly once — the reply didn't add a second write.
        assertEquals(1, writer.commands.count { it[0] == 0x16.toByte() && it[1] == 0x02.toByte() })
    }

    @Test
    fun `apply pushes all five measurement commands immediately, then reads temp back`() {
        val writer = RecordingWriter()
        val engine = ColmiSyncEngine(writer, ColmiDecoder)
        engine.applyMeasurementSettings(MeasurementSettings.ALL_ON_DEFAULT)
        engine.destroy()

        assertEquals(6, writer.commands.size)
        assertEquals(0x16.toByte(), writer.commands[0][0])  // auto-HR
        assertEquals(0x2C.toByte(), writer.commands[1][0])  // SpO2 pref
        assertEquals(0x36.toByte(), writer.commands[2][0])  // stress pref
        assertEquals(0x38.toByte(), writer.commands[3][0])  // HRV pref
        assertEquals(0x3A.toByte(), writer.commands[4][0])  // temp pref write
        assertArrayEquals(ColmiEncoder.readTempPref(), writer.commands[5])  // temp read-back
    }

    // MARK: Temperature read-back

    private fun tempWrites(writer: RecordingWriter) =
        writer.commands.filter { it[0] == 0x3A.toByte() && it[2] == 0x02.toByte() }

    private fun tempReply(enabled: Int, interval: Int) =
        ColmiPacket.frame(byteArrayOf(0x3A, 0x03, 0x01, enabled.toByte(), interval.toByte(), 5, 10))

    @Test
    fun `temp read-back that echoes the interval is recorded as applied`() {
        val writer = RecordingWriter()
        val engine = ColmiSyncEngine(writer, ColmiDecoder)
        engine.applyMeasurementSettings(MeasurementSettings.ALL_ON_DEFAULT)
        engine.handleRawNotify(tempReply(enabled = 1, interval = 5))
        engine.destroy()

        val status = ColmiTempPrefReadback.status.value!!
        assertEquals(ColmiTempPrefReadback.Verdict.APPLIED, status.verdict)
        assertEquals(5, status.reportedMinutes)
        assertEquals(1, tempWrites(writer).size)  // no retry
    }

    @Test
    fun `startup read before the write is not taken as the read-back`() {
        val writer = RecordingWriter()
        val engine = ColmiSyncEngine(writer, ColmiDecoder)
        engine.setMeasurementSettings(MeasurementSettings.ALL_ON_DEFAULT)
        engine.runStartup()
        // First reply answers the handshake's pre-write read: the ring was still at 30, off.
        engine.handleRawNotify(tempReply(enabled = 0, interval = 30))
        assertEquals(1, tempWrites(writer).size)  // the pre-write state triggers no retry
        // Second reply is the read-back of the 5-minute write.
        engine.handleRawNotify(tempReply(enabled = 1, interval = 5))
        engine.destroy()

        assertEquals(ColmiTempPrefReadback.Verdict.APPLIED, ColmiTempPrefReadback.status.value!!.verdict)
    }

    @Test
    fun `ring that clamps the interval is accepted without a retry`() {
        val writer = RecordingWriter()
        val engine = ColmiSyncEngine(writer, ColmiDecoder)
        engine.applyMeasurementSettings(MeasurementSettings.ALL_ON_DEFAULT)
        engine.handleRawNotify(tempReply(enabled = 1, interval = 30))
        engine.destroy()

        val status = ColmiTempPrefReadback.status.value!!
        assertEquals(ColmiTempPrefReadback.Verdict.CLAMPED, status.verdict)
        assertEquals(30, status.reportedMinutes)
        assertEquals(5, status.requestedMinutes)
        assertEquals(1, tempWrites(writer).size)
    }

    @Test
    fun `ring that switches temperature off gets one retry at the firmware default`() {
        val writer = RecordingWriter()
        val engine = ColmiSyncEngine(writer, ColmiDecoder)
        engine.applyMeasurementSettings(MeasurementSettings.ALL_ON_DEFAULT)
        engine.handleRawNotify(tempReply(enabled = 0, interval = 0))
        assertEquals(ColmiTempPrefReadback.Verdict.DISABLED_BY_RING, ColmiTempPrefReadback.status.value!!.verdict)
        val writes = tempWrites(writer)
        assertEquals(2, writes.size)
        assertEquals(30.toByte(), writes[1][4])
        assertArrayEquals(ColmiEncoder.readTempPref(), writer.commands.last())

        // The retry's read-back settles it; a second refusal does not loop.
        engine.handleRawNotify(tempReply(enabled = 0, interval = 0))
        engine.destroy()
        assertTrue(ColmiTempPrefReadback.status.value!!.fellBack)
        assertEquals(2, tempWrites(writer).size)
    }

    @Test
    fun `rejected temp write retries and its stale read-back is ignored`() {
        val writer = RecordingWriter()
        val engine = ColmiSyncEngine(writer, ColmiDecoder)
        engine.applyMeasurementSettings(MeasurementSettings.ALL_ON_DEFAULT)
        engine.handleRawNotify(ColmiPacket.frame(byteArrayOf(0xBA.toByte(), 0x03)))  // error flag
        assertEquals(ColmiTempPrefReadback.Verdict.REJECTED, ColmiTempPrefReadback.status.value!!.verdict)
        assertEquals(30.toByte(), tempWrites(writer)[1][4])

        // The read queued behind the rejected write answers first, with the ring's old state:
        // it must not settle the retry.
        engine.handleRawNotify(tempReply(enabled = 0, interval = 0))
        assertEquals(ColmiTempPrefReadback.Verdict.REJECTED, ColmiTempPrefReadback.status.value!!.verdict)
        // The retry's own read-back does.
        engine.handleRawNotify(tempReply(enabled = 1, interval = 30))
        engine.destroy()
        val status = ColmiTempPrefReadback.status.value!!
        assertEquals(ColmiTempPrefReadback.Verdict.APPLIED, status.verdict)
        assertTrue(status.fellBack)
        assertEquals(2, tempWrites(writer).size)
    }

    @Test
    fun `read-back without an interval byte is not reported, not a failure`() {
        val request = ColmiTempPrefReadback.Request(enabled = true, intervalMinutes = 5, fallback = false)
        val verdict = ColmiTempPrefReadback.evaluate(request, ColmiTempPrefReadout(enabled = true, intervalMinutes = null))
        assertEquals(ColmiTempPrefReadback.Verdict.NOT_REPORTED, verdict)
        assertTrue(!ColmiTempPrefReadback.needsFallback(request, verdict))
        // Already at the firmware default: nothing to retry with.
        val atDefault = request.copy(intervalMinutes = 30)
        assertTrue(!ColmiTempPrefReadback.needsFallback(atDefault, ColmiTempPrefReadback.Verdict.REJECTED))
    }

    @Test
    fun `user profile feeds the preferences command on startup`() {
        val writer = RecordingWriter()
        val engine = ColmiSyncEngine(writer, ColmiDecoder)
        engine.setUserProfile(UserProfileValues.from(
            metric = false, sex = "female", age = 42, heightCm = 160.0, weightKg = 55.0,
        ))
        engine.runStartup()
        engine.destroy()

        val prefs = writer.commands.first { it[0] == 0x0A.toByte() }
        assertEquals(0x01.toByte(), prefs[3])  // imperial flag
        assertEquals(0x00.toByte(), prefs[4])  // female
        assertEquals(42.toByte(), prefs[5])
        assertEquals(160.toByte(), prefs[6])
        assertEquals(55.toByte(), prefs[7])
    }

    @Test
    fun `colmi declares the measurement interval capability, jring does not`() {
        assertTrue(ColmiCoordinator.capabilities.contains(WearableCapability.MEASUREMENT_INTERVAL))
        assertTrue(!JringCoordinator.capabilities.contains(WearableCapability.MEASUREMENT_INTERVAL))
    }

    // MARK: Capability-gated bonding (QRing supportBlePair)

    @Test
    fun `startup reads the device-support capability bitfield`() {
        val writer = RecordingWriter()
        val engine = ColmiSyncEngine(writer, ColmiDecoder)
        engine.setMeasurementSettings(null)
        engine.runStartup()
        engine.destroy()

        assertTrue(writer.commands.any { it[0] == 0x3C.toByte() })  // DeviceSupportReq
    }

    @Test
    fun `supportBlePair reply requests a bond, non-support reply does not`() {
        val writer = RecordingWriter()
        val engine = ColmiSyncEngine(writer, ColmiDecoder)
        var bondRequests = 0
        engine.setOnBondRequested { bondRequests++ }

        // Feature byte (full-frame [2] — QRing rsp classes see the opcode-stripped slice, so
        // their bArr[1] is full-frame byte 2) with bit 3 set → bond wanted.
        engine.handleRawNotify(ColmiPacket.frame(byteArrayOf(0x3C, 0x00, 0x08)))
        assertEquals(1, bondRequests)

        // Feature byte without bit 3 → no bond.
        engine.handleRawNotify(ColmiPacket.frame(byteArrayOf(0x3C, 0x00, 0x07)))
        assertEquals(1, bondRequests)
        engine.destroy()
    }
}
