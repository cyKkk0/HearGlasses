package com.hearglasses.app.settings

import com.hearglasses.app.di.DebugMode

data class GeekSettings(
    val vadThreshold: Float = 0.5f,
    val mtuSize: Int = 512,
    val modelName: String = "zh-streaming-ctc",
    val debugMode: DebugMode = DebugMode.REAL_BLE,
    val keepAlive: Boolean = true,
)

enum class VadPreset(val label: String, val threshold: Float) {
    HIGH("高 (静音判停更久)", 0.8f),
    MEDIUM("中 (默认)", 0.5f),
    LOW("低 (更快判停)", 0.2f),
}

enum class MtuPreset(val label: String, val mtu: Int) {
    S23("23 (默认)", 23),
    S185("185", 185),
    S512("512 (高吞吐)", 512),
}

enum class ModelPreset(val label: String, val key: String) {
    CTC_ZH("中文 CTC (int8)", "zh-streaming-ctc"),
    ZIPFORMER_ZH("中文 Zipformer2", "zh-zipformer2"),
}
