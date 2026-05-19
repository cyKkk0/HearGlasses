# HearGlasses

`HearGlasses` 当前是一个基于 Android Studio 的 Android 端 MVP 工程骨架，目标是连接眼镜端 ESP32 设备，通过 BLE 接收 Opus 音频流、在手机端完成语音识别，并将识别文本回写到眼镜端显示。

## 当前功能目标
- 与眼镜端 ESP32-S3 建立 BLE 通信
- 接收 `Audio_TX` 音频数据与 `Command_TX` 控制指令
- 在手机端完成 Opus 解码
- 将 PCM 数据送入本地 ASR 引擎
- 在收到句末命令后输出最终识别文本
- 通过 `Text_RX` 将文本写回眼镜显示
- 在手机界面展示连接状态、电量、实时转写和收音控制

## 当前代码功能框架

### 1. 应用入口层
- `app/src/main/java/com/hearglasses/app/MainActivity.kt`
  - 应用入口
  - 申请蓝牙与通知权限
  - 初始化依赖容器
  - 挂载 Compose 主界面

### 2. 依赖装配层
- `app/src/main/java/com/hearglasses/app/di/AppContainer.kt`
  - 统一创建并管理 `BleManager`、`OpusDecoder`、`SpeechRecognizerEngine`、`HearGlassesController`
  - 内置 `DebugMode`，当前默认走 `MOCK` 模式，便于无硬件联调

### 3. BLE 通信层
- `app/src/main/java/com/hearglasses/app/ble/BleConstants.kt`
  - 定义 MVP 阶段使用的 BLE Service / Characteristic UUID
  - 定义 `START_SPEECH` 与 `END_SPEECH` 指令常量
- `app/src/main/java/com/hearglasses/app/ble/BleManager.kt`
  - 抽象 BLE 能力接口
  - 定义 `BleUiState` 和 `BleEvent`
- `app/src/main/java/com/hearglasses/app/ble/MockBleManager.kt`
  - 模拟 BLE 连接成功、MTU、音频包输入、句末命令、文本回写
  - 用于无硬件情况下验证业务流和 UI
- `app/src/main/java/com/hearglasses/app/ble/RealBleManager.kt`
  - 真实 BLE 管理骨架
  - 预留 GATT 连接、MTU 请求、特征监听与写回逻辑

### 4. 音频处理层
- `app/src/main/java/com/hearglasses/app/audio/OpusDecoder.kt`
  - Opus 解码器接口占位
  - 后续替换为真实 JNI/JNA 或纯 Kotlin 解码实现

### 5. 识别引擎层
- `app/src/main/java/com/hearglasses/app/asr/SpeechRecognizerEngine.kt`
  - 流式识别引擎占位
  - 提供 `acceptWaveform()` 与 `forceFinalize()` 两类接口
  - 后续用于接入 Sherpa-onnx 与 endpoint 检测逻辑

### 6. 状态协调与业务编排层
- `app/src/main/java/com/hearglasses/app/service/HearGlassesController.kt`
  - 聚合 BLE、Opus、ASR 三层能力
  - 管理 UI 状态 `AppUiState`
  - 负责收音启停、识别结果流转、实时文本列表更新
  - 当前已加入调试面板状态，支持展示模式、MTU、包数、partial/final 文本
- `app/src/main/java/com/hearglasses/app/service/HearGlassesService.kt`
  - 前台服务骨架
  - 为后续后台保活、蓝牙常驻连接、持续识别提供承载点

### 7. UI 展示层
- `app/src/main/java/com/hearglasses/app/ui/HearGlassesApp.kt`
  - 使用 Jetpack Compose 还原 `ui.html` 中的主界面结构
  - 包含顶部状态栏、调试面板、实时字幕区、主控制按钮、设置摘要区
  - 当前可直接在无硬件下观察 mock 模式的链路结果
- `app/src/main/java/com/hearglasses/app/ui/theme/Theme.kt`
  - 定义应用浅色主题色板
- `app/src/main/res/values/themes.xml`
  - Android 主题入口

## 无硬件测试方式
当前默认 `DebugMode.REAL_BLE`，启动收音后会按旧设备地址
`14:C1:9F:26:C5:61` 直连开发板，并将 partial/final 转录文本通过
`Text_RX` 写回屏幕。
如需无硬件测试，可在极客设置里切换到本地文件或手机麦克风模式。

## 当前目录结构
```text
HearGlasses/
├── app/
│   ├── build.gradle.kts
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/hearglasses/app/
│       │   ├── MainActivity.kt
│       │   ├── asr/
│       │   ├── audio/
│       │   ├── ble/
│       │   ├── di/
│       │   ├── service/
│       │   └── ui/
│       └── res/values/themes.xml
├── build.gradle.kts
├── gradle.properties
├── settings.gradle.kts
├── prototype.md
└── ui.html
```

## 当前状态
已完成：
- Android Studio 项目初始化
- Compose 主界面骨架搭建
- BLE / Opus / ASR / Controller / Service 分层落位
- Mock BLE 与调试面板接入
- File 音频模式输入接入
- Sherpa-onnx Android 识别骨架接入（依赖 + 配置 + 运行时降级）
- README 与工程结构整理

未完成：
- 真实 BLE 扫描与设备连接
- GATT 服务发现与特征订阅
- Opus 真正解码
- Sherpa-onnx 模型文件落地与真机识别验证
- 前台服务与 UI 的正式联动
- 眼镜端文本回写闭环验证

## Sherpa-onnx 接入说明
当前已接入 `com.bihe0832.android:lib-sherpa-onnx:8.5.1`，并在 `SpeechRecognizerEngine` 中优先尝试初始化在线流式识别器。

默认模型目录约定：
- `app/src/main/assets/sherpa-onnx-streaming-zipformer-zh/encoder.onnx`
- `app/src/main/assets/sherpa-onnx-streaming-zipformer-zh/decoder.onnx`
- `app/src/main/assets/sherpa-onnx-streaming-zipformer-zh/joiner.onnx`
- `app/src/main/assets/sherpa-onnx-streaming-zipformer-zh/tokens.txt`

如果这些模型文件不存在：
- 应用不会直接崩溃
- 会自动降级回当前的 Mock ASR 输出逻辑

建议你下一步手动放入一套可用的 sherpa-onnx streaming transducer 中文模型，再用 `DebugMode.FILE` 验证整条链路。

## 建议下一步
1. 放入真实 sherpa-onnx streaming 模型文件
2. 将 File 模式的 wav 输入与 sherpa-onnx 结果联调验证
3. 接入真实 Opus 解码器
4. 将控制器迁移为 `ViewModel + ForegroundService` 协同架构
