package ai.fd.thinklet.app.outing.advisor.data.xfe

import ai.fd.thinklet.app.outing.advisor.data.model.AudioProcessingConfig
import ai.fd.thinklet.app.outing.advisor.data.model.AudioStats
import ai.fd.thinklet.app.outing.advisor.data.model.VadStatus
import ai.fd.thinklet.xfe.TLXFECallback
import ai.fd.thinklet.xfe.TLXFEConfigs
import ai.fd.thinklet.xfe.TLXFEData
import ai.fd.thinklet.xfe.TLXFEPreprocessor
import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.io.File
import java.io.FileInputStream
import java.nio.charset.Charset
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

@Singleton
class XFERepositoryImpl @Inject constructor() : XFERepository {

    companion object {
        private const val TAG = "XFERepository"
        private const val XFE_LICENSE_FILE_PATH = "/mnt/sdcard/thinklet/xfe-license.dat"
        private const val XFE_LICENSE_FILE_LENGTH_LIMIT = 1024
        private const val DISPLAY_VAD_AZIMUTH_CHANGE_THRESHOLD = 3
        private const val DISPLAY_VAD_PEAK_CHANGE_THRESHOLD = 1.0f
    }

    private var xfe: TLXFEPreprocessor? = null
    private var displayAzimuth = -1
    private var displayPeak = 0.0f
    private var processedRms = 0.0f
    private var processedProbability = 0.0f

    // SharedFlowをMutableSharedFlowで初期化し、replay=1でキャッシュ
    private val _vadStatusFlow = MutableSharedFlow<VadStatus>(replay = 1)
    override val vadStatusFlow: SharedFlow<VadStatus> = _vadStatusFlow.asSharedFlow()

    private val _vadAudioFlow = MutableSharedFlow<ByteArray>(replay = 0, extraBufferCapacity = 64)
    override val vadAudioFlow: SharedFlow<ByteArray> = _vadAudioFlow.asSharedFlow()

    private val _audioStatsFlow = MutableSharedFlow<AudioStats>(replay = 1)
    override val audioStatsFlow: SharedFlow<AudioStats> = _audioStatsFlow.asSharedFlow()

