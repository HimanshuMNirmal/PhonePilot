package com.phonepilot.agent

import com.phonepilot.action.PhonePilotAction

/**
 * Represents the planner's decision after observing the screen and task history.
 */
sealed class AgentDecision {

    /**
     * Agent should proceed with the specified [action].
     */
    data class NextAction(
        val action: PhonePilotAction,
        val reasoning: String
    ) : AgentDecision()

    /**
     * The agent determined that the user's goal has been achieved.
     */
    data class GoalAchieved(
        val summary: String
    ) : AgentDecision()

    /**
     * The agent determined that the goal cannot be achieved (e.g. app missing, target blocked).
     */
    data class GoalUnachievable(
        val reason: String
    ) : AgentDecision()
}
