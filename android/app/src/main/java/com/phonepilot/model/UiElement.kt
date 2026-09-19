package com.phonepilot.model

import android.graphics.Rect

/**
 * Clean, decoupled internal representation of an observed Android UI element.
 * Completely independent of [android.view.accessibility.AccessibilityNodeInfo].
 */
data class UiElement(
    val id: String,
    val resourceId: String? = null,
    val packageName: String? = null,
    val className: String? = null,
    val text: String? = null,
    val contentDescription: String? = null,
    val bounds: Rect = Rect(),
    val isClickable: Boolean = false,
    val isLongClickable: Boolean = false,
    val isEnabled: Boolean = true,
    val isVisible: Boolean = true,
    val isEditable: Boolean = false,
    val isScrollable: Boolean = false,
    val isPassword: Boolean = false,
    val children: List<UiElement> = emptyList()
) {
    val centerX: Int get() = bounds.centerX()
    val centerY: Int get() = bounds.centerY()

    val hasReadableContent: Boolean
        get() = !text.isNullOrBlank() || !contentDescription.isNullOrBlank()

    val isInteractive: Boolean
        get() = isClickable || isLongClickable || isEditable || isScrollable

    /**
     * Recursively flattens this element and all its descendant children into a single list.
     */
    fun flatten(): List<UiElement> {
        val list = mutableListOf<UiElement>()
        collect(list)
        return list
    }

    private fun collect(accumulator: MutableList<UiElement>) {
        accumulator.add(this)
        for (child in children) {
            child.collect(accumulator)
        }
    }

    /**
     * Recursively finds the first element matching [predicate].
     */
    fun find(predicate: (UiElement) -> Boolean): UiElement? {
        if (predicate(this)) return this
        for (child in children) {
            val found = child.find(predicate)
            if (found != null) return found
        }
        return null
    }

    /**
     * Recursively finds all elements matching [predicate].
     */
    fun findAll(predicate: (UiElement) -> Boolean): List<UiElement> {
        val results = mutableListOf<UiElement>()
        findAllInternal(predicate, results)
        return results
    }

    private fun findAllInternal(predicate: (UiElement) -> Boolean, results: MutableList<UiElement>) {
        if (predicate(this)) {
            results.add(this)
        }
        for (child in children) {
            child.findAllInternal(predicate, results)
        }
    }

    /**
     * Produces a readable hierarchical string representation suitable for logging.
     * Passwords and sensitive text are always redacted.
     */
    fun toFormattedString(indent: Int = 0): String {
        val indentStr = "  ".repeat(indent)
        val builder = StringBuilder()
        builder.append(indentStr)

        val simpleClassName = className?.substringAfterLast('.') ?: "View"
        builder.append("[$simpleClassName]")

        if (!resourceId.isNullOrEmpty()) {
            val shortId = resourceId.substringAfter(":id/")
            builder.append(" id=$shortId")
        }

        if (isPassword) {
            builder.append(" text=[REDACTED_PASSWORD]")
        } else if (!text.isNullOrBlank()) {
            builder.append(" text=\"$text\"")
        }

        if (!contentDescription.isNullOrBlank()) {
            builder.append(" desc=\"$contentDescription\"")
        }

        if (isClickable) builder.append(" [clickable]")
        if (isEditable) builder.append(" [editable]")
        if (isScrollable) builder.append(" [scrollable]")

        builder.append(" bounds=${bounds.toShortString()}")

        for (child in children) {
            builder.append("\n")
            builder.append(child.toFormattedString(indent + 1))
        }

        return builder.toString()
    }
}
