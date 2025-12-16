package com.example.meallogger.services

import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import com.example.meallogger.data.ApiClient
import com.example.meallogger.data.TTSRequest
import com.example.meallogger.utils.UserPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.*

class VoiceService(private val context: Context) {

    private var mediaPlayer: MediaPlayer? = null
    private var speechRecognizer: SpeechRecognizer? = null
    private var isListening = false
    private var globalCommandListener: ((String) -> Unit)? = null
    private val userPreferences = UserPreferences(context)
    private val coroutineScope = CoroutineScope(Dispatchers.Main)

    fun initialize(onInit: (Int) -> Unit) {
        // MediaPlayer使用のため、初期化は不要
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
        Log.d(TAG, "VoiceService initialized (Web TTS mode)")
        onInit(0) // SUCCESS
    }

    fun speak(text: String) {
        Log.d(TAG, "speak() called with text: $text")
        coroutineScope.launch {
            try {
                playTTS(text, null)
            } catch (e: Exception) {
                Log.e(TAG, "TTS playback failed", e)
            }
        }
    }

    fun speakAndThen(text: String, onComplete: () -> Unit) {
        Log.d(TAG, "speakAndThen() called with text: $text")
        coroutineScope.launch {
            try {
                playTTS(text, onComplete)
            } catch (e: Exception) {
                Log.e(TAG, "TTS playback failed", e)
                onComplete()
            }
        }
    }

    private suspend fun playTTS(text: String, onComplete: (() -> Unit)?) {
        withContext(Dispatchers.IO) {
            try {
                // サーバーから音声データを取得
                val apiService = ApiClient.getApiService(context)
                val response = apiService.textToSpeech(TTSRequest(text))

                if (response.isSuccessful && response.body() != null) {
                    // 音声ファイルを一時保存（MP3形式）
                    val audioFile = File(context.cacheDir, "tts_${System.currentTimeMillis()}.mp3")
                    FileOutputStream(audioFile).use { output ->
                        response.body()!!.byteStream().use { input ->
                            input.copyTo(output)
                        }
                    }

                    Log.d(TAG, "TTS audio saved (${audioFile.length()} bytes): ${audioFile.absolutePath}")

                    // メインスレッドでMediaPlayer再生
                    withContext(Dispatchers.Main) {
                        mediaPlayer?.release()
                        mediaPlayer = MediaPlayer().apply {
                            setDataSource(audioFile.absolutePath)
                            setVolume(userPreferences.getVoiceVolume(), userPreferences.getVoiceVolume())
                            setOnCompletionListener {
                                Log.d(TAG, "Audio playback completed")
                                audioFile.delete()
                                onComplete?.invoke()
                            }
                            setOnErrorListener { _, what, extra ->
                                Log.e(TAG, "MediaPlayer error: what=$what, extra=$extra")
                                audioFile.delete()
                                onComplete?.invoke()
                                true
                            }
                            prepare()
                            start()
                        }
                    }
                } else {
                    Log.e(TAG, "TTS API failed: ${response.code()}")
                    withContext(Dispatchers.Main) {
                        onComplete?.invoke()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "TTS error", e)
                withContext(Dispatchers.Main) {
                    onComplete?.invoke()
                }
            }
        }
    }

    fun startListening(onResult: (String) -> Unit) {
        Log.d(TAG, "startListening called, isListening=$isListening")
        if (isListening) {
            Log.w(TAG, "Already listening, ignoring request")
            return
        }

        Log.d(TAG, "Creating recognition intent")
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.JAPANESE)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }

        Log.d(TAG, "Destroying and recreating speech recognizer")
        speechRecognizer?.destroy()
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)

        if (speechRecognizer == null) {
            Log.e(TAG, "Failed to create speech recognizer!")
            return
        }

        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            Log.e(TAG, "Speech recognition not available on this device!")
            return
        }

        Log.d(TAG, "Speech recognizer created successfully")

        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                isListening = true
                Log.d(TAG, "Ready for speech")
            }

            override fun onBeginningOfSpeech() {
                Log.d(TAG, "Speech started")
            }

            override fun onRmsChanged(rmsdB: Float) {}

            override fun onBufferReceived(buffer: ByteArray?) {}

            override fun onEndOfSpeech() {
                isListening = false
                Log.d(TAG, "Speech ended")
            }

            override fun onError(error: Int) {
                isListening = false
                val errorMessage = when (error) {
                    android.speech.SpeechRecognizer.ERROR_AUDIO -> "ERROR_AUDIO"
                    android.speech.SpeechRecognizer.ERROR_CLIENT -> "ERROR_CLIENT"
                    android.speech.SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "ERROR_INSUFFICIENT_PERMISSIONS"
                    android.speech.SpeechRecognizer.ERROR_NETWORK -> "ERROR_NETWORK"
                    android.speech.SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "ERROR_NETWORK_TIMEOUT"
                    android.speech.SpeechRecognizer.ERROR_NO_MATCH -> "ERROR_NO_MATCH"
                    android.speech.SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "ERROR_RECOGNIZER_BUSY"
                    android.speech.SpeechRecognizer.ERROR_SERVER -> "ERROR_SERVER"
                    android.speech.SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "ERROR_SPEECH_TIMEOUT"
                    else -> "UNKNOWN_ERROR: $error"
                }
                Log.e(TAG, "Recognition error: $errorMessage")
            }

            override fun onResults(results: Bundle?) {
                isListening = false
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty()) {
                    val recognizedText = matches[0]
                    Log.d(TAG, "Recognized: $recognizedText")
                    onResult(recognizedText)
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {}

            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        Log.d(TAG, "Starting speech recognizer")
        speechRecognizer?.startListening(intent)
        Log.d(TAG, "Speech recognizer started")
    }

    fun stopListening() {
        if (isListening) {
            speechRecognizer?.stopListening()
            isListening = false
        }
    }

    fun setGlobalCommandListener(listener: (String) -> Unit) {
        globalCommandListener = listener
    }

    fun shutdown() {
        mediaPlayer?.release()
        mediaPlayer = null
        speechRecognizer?.destroy()
    }

    companion object {
        private const val TAG = "VoiceService"
    }
}
