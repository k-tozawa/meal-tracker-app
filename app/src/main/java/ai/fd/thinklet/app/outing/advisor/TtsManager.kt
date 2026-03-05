package ai.fd.thinklet.app.outing.advisor

import android.content.Context
import android.media.AudioManager
import android.media.MediaPlayer
import android.util.Base64
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TtsManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val googleCloudTtsApiService: GoogleCloudTtsApiService
) {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var mediaPlayer: MediaPlayer? = null

    @Volatile var isSpeaking: Boolean = false
        private set

    companion object {
        private const val TAG = "TtsManager"
        private val CLOUD_TTS_API_KEY get() = BuildConfig.CLOUD_TTS_API_KEY
        private const val VOICE_NAME = "ja-JP-Neural2-B"
        private const val LANGUAGE_CODE = "ja-JP"
    }

    fun speak(text: String) {
        val currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        Log.d(TAG, "現在のメディア音量: $currentVolume / $maxVolume")

        if (currentVolume == 0) {
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, (maxVolume * 0.3).toInt(), 0)
            Log.d(TAG, "音量を自動的に上げました")
        }

        val cleanText = text
            .replace("**", "")
            .replace("*", "")
            .replace("-", "")
            .replace("  ", "")
            .trim()

        Log.d(TAG, "音声読み上げ: $cleanText")

        scope.launch {
            try {
                val response = googleCloudTtsApiService.synthesize(
                    apiKey = CLOUD_TTS_API_KEY,
                    request = TtsSynthesizeRequest(
                        input = TtsInput(cleanText),
                        voice = TtsVoice(LANGUAGE_CODE, VOICE_NAME),
                        audioConfig = TtsAudioConfig("MP3")
                    )
                )
                playAudio(response.audioContent)
            } catch (e: Exception) {
                Log.e(TAG, "Cloud TTS エラー", e)
            }
        }
    }

    private suspend fun playAudio(base64Audio: String) {
        try {
            val audioBytes = Base64.decode(base64Audio, Base64.DEFAULT)
            val tempFile = File.createTempFile("tts_", ".mp3", context.cacheDir)
            tempFile.writeBytes(audioBytes)

            withContext(Dispatchers.Main) {
                mediaPlayer?.release()
                mediaPlayer = MediaPlayer().apply {
                    setAudioAttributes(
                        android.media.AudioAttributes.Builder()
                            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                            .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                            .build()
                    )
                    setDataSource(tempFile.absolutePath)
                    prepare()
                    setOnCompletionListener {
                        it.release()
                        mediaPlayer = null
                        tempFile.delete()
                        isSpeaking = false
                        Log.d(TAG, "音声読み上げ完了")
                    }
                    start()
                    isSpeaking = true
                    Log.d(TAG, "音声読み上げ開始")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "音声再生エラー", e)
        }
    }

    fun stop() {
        mediaPlayer?.stop()
        mediaPlayer?.release()
        mediaPlayer = null
    }

    fun shutdown() {
        stop()
        scope.cancel()
    }
}
