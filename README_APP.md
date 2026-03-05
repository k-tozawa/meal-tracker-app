# MealLogger - 音声操作食事記録アプリ

THINKLETのような画面なしAndroid端末で音声操作する食事記録アプリです。

## 主な機能

- **写真撮影と食事解析**: カメラで食事を撮影し、サーバー側AIが内容を解析
- **音声での確認と修正**: 解析結果を音声で読み上げ、ユーザーが口頭で修正可能
- **THINKLET物理ボタン対応**: 中央ボタン（短押し=撮影/長押し=献立提案）、左右ボタン（Yes/No）
- **オフライン音声認識**: Vosk + THINKLET XFE（VAD、ビームフォーミング、エコーキャンセル）
- **サーバーへのデータ保存**: 端末が変わっても使えるようサーバーにデータを保存
- **献立提案**: 過去の食事データから時間帯に応じた献立を自動提案
- **食事傾向とアドバイス**: 食事の傾向を分析してパーソナライズされたアドバイスを提供

## プロジェクト構造

```
MealLogger/
├── app/
│   ├── src/
│   │   └── main/
│   │       ├── java/com/example/meallogger/
│   │       │   ├── MainActivity.kt              # メインアクティビティ（物理ボタン対応）
│   │       │   ├── LoginActivity.kt             # ログイン画面
│   │       │   ├── SettingsActivity.kt          # 設定画面
│   │       │   ├── data/
│   │       │   │   ├── MealRecord.kt           # 食事記録のデータモデル
│   │       │   │   ├── ApiService.kt           # API定義
│   │       │   │   └── ApiClient.kt            # APIクライアント
│   │       │   ├── services/
│   │       │   │   ├── CameraService.kt        # カメラ機能（CameraX）
│   │       │   │   ├── VoiceService.kt         # TTS音声合成
│   │       │   │   ├── VoskVoiceService.kt     # Vosk + XFE音声認識（オフライン）
│   │       │   │   ├── WebSocketVoiceService.kt # WebSocket音声認識（サーバー）
│   │       │   │   └── MealAnalysisService.kt  # 食事解析・データ管理
│   │       │   ├── audio/
│   │       │   │   ├── BeamformingProcessor.kt # ビームフォーミング処理
│   │       │   │   └── AudioResampler.kt       # オーディオリサンプリング
│   │       │   └── utils/
│   │       │       ├── UserPreferences.kt      # 設定管理
│   │       │       └── EmptyAudioPlayer.kt     # エコーキャンセル用
│   │       ├── res/
│   │       │   ├── layout/
│   │       │   │   ├── activity_main.xml       # メイン画面レイアウト
│   │       │   │   ├── activity_login.xml      # ログイン画面
│   │       │   │   └── activity_settings.xml   # 設定画面
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
- `READ_EXTERNAL_STORAGE`: XFEライセンスとVoskモデルの読み込み

## セットアップ

### 1. THINKLET準備
1. XFEライセンスファイルを配置:
   - `/sdcard/thinklet/xfe-license.dat`
2. Voskモデルを配置:
   - `/sdcard/thinklet/vosk-model-ja-0.22/` または
   - `/sdcard/thinklet/vosk-model-small-ja-0.22/`
   - ダウンロード: https://alphacephei.com/vosk/models

### 2. アプリ設定
1. アプリ起動時にログインID入力（3文字以上）
2. 設定画面（歯車アイコン）でサーバーURL設定
   - 例: `http://192.168.1.100:8000`
3. カメラ回転調整（必要に応じて）
   - 0°/90°/180°/270°から選択

### 3. サーバー設定
`app/src/main/java/com/example/meallogger/data/ApiClient.kt`でデフォルトサーバーURLを変更可能。

## サーバー側で必要なエンドポイント

1. `POST /analyze-meal` - 画像から食事内容を解析
   - リクエスト: `multipart/form-data` (file, userId)
   - レスポンス: `{ description, items[], nutrition{}, advice }`

2. `POST /save-meal` - 食事記録を保存
   - リクエスト: `{ userId, description, items[], nutrition{}, advice }`
   - レスポンス: `{ status, meal_id }`

3. `GET /meals/{userId}` - ユーザーの食事履歴を取得
   - レスポンス: `[{ mealId, timestamp, description, ... }]`

4. `GET /suggest-meal?user_id={userId}&meal_type={type}&preferences={text}` - 献立を提案
   - レスポンス: `{ meal_name, dishes[], reason }`

5. `POST /get-advice` - 食事傾向とアドバイスを取得
   - リクエスト: `{ userId, mealHistory[] }`
   - レスポンス: `{ advice }`

6. `ws://server/ws/speech` - 音声認識WebSocket（オプション、WebSocketVoiceService使用時）
   - 送信: PCM 16-bit 16kHz モノラル
   - 受信: `{ "partial": "..." }` または `{ "final": "..." }`

詳細は [SERVER_WEBSOCKET_API.md](SERVER_WEBSOCKET_API.md) を参照。

## 使い方

### 物理ボタン操作（THINKLET）
- **中央ボタン短押し**: 写真撮影→食事解析
- **中央ボタン長押し（1.5秒）**: 献立提案
- **左ボタン（音量+）短押し**: Yes（確認時）
- **右ボタン（音量-）短押し**: No（確認時）
- **左/右ボタン長押し（1.5秒）**: 献立提案

### 画面操作
1. アプリ起動後、「写真を撮る」ボタンで食事を撮影
2. AIが解析した内容を音声で確認
3. 間違いがあれば口頭で修正
4. 「献立提案」ボタンで献立を提案
5. 設定ボタンで各種設定変更

## トラブルシューティング

### 音声認識が動かない
1. Voskモデルが正しく配置されているか確認（`/sdcard/thinklet/vosk-model-ja-0.22/`）
2. XFEライセンスファイルが有効か確認（`/sdcard/thinklet/xfe-license.dat`）
3. マイク権限が許可されているか確認
4. Logcatで `VoskVoiceService` のエラーを確認

### カメラ画像が回転している
1. 設定画面で「カメラ回転設定」を調整
2. 0°/90°/180°/270°から選択して保存

### サーバーに接続できない
1. 設定画面でサーバーURLが正しいか確認（例: `http://192.168.1.100:8000`）
2. ネットワーク接続を確認
3. サーバー側のエンドポイントが稼働しているか確認
4. Logcatで `MealAnalysisService` や `ApiClient` のエラーを確認

### 物理ボタンが反応しない
1. THINKLETのキー設定ファイル（`/sdcard/thinklet/key_config.json`）を確認
2. アプリのパッケージ名が `com.example.meallogger` であることを確認
3. Logcatで `MainActivity` の `onKeyDown`/`onKeyUp` ログを確認

## 注意事項

- このアプリを実際に使用するには、バックエンドAPIサーバーの実装が必要です
- 画像解析にはAI APIサービス(例: Claude Vision, OpenAI GPT-4 Vision等)が必要です
- THINKLET XFE SDKは別途ライセンスが必要です
- Voskモデルは事前にダウンロードして配置する必要があります

## 関連ドキュメント

- [README.md](README.md) - プロジェクト全体の詳細ドキュメント
- [SERVER_WEBSOCKET_API.md](SERVER_WEBSOCKET_API.md) - サーバー側API仕様
- [THINKLET_SETUP.md](THINKLET_SETUP.md) - THINKLET固有のセットアップ手順
