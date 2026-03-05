package ai.fd.thinklet.app.outing.advisor

import android.graphics.Bitmap
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ImageRecognitionRepository @Inject constructor() {
    companion object {
        private const val TAG = "ImageRecognitionRepository"
        // 靴関連のラベルキーワード
        private val SHOE_KEYWORDS = listOf(
            "shoe", "footwear", "boot", "sneaker", "sandal",
            "靴", "履物", "ブーツ", "スニーカー", "サンダル"
        )
        private const val CONFIDENCE_THRESHOLD = 0.5f
    }

    /**
     * 画像から靴を認識する
     * @param bitmap 認識対象の画像
     * @return Result<Boolean> 靴が検出されたかどうか
     */
    suspend fun detectShoes(bitmap: Bitmap): Result<Boolean> {
        return try {
            Log.d(TAG, "画像認識を開始します")

            // ML Kitの画像ラベリングを初期化
            val labeler = ImageLabeling.getClient(
                ImageLabelerOptions.Builder()
                    .setConfidenceThreshold(CONFIDENCE_THRESHOLD)
                    .build()
            )

            // InputImageを作成
            val inputImage = InputImage.fromBitmap(bitmap, 0)

            // 画像認識を実行
            val labels = labeler.process(inputImage).await()

            Log.d(TAG, "認識されたラベル: ${labels.joinToString { "${it.text}(${it.confidence})" }}")

            // 靴関連のラベルがあるか確認
            val hasShoe = labels.any { label ->
                SHOE_KEYWORDS.any { keyword ->
                    label.text.contains(keyword, ignoreCase = true)
                }
            }

            Log.d(TAG, "靴の検出結果: $hasShoe")

            Result.success(hasShoe)
        } catch (e: Exception) {
            Log.e(TAG, "画像認識エラー", e)
            Result.failure(Exception("画像認識に失敗しました: ${e.message}"))
        }
    }

    /**
     * 認識されたラベルの詳細を取得する
     */
    suspend fun getLabels(bitmap: Bitmap): Result<List<String>> {
        return try {
            val labeler = ImageLabeling.getClient(
                ImageLabelerOptions.Builder()
                    .setConfidenceThreshold(CONFIDENCE_THRESHOLD)
                    .build()
            )

            val inputImage = InputImage.fromBitmap(bitmap, 0)
            val labels = labeler.process(inputImage).await()

            val labelTexts = labels.map { "${it.text} (${(it.confidence * 100).toInt()}%)" }
            Result.success(labelTexts)
        } catch (e: Exception) {
            Log.e(TAG, "ラベル取得エラー", e)
            Result.failure(Exception("ラベル取得に失敗しました: ${e.message}"))
        }
    }
}
