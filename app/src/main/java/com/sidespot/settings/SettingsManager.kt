package com.sidespot.settings

import android.content.Context
import android.content.SharedPreferences
import com.sidespot.bridge.NativeBridge
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

enum class AudioQuality(val bitrate: Int, val label: String) {
    NORMAL(160, "Normal (160 kbps)"),
    HIGH(320, "High (320 kbps)"),
}

data class SettingsState(
    val normalization: Boolean = false,
    val autoplay: Boolean = false,
    val audioQuality: AudioQuality = AudioQuality.HIGH,
    val connectEnabled: Boolean = false,
    val deviceName: String = "Sidespot",
)

class SettingsManager(context: Context) {

    companion object {
        private const val PREFS_NAME = "sidespot_settings"
        private const val KEY_NORMALIZATION = "normalization"
        private const val KEY_AUTOPLAY = "autoplay"
        private const val KEY_AUDIO_QUALITY = "audio_quality"
        private const val KEY_CONNECT_ENABLED = "connect_enabled"
        private const val KEY_DEVICE_NAME = "device_name"
    }

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(loadFromPrefs())
    val state: StateFlow<SettingsState> = _state.asStateFlow()

    private fun loadFromPrefs(): SettingsState {
        val qualityName = prefs.getString(KEY_AUDIO_QUALITY, AudioQuality.HIGH.name)
        val quality = try {
            AudioQuality.valueOf(qualityName ?: AudioQuality.HIGH.name)
        } catch (_: IllegalArgumentException) {
            AudioQuality.HIGH
        }
        return SettingsState(
            normalization = prefs.getBoolean(KEY_NORMALIZATION, false),
            autoplay = prefs.getBoolean(KEY_AUTOPLAY, false),
            audioQuality = quality,
            connectEnabled = prefs.getBoolean(KEY_CONNECT_ENABLED, false),
            deviceName = prefs.getString(KEY_DEVICE_NAME, "Sidespot") ?: "Sidespot",
        )
    }

    /** Set autoplay and persist immediately. No player recreation needed. */
    fun setAutoplay(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTOPLAY, enabled).apply()
        _state.value = _state.value.copy(autoplay = enabled)
        pushConfigToNative()
    }

    /**
     * Apply audio settings (normalization + quality), persist, and push to native.
     * Caller should follow this with PlayerViewModel.recreatePlayer().
     */
    fun applyAudioSettings(normalization: Boolean, quality: AudioQuality) {
        prefs.edit()
            .putBoolean(KEY_NORMALIZATION, normalization)
            .putString(KEY_AUDIO_QUALITY, quality.name)
            .apply()
        _state.value = _state.value.copy(normalization = normalization, audioQuality = quality)
        pushConfigToNative()
    }

    /** Set Spotify Connect enabled and persist. Takes effect on next app restart. */
    fun setConnectEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_CONNECT_ENABLED, enabled).apply()
        _state.value = _state.value.copy(connectEnabled = enabled)
    }

    /** Set device name and persist. Takes effect on next app restart. */
    fun setDeviceName(name: String) {
        prefs.edit().putString(KEY_DEVICE_NAME, name).apply()
        _state.value = _state.value.copy(deviceName = name)
    }

    /** Build JSON and push current config to native layer. */
    fun pushConfigToNative() {
        val s = _state.value
        val json = JSONObject().apply {
            put("bitrate", s.audioQuality.bitrate)
            put("normalisation", s.normalization)
            put("autoplay", s.autoplay)
        }
        val error = NativeBridge.playerConfigure(json.toString())
        if (error != null) {
            android.util.Log.e("SettingsManager", "playerConfigure failed: $error")
        }
    }
}
