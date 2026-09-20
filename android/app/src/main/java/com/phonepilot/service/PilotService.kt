package com.phonepilot.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import com.phonepilot.MainActivity
import com.phonepilot.action.ActionEngine
import com.phonepilot.agent.LlmAgentPlanner
import com.phonepilot.agent.PhonePilotAgent
import com.phonepilot.agent.TaskState
import com.phonepilot.ai.LlmProvider
import com.phonepilot.overlay.FloatingBubbleManager
import com.phonepilot.service.PhonePilotAccessibilityService
import com.phonepilot.util.AppLogger
import com.phonepilot.voice.SystemSpeechManager
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Single foreground service managing the PhonePilot assistant:
 * - Standby mode by default: Microphone is OFF so OS mic icon does not stay on!
 * - Long-Press (Hold ~600ms): Toggles Always-On speech listening ON/OFF.
 * - Single-Tap: Wakes up immediately for 1 command without needing wake word.
 * - Centralized persistent file logging to /sdcard/Android/data/com.phonepilot/files/phonepilot.log
 */
class PilotService : Service() {

    companion object {
        private const val TAG = "PhonePilot"
        const val CHANNEL_ID = "phonepilot_assistant_channel"
        const val NOTIFICATION_ID = 3001

        const val PREFS_NAME = "phonepilot_prefs"
        const val KEY_API_KEY = "api_key"
        const val KEY_MODEL = "model"
        const val KEY_PROVIDER = "provider"

        @Volatile
        var isRunning = false
            private set

        var onWakeWordDetected: ((String) -> Unit)? = null

        fun start(context: Context) {
            val intent = Intent(context, PilotService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, PilotService::class.java)
            context.stopService(intent)
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var notificationManager: NotificationManager? = null
    private var vibrator: Vibrator? = null
    private var toneGenerator: ToneGenerator? = null
    private var floatingBubbleManager: FloatingBubbleManager? = null
    private var systemSpeechManager: SystemSpeechManager? = null
    private var agent: PhonePilotAgent? = null
    private val isAgentRunning = AtomicBoolean(false)
    private var sessionTimeoutRunnable: Runnable? = null

    // Listening Modes
    private val isAlwaysListening = AtomicBoolean(false)
    private val isOneShotListening = AtomicBoolean(false)


    override fun onCreate() {
        super.onCreate()
        AppLogger.init(applicationContext)
        AppLogger.i(TAG, "PilotService: onCreate")
        isRunning = true

        notificationManager = getSystemService(NotificationManager::class.java)
        toneGenerator = try {
            ToneGenerator(AudioManager.STREAM_NOTIFICATION, 90)
        } catch (e: Exception) {
            AppLogger.w(TAG, "Could not initialize ToneGenerator: ${e.message}")
            null
        }

        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            vm?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }

        createNotificationChannel()
        startForegroundWithNotification("PhonePilot Ready (Mic Paused)")

        // Initialize Android System SpeechRecognizer & Autonomous Gemini Agent
        initSystemSpeechManager()
        initAgent()

        // Initialize and display the Floating Bubble Overlay in PAUSED_STANDBY state
        try {
            floatingBubbleManager = FloatingBubbleManager(applicationContext).apply {
                onSingleTap = {
                    triggerOneShotListening()
                }
                onLongPress = {
                    toggleAlwaysListening()
                }
                show()
                setState(FloatingBubbleManager.BubbleState.PAUSED_STANDBY, "Mic Paused (Hold to wake)")
            }
            AppLogger.i(TAG, "FloatingBubbleManager initialized in PAUSED_STANDBY mode")
        } catch (e: Exception) {
            AppLogger.e(TAG, "Could not initialize FloatingBubbleManager: ${e.message}", e)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        AppLogger.i(TAG, "PilotService: onStartCommand (startId=$startId)")
        return START_STICKY
    }

    override fun onDestroy() {
        AppLogger.i(TAG, "PilotService: onDestroy - releasing all resources")
        isRunning = false
        isAlwaysListening.set(false)
        isOneShotListening.set(false)

        try {
            systemSpeechManager?.destroy()
        } catch (_: Exception) {}
        systemSpeechManager = null

        try {
            toneGenerator?.release()
        } catch (_: Exception) {}
        toneGenerator = null

        try {
            floatingBubbleManager?.hide()
        } catch (_: Exception) {}
        floatingBubbleManager = null

        mainHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * Toggles Always-On wake-word spotting ON or OFF (triggered by holding bubble for ~600ms).
     */
    private fun toggleAlwaysListening() {
        if (!isAlwaysListening.get()) {
            // Enable Always-On
            isAlwaysListening.set(true)
            systemSpeechManager?.startListening(continuous = true)
            floatingBubbleManager?.setState(
                FloatingBubbleManager.BubbleState.ALWAYS_LISTENING,
                "🎙 Always Listening ('Hey Pilot')"
            )
            updateNotification("Always Listening for 'Hey Pilot' / Command...")
            playHapticFeedback(isWake = true)
            AppLogger.i(TAG, "[MODE_CHANGE] Always-on speech listening ENABLED via long-press.")
        } else {
            // Disable Always-On
            isAlwaysListening.set(false)
            isOneShotListening.set(false)
            systemSpeechManager?.stopListening()
            floatingBubbleManager?.setState(
                FloatingBubbleManager.BubbleState.PAUSED_STANDBY,
                "Mic Paused (Hold to wake)"
            )
            updateNotification("PhonePilot Ready (Mic Paused)")
            playHapticFeedback(isWake = false)
            AppLogger.i(TAG, "[MODE_CHANGE] Always-on speech listening DISABLED via long-press (Mic OFF).")
        }
    }

    /**
     * Triggers one single-turn listening window without needing wake-word (triggered by tapping bubble).
     */
    private fun triggerOneShotListening() {
        AppLogger.i(TAG, "[ONE_SHOT] Single tap detected. Activating one-shot command listening...")
        isOneShotListening.set(true)
        systemSpeechManager?.startListening(continuous = false)

        floatingBubbleManager?.setState(
            FloatingBubbleManager.BubbleState.ONE_SHOT_LISTENING,
            "🎙 Speak now..."
        )
        updateNotification("Listening for command...")
        playChimeTone()

        // Fallback auto-timeout after 7.5 seconds if speech recognition doesn't report result
        mainHandler.postDelayed({
            if (isOneShotListening.getAndSet(false)) {
                AppLogger.i(TAG, "[ONE_SHOT] One-shot window timed out.")
                if (!isAlwaysListening.get()) {
                    systemSpeechManager?.stopListening()
                    floatingBubbleManager?.setState(
                        FloatingBubbleManager.BubbleState.PAUSED_STANDBY,
                        "Mic Paused (Hold to wake)"
                    )
                    updateNotification("PhonePilot Ready (Mic Paused)")
                } else {
                    floatingBubbleManager?.setState(
                        FloatingBubbleManager.BubbleState.ALWAYS_LISTENING,
                        "🎙 Always Listening ('Hey Pilot')"
                    )
                    updateNotification("Always Listening for 'Hey Pilot' / Command...")
                }
            }
        }, 7500)
    }

    private fun initSystemSpeechManager() {
        AppLogger.i(TAG, "Initializing SystemSpeechManager...")
        systemSpeechManager = SystemSpeechManager(
            context = applicationContext,
            onPartialResult = { partial ->
                handleSpeechPartial(partial)
            },
            onFinalResult = { final ->
                handleSpeechFinal(final)
            },
            onErrorOccurred = { error ->
                handleSpeechError(error)
            }
        )
    }

    private fun handleSpeechPartial(text: String) {
        floatingBubbleManager?.updateTranscript("Heard: \"$text\"")
        checkWakeInSpeech(text, isFinal = false)
    }

    private fun handleSpeechFinal(text: String) {
        AppLogger.i(TAG, "[SPEECH_FINAL] Final recognized text: \"$text\"")
        val handledWake = checkWakeInSpeech(text, isFinal = true)
        if (!handledWake) {
            if (isOneShotListening.getAndSet(false)) {
                handleUserCommand(text)
            } else if (isAlwaysListening.get()) {
                handleUserCommand(text)
            }
        }
    }

    private fun checkWakeInSpeech(text: String, isFinal: Boolean): Boolean {
        val lower = text.lowercase()
        val wakeKeywords = listOf("hey pilot", "hi pilot", "open pilot", "pilot", "hello pilot", "ok pilot")
        val matchedWake = wakeKeywords.firstOrNull { lower.contains(it) }

        if (matchedWake != null) {
            if (isAlwaysListening.get()) {
                AppLogger.i(TAG, "[WAKE_TRIGGER] ★ WAKE WORD DETECTED VIA SYSTEM SPEECH: '$text' (matched '$matchedWake') ★")
                handleWakeWordDetected(matchedWake)

                val afterWake = lower.substringAfter(matchedWake).trim()
                if (afterWake.isNotEmpty() && afterWake.length > 2) {
                    AppLogger.i(TAG, "[COMMAND_EXTRACTED] Command in same breath: \"$afterWake\"")
                    handleUserCommand(afterWake)
                }
                return true
            }
        }
        return false
    }

    private fun initAgent() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val apiKey = prefs.getString(KEY_API_KEY, "") ?: ""
        var savedModel = prefs.getString(KEY_MODEL, "gemini-3.6-flash") ?: "gemini-3.6-flash"
        if (savedModel.contains("gemini-3.", ignoreCase = true)) {
            savedModel = "gemini-3.6-flash"
        }

        val llmPlanner = LlmAgentPlanner(
            provider = LlmProvider.GEMINI,
            apiKey = apiKey.ifBlank { null },
            model = savedModel
        )

        val actionEngine = ActionEngine.create(
            screenObserver = PhonePilotAccessibilityService.screenObserver,
            controllerProvider = { PhonePilotAccessibilityService.controller },
            context = applicationContext
        )

        agent = PhonePilotAgent(
            actionEngine = actionEngine,
            planner = llmPlanner,
            screenSnapshotProvider = {
                PhonePilotAccessibilityService.instance?.captureCurrentScreen("pilot_service")
                    ?: PhonePilotAccessibilityService.screenObserver.getCurrentScreen()
            }
        )
        AppLogger.i(TAG, "PhonePilotAgent initialized with Gemini (${llmPlanner.model})")
    }

    private fun handleUserCommand(command: String) {
        val trimmed = command.trim()
        if (trimmed.isEmpty()) return

        // Check if user is dismissing the session
        val lower = trimmed.lowercase()
        if (lower == "bye" || lower == "goodbye" || lower == "thanks" || lower == "thank you" ||
            lower == "stop" || lower == "turn off mic" || lower == "sleep" || lower == "that's all" || lower == "close") {
            AppLogger.i(TAG, "[SESSION_END] User ended session: \"$trimmed\"")
            floatingBubbleManager?.setState(
                FloatingBubbleManager.BubbleState.PAUSED_STANDBY,
                "See you! (Mic Paused)"
            )
            playHapticFeedback(isWake = false)
            endConversationalSession()
            return
        }

        AppLogger.i(TAG, "[GEMINI_AGENT] Handing goal to Gemini: \"$trimmed\"")
        floatingBubbleManager?.setState(
            FloatingBubbleManager.BubbleState.THINKING,
            "🧠 Planning: \"$trimmed\""
        )
        playHapticFeedback(isWake = true)

        val pilotAgent = agent
        if (pilotAgent == null) {
            AppLogger.e(TAG, "PhonePilotAgent is null! Cannot plan with Gemini.")
            return
        }

        isAgentRunning.set(true)
        systemSpeechManager?.stopListening()

        pilotAgent.startTask(goal = trimmed, maxSteps = 5) { taskState ->
            mainHandler.post {
                when (taskState) {
                    is TaskState.Running -> {
                        val status = "Step ${taskState.step}: ${taskState.statusMessage}"
                        AppLogger.i(TAG, "[GEMINI_STEP] $status")
                        floatingBubbleManager?.setState(
                            FloatingBubbleManager.BubbleState.THINKING,
                            status.take(45)
                        )
                    }
                    is TaskState.Success -> {
                        AppLogger.i(TAG, "[GEMINI_SUCCESS] Goal achieved: ${taskState.summary}")
                        floatingBubbleManager?.setState(
                            FloatingBubbleManager.BubbleState.SPEAKING,
                            "✔ ${taskState.summary.take(45)}"
                        )
                        playHapticFeedback(isWake = false)
                        isAgentRunning.set(false)

                        // Conversational Follow-Up: Stay attentive without wake word!
                        startConversationalFollowUp()
                    }
                    is TaskState.Failed -> {
                        AppLogger.w(TAG, "[GEMINI_FAILED] Goal failed: ${taskState.reason}")
                        floatingBubbleManager?.setState(
                            FloatingBubbleManager.BubbleState.THINKING,
                            "❌ ${taskState.reason.take(45)}"
                        )
                        isAgentRunning.set(false)

                        startConversationalFollowUp()
                    }
                    is TaskState.Cancelled -> {
                        AppLogger.i(TAG, "[GEMINI_CANCELLED] Task cancelled")
                        isAgentRunning.set(false)
                        endConversationalSession()
                    }
                    else -> {}
                }
            }
        }
    }

    private fun startConversationalFollowUp() {
        if (!isRunning) return
        AppLogger.i(TAG, "[SESSION_FOLLOW_UP] Attentive for follow-up command...")
        floatingBubbleManager?.setState(
            FloatingBubbleManager.BubbleState.ONE_SHOT_LISTENING,
            "🎙 Attentive (Speak anytime...)"
        )
        systemSpeechManager?.startListening(continuous = false)

        sessionTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        sessionTimeoutRunnable = Runnable {
            AppLogger.i(TAG, "[SESSION_TIMEOUT] Session silence timeout reached, sleeping.")
            endConversationalSession()
        }.also {
            mainHandler.postDelayed(it, 8000)
        }
    }

    private fun endConversationalSession() {
        sessionTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        sessionTimeoutRunnable = null
        isOneShotListening.set(false)
        isAlwaysListening.set(false)
        systemSpeechManager?.stopListening()
        floatingBubbleManager?.setState(
            FloatingBubbleManager.BubbleState.PAUSED_STANDBY,
            "Mic Paused (Tap to talk)"
        )
        updateNotification("PhonePilot Ready (Mic Paused)")
    }

    private fun handleSpeechError(error: Int) {
        if (isOneShotListening.getAndSet(false)) {
            AppLogger.d(TAG, "[ONE_SHOT] Error/timeout in one-shot mode: $error, returning to standby")
            if (!isAlwaysListening.get()) {
                floatingBubbleManager?.setState(
                    FloatingBubbleManager.BubbleState.PAUSED_STANDBY,
                    "Mic Paused (Hold to wake)"
                )
                updateNotification("PhonePilot Ready (Mic Paused)")
            }
        }
    }

    private fun handleWakeWordDetected(keyword: String) {
        mainHandler.post {
            playHapticFeedback(isWake = true)
            playChimeTone()

            floatingBubbleManager?.setState(
                FloatingBubbleManager.BubbleState.WOKE,
                "★ Woke: '$keyword'!"
            )
            updateNotification("★ WOKE: '$keyword' detected!")
            onWakeWordDetected?.invoke(keyword)

            isOneShotListening.set(true)
            systemSpeechManager?.startListening(continuous = false)

            mainHandler.postDelayed({
                if (isRunning && isAlwaysListening.get()) {
                    updateNotification("Always Listening for 'Hey Pilot'...")
                    floatingBubbleManager?.setState(FloatingBubbleManager.BubbleState.ALWAYS_LISTENING)
                } else if (isRunning) {
                    updateNotification("PhonePilot Ready (Mic Paused)")
                    floatingBubbleManager?.setState(FloatingBubbleManager.BubbleState.PAUSED_STANDBY)
                }
            }, 6000)
        }
    }

    private fun playHapticFeedback(isWake: Boolean) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (isWake) {
                    val timings = longArrayOf(0, 180, 80, 220)
                    val amplitudes = intArrayOf(0, 255, 0, 255)
                    vibrator?.vibrate(VibrationEffect.createWaveform(timings, amplitudes, -1))
                } else {
                    vibrator?.vibrate(VibrationEffect.createOneShot(120, VibrationEffect.DEFAULT_AMPLITUDE))
                }
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(if (isWake) 250 else 100)
            }
        } catch (e: Exception) {
            AppLogger.w(TAG, "Vibration failed: ${e.message}")
        }
    }

    private fun playChimeTone() {
        try {
            val alertTone = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 100)
            alertTone.startTone(ToneGenerator.TONE_PROP_ACK, 250)
            mainHandler.postDelayed({
                try { alertTone.release() } catch (_: Exception) {}
            }, 1200)
        } catch (e: Exception) {
            AppLogger.w(TAG, "Notification tone failed: ${e.message}")
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "PhonePilot Voice Assistant",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows PhonePilot voice assistant status"
                setShowBadge(false)
            }
            notificationManager?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(statusText: String): Notification {
        val appIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            appIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        return builder
            .setContentTitle("PhonePilot Assistant")
            .setContentText(statusText)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun startForegroundWithNotification(initialText: String) {
        val notification = buildNotification(initialText)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(statusText: String) {
        val notification = buildNotification(statusText)
        notificationManager?.notify(NOTIFICATION_ID, notification)
    }
}
