package com.phonepilot.ai

import android.content.Context
import android.util.Log
import com.phonepilot.action.ElementTarget
import com.phonepilot.action.PhonePilotAction
import com.phonepilot.action.ScrollDirection

/**
 * Deterministic, offline-first natural language command parser.
 * Translates common user instructions into typed [PhonePilotAction]s using regex
 * and dynamic [AppPackageResolver] without requiring an external LLM service.
 */
class LocalCommandParser(
    private val packageResolver: AppPackageResolver
) : AiCommandParser {

    constructor(context: Context) : this(AppPackageResolver(context))

    companion object {
        private const val TAG = "PhonePilot"

        private val OPEN_REGEX = Regex("^(?:open|launch|start|run)\\s+(?:the\\s+)?(.+)$", RegexOption.IGNORE_CASE)
        private val TAP_REGEX = Regex("^(?:tap|click|press|select|choose)\\s+(?:on\\s+)?(?:the\\s+)?[\"']?(.+?)[\"']?$", RegexOption.IGNORE_CASE)
        private val LONG_PRESS_REGEX = Regex("^(?:long\\s+press|hold|longclick)\\s+(?:on\\s+)?(?:the\\s+)?[\"']?(.+?)[\"']?$", RegexOption.IGNORE_CASE)
        private val BACK_REGEX = Regex("^(?:back|go\\s+back|press\\s+back|navigate\\s+back)$", RegexOption.IGNORE_CASE)
        private val HOME_REGEX = Regex("^(?:home|go\\s+home|press\\s+home)$", RegexOption.IGNORE_CASE)
        private val SCROLL_DOWN_REGEX = Regex("^(?:scroll|scroll\\s+down|scroll\\s+forward|swipe\\s+up)$", RegexOption.IGNORE_CASE)
        private val SCROLL_UP_REGEX = Regex("^(?:scroll\\s+up|scroll\\s+backward|swipe\\s+down)$", RegexOption.IGNORE_CASE)
        private val WAIT_REGEX = Regex("^(?:wait|pause|sleep)\\s+(\\d+)\\s*(ms|millisecond|milliseconds|s|sec|seconds)?$", RegexOption.IGNORE_CASE)
        private val TYPE_REGEX = Regex("^(?:type|enter|input|write)\\s+[\"']?(.+?)[\"']?\\s+(?:in|into|on)\\s+[\"']?(.+?)[\"']?$", RegexOption.IGNORE_CASE)
        private val FIND_REGEX = Regex("^(?:find|where\\s+is|locate|search\\s+for)\\s+[\"']?(.+?)[\"']?$", RegexOption.IGNORE_CASE)
        private val READ_REGEX = Regex("^(?:read\\s+screen|inspect\\s+screen|what(?:'s|\\s+is)\\s+on\\s+screen)$", RegexOption.IGNORE_CASE)
    }

    override fun parse(command: String, context: SanitizedScreenContext?): ParseResult {
        val trimmed = command.trim()
        if (trimmed.isEmpty()) {
            return ParseResult.Failure("Command cannot be empty")
        }

        Log.d(TAG, "LocalCommandParser: parsing '$trimmed'")

        // 1. Navigation actions
        if (BACK_REGEX.matches(trimmed)) {
            return ParseResult.Success(PhonePilotAction.Back, reasoning = "Matched Back navigation pattern")
        }
        if (HOME_REGEX.matches(trimmed)) {
            return ParseResult.Success(PhonePilotAction.Home, reasoning = "Matched Home navigation pattern")
        }

        // 2. Scroll actions
        if (SCROLL_DOWN_REGEX.matches(trimmed)) {
            return ParseResult.Success(PhonePilotAction.Scroll(ScrollDirection.FORWARD), reasoning = "Matched Scroll forward pattern")
        }
        if (SCROLL_UP_REGEX.matches(trimmed)) {
            return ParseResult.Success(PhonePilotAction.Scroll(ScrollDirection.BACKWARD), reasoning = "Matched Scroll backward pattern")
        }

        // 3. Read screen
        if (READ_REGEX.matches(trimmed)) {
            return ParseResult.Success(PhonePilotAction.ReadScreen, reasoning = "Matched ReadScreen query pattern")
        }

        // 4. Wait
        WAIT_REGEX.find(trimmed)?.let { match ->
            val num = match.groupValues[1].toLongOrNull() ?: 1000L
            val unit = match.groupValues[2].lowercase()
            val durationMs = if (unit.startsWith("s")) num * 1000L else num
            return ParseResult.Success(PhonePilotAction.Wait(durationMs), reasoning = "Matched Wait pattern")
        }

        // 5. Open app
        OPEN_REGEX.find(trimmed)?.let { match ->
            val appQuery = match.groupValues[1].trim()
            val resolvedPackage = packageResolver.resolve(appQuery)
            return if (resolvedPackage != null) {
                ParseResult.Success(
                    PhonePilotAction.OpenApp(resolvedPackage),
                    reasoning = "Resolved '$appQuery' to package $resolvedPackage"
                )
            } else {
                ParseResult.Failure("Could not resolve installed app for '$appQuery'")
            }
        }

        // 6. Type into element
        TYPE_REGEX.find(trimmed)?.let { match ->
            val text = match.groupValues[1].trim()
            val targetName = match.groupValues[2].trim()
            return ParseResult.Success(
                PhonePilotAction.Type(
                    target = ElementTarget.Text(targetName, exactMatch = false),
                    text = text
                ),
                reasoning = "Parsed Type action for target '$targetName'"
            )
        }

        // 7. Long press
        LONG_PRESS_REGEX.find(trimmed)?.let { match ->
            val targetName = match.groupValues[1].trim()
            return ParseResult.Success(
                PhonePilotAction.LongPress(ElementTarget.Text(targetName, exactMatch = false)),
                reasoning = "Parsed LongPress action for target '$targetName'"
            )
        }

        // 8. Tap / Click
        TAP_REGEX.find(trimmed)?.let { match ->
            val targetName = match.groupValues[1].trim()
            return ParseResult.Success(
                PhonePilotAction.Tap(ElementTarget.Text(targetName, exactMatch = false)),
                reasoning = "Parsed Tap action for target '$targetName'"
            )
        }

        // 9. Find element
        FIND_REGEX.find(trimmed)?.let { match ->
            val targetName = match.groupValues[1].trim()
            return ParseResult.Success(
                PhonePilotAction.FindElement(ElementTarget.Text(targetName, exactMatch = false)),
                reasoning = "Parsed FindElement action for target '$targetName'"
            )
        }

        return ParseResult.Failure("Could not understand command: '$trimmed'")
    }
}
