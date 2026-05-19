# HearGlasses

HearGlasses 是一个面向听障辅助眼镜的端到端原型项目。当前链路为：

```text
ESP32-S3 眼镜端采集麦克风音频
  -> BLE 发送 Opus 音频到 Android 手机
  -> 手机端解码并进行本地流式 ASR
  -> 手机端将 partial/final 文字通过 BLE 写回硬件
  -> OLED 屏幕显示转录内容
```

项目同时包含 Android 应用、ESP32-S3 固件、调试工具与原型文档。

## 当前功能

- Android 手机端
  - 通过 BLE 连接 HearGlasses 硬件。
  - 接收硬件端 `Audio_TX` 音频通知和 `Command_TX` 控制通知。
  - 使用 Concentus 解码 Opus 音频，统一送入 16kHz mono PCM 处理链路。
  - 使用 sherpa-onnx 在线流式中文 CTC 模型做本地语音识别。
  - 支持 partial 文本低延迟写回和 final 文本分片写回。
  - 支持前台服务，用于持续收音和通知栏状态展示。
  - 支持本地文件、手机麦克风、真实 BLE 三种音频源模式。
  - 支持日志写入 app 私有目录，记录 BLE 状态、ASR 延迟、写屏动作、错误等信息。
  - 主界面底部双 tab：
    - `转录`：显示转录文字、开始/停止收音按钮、设置按钮。
    - `调试`：上下滑动显示调试信息、应用信息和预留扩展区域。

- ESP32-S3 硬件端
  - 使用 INMP441 采集 16kHz mono 音频。
  - 使用 Opus 40ms frame 编码后通过 BLE notification 发送。
  - 接收手机端写回的 UTF-8 文本。
  - 使用 SSD1306 128x64 I2C OLED 显示状态和转录文字。
  - 对 OLED 刷新、BLE 连接参数、音频队列做了低延迟调优。

## 项目结构

```text
HearGlasses/
├── app/
│   ├── build.gradle.kts
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── assets/
│       │   ├── sample_audio.wav
│       │   ├── sample_transcript.txt
│       │   ├── sherpa-onnx-streaming-ctc-zh/
│       │   └── sherpa-onnx-streaming-zipformer-ctc-zh-int8-2025-06-30/
│       ├── java/com/hearglasses/app/
│       │   ├── MainActivity.kt
│       │   ├── asr/
│       │   ├── audio/
│       │   ├── ble/
│       │   ├── di/
│       │   ├── logging/
│       │   ├── service/
│       │   ├── settings/
│       │   └── ui/
│       └── res/values/
├── driver/
│   ├── ble_audio_peripheral/
│   ├── inmp441_capture/
│   ├── wifi_ap_phone/
│   └── oled_*_test/
├── tools/
├── prototype.md
├── ui.html
├── build.gradle.kts
├── settings.gradle.kts
└── README.md
```

## Android 端模块

- `MainActivity.kt`
  - 应用入口。
  - 申请蓝牙、录音、通知等运行时权限。
  - 初始化依赖容器并挂载 Compose UI。

- `di/AppContainer.kt`
  - 统一创建 `BleManager`、`OpusDecoder`、`PcmAudioRecorder`、`SpeechRecognizerEngine`、`AppLogger` 和 `HearGlassesController`。
  - 管理调试音频源切换。

- `ble/`
  - `BleConstants.kt`：定义 Service / Characteristic UUID、MTU 请求值和控制指令。
  - `BleManager.kt`：抽象 BLE 事件、状态和写回接口。
  - `RealBleManager.kt`：真实 BLE 扫描、直连、GATT 服务发现、通知订阅、文本写回。
  - `FileBleManager.kt`：读取 assets 中的 wav 文件做离线链路验证。
  - `MicBleManager.kt`：使用手机麦克风做本地链路验证。
  - `MockBleManager.kt`：保留 mock 输入，便于 UI/业务流调试。

- `audio/`
  - `OpusDecoder.kt`：基于 Concentus 解码硬件端 Opus 包。
  - `PcmAudioPlayer.kt`：可选播放收到的 PCM。
  - `PcmAudioRecorder.kt`：将链路中的 PCM 保存为调试录音文件。

