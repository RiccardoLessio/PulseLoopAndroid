package com.pulseloop.remote

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/** ring-hub sync settings and the last pass's outcome. Not secret; the token lives in [RingHubTokenStore]. */
@Serializable
data class RingHubPrefs(
    val enabled: Boolean = false,
    /** Normalized base URL, e.g. `http://pc-casa:8765`. */
    val serverUrl: String = "",
    val lastAttemptAt: Long? = null,
    val lastSuccessAt: Long? = null,
    /** True when the last pass completed, false when it stopped on an error. */
    val lastOk: Boolean? = null,
    val lastStatus: String? = null,
)

/**
 * Plain shared-prefs store for [RingHubPrefs] (a JSON blob behind a StateFlow) plus the per-stream
 * watermarks under a second key, so the frequent mid-pass watermark writes don't rewrite the
 * settings blob. Same shape as [com.pulseloop.health.HealthConnectPrefsStore].
 */
class RingHubPrefsStore internal constructor(private val prefsStore: SharedPreferences) : RingHubWatermarkStore {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val marksSerializer = MapSerializer(String.serializer(), Long.serializer())
    private val writeLock = Any()

    private val _prefs = MutableStateFlow(load())
    val prefs: StateFlow<RingHubPrefs> = _prefs.asStateFlow()
    val current: RingHubPrefs get() = _prefs.value

    private var marks: Map<String, Long> = loadMarks()

    fun update(transform: (RingHubPrefs) -> RingHubPrefs) {
        synchronized(writeLock) {
            val next = transform(_prefs.value)
            if (next == _prefs.value) return
            _prefs.value = next
            prefsStore.edit().putString(KEY_PREFS, json.encodeToString(RingHubPrefs.serializer(), next)).apply()
        }
    }

    override fun watermark(stream: RingHubStream): Long? = synchronized(writeLock) { marks[stream.wireName] }

    override fun advance(stream: RingHubStream, value: Long) {
        synchronized(writeLock) {
            val existing = marks[stream.wireName]
            if (existing != null && value <= existing) return
            marks = marks + (stream.wireName to value)
            prefsStore.edit().putString(KEY_WATERMARKS, json.encodeToString(marksSerializer, marks)).apply()
        }
    }

    /** Forget every watermark: the next pass re-sends all history (the server upserts by id). */
    fun clearWatermarks() {
        synchronized(writeLock) {
            marks = emptyMap()
            prefsStore.edit().remove(KEY_WATERMARKS).apply()
        }
    }

    private fun load(): RingHubPrefs {
        val raw = prefsStore.getString(KEY_PREFS, null) ?: return RingHubPrefs()
        return try { json.decodeFromString(RingHubPrefs.serializer(), raw) } catch (_: Exception) { RingHubPrefs() }
    }

    private fun loadMarks(): Map<String, Long> {
        val raw = prefsStore.getString(KEY_WATERMARKS, null) ?: return emptyMap()
        return try { json.decodeFromString(marksSerializer, raw) } catch (_: Exception) { emptyMap() }
    }

    companion object {
        private const val FILE = "ringhub_sync"
        private const val KEY_PREFS = "prefs"
        private const val KEY_WATERMARKS = "watermarks"

        @Volatile private var instance: RingHubPrefsStore? = null

        fun get(context: Context): RingHubPrefsStore = instance ?: synchronized(this) {
            instance ?: RingHubPrefsStore(
                context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE),
            ).also { instance = it }
        }
    }
}

/** The ring-hub bearer token, in the app's existing EncryptedSharedPreferences file. */
class RingHubTokenStore(context: Context) {
    private val prefs = EncryptedSharedPreferences.create(
        "pulseloop_secure",
        MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC),
        context.applicationContext,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    var token: String
        get() = prefs.getString(KEY_TOKEN, "") ?: ""
        set(value) { prefs.edit().putString(KEY_TOKEN, value.trim()).apply() }

    private companion object {
        const val KEY_TOKEN = "ringhub_token"
    }
}
