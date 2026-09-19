package com.phonepilot.ai

import com.phonepilot.action.PhonePilotAction

/**
 * Result of parsing a natural language user command into a structured [PhonePilotAction].
 */
sealed class ParseResult {
    data class Success(
        val action: PhonePilotAction,
        val reasoning: String? = null
    ) : ParseResult()

    data class Failure(
        val reason: String
    ) : ParseResult()
}

/**
 * Common abstraction for transforming natural language user commands into structured [PhonePilotAction]s.
 * Can be backed by deterministic rule-based local parsing or remote LLM providers.
 */
interface AiCommandParser {
    /**
     * Parses the given [command] using optional current UI [context].
     */
    fun parse(command: String, context: SanitizedScreenContext? = null): ParseResult
}
