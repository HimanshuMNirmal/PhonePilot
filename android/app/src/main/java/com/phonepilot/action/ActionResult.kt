package com.phonepilot.action

/**
 * Standardized result of an action execution.
 * Contains enough semantic context for future AI agents or UI layers
 * without exposing raw Android accessibility objects or sensitive data.
 */
sealed class ActionResult {

    abstract val action: String

    /**
     * Action completed successfully.
     */
    data class Success(
        override val action: String,
        val message: String? = null,
        val details: Map<String, String> = emptyMap()
    ) : ActionResult() {
        override fun toString(): String = buildString {
            append("Success[$action]")
            if (!message.isNullOrBlank()) append(": $message")
            if (details.isNotEmpty()) append(" details=$details")
        }
    }

    /**
     * Action failed to validate, resolve, or execute.
     */
    data class Failure(
        override val action: String,
        val reason: String,
        val recoverable: Boolean = false
    ) : ActionResult() {
        override fun toString(): String =
            "Failure[$action]: $reason (recoverable=$recoverable)"
    }
}
