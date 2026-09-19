package com.phonepilot.action

import com.phonepilot.model.ScreenSnapshot
import com.phonepilot.model.UiElement

/**
 * Result of resolving an [ElementTarget] against a [ScreenSnapshot].
 */
sealed class ResolutionResult {
    data class Resolved(val element: UiElement) : ResolutionResult()
    data class Coordinate(val x: Float, val y: Float) : ResolutionResult()
    data class Ambiguous(
        val target: ElementTarget,
        val matchCount: Int,
        val candidates: List<UiElement>
    ) : ResolutionResult()
    data class NotFound(
        val target: ElementTarget,
        val reason: String
    ) : ResolutionResult()
}

/**
 * Resolves an [ElementTarget] to a specific [UiElement] on the current screen.
 * Strictly avoids guessing when multiple elements match.
 */
object ElementResolver {

    /**
     * Resolves [target] against [snapshot].
     */
    fun resolve(target: ElementTarget, snapshot: ScreenSnapshot?): ResolutionResult {
        if (target is ElementTarget.Coordinates) {
            return ResolutionResult.Coordinate(target.x, target.y)
        }

        if (snapshot == null || snapshot.root == null) {
            return ResolutionResult.NotFound(target, "Current screen snapshot is empty or unreadable")
        }

        val visibleElements = snapshot.allElements.filter { it.isVisible }

        return when (target) {
            is ElementTarget.ResourceId -> resolveResourceId(target, visibleElements)
            is ElementTarget.Text -> resolveText(target, visibleElements)
            is ElementTarget.ContentDescription -> resolveContentDescription(target, visibleElements)
            is ElementTarget.Coordinates -> ResolutionResult.Coordinate(target.x, target.y)
        }
    }

    private fun resolveResourceId(
        target: ElementTarget.ResourceId,
        elements: List<UiElement>
    ): ResolutionResult {
        val query = target.resourceId.trim()
        val shortQuery = query.substringAfter(":id/")

        val matches = elements.filter { el ->
            val res = el.resourceId ?: return@filter false
            res == query || res.substringAfter(":id/") == shortQuery
        }

        return when {
            matches.isEmpty() -> ResolutionResult.NotFound(target, "No visible element found with resource ID '$query'")
            matches.size == 1 -> ResolutionResult.Resolved(matches.first())
            else -> ResolutionResult.Ambiguous(target, matches.size, matches)
        }
    }

    private fun resolveText(
        target: ElementTarget.Text,
        elements: List<UiElement>
    ): ResolutionResult {
        val query = target.text.trim()
        if (query.isEmpty()) {
            return ResolutionResult.NotFound(target, "Text query cannot be empty")
        }

        // 1. Exact text match
        val exactTextMatches = elements.filter { el ->
            el.text?.trim().equals(query, ignoreCase = true)
        }
        if (exactTextMatches.size == 1) {
            return ResolutionResult.Resolved(exactTextMatches.first())
        }
        if (exactTextMatches.size > 1) {
            return ResolutionResult.Ambiguous(target, exactTextMatches.size, exactTextMatches)
        }

        // 2. Exact content description fallback
        val exactDescMatches = elements.filter { el ->
            el.contentDescription?.trim().equals(query, ignoreCase = true)
        }
        if (exactDescMatches.size == 1) {
            return ResolutionResult.Resolved(exactDescMatches.first())
        }
        if (exactDescMatches.size > 1) {
            return ResolutionResult.Ambiguous(target, exactDescMatches.size, exactDescMatches)
        }

        // If exact match was explicitly requested, do not perform fuzzy search
        if (target.exactMatch) {
            return ResolutionResult.NotFound(target, "No visible element found with exact text '$query'")
        }

        // 3. Partial substring match
        val partialMatches = elements.filter { el ->
            val textMatch = el.text?.contains(query, ignoreCase = true) == true
            val descMatch = el.contentDescription?.contains(query, ignoreCase = true) == true
            textMatch || descMatch
        }

        return when {
            partialMatches.isEmpty() -> ResolutionResult.NotFound(target, "No visible element found containing text '$query'")
            partialMatches.size == 1 -> ResolutionResult.Resolved(partialMatches.first())
            else -> ResolutionResult.Ambiguous(target, partialMatches.size, partialMatches)
        }
    }

    private fun resolveContentDescription(
        target: ElementTarget.ContentDescription,
        elements: List<UiElement>
    ): ResolutionResult {
        val query = target.description.trim()
        if (query.isEmpty()) {
            return ResolutionResult.NotFound(target, "Content description query cannot be empty")
        }

        // 1. Exact content description
        val exactMatches = elements.filter { el ->
            el.contentDescription?.trim().equals(query, ignoreCase = true)
        }
        if (exactMatches.size == 1) {
            return ResolutionResult.Resolved(exactMatches.first())
        }
        if (exactMatches.size > 1) {
            return ResolutionResult.Ambiguous(target, exactMatches.size, exactMatches)
        }

        if (target.exactMatch) {
            return ResolutionResult.NotFound(target, "No visible element found with exact content description '$query'")
        }

        // 2. Partial content description
        val partialMatches = elements.filter { el ->
            el.contentDescription?.contains(query, ignoreCase = true) == true
        }

        return when {
            partialMatches.isEmpty() -> ResolutionResult.NotFound(target, "No visible element found containing content description '$query'")
            partialMatches.size == 1 -> ResolutionResult.Resolved(partialMatches.first())
            else -> ResolutionResult.Ambiguous(target, partialMatches.size, partialMatches)
        }
    }
}
