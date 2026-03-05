package ai.fd.thinklet.app.outing.advisor

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

@Singleton
class LocationRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val prefs = context.getSharedPreferences("location_prefs", Context.MODE_PRIVATE)

    companion object {
        private const val TAG = "LocationRepository"
        private const val KEY_LAST_LATITUDE = "last_latitude"
        private const val KEY_LAST_LONGITUDE = "last_longitude"
        private const val KEY_LAST_TIMESTAMP = "last_timestamp"
    }

    @SuppressLint("MissingPermission")
    suspend fun getCurrentLocation(): Result<LocationData> {
        return try {
            Log.d(TAG, "位置情報取得を開始")

            // GPSまたはネットワークプロバイダーが有効かチェック
            val isGpsEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
            val isNetworkEnabled = locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)

            Log.d(TAG, "GPS有効: $isGpsEnabled, ネットワーク有効: $isNetworkEnabled")

            if (!isGpsEnabled && !isNetworkEnabled) {
                Log.e(TAG, "位置情報サービスが無効")
                return Result.failure(Exception("位置情報サービスが無効です。設定で有効にしてください。"))
            }

            // 最後の既知の位置をチェック（5分以内なら使用）
            val lastKnownLocation = getLastKnownLocation()
            val now = System.currentTimeMillis()
            val fiveMinutesInMillis = 5 * 60 * 1000L

            if (lastKnownLocation != null) {
                val age = now - lastKnownLocation.time
                Log.d(TAG, "最後の既知の位置: ${lastKnownLocation.latitude}, ${lastKnownLocation.longitude}, 経過時間: ${age / 1000}秒")

                // 5分以内の位置情報ならそれを使用
                if (age < fiveMinutesInMillis) {
                    Log.d(TAG, "最後の既知の位置を使用（新しいため）")
                    return Result.success(LocationData(lastKnownLocation.latitude, lastKnownLocation.longitude))
                } else {
                    Log.d(TAG, "最後の既知の位置が古いため、新しい位置をリクエスト")
                }
            } else {
                Log.d(TAG, "最後の既知の位置がないため、新しい位置をリクエスト")
            }

            // 両方のプロバイダーを並行して試行
            val location = withTimeoutOrNull(60000L) {
                requestLocationFromBestProvider(isGpsEnabled, isNetworkEnabled)
            }

            if (location != null) {
                Log.d(TAG, "位置情報取得成功: ${location.latitude}, ${location.longitude}")
                val locationData = LocationData(location.latitude, location.longitude)
                // 取得成功時に保存
                saveLocation(locationData)
                Result.success(locationData)
            } else {
                Log.e(TAG, "位置情報取得タイムアウト、保存された位置情報を確認")
                // 保存された位置情報を使用
                val savedLocation = getSavedLocation()
                if (savedLocation != null) {
                    Log.d(TAG, "保存された位置情報を使用: ${savedLocation.latitude}, ${savedLocation.longitude}")
                    Result.success(savedLocation)
                } else {
                    Log.e(TAG, "保存された位置情報もありません")
                    Result.failure(Exception("位置情報の取得がタイムアウトしました。GPS信号を受信できる場所に移動してください。"))
                }
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "権限エラー、保存された位置情報を確認", e)
            val savedLocation = getSavedLocation()
            if (savedLocation != null) {
                Log.d(TAG, "保存された位置情報を使用: ${savedLocation.latitude}, ${savedLocation.longitude}")
                Result.success(savedLocation)
            } else {
                Result.failure(Exception("位置情報の権限がありません"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "位置情報取得エラー、保存された位置情報を確認", e)
            val savedLocation = getSavedLocation()
            if (savedLocation != null) {
                Log.d(TAG, "保存された位置情報を使用: ${savedLocation.latitude}, ${savedLocation.longitude}")
                Result.success(savedLocation)
            } else {
                Result.failure(Exception("エラー: ${e.message}"))
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun getLastKnownLocation(): Location? {
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        return providers
            .mapNotNull { provider ->
                try {
                    locationManager.getLastKnownLocation(provider)
                } catch (e: Exception) {
                    null
                }
            }
            .maxByOrNull { it.time }
    }

    @SuppressLint("MissingPermission")
    private suspend fun requestLocationFromBestProvider(isGpsEnabled: Boolean, isNetworkEnabled: Boolean): Location? {
        return suspendCancellableCoroutine { continuation ->
            var resumed = false
            val listeners = mutableListOf<LocationListener>()

            fun resumeOnce(location: Location?) {
                if (!resumed) {
                    resumed = true
                    Log.d(TAG, "位置情報を受信、リスナーをクリーンアップ")
                    listeners.forEach {
                        try {
                            locationManager.removeUpdates(it)
                        } catch (e: Exception) {
                            Log.e(TAG, "リスナー削除エラー", e)
                        }
                    }
                    continuation.resume(location)
                }
            }

            // ネットワークプロバイダーを優先（より高速）
            if (isNetworkEnabled) {
                Log.d(TAG, "ネットワークプロバイダーでリクエスト")
                val networkListener = object : LocationListener {
                    override fun onLocationChanged(location: Location) {
                        Log.d(TAG, "ネットワークから位置情報を受信: ${location.latitude}, ${location.longitude}")
                        resumeOnce(location)
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {
                        Log.d(TAG, "ネットワークステータス変更: $provider, $status")
                    }
                    override fun onProviderEnabled(provider: String) {
                        Log.d(TAG, "ネットワークプロバイダー有効: $provider")
                    }
                    override fun onProviderDisabled(provider: String) {
                        Log.d(TAG, "ネットワークプロバイダー無効: $provider")
                    }
                }
                listeners.add(networkListener)
                try {
                    locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 0L, 0f, networkListener)
                } catch (e: Exception) {
                    Log.e(TAG, "ネットワークプロバイダーリクエストエラー", e)
                }
            }

            // GPSプロバイダーも並行して試行
            if (isGpsEnabled) {
                Log.d(TAG, "GPSプロバイダーでリクエスト")
                val gpsListener = object : LocationListener {
                    override fun onLocationChanged(location: Location) {
                        Log.d(TAG, "GPSから位置情報を受信: ${location.latitude}, ${location.longitude}")
                        resumeOnce(location)
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {
                        Log.d(TAG, "GPSステータス変更: $provider, $status")
                    }
                    override fun onProviderEnabled(provider: String) {
                        Log.d(TAG, "GPSプロバイダー有効: $provider")
                    }
                    override fun onProviderDisabled(provider: String) {
                        Log.d(TAG, "GPSプロバイダー無効: $provider")
                    }
                }
                listeners.add(gpsListener)
                try {
                    locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 0L, 0f, gpsListener)
                } catch (e: Exception) {
                    Log.e(TAG, "GPSプロバイダーリクエストエラー", e)
                }
            }

            continuation.invokeOnCancellation {
                Log.d(TAG, "位置情報リクエストがキャンセルされました")
                listeners.forEach {
                    try {
                        locationManager.removeUpdates(it)
                    } catch (e: Exception) {
                        Log.e(TAG, "キャンセル時のリスナー削除エラー", e)
                    }
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun requestSingleUpdate(provider: String): Location? = suspendCancellableCoroutine { continuation ->
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                locationManager.removeUpdates(this)
                continuation.resume(location)
            }

            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {
                locationManager.removeUpdates(this)
                continuation.resume(null)
            }
        }

        locationManager.requestLocationUpdates(provider, 0L, 0f, listener)

        continuation.invokeOnCancellation {
            locationManager.removeUpdates(listener)
        }
    }

    /**
     * 位置情報をSharedPreferencesに保存
     */
    private fun saveLocation(location: LocationData) {
        try {
            prefs.edit().apply {
                putFloat(KEY_LAST_LATITUDE, location.latitude.toFloat())
                putFloat(KEY_LAST_LONGITUDE, location.longitude.toFloat())
                putLong(KEY_LAST_TIMESTAMP, System.currentTimeMillis())
                apply()
            }
            Log.d(TAG, "位置情報を保存しました: ${location.latitude}, ${location.longitude}")
        } catch (e: Exception) {
            Log.e(TAG, "位置情報の保存に失敗", e)
        }
    }

    /**
     * 保存された位置情報を取得
     */
    private fun getSavedLocation(): LocationData? {
        return try {
            val lat = prefs.getFloat(KEY_LAST_LATITUDE, 0f)
            val lon = prefs.getFloat(KEY_LAST_LONGITUDE, 0f)
            val timestamp = prefs.getLong(KEY_LAST_TIMESTAMP, 0L)

            if (lat != 0f && lon != 0f && timestamp != 0L) {
                val ageInHours = (System.currentTimeMillis() - timestamp) / (1000 * 60 * 60)
                Log.d(TAG, "保存された位置情報: lat=$lat, lon=$lon, 経過時間=${ageInHours}時間")
                LocationData(lat.toDouble(), lon.toDouble())
            } else {
                Log.d(TAG, "保存された位置情報がありません")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "位置情報の読み込みに失敗", e)
            null
        }
    }
}