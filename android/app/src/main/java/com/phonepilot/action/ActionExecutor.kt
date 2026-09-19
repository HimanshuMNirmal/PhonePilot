package com.phonepilot.action

import android.util.Log
import com.phonepilot.accessibility.ScreenObserver
import com.phonepilot.controller.AndroidController
import com.phonepilot.model.ScreenSnapshot
import com.phonepilot.service.PhonePilotAccessibilityService
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Executes validated actions against the phone UI via [AndroidController] and [ScreenObserver].
 * Guarantees that failures never crash the host service and enforces strict logging hygiene.
 */
class ActionExecutor(
    private val screenObserver: ScreenObserver,
    private val controllerProvider: () -> AndroidController?,
    private val validator: ActionValidator
) {

    companion object {
        private const val TAG = "PhonePilot"
        private const val GESTURE_TIMEOUT_MS = 3_000L
    }

    /**
     * Executes the given [action] and returns a structured [ActionResult].
     */
    fun execute(action: PhonePilotAction): ActionResult {
        Log.i(TAG, "Action received: ${action::class.simpleName}")

        return try {
            when (action) {
                is PhonePilotAction.OpenApp -> executeOpenApp(action)
                is PhonePilotAction.Tap -> executeTap(action)
                is PhonePilotAction.LongPress -> executeLongPress(action)
                is PhonePilotAction.Type -> executeType(action)
                is PhonePilotAction.Scroll -> executeScroll(action)
                PhonePilotAction.Back -> executeBack()
                PhonePilotAction.Home -> executeHome()
                is PhonePilotAction.Wait -> executeWait(action)
                PhonePilotAction.ReadScreen -> executeReadScreen()
                is PhonePilotAction.FindElement -> executeFindElement(action)
            }
        } catch (e: Exception) {
            val actionName = action::class.simpleName ?: "Unknown"
            Log.e(TAG, "Unexpected error executing $actionName: ${e.message}")
            ActionResult.Failure(actionName, "Unexpected execution error: ${e.message}", recoverable = false)
        }
    }

    private fun executeOpenApp(action: PhonePilotAction.OpenApp): ActionResult {
        val controller = getControllerOrNull() ?: return controllerUnavailableResult("OpenApp")
        val success = controller.openApp(action.packageName)
        return if (success) {
            Log.i(TAG, "Action executed successfully: OpenApp (${action.packageName})")
            ActionResult.Success("OpenApp", "Launched application ${action.packageName}")
        } else {
            Log.w(TAG, "Action failed: OpenApp (${action.packageName})")
            ActionResult.Failure("OpenApp", "Failed to launch application ${action.packageName}", recoverable = true)
        }
    }

    private fun executeTap(action: PhonePilotAction.Tap): ActionResult {
        val controller = getControllerOrNull() ?: return controllerUnavailableResult("Tap")
        val snapshot = getFreshSnapshot("tap")

        return when (val res = ElementResolver.resolve(action.target, snapshot)) {
            is ResolutionResult.Resolved -> {
                Log.d(TAG, "Target resolved for Tap: ${res.element.id}")
                val latch = CountDownLatch(1)
                val completed = AtomicBoolean(false)

                controller.tap(res.element) { success ->
                    completed.set(success)
                    latch.countDown()
                }

                latch.await(GESTURE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                if (completed.get()) {
                    Log.i(TAG, "Action executed successfully: Tap")
                    ActionResult.Success("Tap", "Tapped element at (${res.element.centerX}, ${res.element.centerY})")
                } else {
                    ActionResult.Failure("Tap", "Tap gesture timed out or was cancelled by system", recoverable = true)
                }
            }
            is ResolutionResult.Coordinate -> {
                Log.d(TAG, "Direct coordinate Tap: (${res.x}, ${res.y})")
                val latch = CountDownLatch(1)
                val completed = AtomicBoolean(false)

                controller.tap(res.x, res.y) { success ->
                    completed.set(success)
                    latch.countDown()
                }

                latch.await(GESTURE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                if (completed.get()) {
                    Log.i(TAG, "Action executed successfully: Tap at (${res.x}, ${res.y})")
                    ActionResult.Success("Tap", "Tapped coordinates (${res.x}, ${res.y})")
                } else {
                    ActionResult.Failure("Tap", "Coordinate tap gesture cancelled or timed out", recoverable = true)
                }
            }
            is ResolutionResult.Ambiguous -> {
                Log.w(TAG, "Tap target ambiguous: matched ${res.matchCount} elements")
                ActionResult.Failure("Tap", "Ambiguous target: matched ${res.matchCount} elements", recoverable = true)
            }
            is ResolutionResult.NotFound -> {
                Log.w(TAG, "Tap target not found: ${res.reason}")
                ActionResult.Failure("Tap", "Target not found: ${res.reason}", recoverable = true)
            }
        }
    }

    private fun executeLongPress(action: PhonePilotAction.LongPress): ActionResult {
        val controller = getControllerOrNull() ?: return controllerUnavailableResult("LongPress")
        val snapshot = getFreshSnapshot("long_press")

        return when (val res = ElementResolver.resolve(action.target, snapshot)) {
            is ResolutionResult.Resolved -> {
                val latch = CountDownLatch(1)
                val completed = AtomicBoolean(false)

                controller.longPress(res.element, action.durationMs) { success ->
                    completed.set(success)
                    latch.countDown()
                }

                latch.await(action.durationMs + GESTURE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                if (completed.get()) {
                    Log.i(TAG, "Action executed successfully: LongPress")
                    ActionResult.Success("LongPress", "Long pressed element for ${action.durationMs}ms")
                } else {
                    ActionResult.Failure("LongPress", "LongPress gesture cancelled or timed out", recoverable = true)
                }
            }
            is ResolutionResult.Coordinate -> {
                val latch = CountDownLatch(1)
                val completed = AtomicBoolean(false)

                controller.longPress(res.x, res.y, action.durationMs) { success ->
                    completed.set(success)
                    latch.countDown()
                }

                latch.await(action.durationMs + GESTURE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                if (completed.get()) {
                    Log.i(TAG, "Action executed successfully: LongPress at (${res.x}, ${res.y})")
                    ActionResult.Success("LongPress", "Long pressed coordinates (${res.x}, ${res.y})")
                } else {
                    ActionResult.Failure("LongPress", "LongPress gesture cancelled or timed out", recoverable = true)
                }
            }
            is ResolutionResult.Ambiguous -> {
                ActionResult.Failure("LongPress", "Ambiguous target: matched ${res.matchCount} elements", recoverable = true)
            }
            is ResolutionResult.NotFound -> {
                ActionResult.Failure("LongPress", "Target not found: ${res.reason}", recoverable = true)
            }
        }
    }

    private fun executeType(action: PhonePilotAction.Type): ActionResult {
        val controller = getControllerOrNull() ?: return controllerUnavailableResult("Type")
        val snapshot = getFreshSnapshot("type")

        return when (val res = ElementResolver.resolve(action.target, snapshot)) {
            is ResolutionResult.Resolved -> {
                // Strict security and capability validation
                val validation = validator.validateElementForTyping(res.element)
                if (validation is ValidationResult.Invalid) {
                    Log.w(TAG, "Typing rejected: ${validation.reason}")
                    return ActionResult.Failure("Type", validation.reason, recoverable = validation.recoverable)
                }

                Log.d(TAG, "Type action executing on editable field")
                val success = controller.typeText(action.text, res.element)
                if (success) {
                    Log.i(TAG, "Action executed successfully: Type")
                    ActionResult.Success("Type", "Entered text into target element")
                } else {
                    Log.w(TAG, "Type action failed during text entry")
                    ActionResult.Failure("Type", "Failed to set text on editable node", recoverable = true)
                }
            }
            is ResolutionResult.Coordinate -> {
                ActionResult.Failure("Type", "Cannot type into raw coordinates; target must be an editable element", recoverable = false)
            }
            is ResolutionResult.Ambiguous -> {
                ActionResult.Failure("Type", "Ambiguous target: matched ${res.matchCount} elements", recoverable = true)
            }
            is ResolutionResult.NotFound -> {
                ActionResult.Failure("Type", "Target not found: ${res.reason}", recoverable = true)
            }
        }
    }

    private fun executeScroll(action: PhonePilotAction.Scroll): ActionResult {
        val controller = getControllerOrNull() ?: return controllerUnavailableResult("Scroll")
        val latch = CountDownLatch(1)
        val completed = AtomicBoolean(false)

        when (action.direction) {
            ScrollDirection.FORWARD -> {
                controller.scrollForward { success ->
                    completed.set(success)
                    latch.countDown()
                }
            }
            ScrollDirection.BACKWARD -> {
                controller.scrollBackward { success ->
                    completed.set(success)
                    latch.countDown()
                }
            }
        }

        latch.await(GESTURE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        return if (completed.get()) {
            Log.i(TAG, "Action executed successfully: Scroll ${action.direction}")
            ActionResult.Success("Scroll", "Scrolled ${action.direction.name.lowercase()}")
        } else {
            ActionResult.Failure("Scroll", "Scroll gesture cancelled or timed out", recoverable = true)
        }
    }

    private fun executeBack(): ActionResult {
        val controller = getControllerOrNull() ?: return controllerUnavailableResult("Back")
        val success = controller.back()
        return if (success) {
            Log.i(TAG, "Action executed successfully: Back")
            ActionResult.Success("Back", "Navigated back")
        } else {
            ActionResult.Failure("Back", "System Back action returned false", recoverable = true)
        }
    }

    private fun executeHome(): ActionResult {
        val controller = getControllerOrNull() ?: return controllerUnavailableResult("Home")
        val success = controller.home()
        return if (success) {
            Log.i(TAG, "Action executed successfully: Home")
            ActionResult.Success("Home", "Navigated to home screen")
        } else {
            ActionResult.Failure("Home", "System Home action returned false", recoverable = true)
        }
    }

    private fun executeWait(action: PhonePilotAction.Wait): ActionResult {
        Log.d(TAG, "Executing Wait: ${action.durationMs}ms")
        try {
            Thread.sleep(action.durationMs)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            return ActionResult.Failure("Wait", "Wait was interrupted", recoverable = true)
        }
        return ActionResult.Success("Wait", "Waited ${action.durationMs}ms")
    }

    private fun executeReadScreen(): ActionResult {
        val snapshot = getFreshSnapshot("read_screen")
        return if (snapshot != null) {
            val interactiveCount = snapshot.interactiveElements.size
            val readableCount = snapshot.readableElements.size
            Log.i(TAG, "Action executed successfully: ReadScreen (pkg=${snapshot.packageName})")
            ActionResult.Success(
                action = "ReadScreen",
                message = "Captured screen for ${snapshot.packageName}",
                details = mapOf(
                    "package" to snapshot.packageName,
                    "interactiveCount" to interactiveCount.toString(),
                    "readableCount" to readableCount.toString()
                )
            )
        } else {
            ActionResult.Failure("ReadScreen", "No active screen snapshot available", recoverable = true)
        }
    }

    private fun executeFindElement(action: PhonePilotAction.FindElement): ActionResult {
        val snapshot = getFreshSnapshot("find_element")
        return when (val res = ElementResolver.resolve(action.target, snapshot)) {
            is ResolutionResult.Resolved -> {
                val el = res.element
                Log.i(TAG, "Action executed successfully: FindElement (${el.id})")
                ActionResult.Success(
                    action = "FindElement",
                    message = "Element located",
                    details = mapOf(
                        "id" to el.id,
                        "text" to (el.text ?: ""),
                        "desc" to (el.contentDescription ?: ""),
                        "bounds" to el.bounds.toShortString(),
                        "clickable" to el.isClickable.toString()
                    )
                )
            }
            is ResolutionResult.Coordinate -> {
                ActionResult.Success(
                    action = "FindElement",
                    message = "Coordinate target",
                    details = mapOf("x" to res.x.toString(), "y" to res.y.toString())
                )
            }
            is ResolutionResult.Ambiguous -> {
                ActionResult.Failure("FindElement", "Ambiguous target: matched ${res.matchCount} elements", recoverable = true)
            }
            is ResolutionResult.NotFound -> {
                ActionResult.Failure("FindElement", "Element not found: ${res.reason}", recoverable = true)
            }
        }
    }

    private fun getFreshSnapshot(reason: String): ScreenSnapshot? {
        return PhonePilotAccessibilityService.instance?.captureCurrentScreen(reason)
            ?: screenObserver.getCurrentScreen()
    }

    private fun getControllerOrNull(): AndroidController? = controllerProvider()

    private fun controllerUnavailableResult(actionName: String): ActionResult =
        ActionResult.Failure(actionName, "AccessibilityService or AndroidController is not active/available", recoverable = false)
}
