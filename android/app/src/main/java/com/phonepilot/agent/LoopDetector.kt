package com.phonepilot.agent

import com.phonepilot.action.ActionResult

/**
 * Result of loop and stuck state analysis.
 */
sealed class LoopStatus {
    data object Normal : LoopStatus()
    data class Stuck(val reason: String) : LoopStatus()
}

/**
 * Detects repetitive actions, stuck states, or cycling behavior during autonomous task execution.
 */
class LoopDetector {

    /**
     * Analyzes execution history to determine if the agent is stuck in a loop.
     */
    fun check(history: List<StepRecord>): LoopStatus {
        if (history.size < 2) return LoopStatus.Normal

        val last = history.last()
        val secondLast = history[history.size - 2]

        // 1. Check for consecutive identical failures
        if (last.actionResult is ActionResult.Failure && secondLast.actionResult is ActionResult.Failure) {
            val lastFail = last.actionResult as ActionResult.Failure
            val prevFail = secondLast.actionResult as ActionResult.Failure
            if (last.action == secondLast.action || lastFail.reason == prevFail.reason) {
                return LoopStatus.Stuck(
                    "Repeated failure on action '${last.action::class.simpleName}': ${lastFail.reason}"
                )
            }
        }

        // 2. Check for repetitive identical action without any state/screen change
        if (last.action == secondLast.action &&
            last.packageNameBefore == last.packageNameAfter &&
            secondLast.packageNameBefore == secondLast.packageNameAfter &&
            last.packageNameBefore == secondLast.packageNameBefore
        ) {
            if (history.size >= 3) {
                val thirdLast = history[history.size - 3]
                if (thirdLast.action == last.action) {
                    return LoopStatus.Stuck(
                        "Repetitive action loop: '${last.action::class.simpleName}' executed 3 times with no UI progress"
                    )
                }
            }
        }

        // 3. Consecutive failures threshold (3 consecutive failures of any kind)
        if (history.size >= 3) {
            val lastThree = history.takeLast(3)
            if (lastThree.all { it.actionResult is ActionResult.Failure }) {
                return LoopStatus.Stuck("Terminated after 3 consecutive action failures")
            }
        }

        // 4. Ping-pong / state oscillation check (A -> B -> A -> B)
        if (history.size >= 4) {
            val p4 = history[history.size - 4].packageNameAfter
            val p3 = history[history.size - 3].packageNameAfter
            val p2 = history[history.size - 2].packageNameAfter
            val p1 = history.last().packageNameAfter
            if (p4 != null && p3 != null && p4 != p3 && p4 == p2 && p3 == p1) {
                return LoopStatus.Stuck("Oscillation detected between packages '$p4' and '$p3'")
            }
        }

        return LoopStatus.Normal
    }
}
