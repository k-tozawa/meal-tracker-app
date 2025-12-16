package com.example.meallogger.services

import ai.fd.thinklet.xfe.TLXFECallback
import ai.fd.thinklet.xfe.TLXFEConfigs
import ai.fd.thinklet.xfe.TLXFEData
import ai.fd.thinklet.xfe.TLXFEPreprocessor
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.*
import org.vosk.Model
import org.vosk.Recognizer
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.charset.Charset
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean

class VoskVoiceService(private val context: Context) {

    private var audioRecorder: AudioRecord? = null
    private var xfe: TLXFEPreprocessor? = null
    private var model: Model? = null
    private var recognizer: Recognizer? = null
    private val isRecording = AtomicBoolean(false)
    private var isSpeechActive = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private var recordingJob: Job? = null

    private var onResultCallback: ((String) -> Unit)? = null
    private var onErrorCallback: ((String) -> Unit)? = null

    // デバッグ用：VADから出力された音声をファイルに保存
    private var debugOutputStream: FileOutputStream? = null
    private var debugFilePath: String? = null
    private val enableDebugRecording = true // デバッグモード

    // VAD callback - 音声の開始/終了を検出してVoskで認識
    private val vadCallback = object : TLXFECallback.VadCallback {
        override fun onData(
            aBuffer: ByteArray,
            aSpeechState: TLXFEData.SpeechState,
            aSourceIndex: Int,
            aStreamInfo: Array<TLXFEData.StreamInfo>?,
            aUserData: Any?
        ) {
            Log.d(TAG, "VAD Callback - SpeechState: $aSpeechState, buffer size: ${aBuffer.size}")

            when (aSpeechState) {
                TLXFEData.SpeechState.SpeechStart -> {
                    Log.d(TAG, "Speech detected - starting Vosk recognition")
                    isSpeechActive = true

                    // Vosk認識器をリセット
                    recognizer?.reset()

                    // デバッグ用：新しい音声ファイルを作成
                    if (enableDebugRecording) {
                        try {
                            val sdf = SimpleDateFormat("yyyyMMdd_HH-mm-ss", Locale.JAPAN)
                            val timestamp = sdf.format(Date())
                            // アプリ専用ディレクトリに保存（権限不要）
                            val debugDir = File(context.getExternalFilesDir(null), "vosk_debug")
                            debugDir.mkdirs()
                            debugFilePath = File(debugDir, "vosk_debug_$timestamp.raw").absolutePath
                            debugOutputStream = FileOutputStream(debugFilePath)
                            Log.d(TAG, "Debug recording started: $debugFilePath (16kHz mono 16-bit PCM)")
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to create debug file", e)
                            debugOutputStream = null
                        }
                    }

                    // 最初のバッファもデバッグファイルに保存
                    if (enableDebugRecording && aBuffer.isNotEmpty()) {
                        try {
                            debugOutputStream?.write(aBuffer)
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to write debug data", e)
                        }
                    }

                    // 最初のバッファもVoskに送信
                    if (aBuffer.isNotEmpty()) {
                        Log.d(TAG, "Sending initial ${aBuffer.size} bytes to Vosk")
                        val accepted = recognizer?.acceptWaveForm(aBuffer, aBuffer.size) ?: false
                        Log.d(TAG, "Vosk acceptWaveForm result (initial): $accepted")
                    }
                }

                TLXFEData.SpeechState.InSpeech -> {
                    // デバッグ用：音声データをファイルに保存
                    if (enableDebugRecording && aBuffer.isNotEmpty()) {
                        try {
                            debugOutputStream?.write(aBuffer)
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to write debug data", e)
                        }
                    }

                    // 音声継続中 - PCMデータをVoskに送信
                    if (isSpeechActive && aBuffer.isNotEmpty()) {
                        Log.d(TAG, "Sending ${aBuffer.size} bytes to Vosk")
                        val accepted = recognizer?.acceptWaveForm(aBuffer, aBuffer.size) ?: false
                        Log.d(TAG, "Vosk acceptWaveForm result: $accepted")

                        if (accepted) {
                            // 中間結果（文の区切り）
                            val result = recognizer?.result
                            Log.d(TAG, "Vosk intermediate result: $result")
                            if (result != null && result.isNotEmpty()) {
                                try {
                                    val json = JSONObject(result)
                                    val text = json.optString("text", "")
                                    if (text.isNotEmpty()) {
                                        Log.d(TAG, "Vosk partial result: $text")
                                    }
                                } catch (e: Exception) {
                                    Log.e(TAG, "Failed to parse Vosk result", e)
                                }
                            }
                        } else {
                            // 部分結果（リアルタイム）
                            val partialResult = recognizer?.partialResult
                            if (partialResult != null && partialResult.isNotEmpty()) {
                                try {
                                    val json = JSONObject(partialResult)
                                    val partial = json.optString("partial", "")
                                    if (partial.isNotEmpty()) {
                                        Log.d(TAG, "Vosk real-time partial: $partial")
                                    }
                                } catch (e: Exception) {
                                    Log.e(TAG, "Failed to parse Vosk partial result", e)
                                }
                            }
                        }
                    } else {
                        Log.w(TAG, "InSpeech but not sending: isSpeechActive=$isSpeechActive, buffer size=${aBuffer.size}")
                    }
                }

                TLXFEData.SpeechState.SpeechEnd -> {
                    Log.d(TAG, "Speech ended - getting final Vosk result")
                    isSpeechActive = false

                    // デバッグ用：最後のバッファを保存してファイルを閉じる
                    if (enableDebugRecording && aBuffer.isNotEmpty()) {
                        try {
                            debugOutputStream?.write(aBuffer)
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to write final debug data", e)
                        }
                    }
                    if (enableDebugRecording) {
                        try {
                            debugOutputStream?.close()
                            val file = File(debugFilePath ?: "")
                            if (file.exists()) {
                                Log.d(TAG, "Debug recording saved: $debugFilePath (${file.length()} bytes)")
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to close debug file", e)
                        } finally {
                            debugOutputStream = null
                        }
                    }

                    // 最終結果を取得
                    val finalResult = recognizer?.finalResult
                    if (finalResult != null && finalResult.isNotEmpty()) {
                        try {
                            val json = JSONObject(finalResult)
                            val text = json.optString("text", "")

                            Log.d(TAG, "Vosk final result: $text")

                            if (text.isNotEmpty()) {
                                // 結果をコールバック
                                mainHandler.post { onResultCallback?.invoke(text) }
                            } else {
                                Log.w(TAG, "Vosk recognition returned empty text")
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to parse Vosk final result", e)
                            mainHandler.post { onErrorCallback?.invoke("音声認識の解析に失敗しました") }
                        }
                    }

                    Log.d(TAG, "Ready for next speech")
                }

                else -> {
                    Log.d(TAG, "VAD Callback - Other state: $aSpeechState")
                }
            }
        }
    }

    // アプリ起動時にバックグラウンドでモデルをロード
    fun initializeModel(onComplete: ((Boolean) -> Unit)? = null) {
        CoroutineScope(Dispatchers.IO).launch {
            val success = loadVoskModel()
            mainHandler.post { onComplete?.invoke(success) }
        }
    }

    fun startListening(onResult: (String) -> Unit, onError: ((String) -> Unit)? = null) {
        if (isRecording.get()) {
            Log.w(TAG, "Already recording")
            return
        }

        this.onResultCallback = onResult
        this.onErrorCallback = onError

        // モデルがロード済みかチェック
        if (model == null || recognizer == null) {
            Log.e(TAG, "Vosk model not loaded. Call initializeModel() first.")
            mainHandler.post { onError?.invoke("Voskモデルが初期化されていません") }
            return
        }

        // XFEの初期化
        if (!setupXfe()) {
            mainHandler.post { onError?.invoke("XFE VADの初期化に失敗しました") }
            return
        }

        // XFE処理を開始
        val ret = xfe?.startProcessing()
        Log.d(TAG, "XFE startProcessing() returned: $ret")
        if (ret == null || ret < 0) {
            Log.e(TAG, "Failed to start XFE processing: $ret")
            mainHandler.post { onError?.invoke("XFE処理の開始に失敗しました") }
            return
        }
        Log.d(TAG, "XFE processing started successfully")

        // THINKLET 6ch 48kHz録音開始
        startRecording()
    }

    private fun loadVoskModel(): Boolean {
        if (model != null && recognizer != null) {
            Log.d(TAG, "Vosk model already loaded")
            return true
        }

        Log.d(TAG, "Loading Vosk model")

        // モデルパスの候補
        val modelPaths = arrayOf(
            "/mnt/sdcard/thinklet/vosk-model-ja-0.22",
            "/storage/emulated/0/thinklet/vosk-model-ja-0.22",
            "/sdcard/thinklet/vosk-model-ja-0.22",
            "/mnt/sdcard/thinklet/vosk-model-small-ja-0.22",
            "/storage/emulated/0/thinklet/vosk-model-small-ja-0.22",
            "/sdcard/thinklet/vosk-model-small-ja-0.22"
        )

        for (modelPath in modelPaths) {
            val modelDir = File(modelPath)
            if (modelDir.exists() && modelDir.isDirectory) {
                Log.d(TAG, "Found Vosk model at: $modelPath")

                return try {
                    model = Model(modelPath)

                    // 16kHz認識器を作成（XFEが48kHz→16kHzに変換してくれる）
                    recognizer = Recognizer(model, 16000.0f)

                    Log.d(TAG, "Vosk model loaded successfully")
                    true
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to load Vosk model at $modelPath", e)
                    continue
                }
            }
        }

        Log.e(TAG, "Vosk model not found in any of the expected paths")
        return false
    }

    private fun setupXfe(): Boolean {
        Log.d(TAG, "Setting up XFE")

        // 既存のXFEをクリーンアップ
        xfe?.cleanup()
        xfe = null

        // ライセンスデータの読み込み
        val licenseData = getLicenseData()
        if (licenseData.isEmpty()) {
            Log.e(TAG, "Valid license data is not found.")
            return false
        }

        // XFE初期化（HumanVoiceモード）
        xfe = TLXFEPreprocessor(TLXFEPreprocessor.ProcessMode.HumanVoice)
        xfe?.registerLicenseData(licenseData)
        xfe?.registerVadCallback(vadCallback, null)

        // ソース設定（48kHz入力 → 16kHz出力）
        xfe?.setSourceConfig(
            TLXFEConfigs.Source.Builder()
                .setUseVerticalPlane(true)
                .setInputSamplingRate(48000)
                .setOutputSamplingRate(16000)
                .build()
        )

        // VAD設定
        xfe?.setVadConfig(
            TLXFEConfigs.Vad.Builder()
                .setTimeToActive(100)      // 100ms音声検出で開始
                .setTimeToInactive(600)    // 600ms無音で終了
                .setHeadPaddingTime(400)   // 前方400msパディング
                .setTailPaddingTime(400)   // 後方400msパディング
                .setDbfsThreshold(-60)     // -60dBFS閾値
                .build()
        )

        // ローカライザー設定
        xfe?.setLocalizerConfig(
            TLXFEConfigs.Localizer.Builder()
                .setType(TLXFEConfigs.Localizer.TYPE_STATIC)
                .setDirection(60, 90)
                .setIdenticalRange(30)
                .build()
        )

        xfe?.setup()

        Log.d(TAG, "XFE setup complete")
        return true
    }

    private fun getLicenseData(): String {
        // 複数のライセンスファイルパスを試す
        val licensePaths = arrayOf(
            "/mnt/sdcard/thinklet/xfe-license.dat",
            "/storage/emulated/0/thinklet/xfe-license.dat",
            "/sdcard/thinklet/xfe-license.dat"
        )

        for (licensePath in licensePaths) {
            val licenseFile = File(licensePath)
            if (licenseFile.isFile) {
                Log.d(TAG, "Found license file at: $licensePath")
                return try {
                    val dataLength = licenseFile.length()
                    if (dataLength > 1024) {
                        Log.e(TAG, "License file too long: $dataLength bytes")
                        continue
                    }
                    val readBuffer = ByteArray(dataLength.toInt())
                    val licenseFileStream = FileInputStream(licenseFile)
                    licenseFileStream.read(readBuffer)
                    licenseFileStream.close()
                    readBuffer.toString(Charset.defaultCharset())
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to read license file at $licensePath", e)
                    continue
                }
            }
        }

        Log.e(TAG, "License file not found in any of the expected paths")
        return ""
    }

    @android.annotation.SuppressLint("MissingPermission")
    private fun startRecording() {
        try {
            val sampleRate = 48000
            val audioRecordBufferSize = AudioRecord.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_5POINT1,
                AudioFormat.ENCODING_PCM_16BIT
            )

            audioRecorder = AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.MIC)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setChannelMask(AudioFormat.CHANNEL_OUT_5POINT1)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .build()
                )
                .setBufferSizeInBytes(audioRecordBufferSize)
                .build()

            audioRecorder?.startRecording()
            isRecording.set(true)
            isSpeechActive = false

            Log.d(TAG, "Started THINKLET 6-channel 48kHz recording with XFE VAD + Vosk (buffer: $audioRecordBufferSize bytes)")

            // 録音ループを別スレッドで開始
            recordingJob = CoroutineScope(Dispatchers.IO).launch {
                recordAudioLoop(audioRecordBufferSize)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start recording", e)
            isRecording.set(false)
            mainHandler.post { onErrorCallback?.invoke("録音の開始に失敗しました: ${e.message}") }
        }
    }

