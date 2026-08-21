package com.vpsin.kino.ui.common

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.RowScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.tv.material3.Button

/**
 * Adds mouse/touch activation to a tv-material clickable component.
 *
 * tv-material's `Surface(onClick = …)` — which `Button`, `ListItem` and our cards
 * are all built on — only reacts to DPAD_CENTER/ENTER key events. Its modifier
 * chain is `focusable()` plus an internal key handler and contains no pointer
 * input at all, so every control is inert to a mouse. That is defensible for a
 * living-room remote but breaks the TV emulator and air-mouse remotes, where
 * clicking is the natural gesture.
 *
 * Tap detection only, deliberately: `Modifier.clickable` would also claim ENTER
 * and fire `onClick` a second time on every D-pad press.
 */
@Composable
fun Modifier.pointerClick(enabled: Boolean = true, onClick: () -> Unit): Modifier =
    this.pointerInput(enabled, onClick) {
        if (!enabled) return@pointerInput
        detectTapGestures { onClick() }
    }

/**
 * tv-material [Button] that also responds to the mouse. Used everywhere in place
 * of the raw component so no screen can accidentally ship a mouse-dead control.
 */
@Composable
fun TvButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.pointerClick(enabled, onClick),
        content = content,
    )
}
