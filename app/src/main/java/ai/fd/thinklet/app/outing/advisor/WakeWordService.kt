package ai.fd.thinklet.app.outing.advisor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import java.io.File
import kotlin.concurrent.thread

class WakeWordService : Service() {

    companion object {
        private const val TAG = "WakeWordService"
        private const val WAKE_WORD = "てんきおしえて"
        private const val NOTIFICATION_CHANNEL_ID = "wake_word_channel"
        private const val NOTIFICATION_ID = 1
        const val ACTION_WAKE_WORD_DETECTED = "ai.fd.thinklet.app.outing.advisor.WAKE_WORD_DETECTED"
        private const val MODEL_PATH = "model"
    }

    private var model: Model? = null
    private var speechService: SpeechService? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        initVosk()
    }

    private fun initVosk() {
        thread {
            try {
                val modelDir = listOf(
                    "/mnt/sdcard/thinklet/vosk-model-ja-0.22",
                    "/sdcard/thinklet/vosk-model-ja-0.22",
                    "/storage/emulated/0/thinklet/vosk-model-ja-0.22",
                    "/mnt/sdcard/thinklet/vosk-model-small-ja-0.22",
                    "/sdcard/thinklet/vosk-model-small-ja-0.22"
                ).map { File(it) }.firstOrNull { it.exists() && it.isDirectory }
                if (modelDir == null) {
                    Log.e(TAG, "VOSK モデルが見つかりません。/sdcard/thinklet/ にモデルを配置してください")
                    return@thread
                }
                Log.d(TAG, "VOSK モデルロード開始: ${modelDir.absolutePath}")
                model = Model(modelDir.absolutePath)
                val grammar = "[\"$WAKE_WORD\", \"[unk]\"]"
                val recognizer = Recognizer(model, 16000.0f, grammar)
                speechService = SpeechService(recognizer, 16000.0f)
                speechService?.startListening(recognitionListener)
                Log.d(TAG, "VOSK 初期化完了。「$WAKE_WORD」を待機中...")
            } catch (e: Exception) {
                Log.e(TAG, "VOSK 初期化失敗", e)
            }
        }
    }

    private val recognitionListener = object : RecognitionListener {
        override fun onPartialResult(hypothesis: String?) {
            hypothesis ?: return
            if (hypothesis.contains(WAKE_WORD)) {
                Log.d(TAG, "ウェイクワード検出（部分）: $hypothesis")
                onWakeWordDetected()
            }
        }

        override fun onResult(hypothesis: String?) {
            hypothesis ?: return
            if (hypothesis.contains(WAKE_WORD)) {
                Log.d(TAG, "ウェイクワード検出: $hypothesis")
                onWakeWordDetected()
            }
        }

        override fun onFinalResult(hypothesis: String?) {}
        override fun onError(exception: Exception?) {
            Log.e(TAG, "音声認識エラー", exception)
        }
        override fun onTimeout() {
            Log.d(TAG, "音声認識タイムアウト")
        }
    }

    private fun onWakeWordDetected() {
        val intent = Intent(this, MainActivity::class.java).apply {
            action = ACTION_WAKE_WORD_DETECTED
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        startActivity(intent)
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            "ウェイクワード待機",
            NotificationManager.IMPORTANCE_LOW
        )
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        return Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("おでかけアドバイザー")
            .setContentText("「てんきおしえて」と話しかけてください")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        speechService?.stop()
        speechService?.shutdown()
        model?.close()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
