package com.phonepilot.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale

/**
 * Manages Speech-to-Text (STT) via [SpeechRecognizer]
 * and Text-to-Speech (TTS) via [TextToSpeech].
 */
class VoiceManager(
    private val context: Context
) {

    companion object {
        private const val TAG = "PhonePilot"
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var speechRecognizer: SpeechRecognizer? = null
    private var textToSpeech: TextToSpeech? = null
    private var isTtsReady = false

    var isTtsEnabled: Boolean = true

    init {
        // Initialize TTS
        textToSpeech = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val result = textToSpeech?.setLanguage(Locale.getDefault())
                isTtsReady = result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED
                Log.d(TAG, "TTS initialized: isReady=$isTtsReady")
            } else {
                Log.w(TAG, "Failed to initialize TextToSpeech engine")
            }
        }
    }

    /**
     * Speaks the given [text] aloud if TTS is ready and enabled.
     */
    fun speak(text: String) {
        if (!isTtsEnabled || !isTtsReady) return
        mainHandler.post {
            try {
                textToSpeech?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "PhonePilot_${System.currentTimeMillis()}")
            } catch (e: Exception) {
                Log.w(TAG, "TTS speak failed: ${e.message}")
            }
        }
    }

    /**
     * Starts listening for user voice speech input on the UI thread.
     */
    fun startListening(
        onResult: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        mainHandler.post {
            if (!SpeechRecognizer.isRecognitionAvailable(context)) {
                onError("Speech recognition is not available on this device")
                return@post
            }

            try {
                speechRecognizer?.destroy()
                speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                    setRecognitionListener(object : RecognitionListener {
                        override fun onReadyForSpeech(params: Bundle?) {
                            Log.d(TAG, "SpeechRecognizer: Ready for speech")
                        }

                        override fun onBeginningOfSpeech() {
                            Log.d(TAG, "SpeechRecognizer: Speech started")
                        }

                        override fun onRmsChanged(rmsdB: Float) {}
                        override fun onBufferReceived(buffer: ByteArray?) {}
                        override fun onEndOfSpeech() {
                            Log.d(TAG, "SpeechRecognizer: Speech ended")
                        }

                        override fun onError(error: Int) {
                            val msg = getSpeechErrorMessage(error)
                            Log.w(TAG, "SpeechRecognizer error: $msg ($error)")
                            onError(msg)
                        }

                        override fun onResults(results: Bundle?) {
                            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            val transcript = matches?.firstOrNull()?.trim() ?: ""
                            if (transcript.isNotEmpty()) {
                                Log.i(TAG, "SpeechRecognizer result: \"$transcript\"")
                                onResult(transcript)
                            } else {
                                onError("No speech detected")
                            }
                        }

                        override fun onPartialResults(partialResults: Bundle?) {}
                        override fun onEvent(eventType: Int, params: Bundle?) {}
                    })
                }

                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                }

                speechRecognizer?.startListening(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start speech recognition: ${e.message}", e)
                onError("Speech error: ${e.message}")
            }
        }
    }

    /**
     * Stops listening.
     */
    fun stopListening() {
        mainHandler.post {
            try {
                speechRecognizer?.stopListening()
            } catch (_: Exception) {}
        }
    }

    /**
     * Releases system audio resources.
     */
    fun destroy() {
        mainHandler.post {
            try {
                speechRecognizer?.destroy()
                speechRecognizer = null
            } catch (_: Exception) {}

            try {
                textToSpeech?.stop()
                textToSpeech?.shutdown()
                textToSpeech = null
            } catch (_: Exception) {}
        }
    }

    private fun getSpeechErrorMessage(code: Int): String {
        return when (code) {
            SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
            SpeechRecognizer.ERROR_CLIENT -> "Client-side speech error"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission required"
            SpeechRecognizer.ERROR_NETWORK -> "Network error during speech recognition"
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Speech recognition network timeout"
            SpeechRecognizer.ERROR_NO_MATCH -> "No speech recognized. Please speak again."
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Speech recognizer is busy"
            SpeechRecognizer.ERROR_SERVER -> "Server speech recognition error"
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech input heard"
            else -> "Speech recognition error ($code)"
        }
    }
}
