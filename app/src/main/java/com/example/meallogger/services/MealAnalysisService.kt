package com.example.meallogger.services

import android.content.Context
import android.util.Log
import com.example.meallogger.data.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.*

class MealAnalysisService(private val context: Context) {

    private fun getApiService() = ApiClient.getApiService(context)

    suspend fun analyzeMeal(imageFile: File, userId: String): AnalyzeResponse? {
        return try {
            val requestBody = imageFile.asRequestBody("image/jpeg".toMediaTypeOrNull())
            val imagePart = MultipartBody.Part.createFormData("file", imageFile.name, requestBody)
            val userIdPart = MultipartBody.Part.createFormData("userId", userId)

            val response = getApiService().analyzeMeal(imagePart, userIdPart)

            if (response.isSuccessful) {
                Log.d(TAG, "Analysis successful: ${response.body()}")
                response.body()
            } else {
                Log.e(TAG, "Analysis failed: ${response.code()}")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Analysis error", e)
            null
        }
    }

    suspend fun saveMealRecord(
        userId: String,
        description: String?,
        items: List<MealItem>,
        nutrition: NutritionalInfo?,
        advice: String?
    ): Boolean {
        return try {
            val request = SaveMealRequest(
                userId = userId,
                description = description,
                items = items,
                nutrition = nutrition,
                advice = advice
            )
            val response = getApiService().saveMeal(request)

            Log.d(TAG, "Response code: ${response.code()}")
            Log.d(TAG, "Response body: ${response.body()}")

            if (response.isSuccessful && response.body()?.status == "success") {
                Log.d(TAG, "Meal saved successfully: ${response.body()?.meal_id}")
                true
            } else {
                Log.e(TAG, "Save failed: ${response.code()} - ${response.errorBody()?.string()}")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Save error", e)
            false
        }
    }

    suspend fun getMealHistory(userId: String): List<MealRecord> {
        return try {
            val response = getApiService().getMeals(userId)

            if (response.isSuccessful) {
                response.body() ?: emptyList()
            } else {
                Log.e(TAG, "Get meals failed: ${response.code()}")
                emptyList()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Get meals error", e)
            emptyList()
        }
    }

    suspend fun suggestMeal(userId: String, mealType: String? = null, preferences: String? = null): SuggestMealResponse? {
        return try {
            val response = getApiService().suggestMeal(userId, mealType, preferences)

            if (response.isSuccessful) {
                Log.d(TAG, "Suggestion received: ${response.body()}")
                response.body()
            } else {
                Log.e(TAG, "Suggestion failed: ${response.code()} - ${response.errorBody()?.string()}")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Suggestion error", e)
            null
        }
    }

    suspend fun getAdvice(userId: String): AdviceResponse? {
        return try {
            val mealHistory = getMealHistory(userId)
            val request = AdviceRequest(userId, mealHistory)
            val response = getApiService().getAdvice(request)

            if (response.isSuccessful) {
                Log.d(TAG, "Advice received")
                response.body()
            } else {
                Log.e(TAG, "Advice failed: ${response.code()}")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Advice error", e)
            null
        }
    }

    companion object {
        private const val TAG = "MealAnalysisService"
    }
}