    // Callback implementations
    private val vadCallback = object : TLXFECallback.VadCallback {
        override fun onData(
            aBuffer: ByteArray,
            aSpeechState: TLXFEData.SpeechState,
            aSourceIndex: Int,
            aStreamInfo: Array<TLXFEData.StreamInfo>?,
            aUserData: Any?
        ) {
            Log.d(TAG, "VAD Callback - SpeechState: $aSpeechState")

            when (aSpeechState) {
                TLXFEData.SpeechState.SpeechStart -> {
                    displayAzimuth = -1
                    val vadStatus =
                        VadStatus(isInSpeech = true, azimuth = displayAzimuth, peak = displayPeak)
                    Log.d(TAG, "Emitting VAD Status: $vadStatus")
                    _vadStatusFlow.tryEmit(vadStatus)
                    // 音声データをFlowに送信
                    _vadAudioFlow.tryEmit(aBuffer)
                }

                TLXFEData.SpeechState.InSpeech -> {
                    val vadStatus =
                        VadStatus(isInSpeech = true, azimuth = displayAzimuth, peak = displayPeak)
                    _vadStatusFlow.tryEmit(vadStatus)
                    // 音声データをFlowに送信
                    _vadAudioFlow.tryEmit(aBuffer)
                }

                TLXFEData.SpeechState.SpeechEnd -> {
                    val vadStatus = VadStatus(isInSpeech = false, azimuth = -1, peak = 0.0f)
                    Log.d(TAG, "Emitting VAD Status (End): $vadStatus")
                    _vadStatusFlow.tryEmit(vadStatus)
                    // 最後の音声データをFlowに送信
                    if (aBuffer.isNotEmpty()) {
                        _vadAudioFlow.tryEmit(aBuffer)
                    }
                }

                else -> {
                    Log.d(TAG, "VAD Callback - Other state: $aSpeechState")
                }
            }

            // StreamInfo処理
            aStreamInfo?.let { infoArray ->
                if (infoArray.isEmpty()) return@let

                var currentPeakSum = 0.0f
                var currentAzimuthSum = 0
                var validAzimuthCount = 0

                for (itInfo in infoArray) {
                    val currentAzimuth = itInfo.estimatedDirection.azimuth
                    if (0 <= currentAzimuth) {
                        currentPeakSum += itInfo.spatialSpectralPeak
                        currentAzimuthSum += currentAzimuth
                        validAzimuthCount++
                    }
                }

                if (0 < validAzimuthCount) {
                    val azimuthAverage = currentAzimuthSum / validAzimuthCount
                    val peakAverage = currentPeakSum / validAzimuthCount

                    var shouldUpdate = false

                    if (DISPLAY_VAD_AZIMUTH_CHANGE_THRESHOLD < abs(azimuthAverage - displayAzimuth)) {
                        displayAzimuth = azimuthAverage
                        shouldUpdate = true
                    }

                    if (DISPLAY_VAD_PEAK_CHANGE_THRESHOLD < abs(peakAverage - displayPeak)) {
                        displayPeak = peakAverage
                        shouldUpdate = true
                    }

                    if (shouldUpdate) {
                        val vadStatus = VadStatus(
                            isInSpeech = aSpeechState == TLXFEData.SpeechState.InSpeech || aSpeechState == TLXFEData.SpeechState.SpeechStart,
                            azimuth = displayAzimuth,
                            peak = displayPeak
                        )
                        Log.d(TAG, "Emitting VAD Status (Info): $vadStatus")
                        _vadStatusFlow.tryEmit(vadStatus)
                    }
                }
            }
        }
    }

    private val processedCallback = object : TLXFECallback.ProcessedCallback {
        override fun onData(
            aBuffer: ByteArray,
            aSourceIndex: Int,
            aStreamInfo: Array<TLXFEData.StreamInfo>?,
            aUserData: Any?
        ) {
            if (aBuffer.isEmpty()) return

            aStreamInfo?.let { infoArray ->
                if (infoArray.isEmpty()) return@let

                var rmsSum = 0.0f
                var probabilitySum = 0.0f
                for (itInfo in infoArray) {
                    rmsSum += (itInfo.rmsDbfs / infoArray.size)
                    probabilitySum += (itInfo.speechProbability / infoArray.size)
                }

                if ((0.5f < abs(rmsSum - processedRms)) || (0.05f < abs(probabilitySum - processedProbability))) {
                    processedRms = rmsSum
                    processedProbability = probabilitySum

                    val audioStats =
                        AudioStats(rms = processedRms, speechProbability = processedProbability)
                    Log.d(TAG, "Emitting Audio Stats: $audioStats")
                    _audioStatsFlow.tryEmit(audioStats)
                }
            }
        }
    }

