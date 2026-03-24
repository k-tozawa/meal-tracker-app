# おでかけアドバイザー 設計書

## サービス構成

| サービス | 役割 | foregroundServiceType |
|---|---|---|
| VoiceAssistantService | 音声入力・VAD・カメラ・Gemini・TTS | microphone + camera |

- カメラボタンの検知も VoiceAssistantService 内で処理する
- SceneDetectionService は削除済み

---

## 各機能の設計

### 1. 質問に答える

**フロー**
1. XFE / NNVAD が音声区間を検出
2. 音声バッファを WAV に変換して Gemini API に送信
3. Gemini が function calling で必要な API を呼び出す
4. 結果を TTS で読み上げる

**Gemini function calling**
| 関数名 | 用途 |
|---|---|
| `search_nearby_places` | Google Places API で周辺検索 |
| `start_navigation` | 道案内を開始する |

**注意点**
- 音声処理中に次の発話が来た場合、1つだけキューに保存（それ以上は捨てる）
- TTS 再生中は VAD を無視する（TTS の声を拾わないため）
- 位置情報はバックグラウンドで定期取得してキャッシュ。音声処理時はキャッシュを使う（毎回フェッチしない）

---

### 2. 目の前のものを確認する

**フロー**
1. カメラボタン短押しを VoiceAssistantService で検知（`KEYCODE_CAMERA`）
2. CameraX で即撮影
3. 「どうぞ」と TTS で言って音声入力待ち
4. ユーザーの質問を VAD で検出
5. 撮影画像 + 質問を Gemini API に送信して回答
6. 一定時間内に音声がなければ映っているものを1〜2文で説明してフォールバック

**注意点**
- `CameraSelector.DEFAULT_BACK_CAMERA` はThinkletで動かない → `CameraSelector.Builder().build()` を使う
- カメラは起動時から準備状態にしておく
- `foregroundServiceType="microphone|camera"` が必要

---

### 3. 道案内

**フロー**
1. 「〇〇に行きたい」を Gemini が検出 → `start_navigation` function call
2. Google Places API で目的地を特定
3. Google Directions API（徒歩モード）でルート取得
4. ルートの要約を TTS で読み上げ（距離・方向・ランドマーク）
5. GPS を継続監視しながら定期的に進行方向を案内
6. 到着を検出したら「到着です」と読み上げ・ナビ終了

**注意点**
- GPS精度に限界がある（都市部で誤差10〜30m）
- 曲がり角のピンポイント検出より「北に進んでください」程度のゆるい案内を基本とする
- ナビ中は「案内をやめて」で終了
- ナビ中も音声による質問は引き続き受け付ける

---

### 4. 記録する

**フロー**
- GPSトレース：定期的に現在地（緯度・経度・時刻）を記録してローカルに保存
- 写真：1分に1枚自動撮影、撮影時刻と位置情報を付与してローカルに保存

**保存形式**
- GPSトレース：GPX形式（地図ツールで読み込み可能）
- 写真：JPEG（EXIFに位置情報を付与）

**注意点**
- 記録はアプリ起動中は常時動作
- 写真の自動撮影はカメラボタン操作中と競合しないよう排他制御する

---

## 既知の問題と対処

### 位置情報が取れない
- **原因**：NETWORK_PROVIDER（NpProxy/IZAT）がデバイスで壊れている
- **対処**：GPS_PROVIDER のみ使用。last known location を積極的に使う（キャッシュ有効期限を長めに設定）
- **残課題**：GPS フィックスに時間がかかる場合、天気・場所検索が機能しないことがある

### 音声がスルーされる
- **原因**：音声処理のたびに位置情報を3秒待っていた
- **対処**：位置情報はバックグラウンドで定期取得してキャッシュ。音声処理時はキャッシュのみ使う
- **補足**：TTS 再生中の無視は仕様として許容（TTS の声を拾わないために必要）

### CameraX が動かない
- **原因**：Thinkletのカメラにレンズ向きメタデータがない
- **対処**：`CameraSelector.Builder().build()` を使う（`DEFAULT_BACK_CAMERA` は使わない）

---

## ボタン割り当て

| ボタン | 操作 | 処理 |
|---|---|---|
| カメラボタン長押し | アプリ起動 | key_config.json で設定 |
| カメラボタン短押し | 撮影・質問受付 | VoiceAssistantService 内で `KEYCODE_CAMERA` を検知 |
| 音量ボタン | （未割り当て） | - |
