package com.phonepilot

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.phonepilot.action.ActionEngine
import com.phonepilot.action.ActionResult
import com.phonepilot.agent.LlmAgentPlanner
import com.phonepilot.agent.PhonePilotAgent
import com.phonepilot.agent.StepRecord
import com.phonepilot.agent.TaskState
import com.phonepilot.ai.LlmProvider
import com.phonepilot.service.PhonePilotAccessibilityService
import com.phonepilot.voice.VoiceManager

/**
 * Clean, production-ready AI console for PhonePilot.
 * Powered purely by online LLM planning and hands-free voice interaction.
 */
class MainActivity : Activity() {

    companion object {
        private const val PREFS_NAME = "phonepilot_prefs"
        private const val KEY_API_KEY = "api_key"
        private const val KEY_PROVIDER = "provider"
        private const val KEY_MODEL = "model"
        private const val KEY_TTS_ENABLED = "tts_enabled"
        private const val KEY_HANDS_FREE_ENABLED = "hands_free_enabled"
        private const val REQ_CODE_MIC = 101
        private const val REQ_CODE_HANDS_FREE = 102
    }

    private lateinit var prefs: SharedPreferences
    private lateinit var voiceManager: VoiceManager

    // UI - Status
    private lateinit var statusBadge: TextView
    private lateinit var settingsButton: Button

    // UI - AI Configuration
    private lateinit var providerToggleBtn: Button
    private lateinit var modelInput: EditText
    private lateinit var apiKeyInput: EditText
    private lateinit var saveKeyBtn: Button
    private lateinit var keyStatusText: TextView

    // UI - Autonomous Voice & Agent Console
    private lateinit var agentGoalInput: EditText
    private lateinit var micButton: Button
    private lateinit var ttsCheckbox: CheckBox
    private lateinit var handsFreeCheckbox: CheckBox
    private lateinit var startAgentButton: Button
    private lateinit var cancelAgentButton: Button
    private lateinit var agentStateBadge: TextView
    private lateinit var agentStatusView: TextView
    private lateinit var agentHistoryView: TextView

    // Core AI Agent
    private lateinit var llmPlanner: LlmAgentPlanner
    private lateinit var agent: PhonePilotAgent

    private val actionEngine: ActionEngine
        get() = PhonePilotAccessibilityService.actionEngine
            ?: ActionEngine.create(
                screenObserver = PhonePilotAccessibilityService.screenObserver,
                controllerProvider = { PhonePilotAccessibilityService.controller },
                context = this
            )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        voiceManager = VoiceManager(this)

        // Load saved provider and API key
        val savedProviderName = prefs.getString(KEY_PROVIDER, LlmProvider.GEMINI.name) ?: LlmProvider.GEMINI.name
        val savedProvider = try { LlmProvider.valueOf(savedProviderName) } catch (_: Exception) { LlmProvider.GEMINI }
        var savedModel = prefs.getString(KEY_MODEL, savedProvider.defaultModel) ?: savedProvider.defaultModel
        if (savedModel.contains("gemini-3.", ignoreCase = true)) {
            savedModel = "gemini-3.6-flash"
            prefs.edit().putString(KEY_MODEL, savedModel).apply()
        }
        val savedApiKey = prefs.getString(KEY_API_KEY, "") ?: ""

        // Pure online LLM planner
        llmPlanner = LlmAgentPlanner(
            provider = savedProvider,
            apiKey = savedApiKey.ifBlank { null },
            model = savedModel
        )

        agent = PhonePilotAgent(
            actionEngine = actionEngine,
            planner = llmPlanner,
            screenSnapshotProvider = {
                PhonePilotAccessibilityService.instance?.captureCurrentScreen("agent_loop")
                    ?: PhonePilotAccessibilityService.screenObserver.getCurrentScreen()
            }
        )

        // Wire hands-free wake word callback
        com.phonepilot.service.PilotService.onWakeWordDetected = { keyword ->
            runOnUiThread {
                agentStatusView.text = "★ Wake Word Detected: '$keyword'!\nReady for voice command (Deliverable 3)"
                Toast.makeText(this, "★ 'Hey Pilot' detected!", Toast.LENGTH_SHORT).show()
            }
        }

