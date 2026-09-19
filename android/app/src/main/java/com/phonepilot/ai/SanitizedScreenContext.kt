package com.phonepilot.ai

import com.phonepilot.model.ScreenSnapshot
import com.phonepilot.model.UiElement

/**
 * Compact, privacy-safe representation of an interactive UI element.
 * Excludes sensitive data, passwords, and deep tree structures.
 */
data class SanitizedElement(
    val label: String,
    val resourceId: String? = null,
    val type: String,
    val isClickable: Boolean = false,
    val isEditable: Boolean = false
) {
    override fun toString(): String {
        val idPart = if (!resourceId.isNullOrEmpty()) " id=$resourceId" else ""
        val editPart = if (isEditable) " [editable]" else ""
        return "[$type$idPart] \"$label\"$editPart"
    }
}

/**
 * Compact, sanitized screen context containing only actionable UI information
 * needed for AI command understanding and targeting.
 */
data class SanitizedScreenContext(
    val packageName: String,
    val elements: List<SanitizedElement>
) {
    companion object {
        /**
         * Creates a [SanitizedScreenContext] from a [ScreenSnapshot].
         * Enforces strict privacy rules by redacting/excluding password fields.
         */
        fun from(snapshot: ScreenSnapshot?): SanitizedScreenContext {
            if (snapshot == null) {
                return SanitizedScreenContext("unknown", emptyList())
            }

            val sanitizedList = mutableListOf<SanitizedElement>()

            for (el in snapshot.interactiveElements) {
                // Strictly exclude or redact password fields
                if (el.isPassword) {
                    continue
                }

                val label = (el.text?.trim()?.takeIf { it.isNotEmpty() }
                    ?: el.contentDescription?.trim()?.takeIf { it.isNotEmpty() }) ?: continue

                val simpleType = el.className?.substringAfterLast('.') ?: "View"
                val shortId = el.resourceId?.substringAfter(":id/")

                sanitizedList.add(
                    SanitizedElement(
                        label = label,
                        resourceId = shortId,
                        type = simpleType,
                        isClickable = el.isClickable,
                        isEditable = el.isEditable
                    )
                )
            }

            return SanitizedScreenContext(
                packageName = snapshot.packageName,
                elements = sanitizedList
            )
        }
    }

    /**
     * Formats the context into a concise summary suitable for system prompts or logging.
     */
    fun toPromptSummary(): String = buildString {
        appendLine("Active Application: $packageName")
        if (elements.isEmpty()) {
            appendLine("No interactive elements detected on screen.")
        } else {
            appendLine("Visible Interactive Elements (${elements.size}):")
            elements.take(30).forEach { el ->
                appendLine(" - $el")
            }
        }
    }
}
