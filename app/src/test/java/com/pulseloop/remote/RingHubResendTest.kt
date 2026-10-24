package com.pulseloop.remote

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RingHubResendTest {

    /** Minimal in-memory SharedPreferences (strings only is all the store uses). */
    private class FakePrefs : SharedPreferences {
        val data = mutableMapOf<String, Any?>()
        override fun getAll(): MutableMap<String, *> = data
        override fun getString(key: String, defValue: String?) = data[key] as String? ?: defValue
        override fun getStringSet(key: String, defValues: MutableSet<String>?) = defValues
        override fun getInt(key: String, defValue: Int) = defValue
        override fun getLong(key: String, defValue: Long) = defValue
        override fun getFloat(key: String, defValue: Float) = defValue
        override fun getBoolean(key: String, defValue: Boolean) = defValue
        override fun contains(key: String) = data.containsKey(key)
        override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            override fun putString(key: String, value: String?) = apply { data[key] = value }
            override fun remove(key: String) = apply { data.remove(key) }
            override fun clear() = apply { data.clear() }
            override fun apply() {}
            override fun commit() = true
            override fun putStringSet(key: String, values: MutableSet<String>?) = this
            override fun putInt(key: String, value: Int) = this
            override fun putLong(key: String, value: Long) = this
            override fun putFloat(key: String, value: Float) = this
            override fun putBoolean(key: String, value: Boolean) = this
        }
    }

    @Test
    fun resetClearsEveryStreamWatermark() {
        val raw = FakePrefs()
        val store = RingHubPrefsStore(raw)
        RingHubStream.values().forEachIndexed { i, s -> store.advance(s, 100L + i) }
        RingHubStream.values().forEach { assertTrue(store.watermark(it) != null) }

        store.resetForResend()

        RingHubStream.values().forEach { assertNull("${it.wireName} not cleared", store.watermark(it)) }
        // survives a reload from the persisted blob
        RingHubStream.values().forEach { assertNull(RingHubPrefsStore(raw).watermark(it)) }
        // watermarks can advance again from scratch
        store.advance(RingHubStream.MEASUREMENTS, 5)
        assertEquals(5L, store.watermark(RingHubStream.MEASUREMENTS))
    }

    @Test
    fun resetKeepsSettingsAndOnlyFlagsStatus() {
        val store = RingHubPrefsStore(FakePrefs())
        store.update { it.copy(enabled = true, serverUrl = "http://a:1", lastSuccessAt = 42L, lastOk = true) }
        store.advance(RingHubStream.ZONES, 9)

        store.resetForResend()

        assertTrue(store.current.enabled)
        assertEquals("http://a:1", store.current.serverUrl)
        assertEquals(42L, store.current.lastSuccessAt)
        assertEquals("Reinvio completo richiesto", store.current.lastStatus)
    }

    @Test
    fun resetWritesOnlyRingHubKeys() {
        // Local data lives in Room, never in this store: the reset only touches the two ring-hub keys.
        val raw = FakePrefs()
        raw.data["unrelated"] = "keep"
        val store = RingHubPrefsStore(raw)
        store.advance(RingHubStream.DEVICES, 1)
        store.resetForResend()
        assertEquals("keep", raw.data["unrelated"])
        assertFalse(raw.data.containsKey("watermarks"))
    }

    @Test
    fun serverChangeDetection() {
        assertFalse(RingHubPrefsStore.isServerChange("", "http://a:1"))
        assertFalse(RingHubPrefsStore.isServerChange("http://a:1", "http://a:1"))
        assertTrue(RingHubPrefsStore.isServerChange("http://a:1", "http://b:1"))
    }
}
