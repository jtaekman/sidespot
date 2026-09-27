package com.sidespot.ui

import android.view.KeyEvent
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.util.WeakHashMap

private val DPAD_NAV_CODES = setOf(
    KeyEvent.KEYCODE_DPAD_UP,
    KeyEvent.KEYCODE_DPAD_DOWN,
    KeyEvent.KEYCODE_DPAD_LEFT,
    KeyEvent.KEYCODE_DPAD_RIGHT,
)

/** Global toggle: true after any D-pad key, false after any touch. */
private val dpadActive = mutableStateOf(false)

/** Indicator-drawing elements that currently hold focus.  More than one window can
 *  have one at a time (the activity plus an open bottom sheet). */
private val focusedIndicators = mutableSetOf<Any>()

/**
 * Turns the D-pad focus indicators on for a navigation key press.  Returns true when
 * the press should only reveal the indicator: it was hidden and the focused element
 * draws one, so moving on straight away would skip past an item the user never saw.
 */
fun revealFocusHighlight(): Boolean {
    if (dpadActive.value) return false
    dpadActive.value = true
    return focusedIndicators.isNotEmpty()
}

/** Hides the D-pad focus indicators after touch input. */
fun hideFocusHighlight() {
    dpadActive.value = false
}

/** Per window, the indicator element that last gained focus. */
private val lastFocusedIndicator = WeakHashMap<View, FocusRequester>()

/** False inside overlays that borrow focus and hand it back with
 *  [restoreIndicatorFocus], so their own controls aren't remembered. */
val LocalRemembersIndicatorFocus = staticCompositionLocalOf { true }

/** True while [restoreIndicatorFocus] runs.  Screens that steer focus entering them
 *  to their first row let it through then, so it lands back on the row it left. */
var isRestoringIndicatorFocus = false
    private set

/** Moves focus back to the indicator element in [view]'s window that last had it. */
fun restoreIndicatorFocus(view: View) {
    isRestoringIndicatorFocus = true
    try {
        lastFocusedIndicator[view]?.requestFocus()
    } catch (_: IllegalStateException) {
    } finally {
        isRestoringIndicatorFocus = false
    }
}

/** Focus tracking shared by the indicator modifiers below. */
private fun Modifier.onIndicatorFocusChanged(onChanged: (Boolean) -> Unit): Modifier = composed {
    val token = remember { Any() }
    val requester = remember { FocusRequester() }
    val view = LocalView.current
    val remembersFocus = LocalRemembersIndicatorFocus.current
    DisposableEffect(token) {
        onDispose {
            focusedIndicators.remove(token)
            if (lastFocusedIndicator[view] === requester) lastFocusedIndicator.remove(view)
        }
    }
    this
        .focusRequester(requester)
        .onFocusChanged {
            if (it.hasFocus) {
                focusedIndicators.add(token)
                if (remembersFocus) lastFocusedIndicator[view] = requester
            } else {
                focusedIndicators.remove(token)
            }
            onChanged(it.hasFocus)
        }
}

