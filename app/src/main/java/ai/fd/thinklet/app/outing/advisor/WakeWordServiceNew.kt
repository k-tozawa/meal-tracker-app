package ai.fd.thinklet.app.outing.advisor

import ai.fd.thinklet.app.outing.advisor.data.model.AudioProcessingConfig
import ai.fd.thinklet.app.outing.advisor.data.xfe.XFERepository
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.IBinder
import android.util.Log
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import kotlin.concurrent.thread

@AndroidEntryPoint
class WakeWordServiceNew : Service() {

    companion object {
        private const val TAG = "WakeWordServiceNew"
        private const val WAKE_WORD_KEYWORD_HIRAGANA = "てんき"
        private const val WAKE_WORD_KEYWORD_KANJI = "天気"
        private const val NOTIFICATION_CHANNEL_ID = "wake_word_channel"
        private const val NOTIFICATION_ID = 1
        const val ACTION_WAKE_WORD_DETECTED = "ai.fd.thinklet.app.outing.advisor.WAKE_WORD_DETECTED"
        private const val MODEL_PATH = "model"
        private const val SAMPLE_RATE = 48000
    }

    @Inject
    lateinit var xfeRepository: XFERepository

    @Inject
    lateinit var ttsManager: TtsManager

    private var model: Model? = null
    private var recognizer: Recognizer? = null
    private var audioRecorder: AudioRecord? = null
    private var audioRecordBufferSize = 0
    private val isAudioRecordRunning = AtomicBoolean(false)
    private var recordingJob: Job? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile private var vadAudioBuffer = mutableListOf<ByteArray>()
    private val vadAudioBufferLock = Any()
    private val isRecognizing = AtomicBoolean(false)
    private val emptyAudioPlayer = EmptyAudioPlayer()

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "========================================")
        Log.d(TAG, "onCreate() - Service Start")
        Log.d(TAG, "========================================")
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        initializeComponents()
    }

    private fun initializeComponents() {
        Log.d(TAG, "[1/5] Initialize Thread Started")
        thread {
            try {
                // XFEのセットアップ
                Log.d(TAG, "[2/5] XFE Setup...")
                val config = AudioProcessingConfig()
                Log.d(TAG, "  Config: mode=${config.mode}, vadEnabled=${config.isVadEnabled}")

                if (!xfeRepository.setupXfe(config)) {
                    Log.e(TAG, "[ERROR] XFE Setup Failed")
                    Log.e(TAG, "  Check license file: /mnt/sdcard/thinklet/xfe-license.dat")
                    return@thread
                }
                Log.d(TAG, "  XFE Setup Success")

                // VOSKモデルのロード
                Log.d(TAG, "[3/5] VOSK Model Loading...")
                // 小さいモデルを優先（速度重視）
                val modelDir = listOf(
                    "/mnt/sdcard/thinklet/vosk-model-small-ja-0.22",
                    "/sdcard/thinklet/vosk-model-small-ja-0.22",
                    "/storage/emulated/0/thinklet/vosk-model-small-ja-0.22",
                    "/mnt/sdcard/thinklet/vosk-model-ja-0.22",
                    "/sdcard/thinklet/vosk-model-ja-0.22",
                    "/storage/emulated/0/thinklet/vosk-model-ja-0.22"
                ).map { File(it) }.firstOrNull { it.exists() && it.isDirectory }
                Log.d(TAG, "  Model path: ${modelDir?.absolutePath}")

                if (modelDir == null) {
                    Log.e(TAG, "[ERROR] Model directory does not exist. Place model at /sdcard/thinklet/vosk-model-ja-0.22")
                    return@thread
                }

                val fileList = modelDir.list()
                if (fileList == null || fileList.isEmpty()) {
                    Log.e(TAG, "[ERROR] Model directory is empty")
                    return@thread
                }

                Log.d(TAG, "  Model files: ${fileList.size}")
                model = Model(modelDir.absolutePath)
                // grammarを使わず自由認識（"てんきおしえて"は1単語として語彙にないため）
                recognizer = Recognizer(model, 16000.0f)
                Log.d(TAG, "  VOSK Init Success (Wake word: $WAKE_WORD_KEYWORD_HIRAGANA / $WAKE_WORD_KEYWORD_KANJI)")

                // AudioRecorderの初期化
                Log.d(TAG, "[4/5] AudioRecord Init...")
                initAudioRecord()
                Log.d(TAG, "  AudioRecord Init Success")

                // VADステータスの監視
                Log.d(TAG, "[5/5] VAD Flow Monitoring...")
                serviceScope.launch {
                    xfeRepository.vadStatusFlow.collectLatest { vadStatus ->
                        Log.d(TAG, "[VAD] Status: isInSpeech=${vadStatus.isInSpeech}, azimuth=${vadStatus.azimuth}, peak=${vadStatus.peak}")

                        if (vadStatus.isInSpeech) {
                            // 発話開始: バッファが空の時だけリセット（SpeechStartの初回）
                            synchronized(vadAudioBufferLock) {
                                if (vadAudioBuffer.isEmpty()) {
                                    Log.d(TAG, "[VAD] Speech Started - Buffer Ready")
                                }
                            }
                        } else {
                            // 音声終了 - ウェイクワード認識を実行
                            val bufferSnapshot: List<ByteArray>
                            synchronized(vadAudioBufferLock) {
                                bufferSnapshot = vadAudioBuffer.toList()
                                vadAudioBuffer = mutableListOf()
                            }
                            if (bufferSnapshot.isNotEmpty()) {
                                Log.d(TAG, "[VAD] Speech Ended (Buffer count: ${bufferSnapshot.size})")
                                processAudioBuffer(bufferSnapshot)
                            } else {
                                Log.d(TAG, "[VAD] Speech Ended (Buffer empty)")
                            }
                        }
                    }
                }

                // VAD音声データの監視（collectLatestではなくcollectを使用してデータをドロップしない）
                serviceScope.launch {
                    xfeRepository.vadAudioFlow.collect { audioData ->
                        if (ttsManager.isSpeaking) {
                            // TTS再生中はバッファをクリアして蓄積しない
                            synchronized(vadAudioBufferLock) {
                                if (vadAudioBuffer.isNotEmpty()) {
                                    Log.d(TAG, "[VAD] TTS playing - clearing buffer")
                                    vadAudioBuffer = mutableListOf()
                                }
                            }
                            return@collect
                        }
                        synchronized(vadAudioBufferLock) {
                            Log.d(TAG, "[VAD] Audio Received: ${audioData.size} bytes (Total chunks: ${vadAudioBuffer.size + 1})")
                            vadAudioBuffer.add(audioData.copyOf())
                        }
                    }
                }

                // XFE処理開始（録音より先に開始）
                Log.d(TAG, "[XFE] Starting Processing...")
                val startResult = xfeRepository.startProcessing(config)
                if (startResult < 0) {
                    val errorMessage = when (startResult) {
                        -1 -> "Invalid license data detected"
                        -2 -> "License has expired"
                        else -> "Internal error ($startResult)"
                    }
                    Log.e(TAG, "[ERROR] XFE Start Failed: $errorMessage")
                    return@thread
                }
                Log.d(TAG, "  XFE Processing Started")

                // 録音開始（XFEの後に開始）
                Log.d(TAG, "[AUDIO] Starting Recording...")
                startAudioRecord()

                Log.d(TAG, "========================================")
                Log.d(TAG, "Initialization Complete - Waiting for Wake Word...")
                Log.d(TAG, "========================================")

            } catch (e: Exception) {
                Log.e(TAG, "========================================")
                Log.e(TAG, "[ERROR] Initialization Failed", e)
                Log.e(TAG, "========================================")
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun initAudioRecord() {
        audioRecordBufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_5POINT1,  // THINKLET 6ch マイク
            AudioFormat.ENCODING_PCM_16BIT
        )

        audioRecorder = AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.MIC)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setChannelMask(AudioFormat.CHANNEL_OUT_5POINT1)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .build()
            )
            .setBufferSizeInBytes(audioRecordBufferSize)
            .build()

        Log.d(TAG, "  AudioRecord: SampleRate=${SAMPLE_RATE}Hz, BufferSize=$audioRecordBufferSize")
    }

    private fun startAudioRecord() {
        if (isAudioRecordRunning.get()) {
            Log.w(TAG, "[WARN] Recording already running")
            return
        }

        audioRecorder?.startRecording()
        isAudioRecordRunning.set(true)
        emptyAudioPlayer.start()
        Log.d(TAG, "[AUDIO] Recording Started")

        recordingJob = serviceScope.launch(Dispatchers.IO) {
            recordAudioLoop()
        }
    }

    private fun stopAudioRecord() {
        if (!isAudioRecordRunning.get()) {
            return
        }

        isAudioRecordRunning.set(false)
        recordingJob?.cancel()
        recordingJob = null

        emptyAudioPlayer.stop()
        try {
            audioRecorder?.stop()
            Log.d(TAG, "[AUDIO] Recording Stopped")
        } catch (e: Exception) {
            Log.e(TAG, "[ERROR] Stop recording failed", e)
        }
    }

    private fun recordAudioLoop() {
        Log.d(TAG, "[AUDIO] Record loop started")
        val pcmBuffer = ByteArray(audioRecordBufferSize)

        while (isAudioRecordRunning.get()) {
            try {
                val readCount = audioRecorder?.read(pcmBuffer, 0, pcmBuffer.size) ?: -1

                if (readCount > 0) {
                    // XFEにオーディオデータを送信
                    val result = xfeRepository.enqueueAudioData(pcmBuffer.copyOf(readCount))
                    if (result != 0) {
                        Log.w(TAG, "[WARN] XFE enqueue result=$result")
                    }
                } else if (readCount < 0) {
                    Log.e(TAG, "[ERROR] AudioRecord.read error: $readCount")
                    break
                }
            } catch (e: Exception) {
                Log.e(TAG, "[ERROR] Recording loop failed", e)
                break
            }
        }

        Log.d(TAG, "[AUDIO] Record loop stopped")
    }

    private fun processAudioBuffer(bufferSnapshot: List<ByteArray>) {
        if (ttsManager.isSpeaking) {
            Log.d(TAG, "[VOSK] TTS is playing, skipping recognition to avoid echo")
            return
        }
        if (!isRecognizing.compareAndSet(false, true)) {
            Log.w(TAG, "[VOSK] Recognition already in progress, skipping this utterance")
            return
        }
        thread {
            try {
                Log.d(TAG, "----------------------------------------")
                Log.d(TAG, "[VOSK] Wake Word Recognition Started")
                Log.d(TAG, "  Buffer count: ${bufferSnapshot.size}")

                // XFEのVADコールバックから取得した16kHzデータを結合
                val combinedBuffer = ByteArray(bufferSnapshot.sumOf { it.size })
                var offset = 0
                for (buffer in bufferSnapshot) {
                    buffer.copyInto(combinedBuffer, offset)
                    offset += buffer.size
                }
                val durationSec = combinedBuffer.size / 32000.0f
                Log.d(TAG, "  Combined size: ${combinedBuffer.size} bytes (${durationSec}sec)")

                // VOSKで認識
                val startMs = System.currentTimeMillis()
                recognizer?.let { rec ->
                    Log.d(TAG, "  VOSK processing...")
                    rec.acceptWaveForm(combinedBuffer, combinedBuffer.size)
                    val result = rec.finalResult
                    val elapsedMs = System.currentTimeMillis() - startMs
                    Log.d(TAG, "  Final result (${elapsedMs}ms): $result")
                    if (result.contains(WAKE_WORD_KEYWORD_HIRAGANA) || result.contains(WAKE_WORD_KEYWORD_KANJI)) {
                        Log.d(TAG, ">>> WAKE WORD DETECTED <<<")
                        onWakeWordDetected()
                    } else {
                        Log.d(TAG, "  No match (keyword: '$WAKE_WORD_KEYWORD_HIRAGANA' / '$WAKE_WORD_KEYWORD_KANJI')")
                    }
                } ?: Log.e(TAG, "  [ERROR] Recognizer is null")

                Log.d(TAG, "----------------------------------------")
            } catch (e: Exception) {
                Log.e(TAG, "[ERROR] Recognition failed", e)
            } finally {
                isRecognizing.set(false)
            }
        }
    }

    private fun onWakeWordDetected() {
        Log.d(TAG, "========================================")
        Log.d(TAG, "Starting MainActivity...")
        Log.d(TAG, "========================================")
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
            .setContentText("「てんきおしえて」と話しかけてください（VAD有効）")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "========================================")
        Log.d(TAG, "onDestroy() - Service Cleanup")
        Log.d(TAG, "========================================")
        stopAudioRecord()
        audioRecorder?.release()
        audioRecorder = null
        xfeRepository.stopProcessing()
        xfeRepository.cleanupXfe()
        recognizer?.close()
        model?.close()
        serviceScope.cancel()
        Log.d(TAG, "Service Cleanup Complete")
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
