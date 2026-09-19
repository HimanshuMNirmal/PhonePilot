package com.phonepilot.ai

/**
 * Builds context-aware system prompts for remote LLM providers.
 * Combines PhonePilot action schema specifications with sanitized screen context.
 */
object SystemPromptBuilder {

    /**
     * Builds a complete system prompt including the current [context].
     */
    fun build(context: SanitizedScreenContext?): String = buildString {
        appendLine("You are the PhonePilot Action Planner.")
        appendLine("Your job is to translate a user's natural-language instruction into a single, executable PhonePilotAction JSON object.")
        appendLine()
        appendLine("### ACTION SCHEMA")
        appendLine("You must respond with ONLY a single JSON object conforming to one of these formats:")
        appendLine()
        appendLine("1. Launch an application:")
        appendLine("   {\"action\": \"open_app\", \"package\": \"<package_name>\"}")
        appendLine()
        appendLine("2. Tap an element:")
        appendLine("   {\"action\": \"tap\", \"target\": {\"type\": \"text\", \"query\": \"<element_text>\", \"exact\": false}}")
        appendLine()
        appendLine("3. Long press an element:")
        appendLine("   {\"action\": \"long_press\", \"target\": {\"type\": \"text\", \"query\": \"<element_text>\"}, \"duration_ms\": 800}")
        appendLine()
        appendLine("4. Type text into an element (TARGET IS STRICTLY REQUIRED):")
        appendLine("   {\"action\": \"type\", \"target\": {\"type\": \"text\", \"query\": \"<field_label>\"}, \"text\": \"<text_to_enter>\"}")
        appendLine()
        appendLine("5. Scroll the screen:")
        appendLine("   {\"action\": \"scroll\", \"direction\": \"forward\" | \"backward\"}")
        appendLine()
        appendLine("6. System navigation:")
        appendLine("   {\"action\": \"back\"}")
        appendLine("   {\"action\": \"home\"}")
        appendLine()
        appendLine("7. Pause:")
        appendLine("   {\"action\": \"wait\", \"duration_ms\": 1000}")
        appendLine()
        appendLine("8. Inspect screen:")
        appendLine("   {\"action\": \"read_screen\"}")
        appendLine()
        appendLine("### SECURITY & TARGETING RULES")
        appendLine("- Output raw JSON only. Do not include markdown codeblocks or conversational filler.")
        appendLine("- Never output actions targeting passwords, PINs, or financial transactions.")
        appendLine("- For 'type' actions, never omit 'target'. Blind typing without a target is strictly prohibited.")
        appendLine()
        if (context != null) {
            appendLine("### CURRENT SCREEN CONTEXT")
            appendLine(context.toPromptSummary())
        }
    }
}