fun Modifier.focusHighlight(
    color: Color = Color.Unspecified,
    shape: Shape = RectangleShape,
    onEnterKey: (() -> Unit)? = null,
    horizontalPadding: Dp = 14.dp,
    verticalPadding: Dp = 8.dp,
): Modifier = composed {
    var hasFocus by remember { mutableStateOf(false) }
    // Set while the select key is held past a long press, so its repeats and
    // release don't also click the row once the actions menu is open.
    var selectHeld by remember { mutableStateOf(false) }
    val showHighlight by dpadActive
    val resolvedColor = if (color == Color.Unspecified) MaterialTheme.colorScheme.primary else color
    val fillColor = if (hasFocus && showHighlight) resolvedColor.copy(alpha = 0.5f) else Color.Transparent

    this
        .pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    awaitPointerEvent(PointerEventPass.Initial)
                    dpadActive.value = false
                }
            }
        }
        .onIndicatorFocusChanged { hasFocus = it }
        .onPreviewKeyEvent { event ->
            val native = event.nativeKeyEvent
            // MainActivity reveals the highlight for keys in its own window; bottom
            // sheets are separate windows whose keys never pass through it.
            if (native.action == KeyEvent.ACTION_DOWN &&
                revealFocusHighlight() && native.keyCode in DPAD_NAV_CODES
            ) {
                return@onPreviewKeyEvent true
            }
            if (onEnterKey != null && hasFocus) {
                val kc = native.keyCode
                val isSelectKey = kc == KeyEvent.KEYCODE_DPAD_CENTER || kc == KeyEvent.KEYCODE_ENTER
                if (isSelectKey && native.action == KeyEvent.ACTION_DOWN && native.repeatCount == 0) {
                    selectHeld = false
                }
                when {
                    // The Sundial's Enter corner button opens row actions outright;
                    // on other keypads Enter selects like the centre key.
                    Keypad.isSundial && kc == KeyEvent.KEYCODE_ENTER &&
                        native.action == KeyEvent.ACTION_DOWN -> {
                        if (native.repeatCount == 0) onEnterKey()
                        return@onPreviewKeyEvent true
                    }
                    // Holding the select key opens row actions instead of selecting.
                    isSelectKey && native.action == KeyEvent.ACTION_DOWN && native.isLongPress -> {
                        if (!selectHeld) {
                            selectHeld = true
                            onEnterKey()
                        }
                        return@onPreviewKeyEvent true
                    }
                    isSelectKey && selectHeld -> {
                        if (native.action == KeyEvent.ACTION_UP) selectHeld = false
                        return@onPreviewKeyEvent true
                    }
                    kc == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                        if (native.action == KeyEvent.ACTION_DOWN && native.repeatCount == 0) {
                            onEnterKey()
                        }
                        return@onPreviewKeyEvent true
                    }
                }
            }
            false
        }
        .focusable()
        .background(fillColor, shape)
        .padding(horizontal = horizontalPadding, vertical = verticalPadding)
}

/** Draws a white border on a filled button when focused via D-pad. */
fun Modifier.focusDarken(): Modifier = composed {
    var hasFocus by remember { mutableStateOf(false) }
    val showHighlight by dpadActive

    this
        .onIndicatorFocusChanged { hasFocus = it }
        .drawWithContent {
            drawContent()
            if (hasFocus && showHighlight) {
                val hInset = 0.dp.toPx()
                val vInset = 2.dp.toPx()
                drawRoundRect(
                    color = Color.White,
                    topLeft = androidx.compose.ui.geometry.Offset(hInset, vInset),
                    size = androidx.compose.ui.geometry.Size(
                        size.width - hInset * 2,
                        size.height - vInset * 2,
                    ),
                    style = Stroke(width = 3.dp.toPx()),
                    cornerRadius = CornerRadius(20.dp.toPx()),
                )
            }
        }
}

/** Draws a filled circle behind an IconButton when focused via D-pad. */
fun Modifier.focusCircle(
    color: Color = Color.Unspecified,
): Modifier = composed {
    var hasFocus by remember { mutableStateOf(false) }
    val showHighlight by dpadActive
    val resolvedColor = if (color == Color.Unspecified) MaterialTheme.colorScheme.primary else color

    this
        .onIndicatorFocusChanged { hasFocus = it }
        .drawWithContent {
            if (hasFocus && showHighlight) {
                drawCircle(resolvedColor.copy(alpha = 0.5f))
            }
            drawContent()
        }
}

/** Dismiss bottom sheet popups with the keypad's back keys, matching what they
 *  do on screens: the Sundial's top-right button, or Left / Backspace on a D-pad
 *  keypad.  Also consume MEDIA_PLAY_PAUSE so it doesn't leak to the Activity. */
fun Modifier.dismissOnDpad(onDismiss: () -> Unit): Modifier = this
    .onPreviewKeyEvent { event ->
        if (Keypad.isSundial &&
            event.nativeKeyEvent.action == KeyEvent.ACTION_DOWN &&
            event.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
        ) {
            onDismiss()
            true
        } else false
    }
    .onKeyEvent { event ->
        val native = event.nativeKeyEvent
        // Bubble phase, so a text field in the sheet still gets Left/Backspace first.
        if (!Keypad.isSundial && native.action == KeyEvent.ACTION_DOWN && (
                native.keyCode == KeyEvent.KEYCODE_DPAD_LEFT ||
                    native.keyCode == KeyEvent.KEYCODE_DEL
                )
        ) {
            onDismiss()
            return@onKeyEvent true
        }
        // Safety net: consume any MEDIA_PLAY_PAUSE not caught by a focused child
        // (e.g. ACTION_UP arriving after the action row recomposes).
        native.keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
    }
