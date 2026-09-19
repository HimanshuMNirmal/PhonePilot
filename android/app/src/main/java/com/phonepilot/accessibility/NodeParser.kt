package com.phonepilot.accessibility

import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.phonepilot.model.UiElement

/**
 * Safely parses Android [AccessibilityNodeInfo] hierarchies into decoupled [UiElement] trees.
 * Defensively catches node disappearance, protects against crashes, and redacts sensitive data.
 */
object NodeParser {

    private const val TAG = "PhonePilot"

    /**
     * Converts an [AccessibilityNodeInfo] tree into a [UiElement] tree.
     * Returns null if [rootNode] is null or unreadable.
     */
    fun parse(rootNode: AccessibilityNodeInfo?): UiElement? {
        if (rootNode == null) return null
        return try {
            parseNodeInternal(rootNode, depth = 0)
        } catch (e: Exception) {
            Log.w(TAG, "Error during accessibility node tree traversal: ${e.message}")
            null
        }
    }

    private fun parseNodeInternal(node: AccessibilityNodeInfo, depth: Int): UiElement? {
        if (depth > 50) {
            // Guard against unexpectedly deep or cyclical hierarchies
            return null
        }

        try {
            val bounds = Rect()
            node.getBoundsInScreen(bounds)

            val isPassword = node.isPassword
            val rawText = try { node.text?.toString() } catch (_: Exception) { null }
            val rawDesc = try { node.contentDescription?.toString() } catch (_: Exception) { null }

            // Redact password or highly sensitive content
            val text = if (isPassword) null else rawText
            val desc = if (isPassword) null else rawDesc

            val resourceId = try { node.viewIdResourceName } catch (_: Exception) { null }
            val className = try { node.className?.toString() } catch (_: Exception) { null }
            val packageName = try { node.packageName?.toString() } catch (_: Exception) { null }

            val isClickable = try { node.isClickable } catch (_: Exception) { false }
            val isLongClickable = try { node.isLongClickable } catch (_: Exception) { false }
            val isEnabled = try { node.isEnabled } catch (_: Exception) { true }
            val isVisible = try { node.isVisibleToUser } catch (_: Exception) { true }
            val isEditable = try { node.isEditable } catch (_: Exception) { false }
            val isScrollable = try { node.isScrollable } catch (_: Exception) { false }

            val childCount = try { node.childCount } catch (_: Exception) { 0 }
            val children = mutableListOf<UiElement>()

            for (i in 0 until childCount) {
                val childNode = try {
                    node.getChild(i)
                } catch (e: Exception) {
                    null
                }

                if (childNode != null) {
                    val childElement = parseNodeInternal(childNode, depth + 1)
                    if (childElement != null) {
                        children.add(childElement)
                    }
                }
            }

            // Generate a deterministic identifier for this element
            val id = buildString {
                append(packageName ?: "unknown")
                append(':')
                append(resourceId ?: className?.substringAfterLast('.') ?: "view")
                append(':')
                append(bounds.left).append(',').append(bounds.top).append('-')
                append(bounds.right).append(',').append(bounds.bottom)
            }

            return UiElement(
                id = id,
                resourceId = resourceId,
                packageName = packageName,
                className = className,
                text = text,
                contentDescription = desc,
                bounds = bounds,
                isClickable = isClickable,
                isLongClickable = isLongClickable,
                isEnabled = isEnabled,
                isVisible = isVisible,
                isEditable = isEditable,
                isScrollable = isScrollable,
                isPassword = isPassword,
                children = children
            )
        } catch (e: Exception) {
            // Node could have been detached, recycled, or disposed concurrently
            Log.d(TAG, "Skipping unreachable node: ${e.message}")
            return null
        }
    }
}
