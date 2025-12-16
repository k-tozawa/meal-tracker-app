package com.example.meallogger

import android.content.Intent
import android.os.Bundle
import android.view.inputmethod.EditorInfo
import androidx.appcompat.app.AppCompatActivity
import com.example.meallogger.databinding.ActivityLoginBinding
import com.example.meallogger.utils.UserPreferences

class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding
    private lateinit var userPreferences: UserPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        userPreferences = UserPreferences(this)

        // すでにログイン済みの場合はメイン画面へ
        if (userPreferences.isLoggedIn()) {
            navigateToMain()
            return
        }

        setupListeners()
    }

    private fun setupListeners() {
        binding.loginButton.setOnClickListener {
            login()
        }

        binding.userIdInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                login()
                true
            } else {
                false
            }
        }
    }

    private fun login() {
        val userId = binding.userIdInput.text.toString().trim()

        // バリデーション
        if (userId.isEmpty()) {
            showError("ログインIDを入力してください")
            return
        }

        if (userId.length < 3) {
            showError("ログインIDは3文字以上で入力してください")
            return
        }

        // ユーザーIDを保存
        userPreferences.saveUserId(userId)

        // メイン画面へ遷移
        navigateToMain()
    }

    private fun showError(message: String) {
        binding.errorText.text = message
        binding.errorText.visibility = android.view.View.VISIBLE
    }

    private fun navigateToMain() {
        val intent = Intent(this, MainActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        finish()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // 初期設定画面では戻るボタンを無効化
        // ユーザーIDの設定を必須とする
        // 何もしない
    }
}
