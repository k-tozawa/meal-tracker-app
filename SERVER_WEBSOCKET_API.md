# WebSocket音声認識API仕様

## エンドポイント
```
ws://192.168.3.27:8000/ws/voice-recognition
```

## プロトコル

### クライアント → サーバー
音声データをリアルタイムでバイナリメッセージとして送信：
- フォーマット: PCM 16-bit
- サンプルレート: 16000 Hz
- チャンネル: モノラル
- エンコーディング: リトルエンディアン

### サーバー → クライアント
JSON形式のテキストメッセージ：

#### 1. 途中結果（オプション）
```json
{
  "partial": "こんにち"
}
```

#### 2. 最終結果
```json
{
  "final": "こんにちは"
}
```

#### 3. エラー
```json
{
  "error": "エラーメッセージ"
}
```

## 実装例（Python + FastAPI + Google Speech API）

```python
from fastapi import FastAPI, WebSocket
from google.cloud import speech
import asyncio

app = FastAPI()

@app.websocket("/ws/voice-recognition")
async def voice_recognition(websocket: WebSocket):
    await websocket.accept()

    client = speech.SpeechClient()

    config = speech.RecognitionConfig(
        encoding=speech.RecognitionConfig.AudioEncoding.LINEAR16,
        sample_rate_hertz=16000,
        language_code="ja-JP",
        enable_automatic_punctuation=True,
    )

    streaming_config = speech.StreamingRecognitionConfig(
        config=config,
        interim_results=True  # 途中結果を返す
    )

    async def audio_generator():
        try:
            while True:
                data = await websocket.receive_bytes()
                yield speech.StreamingRecognizeRequest(audio_content=data)
        except Exception as e:
            print(f"Audio stream ended: {e}")

    try:
        requests = audio_generator()
        responses = client.streaming_recognize(streaming_config, requests)

        for response in responses:
            for result in response.results:
                if result.is_final:
                    transcript = result.alternatives[0].transcript
                    await websocket.send_json({"final": transcript})
                else:
                    transcript = result.alternatives[0].transcript
                    await websocket.send_json({"partial": transcript})

    except Exception as e:
        await websocket.send_json({"error": str(e)})
    finally:
        await websocket.close()
```

## 必要な設定

### Google Cloud Speech-to-Text API
1. Google Cloud Consoleでプロジェクトを作成
2. Speech-to-Text APIを有効化
3. サービスアカウントキーを作成してダウンロード
4. 環境変数を設定：
```bash
export GOOGLE_APPLICATION_CREDENTIALS="/path/to/service-account-key.json"
```

### 依存関係（Python）
```bash
pip install fastapi uvicorn google-cloud-speech websockets
```

### サーバー起動
```bash
uvicorn main:app --host 0.0.0.0 --port 8000 --reload
```

## テスト方法

### WebSocketクライアントでテスト
```javascript
const ws = new WebSocket('ws://192.168.3.27:8000/ws/voice-recognition');

ws.onopen = () => {
    console.log('Connected');
    // 音声データを送信
    const audioData = new Uint8Array([...]); // PCM データ
    ws.send(audioData);
};

ws.onmessage = (event) => {
    const data = JSON.parse(event.data);
    console.log('Received:', data);
};
```

## 注意事項

1. **Google Speech APIの料金**
   - 最初の60分/月は無料
   - それ以降は従量課金
   - 詳細: https://cloud.google.com/speech-to-text/pricing

2. **ネットワーク要件**
   - 低レイテンシが重要
   - 安定したWi-Fi接続推奨

3. **セキュリティ**
   - 本番環境ではWSS（WebSocket Secure）を使用
   - 認証・認可を実装

4. **代替案**
   - Google Speech API以外の選択肢：
     - Azure Speech Service
     - Amazon Transcribe
     - OpenAI Whisper API
     - ローカルWhisperモデル（オフライン対応）
