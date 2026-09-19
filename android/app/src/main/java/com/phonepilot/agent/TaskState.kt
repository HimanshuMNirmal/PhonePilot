package com.phonepilot.agent

import com.phonepilot.action.PhonePilotAction

/**
 * Lifecycle states of an autonomous PhonePilot agent task.
 */
sealed class TaskState {

    /**
     * Agent is idle and ready to receive a new goal.
     */
    data object Idle : TaskState()

    /**
     * Agent is actively executing an autonomous task loop.
     */
    data class Running(
        val step: Int,
        val maxSteps: Int,
        val currentGoal: String,
        val lastAction: PhonePilotAction? = null,
        val statusMessage: String = "Observing and planning..."
    ) : TaskState()

    /**
     * Goal was successfully achieved.
     */
    data class Success(
        val totalSteps: Int,
        val summary: String,
        val history: List<StepRecord>
    ) : TaskState()

    /**
     * Task terminated due to unresolvable error, loop detection, or max step limit.
     */
    data class Failed(
        val failedAtStep: Int,
        val reason: String,
        val history: List<StepRecord>
    ) : TaskState()

    /**
     * Task was explicitly stopped by the user.
     */
    data class Cancelled(
        val stoppedAtStep: Int,
        val history: List<StepRecord>
    ) : TaskState()
}
