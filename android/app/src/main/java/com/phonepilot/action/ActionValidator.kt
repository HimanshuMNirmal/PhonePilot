package com.phonepilot.action

import android.content.Context
import android.content.pm.PackageManager
import com.phonepilot.model.UiElement

/**
 * Result of validating an action before execution.
 */
sealed class ValidationResult {
    data object Valid : ValidationResult()
    data class Invalid(
        val reason: String,
        val recoverable: Boolean = false
    ) : ValidationResult()
}

/**
 * Validates actions and preconditions prior to execution.
 * Enforces safety guarantees, parameter boundaries, and security rules.
 */
class ActionValidator(
    private val context: Context? = null
) {

    companion object {
        private const val MAX_WAIT_MS = 30_000L
        private const val MIN_LONG_PRESS_MS = 100L
        private const val MAX_LONG_PRESS_MS = 5_000L
        private val PACKAGE_REGEX = Regex("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+$")
    }

    /**
     * Validates [action] structure and parameters.
     */
    fun validate(action: PhonePilotAction): ValidationResult {
        return when (action) {
            is PhonePilotAction.OpenApp -> validateOpenApp(action)
            is PhonePilotAction.Tap -> validateTarget(action.target, "Tap")
            is PhonePilotAction.LongPress -> validateLongPress(action)
            is PhonePilotAction.Type -> validateType(action)
            is PhonePilotAction.Scroll -> validateScroll(action)
            is PhonePilotAction.Wait -> validateWait(action)
            is PhonePilotAction.FindElement -> validateTarget(action.target, "FindElement")
            PhonePilotAction.Back,
            PhonePilotAction.Home,
            PhonePilotAction.ReadScreen -> ValidationResult.Valid
        }
    }

    /**
     * Specifically validates whether a resolved [UiElement] is safe and capable of receiving text input.
     * Enforces that password fields are strictly rejected.
     */
    fun validateElementForTyping(element: UiElement): ValidationResult {
        if (element.isPassword) {
            return ValidationResult.Invalid(
                reason = "Target element is a password field; automated typing is prohibited",
                recoverable = false
            )
        }
        if (!element.isVisible) {
            return ValidationResult.Invalid(
                reason = "Target element is not visible on screen",
                recoverable = true
            )
        }
        if (!element.isEditable) {
            return ValidationResult.Invalid(
                reason = "Target element is not an editable text input",
                recoverable = true
            )
        }
        return ValidationResult.Valid
    }

    private fun validateOpenApp(action: PhonePilotAction.OpenApp): ValidationResult {
        val pkg = action.packageName.trim()
        if (pkg.isEmpty()) {
            return ValidationResult.Invalid("Package name cannot be empty", recoverable = false)
        }
        if (!PACKAGE_REGEX.matches(pkg)) {
            return ValidationResult.Invalid("Invalid Android package name format: '$pkg'", recoverable = false)
        }
        if (context != null) {
            val pm = context.packageManager
            val launchIntent = pm.getLaunchIntentForPackage(pkg)
            if (launchIntent == null) {
                return ValidationResult.Invalid("Application '$pkg' is not installed or has no launchable activity", recoverable = false)
            }
        }
        return ValidationResult.Valid
    }

    private fun validateTarget(target: ElementTarget, actionName: String): ValidationResult {
        return when (target) {
            is ElementTarget.Text -> {
                if (target.text.isBlank()) {
                    ValidationResult.Invalid("$actionName target text cannot be blank", recoverable = false)
                } else ValidationResult.Valid
            }
            is ElementTarget.ContentDescription -> {
                if (target.description.isBlank()) {
                    ValidationResult.Invalid("$actionName target content description cannot be blank", recoverable = false)
                } else ValidationResult.Valid
            }
            is ElementTarget.ResourceId -> {
                if (target.resourceId.isBlank()) {
                    ValidationResult.Invalid("$actionName target resourceId cannot be blank", recoverable = false)
                } else ValidationResult.Valid
            }
            is ElementTarget.Coordinates -> {
                if (target.x < 0 || target.y < 0) {
                    ValidationResult.Invalid("$actionName coordinates cannot be negative: (${target.x}, ${target.y})", recoverable = false)
                } else ValidationResult.Valid
            }
        }
    }

    private fun validateLongPress(action: PhonePilotAction.LongPress): ValidationResult {
        val targetResult = validateTarget(action.target, "LongPress")
        if (targetResult is ValidationResult.Invalid) return targetResult

        if (action.durationMs < MIN_LONG_PRESS_MS || action.durationMs > MAX_LONG_PRESS_MS) {
            return ValidationResult.Invalid(
                "LongPress duration ${action.durationMs}ms is outside valid range ($MIN_LONG_PRESS_MS..$MAX_LONG_PRESS_MS ms)",
                recoverable = false
            )
        }
        return ValidationResult.Valid
    }

    private fun validateType(action: PhonePilotAction.Type): ValidationResult {
        val targetResult = validateTarget(action.target, "Type")
        if (targetResult is ValidationResult.Invalid) return targetResult

        if (action.target is ElementTarget.Coordinates) {
            return ValidationResult.Invalid("Type action requires a semantic element target (Text, ResourceId, or ContentDescription), not raw coordinates", recoverable = false)
        }

        return ValidationResult.Valid
    }

    private fun validateScroll(action: PhonePilotAction.Scroll): ValidationResult {
        if (action.target != null) {
            return validateTarget(action.target, "Scroll")
        }
        return ValidationResult.Valid
    }

    private fun validateWait(action: PhonePilotAction.Wait): ValidationResult {
        if (action.durationMs < 0) {
            return ValidationResult.Invalid("Wait duration cannot be negative: ${action.durationMs}ms", recoverable = false)
        }
        if (action.durationMs > MAX_WAIT_MS) {
            return ValidationResult.Invalid("Wait duration exceeds maximum allowed (${action.durationMs}ms > $MAX_WAIT_MS ms)", recoverable = false)
        }
        return ValidationResult.Valid
    }
}
