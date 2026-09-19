package com.phonepilot.agent

import android.content.Context
import android.util.Log
import com.phonepilot.action.ActionResult
import com.phonepilot.action.ElementTarget
import com.phonepilot.action.PhonePilotAction
import com.phonepilot.action.ScrollDirection
import com.phonepilot.ai.AppPackageResolver
import com.phonepilot.ai.LocalCommandParser
import com.phonepilot.ai.ParseResult
import com.phonepilot.ai.SanitizedScreenContext

/**
 * Deterministic, offline multi-step agent planner.
 * Decomposes compound user instructions (e.g. "Open Settings and tap Wi-Fi")
 * into sequential sub-goals and evaluates current screen state and audit history
 * to emit the next action or determine task completion.
 */
class LocalRuleAgentPlanner(
    private val packageResolver: AppPackageResolver,
    private val commandParser: LocalCommandParser = LocalCommandParser(packageResolver)
) : AgentPlanner {

    constructor(context: Context) : this(AppPackageResolver(context))

    companion object {
        private const val TAG = "PhonePilot"
        private val CLAUSE_SPLIT_REGEX = Regex("\\s+(?:and(?:\\s+then)?|then|,\\s*then|&)\\s+", RegexOption.IGNORE_CASE)
    }

    override fun planNextStep(
        goal: String,
        screenContext: SanitizedScreenContext,
        history: List<StepRecord>
    ): AgentDecision {
        val trimmedGoal = goal.trim()
        if (trimmedGoal.isEmpty()) {
            return AgentDecision.GoalUnachievable("Empty goal provided")
        }

        Log.d(TAG, "LocalRuleAgentPlanner: evaluating goal '$trimmedGoal' on package '${screenContext.packageName}'")

        // Decompose compound goal into clauses: e.g. ["Open Settings", "tap Wi-Fi"]
        val subGoals = trimmedGoal.split(CLAUSE_SPLIT_REGEX).map { it.trim() }.filter { it.isNotEmpty() }
        if (subGoals.isEmpty()) {
            return AgentDecision.GoalUnachievable("Unable to parse sub-goals from: $trimmedGoal")
        }

        // Determine which sub-goal needs to be performed next
        var subGoalIndex = 0
        var historyOffset = 0

        for (i in subGoals.indices) {
            val subGoal = subGoals[i]
            val isSatisfied = checkSubGoalSatisfied(subGoal, screenContext, history, historyOffset)
            if (isSatisfied.first) {
                historyOffset = isSatisfied.second
                subGoalIndex = i + 1
            } else {
                subGoalIndex = i
                break
            }
        }

        // If all sub-goals have been satisfied, we're done!
        if (subGoalIndex >= subGoals.size) {
            return AgentDecision.GoalAchieved(
                summary = "Successfully completed all steps for: $trimmedGoal"
            )
        }

        val currentSubGoal = subGoals[subGoalIndex]
        Log.d(TAG, "LocalRuleAgentPlanner: active sub-goal ($subGoalIndex/${subGoals.size}): '$currentSubGoal'")

        return planSubGoalAction(currentSubGoal, screenContext, history)
    }

    /**
     * Checks whether a specific sub-goal has already been satisfied by comparing screen state and history.
     */
    private fun checkSubGoalSatisfied(
        subGoal: String,
        screenContext: SanitizedScreenContext,
        history: List<StepRecord>,
        fromHistoryIndex: Int
    ): Pair<Boolean, Int> {
        val lower = subGoal.lowercase()

        // 1. App opening clause: "open <app>"
        if (lower.startsWith("open ") || lower.startsWith("launch ") || lower.startsWith("start ")) {
            val appQuery = subGoal.substringAfter(' ').trim()
            val expectedPkg = packageResolver.resolve(appQuery)

            // Check if this app was already successfully opened in history
            for (idx in fromHistoryIndex until history.size) {
                val step = history[idx]
                if (step.action is PhonePilotAction.OpenApp && step.actionResult is ActionResult.Success) {
                    val openedPkg = (step.action as PhonePilotAction.OpenApp).packageName
                    if (expectedPkg != null && openedPkg.equals(expectedPkg, ignoreCase = true)) {
                        return true to (idx + 1)
                    }
                }
            }

            // Fallback: If app was already open before task started
            if (expectedPkg != null && screenContext.packageName.equals(expectedPkg, ignoreCase = true)) {
                return true to fromHistoryIndex
            }
            return false to fromHistoryIndex
        }

        // 2. Tap clause: "tap <target>"
        if (lower.startsWith("tap ") || lower.startsWith("click ") || lower.startsWith("press ")) {
            val target = subGoal.substringAfter(' ').replace("\"", "").replace("'", "").trim()
            for (idx in fromHistoryIndex until history.size) {
                val step = history[idx]
                if (step.action is PhonePilotAction.Tap && step.actionResult is ActionResult.Success) {
                    val actTarget = (step.action as PhonePilotAction.Tap).target
                    if (actTarget is ElementTarget.Text &&
                        (actTarget.text.contains(target, ignoreCase = true) || target.contains(actTarget.text, ignoreCase = true))
                    ) {
                        return true to (idx + 1)
                    }
                }
            }
            return false to fromHistoryIndex
        }

        // 3. Scroll clause: "scroll down" / "scroll up"
        if (lower.startsWith("scroll") || lower.startsWith("swipe")) {
            for (idx in fromHistoryIndex until history.size) {
                val step = history[idx]
                if (step.action is PhonePilotAction.Scroll && step.actionResult is ActionResult.Success) {
                    return true to (idx + 1)
                }
            }
            return false to fromHistoryIndex
        }

        // 4. Back clause: "back" / "go back"
        if (lower == "back" || lower == "go back" || lower == "navigate back") {
            for (idx in fromHistoryIndex until history.size) {
                val step = history[idx]
                if (step.action is PhonePilotAction.Back && step.actionResult is ActionResult.Success) {
                    return true to (idx + 1)
                }
            }
            return false to fromHistoryIndex
        }

        // 5. Home clause: "home" / "go home"
        if (lower == "home" || lower == "go home") {
            for (idx in fromHistoryIndex until history.size) {
                val step = history[idx]
                if (step.action is PhonePilotAction.Home && step.actionResult is ActionResult.Success) {
                    return true to (idx + 1)
                }
            }
            return false to fromHistoryIndex
        }

        // 6. Type clause: "type <text> in <target>"
        if (lower.startsWith("type ") || lower.startsWith("enter ")) {
            for (idx in fromHistoryIndex until history.size) {
                val step = history[idx]
                if (step.action is PhonePilotAction.Type && step.actionResult is ActionResult.Success) {
                    return true to (idx + 1)
                }
            }
            return false to fromHistoryIndex
        }

        return false to fromHistoryIndex
    }

    /**
     * Determines the next action needed to advance or complete [subGoal].
     */
    private fun planSubGoalAction(
        subGoal: String,
        screenContext: SanitizedScreenContext,
        history: List<StepRecord>
    ): AgentDecision {
        val lower = subGoal.lowercase()

        // 1. App opening clause
        if (lower.startsWith("open ") || lower.startsWith("launch ") || lower.startsWith("start ")) {
            val appQuery = subGoal.substringAfter(' ').trim()
            val targetPkg = packageResolver.resolve(appQuery)
                ?: return AgentDecision.GoalUnachievable("Unable to resolve installed app for '$appQuery'")

            if (screenContext.packageName.equals(targetPkg, ignoreCase = true)) {
                return AgentDecision.GoalAchieved("App '$appQuery' is active")
            }

            // If we just executed OpenApp in the previous step and it succeeded, give it a moment to load
            val lastStep = history.lastOrNull()
            if (lastStep != null && lastStep.action is PhonePilotAction.OpenApp && lastStep.actionResult is ActionResult.Success) {
                return AgentDecision.NextAction(
                    action = PhonePilotAction.Wait(1000L),
                    reasoning = "Waiting for $appQuery to finish launching into foreground"
                )
            }

            return AgentDecision.NextAction(
                action = PhonePilotAction.OpenApp(targetPkg),
                reasoning = "Target app '$appQuery' is not in foreground. Opening $targetPkg."
            )
        }

        // 2. Tap clause
        if (lower.startsWith("tap ") || lower.startsWith("click ") || lower.startsWith("press ")) {
            val rawTarget = subGoal.substringAfter(' ').replace("\"", "").replace("'", "").trim()

            // Look for matching element in current screen context
            val matchingElement = findBestMatchingElement(rawTarget, screenContext)
            if (matchingElement != null) {
                return AgentDecision.NextAction(
                    action = PhonePilotAction.Tap(ElementTarget.Text(matchingElement.label, exactMatch = true)),
                    reasoning = "Found matching interactive element '${matchingElement.label}'. Tapping it."
                )
            }

            // If not found, check if we should scroll down to locate it
            val recentScrolls = history.takeLast(2).count { it.action is PhonePilotAction.Scroll }
            if (recentScrolls == 0) {
                return AgentDecision.NextAction(
                    action = PhonePilotAction.Scroll(ScrollDirection.FORWARD),
                    reasoning = "Target '$rawTarget' not visible on current screen. Scrolling down to search."
                )
            }

            return AgentDecision.GoalUnachievable("Target '$rawTarget' could not be found on screen")
        }

        // 3. Fallback: Parse via LocalCommandParser
        when (val parseResult = commandParser.parse(subGoal, screenContext)) {
            is ParseResult.Success -> {
                return AgentDecision.NextAction(
                    action = parseResult.action,
                    reasoning = parseResult.reasoning ?: "Parsed from command: $subGoal"
                )
            }
            is ParseResult.Failure -> {
                return AgentDecision.GoalUnachievable(parseResult.reason)
            }
        }
    }

    /**
     * Finds the best matching element on screen, with intelligent aliases (e.g. Wi-Fi <-> WLAN).
     */
    private fun findBestMatchingElement(
        query: String,
        screenContext: SanitizedScreenContext
    ): com.phonepilot.ai.SanitizedElement? {
        val lowerQuery = query.lowercase().trim()

        // 1. Exact or contains match on label
        val directMatch = screenContext.elements.firstOrNull {
            it.label.contains(lowerQuery, ignoreCase = true)
        }
        if (directMatch != null) return directMatch

        // 2. Alias match for Wi-Fi / Network
        if (lowerQuery.contains("wi-fi") || lowerQuery.contains("wifi")) {
            val wifiMatch = screenContext.elements.firstOrNull {
                val l = it.label.lowercase()
                l.contains("wlan") || l.contains("internet") || l.contains("network")
            }
            if (wifiMatch != null) return wifiMatch
        }

        // 3. Alias match for Bluetooth
        if (lowerQuery.contains("bluetooth")) {
            val btMatch = screenContext.elements.firstOrNull {
                it.label.contains("connected devices", ignoreCase = true)
            }
            if (btMatch != null) return btMatch
        }

        return null
    }
}
