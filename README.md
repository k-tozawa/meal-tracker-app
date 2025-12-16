# Meal Tracker Android App

音声ベースの食事記録・栄養管理アプリ（THINKLETなど画面なし端末対応）

## アーキテクチャ

```
┌─────────────────────────────────────────┐
│          VoiceInterface                 │  ← ユーザー対話
│  (SpeechRecognizer + TTS)              │
└──────────────┬──────────────────────────┘
               │
┌──────────────▼──────────────────────────┐
│        MealTrackingService              │  ← メインロジック
│  - 音声コマンド処理                       │
│  - カメラトリガー                         │
│  - 解析フロー管理                         │
└──────┬──────────┬──────────┬────────────┘
       │          │          │
   ┌───▼───┐  ┌──▼──┐  ┌───▼────┐
   │Camera │  │Local│  │Server  │
   │Manager│  │ ML  │  │API     │
   └───────┘  └─────┘  └────────┘
       │          │          │
       └──────────▼──────────┘
              ┌────▼─────┐
              │ Database │  ← Room
              │ (Local)  │
              └──────────┘
```

## コア機能

### 1. VoiceInterface
- **音声認識**: ユーザーのコマンドを認識
- **音声合成**: エージェントの応答を読み上げ
- **コマンド**:
  - 「ごはんを記録して」→ カメラ撮影
  - 「今日の食事は？」→ 履歴表示
  - 「アドバイスちょうだい」→ 栄養アドバイス

### 2. CameraManager (Camera2 API)
- 音声コマンドで自動撮影
- カウントダウン付き（「3、2、1」）
- 低解像度で高速処理

### 3. HybridAnalyzer
```kotlin
// オンデバイスML（1-2秒）→ サーバー解析（5-10秒）
sealed class AnalysisResult {
    data class Quick(val category: String) : AnalysisResult()
    data class Detailed(
        val description: String,
        val items: List<FoodItem>,
        val nutrition: Nutrition
    ) : AnalysisResult()
}
```

### 4. OfflineQueue
- ネットワーク不可時に撮影データを保存
- WorkManagerで自動同期

## 主要クラス

### VoiceAssistant.kt
```kotlin
class VoiceAssistant(context: Context) {
    private val tts: TextToSpeech
    private val speechRecognizer: SpeechRecognizer

    fun speak(text: String)
    fun listen(callback: (String) -> Unit)
    fun stopListening()
}
```

### CameraManager.kt
```kotlin
class CameraManager(context: Context) {
    fun takePhoto(countdown: Boolean = true): File
    fun release()
}
```

### MealAnalyzer.kt
```kotlin
class MealAnalyzer(
    private val mlKit: MLKitDetector,
    private val serverAPI: ServerAPI,
    private val database: MealDatabase
) {
    suspend fun analyze(photo: File, userId: String): Flow<AnalysisResult>
}
```

### ServerAPI.kt
```kotlin
interface ServerAPI {
    suspend fun analyzeImage(photo: File, userId: String): MealAnalysisResult
    suspend fun updateMeal(mealId: String, corrections: Map<String, Any>)
    suspend fun getMealHistory(userId: String, limit: Int): List<MealRecord>
    suspend fun getAdvice(userId: String, context: String?): String
}
```

## データモデル

### MealEntity (Room)
```kotlin
@Entity(tableName = "meals")
data class MealEntity(
    @PrimaryKey val mealId: String,
    val userId: String,
    val timestamp: Long,
    val description: String,
    val itemsJson: String,
    val nutritionJson: String,
    val photoPath: String,
    val syncStatus: SyncStatus // PENDING, SYNCED, FAILED
)
```

## 会話フロー例

```
[アプリ起動]
App: 「こんにちは。食事を記録しますか？」

User: 「はい」
App: 「写真を撮ります。3、2、1」
[カメラで撮影]
App: 「解析中です...」

[2秒後 - オンデバイスML結果]
App: 「料理が写っています。詳しく確認中...」

[7秒後 - サーバー解析完了]
App: 「焼き魚定食ですね。サバの塩焼き、ご飯150グラム、味噌汁、サラダです。これで記録しますか？」

User: 「サバじゃなくて鮭です」
App: 「鮭の塩焼きに修正しました。記録完了です。今日は魚料理が多いですね。夕食は野菜中心にしましょうか？」

User: 「おすすめの献立は？」
App: 「今日は魚料理でタンパク質は十分です。野菜炒めやサラダで食物繊維を摂りましょう。ビタミンCが豊富なブロッコリーやパプリカがおすすめです」
```

## セットアップ

### 必要な権限 (AndroidManifest.xml)
```xml
<uses-permission android:name="android.permission.CAMERA" />
<uses-permission android:name="android.permission.RECORD_AUDIO" />
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.WRITE_EXTERNAL_STORAGE" />
```

### 依存関係 (build.gradle.kts)
```kotlin
// CameraX
implementation("androidx.camera:camera-camera2:1.3.1")
implementation("androidx.camera:camera-lifecycle:1.3.1")

// ML Kit
implementation("com.google.mlkit:image-labeling:17.0.8")

// Room
implementation("androidx.room:room-runtime:2.6.1")
kapt("androidx.room:room-compiler:2.6.1")

// Retrofit
implementation("com.squareup.retrofit2:retrofit:2.9.0")
implementation("com.squareup.retrofit2:converter-gson:2.9.0")

// WorkManager
implementation("androidx.work:work-runtime-ktx:2.9.0")
```

## 次のステップ

1. VoiceAssistantの実装
2. CameraManagerの実装
3. ML Kitの統合
4. サーバーAPI接続
5. オフライン機能の実装
6. UIの追加（設定画面など）

## 今後の拡張

- Apple Watchなどウェアラブル対応
- 音声での献立提案
- 家族での食事シェア機能
- 栄養目標の設定とトラッキング
