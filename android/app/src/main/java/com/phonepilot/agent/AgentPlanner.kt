package com.phonepilot.agent

import com.phonepilot.ai.SanitizedScreenContext

/**
 * Strategy interface for multi-step autonomous planning.
 * Decides whether to continue execution with a new action or finish with success/failure.
 */
interface AgentPlanner {

    /**
     * Evaluates the current [screenContext], past [history], and user [goal]
     * to determine the next agent step or task completion.
     */
    fun planNextStep(
        goal: String,
        screenContext: SanitizedScreenContext,
        history: List<StepRecord>
    ): AgentDecision
}
