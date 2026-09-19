package com.phonepilot.action

/**
 * Specification for locating a UI element on the screen.
 * Contains only application-level data and has no direct Android dependencies.
 */
sealed class ElementTarget {

    /**
     * Locate by visible text.
     */
    data class Text(
        val text: String,
        val exactMatch: Boolean = true
    ) : ElementTarget()

    /**
     * Locate by accessibility content description.
     */
    data class ContentDescription(
        val description: String,
        val exactMatch: Boolean = true
    ) : ElementTarget()

    /**
     * Locate by view resource ID (full ID or short suffix).
     */
    data class ResourceId(
        val resourceId: String
    ) : ElementTarget()

    /**
     * Explicit coordinate target as a fallback when semantic targets cannot be found.
     */
    data class Coordinates(
        val x: Float,
        val y: Float
    ) : ElementTarget()
}
