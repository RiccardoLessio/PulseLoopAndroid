package com.pulseloop.adaptive

import android.content.Context
import android.content.SharedPreferences
import com.pulseloop.labels.LabelPromptPolicy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** User settings for adaptive sampling and the "Cosa stai facendo?" prompt. */
@Serializable
data class AdaptiveSettings(
    /** Off by default: escalation needs the app to run unrestricted in the background. */
    val enabled: Boolean = false,
    val dailyBudgetMinutes: Int = 90,
    val labelPromptsEnabled: Boolean = true,
    val labelMinIntervalMinutes: Int = 30,
    val labelQuietStartMinute: Int = 23 * 60,
    val labelQuietEndMinute: Int = 7 * 60,
) {
    fun config() = AdaptiveConfig(dailyBudgetMinutes = dailyBudgetMinutes)
    fun promptPolicy() = LabelPromptPolicy(labelPromptsEnabled, labelMinIntervalMinutes, labelQuietStartMinute, labelQuietEndMinute)
}

/**
 * Settings + the state machine's memory, in shared prefs so an episode survives the process
 * being killed (MagicOS does this routinely); the next evaluation then sees the gap and closes the
 * episode as `interrupted`.
 */
class AdaptivePrefs private constructor(private val prefs: SharedPreferences) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val lock = Any()

    private val _settings = MutableStateFlow(loadSettings())
    val settings: StateFlow<AdaptiveSettings> = _settings.asStateFlow()

    private val _memory = MutableStateFlow(loadMemory())
    val memory: StateFlow<AdaptiveMemory> = _memory.asStateFlow()

    fun updateSettings(transform: (AdaptiveSettings) -> AdaptiveSettings) = synchronized(lock) {
        val next = transform(_settings.value).let {
            it.copy(dailyBudgetMinutes = it.dailyBudgetMinutes.coerceIn(MIN_BUDGET, MAX_BUDGET))
        }
        _settings.value = next
        prefs.edit().putString(KEY_SETTINGS, json.encodeToString(AdaptiveSettings.serializer(), next)).apply()
    }

    fun saveMemory(memory: AdaptiveMemory) = synchronized(lock) {
        _memory.value = memory
        prefs.edit().putString(KEY_MEMORY, json.encodeToString(AdaptiveMemory.serializer(), memory)).apply()
    }

    var lastLabelPromptAt: Long?
        get() = prefs.getLong(KEY_LAST_PROMPT, 0L).takeIf { it > 0 }
        set(value) { prefs.edit().putLong(KEY_LAST_PROMPT, value ?: 0L).apply() }

    private fun loadSettings(): AdaptiveSettings = prefs.getString(KEY_SETTINGS, null)?.let {
        runCatching { json.decodeFromString(AdaptiveSettings.serializer(), it) }.getOrNull()
    } ?: AdaptiveSettings()

    private fun loadMemory(): AdaptiveMemory = prefs.getString(KEY_MEMORY, null)?.let {
        runCatching { json.decodeFromString(AdaptiveMemory.serializer(), it) }.getOrNull()
    } ?: AdaptiveMemory()

    companion object {
        const val MIN_BUDGET = 15
        const val MAX_BUDGET = 180
        private const val FILE = "adaptive_sampling"
        private const val KEY_SETTINGS = "settings"
        private const val KEY_MEMORY = "memory"
        private const val KEY_LAST_PROMPT = "last_label_prompt"

        @Volatile private var instance: AdaptivePrefs? = null

        fun get(context: Context): AdaptivePrefs = instance ?: synchronized(this) {
            instance ?: AdaptivePrefs(context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE))
                .also { instance = it }
        }
    }
}
