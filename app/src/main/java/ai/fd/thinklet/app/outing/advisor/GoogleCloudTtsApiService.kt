package ai.fd.thinklet.app.outing.advisor

import retrofit2.http.Body
import retrofit2.http.POST
import retrofit2.http.Query

interface GoogleCloudTtsApiService {
    @POST("v1/text:synthesize")
    suspend fun synthesize(
        @Query("key") apiKey: String,
        @Body request: TtsSynthesizeRequest
    ): TtsSynthesizeResponse
}

data class TtsSynthesizeRequest(
    val input: TtsInput,
    val voice: TtsVoice,
    val audioConfig: TtsAudioConfig
)

data class TtsInput(val text: String)
data class TtsVoice(val languageCode: String, val name: String)
data class TtsAudioConfig(val audioEncoding: String)
data class TtsSynthesizeResponse(val audioContent: String)
