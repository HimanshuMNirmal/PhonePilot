package com.phonepilot.model

/**
 * Immutable snapshot of the phone screen at a specific point in time.
 * Provides easy querying for elements without exposing low-level accessibility internals.
 */
data class ScreenSnapshot(
    val packageName: String,
    val windowTitle: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val root: UiElement? = null
) {
    /**
     * All UI elements in the current screen hierarchy, flattened.
     */
    val allElements: List<UiElement> by lazy {
        root?.flatten() ?: emptyList()
    }

    /**
     * Elements that can receive user interaction (clickable, editable, scrollable).
     */
    val interactiveElements: List<UiElement> by lazy {
        allElements.filter { it.isInteractive && it.isVisible }
    }

    /**
     * Elements containing readable text or content descriptions.
     */
    val readableElements: List<UiElement> by lazy {
        allElements.filter { it.hasReadableContent && it.isVisible }
    }

    /**
     * Finds the first element containing or matching [text].
     */
    fun findElementByText(text: String, exactMatch: Boolean = false): UiElement? {
        val trimmed = text.trim()
        return allElements.firstOrNull { element ->
            val elText = element.text?.trim()
            val elDesc = element.contentDescription?.trim()
            if (exactMatch) {
                elText.equals(trimmed, ignoreCase = true) || elDesc.equals(trimmed, ignoreCase = true)
            } else {
                (elText != null && elText.contains(trimmed, ignoreCase = true)) ||
                    (elDesc != null && elDesc.contains(trimmed, ignoreCase = true))
            }
        }
    }

    /**
     * Finds all elements containing or matching [text].
     */
    fun findElementsByText(text: String, exactMatch: Boolean = false): List<UiElement> {
        val trimmed = text.trim()
        return allElements.filter { element ->
            val elText = element.text?.trim()
            val elDesc = element.contentDescription?.trim()
            if (exactMatch) {
                elText.equals(trimmed, ignoreCase = true) || elDesc.equals(trimmed, ignoreCase = true)
            } else {
                (elText != null && elText.contains(trimmed, ignoreCase = true)) ||
                    (elDesc != null && elDesc.contains(trimmed, ignoreCase = true))
            }
        }
    }

    /**
     * Finds an element by resource ID (either full ID or short suffix).
     */
    fun findElementById(resourceId: String): UiElement? {
        val search = resourceId.substringAfter(":id/")
        return allElements.firstOrNull { element ->
            element.resourceId != null &&
                (element.resourceId == resourceId || element.resourceId.substringAfter(":id/") == search)
        }
    }
}
