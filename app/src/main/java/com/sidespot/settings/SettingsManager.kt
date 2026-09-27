package com.sidespot.settings

import android.content.Context
import android.content.SharedPreferences
import com.sidespot.bridge.NativeBridge
import com.sidespot.offline.DownloadManager
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
    val gapless: Boolean = true,
    val autoplay: Boolean = false,
    val audioQuality: AudioQuality = AudioQuality.HIGH,
    val einkMode: Boolean = false,
    val downloadOverCellular: Boolean = false,
)

class SettingsManager(context: Context) {

    companion object {
        private const val PREFS_NAME = "sidespot_settings"
        private const val KEY_NORMALIZATION = "normalization"
        private const val KEY_AUTOPLAY = "autoplay"
        private const val KEY_GAPLESS = "gapless"
        private const val KEY_AUDIO_QUALITY = "audio_quality"
        private const val KEY_EINK_MODE = "eink_mode"
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
            gapless = prefs.getBoolean(KEY_GAPLESS, true),
            autoplay = prefs.getBoolean(KEY_AUTOPLAY, false),
            audioQuality = quality,
            einkMode = prefs.getBoolean(KEY_EINK_MODE, false),
            downloadOverCellular = prefs.getBoolean(DownloadManager.KEY_DOWNLOAD_OVER_CELLULAR, false),
        )
    }

    /** Allow downloads on metered networks, and persist immediately. */
    fun setDownloadOverCellular(enabled: Boolean) {
        prefs.edit().putBoolean(DownloadManager.KEY_DOWNLOAD_OVER_CELLULAR, enabled).apply()
        _state.value = _state.value.copy(downloadOverCellular = enabled)
        DownloadManager.get().setAllowCellular(enabled)
    }

    /** Set e-ink mode and persist immediately. No player recreation needed. */
    fun setEinkMode(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_EINK_MODE, enabled).apply()
        _state.value = _state.value.copy(einkMode = enabled)
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
    fun applyAudioSettings(normalization: Boolean, gapless: Boolean, quality: AudioQuality) {
        prefs.edit()
            .putBoolean(KEY_NORMALIZATION, normalization)
            .putBoolean(KEY_GAPLESS, gapless)
            .putString(KEY_AUDIO_QUALITY, quality.name)
            .apply()
        _state.value = _state.value.copy(normalization = normalization, gapless = gapless, audioQuality = quality)
        pushConfigToNative()
    }

    /** Build JSON and push current config to native layer. */
    fun pushConfigToNative() {
        val s = _state.value
        val json = JSONObject().apply {
            put("bitrate", s.audioQuality.bitrate)
            put("normalisation", s.normalization)
            put("gapless", s.gapless)
            put("autoplay", s.autoplay)
        }
        val error = NativeBridge.playerConfigure(json.toString())
        if (error != null) {
            android.util.Log.e("SettingsManager", "playerConfigure failed: $error")
        }
    }
}
