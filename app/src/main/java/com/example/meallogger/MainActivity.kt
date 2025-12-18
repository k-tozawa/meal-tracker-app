package com.example.meallogger

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.meallogger.databinding.ActivityMainBinding
import com.example.meallogger.services.CameraService
import com.example.meallogger.services.MealAnalysisService
import com.example.meallogger.services.VoiceService
import com.example.meallogger.services.VoskVoiceService
import com.example.meallogger.utils.UserPreferences
import kotlinx.coroutines.launch
import java.util.*

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var cameraService: CameraService
    private lateinit var voiceService: VoiceService
    private lateinit var voskVoiceService: VoskVoiceService
    private lateinit var mealAnalysisService: MealAnalysisService
    private lateinit var userPreferences: UserPreferences
    private var userId: String = ""

    // Physical button confirmation state
    private var pendingAnalyzeResult: com.example.meallogger.data.AnalyzeResponse? = null
    private var pendingCorrectionText: String? = null
    private enum class ConfirmationMode { NONE, ANALYZE_RESULT, CORRECTION }
    private var confirmationMode = ConfirmationMode.NONE

    // Center button long press detection
    private var centerButtonDownTime: Long = 0
    private val LONG_PRESS_THRESHOLD = 500L // 500ms

    private val requiredPermissions = arrayOf(
        Manifest.permission.CAMERA,
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.READ_EXTERNAL_STORAGE
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        userPreferences = UserPreferences(this)
        userId = userPreferences.getUserId() ?: run {
            // ログインされていない場合はログイン画面へ
            finish()
            return
        }

        cameraService = CameraService(this)
        voiceService = VoiceService(this)
        voskVoiceService = VoskVoiceService(this)
        mealAnalysisService = MealAnalysisService(this)

        // Voskモデルをバックグラウンドで事前ロード（ANR防止）
        voskVoiceService.initializeModel { success ->
            if (success) {
                Log.d(TAG, "Vosk model pre-loaded successfully")
            } else {
                Log.e(TAG, "Failed to pre-load Vosk model")
            }
        }

        if (allPermissionsGranted()) {
            startCamera()
            initializeVoiceServices()
        } else {
            ActivityCompat.requestPermissions(
                this, requiredPermissions, REQUEST_CODE_PERMISSIONS
            )
        }

        binding.captureButton.setOnClickListener {
            takePhotoAndAnalyze()
        }

        binding.settingsButton.setOnClickListener {
            val intent = android.content.Intent(this, SettingsActivity::class.java)
            startActivity(intent)
        }

        binding.suggestButton.setOnClickListener {
            suggestMeal()
        }

        // Add voice command listeners for additional features
        voiceService.setGlobalCommandListener { command ->
            when {
                command.contains("献立") || command.contains("提案") -> suggestMeal()
                command.contains("アドバイス") || command.contains("傾向") -> showAdvice()
            }
        }

        // Handle intent actions from THINKLET key config
        handleIntentAction(intent)
    }

    override fun onNewIntent(intent: android.content.Intent?) {
        super.onNewIntent(intent)
        intent?.let { handleIntentAction(it) }
    }

    private fun handleIntentAction(intent: android.content.Intent) {
        when (intent.action) {
            ACTION_ANALYZE -> {
                android.util.Log.d("MainActivity", "ACTION_ANALYZE received from key config")
                takePhotoAndAnalyze()
            }
            ACTION_SUGGEST -> {
                android.util.Log.d("MainActivity", "ACTION_SUGGEST received from key config")
                suggestMeal()
            }
        }
    }

    private fun suggestMeal() {
        // 時間帯から meal_type を判定
        val autoDetectedMealType = getCurrentMealType()
        val mealTypeName = getMealTypeName(autoDetectedMealType)

        // まず食事の種類を確認
        voiceService.speakAndThen("${mealTypeName}の献立ですね。変更される場合は食事の種類を、このままで良ければ「はい」とお答えください。") {
            android.util.Log.d("MainActivity", "Starting WebSocket voice recognition for meal type confirmation")
            voskVoiceService.startListening(
                onResult = { confirmText ->
                    android.util.Log.d("MainActivity", "Meal type confirmation: $confirmText")

                    // ユーザーの応答から meal_type を決定
                    val finalMealType = when {
                        confirmText.contains("朝食") || confirmText.contains("朝ごはん") -> "breakfast"
                        confirmText.contains("昼食") || confirmText.contains("昼ごはん") || confirmText.contains("ランチ") -> "lunch"
                        confirmText.contains("夕食") || confirmText.contains("夜ごはん") || confirmText.contains("晩ごはん") || confirmText.contains("ディナー") -> "dinner"
                        confirmText.contains("おやつ") || confirmText.contains("間食") || confirmText.contains("スナック") -> "snack"
                        confirmText.contains("はい") || confirmText.contains("そのまま") || confirmText.contains("お願い") -> autoDetectedMealType
                        else -> autoDetectedMealType // デフォルトは自動判定のまま
                    }

                    // 次に preferences を聞く
                    askForPreferences(finalMealType)
                },
                onError = { error ->
                    android.util.Log.e("MainActivity", "WebSocket voice recognition error: $error")
                    speak("音声認識でエラーが発生しました")
                    binding.statusText.text = ""
                }
            )
        }
    }

    private fun askForPreferences(mealType: String) {
        voiceService.speakAndThen("何かご希望はありますか？特になければ「お任せ」とお答えください。") {
            android.util.Log.d("MainActivity", "Starting WebSocket voice recognition for preferences")
            voskVoiceService.startListening(
                onResult = { preferencesText ->
                    android.util.Log.d("MainActivity", "Preferences: $preferencesText")

                    // 「お任せ」「特になし」などの場合は preferences を null にする
                    val preferences = if (preferencesText.contains("お任せ") ||
                                         preferencesText.contains("特になし") ||
                                         preferencesText.contains("ない")) {
                        null
                    } else {
                        preferencesText
                    }

                    // 献立を提案
                    fetchMealSuggestion(mealType, preferences)
                },
                onError = { error ->
                    android.util.Log.e("MainActivity", "WebSocket voice recognition error: $error")
                    speak("音声認識でエラーが発生しました")
                    binding.statusText.text = ""
                }
            )
        }
    }

    private fun getMealTypeName(mealType: String): String {
        return when (mealType) {
            "breakfast" -> "朝食"
            "lunch" -> "昼食"
            "dinner" -> "夕食"
            "snack" -> "おやつ"
            else -> "食事"
        }
    }

    private fun getCurrentMealType(): String {
        val calendar = java.util.Calendar.getInstance()
        val hour = calendar.get(java.util.Calendar.HOUR_OF_DAY)

        return when (hour) {
            in 6..9 -> "breakfast"
            in 10..14 -> "lunch"
            in 15..17 -> "snack"
            in 18..22 -> "dinner"
            else -> "snack"
        }
    }

    private fun fetchMealSuggestion(mealType: String, preferences: String?) {
        speak("献立を提案します")
        binding.statusText.text = "献立提案中..."

        lifecycleScope.launch {
            try {
                val suggestion = mealAnalysisService.suggestMeal(userId, mealType, preferences)
                if (suggestion != null) {
                    // 料理名をリスト化
                    val dishNames = suggestion.dishes.joinToString("、") { it.name }

                    // 発話内容を構築
                    val message = buildString {
                        append("おすすめの献立は、${suggestion.meal_name}です。")
                        append("具体的には、${dishNames}です。")
                        append(suggestion.reason)
                    }

                    speak(message)
                    binding.statusText.text = ""
                } else {
                    speak("献立の提案ができませんでした")
                    binding.statusText.text = ""
                }
            } catch (e: Exception) {
                android.util.Log.e("MainActivity", "Meal suggestion error", e)
                speak("エラーが発生しました")
                binding.statusText.text = ""
            }
        }
    }

    private fun showAdvice() {
        speak("食事の傾向を分析します")
        binding.statusText.text = "分析中..."

        lifecycleScope.launch {
            try {
                val advice = mealAnalysisService.getAdvice(userId)
                if (advice != null) {
                    speak(advice.advice)
                    binding.statusText.text = ""
                } else {
                    speak("アドバイスを取得できませんでした")
                    binding.statusText.text = ""
                }
            } catch (e: Exception) {
                speak("エラーが発生しました")
                binding.statusText.text = ""
            }
        }
    }

    private fun startCamera() {
        // 撮影画像の回転設定（画面向きはManifestで固定済み）
        val savedRotation = userPreferences.getCameraRotation()
        cameraService.setDefaultTargetRotation(savedRotation)
        cameraService.startCamera(binding.previewView, this)
    }

    private fun initializeVoiceServices() {
        android.util.Log.d("MainActivity", "initializeVoiceServices called")
        voiceService.initialize { status ->
            android.util.Log.d("MainActivity", "VoiceService init status: $status")
            if (status == 0) {
                speak("食事記録アプリを起動しました。写真を撮るボタンを押してください。")
            } else {
                speak("音声サービスの初期化に失敗しました")
            }
        }
    }


    private fun takePhotoAndAnalyze() {
        speak("写真を撮影します")
        binding.statusText.text = getString(R.string.analyzing)

        lifecycleScope.launch {
            try {
                val imageFile = cameraService.takePhoto()

                // Analyze the meal
                val result = mealAnalysisService.analyzeMeal(imageFile, userId)

                if (result != null) {
                    val itemsText = result.items.joinToString("、") { it.name }
                    binding.statusText.text = itemsText

                    // Speak and then show Yes/No buttons
                    android.util.Log.d("MainActivity", "Speaking result and will show Yes/No buttons")
                    voiceService.speakAndThen("解析結果: ${itemsText}。この内容で正しいですか?") {
                        android.util.Log.d("MainActivity", "Showing Yes/No buttons")
                        showConfirmButtons(result)
                    }
                } else {
                    speak("解析に失敗しました。もう一度お試しください。")
                    binding.statusText.text = ""
                }
            } catch (e: Exception) {
                speak("エラーが発生しました")
                binding.statusText.text = ""
            }
        }
    }

    private fun showConfirmButtons(analyzeResult: com.example.meallogger.data.AnalyzeResponse) {
        pendingAnalyzeResult = analyzeResult
        confirmationMode = ConfirmationMode.ANALYZE_RESULT
        android.util.Log.d(TAG, "Waiting for physical button input (left=Yes, right=No)")
    }

    private fun handleUserConfirmation(confirmed: Boolean, analyzeResult: com.example.meallogger.data.AnalyzeResponse) {
        if (confirmed) {
            android.util.Log.d("MainActivity", "User confirmed")
            lifecycleScope.launch {
                val saved = mealAnalysisService.saveMealRecord(
                    userId = userId,
                    description = analyzeResult.description,
                    items = analyzeResult.items,
                    nutrition = analyzeResult.nutrition,
                    advice = analyzeResult.advice
                )

                if (saved) {
                    android.util.Log.d("MainActivity", "Save successful")
                    val messageBuilder = StringBuilder("食事を記録しました。")

                    if (!analyzeResult.description.isNullOrEmpty()) {
                        messageBuilder.append("内容は、${analyzeResult.description}です。")
                    }

                    analyzeResult.nutrition?.let { nutrition ->
                        messageBuilder.append("栄養情報は、")
                        val nutritionParts = mutableListOf<String>()
                        nutrition.calories?.let { nutritionParts.add("カロリー${it}キロカロリー") }
                        nutrition.protein?.let { nutritionParts.add("タンパク質${it}グラム") }
                        nutrition.carbs?.let { nutritionParts.add("炭水化物${it}グラム") }
                        nutrition.fat?.let { nutritionParts.add("脂質${it}グラム") }
                        if (nutritionParts.isNotEmpty()) {
                            messageBuilder.append(nutritionParts.joinToString("、"))
                            messageBuilder.append("です。")
                        }
                    }

                    if (!analyzeResult.advice.isNullOrEmpty()) {
                        messageBuilder.append(analyzeResult.advice)
                    }

                    speak(messageBuilder.toString())
                } else {
                    android.util.Log.d("MainActivity", "Save failed")
                    speak("記録に失敗しました")
                }
                binding.statusText.text = ""
            }
        } else {
            android.util.Log.d("MainActivity", "User wants to correct")
            askForCorrection(analyzeResult)
        }
    }

    private fun askForCorrection(analyzeResult: com.example.meallogger.data.AnalyzeResponse) {
        // Ensure previous recording is stopped
        voskVoiceService.stopRecording()

        voiceService.speakAndThen("修正内容をどうぞ") {
            android.util.Log.d("MainActivity", "Starting WebSocket voice recognition for correction")
            voskVoiceService.startListening(
                onResult = { correctionText ->
                    android.util.Log.d("MainActivity", "Correction text: $correctionText")
                    confirmCorrection(correctionText, analyzeResult)
                },
                onError = { error ->
                    android.util.Log.e("MainActivity", "WebSocket voice recognition error: $error")
                    speak("音声認識でエラーが発生しました")
                    binding.statusText.text = ""
                }
            )
        }
    }

    private fun confirmCorrection(correctionText: String, analyzeResult: com.example.meallogger.data.AnalyzeResponse) {
        // Ensure previous recording is stopped
        voskVoiceService.stopRecording()

        binding.statusText.text = correctionText

        voiceService.speakAndThen("修正内容は、${correctionText}です。この内容でよろしいですか？") {
            android.util.Log.d("MainActivity", "Showing Yes/No buttons for correction confirmation")
            showCorrectionConfirmButtons(correctionText, analyzeResult)
        }
    }

    private fun showCorrectionConfirmButtons(correctionText: String, analyzeResult: com.example.meallogger.data.AnalyzeResponse) {
        pendingCorrectionText = correctionText
        pendingAnalyzeResult = analyzeResult
        confirmationMode = ConfirmationMode.CORRECTION
        android.util.Log.d(TAG, "Waiting for physical button input for correction (left=Yes, right=No)")
    }

    private fun handleCorrectionConfirmation(confirmed: Boolean, correctionText: String, analyzeResult: com.example.meallogger.data.AnalyzeResponse) {
        if (confirmed) {
            android.util.Log.d("MainActivity", "User confirmed correction")
            lifecycleScope.launch {
                val saved = mealAnalysisService.saveMealRecord(
                    userId = userId,
                    description = correctionText,
                    items = analyzeResult.items,
                    nutrition = analyzeResult.nutrition,
                    advice = analyzeResult.advice
                )

                if (saved) {
                    android.util.Log.d("MainActivity", "Save successful with correction")
                    speak("修正内容で食事を記録しました")
                } else {
                    android.util.Log.d("MainActivity", "Save failed")
                    speak("記録に失敗しました")
                }
                binding.statusText.text = ""
            }
        } else {
            android.util.Log.d("MainActivity", "User wants to correct again")
            askForCorrection(analyzeResult)
        }
    }

    private fun speak(text: String) {
        voiceService.speak(text)
    }

    private fun showSettingsDialog() {
        val layout = android.widget.LinearLayout(this)
        layout.orientation = android.widget.LinearLayout.VERTICAL
        layout.setPadding(50, 40, 50, 10)

        val input = EditText(this)
        input.setText(userId)
        input.hint = "ユーザーID"
        layout.addView(input)

        val debugRecordingCheckbox = android.widget.CheckBox(this)
        debugRecordingCheckbox.text = "デバッグ録音を有効にする"
        debugRecordingCheckbox.isChecked = userPreferences.isDebugRecordingEnabled()
        debugRecordingCheckbox.setPadding(0, 40, 0, 0)
        layout.addView(debugRecordingCheckbox)

        val infoText = android.widget.TextView(this)
        infoText.text = "※ 有効にすると録音データが保存されます"
        infoText.textSize = 12f
        infoText.setTextColor(android.graphics.Color.GRAY)
        infoText.setPadding(0, 10, 0, 0)
        layout.addView(infoText)

        AlertDialog.Builder(this)
            .setTitle("設定")
            .setMessage("現在のユーザーID: $userId")
            .setView(layout)
            .setPositiveButton("保存") { _, _ ->
                val newUserId = input.text.toString().trim()
                if (newUserId.isEmpty()) {
                    Toast.makeText(this, "ユーザーIDを入力してください", Toast.LENGTH_SHORT).show()
                } else if (newUserId.length < 3) {
                    Toast.makeText(this, "ユーザーIDは3文字以上で入力してください", Toast.LENGTH_SHORT).show()
                } else {
                    userPreferences.saveUserId(newUserId)
                    userId = newUserId
                    userPreferences.setDebugRecordingEnabled(debugRecordingCheckbox.isChecked)
                    Toast.makeText(this, "設定を保存しました", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    private fun allPermissionsGranted() = requiredPermissions.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                startCamera()
                initializeVoiceServices()
            } else {
                Toast.makeText(
                    this,
                    getString(R.string.permission_required),
                    Toast.LENGTH_SHORT
                ).show()
                finish()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // 設定が変更された可能性があるため、カメラを再起動
        if (allPermissionsGranted()) {
            val savedRotation = userPreferences.getCameraRotation()
            cameraService.setDefaultTargetRotation(savedRotation)
            cameraService.startCamera(binding.previewView, this)
        }
    }

    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent?): Boolean {
        android.util.Log.d(TAG, "onKeyDown: keyCode=$keyCode, confirmationMode=$confirmationMode")

        // Center button press for photo/suggest
        if (keyCode == android.view.KeyEvent.KEYCODE_CAMERA || // THINKLET center button
            keyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER ||
            keyCode == android.view.KeyEvent.KEYCODE_ENTER) {
            if (confirmationMode == ConfirmationMode.NONE) {
                centerButtonDownTime = System.currentTimeMillis()
                android.util.Log.d(TAG, "Center button down at $centerButtonDownTime")
                return true
            }
        }

        when (confirmationMode) {
            ConfirmationMode.ANALYZE_RESULT -> {
                when (keyCode) {
                    android.view.KeyEvent.KEYCODE_DPAD_LEFT, // THINKLETの左ボタン
                    android.view.KeyEvent.KEYCODE_VOLUME_UP -> { // フォールバック
                        android.util.Log.d(TAG, "Yes button (left) pressed")
                        pendingAnalyzeResult?.let {
                            confirmationMode = ConfirmationMode.NONE
                            handleUserConfirmation(true, it)
                            pendingAnalyzeResult = null
                        }
                        return true
                    }
                    android.view.KeyEvent.KEYCODE_DPAD_RIGHT, // THINKLETの右ボタン
                    android.view.KeyEvent.KEYCODE_VOLUME_DOWN -> { // フォールバック
                        android.util.Log.d(TAG, "No button (right) pressed")
                        pendingAnalyzeResult?.let {
                            confirmationMode = ConfirmationMode.NONE
                            handleUserConfirmation(false, it)
                            pendingAnalyzeResult = null
                        }
                        return true
                    }
                }
            }
            ConfirmationMode.CORRECTION -> {
                when (keyCode) {
                    android.view.KeyEvent.KEYCODE_DPAD_LEFT,
                    android.view.KeyEvent.KEYCODE_VOLUME_UP -> {
                        android.util.Log.d(TAG, "Yes button (left) pressed for correction")
                        val correctionText = pendingCorrectionText
                        val result = pendingAnalyzeResult
                        if (correctionText != null && result != null) {
                            confirmationMode = ConfirmationMode.NONE
                            handleCorrectionConfirmation(true, correctionText, result)
                            pendingCorrectionText = null
                            pendingAnalyzeResult = null
                        }
                        return true
                    }
                    android.view.KeyEvent.KEYCODE_DPAD_RIGHT,
                    android.view.KeyEvent.KEYCODE_VOLUME_DOWN -> {
                        android.util.Log.d(TAG, "No button (right) pressed for correction")
                        val result = pendingAnalyzeResult
                        if (result != null) {
                            confirmationMode = ConfirmationMode.NONE
                            handleCorrectionConfirmation(false, "", result)
                            pendingCorrectionText = null
                            pendingAnalyzeResult = null
                        }
                        return true
                    }
                }
            }
            ConfirmationMode.NONE -> {
                // Not in confirmation mode, let default handling occur
            }
        }

        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: android.view.KeyEvent?): Boolean {
        android.util.Log.d(TAG, "onKeyUp: keyCode=$keyCode, confirmationMode=$confirmationMode")

        // Center button release for photo/suggest
        if (keyCode == android.view.KeyEvent.KEYCODE_CAMERA || // THINKLET center button
            keyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER ||
            keyCode == android.view.KeyEvent.KEYCODE_ENTER) {
            if (confirmationMode == ConfirmationMode.NONE && centerButtonDownTime > 0) {
                val pressDuration = System.currentTimeMillis() - centerButtonDownTime
                android.util.Log.d(TAG, "Center button released after ${pressDuration}ms")

                centerButtonDownTime = 0

                if (pressDuration >= LONG_PRESS_THRESHOLD) {
                    // Long press - suggest meal
                    android.util.Log.d(TAG, "Long press detected - suggesting meal")
                    suggestMeal()
                } else {
                    // Short press - take photo and analyze
                    android.util.Log.d(TAG, "Short press detected - taking photo")
                    takePhotoAndAnalyze()
                }
                return true
            }
        }

        return super.onKeyUp(keyCode, event)
    }

    override fun onDestroy() {
        super.onDestroy()
        voiceService.shutdown()
        voskVoiceService.shutdown()
    }

    companion object {
        private const val TAG = "MainActivity"
        private const val REQUEST_CODE_PERMISSIONS = 10
        const val ACTION_ANALYZE = "com.example.meallogger.ACTION_ANALYZE"
        const val ACTION_SUGGEST = "com.example.meallogger.ACTION_SUGGEST"
    }
}
