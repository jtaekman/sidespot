package com.sidespot

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.ViewConfiguration
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.sidespot.auth.AuthManager
import com.sidespot.bridge.NativeBridge
import com.sidespot.settings.SettingsManager
import com.sidespot.ui.SidespotNavigation
import com.sidespot.ui.SidespotTheme
import com.sidespot.viewmodel.PlayerViewModel
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private var playerViewModel: PlayerViewModel? = null
    private lateinit var authManager: AuthManager
    private lateinit var settingsManager: SettingsManager

    /** Now Playing is a full-screen overlay, not a NavHost destination, so its
     *  visibility can't be derived from the nav back stack — the UI syncs it here. */
    var isNowPlayingVisible: Boolean = false

    var onNowPlayingToggleRequested: (() -> Unit)? = null
    var onTabCycleRequested: (() -> Unit)? = null

    // Center button long-press tracking
    private var centerDownTime = 0L
    private var centerLongPressed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        authManager = AuthManager.getInstance(this)
        settingsManager = SettingsManager(this)

        // Push initial config to native before any connect
        settingsManager.pushConfigToNative()

        // Handle deep link on cold start
        handleAuthCallback(intent)

        setContent {
            SidespotTheme {
                val vm: PlayerViewModel = viewModel()
                playerViewModel = vm
                vm.initPlatform(this@MainActivity)

                SidespotNavigation(
                    playerViewModel = vm,
                    authManager = authManager,
                    settingsManager = settingsManager,
                    mainActivity = this@MainActivity,
                )
            }
        }

        // Keep the window out of touch mode so sundial hardware key events
        // reach dispatchKeyEvent immediately (Android consumes the first
        // navigation-key press to exit touch mode, swallowing it).
        window.decorView.apply {
            post {
                try { requestFocusFromTouch() } catch (_: IllegalStateException) {}
            }
            viewTreeObserver.addOnTouchModeChangeListener { inTouchMode ->
                if (inTouchMode) post {
                    try { requestFocusFromTouch() } catch (_: IllegalStateException) {}
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleAuthCallback(intent)
    }

    private fun handleAuthCallback(intent: Intent?) {
        val uri = intent?.data ?: return
        if (uri.scheme == "sidespot" && uri.host == "callback") {
            val code = uri.getQueryParameter("code") ?: return
            // Clear the intent data so we don't re-process on activity recreation
            intent.data = null
            // Skip if already authenticated (stale callback from process restart)
            if (authManager.state.value.isAuthenticated) {
                Log.i("SidespotAuth", "handleAuthCallback: skipping, already authenticated")
                return
            }
            lifecycleScope.launch {
                authManager.exchangeCode(code)
            }
        }
    }

    private fun adjustVolume(up: Boolean): Boolean {
        val step = 65535 / 20
        val current = NativeBridge.playerGetVolume()
        val newVol = if (up) (current + step).coerceAtMost(65535)
        else (current - step).coerceAtLeast(0)
        NativeBridge.playerSetVolume(newVol)
        playerViewModel?.onVolumeChanged(newVol)
        return true
    }

    private fun dispatchSyntheticKey(keyCode: Int) {
        val now = android.os.SystemClock.uptimeMillis()
        val down = KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0)
        val up = KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0)
        super.dispatchKeyEvent(down)
        super.dispatchKeyEvent(up)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            Log.i("Sundial", "keyCode=${event.keyCode} (${KeyEvent.keyCodeToString(event.keyCode)})")
        }

        // Tab key — intercept before Compose consumes it for focus traversal
        if (event.keyCode == KeyEvent.KEYCODE_TAB) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                onNowPlayingToggleRequested?.invoke()
            }
            return true
        }

        // DPAD_LEFT — cycle bottom nav tabs
        if (event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                onTabCycleRequested?.invoke()
            }
            return true
        }

        // DPAD_RIGHT — translate to BACK so it dismisses bottom sheets and navigates back
        if (event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
            val translated = KeyEvent(
                event.downTime, event.eventTime, event.action,
                KeyEvent.KEYCODE_BACK, event.repeatCount, event.metaState,
                event.deviceId, event.scanCode, event.flags, event.source,
            )
            return super.dispatchKeyEvent(translated)
        }

        // Center button: Play/Pause on Now Playing; short press = select, long press = row actions elsewhere
        if (event.keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) {
            if (isNowPlayingVisible) {
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                    val vm = playerViewModel ?: return true
                    if (vm.uiState.value.isPlaying) vm.pause() else vm.play()
                }
                return true
            }

            // On list screens: hold all events, decide on release
            when (event.action) {
                KeyEvent.ACTION_DOWN -> {
                    if (event.repeatCount == 0) {
                        centerDownTime = event.eventTime
                        centerLongPressed = false
                    } else if (!centerLongPressed) {
                        val held = event.eventTime - centerDownTime
                        if (held >= ViewConfiguration.getLongPressTimeout()) {
                            centerLongPressed = true
                            dispatchSyntheticKey(KeyEvent.KEYCODE_ENTER)
                        }
                    }
                    return true
                }
                KeyEvent.ACTION_UP -> {
                    if (!centerLongPressed) {
                        dispatchSyntheticKey(KeyEvent.KEYCODE_DPAD_CENTER)
                    }
                    return true
                }
            }
        }

        return super.dispatchKeyEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            // Hardware volume keys — always adjust volume
            KeyEvent.KEYCODE_VOLUME_UP -> adjustVolume(up = true)
            KeyEvent.KEYCODE_VOLUME_DOWN -> adjustVolume(up = false)

            // Media skip keys
            KeyEvent.KEYCODE_MEDIA_NEXT -> {
                playerViewModel?.next(); true
            }
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                playerViewModel?.previous(); true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY -> {
                playerViewModel?.play(); true
            }
            KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                playerViewModel?.pause(); true
            }

            // D-pad up/down — volume on Now Playing, focus traversal elsewhere
            KeyEvent.KEYCODE_DPAD_UP -> {
                if (isNowPlayingVisible) adjustVolume(up = true)
                else super.onKeyDown(keyCode, event)
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (isNowPlayingVisible) adjustVolume(up = false)
                else super.onKeyDown(keyCode, event)
            }

            else -> super.onKeyDown(keyCode, event)
        }
    }
}
