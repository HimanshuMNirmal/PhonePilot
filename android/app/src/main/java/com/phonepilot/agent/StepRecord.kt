package com.phonepilot.agent

import com.phonepilot.action.ActionResult
import com.phonepilot.action.PhonePilotAction

/**
 * Immutable audit record of a single step taken by the autonomous agent loop.
 */
data class StepRecord(
    val stepNumber: Int,
    val action: PhonePilotAction,
    val actionResult: ActionResult,
    val reasoning: String,
    val packageNameBefore: String?,
    val packageNameAfter: String?,
    val timestamp: Long = System.currentTimeMillis()
)
