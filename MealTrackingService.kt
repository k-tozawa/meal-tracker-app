package ai.fd.mealtracker

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect

/**
 * 食事記録サービス - 全機能を統合
 *
 * 音声で操作するメインサービス
 */
class MealTrackingService : Service() {
    private lateinit var voiceAssistant: VoiceAssistant
    private lateinit var cameraManager: CameraManager
    private lateinit var mealAnalyzer: MealAnalyzer
    private lateinit var serverAPI: ServerAPI
    private lateinit var database: MealDatabase

    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
    private var currentState: AppState = AppState.Idle
    private var currentUserId = "default_user"  // 実際はログイン情報から取得

    companion object {
        private const val TAG = "MealTrackingService"
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "Service created")

        // コンポーネントを初期化
        initializeComponents()

        // 起動時の挨拶
        voiceAssistant.speakAndListen("こんにちは。食事を記録しますか？")
    }

    private fun initializeComponents() {
        // Voice Assistant
        voiceAssistant = VoiceAssistant(this) { command, text ->
            handleVoiceCommand(command, text)
        }

        // Camera
        cameraManager = CameraManager(this)

        // Server API (実際の実装が必要)
        serverAPI = ServerAPIImpl(baseUrl = "http://192.168.1.100:8000")

        // Database (実際の実装が必要)
        database = MealDatabase.getInstance(this)

        // Analyzer
        mealAnalyzer = MealAnalyzer(this, serverAPI, database)
    }

    /**
     * 音声コマンドを処理
     */
    private fun handleVoiceCommand(command: VoiceCommand, text: String) {
        Log.i(TAG, "Command: $command, Text: $text")

        when (command) {
            VoiceCommand.RECORD_MEAL -> {
                handleRecordMeal()
            }

            VoiceCommand.VIEW_HISTORY -> {
                handleViewHistory()
            }

            VoiceCommand.GET_ADVICE, VoiceCommand.SUGGEST_MENU -> {
                handleGetAdvice(text)
            }

            VoiceCommand.CONFIRM_YES -> {
                handleConfirmYes()
            }

            VoiceCommand.CONFIRM_NO -> {
                handleConfirmNo()
            }

            VoiceCommand.CANCEL -> {
                handleCancel()
            }

            VoiceCommand.UNKNOWN -> {
                voiceAssistant.speakAndListen(
                    "すみません、もう一度お願いします。「ご飯を記録して」「アドバイスちょうだい」などと言ってください。"
                )
            }
        }
    }

    /**
     * 食事を記録
     */
    private fun handleRecordMeal() {
        if (currentState != AppState.Idle) {
            voiceAssistant.speak("今は処理中です。少しお待ちください。")
            return
        }

        currentState = AppState.TakingPhoto

        voiceAssistant.speak("写真を撮ります。3、2、1") {
            // カメラで撮影
            serviceScope.launch {
                try {
                    val photo = cameraManager.takePhoto(countdown = true) { count ->
                        // カウントダウン（音声で読み上げ済み）
                    }

                    cameraManager.closeCamera()

                    // 解析開始
                    analyzeMeal(photo)

                } catch (e: Exception) {
                    Log.e(TAG, "Failed to take photo", e)
                    voiceAssistant.speakAndListen("撮影に失敗しました。もう一度やり直しますか？")
                    currentState = AppState.Idle
                }
            }
        }
    }

    /**
     * 食事を解析（ハイブリッド）
     */
    private fun analyzeMeal(photo: java.io.File) {
        currentState = AppState.Analyzing(AnalysisStage.QUICK)

        voiceAssistant.speak("解析中です...")

        serviceScope.launch {
            try {
                mealAnalyzer.analyze(photo, currentUserId).collect { result ->
                    when (result) {
                        is AnalysisResult.Quick -> {
                            // 簡易結果（1-2秒後）
                            Log.i(TAG, "Quick result: ${result.category}")
                            voiceAssistant.speak("${result.category}が写っています。詳しく確認中...")
                            currentState = AppState.Analyzing(AnalysisStage.DETAILED)
                        }

                        is AnalysisResult.Detailed -> {
                            // 詳細結果（5-10秒後）
                            Log.i(TAG, "Detailed result: ${result.description}")
                            currentState = AppState.ConfirmingMeal(result)

                            // ユーザーに結果を伝える
                            val message = buildString {
                                append(result.description)
                                append(" これで記録しますか？")
                            }

                            voiceAssistant.speakAndListen(message)
                        }

                        is AnalysisResult.Error -> {
                            Log.e(TAG, "Analysis error: ${result.message}")
                            voiceAssistant.speakAndListen(
                                "${result.message} もう一度試しますか？"
                            )
                            currentState = AppState.Idle
                        }
                    }
                }

            } catch (e: Exception) {
                Log.e(TAG, "Analysis failed", e)
                voiceAssistant.speakAndListen("解析に失敗しました。もう一度試しますか？")
                currentState = AppState.Idle
            }
        }
    }

    /**
     * 確認: はい
     */
    private fun handleConfirmYes() {
        when (val state = currentState) {
            is AppState.ConfirmingMeal -> {
                // 食事を記録
                serviceScope.launch {
                    val result = state.result
                    // 既にデータベースに保存済み（MealAnalyzerで保存）

                    voiceAssistant.speakAndListen(
                        "記録しました。${result.advice} 他にできることはありますか？"
                    )
                    currentState = AppState.Idle
                }
            }

            else -> {
                voiceAssistant.speakAndListen("何を確認しますか？")
            }
        }
    }

    /**
     * 確認: いいえ
     */
    private fun handleConfirmNo() {
        when (currentState) {
            is AppState.ConfirmingMeal -> {
                voiceAssistant.speakAndListen(
                    "内容を修正しますか？それともキャンセルしますか？"
                )
            }

            else -> {
                voiceAssistant.speakAndListen("分かりました。他にできることはありますか？")
                currentState = AppState.Idle
            }
        }
    }

    /**
     * キャンセル
     */
    private fun handleCancel() {
        currentState = AppState.Idle
        voiceAssistant.speakAndListen("キャンセルしました。他にできることはありますか？")
    }

    /**
     * 履歴を表示
     */
    private fun handleViewHistory() {
        serviceScope.launch {
            try {
                val meals = serverAPI.getMealHistory(currentUserId, limit = 5)

                if (meals.isEmpty()) {
                    voiceAssistant.speakAndListen("まだ食事の記録がありません。")
                } else {
                    val summary = buildString {
                        append("直近の食事記録です。")
                        meals.take(3).forEach { meal ->
                            append(meal.description)
                            append("、")
                        }
                        append("以上です。")
                    }
                    voiceAssistant.speakAndListen(summary)
                }

            } catch (e: Exception) {
                Log.e(TAG, "Failed to get history", e)
                voiceAssistant.speakAndListen("履歴の取得に失敗しました。")
            }
        }
    }

    /**
     * アドバイスを取得
     */
    private fun handleGetAdvice(context: String) {
        currentState = AppState.ShowingAdvice("")

        serviceScope.launch {
            try {
                val advice = serverAPI.getAdvice(currentUserId, context)
                currentState = AppState.ShowingAdvice(advice)
                voiceAssistant.speakAndListen("$advice 他にできることはありますか？")

            } catch (e: Exception) {
                Log.e(TAG, "Failed to get advice", e)
                voiceAssistant.speakAndListen("アドバイスの取得に失敗しました。")
            } finally {
                currentState = AppState.Idle
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "Service destroyed")

        // クリーンアップ
        voiceAssistant.release()
        cameraManager.release()
        serviceScope.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
