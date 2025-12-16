package com.example.meallogger.data

import okhttp3.MultipartBody
import okhttp3.RequestBody
import retrofit2.Response
import retrofit2.http.*

interface ApiService {

    @Multipart
    @POST("analyze-meal")
    suspend fun analyzeMeal(
        @Part file: MultipartBody.Part,
        @Part userId: MultipartBody.Part
    ): Response<AnalyzeResponse>

    @POST("save-meal")
    suspend fun saveMeal(@Body request: SaveMealRequest): Response<SaveMealResponse>

    @GET("meals/{userId}")
    suspend fun getMeals(@Path("userId") userId: String): Response<List<MealRecord>>

    @GET("api/meal-suggestions/{userId}")
    suspend fun suggestMeal(
        @Path("userId") userId: String,
        @Query("meal_type") mealType: String? = null,
        @Query("preferences") preferences: String? = null
    ): Response<SuggestMealResponse>

    @POST("get-advice")
    suspend fun getAdvice(@Body request: AdviceRequest): Response<AdviceResponse>

    @POST("tts")
    suspend fun textToSpeech(@Body request: TTSRequest): Response<okhttp3.ResponseBody>
}

data class MealItem(
    val name: String,
    val amount: String?,
    val ingredients: List<String>?
)

data class NutritionalInfo(
    val calories: Int?,
    val protein: Int?,
    val carbs: Int?,
    val fat: Int?
)

data class AnalyzeResponse(
    val meal_id: String?,
    val timestamp: String?,
    val description: String?,
    val items: List<MealItem>,
    val nutrition: NutritionalInfo?,  // nutrition not nutritional_info
    val advice: String?
)

data class SaveMealRequest(
    val userId: String,
    val description: String?,
    val items: List<MealItem>,
    val nutrition: NutritionalInfo?,  // nutrition not nutritional_info
    val advice: String? = null,
    val photo_path: String? = null
)

data class SaveMealResponse(
    val status: String,
    val meal_id: String,
    val timestamp: String
)

data class SuggestMealResponse(
    val meal_name: String,
    val dishes: List<DishSuggestion>,
    val nutrition_summary: String,
    val reason: String,
    val based_on_meals_count: Int
)

data class DishSuggestion(
    val name: String,
    val description: String,
    val estimated_calories: Int
)

data class AdviceRequest(
    val userId: String,
    val mealHistory: List<MealRecord>
)

data class AdviceResponse(
    val advice: String,
    val trends: Map<String, Any>
)

data class TTSRequest(
    val text: String,
    val language: String = "ja-JP",
    val speakingRate: Float = 1.0f,
    val pitch: Float = 0.0f
)
