package com.phonepilot.voice

import android.media.AudioManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.phonepilot.util.AppLogger
import java.util.Locale

/**
 * High-accuracy speech recognition controller powered by Android's built-in SpeechRecognizer.
 * Supports:
 * - Real-time word-by-word streaming partial results (for floating pill feedback).
 * - Continuous listening loop for Always-On wake words ("Hey Pilot", "Open Pilot", etc.).
 * - One-shot single-turn listening for commands ("Open Instagram", "Open WhatsApp", etc.).
 * - Clean microphone release on standby so the status bar mic icon turns off.
 * - Silences Google Speech beep/ding audio feedback during session listening.
 */
class SystemSpeechManager(
    private val context: Context,
    private val onPartialResult: (String) -> Unit,
    private val onFinalResult: (String) -> Unit,
    private val onErrorOccurred: (Int) -> Unit
) {
    companion object {
        private const val TAG = "PhonePilot-Speech"
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private var speechRecognizer: SpeechRecognizer? = null
    private var isListening = false
    private var continuousMode = false

    private fun muteBeep() {
        try {
            audioManager?.adjustStreamVolume(AudioManager.STREAM_NOTIFICATION, AudioManager.ADJUST_MUTE, 0)
            audioManager?.adjustStreamVolume(AudioManager.STREAM_SYSTEM, AudioManager.ADJUST_MUTE, 0)
        } catch (_: Exception) {}
    }

    private fun unmuteBeep() {
        try {
            audioManager?.adjustStreamVolume(AudioManager.STREAM_NOTIFICATION, AudioManager.ADJUST_UNMUTE, 0)
            audioManager?.adjustStreamVolume(AudioManager.STREAM_SYSTEM, AudioManager.ADJUST_UNMUTE, 0)
        } catch (_: Exception) {}
    }

    init {
        mainHandler.post {
            initRecognizer()
        }
    }

    private fun initRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            AppLogger.e(TAG, "SpeechRecognizer is NOT available on this device!")
            return
        }
        try {
            speechRecognizer?.destroy()
        } catch (_: Exception) {}

        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
            setRecognitionListener(createListener())
        }
        AppLogger.i(TAG, "System SpeechRecognizer initialized successfully")
    }

    private fun createRecognizerIntent(): Intent {
        return Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        }
    }

    fun startListening(continuous: Boolean) {
        mainHandler.post {
            continuousMode = continuous
            if (speechRecognizer == null) {
                initRecognizer()
            }
            try {
                muteBeep()
                speechRecognizer?.cancel()
                speechRecognizer?.startListening(createRecognizerIntent())
                isListening = true
                AppLogger.i(TAG, "[MIC_START] SpeechRecognizer started (continuous=$continuous)")
            } catch (e: Exception) {
                AppLogger.e(TAG, "Failed to start listening: ${e.message}", e)
                recreateRecognizer()
            }
        }
    }

    fun stopListening() {
        mainHandler.post {
            continuousMode = false
            isListening = false
            try {
                speechRecognizer?.stopListening()
                speechRecognizer?.cancel()
                unmuteBeep()
                AppLogger.i(TAG, "[MIC_STOP] SpeechRecognizer stopped & cancelled (Mic OFF)")
            } catch (e: Exception) {
                AppLogger.w(TAG, "Error stopping SpeechRecognizer: ${e.message}")
            }
        }
    }

    fun destroy() {
        mainHandler.post {
            continuousMode = false
            isListening = false
            unmuteBeep()
            try {
                speechRecognizer?.destroy()
            } catch (_: Exception) {}
            speechRecognizer = null
            AppLogger.i(TAG, "SpeechRecognizer destroyed")
        }
    }

    private fun recreateRecognizer() {
        try {
            speechRecognizer?.destroy()
        } catch (_: Exception) {}
        speechRecognizer = null
        initRecognizer()
        if (continuousMode) {
            mainHandler.postDelayed({
                if (continuousMode) {
                    try {
                        speechRecognizer?.startListening(createRecognizerIntent())
                        isListening = true
                    } catch (e: Exception) {
                        AppLogger.e(TAG, "Error restarting after recreate: ${e.message}")
                    }
                }
            }, 300)
        }
    }

    private fun createListener(): RecognitionListener {
        return object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                AppLogger.d(TAG, "[LISTENER] Ready for speech")
            }

            override fun onBeginningOfSpeech() {
                AppLogger.d(TAG, "[LISTENER] Beginning of speech detected")
            }

            override fun onRmsChanged(rmsdB: Float) {}

            override fun onBufferReceived(buffer: ByteArray?) {}

            override fun onEndOfSpeech() {
                AppLogger.d(TAG, "[LISTENER] End of speech")
            }

            override fun onError(error: Int) {
                val errorName = when (error) {
                    SpeechRecognizer.ERROR_AUDIO -> "ERROR_AUDIO"
                    SpeechRecognizer.ERROR_CLIENT -> "ERROR_CLIENT"
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "ERROR_INSUFFICIENT_PERMISSIONS"
                    SpeechRecognizer.ERROR_NETWORK -> "ERROR_NETWORK"
                    SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "ERROR_NETWORK_TIMEOUT"
                    SpeechRecognizer.ERROR_NO_MATCH -> "ERROR_NO_MATCH"
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "ERROR_RECOGNIZER_BUSY"
                    SpeechRecognizer.ERROR_SERVER -> "ERROR_SERVER"
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "ERROR_SPEECH_TIMEOUT"
                    else -> "ERROR_$error"
                }
                AppLogger.d(TAG, "[LISTENER] onError: $errorName ($error)")
                onErrorOccurred(error)

                if (continuousMode) {
                    val delay = if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) 400L else 120L
                    mainHandler.postDelayed({
                        if (continuousMode) {
                            try {
                                speechRecognizer?.cancel()
                                speechRecognizer?.startListening(createRecognizerIntent())
                                isListening = true
                            } catch (e: Exception) {
                                AppLogger.w(TAG, "Error restarting in onError: ${e.message}")
                                recreateRecognizer()
                            }
                        }
                    }, delay)
                }
            }

            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val text = matches?.firstOrNull()?.trim() ?: ""
                AppLogger.i(TAG, "[SPEECH_RESULT] Recognized text: \"$text\" (candidates: $matches)")
                if (text.isNotEmpty()) {
                    onFinalResult(text)
                }

                if (continuousMode) {
                    mainHandler.postDelayed({
                        if (continuousMode) {
                            try {
                                speechRecognizer?.startListening(createRecognizerIntent())
                                isListening = true
                            } catch (e: Exception) {
                                AppLogger.w(TAG, "Error restarting in onResults: ${e.message}")
                                recreateRecognizer()
                            }
                        }
                    }, 100)
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val text = matches?.firstOrNull()?.trim() ?: ""
                if (text.isNotEmpty()) {
                    AppLogger.d(TAG, "[SPEECH_PARTIAL] \"$text\"")
                    onPartialResult(text)
                }
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        }
    }
}
