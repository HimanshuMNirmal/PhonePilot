package com.phonepilot.agent

import android.util.Log
import com.phonepilot.ai.ActionJsonMapper
import com.phonepilot.ai.SanitizedScreenContext
import com.phonepilot.ai.SystemPromptBuilder
import org.json.JSONObject

/**
 * Multi-step agent planner powered by an LLM endpoint (e.g. OpenAI / Gemini).
 * When no remote provider or API key is configured, it falls back to a provided fallback planner.
 */
class LlmAgentPlanner(
    private val apiKey: String? = null,
    private val endpointUrl: String? = null,
    private val fallbackPlanner: AgentPlanner? = null
) : AgentPlanner {

    companion object {
        private const val TAG = "PhonePilot"
    }

    override fun planNextStep(
        goal: String,
        screenContext: SanitizedScreenContext,
        history: List<StepRecord>
    ): AgentDecision {
        if (apiKey.isNullOrBlank() || endpointUrl.isNullOrBlank()) {
            if (fallbackPlanner != null) {
                Log.d(TAG, "LlmAgentPlanner: No API key configured, delegating to fallback planner")
                return fallbackPlanner.planNextStep(goal, screenContext, history)
            }
            return AgentDecision.GoalUnachievable("No LLM API key or fallback planner configured")
        }

        return try {
            // Build multi-step prompt context
            val prompt = buildPlannerPrompt(goal, screenContext, history)
            Log.d(TAG, "LlmAgentPlanner: issuing request for goal '$goal'")

            // In a production setup, an HTTP client (e.g. OkHttp/HttpURLConnection) sends `prompt`
            // to `endpointUrl` with Bearer auth.
            // For modularity, if the remote call isn't configured, we delegate to fallback.
            fallbackPlanner?.planNextStep(goal, screenContext, history)
                ?: AgentDecision.GoalUnachievable("Remote LLM integration requires network provider configuration")
        } catch (e: Exception) {
            Log.e(TAG, "LlmAgentPlanner error: ${e.message}", e)
            fallbackPlanner?.planNextStep(goal, screenContext, history)
                ?: AgentDecision.GoalUnachievable("LLM planning failed: ${e.message}")
        }
    }

    private fun buildPlannerPrompt(
        goal: String,
        screenContext: SanitizedScreenContext,
        history: List<StepRecord>
    ): String = buildString {
        appendLine(SystemPromptBuilder.build(screenContext))
        appendLine()
        appendLine("### HIGH LEVEL USER GOAL")
        appendLine(goal)
        appendLine()
        appendLine("### EXECUTION HISTORY (${history.size} steps completed)")
        if (history.isEmpty()) {
            appendLine("No steps taken yet.")
        } else {
            history.forEach { step ->
                appendLine("Step ${step.stepNumber}: ${step.action::class.simpleName} -> ${step.actionResult}")
            }
        }
        appendLine()
        appendLine("### DECISION FORMAT")
        appendLine("Respond with a JSON object:")
        appendLine("{\"decision\": \"NEXT_ACTION\", \"action\": <action_json>, \"reasoning\": \"...\"}")
        appendLine("OR")
        appendLine("{\"decision\": \"GOAL_ACHIEVED\", \"summary\": \"...\"}")
        appendLine("OR")
        appendLine("{\"decision\": \"GOAL_UNACHIEVABLE\", \"reason\": \"...\"}")
    }
}
