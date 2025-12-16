package ai.fd.mealtracker

import android.content.Context
import android.graphics.BitmapFactory
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.File

/**
 * ハイブリッド食事解析システム
 *
 * 段階的解析:
 * 1. オンデバイスML (1-2秒) → 即座にフィードバック
 * 2. サーバー解析 (5-10秒) → 詳細な結果
 */
class MealAnalyzer(
    private val context: Context,
    private val serverAPI: ServerAPI,
    private val database: MealDatabase
) {
    companion object {
        private const val TAG = "MealAnalyzer"
    }

    /**
     * 食事画像を解析（段階的に結果を返す）
     *
     * @param photo 食事の画像ファイル
     * @param userId ユーザーID
     * @return Flow<AnalysisResult> 解析結果のストリーム
     */
    fun analyze(photo: File, userId: String): Flow<AnalysisResult> = flow {
        try {
            // Step 1: オンデバイスML（即座に）
            Log.i(TAG, "Starting quick analysis...")
            val quickResult = performQuickAnalysis(photo)
            emit(quickResult)

            // Step 2: サーバー解析（詳細）
            Log.i(TAG, "Starting detailed analysis...")
            try {
                val detailedResult = performDetailedAnalysis(photo, userId)
                emit(detailedResult)

                // ローカルデータベースに保存
                saveToDatabase(userId, detailedResult, photo.absolutePath)

            } catch (e: Exception) {
                // サーバー解析失敗（オフラインなど）
                Log.e(TAG, "Detailed analysis failed", e)

                // オフラインキューに追加
                queueForLaterSync(userId, photo)

                emit(
                    AnalysisResult.Error(
                        "詳細解析は後で実行します。オフラインモードです。"
                    )
                )
            }

        } catch (e: Exception) {
            Log.e(TAG, "Analysis failed completely", e)
            emit(AnalysisResult.Error("解析に失敗しました: ${e.message}"))
        }
    }.flowOn(Dispatchers.IO)

    /**
     * オンデバイスML Kitで簡易解析
     */
    private suspend fun performQuickAnalysis(photo: File): AnalysisResult.Quick {
        return withContext(Dispatchers.Default) {
            try {
                val bitmap = BitmapFactory.decodeFile(photo.absolutePath)
                val image = InputImage.fromBitmap(bitmap, 0)

                val labeler = ImageLabeling.getClient(ImageLabelerOptions.DEFAULT_OPTIONS)

                // 同期的にラベルを取得（suspendコルーチン内）
                val labels = com.google.android.gms.tasks.Tasks.await(labeler.process(image))

                // 食事関連のラベルを検出
                val foodLabel = labels.firstOrNull { label ->
                    label.text.lowercase().let {
                        it.contains("food") ||
                                it.contains("dish") ||
                                it.contains("meal") ||
                                it.contains("cuisine")
                    }
                }

                val category = foodLabel?.text ?: "料理"
                val confidence = foodLabel?.confidence ?: 0.5f

                Log.i(TAG, "Quick analysis: $category (confidence: $confidence)")

                AnalysisResult.Quick(
                    category = category,
                    confidence = confidence
                )

            } catch (e: Exception) {
                Log.e(TAG, "Quick analysis failed", e)
                AnalysisResult.Quick("料理", 0.3f)
            }
        }
    }

    /**
     * サーバーでClaude Vision APIを使用して詳細解析
     */
    private suspend fun performDetailedAnalysis(
        photo: File,
        userId: String
    ): AnalysisResult.Detailed {
        return withContext(Dispatchers.IO) {
            val response = serverAPI.analyzeImage(photo, userId)

            // レスポンスをAnalysisResult.Detailedに変換
            AnalysisResult.Detailed(
                mealId = response.meal_id,
                description = response.description,
                items = response.items.map { item ->
                    FoodItem(
                        name = item["name"] as? String ?: "",
                        amount = item["amount"] as? String ?: "",
                        ingredients = (item["ingredients"] as? List<*>)?.mapNotNull { it as? String }
                            ?: emptyList()
                    )
                },
                nutrition = Nutrition(
                    calories = (response.nutritional_info["calories"] as? Number)?.toInt() ?: 0,
                    protein = (response.nutritional_info["protein"] as? Number)?.toFloat() ?: 0f,
                    carbs = (response.nutritional_info["carbs"] as? Number)?.toFloat() ?: 0f,
                    fat = (response.nutritional_info["fat"] as? Number)?.toFloat() ?: 0f
                ),
                advice = response.suggestions
            )
        }
    }

    /**
     * データベースに保存
     */
    private suspend fun saveToDatabase(
        userId: String,
        result: AnalysisResult.Detailed,
        photoPath: String
    ) {
        withContext(Dispatchers.IO) {
            try {
                val entity = MealEntity(
                    mealId = result.mealId,
                    userId = userId,
                    timestamp = System.currentTimeMillis(),
                    description = result.description,
                    itemsJson = com.google.gson.Gson().toJson(result.items),
                    nutritionJson = com.google.gson.Gson().toJson(result.nutrition),
                    photoPath = photoPath,
                    syncStatus = SyncStatus.SYNCED
                )

                database.mealDao().insert(entity)
                Log.i(TAG, "Meal saved to database: ${result.mealId}")

            } catch (e: Exception) {
                Log.e(TAG, "Failed to save to database", e)
            }
        }
    }

    /**
     * オフラインキューに追加（後で同期）
     */
    private suspend fun queueForLaterSync(userId: String, photo: File) {
        withContext(Dispatchers.IO) {
            try {
                val tempMealId = "temp_${System.currentTimeMillis()}"

                val entity = MealEntity(
                    mealId = tempMealId,
                    userId = userId,
                    timestamp = System.currentTimeMillis(),
                    description = "（未解析）",
                    itemsJson = "[]",
                    nutritionJson = "{}",
                    photoPath = photo.absolutePath,
                    syncStatus = SyncStatus.PENDING,
                    lastSyncAttempt = null
                )

                database.mealDao().insert(entity)
                Log.i(TAG, "Meal queued for later sync: $tempMealId")

            } catch (e: Exception) {
                Log.e(TAG, "Failed to queue meal", e)
            }
        }
    }

    /**
     * 食事記録を更新（ユーザーの修正を反映）
     */
    suspend fun updateMeal(
        mealId: String,
        description: String? = null,
        items: List<FoodItem>? = null
    ) {
        withContext(Dispatchers.IO) {
            try {
                // ローカルデータベースを更新
                val meal = database.mealDao().getMealById(mealId)
                if (meal != null) {
                    val updated = meal.copy(
                        description = description ?: meal.description,
                        itemsJson = items?.let { com.google.gson.Gson().toJson(it) }
                            ?: meal.itemsJson
                    )
                    database.mealDao().update(updated)
                }

                // サーバーにも反映
                if (description != null || items != null) {
                    val corrections = mutableMapOf<String, Any>()
                    description?.let { corrections["description"] = it }
                    items?.let { corrections["items"] = com.google.gson.Gson().toJson(it) }

                    serverAPI.updateMeal(mealId, corrections)
                }

                Log.i(TAG, "Meal updated: $mealId")

            } catch (e: Exception) {
                Log.e(TAG, "Failed to update meal", e)
            }
        }
    }
}
