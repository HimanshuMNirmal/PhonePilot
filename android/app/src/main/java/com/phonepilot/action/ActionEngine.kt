package com.phonepilot.action

import android.content.Context
import android.util.Log
import com.phonepilot.accessibility.ScreenObserver
import com.phonepilot.controller.AndroidController

/**
 * High-level facade for the PhonePilot Action Engine.
 * Serves as the single entry point for future AI agents and UI testing consoles.
 *
 * Enforces:
 *   Action -> Validate -> Resolve -> Execute -> Result
 */
class ActionEngine(
    val validator: ActionValidator,
    val executor: ActionExecutor
) {

    companion object {
        private const val TAG = "PhonePilot"

        /**
         * Factory function to construct an [ActionEngine] with standard dependencies.
         */
        fun create(
            screenObserver: ScreenObserver,
            controllerProvider: () -> AndroidController?,
            context: Context? = null
        ): ActionEngine {
            val validator = ActionValidator(context)
            val executor = ActionExecutor(screenObserver, controllerProvider, validator)
            return ActionEngine(validator, executor)
        }
    }

    /**
     * Validates and executes an action.
     * Guarantees that invalid actions are safely short-circuited before execution.
     */
    fun execute(action: PhonePilotAction): ActionResult {
        val actionName = action::class.simpleName ?: "Action"

        // 1. Precondition and parameter validation
        val validation = validator.validate(action)
        if (validation is ValidationResult.Invalid) {
            Log.w(TAG, "Validation failed for $actionName: ${validation.reason}")
            return ActionResult.Failure(
                action = actionName,
                reason = "Validation failed: ${validation.reason}",
                recoverable = validation.recoverable
            )
        }

        // 2. Execution & target resolution
        return executor.execute(action)
    }
}
