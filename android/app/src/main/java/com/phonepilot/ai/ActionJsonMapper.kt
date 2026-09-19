package com.phonepilot.ai

import com.phonepilot.action.ElementTarget
import com.phonepilot.action.PhonePilotAction
import com.phonepilot.action.ScrollDirection
import org.json.JSONObject

/**
 * Serializes and deserializes between JSON payloads and strongly-typed [PhonePilotAction] instances.
 * Uses Android SDK's built-in [org.json.JSONObject].
 */
object ActionJsonMapper {

    /**
     * Converts a JSON string into a [PhonePilotAction].
     * Throws [IllegalArgumentException] if the schema or parameters are invalid.
     */
    fun fromJson(jsonStr: String): PhonePilotAction {
        val root = try {
            JSONObject(jsonStr.trim())
        } catch (e: Exception) {
            throw IllegalArgumentException("Invalid JSON format: ${e.message}", e)
        }
        return fromJsonObject(root)
    }

    /**
     * Converts a [JSONObject] into a [PhonePilotAction].
     */
    fun fromJsonObject(json: JSONObject): PhonePilotAction {
        val actionType = json.optString("action").lowercase().trim()
        if (actionType.isEmpty()) {
            throw IllegalArgumentException("Missing required 'action' field in JSON")
        }

        return when (actionType) {
            "open_app", "openapp" -> {
                val pkg = json.optString("package", json.optString("package_name")).trim()
                if (pkg.isEmpty()) throw IllegalArgumentException("OpenApp action requires 'package' field")
                PhonePilotAction.OpenApp(pkg)
            }
            "tap", "click" -> {
                val target = parseTarget(json.optJSONObject("target"), "Tap")
                PhonePilotAction.Tap(target)
            }
            "long_press", "longpress" -> {
                val target = parseTarget(json.optJSONObject("target"), "LongPress")
                val duration = json.optLong("duration", json.optLong("duration_ms", 800L))
                PhonePilotAction.LongPress(target, duration)
            }
            "type", "enter_text" -> {
                val targetObj = json.optJSONObject("target")
                    ?: throw IllegalArgumentException("Type action strictly requires an explicit 'target' object")
                val target = parseTarget(targetObj, "Type")
                val text = json.optString("text")
                PhonePilotAction.Type(target, text)
            }
            "scroll" -> {
                val dirStr = json.optString("direction", "forward").lowercase()
                val dir = if (dirStr == "backward" || dirStr == "up") ScrollDirection.BACKWARD else ScrollDirection.FORWARD
                val target = json.optJSONObject("target")?.let { parseTarget(it, "Scroll") }
                PhonePilotAction.Scroll(dir, target)
            }
            "back" -> PhonePilotAction.Back
            "home" -> PhonePilotAction.Home
            "wait" -> {
                val duration = json.optLong("duration", json.optLong("duration_ms", 1000L))
                PhonePilotAction.Wait(duration)
            }
            "read_screen", "readscreen" -> PhonePilotAction.ReadScreen
            "find", "find_element", "findelement" -> {
                val target = parseTarget(json.optJSONObject("target"), "FindElement")
                PhonePilotAction.FindElement(target)
            }
            else -> throw IllegalArgumentException("Unknown action type: '$actionType'")
        }
    }

    /**
     * Serializes a [PhonePilotAction] into a JSON representation.
     */
    fun toJson(action: PhonePilotAction): JSONObject {
        val json = JSONObject()
        when (action) {
            is PhonePilotAction.OpenApp -> {
                json.put("action", "open_app")
                json.put("package", action.packageName)
            }
            is PhonePilotAction.Tap -> {
                json.put("action", "tap")
                json.put("target", targetToJson(action.target))
            }
            is PhonePilotAction.LongPress -> {
                json.put("action", "long_press")
                json.put("target", targetToJson(action.target))
                json.put("duration_ms", action.durationMs)
            }
            is PhonePilotAction.Type -> {
                json.put("action", "type")
                json.put("target", targetToJson(action.target))
                // For privacy, text can be omitted or redacted in logging
                json.put("text", action.text)
            }
            is PhonePilotAction.Scroll -> {
                json.put("action", "scroll")
                json.put("direction", action.direction.name.lowercase())
                action.target?.let { json.put("target", targetToJson(it)) }
            }
            PhonePilotAction.Back -> json.put("action", "back")
            PhonePilotAction.Home -> json.put("action", "home")
            is PhonePilotAction.Wait -> {
                json.put("action", "wait")
                json.put("duration_ms", action.durationMs)
            }
            PhonePilotAction.ReadScreen -> json.put("action", "read_screen")
            is PhonePilotAction.FindElement -> {
                json.put("action", "find_element")
                json.put("target", targetToJson(action.target))
            }
        }
        return json
    }

    private fun parseTarget(json: JSONObject?, actionName: String): ElementTarget {
        if (json == null) {
            throw IllegalArgumentException("$actionName action requires a target specification")
        }

        val type = json.optString("type", "text").lowercase().trim()
        return when (type) {
            "text" -> {
                val query = json.optString("query", json.optString("text")).trim()
                val exact = json.optBoolean("exact", true)
                ElementTarget.Text(query, exact)
            }
            "content_description", "description", "desc" -> {
                val desc = json.optString("query", json.optString("description")).trim()
                val exact = json.optBoolean("exact", true)
                ElementTarget.ContentDescription(desc, exact)
            }
            "resource_id", "id" -> {
                val id = json.optString("id", json.optString("resource_id")).trim()
                ElementTarget.ResourceId(id)
            }
            "coordinates", "coord" -> {
                val x = json.optDouble("x", 0.0).toFloat()
                val y = json.optDouble("y", 0.0).toFloat()
                ElementTarget.Coordinates(x, y)
            }
            else -> {
                // Fallback: If query exists, assume Text target
                val query = json.optString("query", json.optString("text")).trim()
                ElementTarget.Text(query, exactMatch = true)
            }
        }
    }

    private fun targetToJson(target: ElementTarget): JSONObject {
        val json = JSONObject()
        when (target) {
            is ElementTarget.Text -> {
                json.put("type", "text")
                json.put("query", target.text)
                json.put("exact", target.exactMatch)
            }
            is ElementTarget.ContentDescription -> {
                json.put("type", "content_description")
                json.put("query", target.description)
                json.put("exact", target.exactMatch)
            }
            is ElementTarget.ResourceId -> {
                json.put("type", "resource_id")
                json.put("id", target.resourceId)
            }
            is ElementTarget.Coordinates -> {
                json.put("type", "coordinates")
                json.put("x", target.x)
                json.put("y", target.y)
            }
        }
        return json
    }
}
