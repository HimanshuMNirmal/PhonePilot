package com.phonepilot.service

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.phonepilot.accessibility.NodeParser
import com.phonepilot.accessibility.ScreenObserver
import com.phonepilot.controller.AndroidController
import com.phonepilot.model.ScreenSnapshot
import java.lang.ref.WeakReference

/**
 * Core Accessibility Service for PhonePilot.
 * Observes UI changes across the Android system and hosts controller actuators.
 */
class PhonePilotAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "PhonePilot"

        @Volatile
        private var serviceRef: WeakReference<PhonePilotAccessibilityService>? = null

        /**
         * Global ScreenObserver instance. Always non-null, updated whenever service connects.
         */
        val screenObserver: ScreenObserver = ScreenObserver()

        /**
         * Active controller instance if the service is currently running.
         */
        @Volatile
        var controller: AndroidController? = null
            private set

        /**
         * Whether the service is currently connected and running.
         */
        val isRunning: Boolean
            get() = serviceRef?.get() != null

        /**
         * Safe accessor to the running service instance.
         */
        val instance: PhonePilotAccessibilityService?
            get() = serviceRef?.get()
    }

    private var lastObservedPackage: String? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        serviceRef = WeakReference(this)
        controller = AndroidController(this)
        Log.i(TAG, "AccessibilityService connected")

        // Perform initial capture of active window
        captureCurrentScreen("service_connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        val eventPackage = event.packageName?.toString()
        if (eventPackage != null && eventPackage != lastObservedPackage) {
            lastObservedPackage = eventPackage
            Log.i(TAG, "Active package: $eventPackage")
        }

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                Log.d(TAG, "Window state changed: $eventPackage")
                captureCurrentScreen("window_state_changed")
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                // Throttled or direct capture
                captureCurrentScreen("content_changed")
            }
            AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                val viewText = event.text.firstOrNull()?.toString()
                Log.d(TAG, "View clicked: package=$eventPackage text='$viewText'")
            }
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                captureCurrentScreen("scrolled")
            }
            else -> {
                // Other observed events
            }
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "AccessibilityService interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "AccessibilityService destroyed")
        serviceRef = null
        controller = null
        screenObserver.clear()
        lastObservedPackage = null
    }

    /**
     * Captures the current active window hierarchy, transforms it into our decoupled
     * [ScreenSnapshot] model, and notifies observers.
     */
    fun captureCurrentScreen(reason: String): ScreenSnapshot? {
        val rootNode = try {
            rootInActiveWindow
        } catch (e: Exception) {
            Log.w(TAG, "Failed to obtain rootInActiveWindow: ${e.message}")
            null
        }

        if (rootNode == null) {
            return null
        }

        val parsedRoot = NodeParser.parse(rootNode)
        val currentPkg = rootNode.packageName?.toString() ?: lastObservedPackage ?: "unknown"

        val snapshot = ScreenSnapshot(
            packageName = currentPkg,
            root = parsedRoot
        )

        screenObserver.updateSnapshot(snapshot)

        val interactiveCount = snapshot.interactiveElements.size
        val readableCount = snapshot.readableElements.size
        Log.i(TAG, "UI changed ($reason): package=$currentPkg, interactive=$interactiveCount, readable=$readableCount")

        return snapshot
    }
}
