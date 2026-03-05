package ai.fd.thinklet.app.outing.advisor

import com.google.gson.annotations.SerializedName

// Yahoo天気API (OpenWeatherMap代替)
// 実際にはYahoo天気は公式APIを提供していないため、OpenWeatherMap APIを使用
data class WeatherResponse(
    @SerializedName("coord") val coord: Coord?,
    @SerializedName("weather") val weather: List<Weather>?,
    @SerializedName("main") val main: Main?,
    @SerializedName("wind") val wind: Wind?,
    @SerializedName("clouds") val clouds: Clouds?,
    @SerializedName("rain") val rain: Rain?,
    @SerializedName("dt") val dt: Long?,
    @SerializedName("name") val name: String?
)

data class Coord(
    @SerializedName("lat") val lat: Double?,
    @SerializedName("lon") val lon: Double?
)

data class Weather(
    @SerializedName("id") val id: Int?,
    @SerializedName("main") val main: String?,
    @SerializedName("description") val description: String?,
    @SerializedName("icon") val icon: String?
)

data class Main(
    @SerializedName("temp") val temp: Double?,
    @SerializedName("feels_like") val feelsLike: Double?,
    @SerializedName("temp_min") val tempMin: Double?,
    @SerializedName("temp_max") val tempMax: Double?,
    @SerializedName("pressure") val pressure: Int?,
    @SerializedName("humidity") val humidity: Int?
)

data class Wind(
    @SerializedName("speed") val speed: Double?,
    @SerializedName("deg") val deg: Int?
)

data class Clouds(
    @SerializedName("all") val all: Int?
)

data class Rain(
    @SerializedName("1h") val oneHour: Double?
)

// Gemini APIのリクエスト/レスポンス
data class GeminiRequest(
    @SerializedName("contents") val contents: List<Content>
)

data class Content(
    @SerializedName("parts") val parts: List<Part>
)

data class Part(
    @SerializedName("text") val text: String
)

data class GeminiResponse(
    @SerializedName("candidates") val candidates: List<Candidate>?
)

data class Candidate(
    @SerializedName("content") val content: GeminiContent?
)

data class GeminiContent(
    @SerializedName("parts") val parts: List<GeminiPart>?
)

data class GeminiPart(
    @SerializedName("text") val text: String?
)
