package com.phonepilot.accessibility

import com.phonepilot.model.ScreenSnapshot
import com.phonepilot.model.UiElement
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference

/**
 * High-level screen observation API.
 * Provides a clean interface for querying the current visible UI state without
 * exposing raw Android Accessibility APIs to future AI / action planning layers.
 */
class ScreenObserver {

    private val latestSnapshot = AtomicReference<ScreenSnapshot?>()
    private val listeners = CopyOnWriteArrayList<(ScreenSnapshot) -> Unit>()

    /**
     * Returns the current screen snapshot.
     */
    fun getCurrentScreen(): ScreenSnapshot? = latestSnapshot.get()

    /**
     * Finds all visible elements matching the given text query.
     */
    fun findElementsByText(text: String, exactMatch: Boolean = false): List<UiElement> {
        return latestSnapshot.get()?.findElementsByText(text, exactMatch) ?: emptyList()
    }

    /**
     * Finds the first visible element matching the given text query.
     */
    fun findElementByText(text: String, exactMatch: Boolean = false): UiElement? {
        return latestSnapshot.get()?.findElementByText(text, exactMatch)
    }

    /**
     * Returns all currently visible interactive elements (clickable, editable, scrollable).
     */
    fun findInteractiveElements(): List<UiElement> {
        return latestSnapshot.get()?.interactiveElements ?: emptyList()
    }

    /**
     * Returns all currently visible clickable elements.
     */
    fun findClickableElements(): List<UiElement> {
        return latestSnapshot.get()?.interactiveElements?.filter { it.isClickable } ?: emptyList()
    }

    /**
     * Returns all currently visible editable elements (text input fields).
     */
    fun findEditableElements(): List<UiElement> {
        return latestSnapshot.get()?.interactiveElements?.filter { it.isEditable } ?: emptyList()
    }

    /**
     * Subscribes to real-time screen snapshot changes.
     * Returns an unregister function.
     */
    fun addListener(listener: (ScreenSnapshot) -> Unit): () -> Unit {
        listeners.add(listener)
        // Immediately notify with current snapshot if available
        latestSnapshot.get()?.let { listener(it) }
        return { listeners.remove(listener) }
    }

    /**
     * Called internally by [com.phonepilot.service.PhonePilotAccessibilityService]
     * when a UI state change is detected.
     */
    internal fun updateSnapshot(snapshot: ScreenSnapshot) {
        latestSnapshot.set(snapshot)
        for (listener in listeners) {
            try {
                listener(snapshot)
            } catch (_: Exception) {
                // Ignore listener errors to protect observation pipeline
            }
        }
    }

    /**
     * Clears cached state when service is disconnected.
     */
    internal fun clear() {
        latestSnapshot.set(null)
        listeners.clear()
    }
}
