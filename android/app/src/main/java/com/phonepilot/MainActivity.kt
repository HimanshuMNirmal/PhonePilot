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
import com.phonepilot.agent.LocalRuleAgentPlanner
import com.phonepilot.agent.PhonePilotAgent
import com.phonepilot.agent.StepRecord
import com.phonepilot.agent.TaskState
import com.phonepilot.ai.AiCommandParser
import com.phonepilot.ai.AppPackageResolver
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

    // Phase 4 Autonomous Agent Console UI
    private lateinit var agentGoalInput: EditText
    private lateinit var startAgentButton: Button
    private lateinit var cancelAgentButton: Button
    private lateinit var agentStateBadge: TextView
    private lateinit var agentStatusView: TextView
    private lateinit var agentHistoryView: TextView

    // AI Command Console UI (Phase 3.1)
    private lateinit var commandInput: EditText
    private lateinit var aiResultView: TextView

    // Phase 2 Action Console UI
    private lateinit var actionResultView: TextView

    private var unsubscribeScreenObserver: (() -> Unit)? = null

    // Offline-first AI parser & package resolver
    private lateinit var packageResolver: AppPackageResolver
    private lateinit var aiParser: AiCommandParser

    // Autonomous agent orchestrator
    private lateinit var agent: PhonePilotAgent

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
        packageResolver = AppPackageResolver(this)
        aiParser = LocalCommandParser(packageResolver)

        val localPlanner = LocalRuleAgentPlanner(packageResolver)
        agent = PhonePilotAgent(
            actionEngine = actionEngine,
            planner = localPlanner,
            screenSnapshotProvider = {
                PhonePilotAccessibilityService.instance?.captureCurrentScreen("agent_loop")
                    ?: PhonePilotAccessibilityService.screenObserver.getCurrentScreen()
            }
        )

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

            statusDescription.text = "PhonePilot is actively observing UI events and ready for autonomous agent execution."
            settingsButton.text = "Accessibility Settings"
            liveInfoContainer.visibility = View.VISIBLE
        } else {
            statusBadge.text = "○ ACCESSIBILITY SERVICE NOT ENABLED"
            statusBadge.setTextColor(Color.parseColor("#FFA000"))
            statusBadge.background = createRoundedDrawable(Color.parseColor("#4E342E"), cornerRadius = 16f)

            statusDescription.text = "To enable observation, action execution, and autonomous agent control, please grant PhonePilot accessibility permissions in Android Settings."
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

    private fun startAgentTask(goal: String) {
        val trimmed = goal.trim()
        if (trimmed.isEmpty()) return

        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
        currentFocus?.let { view ->
            imm?.hideSoftInputFromWindow(view.windowToken, 0)
        }

        agent.startTask(trimmed) { state ->
            renderAgentState(state)
        }
    }

    private fun renderAgentState(state: TaskState) {
        when (state) {
            is TaskState.Idle -> {
                agentStateBadge.text = "● IDLE"
                agentStateBadge.setTextColor(Color.parseColor("#90A4AE"))
                agentStateBadge.background = createRoundedDrawable(Color.parseColor("#37474F"), cornerRadius = 12f)
                agentStatusView.text = "Ready to accept autonomous multi-step goals"
                agentStatusView.setTextColor(Color.parseColor("#90A4AE"))
                startAgentButton.isEnabled = true
                cancelAgentButton.isEnabled = false
                startAgentButton.alpha = 1.0f
                cancelAgentButton.alpha = 0.5f
            }

            is TaskState.Running -> {
                agentStateBadge.text = "▶ RUNNING (Step ${state.step}/${state.maxSteps})"
                agentStateBadge.setTextColor(Color.parseColor("#80D8FF"))
                agentStateBadge.background = createRoundedDrawable(Color.parseColor("#0D47A1"), cornerRadius = 12f)
                val actionInfo = state.lastAction?.let { "Action: ${it::class.simpleName}" } ?: "Planning..."
                agentStatusView.text = "${state.statusMessage}\n$actionInfo"
                agentStatusView.setTextColor(Color.parseColor("#FFD54F"))
                startAgentButton.isEnabled = false
                cancelAgentButton.isEnabled = true
                startAgentButton.alpha = 0.5f
                cancelAgentButton.alpha = 1.0f
            }

            is TaskState.Success -> {
                agentStateBadge.text = "✔ SUCCESS (${state.totalSteps} steps)"
                agentStateBadge.setTextColor(Color.parseColor("#81C784"))
                agentStateBadge.background = createRoundedDrawable(Color.parseColor("#1B5E20"), cornerRadius = 12f)
                agentStatusView.text = state.summary
                agentStatusView.setTextColor(Color.parseColor("#81C784"))
                startAgentButton.isEnabled = true
                cancelAgentButton.isEnabled = false
                startAgentButton.alpha = 1.0f
                cancelAgentButton.alpha = 0.5f
                renderAgentHistory(state.history)
            }

            is TaskState.Failed -> {
                agentStateBadge.text = "✖ FAILED (at step ${state.failedAtStep})"
                agentStateBadge.setTextColor(Color.parseColor("#E57373"))
                agentStateBadge.background = createRoundedDrawable(Color.parseColor("#B71C1C"), cornerRadius = 12f)
                agentStatusView.text = "Failure Reason: ${state.reason}"
                agentStatusView.setTextColor(Color.parseColor("#E57373"))
                startAgentButton.isEnabled = true
                cancelAgentButton.isEnabled = false
                startAgentButton.alpha = 1.0f
                cancelAgentButton.alpha = 0.5f
                renderAgentHistory(state.history)
            }

            is TaskState.Cancelled -> {
                agentStateBadge.text = "⏹ CANCELLED (at step ${state.stoppedAtStep})"
                agentStateBadge.setTextColor(Color.parseColor("#FFB74D"))
                agentStateBadge.background = createRoundedDrawable(Color.parseColor("#E65100"), cornerRadius = 12f)
                agentStatusView.text = "Task was cancelled by user"
                agentStatusView.setTextColor(Color.parseColor("#FFB74D"))
                startAgentButton.isEnabled = true
                cancelAgentButton.isEnabled = false
                startAgentButton.alpha = 1.0f
                cancelAgentButton.alpha = 0.5f
                renderAgentHistory(state.history)
            }
        }
    }

    private fun renderAgentHistory(history: List<StepRecord>) {
        if (history.isEmpty()) {
            agentHistoryView.text = "No actions executed yet."
            return
        }

        val text = buildString {
            appendLine("Step Audit Trail:")
            history.forEach { step ->
                val resIcon = if (step.actionResult is ActionResult.Success) "✔" else "✖"
                appendLine("Step ${step.stepNumber}: ${step.action::class.simpleName} $resIcon")
                appendLine("  • Reason: ${step.reasoning}")
                appendLine("  • Package: ${step.packageNameBefore ?: "unknown"} ➔ ${step.packageNameAfter ?: "unknown"}")
                if (step.actionResult is ActionResult.Failure) {
                    appendLine("  • Error: ${(step.actionResult as ActionResult.Failure).reason}")
                }
            }
        }
        agentHistoryView.text = text
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
            text = "Phase 4 — Autonomous Agent Loop"
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

        // =========================================================================
        // Autonomous Agent Loop Console Card (PHASE 4)
        // =========================================================================
        val agentCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = createRoundedDrawable(Color.parseColor("#1A2138"), cornerRadius = 24f)
            setPadding(40, 40, 40, 40)
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = 32
            }
            layoutParams = params
        }

        val agentTitle = TextView(this).apply {
            text = "Autonomous Agent Console"
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
        }
        agentCard.addView(agentTitle)

        val agentDesc = TextView(this).apply {
            text = "User Goal ➔ Observe ➔ Evaluate Loop ➔ Plan Next ➔ ActionEngine ➔ Repeat"
            textSize = 12f
            setTextColor(Color.parseColor("#90CAF9"))
            setPadding(0, 4, 0, 20)
        }
        agentCard.addView(agentDesc)

        // State Badge
        agentStateBadge = TextView(this).apply {
            text = "● IDLE"
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#90A4AE"))
            background = createRoundedDrawable(Color.parseColor("#263238"), cornerRadius = 12f)
            setPadding(20, 10, 20, 10)
        }
        agentCard.addView(agentStateBadge)

        // Goal Input Field
        agentGoalInput = EditText(this).apply {
            hint = "e.g. 'Open Settings and tap Wi-Fi'"
            setText("Open Settings and tap Wi-Fi")
            setHintTextColor(Color.parseColor("#78909C"))
            setTextColor(Color.WHITE)
            textSize = 14f
            background = createRoundedDrawable(Color.parseColor("#15202B"), cornerRadius = 12f)
            setPadding(28, 24, 28, 24)
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = 16
            }
            layoutParams = params
        }
        agentCard.addView(agentGoalInput)

        // Action Buttons Row: Start & Cancel
        val agentButtonsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = 16
            }
            layoutParams = params
        }

        startAgentButton = Button(this).apply {
            text = "▶ Start Agent Task"
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            background = createRoundedDrawable(Color.parseColor("#00E676"), cornerRadius = 14f)
            val params = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f).apply {
                marginEnd = 8
            }
            layoutParams = params
            setPadding(20, 20, 20, 20)
            setOnClickListener {
                startAgentTask(agentGoalInput.text.toString())
            }
        }
        agentButtonsRow.addView(startAgentButton)

        cancelAgentButton = Button(this).apply {
            text = "⏹ Cancel Task"
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            background = createRoundedDrawable(Color.parseColor("#D50000"), cornerRadius = 14f)
            val params = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f).apply {
                marginStart = 8
            }
            layoutParams = params
            setPadding(20, 20, 20, 20)
            isEnabled = false
            alpha = 0.5f
            setOnClickListener {
                agent.cancelTask()
            }
        }
        agentButtonsRow.addView(cancelAgentButton)
        agentCard.addView(agentButtonsRow)

        // Preset Task Chips
        val presetTasksContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 16, 0, 0)
        }

        fun addAgentChip(goalText: String) {
            val chip = Button(this).apply {
                text = "⚡ Goal: \"$goalText\""
                textSize = 12f
                setTextColor(Color.parseColor("#B388FF"))
                background = createRoundedDrawable(Color.parseColor("#311B92"), cornerRadius = 10f)
                val params = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = 8
                }
                layoutParams = params
                setPadding(16, 12, 16, 12)
                setOnClickListener {
                    agentGoalInput.setText(goalText)
                    startAgentTask(goalText)
                }
            }
            presetTasksContainer.addView(chip)
        }

        addAgentChip("Open Settings and tap Wi-Fi")
        addAgentChip("Open Settings and scroll down")
        addAgentChip("Open Settings and go back")
        addAgentChip("Open PhonePilot")
        agentCard.addView(presetTasksContainer)

        // Agent Status Message View
        agentStatusView = TextView(this).apply {
            text = "Ready to start autonomous agent loop"
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setTextColor(Color.parseColor("#90A4AE"))
            background = createRoundedDrawable(Color.parseColor("#15202B"), cornerRadius = 12f)
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = 20
            }
            layoutParams = params
            setPadding(24, 20, 24, 20)
        }
        agentCard.addView(agentStatusView)

        // Step History View
        agentHistoryView = TextView(this).apply {
            text = "No history yet."
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setTextColor(Color.parseColor("#B0BEC5"))
            background = createRoundedDrawable(Color.parseColor("#15202B"), cornerRadius = 12f)
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = 12
            }
            layoutParams = params
            setPadding(24, 20, 24, 20)
        }
        agentCard.addView(agentHistoryView)

        container.addView(agentCard)

        // AI Command Console Card (PHASE 3.1)
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
            text = "Single-Command AI Console (Phase 3.1)"
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
        }
        aiCard.addView(aiTitle)

        val aiDesc = TextView(this).apply {
            text = "Single-turn instruction ➔ AiCommandParser ➔ ActionEngine"
            textSize = 12f
            setTextColor(Color.parseColor("#9E9E9E"))
            setPadding(0, 4, 0, 20)
        }
        aiCard.addView(aiDesc)

        commandInput = EditText(this).apply {
            hint = "e.g. 'open settings', 'tap wi-fi', 'go back'"
            setHintTextColor(Color.parseColor("#78909C"))
            setTextColor(Color.WHITE)
            textSize = 14f
            background = createRoundedDrawable(Color.parseColor("#263238"), cornerRadius = 12f)
            setPadding(28, 24, 28, 24)
        }
        aiCard.addView(commandInput)

        val executeButton = Button(this).apply {
            text = "Parse & Execute Single Command"
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
        aiCard.addView(chipsContainer)

        aiResultView = TextView(this).apply {
            text = "Ready for single command instruction"
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

        actionResultView = TextView(this).apply {
            text = "Ready to test actions"
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setTextColor(Color.parseColor("#90A4AE"))
            background = createRoundedDrawable(Color.parseColor("#263238"), cornerRadius = 12f)
            setPadding(24, 20, 24, 20)
        }
        consoleCard.addView(actionResultView)

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

        addTestButton("Test: Read Screen") {
            actionEngine.execute(PhonePilotAction.ReadScreen)
        }

        addTestButton("Test: Open Settings") {
            actionEngine.execute(PhonePilotAction.OpenApp("com.android.settings"))
        }

        addTestButton("Test: Open Settings & Tap 'Wi-Fi'") {
            val openRes = actionEngine.execute(PhonePilotAction.OpenApp("com.android.settings"))
            if (openRes is ActionResult.Failure) return@addTestButton openRes

            actionEngine.execute(PhonePilotAction.Wait(1200L))

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
