package com.sidespot.ui

import android.content.Context
import android.content.SharedPreferences
import android.view.KeyEvent

/**
 * Which kind of Sidephone keypad tile is fitted, inferred from the keys it sends.
 *
 * Tiles are swappable and the app can't ask which one is attached, but each sends
 * keys the others don't (docs.sidephone.com, "Default keypad keymappings"):
 *  - Sundial: centre glass = MEDIA_PLAY_PAUSE, dial left/right = MEDIA_PREVIOUS/NEXT,
 *    bottom corners = TAB / ENTER, top corners = DPAD_LEFT / DPAD_RIGHT.
 *  - Classic (T9) and Mini Controller: a real D-pad, selecting with DPAD_CENTER (or A /
 *    Start), alongside number, call and letter keys.
 *
 * Both send DPAD_LEFT/RIGHT, but only on a D-pad do they mean "move left/right" — on the
 * Sundial they are corner buttons the app repurposes for tab cycling and back.  Those
 * remaps are therefore applied only once a Sundial key has been seen.  The guess is
 * persisted so the first press after a restart is handled right too.
 */
object Keypad {

    private const val PREFS_NAME = "sidespot_keypad"
    private const val KEY_SUNDIAL = "sundial"

    private val SUNDIAL_ONLY_KEYS = setOf(
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
        KeyEvent.KEYCODE_MEDIA_NEXT,
        KeyEvent.KEYCODE_MEDIA_PREVIOUS,
        KeyEvent.KEYCODE_TAB,
    )

    private val DPAD_KEYPAD_ONLY_KEYS = setOf(
        KeyEvent.KEYCODE_DPAD_CENTER,
        KeyEvent.KEYCODE_BUTTON_A,
        KeyEvent.KEYCODE_BUTTON_B,
        KeyEvent.KEYCODE_BUTTON_X,
        KeyEvent.KEYCODE_BUTTON_Y,
        KeyEvent.KEYCODE_BUTTON_START,
        KeyEvent.KEYCODE_BUTTON_SELECT,
        KeyEvent.KEYCODE_CALL,
        KeyEvent.KEYCODE_ENDCALL,
        KeyEvent.KEYCODE_DEL,
        KeyEvent.KEYCODE_STAR,
        KeyEvent.KEYCODE_POUND,
    ) + (KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9)

    private var prefs: SharedPreferences? = null

    /** True when the last keypad seen was a Sundial; false for D-pad keypads. */
    var isSundial: Boolean = false
        private set

    fun init(context: Context) {
        val p = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs = p
        isSundial = p.getBoolean(KEY_SUNDIAL, false)
    }

    /** Updates the guess from a hardware key press. */
    fun onKeyDown(event: KeyEvent) {
        // The on-screen keyboard sends DEL and digits too; they say nothing about the tile.
        if (event.flags and KeyEvent.FLAG_SOFT_KEYBOARD != 0) return
        val sundial = when (event.keyCode) {
            in SUNDIAL_ONLY_KEYS -> true
            in DPAD_KEYPAD_ONLY_KEYS -> false
            else -> return
        }
        if (sundial != isSundial) {
            isSundial = sundial
            prefs?.edit()?.putBoolean(KEY_SUNDIAL, sundial)?.apply()
        }
    }
}
