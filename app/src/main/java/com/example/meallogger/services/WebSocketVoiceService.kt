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
import com.example.meallogger.utils.UserPreferences
import kotlinx.coroutines.*
import okhttp3.*
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.io.File
import java.io.FileInputStream
import java.nio.charset.Charset
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class WebSocketVoiceService(private val context: Context) {

    private var webSocket: WebSocket? = null
    private var audioRecorder: AudioRecord? = null
    private var xfe: TLXFEPreprocessor? = null
    private val isRecording = AtomicBoolean(false)
    private var isSpeechActive = false
    private val userPreferences = UserPreferences(context)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var recordingJob: Job? = null

    private var onResultCallback: ((String) -> Unit)? = null
    private var onErrorCallback: ((String) -> Unit)? = null

    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    // VAD callback - 音声の開始/終了を検出
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
                    Log.d(TAG, "Speech detected - starting WebSocket connection")
                    isSpeechActive = true

                    // WebSocket接続開始
                    if (webSocket == null) {
                        connectWebSocket()
                    }

                    // 最初のデータを送信
                    if (aBuffer.isNotEmpty()) {
                        webSocket?.send(aBuffer.toByteString())
                    }
                }

                TLXFEData.SpeechState.InSpeech -> {
                    // 音声継続中 - PCMデータをWebSocketに送信
                    if (isSpeechActive && aBuffer.isNotEmpty() && webSocket != null) {
                        webSocket?.send(aBuffer.toByteString())
                    }
                }

                TLXFEData.SpeechState.SpeechEnd -> {
                    Log.d(TAG, "Speech ended - sending final data")
                    isSpeechActive = false

                    // 最後のデータを送信
                    if (aBuffer.isNotEmpty()) {
                        webSocket?.send(aBuffer.toByteString())
                    }

                    // WebSocketは開いたままにして認識結果を待つ
                    // 録音は継続したまま次のSpeechStartを待つ
                    Log.d(TAG, "Waiting for recognition result, recording continues...")
                }

                else -> {
                    Log.d(TAG, "VAD Callback - Other state: $aSpeechState")
                }
            }
        }
    }

    fun startListening(onResult: (String) -> Unit, onError: ((String) -> Unit)? = null) {
        if (isRecording.get()) {
            Log.w(TAG, "Already recording")
            return
        }

        this.onResultCallback = onResult
        this.onErrorCallback = onError

        // XFEの初期化
        if (!setupXfe()) {
            mainHandler.post { onError?.invoke("XFE VADの初期化に失敗しました") }
            return
        }

        // XFE処理を開始
        val ret = xfe?.startProcessing()
        if (ret == null || ret < 0) {
            Log.e(TAG, "Failed to start XFE processing: $ret")
            mainHandler.post { onError?.invoke("XFE処理の開始に失敗しました") }
            return
        }

        // THINKLET 6ch 48kHz録音開始
        startRecording()
    }

    private fun setupXfe(): Boolean {
        Log.d(TAG, "Setting up XFE")

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

    private fun connectWebSocket() {
        if (webSocket != null) {
            Log.w(TAG, "WebSocket already connected")
            return
        }

        val serverUrl = userPreferences.getServerUrl()
        val wsUrl = serverUrl.replace("http://", "ws://").replace("https://", "wss://") + "/ws/speech"
        Log.d(TAG, "Connecting to WebSocket: $wsUrl")

        val request = Request.Builder()
            .url(wsUrl)
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "WebSocket connected")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.d(TAG, "Received message: $text")

                try {
                    val json = org.json.JSONObject(text)
                    when {
                        json.has("partial") -> {
                            val partial = json.getString("partial")
                            Log.d(TAG, "Partial result: $partial")
                        }
                        json.has("final") -> {
                            val final = json.getString("final")
                            Log.d(TAG, "Final result: $final")

                            // WebSocketを切断（録音は継続）
                            this@WebSocketVoiceService.webSocket?.close(1000, "Recognition complete")
                            this@WebSocketVoiceService.webSocket = null

                            // 結果をコールバック
                            mainHandler.post { onResultCallback?.invoke(final) }

                            Log.d(TAG, "Recognition complete, ready for next speech")
                        }
                        json.has("error") -> {
                            val error = json.getString("error")
                            Log.e(TAG, "Server error: $error")

                            // WebSocketを切断（録音は継続）
                            this@WebSocketVoiceService.webSocket?.close(1000, "Error")
                            this@WebSocketVoiceService.webSocket = null

                            mainHandler.post { onErrorCallback?.invoke(error) }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to parse response: $text", e)
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                Log.d(TAG, "Received bytes: ${bytes.size}")
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket closing: $code $reason")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket closed: $code $reason")
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket error", t)

                // WebSocketを切断（録音は継続）
                this@WebSocketVoiceService.webSocket = null

                mainHandler.post { onErrorCallback?.invoke(t.message ?: "WebSocket connection failed") }
                Log.d(TAG, "WebSocket failed, but recording continues for next attempt")
            }
        })
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

            Log.d(TAG, "Started THINKLET 6-channel 48kHz recording with XFE VAD (buffer: $audioRecordBufferSize bytes)")

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
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping recording", e)
        }

        // WebSocket切断
        webSocket?.close(1000, "Recording stopped")
        webSocket = null
    }

    fun shutdown() {
        stopRecording()

        // XFEクリーンアップ
        xfe?.cleanup()
        xfe = null

        client.dispatcher.executorService.shutdown()
    }

    companion object {
        private const val TAG = "WebSocketVoiceService"
    }
}
