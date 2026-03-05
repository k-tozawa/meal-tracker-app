package ai.fd.thinklet.app.outing.advisor

import android.graphics.Bitmap
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    private val locationRepository: LocationRepository,
    private val weatherRepository: WeatherRepository,
    private val ttsManager: TtsManager,
    private val imageRecognitionRepository: ImageRecognitionRepository
) : ViewModel() {

    companion object {
        private const val TAG = "MainViewModel"
    }

    private val _uiState = MutableStateFlow<UiState>(UiState.Initial)
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val _selectedImage = MutableStateFlow<Bitmap?>(null)
    val selectedImage: StateFlow<Bitmap?> = _selectedImage.asStateFlow()

    /**
     * 画像が選択されたときの処理
     */
    fun onImageSelected(bitmap: Bitmap) {
        _selectedImage.value = bitmap
        Log.d(TAG, "画像が選択されました")
    }

    /**
     * 位置情報と天気を取得（手動トリガー）
     */
    fun fetchLocationAndWeather() {
        viewModelScope.launch {
            _uiState.value = UiState.Loading
            ttsManager.speak("位置情報を取得しています")

            locationRepository.getCurrentLocation()
                .onSuccess { location ->
                    _uiState.value = UiState.LoadingWeather(location)
                    ttsManager.speak("天気情報を取得しています")

                    val shoeBitmap = _selectedImage.value
                    if (shoeBitmap != null) {
                        // 靴の画像がある場合
                        weatherRepository.getWeatherAdviceWithShoeImage(
                            location.latitude,
                            location.longitude,
                            shoeBitmap
                        )
                        .onSuccess { advice ->
                            _uiState.value = UiState.Success(location, advice)
                            ttsManager.speak(advice)
                        }
                        .onFailure { error ->
                            val errorMsg = error.message ?: "天気情報の取得に失敗しました"
                            _uiState.value = UiState.Error(errorMsg)
                            ttsManager.speak(errorMsg)
                        }
                    } else {
                        // 靴の画像がない場合
                        weatherRepository.getWeatherAdvice(
                            location.latitude,
                            location.longitude
                        )
                        .onSuccess { advice ->
                            _uiState.value = UiState.Success(location, advice)
                            ttsManager.speak(advice)
                        }
                        .onFailure { error ->
                            val errorMsg = error.message ?: "天気情報の取得に失敗しました"
                            _uiState.value = UiState.Error(errorMsg)
                            ttsManager.speak(errorMsg)
                        }
                    }
                }
                .onFailure { error ->
                    val errorMsg = error.message ?: "位置情報の取得に失敗しました"
                    _uiState.value = UiState.Error(errorMsg)
                    ttsManager.speak(errorMsg)
                }
        }
    }

    /**
     * 権限が拒否されたときの処理
     */
    fun onPermissionDenied() {
        val errorMsg = "位置情報の権限が必要です"
        _uiState.value = UiState.Error(errorMsg)
        ttsManager.speak(errorMsg)
    }

    override fun onCleared() {
        super.onCleared()
        ttsManager.shutdown()
    }

    sealed class UiState {
        data object Initial : UiState()  // 初期状態
        data object Loading : UiState()
        data class LoadingWeather(val location: LocationData) : UiState()
        data class Success(val location: LocationData, val weatherAdvice: String) : UiState()
        data class Error(val message: String) : UiState()
    }
}