    override fun setupXfe(config: AudioProcessingConfig): Boolean {
        Log.d(TAG, "setupXfe() : E")

        val licenseData = getLicenseData()
        if (licenseData.isEmpty()) {
            Log.e(TAG, "Valid license data is not found.")
            return false
        }

        xfe = TLXFEPreprocessor(config.mode)
        xfe?.registerLicenseData(licenseData)

        if (config.mode == TLXFEPreprocessor.ProcessMode.HumanVoice) {
            Log.i(TAG, "ProcessMode is HumanVoice mode.")
            xfe?.registerVadCallback(vadCallback, null)

            xfe?.setSourceConfig(
                TLXFEConfigs.Source.Builder()
                    .setUseVerticalPlane(true)
                    .setInputSamplingRate(48000)
                    .setOutputSamplingRate(16000)
                    .build()
            )

            xfe?.setVadConfig(
                TLXFEConfigs.Vad.Builder()
                    .setTimeToActive(100)
                    .setTimeToInactive(600)
                    .setHeadPaddingTime(400)
                    .setTailPaddingTime(400)
                    .setDbfsThreshold(-60)
                    .build()
            )

            xfe?.setLocalizerConfig(
                TLXFEConfigs.Localizer.Builder()
                    .setType(TLXFEConfigs.Localizer.TYPE_STATIC)
                    .setDirection(60, 90)
                    .setIdenticalRange(30)
                    .build()
            )
        } else {
            Log.i(TAG, "ProcessMode is GeneralSound mode.")
            xfe?.registerProcessedCallback(processedCallback, null)

            xfe?.setSourceConfig(
                TLXFEConfigs.Source.Builder()
                    .setUseVerticalPlane(false)
                    .setInputSamplingRate(48000)
                    .setOutputSamplingRate(48000)
                    .build()
            )

            xfe?.setBeamformConfig(
                TLXFEConfigs.Beamform.Builder()
                    .setDefaultDirection(0, 90)
                    .setSensitivity(0.8f)
                    .setUsePostFilter(true)
                    .build()
            )

            xfe?.setNoiseReductionConfig(
                TLXFEConfigs.NoiseReduction.Builder()
                    .setEnable(true)
                    .setReductionLevel(0.9)
                    .setArtifactLevel(1.0)
                    .setPrecision(false)
                    .build()
            )
        }

        xfe?.setup()

        // セットアップ後に初期状態を送信
        _vadStatusFlow.tryEmit(VadStatus())
        _audioStatsFlow.tryEmit(AudioStats())

        Log.d(TAG, "setupXfe() : X with true.")
        return true
    }

    override fun cleanupXfe() {
        Log.d(TAG, "cleanupXfe() : E")
        xfe?.cleanup()
        xfe = null

        // クリーンアップ後に初期状態にリセット
        _vadStatusFlow.tryEmit(VadStatus())
        _audioStatsFlow.tryEmit(AudioStats())

        Log.d(TAG, "cleanupXfe() : X")
    }

    override fun startProcessing(config: AudioProcessingConfig): Int {
        Log.d(TAG, "startProcessing() : E")
        val ret = xfe?.startProcessing() ?: Int.MIN_VALUE
        if (0 > ret) {
            Log.d(TAG, "startProcessing() : X with $ret")
            return ret
        }

        // 処理開始時に初期状態を送信
        _vadStatusFlow.tryEmit(VadStatus())
        _audioStatsFlow.tryEmit(AudioStats())

        Log.d(TAG, "startProcessing() : X with 0")
        return 0
    }

    override fun stopProcessing() {
        Log.d(TAG, "stopProcessing() : E")
        xfe?.stopProcessing()

        // 処理停止時に初期状態にリセット
        _vadStatusFlow.tryEmit(VadStatus())
        _audioStatsFlow.tryEmit(AudioStats())

        Log.d(TAG, "stopProcessing() : X")
    }

    override fun enqueueAudioData(buffer: ByteArray): Int {
        return xfe?.enqueue(buffer) ?: Int.MIN_VALUE
    }

    private fun getLicenseData(): String {
        val licenseFile = File(XFE_LICENSE_FILE_PATH)
        if (!licenseFile.isFile) {
            Log.e(TAG, "license file is not found.")
            return ""
        }

        val dataLength = licenseFile.length()
        if (XFE_LICENSE_FILE_LENGTH_LIMIT < dataLength) {
            Log.e(TAG, "May not be a license file, too long.")
            return ""
        }

        return try {
            val readBuffer = ByteArray(dataLength.toInt())
            val licenseFileStream = FileInputStream(licenseFile)
            licenseFileStream.read(readBuffer)
            licenseFileStream.close()
            readBuffer.toString(Charset.defaultCharset())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read license file", e)
            ""
        }
    }
}
