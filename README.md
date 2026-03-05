# MealLogger - THINKLETハンズフリー食事記録アプリ

THINKLETで使える音声操作の食事記録アプリです。物理ボタンと音声だけで写真撮影→AI解析→記録→献立提案までを完結できます。

## 特徴

- **完全ハンズフリー操作**: THINKLETの物理ボタンと音声で全操作が可能
- **高精度音声認識**: Vosk（オフライン）またはWebSocket経由サーバー音声認識
- **THINKLET XFE対応**: VAD（音声区間検出）、ビームフォーミング、エコーキャンセル
- **AI画像解析**: サーバー側でClaude等のVision APIを使用した食事内容解析
- **献立提案**: 過去の食事データから時間帯に応じた献立を自動提案
- **栄養アドバイス**: 食事傾向を分析してアドバイスを提供

## アーキテクチャ

```
┌─────────────────────────────────────────────────┐
│  THINKLET Physical Buttons & Voice Interface   │
│  - Center: 短押し=撮影 / 長押し=献立提案           │
│  - Left/Right: Yes/No確認                       │
│  - TTS: 音声フィードバック                        │
└──────────────┬──────────────────────────────────┘
               │
┌──────────────▼──────────────────────────────────┐
│              MainActivity                       │
│  - 物理ボタンイベント処理                          │
│  - 音声認識フロー制御                             │
│  - 確認モード管理                                 │
└──┬───────┬──────────┬──────────┬────────────────┘
   │       │          │          │
   │   ┌───▼───┐  ┌──▼──────┐  ┌▼────────────┐
   │   │Camera │  │VoskVoice│  │MealAnalysis │
   │   │Service│  │Service  │  │Service      │
   │   └───────┘  └──┬──────┘  └─┬───────────┘
   │                 │            │
   │           ┌─────▼────┐  ┌────▼────────┐
   │           │THINKLET  │  │Server API   │
   │           │XFE VAD   │  │- 画像解析    │
   │           │+ Vosk    │  │- 献立提案    │
   │           └──────────┘  │- アドバイス  │
   │                         └─────────────┘
   │
   └─► VoiceService (TTS)
```

## 実装済み機能

### 1. 音声認識（2方式対応）

#### VoskVoiceService（オフライン、デフォルト）
- Vosk音声認識エンジンでオフライン日本語認識
- THINKLET XFE統合: 6チャンネル48kHz録音、VAD、ビームフォーミング、エコーキャンセル
- 常時動作: アプリ起動中は録音・VAD・AECを常時稼働してレスポンス高速化
- TTS残響対策: 最初のサイクルをスキップして誤認識防止

#### WebSocketVoiceService（サーバー音声認識）
- WebSocketでリアルタイム音声認識
- サーバー側でGoogle Speech API等の高精度認識
- VAD連動で音声検出時のみ接続

### 2. THINKLET物理ボタン対応
- **中央ボタン**: 短押し（< 1.5秒）= 写真撮影 / 長押し（≥ 1.5秒）= 献立提案
- **左ボタン（音量+）**: 短押し = Yes（確認時） / 長押し = 献立提案
- **右ボタン（音量-）**: 短押し = No（確認時） / 長押し = 献立提案
- キー設定インテント: THINKLET key_config.json対応

### 3. カメラ撮影
- CameraX APIを使用
- 設定画面で撮影画像の回転角度を調整可能（0°/90°/180°/270°）
- 物理ボタン操作: 中央ボタン短押しで撮影

### 4. 食事解析・記録
- サーバーAPIと連携してVision APIで食事内容を自動認識
- 音声修正対応: 解析結果を音声で修正可能
- 栄養情報: カロリー、タンパク質、炭水化物、脂質

### 5. 献立提案
- 時間帯自動判定: 現在時刻から朝食/昼食/夕食/おやつを自動判定
- 過去データ活用: ユーザーの食事履歴から最適な献立を提案
- 音声フィードバック: 提案理由も含めて音声で読み上げ

### 6. 栄養アドバイス
- 食事傾向分析: 過去の食事データを分析
- パーソナライズ: ユーザーの食事パターンに応じたアドバイス

### 7. ユーザー管理・設定
- LoginActivity: 初回起動時のログインID設定
- SettingsActivity: サーバーURL設定、カメラ回転角度調整
- UserPreferences: ユーザーID、サーバーURL、カメラ回転設定など

## 使用例（会話フロー）

