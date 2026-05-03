V1.0 核心功能原型与系统工程规范：

---

### 1. 系统核心架构 (V1.0 MVP 数据流)

将 VAD 放到 ESP32 上的最大好处是：**从物理源头上掐断了无效的蓝牙数据传输**。手机端的 Android 应用不再需要跑 VAD，只要收到音频包，无脑解码丢给 ASR 引擎即可。

* **状态 A（安静/噪音）：** 麦克风采集 $\rightarrow$ ESP32 跑轻量级 VAD $\rightarrow$ 判断为非人声 $\rightarrow$ **直接丢弃数据**。蓝牙处于空闲状态，极度省电。
* **状态 B（有人说话）：** 麦克风采集 $\rightarrow$ VAD 判断为人声 $\rightarrow$ ESP32 唤醒 Opus 编码器 $\rightarrow$ 压缩成帧 $\rightarrow$ 通过 BLE 发送给手机 $\rightarrow$ 手机端 Opus 解码 $\rightarrow$ 喂给 Sherpa-onnx $\rightarrow$ 识别出文字 $\rightarrow$ 通过 BLE 发送给 ESP32 $\rightarrow$ 刷新屏幕。

---

### 2. ESP32-S3 端固件逻辑设计

在 ESP32 上，你需要维护一个严格的**状态机**和**多线程/任务 (FreeRTOS)** 架构：

* **Task 1: I2S 采集任务 (高优先级)**
    * 死循环按 16kHz, 16bit 读取 I2S 麦克风数据。
    * 将数据切分为 **20ms 或 30ms** 的帧（对应 320 或 480 个采样点），写入 RingBuffer。
* **Task 2: VAD & Opus 处理任务 (中优先级)**
    * 从 RingBuffer 读取帧，送入轻量级 VAD 算法（强烈推荐移植 **WebRTC VAD** 的 C 代码，它极度轻量，专为微控制器设计）。
    * **静音缓冲机制 (Hangover)：** 核心难点。人说话有停顿，VAD 不能一碰到静音立刻切断，否则一句话会碎成好几段。你需要设置一个计数器，比如 VAD 连续 500ms（约 25 帧）检测不到人声，才判定为“一句话结束”。
    * 判定为人声的帧，送入 `libopus` 进行编码（设定码率为 24kbps 或 32kbps）。
* **Task 3: BLE 通信任务 (低优先级)**
    * 维护 GATT Server。
    * 将 Opus 压缩后的字节流通过 `Notify` 发送出去。

---

### 3. 极简 BLE GATT 协议设计

不需要复杂的配置，我们只建立一个核心 Service 和三个 Characteristic：

**Service UUID: `0x18FD` (自定义语音辅助服务)**

1.  **Audio_TX (特征值 1 - 眼镜发音频给手机)**
    * UUID: `0x2A3D`
    * 属性: `Notify`
    * 有效载荷: Opus 压缩后的音频数据（通常一帧约 60-80 字节）。
2.  **Command_TX (特征值 2 - 眼镜发指令给手机)**
    * UUID: `0x2A3E`
    * 属性: `Notify`
    * 有效载荷: 极其重要！用于发送 `START_SPEECH` (0x01) 和 `END_SPEECH` (0x00)。因为 ESP32 做了截断，手机不知道什么时候一句话结束。ESP32 必须在 VAD 判定句子结束时发一个 `END_SPEECH`，告诉手机端 ASR 引擎：“别等了，强制输出当前句子的最终结果，并清空历史状态”。
3.  **Text_RX (特征值 3 - 手机发文字给眼镜)**
    * UUID: `0x2A3F`
    * 属性: `Write Without Response`
    * 有效载荷: UTF-8 编码的汉字字符串。为了简化，每次直接发送当前应该在屏幕上显示的完整字符串（例如最多 20 个汉字）。

---

### 4. Android 端 (手机) 极简代码逻辑

在 V1.0 阶段，Android App 的 UI 只需要一个黑乎乎的屏幕加上几行日志打印。核心在后台服务 (Service) 中：

1.  **BLE 接收器：** 连接 ESP32，申请最大 MTU (建议 512)，订阅 `Audio_TX` 和 `Command_TX`。
2.  **Opus 解码器：** 引入 Android 端的 JNA/JNI 封装的 Opus 库（如 `concentus` 或自己编译 `libopus`），将收到的字节数组还原为 16kHz 的 PCM 短数组。
3.  **Sherpa-onnx 引擎：**
    * 收到解码后的 PCM 数据，直接调用 `recognizer.acceptWaveform(pcmArray)`。
    * 如果收到 `Command_TX` 的 `END_SPEECH` 信号，调用一个强制刷新的方法获取最终文本（或者判断 `is_endpoint`）。
    * 如果识别出有效文字，通过 BLE 写回 `Text_RX` 特征值。


### 开发起步建议 (避坑指南)

1.  **先跑通 WebRTC VAD + Opus (纯软件验证)：** 在写任何蓝牙代码之前，先在 ESP32 上写个简单的测试代码。读一段内置的 PCM 数组，喂给 VAD，判断为 1 的喂给 Opus，看看会不会内存溢出 (OOM)。ESP32 的内部 SRAM 有限，分配 RingBuffer 时不要太大。
2.  **关闭 WiFi：** 在 ESP32-S3 上，**绝对不要同时开启 WiFi 和 BLE 跑实时音频流**。它们共用一根天线，分时复用会造成灾难性的 BLE 延迟和丢包。你的系统是纯离线的，直接用 `esp_wifi_stop()` 关死 WiFi。
3.  **Android 端的 MTU 坑：** Android 连接 BLE 后，默认 MTU 是 23。你必须在 `onServicesDiscovered` 回调之后，手动调用 `gatt.requestMtu(512)`，并在 `onMtuChanged` 成功回调后，再开始让 ESP32 发送音频！否则包会被切碎，导致 Opus 无法解码。