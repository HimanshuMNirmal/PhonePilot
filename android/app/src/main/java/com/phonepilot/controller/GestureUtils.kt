package com.phonepilot.controller

import android.accessibilityservice.GestureDescription
import android.graphics.Path

/**
 * Utility functions for constructing [GestureDescription] objects for accessibility gestures.
 */
object GestureUtils {

    /**
     * Creates a tap gesture at the specified coordinates.
     */
    fun createTap(x: Float, y: Float, durationMs: Long = 50L): GestureDescription {
        val path = Path().apply {
            moveTo(x, y)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs.coerceAtLeast(10L))
        return GestureDescription.Builder().addStroke(stroke).build()
    }

    /**
     * Creates a long-press gesture at the specified coordinates.
     */
    fun createLongPress(x: Float, y: Float, durationMs: Long = 800L): GestureDescription {
        val path = Path().apply {
            moveTo(x, y)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs.coerceAtLeast(400L))
        return GestureDescription.Builder().addStroke(stroke).build()
    }

    /**
     * Creates a swipe gesture between start and end coordinates.
     */
    fun createSwipe(
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        durationMs: Long = 300L
    ): GestureDescription {
        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs.coerceAtLeast(50L))
        return GestureDescription.Builder().addStroke(stroke).build()
    }
}
