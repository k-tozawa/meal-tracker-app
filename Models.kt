package ai.fd.mealtracker

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.Date

/**
 * データモデル定義
 */

// 食品アイテム
data class FoodItem(
    val name: String,
    val amount: String,
    val ingredients: List<String>
)

// 栄養情報
data class Nutrition(
    val calories: Int,
    val protein: Float,
    val carbs: Float,
    val fat: Float
)

// 解析結果（段階的）
sealed class AnalysisResult {
    // 簡易解析結果（オンデバイスML、1-2秒）
    data class Quick(
        val category: String,  // "料理", "和食", "洋食" など
        val confidence: Float
    ) : AnalysisResult()

    // 詳細解析結果（サーバー、5-10秒）
    data class Detailed(
        val mealId: String,
        val description: String,  // 自然な話し言葉での説明
        val items: List<FoodItem>,
        val nutrition: Nutrition,
        val advice: String
    ) : AnalysisResult()

    // エラー
    data class Error(val message: String) : AnalysisResult()
}

// 同期状態
enum class SyncStatus {
    PENDING,   // サーバーに未送信
    SYNCED,    // 同期済み
    FAILED     // 同期失敗
}

// Room エンティティ
@Entity(tableName = "meals")
data class MealEntity(
    @PrimaryKey val mealId: String,
    val userId: String,
    val timestamp: Long,
    val description: String,
    val itemsJson: String,       // JSON文字列
    val nutritionJson: String,   // JSON文字列
    val photoPath: String,
    val syncStatus: SyncStatus,
    val lastSyncAttempt: Long? = null
)

// サーバーレスポンス
data class MealAnalysisResponse(
    val meal_id: String,
    val timestamp: String,
    val description: String,
    val items: List<Map<String, Any>>,
    val nutritional_info: Map<String, Any>,
    val suggestions: String
)

data class AdviceResponse(
    val advice: String,
    val timestamp: String
)

// 音声コマンド
enum class VoiceCommand {
    RECORD_MEAL,       // 「ご飯を記録して」
    VIEW_HISTORY,      // 「今日の食事は？」
    GET_ADVICE,        // 「アドバイスちょうだい」
    SUGGEST_MENU,      // 「おすすめの献立は？」
    CONFIRM_YES,       // 「はい」
    CONFIRM_NO,        // 「いいえ」
    CANCEL,            // 「キャンセル」
    UNKNOWN            // 認識できず
}

// アプリの状態
sealed class AppState {
    object Idle : AppState()
    object Listening : AppState()
    object TakingPhoto : AppState()
    data class Analyzing(val stage: AnalysisStage) : AppState()
    data class ConfirmingMeal(val result: AnalysisResult.Detailed) : AppState()
    data class Correcting(val mealId: String) : AppState()
    data class ShowingAdvice(val advice: String) : AppState()
}

enum class AnalysisStage {
    QUICK,      // オンデバイスML実行中
    DETAILED,   // サーバー解析中
    COMPLETE    // 完了
}
