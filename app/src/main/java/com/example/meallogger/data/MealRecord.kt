package com.example.meallogger.data

data class MealRecord(
    val meal_id: String,
    val timestamp: String,
    val description: String?,
    val items: List<MealItem>,
    val nutrition: NutritionalInfo?,
    val advice: String?,
    val photo_path: String? = null
)