```
[アプリ起動]
App: 「アプリを起動しました」（音声認識待機開始）

[中央ボタン短押し]
App: 「写真を撮影します。解析しますので少々お待ちください。」
[撮影→解析]
App: 「解析結果: カレーライス、サラダ。この内容で正しいですか?」

[左ボタン押下 = Yes]
App: 「食事を記録しました。内容は、カレーライスです。栄養情報は、カロリー650キロカロリー、タンパク質18グラム、炭水化物85グラム、脂質22グラムです。」

[中央ボタン長押し = 献立提案]
App: 「献立を提案します。少々お待ちください。」
App: 「献立が決まりました。おすすめの献立は、さっぱり和定食です。具体的には、焼き魚、野菜の煮物、ご飯です。昼はカレーで炭水化物多めでしたので、夕食は魚と野菜中心でバランスを取りましょう。」
```

## セットアップ

### 1. 必要な権限
```xml
<uses-permission android:name="android.permission.CAMERA" />
<uses-permission android:name="android.permission.RECORD_AUDIO" />
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE" />
```

### 2. THINKLET準備
1. XFEライセンスファイルを配置:
   - `/sdcard/thinklet/xfe-license.dat`
2. Voskモデルを配置:
   - `/sdcard/thinklet/vosk-model-ja-0.22/` または
   - `/sdcard/thinklet/vosk-model-small-ja-0.22/`

### 3. サーバーセットアップ
サーバー側APIの実装方法は [SERVER_WEBSOCKET_API.md](SERVER_WEBSOCKET_API.md) を参照してください。

必要なエンドポイント:
- `POST /analyze-meal` - 画像解析
- `POST /save-meal` - 食事記録保存
- `GET /meals/{userId}` - 食事履歴取得
- `GET /suggest-meal` - 献立提案
- `POST /get-advice` - 栄養アドバイス
- `ws://server/ws/speech` - 音声認識WebSocket（オプション）

### 4. アプリ設定
1. アプリ起動
2. ログインID入力（3文字以上）
3. 設定画面でサーバーURL設定
4. カメラ回転調整（必要に応じて）

## 依存関係

### 主要ライブラリ
```kotlin
// CameraX
implementation("androidx.camera:camera-camera2:1.3.1")
implementation("androidx.camera:camera-lifecycle:1.3.1")

// Retrofit (サーバー通信)
implementation("com.squareup.retrofit2:retrofit:2.9.0")
implementation("com.squareup.retrofit2:converter-gson:2.9.0")

// OkHttp (WebSocket)
implementation("com.squareup.okhttp3:okhttp:4.x.x")

// Vosk (オフライン音声認識)
implementation("com.alphacephei:vosk-android:0.3.47")

// THINKLET XFE (ライセンス必要)
implementation("ai.fd.thinklet:xfe:x.x.x")

// Coroutines
implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
```

## THINKLET キー設定例

`/sdcard/thinklet/key_config.json`:
```json
{
  "keyMappings": [
    {
      "name": "食事を撮影",
      "keyEvent": "KEYCODE_CAMERA",
      "action": "SHORT_PRESS",
      "intent": {
        "action": "com.example.meallogger.ACTION_ANALYZE",
        "package": "com.example.meallogger"
      }
    },
    {
      "name": "献立を提案",
      "keyEvent": "KEYCODE_CAMERA",
      "action": "LONG_PRESS",
      "intent": {
        "action": "com.example.meallogger.ACTION_SUGGEST",
        "package": "com.example.meallogger"
      }
    }
  ]
}
```

## 今後の拡張予定

- [ ] ローカルデータベース（Room）でオフライン対応
- [ ] ウェアラブル端末（Apple Watch等）連携
- [ ] 家族での食事シェア機能
- [ ] 栄養目標設定とトラッキング
- [ ] 週次/月次レポート
- [ ] バーコードスキャンによる食品登録

## トラブルシューティング

### 音声認識が動かない
1. Voskモデルが正しく配置されているか確認
2. XFEライセンスファイルが有効か確認
3. マイク権限が許可されているか確認
4. ログで `VoskVoiceService` のエラーを確認

### カメラ画像が回転している
1. 設定画面で「カメラ回転設定」を調整
2. 0°/90°/180°/270°から選択

### サーバーに接続できない
1. 設定画面でサーバーURLが正しいか確認
2. ネットワーク接続を確認
3. サーバー側のエンドポイントが稼働しているか確認

## ライセンス

本プロジェクトはサンプルアプリケーションです。THINKLET XFE SDKは別途ライセンスが必要です。
