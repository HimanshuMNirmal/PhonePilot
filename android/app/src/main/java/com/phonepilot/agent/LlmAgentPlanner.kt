package com.phonepilot.agent

import android.util.Log
import com.phonepilot.ai.ActionJsonMapper
import com.phonepilot.ai.LlmClient
import com.phonepilot.ai.LlmProvider
import com.phonepilot.ai.SanitizedScreenContext
import org.json.JSONObject

/**
 * Pure AI autonomous agent planner powered exclusively by online LLM models (Gemini / OpenAI).
 * Evaluates goal, step history, and sanitized screen context on each turn to emit structured decisions.
 */
class LlmAgentPlanner(
    var provider: LlmProvider = LlmProvider.GEMINI,
    var apiKey: String? = null,
    var model: String = provider.defaultModel,
    var customBaseUrl: String? = null,
    private val llmClient: LlmClient = LlmClient()
) : AgentPlanner {

    companion object {
        private const val TAG = "PhonePilot"
    }

    override fun planNextStep(
        goal: String,
        screenContext: SanitizedScreenContext,
        history: List<StepRecord>
    ): AgentDecision {
        val currentKey = apiKey?.trim()
        if (currentKey.isNullOrEmpty()) {
            Log.w(TAG, "LlmAgentPlanner: No API key configured")
            return AgentDecision.GoalUnachievable("API key is not configured. Please enter your API key in Settings.")
        }

        return try {
            val systemPrompt = buildSystemPrompt()
            val userPrompt = buildUserPrompt(goal, screenContext, history)

            Log.i(TAG, "Querying ${provider.displayName} ($model) for goal: \"$goal\" (step ${history.size + 1})")
            val rawJson = llmClient.sendPrompt(
                provider = provider,
                apiKey = currentKey,
                model = model,
                systemPrompt = systemPrompt,
                userPrompt = userPrompt,
                customBaseUrl = customBaseUrl
            )

            Log.d(TAG, "LLM Response JSON:\n$rawJson")
            parseDecision(rawJson, goal)
        } catch (e: Exception) {
            Log.e(TAG, "LLM Planning failed: ${e.message}", e)
            AgentDecision.GoalUnachievable("LLM planning error: ${e.message}")
        }
    }

    private fun parseDecision(rawJson: String, goal: String): AgentDecision {
        val json = JSONObject(rawJson)

        val decisionType = json.optString("decision").uppercase().trim()
        val reasoning = json.optString("reasoning", "Decided by ${provider.displayName}").trim()

        when (decisionType) {
            "GOAL_ACHIEVED", "COMPLETE", "SUCCESS" -> {
                val summary = json.optString("summary", "Goal successfully completed: $goal")
                return AgentDecision.GoalAchieved(summary)
            }

            "GOAL_UNACHIEVABLE", "FAILED", "UNACHIEVABLE" -> {
                val reason = json.optString("reason", "Unable to complete task with available screen state")
                return AgentDecision.GoalUnachievable(reason)
            }

            "NEXT_ACTION", "ACTION", "" -> {
                val actionObj = json.optJSONObject("action") ?: json
                val action = ActionJsonMapper.fromJsonObject(actionObj)
                return AgentDecision.NextAction(action, reasoning)
            }

            else -> {
                throw IllegalArgumentException("Unknown decision type: '$decisionType'")
            }
        }
    }

    private fun buildSystemPrompt(): String = buildString {
        appendLine("You are the PhonePilot Autonomous Android Agent.")
        appendLine("Your objective is to accomplish the user's high-level goal on an Android phone by issuing one action at a time.")
        appendLine("After each action is executed, you will observe the updated screen state and execution history.")
        appendLine()
        appendLine("### DECISION FORMAT")
        appendLine("You MUST respond with a single valid JSON object in one of these three formats:")
        appendLine()
        appendLine("1. When another step is needed:")
        appendLine("   {")
        appendLine("     \"decision\": \"NEXT_ACTION\",")
        appendLine("     \"reasoning\": \"Brief explanation of why this action is taken\",")
        appendLine("     \"action\": { <PhonePilotAction JSON> }")
        appendLine("   }")
        appendLine()
        appendLine("2. When the user's goal is fully achieved on screen:")
        appendLine("   {")
        appendLine("     \"decision\": \"GOAL_ACHIEVED\",")
        appendLine("     \"summary\": \"Clear description of what was accomplished\"")
        appendLine("   }")
        appendLine()
        appendLine("3. When the goal cannot be achieved (app missing, target blocked):")
        appendLine("   {")
        appendLine("     \"decision\": \"GOAL_UNACHIEVABLE\",")
        appendLine("     \"reason\": \"Detailed reason why the goal cannot be satisfied\"")
        appendLine("   }")
        appendLine()
        appendLine("### ACTION SCHEMA SPECIFICATION")
        appendLine("- Open App:        {\"action\": \"open_app\", \"package\": \"<package_name>\"}")
        appendLine("- Tap:             {\"action\": \"tap\", \"target\": {\"type\": \"text\", \"query\": \"<visible_text>\", \"exact\": false}}")
        appendLine("                   Or target by description: {\"type\": \"content_description\", \"query\": \"<desc>\"}")
        appendLine("                   Or target by resource ID: {\"type\": \"resource_id\", \"id\": \"<id>\"}")
        appendLine("- Long Press:      {\"action\": \"long_press\", \"target\": { ... }, \"duration_ms\": 800}")
        appendLine("- Type Text:       {\"action\": \"type\", \"target\": { ... }, \"text\": \"<text_to_type>\"}  (target is MANDATORY)")
        appendLine("- Scroll:          {\"action\": \"scroll\", \"direction\": \"forward\" | \"backward\"}")
        appendLine("- Back / Home:     {\"action\": \"back\"} | {\"action\": \"home\"}")
        appendLine("- Wait / Settle:   {\"action\": \"wait\", \"duration_ms\": 1000}")
        appendLine()
        appendLine("### STRICT RULES")
        appendLine("1. Output ONLY raw valid JSON. No markdown backticks, no text outside the JSON.")
        appendLine("2. Never invent UI elements; target ONLY elements visible in the provided SCREEN CONTEXT.")
        appendLine("3. Never attempt to type passwords, PINs, or financial credentials.")
        appendLine("4. If the requested target requires scrolling to find, issue a 'scroll' forward action.")
    }

    private fun buildUserPrompt(
        goal: String,
        screenContext: SanitizedScreenContext,
        history: List<StepRecord>
    ): String = buildString {
        appendLine("USER GOAL: \"$goal\"")
        appendLine()
        appendLine("CURRENT ACTIVE PACKAGE: ${screenContext.packageName}")
        appendLine()
        appendLine("VISIBLE UI ELEMENTS ON SCREEN (${screenContext.elements.size}):")
        if (screenContext.elements.isEmpty()) {
            appendLine("  (No interactive or labeled elements detected)")
        } else {
            screenContext.elements.take(40).forEach { el ->
                appendLine("  - $el")
            }
        }
        appendLine()
        appendLine("AUDIT TRAIL / PAST ACTIONS (${history.size} executed so far):")
        if (history.isEmpty()) {
            appendLine("  (None, this is the first step)")
        } else {
            history.forEach { step ->
                val status = if (step.actionResult.toString().startsWith("Success")) "SUCCESS" else "FAILURE"
                appendLine("  Step ${step.stepNumber}: ${step.action::class.simpleName} [$status] (from pkg: ${step.packageNameBefore} -> ${step.packageNameAfter})")
                appendLine("    Reasoning: ${step.reasoning}")
            }
        }
        appendLine()
        appendLine("Determine the next decision now (NEXT_ACTION, GOAL_ACHIEVED, or GOAL_UNACHIEVABLE).")
    }
}
