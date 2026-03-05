package ai.fd.thinklet.app.outing.advisor.data.model

import ai.fd.thinklet.xfe.TLXFEPreprocessor

/**
 * VAD（Voice Activity Detection）の状態を表すデータクラス
 */
data class VadStatus(
    val isInSpeech: Boolean = false,
    val azimuth: Int = -1,
    val peak: Float = 0.0f
)

/**
 * 音声処理の設定情報
 */
data class AudioProcessingConfig(
    val mode: TLXFEPreprocessor.ProcessMode = TLXFEPreprocessor.ProcessMode.HumanVoice,
    val isVadEnabled: Boolean = true
)

/**
 * 音声統計情報
 */
data class AudioStats(
    val rms: Float = 0.0f,
    val speechProbability: Float = 0.0f
)
