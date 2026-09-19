package com.phonepilot.action

/**
 * Direction for a scroll action.
 */
enum class ScrollDirection {
    FORWARD,
    BACKWARD
}

/**
 * High-level, structured representation of an action executable by PhonePilot.
 * Completely independent of Android framework classes.
 */
sealed class PhonePilotAction {

    /**
     * Launch an application by its package name.
     */
    data class OpenApp(
        val packageName: String
    ) : PhonePilotAction()

    /**
     * Tap on a specific target element or coordinates.
     */
    data class Tap(
        val target: ElementTarget
    ) : PhonePilotAction()

    /**
     * Long press on a target element or coordinates.
     */
    data class LongPress(
        val target: ElementTarget,
        val durationMs: Long = 800L
    ) : PhonePilotAction()

    /**
     * Type text into an explicit target element.
     * Target is strictly required so that typing is never issued blindly.
     */
    data class Type(
        val target: ElementTarget,
        val text: String
    ) : PhonePilotAction()

    /**
     * Scroll the screen or a specific scrollable container.
     */
    data class Scroll(
        val direction: ScrollDirection = ScrollDirection.FORWARD,
        val target: ElementTarget? = null
    ) : PhonePilotAction()

    /**
     * Trigger the Android system Back navigation action.
     */
    data object Back : PhonePilotAction()

    /**
     * Trigger the Android system Home navigation action.
     */
    data object Home : PhonePilotAction()

    /**
     * Pause execution for a specified duration in milliseconds.
     */
    data class Wait(
        val durationMs: Long
    ) : PhonePilotAction()

    /**
     * Read and inspect the current visible screen hierarchy without performing actions.
     */
    data object ReadScreen : PhonePilotAction()

    /**
     * Locate a target element on the current screen without interacting with it.
     */
    data class FindElement(
        val target: ElementTarget
    ) : PhonePilotAction()
}
