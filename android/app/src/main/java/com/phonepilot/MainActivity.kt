package com.phonepilot

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.phonepilot.action.ActionEngine
import com.phonepilot.action.ActionResult
import com.phonepilot.action.ElementTarget
import com.phonepilot.action.PhonePilotAction
import com.phonepilot.action.ScrollDirection
import com.phonepilot.action.ValidationResult
import com.phonepilot.ai.AiCommandParser
import com.phonepilot.ai.LocalCommandParser
import com.phonepilot.ai.ParseResult
import com.phonepilot.ai.SanitizedScreenContext
import com.phonepilot.model.ScreenSnapshot
import com.phonepilot.model.UiElement
import com.phonepilot.service.PhonePilotAccessibilityService

class MainActivity : Activity() {

    private lateinit var statusBadge: TextView
    private lateinit var statusDescription: TextView
    private lateinit var settingsButton: Button
    private lateinit var liveInfoContainer: LinearLayout
    private lateinit var livePackageText: TextView
    private lateinit var liveElementsText: TextView
    private lateinit var liveDetailsText: TextView

    // AI Command Console UI
    private lateinit var commandInput: EditText
    private lateinit var aiResultView: TextView

    // Phase 2 Action Console UI
    private lateinit var actionResultView: TextView

    private var unsubscribeScreenObserver: (() -> Unit)? = null

    // Offline-first AI parser
    private lateinit var aiParser: AiCommandParser

    // ActionEngine instance via service or explicit construction
    private val actionEngine: ActionEngine
        get() = PhonePilotAccessibilityService.actionEngine
            ?: ActionEngine.create(
                screenObserver = PhonePilotAccessibilityService.screenObserver,
                controllerProvider = { PhonePilotAccessibilityService.controller },
                context = this
            )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        aiParser = LocalCommandParser(this)

        val root = createLayout()
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        updateServiceStatus()

