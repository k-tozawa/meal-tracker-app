package ai.fd.mealtracker

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale

/**
 * 音声アシスタント
 * Text-to-Speech と Speech Recognition を統合
 */
class VoiceAssistant(
    private val context: Context,
    private val onCommandReceived: (VoiceCommand, String) -> Unit
) {
    private var tts: TextToSpeech? = null
    private var speechRecognizer: SpeechRecognizer? = null
    private var isListening = false

    companion object {
        private const val TAG = "VoiceAssistant"
    }

    init {
        initTTS()
        initSpeechRecognizer()
    }

    private fun initTTS() {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.JAPANESE
                Log.i(TAG, "TTS initialized successfully")
            } else {
                Log.e(TAG, "TTS initialization failed")
            }
        }
    }

    private fun initSpeechRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            Log.e(TAG, "Speech recognition not available")
            return
        }

        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                Log.d(TAG, "Ready for speech")
                isListening = true
            }

            override fun onBeginningOfSpeech() {
                Log.d(TAG, "Beginning of speech")
            }

            override fun onRmsChanged(rmsdB: Float) {
                // 音量レベル（必要に応じて使用）
            }

            override fun onBufferReceived(buffer: ByteArray?) {
                // バッファ（通常は使用しない）
            }

            override fun onEndOfSpeech() {
                Log.d(TAG, "End of speech")
                isListening = false
            }

            override fun onError(error: Int) {
                Log.e(TAG, "Speech recognition error: $error")
                isListening = false
                handleError(error)
            }

            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (matches != null && matches.isNotEmpty()) {
                    val text = matches[0]
                    Log.i(TAG, "Recognized: $text")
                    val command = parseCommand(text)
                    onCommandReceived(command, text)
                }
                isListening = false
            }

            override fun onPartialResults(partialResults: Bundle?) {
                // 部分結果（リアルタイムフィードバック用）
            }

            override fun onEvent(eventType: Int, params: Bundle?) {
                // イベント
            }
        })
    }

    /**
     * テキストを読み上げる
     */
    fun speak(text: String, onComplete: (() -> Unit)? = null) {
        Log.i(TAG, "Speaking: $text")
        tts?.speak(text, TextToSpeech.QUEUE_ADD, null, null)

        // 読み上げ完了後のコールバック
        if (onComplete != null) {
            tts?.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) {
                    onComplete()
                }
                override fun onError(utteranceId: String?) {}
            })
        }
    }

    /**
     * 音声認識を開始
     */
    fun startListening() {
        if (isListening) {
            Log.w(TAG, "Already listening")
            return
        }

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.JAPANESE.toString())
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
        }

        try {
            speechRecognizer?.startListening(intent)
            Log.i(TAG, "Started listening")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start listening", e)
        }
    }

    /**
     * 音声認識を停止
     */
    fun stopListening() {
        if (isListening) {
            speechRecognizer?.stopListening()
            isListening = false
            Log.i(TAG, "Stopped listening")
        }
    }

    /**
     * リソースを解放
     */
    fun release() {
        tts?.stop()
        tts?.shutdown()
        speechRecognizer?.destroy()
        Log.i(TAG, "Released resources")
    }

    /**
     * テキストから音声コマンドを解析
     */
    private fun parseCommand(text: String): VoiceCommand {
        val normalized = text.lowercase().replace("\\s+".toRegex(), "")

        return when {
            normalized.contains("ご飯") && (normalized.contains("記録") || normalized.contains("撮") || normalized.contains("写真")) -> VoiceCommand.RECORD_MEAL
            normalized.contains("食事") && (normalized.contains("記録") || normalized.contains("撮")) -> VoiceCommand.RECORD_MEAL
            normalized.contains("記録して") || normalized.contains("撮影して") -> VoiceCommand.RECORD_MEAL

            normalized.contains("履歴") || (normalized.contains("今日") && normalized.contains("食事")) -> VoiceCommand.VIEW_HISTORY

            normalized.contains("アドバイス") || normalized.contains("助言") -> VoiceCommand.GET_ADVICE

            normalized.contains("献立") || normalized.contains("メニュー") || normalized.contains("おすすめ") -> VoiceCommand.SUGGEST_MENU

            normalized.contains("はい") || normalized.contains("うん") || normalized.contains("そう") || normalized.contains("おねがい") -> VoiceCommand.CONFIRM_YES

            normalized.contains("いいえ") || normalized.contains("違") || normalized.contains("ちがう") -> VoiceCommand.CONFIRM_NO

            normalized.contains("キャンセル") || normalized.contains("やめ") || normalized.contains("中止") -> VoiceCommand.CANCEL

            else -> VoiceCommand.UNKNOWN
        }
    }

    /**
     * エラーハンドリング
     */
    private fun handleError(errorCode: Int) {
        val message = when (errorCode) {
            SpeechRecognizer.ERROR_AUDIO -> "音声入力エラー"
            SpeechRecognizer.ERROR_NETWORK -> "ネットワークエラー"
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "ネットワークタイムアウト"
            SpeechRecognizer.ERROR_NO_MATCH -> "認識できませんでした"
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "音声認識が使用中です"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "権限が不足しています"
            else -> "不明なエラー"
        }

        speak("すみません、${message}。もう一度お願いします。") {
            // エラー後に再度リスニングを開始
            startListening()
        }
    }

    /**
     * 会話のフロー管理用ヘルパー
     */
    fun speakAndListen(text: String) {
        speak(text) {
            // 読み上げ完了後に自動でリスニング開始
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                startListening()
            }, 500)  // 500ms待ってからリスニング開始
        }
    }
}
