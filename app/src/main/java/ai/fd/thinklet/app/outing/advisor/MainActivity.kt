package ai.fd.thinklet.app.outing.advisor

import android.Manifest
import android.content.pm.PackageManager
import android.content.Intent
import android.graphics.BitmapFactory
import android.util.Log
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import dagger.hilt.android.AndroidEntryPoint
import androidx.core.content.ContextCompat.startForegroundService

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val TAG = "MainActivity"

    private val viewModel: MainViewModel by viewModels()

    private val startupPermissionRequest = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val audioGranted = permissions[Manifest.permission.RECORD_AUDIO] == true ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (audioGranted) startWakeWordService()
        else Log.w(TAG, "マイクパーミッションが拒否されました")
        // マイク/ストレージの次にGPS権限をリクエスト
        requestLocationPermissionIfNeeded()
    }

    private var pendingWeatherFetch = false

    private val locationPermissionRequest = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        when {
            permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true -> {
                if (pendingWeatherFetch) {
                    pendingWeatherFetch = false
                    viewModel.fetchLocationAndWeather()
                }
            }
            else -> {
                if (pendingWeatherFetch) {
                    pendingWeatherFetch = false
                    viewModel.onPermissionDenied()
                }
            }
        }
    }

    private val imagePickerLauncher = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        uri?.let {
            try {
                val inputStream = contentResolver.openInputStream(it)
                val bitmap = BitmapFactory.decodeStream(inputStream)
                inputStream?.close()
                viewModel.onImageSelected(bitmap)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "onCreate called.")
        startWakeWordServiceIfPermitted()
        // GPS権限はstartupPermissionRequestのコールバック後にリクエストする（同時launch不可）
        handleIntent(intent) // 起動時のインテントを処理

        setContent {
            AppTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    LocationScreen(
                        viewModel = viewModel,
                        onRequestPermission = ::requestWeatherCheck,
                        onImagePickerClick = {
                            imagePickerLauncher.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        }
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        Log.d(TAG, "onNewIntent called.")
        handleIntent(intent) // アプリが既に起動している場合のインテントを処理
    }
    
    private fun handleIntent(intent: Intent?) {
        Log.d(TAG, "========================================")
        Log.d(TAG, "handleIntent called")
        Log.d(TAG, "  Action: ${intent?.action}")
        Log.d(TAG, "  Extra 'action': ${intent?.getStringExtra("action")}")

        // 物理ボタンから起動された場合、IntentにはFLAG_ACTIVITY_LAUNCHED_FROM_HISTORYが含まれない。
        // これを利用して、ランチャーからの通常の起動と区別する。
        val isLaunchedFromHistory = intent?.flags?.and(Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) != 0
        Log.d(TAG, "  isLaunchedFromHistory: $isLaunchedFromHistory")

        if (intent?.action == WakeWordServiceNew.ACTION_WAKE_WORD_DETECTED) {
            Log.d(TAG, ">>> Wake Word Detected! <<<")
            Log.d(TAG, "  Starting weather check...")
            Log.d(TAG, "========================================")
            requestWeatherCheck()
        } else if (intent?.action == Intent.ACTION_MAIN && !isLaunchedFromHistory) {
            Log.d(TAG, "Launched from physical button")
            Log.d(TAG, "  Waiting for wake word...")
            Log.d(TAG, "========================================")
            // ボタンはアプリを開くだけ。天気取得は「てんきおしえて」で行う
        } else {
            Log.d(TAG, "Normal launch")
            Log.d(TAG, "========================================")
        }
    }

    private fun requestLocationPermissionIfNeeded() {
        if (!hasLocationPermission()) {
            locationPermissionRequest.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }

    private fun startWakeWordServiceIfPermitted() {
        val permissions = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.RECORD_AUDIO)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
            != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

        if (permissions.isEmpty()) {
            startWakeWordService()
            requestLocationPermissionIfNeeded()
        } else {
            startupPermissionRequest.launch(permissions.toTypedArray())
        }
    }

    private fun startWakeWordService() {
        Log.d(TAG, "========================================")
        Log.d(TAG, "Starting WakeWordServiceNew...")
        Log.d(TAG, "  (VAD Integrated)")
        Log.d(TAG, "========================================")
        val intent = Intent(this, WakeWordServiceNew::class.java)
        startForegroundService(this, intent)
    }
    
    private fun requestWeatherCheck() {
        if (hasLocationPermission()) {
            viewModel.fetchLocationAndWeather()
        } else {
            pendingWeatherFetch = true
            locationPermissionRequest.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }
}

@Composable
fun LocationScreen(
    viewModel: MainViewModel,
    onRequestPermission: () -> Unit,
    onImagePickerClick: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val selectedImage by viewModel.selectedImage.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        when (val state = uiState) {
            is MainViewModel.UiState.Initial -> {
                Text("お出かけサポーター",
                    style = MaterialTheme.typography.headlineLarge)
                Spacer(modifier = Modifier.height(32.dp))

                // 画像選択ボタン
                Button(
                    onClick = onImagePickerClick,
                    modifier = Modifier.fillMaxWidth(0.8f)
                ) {
                    Text(if (selectedImage != null) "靴の写真を変更" else "靴の写真を選択（オプション）")
                }

                if (selectedImage != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("✓ 写真が選択されました", style = MaterialTheme.typography.bodySmall)
                }

                Spacer(modifier = Modifier.height(24.dp))
                Button(
                    onClick = onRequestPermission,
                    modifier = Modifier.size(200.dp)
                ) {
                    Text("天気をチェック",
                        style = MaterialTheme.typography.headlineSmall)
                }
                Spacer(modifier = Modifier.height(16.dp))
                Text("ボタンを押すと音声で案内します",
                    style = MaterialTheme.typography.bodyMedium)
            }

            is MainViewModel.UiState.Loading -> {
                CircularProgressIndicator(modifier = Modifier.size(100.dp))
                Spacer(modifier = Modifier.height(32.dp))
                Text("🌍", style = MaterialTheme.typography.displayLarge)
            }

            is MainViewModel.UiState.LoadingWeather -> {
                CircularProgressIndicator(modifier = Modifier.size(100.dp))
                Spacer(modifier = Modifier.height(32.dp))
                Text("☁️", style = MaterialTheme.typography.displayLarge)
            }

            is MainViewModel.UiState.Success -> {
                Text("✅", style = MaterialTheme.typography.displayLarge)
                Spacer(modifier = Modifier.height(32.dp))
                Text("音声案内を確認してください",
                    style = MaterialTheme.typography.headlineSmall)
                Spacer(modifier = Modifier.height(32.dp))
                Button(
                    onClick = onRequestPermission,
                    modifier = Modifier.size(150.dp)
                ) {
                    Text("再取得",
                        style = MaterialTheme.typography.headlineSmall)
                }
            }

            is MainViewModel.UiState.Error -> {
                Text("❌", style = MaterialTheme.typography.displayLarge)
                Spacer(modifier = Modifier.height(32.dp))
                Text("エラーが発生しました",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.error
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(state.message,
                    style = MaterialTheme.typography.bodyMedium)
                Spacer(modifier = Modifier.height(32.dp))
                Button(
                    onClick = onRequestPermission,
                    modifier = Modifier.size(150.dp)
                ) {
                    Text("再試行",
                        style = MaterialTheme.typography.headlineSmall)
                }
            }
        }
    }
}

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(),
        content = content
    )
}
