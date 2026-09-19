package com.phonepilot.controller

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.os.Bundle
import android.util.DisplayMetrics
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.phonepilot.model.UiElement

/**
 * Controller abstraction providing programmatic control over the Android UI.
 * Acts as the bridge between future Action Engine / AI layers and [AccessibilityService].
 */
class AndroidController(
    private val service: AccessibilityService
) {

    companion object {
        private const val TAG = "PhonePilot"
    }

    /**
     * Taps at the given screen coordinates (x, y).
     */
    fun tap(x: Float, y: Float, onComplete: ((Boolean) -> Unit)? = null) {
        Log.d(TAG, "Controller: tap($x, $y)")
        val gesture = GestureUtils.createTap(x, y)
        dispatch(gesture, onComplete)
    }

    /**
     * Taps the center of the specified [UiElement].
     */
    fun tap(element: UiElement, onComplete: ((Boolean) -> Unit)? = null) {
        val x = element.centerX.toFloat()
        val y = element.centerY.toFloat()
        Log.d(TAG, "Controller: tap element '${element.text ?: element.resourceId ?: element.className}' at ($x, $y)")
        tap(x, y, onComplete)
    }

    /**
     * Performs a long press at the given screen coordinates.
     */
    fun longPress(x: Float, y: Float, durationMs: Long = 800L, onComplete: ((Boolean) -> Unit)? = null) {
        Log.d(TAG, "Controller: longPress($x, $y, duration=${durationMs}ms)")
        val gesture = GestureUtils.createLongPress(x, y, durationMs)
        dispatch(gesture, onComplete)
    }

    /**
     * Performs a long press on the specified [UiElement].
     */
    fun longPress(element: UiElement, durationMs: Long = 800L, onComplete: ((Boolean) -> Unit)? = null) {
        val x = element.centerX.toFloat()
        val y = element.centerY.toFloat()
        longPress(x, y, durationMs, onComplete)
    }

    /**
     * Performs a directional swipe gesture.
     */
    fun swipe(
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        durationMs: Long = 300L,
        onComplete: ((Boolean) -> Unit)? = null
    ) {
        Log.d(TAG, "Controller: swipe(($startX, $startY) -> ($endX, $endY), duration=${durationMs}ms)")
        val gesture = GestureUtils.createSwipe(startX, startY, endX, endY, durationMs)
        dispatch(gesture, onComplete)
    }

    /**
     * Scrolls forward (swipes upward on the screen).
     */
    fun scrollForward(onComplete: ((Boolean) -> Unit)? = null) {
        val metrics = service.resources.displayMetrics
        val centerX = metrics.widthPixels / 2f
        val startY = metrics.heightPixels * 0.75f
        val endY = metrics.heightPixels * 0.25f
        swipe(centerX, startY, centerX, endY, durationMs = 350L, onComplete = onComplete)
    }

    /**
     * Scrolls backward (swipes downward on the screen).
     */
    fun scrollBackward(onComplete: ((Boolean) -> Unit)? = null) {
        val metrics = service.resources.displayMetrics
        val centerX = metrics.widthPixels / 2f
        val startY = metrics.heightPixels * 0.25f
        val endY = metrics.heightPixels * 0.75f
        swipe(centerX, startY, centerX, endY, durationMs = 350L, onComplete = onComplete)
    }

    /**
     * Triggers the Android system Back navigation action.
     */
    fun back(): Boolean {
        Log.d(TAG, "Controller: back()")
        return service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
    }

    /**
     * Triggers the Android system Home navigation action.
     */
    fun home(): Boolean {
        Log.d(TAG, "Controller: home()")
        return service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
    }

    /**
     * Types text into the currently focused or specified editable element.
     * Uses accessibility node action [AccessibilityNodeInfo.ACTION_SET_TEXT].
     */
    fun typeText(text: String, targetElement: UiElement? = null): Boolean {
        Log.d(TAG, "Controller: typeText length=${text.length}")

        val rootNode = service.rootInActiveWindow ?: return false
        val targetNode: AccessibilityNodeInfo? = if (targetElement?.resourceId != null) {
            val matching = rootNode.findAccessibilityNodeInfosByViewId(targetElement.resourceId)
            matching.firstOrNull() ?: rootNode.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        } else {
            rootNode.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        }

        if (targetNode == null) {
            Log.w(TAG, "Controller: No focused or matching editable node found for typing")
            return false
        }

        val arguments = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }

        return try {
            targetNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
        } catch (e: Exception) {
            Log.e(TAG, "Controller: Failed to set text: ${e.message}")
            false
        }
    }

    /**
     * Launches an application by its package name using an Android launch Intent.
     */
    fun openApp(packageName: String): Boolean {
        Log.d(TAG, "Controller: openApp($packageName)")
        val intent = service.packageManager.getLaunchIntentForPackage(packageName)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        } ?: return false

        return try {
            service.startActivity(intent)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Controller: Failed to launch $packageName: ${e.message}")
            false
        }
    }

    private fun dispatch(gesture: GestureDescription, onComplete: ((Boolean) -> Unit)?) {
        try {
            service.dispatchGesture(
                gesture,
                object : AccessibilityService.GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        super.onCompleted(gestureDescription)
                        Log.d(TAG, "Controller: Gesture completed successfully")
                        onComplete?.invoke(true)
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        super.onCancelled(gestureDescription)
                        Log.w(TAG, "Controller: Gesture cancelled")
                        onComplete?.invoke(false)
                    }
                },
                null
            )
        } catch (e: Exception) {
            Log.e(TAG, "Controller: Error dispatching gesture: ${e.message}")
            onComplete?.invoke(false)
        }
    }
}
