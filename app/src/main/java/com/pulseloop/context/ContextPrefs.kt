package com.pulseloop.context

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** "Contesto" settings. Everything that reads the phone is opt-in. */
@Serializable
data class ContextSettings(
    /** Screen time via UsageStatsManager (needs Usage Access). */
    val usageEnabled: Boolean = false,
    /** Automatic location tracking (needs location "always"). */
    val locationEnabled: Boolean = false,
    /** Activity Recognition transitions (needs physical-activity permission + Play Services). */
    val activityEnabled: Boolean = false,
)

class ContextPrefs private constructor(private val prefs: SharedPreferences) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val placesSerializer = ListSerializer(AutoPlace.serializer())

    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<ContextSettings> = _settings.asStateFlow()

    private val _places = MutableStateFlow(loadPlaces())
    /** ring-hub's auto-detected places, refreshed after each sync pass. */
    val places: StateFlow<List<AutoPlace>> = _places.asStateFlow()

    fun update(transform: (ContextSettings) -> ContextSettings) = synchronized(this) {
        val next = transform(_settings.value)
        _settings.value = next
        prefs.edit().putString(KEY_SETTINGS, json.encodeToString(ContextSettings.serializer(), next)).apply()
    }

    fun savePlaces(places: List<AutoPlace>) = synchronized(this) {
        _places.value = places
        prefs.edit().putString(KEY_PLACES, json.encodeToString(placesSerializer, places)).apply()
    }

    /** Last processed UsageStats window end (epoch ms). */
    var usageProcessedUntil: Long
        get() = prefs.getLong(KEY_USAGE_UNTIL, 0L)
        set(value) { prefs.edit().putLong(KEY_USAGE_UNTIL, value).apply() }

    private fun load(): ContextSettings = prefs.getString(KEY_SETTINGS, null)?.let {
        runCatching { json.decodeFromString(ContextSettings.serializer(), it) }.getOrNull()
    } ?: ContextSettings()

    private fun loadPlaces(): List<AutoPlace> = prefs.getString(KEY_PLACES, null)?.let {
        runCatching { json.decodeFromString(placesSerializer, it) }.getOrNull()
    } ?: emptyList()

    companion object {
        private const val FILE = "phone_context"
        private const val KEY_SETTINGS = "settings"
        private const val KEY_PLACES = "auto_places"
        private const val KEY_USAGE_UNTIL = "usage_until"

        @Volatile private var instance: ContextPrefs? = null

        fun get(context: Context): ContextPrefs = instance ?: synchronized(this) {
            instance ?: ContextPrefs(context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE))
                .also { instance = it }
        }
    }
}
