# MealLogger - 音声操作食事記録アプリ

THINKLETのような画面なしAndroid端末で音声操作する食事記録アプリです。

## 機能

- **写真撮影と食事解析**: カメラで食事を撮影し、AIが内容を解析
- **音声での確認と修正**: 解析結果を音声で読み上げ、ユーザーが口頭で修正可能
- **サーバーへのデータ保存**: 端末が変わっても使えるようサーバーにデータを保存
- **献立提案**: 過去の食事データから献立を提案
- **食事傾向とアドバイス**: 食事の傾向を分析してアドバイスを提供

## プロジェクト構造

```
MealLogger/
├── app/
│   ├── src/
│   │   └── main/
│   │       ├── java/com/example/meallogger/
│   │       │   ├── MainActivity.kt              # メインアクティビティ
│   │       │   ├── data/
│   │       │   │   ├── MealRecord.kt           # 食事記録のデータモデル
│   │       │   │   ├── ApiService.kt           # API定義
│   │       │   │   └── ApiClient.kt            # APIクライアント
│   │       │   └── services/
│   │       │       ├── CameraService.kt        # カメラ機能
│   │       │       ├── VoiceService.kt         # 音声認識・合成
│   │       │       └── MealAnalysisService.kt  # 食事解析・データ管理
│   │       ├── res/
│   │       │   ├── layout/
│   │       │   │   └── activity_main.xml       # メイン画面レイアウト
│   │       │   ├── values/
│   │       │   │   ├── strings.xml
│   │       │   │   ├── colors.xml
│   │       │   │   └── themes.xml
│   │       │   └── drawable/
│   │       └── AndroidManifest.xml
│   └── build.gradle.kts
├── build.gradle.kts
├── settings.gradle.kts
└── gradle.properties
```

## ビルド方法

Android StudioまたはJava 11以上がインストールされた環境で:

```bash
./gradlew build
```

APKのインストール:
```bash
./gradlew installDebug
```

## 必要な権限

- `CAMERA`: 食事の写真撮影
- `RECORD_AUDIO`: 音声認識
- `INTERNET`: サーバーとの通信

## API設定

`app/src/main/java/com/example/meallogger/data/ApiClient.kt`の`BASE_URL`を実際のサーバーURLに変更してください。

## サーバー側で必要なエンドポイント

1. `POST /analyze-meal` - 画像から食事内容を解析
2. `POST /save-meal` - 食事記録を保存
3. `GET /meals/{userId}` - ユーザーの食事履歴を取得
4. `POST /suggest-meal` - 献立を提案
5. `POST /get-advice` - 食事傾向とアドバイスを取得

## 使い方

1. アプリ起動後、「写真を撮る」ボタンで食事を撮影
2. AIが解析した内容を音声で確認
3. 間違いがあれば口頭で修正
4. 「献立」または「提案」と話しかけると献立を提案
5. 「アドバイス」または「傾向」と話しかけると食事傾向を分析

## 注意事項

- このアプリを実際に使用するには、バックエンドAPIサーバーの実装が必要です
- 画像解析にはAI APIサービス(例: Google Cloud Vision, Claude Vision, OpenAI GPT-4 Vision等)が必要です
