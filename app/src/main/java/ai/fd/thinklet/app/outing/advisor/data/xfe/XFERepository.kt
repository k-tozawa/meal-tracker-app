package ai.fd.thinklet.app.outing.advisor.data.xfe

import ai.fd.thinklet.app.outing.advisor.data.model.AudioProcessingConfig
import ai.fd.thinklet.app.outing.advisor.data.model.AudioStats
import ai.fd.thinklet.app.outing.advisor.data.model.VadStatus
import kotlinx.coroutines.flow.SharedFlow

/**
 * XFE（音声処理エンジン）とのインタラクションを抽象化するリポジトリインターフェース
 */
interface XFERepository {

    /**
     * VADステータス（音声検出状態）を監視するためのFlow
     */
    val vadStatusFlow: SharedFlow<VadStatus>

    /**
     * VAD検出された音声データを監視するためのFlow
     */
    val vadAudioFlow: SharedFlow<ByteArray>

    /**
     * オーディオ統計情報を監視するためのFlow
     */
    val audioStatsFlow: SharedFlow<AudioStats>

    /**
     * XFEエンジンの設定とセットアップを行う
     *
     * @param config 音声処理構成設定
     * @return セットアップが成功した場合はtrue、失敗した場合はfalse
     */
    fun setupXfe(config: AudioProcessingConfig): Boolean

    /**
     * XFEエンジンのリソースをクリーンアップする
     */
    fun cleanupXfe()

    /**
     * 音声処理を開始する
     *
     * @param config 音声処理構成設定
     * @return 処理開始が成功した場合は0、失敗した場合は負の値
     */
    fun startProcessing(config: AudioProcessingConfig): Int

    /**
     * 音声処理を停止する
     */
    fun stopProcessing()

    /**
     * オーディオデータをXFEエンジンに送信する
     *
     * @param buffer 処理するオーディオデータバッファ
     * @return 成功した場合は処理されたデータサイズ、失敗した場合は負の値
     */
    fun enqueueAudioData(buffer: ByteArray): Int
}
