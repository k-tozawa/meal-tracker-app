package ai.fd.thinklet.app.outing.advisor

import android.graphics.Bitmap
import android.util.Log
import com.google.gson.Gson
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WeatherRepository @Inject constructor(
    private val weatherApiService: WeatherApiService,
    private val geminiApiService: GeminiApiService,
    private val imageRecognitionRepository: ImageRecognitionRepository
) {
    companion object {
        private const val TAG = "WeatherRepository"
        private val OPENWEATHER_API_KEY get() = BuildConfig.OPENWEATHER_API_KEY
        private val GEMINI_API_KEY get() = BuildConfig.GEMINI_API_KEY
    }

    suspend fun getWeatherAdvice(latitude: Double, longitude: Double): Result<String> {
        return try {
            Log.d(TAG, "天気情報を取得中: lat=$latitude, lon=$longitude")

            // 1. 天気情報を取得
            val weatherResponse = weatherApiService.getCurrentWeather(
                lat = latitude,
                lon = longitude,
                apiKey = OPENWEATHER_API_KEY
            )

            Log.d(TAG, "天気情報取得成功: ${weatherResponse.weather?.firstOrNull()?.description}")

            // 2. 天気情報をJSON文字列に変換
            val weatherJson = Gson().toJson(weatherResponse)
            Log.d(TAG, "天気JSON: $weatherJson")

            // 3. Gemini APIにリクエストを送信
            val prompt = """
以下の天気予報JSONデータに基づいて、現在の天気についての簡単な要約と、傘や長袖が必要かどうかなどの準備について具体的にアドバイスしてください。

データ:
```json
$weatherJson
```

以下の形式で簡潔に回答してください：
- 天気概要: (天気の状態を一言で)
- 気温: (現在の気温と体感気温)
- 準備: (傘、上着、日焼け止めなど必要なものを箇条書きで)
""".trimIndent()

            val geminiRequest = GeminiRequest(
                contents = listOf(
                    Content(
                        parts = listOf(Part(text = prompt))
                    )
                )
            )

            Log.d(TAG, "Gemini APIにリクエスト送信中...")
            val geminiResponse = geminiApiService.generateContent(
                request = geminiRequest,
                apiKey = GEMINI_API_KEY
            )

            val advice = geminiResponse.candidates?.firstOrNull()
                ?.content?.parts?.firstOrNull()?.text
                ?: "アドバイスを生成できませんでした"

            Log.d(TAG, "Geminiからのアドバイス: $advice")

            Result.success(advice)
        } catch (e: Exception) {
            Log.e(TAG, "天気情報の取得エラー", e)
            Result.failure(Exception("天気情報の取得に失敗しました: ${e.message}"))
        }
    }

    /**
     * 靴の画像認識結果と天気情報を組み合わせてアドバイスを取得
     */
    suspend fun getWeatherAdviceWithShoeImage(
        latitude: Double,
        longitude: Double,
        shoeBitmap: Bitmap?
    ): Result<String> {
        return try {
            Log.d(TAG, "天気情報と靴の画像認識を開始")

            // 1. 靴の画像認識（画像が提供されている場合）
            val shoeDetected = if (shoeBitmap != null) {
                Log.d(TAG, "靴の画像認識を実行中...")
                val result = imageRecognitionRepository.detectShoes(shoeBitmap)
                result.getOrElse {
                    Log.w(TAG, "靴の認識に失敗しましたが、天気情報の取得は続行します")
                    false
                }
            } else {
                Log.d(TAG, "画像が提供されていないため、靴認識をスキップ")
                false
            }

            // 2. 天気情報を取得
            val weatherResponse = weatherApiService.getCurrentWeather(
                lat = latitude,
                lon = longitude,
                apiKey = OPENWEATHER_API_KEY
            )

            Log.d(TAG, "天気情報取得成功: ${weatherResponse.weather?.firstOrNull()?.description}")

            // 3. 天気情報をJSON文字列に変換
            val weatherJson = Gson().toJson(weatherResponse)

            // 4. Gemini APIにリクエストを送信（靴認識結果を含む）
            val prompt = if (shoeDetected) {
                """
ユーザーは靴の写真を撮影しました。以下の天気予報JSONデータに基づいて、現在の天気についての簡単な要約と、その靴で外出するのが適切かどうか、または他の靴を推奨すべきかをアドバイスしてください。

データ:
```json
$weatherJson
```

以下の形式で簡潔に回答してください：
- 天気概要: (天気の状態を一言で)
- 気温: (現在の気温と体感気温)
- その靴について: (その靴で外出するのが適切か、雨や雪の場合は防水性のある靴を推奨するなど)
- 準備: (傘、上着、日焼け止めなど必要なものを箇条書きで)
""".trimIndent()
            } else {
                """
以下の天気予報JSONデータに基づいて、現在の天気についての簡単な要約と、傘や長袖が必要かどうかなどの準備について具体的にアドバイスしてください。

データ:
```json
$weatherJson
```

以下の形式で簡潔に回答してください：
- 天気概要: (天気の状態を一言で)
- 気温: (現在の気温と体感気温)
- 準備: (傘、上着、日焼け止めなど必要なものを箇条書きで)
""".trimIndent()
            }

            val geminiRequest = GeminiRequest(
                contents = listOf(
                    Content(
                        parts = listOf(Part(text = prompt))
                    )
                )
            )

            Log.d(TAG, "Gemini APIにリクエスト送信中...")
            val geminiResponse = geminiApiService.generateContent(
                request = geminiRequest,
                apiKey = GEMINI_API_KEY
            )

            val advice = geminiResponse.candidates?.firstOrNull()
                ?.content?.parts?.firstOrNull()?.text
                ?: "アドバイスを生成できませんでした"

            Log.d(TAG, "Geminiからのアドバイス: $advice")

            Result.success(advice)
        } catch (e: Exception) {
            Log.e(TAG, "天気情報の取得エラー", e)
            Result.failure(Exception("天気情報の取得に失敗しました: ${e.message}"))
        }
    }
}
