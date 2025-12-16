# THINKLET物理ボタン設定

## キーコンフィグファイルの配置

`thinklet_key_config.json` を以下の場所にコピーしてください：

```
/storage/emulated/0/Android/data/ai.fd.thinklet.app.launcher/files/key_config.json
```

## ボタン配置

- **CENTER（真ん中）**: アプリ起動
- **LEFT（左）**: 献立提案
- **RIGHT（右）**: 画像解析

## XFEライセンスファイルの配置

XFE VAD（Voice Activity Detection）機能を使用するには、ライセンスファイルが必要です：

```bash
# ライセンスファイルをコピー
adb push thinklet-xfe/daikin_xfe_full_till_2050-12-31.lic /mnt/sdcard/thinklet/xfe-license.dat

# またはディレクトリを作成してから
adb shell mkdir -p /mnt/sdcard/thinklet
adb push thinklet-xfe/daikin_xfe_full_till_2050-12-31.lic /mnt/sdcard/thinklet/xfe-license.dat
```

## Voskモデルの配置（オフライン音声認識）

Voskを使ったオフライン音声認識を使用する場合、モデルファイルをTHINKLETに配置します：

```bash
# Voskモデルをダウンロード（PCで実行）
# 大サイズモデル（約1GB、高精度、推奨）
wget https://alphacephei.com/vosk/models/vosk-model-ja-0.22.zip
unzip vosk-model-ja-0.22.zip

# または小サイズモデル（約130MB、軽量）
# wget https://alphacephei.com/vosk/models/vosk-model-small-ja-0.22.zip
# unzip vosk-model-small-ja-0.22.zip

# THINKLETにコピー
adb push vosk-model-ja-0.22 /mnt/sdcard/thinklet/vosk-model-ja-0.22/
```

モデルダウンロード元：https://alphacephei.com/vosk/models

## 設定方法

1. `thinklet_key_config.json` をTHINKLETデバイスの上記パスにコピー
2. XFEライセンスファイルを `/mnt/sdcard/thinklet/xfe-license.dat` にコピー
3. THINKLETランチャーを再起動（設定を反映）
4. 物理ボタンでアプリを操作

## コマンド例

```bash
# adb経由でコピーする場合
adb push thinklet_key_config.json /storage/emulated/0/Android/data/ai.fd.thinklet.app.launcher/files/key_config.json
adb shell mkdir -p /mnt/sdcard/thinklet
adb push thinklet-xfe/daikin_xfe_full_till_2050-12-31.lic /mnt/sdcard/thinklet/xfe-license.dat
```

## Intent Action

キーコンフィグで使用しているIntent Action：

- `com.example.meallogger.ACTION_ANALYZE` - 画像解析実行
- `com.example.meallogger.ACTION_SUGGEST` - 献立提案実行
- `package: com.example.meallogger`, `class: MainActivity` - アプリ起動