        // Wire hardware volume down shortcut
        PhonePilotAccessibilityService.onHardwareVoiceTrigger = {
            runOnUiThread {
                Toast.makeText(this, "Volume button triggered voice", Toast.LENGTH_SHORT).show()
                startVoiceListening()
            }
        }

        val autoStart = intent?.getBooleanExtra("start_hands_free", false) == true
        if ((autoStart || prefs.getBoolean(KEY_HANDS_FREE_ENABLED, false)) &&
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            prefs.edit().putBoolean(KEY_HANDS_FREE_ENABLED, true).apply()
            com.phonepilot.service.PilotService.start(this)
        }

        val root = createLayout()
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        updateServiceStatus()
        if (::handsFreeCheckbox.isInitialized) {
            handsFreeCheckbox.isChecked = com.phonepilot.service.PilotService.isRunning
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        voiceManager.destroy()
    }

    private fun updateServiceStatus() {
        val isEnabled = isAccessibilityServiceEnabled(this) || PhonePilotAccessibilityService.isRunning

        if (isEnabled) {
            statusBadge.text = "● ACCESSIBILITY SERVICE ACTIVE"
            statusBadge.setTextColor(Color.parseColor("#4CAF50"))
            statusBadge.background = createRoundedDrawable(Color.parseColor("#1B5E20"), cornerRadius = 14f)
            settingsButton.visibility = View.GONE
        } else {
            statusBadge.text = "○ ACCESSIBILITY PERMISSION REQUIRED"
            statusBadge.setTextColor(Color.parseColor("#FFA000"))
            statusBadge.background = createRoundedDrawable(Color.parseColor("#4E342E"), cornerRadius = 14f)
            settingsButton.visibility = View.VISIBLE
        }
    }