        // Subscribe to live screen observation updates
        unsubscribeScreenObserver?.invoke()
        unsubscribeScreenObserver = PhonePilotAccessibilityService.screenObserver.addListener { snapshot ->
            runOnUiThread {
                updateLiveObservation(snapshot)
            }
        }
    }

    override fun onPause() {
        super.onPause()
        unsubscribeScreenObserver?.invoke()
        unsubscribeScreenObserver = null
    }

    private fun updateServiceStatus() {
        val isEnabled = isAccessibilityServiceEnabled(this) || PhonePilotAccessibilityService.isRunning

        if (isEnabled) {
            statusBadge.text = "● ACCESSIBILITY SERVICE ACTIVE"
            statusBadge.setTextColor(Color.parseColor("#4CAF50"))
            statusBadge.background = createRoundedDrawable(Color.parseColor("#1B5E20"), cornerRadius = 16f)

            statusDescription.text = "PhonePilot is actively observing UI events and ready for AI instructions."
            settingsButton.text = "Accessibility Settings"
            liveInfoContainer.visibility = View.VISIBLE
        } else {
            statusBadge.text = "○ ACCESSIBILITY SERVICE NOT ENABLED"
            statusBadge.setTextColor(Color.parseColor("#FFA000"))
            statusBadge.background = createRoundedDrawable(Color.parseColor("#4E342E"), cornerRadius = 16f)

            statusDescription.text = "To enable Phase 1-3 observation, action execution, and AI control, please grant PhonePilot accessibility permissions in Android Settings."
            settingsButton.text = "Enable in Accessibility Settings"
            liveInfoContainer.visibility = View.GONE
        }
    }

    private fun updateLiveObservation(snapshot: ScreenSnapshot) {
        livePackageText.text = "Active App: ${snapshot.packageName}"
        liveElementsText.text = "Detected Elements: ${snapshot.interactiveElements.size} interactive, ${snapshot.readableElements.size} readable"

        val preview = snapshot.interactiveElements.take(5).joinToString("\n") { el ->
            val desc = el.text ?: el.contentDescription ?: el.resourceId?.substringAfter(":id/") ?: el.className?.substringAfterLast('.')
            "• [$desc] at (${el.centerX}, ${el.centerY})"
        }
        liveDetailsText.text = if (preview.isNotEmpty()) "Sample interactive elements:\n$preview" else "No interactive elements in view"
    }

    private fun runAiCommand(command: String) {
        val trimmed = command.trim()
        if (trimmed.isEmpty()) return

        aiResultView.text = "Parsing: \"$trimmed\"..."
        aiResultView.setTextColor(Color.parseColor("#FFD54F"))

        Thread {
            val freshSnapshot = PhonePilotAccessibilityService.instance?.captureCurrentScreen("ai_command")
                ?: PhonePilotAccessibilityService.screenObserver.getCurrentScreen()
            val sanitizedContext = SanitizedScreenContext.from(freshSnapshot)

            val parseResult = aiParser.parse(trimmed, sanitizedContext)

            when (parseResult) {
                is ParseResult.Failure -> {
                    runOnUiThread {
                        aiResultView.setTextColor(Color.parseColor("#E57373"))
                        aiResultView.text = "AI Parse Error: ${parseResult.reason}"
                    }
                }
                is ParseResult.Success -> {
                    val action = parseResult.action
                    runOnUiThread {
                        aiResultView.setTextColor(Color.parseColor("#80D8FF"))
                        aiResultView.text = "Parsed: ${action::class.simpleName}\nExecuting through ActionEngine..."
                    }

                    val actionResult = actionEngine.execute(action)
                    runOnUiThread {
                        when (actionResult) {
                            is ActionResult.Success -> {
                                aiResultView.setTextColor(Color.parseColor("#81C784"))
                                aiResultView.text = "AI Action: ${action::class.simpleName}\n$actionResult"
                            }
                            is ActionResult.Failure -> {
                                aiResultView.setTextColor(Color.parseColor("#E57373"))
                                aiResultView.text = "AI Action: ${action::class.simpleName}\n$actionResult"
                            }
                        }
                    }
                }
            }
        }.start()
    }

    private fun runAction(name: String, block: () -> ActionResult) {
        actionResultView.text = "Executing $name..."
        actionResultView.setTextColor(Color.parseColor("#FFD54F"))

        Thread {
            val result = block()
            runOnUiThread {
                when (result) {
                    is ActionResult.Success -> {
                        actionResultView.setTextColor(Color.parseColor("#81C784"))
                        actionResultView.text = result.toString()
                    }
                    is ActionResult.Failure -> {
                        actionResultView.setTextColor(Color.parseColor("#E57373"))
                        actionResultView.text = result.toString()
                    }
                }
            }
        }.start()
    }

    private fun isAccessibilityServiceEnabled(context: Context): Boolean {
        val expectedServiceName = "${context.packageName}/${PhonePilotAccessibilityService::class.java.canonicalName}"
        val enabledServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false

        return enabledServices.split(":").any { it.equals(expectedServiceName, ignoreCase = true) }
    }

    private fun openAccessibilitySettings() {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        startActivity(intent)
    }

    private fun createLayout(): View {
        val scrollView = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#121212"))
            isFillViewport = true
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 64, 48, 64)
        }

        // Title
        val titleText = TextView(this).apply {
            text = "PhonePilot"
            textSize = 30f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
        }
        container.addView(titleText)

        // Subtitle
        val subtitleText = TextView(this).apply {
            text = "Phase 3.1 — AI Integration Layer"
            textSize = 15f
            setTextColor(Color.parseColor("#9E9E9E"))
            setPadding(0, 8, 0, 36)
        }
        container.addView(subtitleText)

        // Status Card
        val statusCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = createRoundedDrawable(Color.parseColor("#1E1E1E"), cornerRadius = 24f)
            setPadding(40, 40, 40, 40)
        }

        statusBadge = TextView(this).apply {
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(24, 12, 24, 12)
        }
        statusCard.addView(statusBadge)

        statusDescription = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.parseColor("#CCCCCC"))
            setPadding(0, 24, 0, 32)
            setLineSpacing(6f, 1f)
        }
        statusCard.addView(statusDescription)

        settingsButton = Button(this).apply {
            setTextColor(Color.WHITE)
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            background = createRoundedDrawable(Color.parseColor("#2979FF"), cornerRadius = 16f)
            setPadding(32, 24, 32, 24)
            setOnClickListener { openAccessibilitySettings() }
        }
        statusCard.addView(settingsButton)

        container.addView(statusCard)

        // AI Command Console Card (NEW IN PHASE 3.1)
        val aiCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = createRoundedDrawable(Color.parseColor("#1E1E1E"), cornerRadius = 24f)
            setPadding(40, 40, 40, 40)
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = 32
            }
            layoutParams = params
        }

        val aiTitle = TextView(this).apply {
            text = "AI Command Console"
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
        }
        aiCard.addView(aiTitle)

        val aiDesc = TextView(this).apply {
            text = "Natural Language → AiCommandParser → PhonePilotAction → ActionEngine"
            textSize = 12f
            setTextColor(Color.parseColor("#9E9E9E"))
            setPadding(0, 4, 0, 20)
        }
        aiCard.addView(aiDesc)

        // Command Input Field
        commandInput = EditText(this).apply {
            hint = "e.g. 'open settings', 'tap wi-fi', 'go back'"
            setHintTextColor(Color.parseColor("#78909C"))
            setTextColor(Color.WHITE)
            textSize = 14f
            background = createRoundedDrawable(Color.parseColor("#263238"), cornerRadius = 12f)
            setPadding(28, 24, 28, 24)
        }
        aiCard.addView(commandInput)

        // Execute Button
        val executeButton = Button(this).apply {
            text = "Parse & Execute AI Command"
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            background = createRoundedDrawable(Color.parseColor("#00B0FF"), cornerRadius = 14f)
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = 16
            }
            layoutParams = params
            setPadding(24, 20, 24, 20)
            setOnClickListener {
                runAiCommand(commandInput.text.toString())
            }
        }
        aiCard.addView(executeButton)

        // Preset Quick Chips
        val chipsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 16, 0, 0)
        }

        fun addChipButton(commandText: String) {
            val chip = Button(this).apply {
                text = "⚡ \"$commandText\""
                textSize = 12f
                setTextColor(Color.parseColor("#80D8FF"))
                background = createRoundedDrawable(Color.parseColor("#1A237E"), cornerRadius = 10f)
                val params = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = 8
                }
                layoutParams = params
                setPadding(16, 12, 16, 12)
                setOnClickListener {
                    commandInput.setText(commandText)
                    runAiCommand(commandText)
                }
            }
            chipsContainer.addView(chip)
        }

        addChipButton("open settings")
        addChipButton("tap wi-fi")
        addChipButton("go back")
        addChipButton("scroll down")
        addChipButton("where is 'Action Engine Console'")
        aiCard.addView(chipsContainer)

        // AI Result Display
        aiResultView = TextView(this).apply {
            text = "Ready for natural language instruction"
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setTextColor(Color.parseColor("#90A4AE"))
            background = createRoundedDrawable(Color.parseColor("#263238"), cornerRadius = 12f)
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = 20
            }
            layoutParams = params
            setPadding(24, 20, 24, 20)
        }
        aiCard.addView(aiResultView)

        container.addView(aiCard)

        // Live observation Card
        liveInfoContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = createRoundedDrawable(Color.parseColor("#1E1E1E"), cornerRadius = 24f)
            setPadding(40, 40, 40, 40)
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = 32
            }
            layoutParams = params
            visibility = View.GONE
        }

        val liveTitle = TextView(this).apply {
            text = "Live Screen Observation"
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
        }
        liveInfoContainer.addView(liveTitle)

        livePackageText = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.parseColor("#80D8FF"))
            setPadding(0, 16, 0, 8)
        }
        liveInfoContainer.addView(livePackageText)

        liveElementsText = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.parseColor("#B0BEC5"))
            setPadding(0, 0, 0, 16)
        }
        liveInfoContainer.addView(liveElementsText)

        liveDetailsText = TextView(this).apply {
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setTextColor(Color.parseColor("#78909C"))
            setLineSpacing(4f, 1f)
        }
        liveInfoContainer.addView(liveDetailsText)

        container.addView(liveInfoContainer)

        // Action Engine Console Card (Phase 2)
        val consoleCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = createRoundedDrawable(Color.parseColor("#1E1E1E"), cornerRadius = 24f)
            setPadding(40, 40, 40, 40)
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = 32
            }
            layoutParams = params
        }

        val consoleTitle = TextView(this).apply {
            text = "Action Engine Console (Direct)"
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
        }
        consoleCard.addView(consoleTitle)

        val consoleDesc = TextView(this).apply {
            text = "Deterministic Action Schema → Validate → Resolve → Execute"
            textSize = 12f
            setTextColor(Color.parseColor("#9E9E9E"))
            setPadding(0, 4, 0, 20)
        }
        consoleCard.addView(consoleDesc)

        // Result display
        actionResultView = TextView(this).apply {
            text = "Ready to test actions"
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setTextColor(Color.parseColor("#90A4AE"))
            background = createRoundedDrawable(Color.parseColor("#263238"), cornerRadius = 12f)
            setPadding(24, 20, 24, 20)
        }
        consoleCard.addView(actionResultView)

        // Test buttons grid/column
        val buttonsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 24, 0, 0)
        }

        fun addTestButton(label: String, actionBlock: () -> ActionResult) {
            val btn = Button(this).apply {
                text = label
                textSize = 13f
                setTextColor(Color.WHITE)
                background = createRoundedDrawable(Color.parseColor("#37474F"), cornerRadius = 12f)
                val params = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = 12
                }
                layoutParams = params
                setPadding(20, 16, 20, 16)
                setOnClickListener {
                    runAction(label, actionBlock)
                }
            }
            buttonsContainer.addView(btn)
        }

        // Action buttons
        addTestButton("Test: Read Screen") {
            actionEngine.execute(PhonePilotAction.ReadScreen)
        }

        addTestButton("Test: Open Settings") {
            actionEngine.execute(PhonePilotAction.OpenApp("com.android.settings"))
        }

        addTestButton("Test: Open Settings & Tap 'Wi-Fi'") {
            val openRes = actionEngine.execute(PhonePilotAction.OpenApp("com.android.settings"))
            if (openRes is ActionResult.Failure) return@addTestButton openRes

            // Wait for Settings window to appear and capture
            actionEngine.execute(PhonePilotAction.Wait(1200L))

            // Tap Wi-Fi item in Settings (exact text match)
            actionEngine.execute(
                PhonePilotAction.Tap(ElementTarget.Text("Wi-Fi", exactMatch = true))
            )
        }

        addTestButton("Test: Find 'Action Engine Console' (On Screen)") {
            actionEngine.execute(
                PhonePilotAction.FindElement(ElementTarget.Text("Action Engine Console", exactMatch = true))
            )
        }

        addTestButton("Test: Back") {
            actionEngine.execute(PhonePilotAction.Back)
        }

        addTestButton("Test: Scroll Forward") {
            actionEngine.execute(PhonePilotAction.Scroll(ScrollDirection.FORWARD))
        }

        addTestButton("Test: Wait (1000ms)") {
            actionEngine.execute(PhonePilotAction.Wait(1000L))
        }

        addTestButton("Test: Ambiguous Target (query 'e')") {
            actionEngine.execute(
                PhonePilotAction.FindElement(ElementTarget.Text("e", exactMatch = false))
            )
        }

        addTestButton("Test: Password Rejection (Mock UiElement)") {
            val mockPasswordNode = UiElement(
                id = "mock.auth:id/password_input",
                className = "android.widget.EditText",
                isPassword = true,
                isEditable = true,
                isVisible = true
            )
            val validation = actionEngine.validator.validateElementForTyping(mockPasswordNode)
            if (validation is ValidationResult.Invalid) {
                ActionResult.Failure("Type", validation.reason, recoverable = validation.recoverable)
            } else {
                ActionResult.Success("Type", "Password field accepted (security rule failed!)")
            }
        }

        addTestButton("Test: Invalid Action (Empty Pkg)") {
            actionEngine.execute(PhonePilotAction.OpenApp(""))
        }

        consoleCard.addView(buttonsContainer)
        container.addView(consoleCard)

        scrollView.addView(container)
        return scrollView
    }

    private fun createRoundedDrawable(bgColor: Int, cornerRadius: Float): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(bgColor)
            setCornerRadius(cornerRadius)
        }
    }
}
