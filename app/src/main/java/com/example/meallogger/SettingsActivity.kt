package com.example.meallogger

import android.os.Bundle
import android.view.Surface
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.meallogger.databinding.ActivitySettingsBinding
import com.example.meallogger.utils.UserPreferences

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var userPreferences: UserPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        userPreferences = UserPreferences(this)

        // 現在の設定を読み込んで表示
        loadSettings()

        // 保存ボタン
        binding.saveButton.setOnClickListener {
            saveSettings()
        }
    }

    private fun loadSettings() {
        // カメラ回転設定を読み込み
        val currentRotation = userPreferences.getCameraRotation()
        when (currentRotation) {
            Surface.ROTATION_0 -> binding.rotation0.isChecked = true
            Surface.ROTATION_90 -> binding.rotation90.isChecked = true
            Surface.ROTATION_180 -> binding.rotation180.isChecked = true
            Surface.ROTATION_270 -> binding.rotation270.isChecked = true
        }

        // デバッグ録音設定を読み込み
        binding.debugRecordingSwitch.isChecked = userPreferences.isDebugRecordingEnabled()

        // 音声音量設定を読み込み
        val volume = userPreferences.getVoiceVolume()
        val volumePercent = (volume * 100).toInt()
        binding.volumeSeekBar.progress = volumePercent
        binding.volumeValueText.text = "${volumePercent}%"

        // 音量スライダーの変更リスナー
        binding.volumeSeekBar.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                binding.volumeValueText.text = "${progress}%"
            }

            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {}
        })

        // サーバーURL設定を読み込み
        binding.serverUrlEditText.setText(userPreferences.getServerUrl())
    }

    private fun saveSettings() {
        // カメラ回転設定を保存
        val selectedRotation = when (binding.rotationRadioGroup.checkedRadioButtonId) {
            R.id.rotation0 -> Surface.ROTATION_0
            R.id.rotation90 -> Surface.ROTATION_90
            R.id.rotation180 -> Surface.ROTATION_180
            R.id.rotation270 -> Surface.ROTATION_270
            else -> Surface.ROTATION_0
        }
        userPreferences.setCameraRotation(selectedRotation)

        // デバッグ録音設定を保存
        userPreferences.setDebugRecordingEnabled(binding.debugRecordingSwitch.isChecked)

        // 音声音量設定を保存（0.0〜1.0の範囲に変換）
        val volumePercent = binding.volumeSeekBar.progress
        val volume = volumePercent / 100f
        userPreferences.setVoiceVolume(volume)

        // サーバーURL設定を保存
        val serverUrl = binding.serverUrlEditText.text.toString().trim()
        if (serverUrl.isNotEmpty()) {
            userPreferences.setServerUrl(serverUrl)
        }

        Toast.makeText(this, "設定を保存しました", Toast.LENGTH_SHORT).show()
        finish()
    }
}
