package com.sidespot

import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewConfiguration
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import com.sidespot.auth.AuthManager
import com.sidespot.bridge.NativeBridge
import com.sidespot.settings.SettingsManager
import com.sidespot.ui.Keypad
import com.sidespot.ui.SidespotNavigation
import com.sidespot.ui.SidespotTheme
import com.sidespot.ui.hideFocusHighlight
import com.sidespot.ui.revealFocusHighlight
import com.sidespot.viewmodel.PlayerViewModel

class MainActivity : ComponentActivity() {

    private var playerViewModel: PlayerViewModel? = null
    private lateinit var authManager: AuthManager
    private lateinit var settingsManager: SettingsManager

    /** Now Playing is a full-screen overlay, not a NavHost destination, so its
     *  visibility can't be derived from the nav back stack — the UI syncs it here. */
    var isNowPlayingVisible: Boolean = false

    var onNowPlayingToggleRequested: (() -> Unit)? = null
    var onTabCycleRequested: (() -> Unit)? = null

    /** D-pad keypads: Left/Right pressed with nothing further that way to focus. */
    var onDpadEdgeReached: ((toLeft: Boolean) -> Unit)? = null

    // Center button long-press tracking
    private var centerDownTime = 0L
    private var centerLongPressed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        authManager = AuthManager.getInstance(this)
        settingsManager = SettingsManager(this)
        Keypad.init(this)

        // Push initial config to native before any connect
        settingsManager.pushConfigToNative()

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

    /** Re-dispatches [event] as [keyCode], keeping its action, timing and repeat count. */
    private fun dispatchAs(event: KeyEvent, keyCode: Int): Boolean {
        val translated = KeyEvent(
            event.downTime, event.eventTime, event.action,
            keyCode, event.repeatCount, event.metaState,
            event.deviceId, event.scanCode, event.flags, event.source,
        )
        return super.dispatchKeyEvent(translated)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) hideFocusHighlight()
        return super.dispatchTouchEvent(ev)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            Keypad.onKeyDown(event)
            Log.i(
                "Keypad",
                "keyCode=${event.keyCode} (${KeyEvent.keyCodeToString(event.keyCode)}) " +
                    "device=${event.device?.name} sundial=${Keypad.isSundial}",
            )
        }

        // Skip tracks once per press.  Holding the key auto-repeats, which would
        // otherwise skip through several tracks; consuming the release too keeps it
        // from falling through to the media session.
        if (event.keyCode == KeyEvent.KEYCODE_MEDIA_NEXT ||
            event.keyCode == KeyEvent.KEYCODE_MEDIA_PREVIOUS
        ) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                if (event.keyCode == KeyEvent.KEYCODE_MEDIA_NEXT) playerViewModel?.next()
                else playerViewModel?.previous()
            }
            return true
        }

        val handled = if (Keypad.isSundial) dispatchSundialKey(event) else dispatchDpadKey(event)
        return handled || super.dispatchKeyEvent(event)
    }

    /**
     * D-pad keypads (Classic, Mini Controller): standard Android navigation — arrows
     * move focus, the centre key selects.  Left/Right that find nothing to focus land
     * in [onKeyDown].
     */
    private fun dispatchDpadKey(event: KeyEvent): Boolean {
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                // After touch input the first press only shows where focus is.
                return event.action == KeyEvent.ACTION_DOWN && revealFocusHighlight()
            }
            // The Mini Controller's A and Start select, as in Sidephone's sample apps.
            KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_START ->
                return dispatchAs(event, KeyEvent.KEYCODE_DPAD_CENTER)
        }
        return false
    }

    /** Sundial keypad: dial, centre glass and corner buttons have fixed roles. */
    private fun dispatchSundialKey(event: KeyEvent): Boolean {
        when (event.keyCode) {
            // Tab key — intercept before Compose consumes it for focus traversal
            KeyEvent.KEYCODE_TAB -> {
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                    onNowPlayingToggleRequested?.invoke()
                }
                return true
            }

            // DPAD_LEFT — cycle bottom nav tabs
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                    onTabCycleRequested?.invoke()
                }
                return true
            }

            // DPAD_RIGHT — translate to BACK so it dismisses bottom sheets and navigates back
            KeyEvent.KEYCODE_DPAD_RIGHT -> return dispatchAs(event, KeyEvent.KEYCODE_BACK)

            // Dial up/down — volume on Now Playing, focus traversal elsewhere.  Handled
            // here rather than in onKeyDown: Compose would otherwise use them to move
            // focus first, and only an unused press would reach the volume.
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (isNowPlayingVisible) {
                    if (event.action == KeyEvent.ACTION_DOWN) {
                        adjustVolume(up = event.keyCode == KeyEvent.KEYCODE_DPAD_UP)
                    }
                    return true
                }
                // After touch input the first press only shows where focus is.
                return event.action == KeyEvent.ACTION_DOWN && revealFocusHighlight()
            }

            // Enter corner opens row actions, which don't exist on Now Playing
            KeyEvent.KEYCODE_ENTER -> return isNowPlayingVisible

            // Center button: Play/Pause on Now Playing; short press = select, long press = row actions elsewhere
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
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
        }
        return false
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            // Hardware volume keys — always adjust volume
            KeyEvent.KEYCODE_VOLUME_UP -> adjustVolume(up = true)
            KeyEvent.KEYCODE_VOLUME_DOWN -> adjustVolume(up = false)

            KeyEvent.KEYCODE_MEDIA_PLAY -> {
                playerViewModel?.play(); true
            }
            KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                playerViewModel?.pause(); true
            }

            // Only reached when nothing on screen used the key, i.e. there was
            // nothing further left/right to focus (the Sundial intercepts these
            // earlier).  Now Playing keeps its own controls.
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (!isNowPlayingVisible && event?.repeatCount == 0) {
                    onDpadEdgeReached?.invoke(keyCode == KeyEvent.KEYCODE_DPAD_LEFT)
                }
                true
            }

            // The Classic keypad's Backspace key goes back when not editing text
            // (a focused text field consumes it first).
            KeyEvent.KEYCODE_DEL -> {
                if (event != null && event.repeatCount == 0 &&
                    event.flags and KeyEvent.FLAG_SOFT_KEYBOARD == 0
                ) {
                    onBackPressedDispatcher.onBackPressed()
                }
                true
            }

            else -> super.onKeyDown(keyCode, event)
        }
    }
}