- `asr/SpeechRecognizerEngine.kt`
  - 使用 `com.bihe0832.android:lib-sherpa-onnx` 初始化在线识别器。
  - 默认加载 `sherpa-onnx-streaming-zipformer-ctc-zh-int8-2025-06-30/model.int8.onnx` 和 `tokens.txt`。
  - 支持 endpoint 检测，VAD 阈值会影响静音判停速度。
  - 模型初始化失败时回退到 mock ASR，避免应用崩溃。

- `service/`
  - `HearGlassesController.kt`：核心业务编排，负责 BLE 事件消费、音频缓冲、ASR 分块、转录列表、写屏节流、延迟统计和日志记录。
  - `HearGlassesService.kt`：前台服务，用于后台持续收音和通知栏状态更新。

- `logging/AppLogger.kt`
  - 每次 app 会话创建一个日志文件。
  - 日志目录位于 app 私有目录：`files/logs/hearglasses-yyyyMMdd-HHmmss.log`。
  - 记录启动/停止、BLE 状态、ASR chunk、partial/final 文本、写屏动作、周期性调试快照和错误。

- `settings/GeekSettings.kt`
  - 管理调试模式、VAD 阈值、MTU、模型选项等极客设置。

- `ui/`
  - `HearGlassesApp.kt`：Compose 主界面，底部双 tab 布局。
  - `GeekSettingsDialog.kt`：调试设置弹窗。
  - `theme/Theme.kt`：应用主题。

## BLE 协议

默认 Service UUID：

```text
Service:    000018fd-0000-1000-8000-00805f9b34fb
Audio_TX:   00002a3d-0000-1000-8000-00805f9b34fb  notify
Command_TX: 00002a3e-0000-1000-8000-00805f9b34fb  notify
Text_RX:    00002a3f-0000-1000-8000-00805f9b34fb  write / write no response
```

控制指令：

```text
0x01 START_SPEECH
0x00 END_SPEECH
```

手机写回硬件显示的文本命令：

```text
@STATUS <status text>
@TEXT <partial text>
@TEXTF <final first chunk>
@TEXTA <final append chunk>
@COMMIT
@CLEAR
```

## 低延迟优化

当前已做的主要优化：

- Android 端 ASR 分块从 100ms 降到 40ms，贴近硬件 Opus frame。
- Android 端 BLE 事件轮询从 20ms 降到 10ms。
- partial 文本写屏节流从 2s 降到 350ms。
- Sherpa endpoint 静音判停使用 VAD 阈值动态调整。
- ESP32-S3 OLED 刷新间隔从 1000ms 降到 150ms。
- 调试面板显示 `音频->ASR / ASR / 写屏` 延迟拆分。
- 日志文件记录每个 ASR chunk 的耗时，便于实测定位瓶颈。

## 硬件固件

主固件：

```text
driver/ble_audio_peripheral/ble_audio_peripheral.ino
```

辅助固件：

- `driver/inmp441_capture/`：INMP441 采集调试。
- `driver/wifi_ap_phone/`：ESP32-S3 Wi-Fi AP 手机连接实验。
- `driver/oled_*_test/`：OLED 连线、地址、对比度和全屏显示测试。

硬件连接说明见：

```text
driver/README.md
```

## 编译与运行

Android debug 包：

```bash
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:assembleDebug
```

生成位置：

```text
app/build/outputs/apk/debug/app-debug.apk
```

ESP32-S3 固件编译：

```bash
arduino-cli compile --fqbn esp32:esp32:esp32s3 driver/ble_audio_peripheral
```

ESP32-S3 固件上传：

```bash
arduino-cli upload --fqbn esp32:esp32:esp32s3 --port /dev/cu.usbmodem5C371873961 driver/ble_audio_peripheral
```

如果端口不同，先查看：

```bash
arduino-cli board list
```

## 调试建议

1. 优先用 `手机麦克风` 或 `本地文件` 模式确认 ASR 和 UI 正常。
2. 再切换到 `硬件 BLE` 模式确认 BLE 连接、音频包数和丢包数。
3. 观察调试 tab 中的延迟拆分：
   - `音频->ASR` 高：说明 BLE 队列、音频缓冲或轮询有积压。
   - `ASR` 高：说明模型推理耗时较长。
   - `写屏` 高或屏幕慢：检查 BLE 写回和 OLED 刷新。
4. 查看 app 私有目录下的日志文件，确认每次运行的关键事件和耗时。

## TODO

1. 增加声纹识别功能。
2. 增加一键跳转日志文件界面功能。
3. 考虑如何实现硬件连接手机热点，更新固件。