    private fun recordAudioLoop(bufferSize: Int) {
        Log.i(TAG, "Record Thread E")
        val pcmBuffer = ByteArray(bufferSize)
        var readCountInByte: Int
        var dataCount = 0

        while (isRecording.get()) {
            try {
                readCountInByte = audioRecorder?.read(pcmBuffer, 0, pcmBuffer.size) ?: -1

                if (readCountInByte > 0) {
                    dataCount++
                    if (dataCount % 100 == 0) {
                        Log.d(TAG, "Received PCM data: $readCountInByte bytes (count: $dataCount)")
                    }

                    // XFEにPCMデータを送る（読み取ったサイズ分のみ）
                    val dataToEnqueue = if (readCountInByte < pcmBuffer.size) {
                        pcmBuffer.copyOf(readCountInByte)
                    } else {
                        pcmBuffer
                    }

                    val ret = xfe?.enqueue(dataToEnqueue)
                    if (ret != null && ret < 0) {
                        Log.e(TAG, "XFE enqueue failed: $ret")
                    } else if (dataCount % 100 == 0) {
                        Log.d(TAG, "XFE enqueue success: $ret")
                    }
                } else if (readCountInByte < 0) {
                    Log.e(TAG, "AudioRecord.read returns error value: $readCountInByte")
                    break
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in recording loop", e)
                break
            }
        }

        Log.i(TAG, "Record Thread X")
    }

    fun stopRecording() {
        if (!isRecording.get()) return

        Log.d(TAG, "Stopping recording")
        isRecording.set(false)
        isSpeechActive = false

        try {
            // 録音ループを停止
            recordingJob?.cancel()
            recordingJob = null

            // XFE処理停止
            xfe?.stopProcessing()

            // 録音停止
            audioRecorder?.stop()
            audioRecorder?.release()
            audioRecorder = null

            // デバッグ用ファイルを閉じる
            if (enableDebugRecording && debugOutputStream != null) {
                try {
                    debugOutputStream?.close()
                    Log.d(TAG, "Debug file closed on stop")
                } catch (e: Exception) {
                    Log.e(TAG, "Error closing debug file", e)
                } finally {
                    debugOutputStream = null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping recording", e)
        }
    }

    fun shutdown() {
        stopRecording()

        // XFEクリーンアップ
        xfe?.cleanup()
        xfe = null

        // Voskクリーンアップ
        recognizer?.close()
        recognizer = null
        model?.close()
        model = null
    }

    companion object {
        private const val TAG = "VoskVoiceService"
    }
}
