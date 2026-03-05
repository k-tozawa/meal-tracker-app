# APIキーの設定方法

このアプリを動作させるには、以下の2つのAPIキーが必要です。

## 1. OpenWeatherMap APIキー

Yahoo天気APIは公式に提供されていないため、代わりに無料で使えるOpenWeatherMap APIを使用します。

### 取得方法:
1. https://openweathermap.org/ にアクセス
2. 「Sign Up」で無料アカウントを作成
3. ダッシュボードから「API Keys」タブを開く
4. デフォルトのAPIキーをコピー（または新しいキーを生成）

### 無料プランの制限:
- 1分間に60リクエストまで
- 1日1,000リクエストまで

## 2. Gemini APIキー

### 取得方法:
1. https://makersuite.google.com/app/apikey にアクセス
2. Googleアカウントでログイン
3. 「Create API Key」をクリック
4. 生成されたAPIキーをコピー

### 無料プランの制限:
- 1分間に60リクエストまで
- 1日1,500リクエストまで

## APIキーの設定

`WeatherRepository.kt` ファイルの以下の部分を編集してください:

```kotlin
private const val OPENWEATHER_API_KEY = "ここにOpenWeatherMapのAPIキーを貼り付け"
private const val GEMINI_API_KEY = "ここにGemini APIキーを貼り付け"
```

### セキュリティ上の注意:
本番環境では、APIキーをコードに直接書かずに、以下の方法で管理してください:
- `local.properties` に記載し、BuildConfigで読み込む
- 環境変数として設定
- Secrets Gradleプラグインを使用

## テスト方法

1. 上記の手順でAPIキーを設定
2. アプリをビルドしてインストール: `./gradlew assembleDebug && adb install -r app/build/outputs/apk/debug/app-debug.apk`
3. アプリを起動し、「天気をチェック」ボタンをタップ
4. 位置情報の権限を許可
5. 天気情報とGeminiのアドバイスが表示されます
