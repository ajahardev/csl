/*
 * Zalith Launcher 2
 * Copyright (C) 2025 MovTery <movtery228@qq.com> and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.movtery.zalithlauncher.game.input

import com.movtery.layer_controller.event.ClickEvent
import com.movtery.zalithlauncher.ui.control.event.lwjglEvent
import java.util.concurrent.ConcurrentHashMap

/**
 * Pojav-compatible virtual control input bridge.
 *
 * Pojav's in-game controls send GLFW key/mouse events directly from each view, but
 * its Android View dispatch guarantees that a control receives a release before it
 * disappears. Zalith's Compose overlay can be recomposed or hidden while controls
 * are held, so this adapter adds the missing deterministic state layer:
 *
 * * multiple controls may hold the same GLFW key without premature release;
 * * mouse buttons and keyboard keys share the same press/release lifecycle;
 * * all held inputs can be released on pause/resume/layout reload/game exit to
 *   prevent stuck movement, attack, sneak, etc.;
 * * launcher-only events remain edge-triggered and are intentionally not counted.
 */
class PojavInputBridge(
    private val dispatchLauncherEvent: (event: ClickEvent, pressed: Boolean) -> Unit
) {
    private val pressedCounts = ConcurrentHashMap<String, Int>()

    fun dispatch(event: ClickEvent, pressed: Boolean) {
        when (event.type) {
            ClickEvent.Type.Key -> dispatchCounted(event.key, pressed, isMouse = false)
            ClickEvent.Type.LauncherEvent -> {
                if (event.key.startsWith("GLFW_MOUSE_", ignoreCase = false)) {
                    dispatchCounted(event.key, pressed, isMouse = true)
                } else {
                    dispatchLauncherEvent(event, pressed)
                }
            }
            else -> dispatchLauncherEvent(event, pressed)
        }
    }

    fun releaseAll() {
        val keys = pressedCounts.keys.toList()
        pressedCounts.clear()
        for (key in keys) {
            lwjglEvent(
                eventKey = key,
                isMouse = key.startsWith("GLFW_MOUSE_", ignoreCase = false),
                isPressed = false
            )
        }
    }

    private fun dispatchCounted(key: String, pressed: Boolean, isMouse: Boolean) {
        if (pressed) {
            var shouldSendDown = false
            pressedCounts.compute(key) { _, current ->
                val count = current ?: 0
                shouldSendDown = count == 0
                count + 1
            }
            if (shouldSendDown) lwjglEvent(key, isMouse, true)
        } else {
            var shouldSendUp = false
            pressedCounts.compute(key) { _, current ->
                when {
                    current == null || current <= 0 -> null
                    current == 1 -> {
                        shouldSendUp = true
                        null
                    }
                    else -> current - 1
                }
            }
            if (shouldSendUp) lwjglEvent(key, isMouse, false)
        }
    }
}