    private fun startAgentTask(goal: String) {
        val trimmed = goal.trim()
        if (trimmed.isEmpty()) return

        if (llmPlanner.apiKey.isNullOrBlank()) {
            Toast.makeText(this, "Please enter your AI API key first", Toast.LENGTH_LONG).show()
            apiKeyInput.requestFocus()
            return
        }

        hideKeyboard()

        if (ttsCheckbox.isChecked) {
            voiceManager.speak("Starting goal: $trimmed")
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
                agentStatusView.text = "Ready for voice or text goal"
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

                if (ttsCheckbox.isChecked) {
                    val spoken = state.lastAction?.let { it::class.simpleName } ?: "Thinking"
                    voiceManager.speak(spoken.toString())
                }
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

                if (ttsCheckbox.isChecked) {
                    voiceManager.speak("Goal completed in ${state.totalSteps} steps.")
                }
            }

            is TaskState.Failed -> {
                agentStateBadge.text = "✖ FAILED (at step ${state.failedAtStep})"
                agentStateBadge.setTextColor(Color.parseColor("#E57373"))
                agentStateBadge.background = createRoundedDrawable(Color.parseColor("#B71C1C"), cornerRadius = 12f)
                agentStatusView.text = "Error: ${state.reason}"
                agentStatusView.setTextColor(Color.parseColor("#E57373"))
                startAgentButton.isEnabled = true
                cancelAgentButton.isEnabled = false
                startAgentButton.alpha = 1.0f
                cancelAgentButton.alpha = 0.5f
                renderAgentHistory(state.history)

                if (ttsCheckbox.isChecked) {
                    voiceManager.speak("Goal failed: ${state.reason}")
                }
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

                if (ttsCheckbox.isChecked) {
                    voiceManager.speak("Task cancelled.")
                }
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
                appendLine("  • App: ${step.packageNameBefore ?: "unknown"} ➔ ${step.packageNameAfter ?: "unknown"}")
                if (step.actionResult is ActionResult.Failure) {
                    appendLine("  • Error: ${(step.actionResult as ActionResult.Failure).reason}")
                }
            }
        }
        agentHistoryView.text = text
    }

    private fun startVoiceListening() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_CODE_MIC)
            return
        }

        micButton.text = "🔴 Listening..."
        micButton.background = createRoundedDrawable(Color.parseColor("#D50000"), cornerRadius = 14f)

        voiceManager.startListening(
            onResult = { transcribedText ->
                runOnUiThread {
                    micButton.text = "🎙 Voice"
                    micButton.background = createRoundedDrawable(Color.parseColor("#7C4DFF"), cornerRadius = 14f)
                    agentGoalInput.setText(transcribedText)
                    Toast.makeText(this, "Heard: \"$transcribedText\"", Toast.LENGTH_SHORT).show()
                    startAgentTask(transcribedText)
                }
            },
            onError = { error ->
                runOnUiThread {
                    micButton.text = "🎙 Voice"
                    micButton.background = createRoundedDrawable(Color.parseColor("#7C4DFF"), cornerRadius = 14f)
                    Toast.makeText(this, error, Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val granted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
        when (requestCode) {
            REQ_CODE_MIC -> {
                if (granted) startVoiceListening()
                else Toast.makeText(this, "Microphone permission is required for voice input", Toast.LENGTH_LONG).show()
            }
            REQ_CODE_HANDS_FREE -> {
                if (granted) {
                    toggleHandsFree(true)
                } else {
                    handsFreeCheckbox.isChecked = false
                    Toast.makeText(this, "Microphone permission is required for hands-free wake", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun toggleHandsFree(enable: Boolean) {
        if (enable) {
            val permsNeeded = mutableListOf<String>()
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                permsNeeded.add(Manifest.permission.RECORD_AUDIO)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    permsNeeded.add(Manifest.permission.POST_NOTIFICATIONS)
                }
            }

            if (permsNeeded.isNotEmpty()) {
                requestPermissions(permsNeeded.toTypedArray(), REQ_CODE_HANDS_FREE)
                return
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "Please allow 'Display over other apps' for the floating bubble", Toast.LENGTH_LONG).show()
                val overlayIntent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    android.net.Uri.parse("package:$packageName")
                )
                startActivity(overlayIntent)
            }

            com.phonepilot.service.PilotService.start(this)
            prefs.edit().putBoolean(KEY_HANDS_FREE_ENABLED, true).apply()
            handsFreeCheckbox.isChecked = true
            Toast.makeText(this, "🛸 Floating Bubble Active! (Tap: 1-Shot, Hold: Always-Mic)", Toast.LENGTH_SHORT).show()
        } else {
            com.phonepilot.service.PilotService.stop(this)
            prefs.edit().putBoolean(KEY_HANDS_FREE_ENABLED, false).apply()
            handsFreeCheckbox.isChecked = false
            Toast.makeText(this, "Floating Bubble Dismissed", Toast.LENGTH_SHORT).show()
        }
    }

    private fun saveApiConfig() {
        val newKey = apiKeyInput.text.toString().trim()
        val newModel = modelInput.text.toString().trim()
        val currentProvider = llmPlanner.provider

        prefs.edit().apply {
            putString(KEY_API_KEY, newKey)
            putString(KEY_PROVIDER, currentProvider.name)
            putString(KEY_MODEL, newModel.ifBlank { currentProvider.defaultModel })
            apply()
        }

        llmPlanner.apiKey = newKey.ifBlank { null }
        llmPlanner.model = newModel.ifBlank { currentProvider.defaultModel }

        hideKeyboard()
        updateKeyStatusText()
        Toast.makeText(this, "AI Settings Saved", Toast.LENGTH_SHORT).show()
    }

    private fun toggleProvider() {
        val current = llmPlanner.provider
        val next = if (current == LlmProvider.GEMINI) LlmProvider.OPENAI else LlmProvider.GEMINI
        llmPlanner.provider = next
        providerToggleBtn.text = "Provider: ${next.displayName}"
        modelInput.setText(next.defaultModel)
        llmPlanner.model = next.defaultModel
        updateKeyStatusText()
    }

    private fun updateKeyStatusText() {
        val hasKey = !llmPlanner.apiKey.isNullOrBlank()
        if (hasKey) {
            keyStatusText.text = "✔ Online AI Active: ${llmPlanner.provider.displayName} (${llmPlanner.model})"
            keyStatusText.setTextColor(Color.parseColor("#81C784"))
        } else {
            keyStatusText.text = "⚠ Enter API Key to enable online AI planning"
            keyStatusText.setTextColor(Color.parseColor("#FFD54F"))
        }
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        currentFocus?.let { view ->
            imm?.hideSoftInputFromWindow(view.windowToken, 0)
        }
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
            setBackgroundColor(Color.parseColor("#0F141C"))
            isFillViewport = true
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 64, 48, 64)
        }

        // Branding Title
        val titleText = TextView(this).apply {
            text = "PhonePilot AI"
            textSize = 32f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
        }
        container.addView(titleText)

        val subtitleText = TextView(this).apply {
            text = "Autonomous Voice & LLM Mobile Agent"
            textSize = 14f
            setTextColor(Color.parseColor("#90CAF9"))
            setPadding(0, 4, 0, 28)
        }
        container.addView(subtitleText)

        // Accessibility Service Status Badge
        statusBadge = TextView(this).apply {
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(20, 10, 20, 10)
        }
        container.addView(statusBadge)

        settingsButton = Button(this).apply {
            text = "Grant Accessibility Permission"
            setTextColor(Color.WHITE)
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            background = createRoundedDrawable(Color.parseColor("#FF9100"), cornerRadius = 14f)
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = 12
            }
            layoutParams = params
            setPadding(24, 16, 24, 16)
            setOnClickListener { openAccessibilitySettings() }
            visibility = View.GONE
        }
        container.addView(settingsButton)

        // =========================================================================
        // AI Model & Key Configuration Card
        // =========================================================================
        val configCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = createRoundedDrawable(Color.parseColor("#171F2C"), cornerRadius = 24f)
            setPadding(36, 36, 36, 36)
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = 28
            }
            layoutParams = params
        }

        val configTitle = TextView(this).apply {
            text = "AI Model Configuration"
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
        }
        configCard.addView(configTitle)

        providerToggleBtn = Button(this).apply {
            text = "Provider: ${llmPlanner.provider.displayName}"
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            background = createRoundedDrawable(Color.parseColor("#26354A"), cornerRadius = 12f)
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = 14
            }
            layoutParams = params
            setPadding(20, 14, 20, 14)
            setOnClickListener { toggleProvider() }
        }
        configCard.addView(providerToggleBtn)

        modelInput = EditText(this).apply {
            hint = "Model Name"
            setText(llmPlanner.model)
            setHintTextColor(Color.parseColor("#78909C"))
            setTextColor(Color.WHITE)
            textSize = 13f
            background = createRoundedDrawable(Color.parseColor("#121924"), cornerRadius = 12f)
            setPadding(20, 16, 20, 16)
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = 10
            }
            layoutParams = params
        }
        configCard.addView(modelInput)

        apiKeyInput = EditText(this).apply {
            hint = "API Key"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(prefs.getString(KEY_API_KEY, ""))
            setHintTextColor(Color.parseColor("#78909C"))
            setTextColor(Color.WHITE)
            textSize = 13f
            background = createRoundedDrawable(Color.parseColor("#121924"), cornerRadius = 12f)
            setPadding(20, 16, 20, 16)
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = 10
            }
            layoutParams = params
        }
        configCard.addView(apiKeyInput)

        saveKeyBtn = Button(this).apply {
            text = "Save Configuration"
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            background = createRoundedDrawable(Color.parseColor("#00B0FF"), cornerRadius = 12f)
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = 12
            }
            layoutParams = params
            setPadding(20, 14, 20, 14)
            setOnClickListener { saveApiConfig() }
        }
        configCard.addView(saveKeyBtn)

        keyStatusText = TextView(this).apply {
            textSize = 12f
            setPadding(0, 10, 0, 0)
        }
        updateKeyStatusText()
        configCard.addView(keyStatusText)

        container.addView(configCard)

        // =========================================================================
        // Autonomous Agent Voice & Goal Console
        // =========================================================================
        val agentCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = createRoundedDrawable(Color.parseColor("#171F2C"), cornerRadius = 24f)
            setPadding(36, 36, 36, 36)
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = 28
            }
            layoutParams = params
        }

        val agentTitle = TextView(this).apply {
            text = "Agent Goal & Voice Input"
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
        }
        agentCard.addView(agentTitle)

        // State Badge
        agentStateBadge = TextView(this).apply {
            text = "● IDLE"
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#90A4AE"))
            background = createRoundedDrawable(Color.parseColor("#26354A"), cornerRadius = 10f)
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = 12
            }
            layoutParams = params
            setPadding(16, 8, 16, 8)
        }
        agentCard.addView(agentStateBadge)

        // Goal Input Row (Mic + EditText)
        val inputRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = 14
            }
            layoutParams = params
        }

        micButton = Button(this).apply {
            text = "🎙 Voice"
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            background = createRoundedDrawable(Color.parseColor("#7C4DFF"), cornerRadius = 14f)
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            ).apply {
                marginEnd = 10
            }
            layoutParams = params
            setPadding(22, 18, 22, 18)
            setOnClickListener { startVoiceListening() }
        }
        inputRow.addView(micButton)

        agentGoalInput = EditText(this).apply {
            hint = "Speak or enter goal (e.g. 'Turn on Wi-Fi')"
            setHintTextColor(Color.parseColor("#78909C"))
            setTextColor(Color.WHITE)
            textSize = 14f
            background = createRoundedDrawable(Color.parseColor("#121924"), cornerRadius = 14f)
            setPadding(22, 18, 22, 18)
            val params = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f)
            layoutParams = params
        }
        inputRow.addView(agentGoalInput)
        agentCard.addView(inputRow)

        // Audio TTS Feedback Toggle
        ttsCheckbox = CheckBox(this).apply {
            text = "🔊 Voice Feedback (Announce actions aloud)"
            isChecked = prefs.getBoolean(KEY_TTS_ENABLED, true)
            setTextColor(Color.parseColor("#B0BEC5"))
            textSize = 12f
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = 12
            }
            layoutParams = params
            setOnCheckedChangeListener { _, isChecked ->
                prefs.edit().putBoolean(KEY_TTS_ENABLED, isChecked).apply()
                voiceManager.isTtsEnabled = isChecked
            }
        }
        agentCard.addView(ttsCheckbox)

        // Floating Bubble Assistant Overlay Toggle
        handsFreeCheckbox = CheckBox(this).apply {
            text = "🛸 Floating Bubble Overlay (Tap: 1-Shot, Hold: Always-On Mic)"
            isChecked = com.phonepilot.service.PilotService.isRunning || prefs.getBoolean(KEY_HANDS_FREE_ENABLED, false)
            setTextColor(Color.parseColor("#38BDF8"))
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = 8
            }
            layoutParams = params
            setOnClickListener {
                toggleHandsFree(isChecked)
            }
        }
        agentCard.addView(handsFreeCheckbox)

        // Start & Cancel Action Buttons Row
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
            text = "▶ Start Agent"
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            background = createRoundedDrawable(Color.parseColor("#00E676"), cornerRadius = 14f)
            val params = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f).apply {
                marginEnd = 8
            }
            layoutParams = params
            setPadding(18, 18, 18, 18)
            setOnClickListener {
                startAgentTask(agentGoalInput.text.toString())
            }
        }
        agentButtonsRow.addView(startAgentButton)

        cancelAgentButton = Button(this).apply {
            text = "⏹ Cancel"
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            background = createRoundedDrawable(Color.parseColor("#D50000"), cornerRadius = 14f)
            val params = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f).apply {
                marginStart = 8
            }
            layoutParams = params
            setPadding(18, 18, 18, 18)
            isEnabled = false
            alpha = 0.5f
            setOnClickListener {
                agent.cancelTask()
            }
        }
        agentButtonsRow.addView(cancelAgentButton)
        agentCard.addView(agentButtonsRow)

        // Live Status & LLM Reasoning View
        agentStatusView = TextView(this).apply {
            text = "Ready to start autonomous agent loop"
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setTextColor(Color.parseColor("#90A4AE"))
            background = createRoundedDrawable(Color.parseColor("#121924"), cornerRadius = 12f)
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = 18
            }
            layoutParams = params
            setPadding(20, 16, 20, 16)
        }
        agentCard.addView(agentStatusView)

        // Step History View
        agentHistoryView = TextView(this).apply {
            text = "No history yet."
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setTextColor(Color.parseColor("#B0BEC5"))
            background = createRoundedDrawable(Color.parseColor("#121924"), cornerRadius = 12f)
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = 12
            }
            layoutParams = params
            setPadding(20, 16, 20, 16)
        }
        agentCard.addView(agentHistoryView)

        container.addView(agentCard)

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
