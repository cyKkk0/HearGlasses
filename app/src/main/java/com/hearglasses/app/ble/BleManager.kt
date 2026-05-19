package com.hearglasses.app.ble

import kotlinx.coroutines.flow.StateFlow

data class BleUiState(
    val isConnected: Boolean = false,
    val statusText: String = "未连接",
    val batteryText: String = "--%",
    val mtu: Int = 23,
    val receivedAudioPackets: Long = 0,
    val lostAudioPackets: Long = 0,
)

sealed interface BleEvent {
    data class AudioPacket(
        val bytes: ByteArray,
        val receivedElapsedRealtimeMillis: Long = android.os.SystemClock.elapsedRealtime(),
    ) : BleEvent
    data class CommandPacket(val command: Byte) : BleEvent
    data class Error(val message: String) : BleEvent
}

interface BleManager {
    val modeLabel: String
    /** True when the audio payload from consumeEvents() is already raw PCM, not Opus-encoded. */
    val isAudioPcm: Boolean get() = false
    /** Sample rate of the audio payload (PCM). Defaults to 16kHz. */
    val audioSampleRate: Int get() = 16_000
    /** Human-readable audio file info, e.g. "16000Hz/1ch/16bit". */
    val audioInfo: String get() = ""
    /** True when incoming PCM should also be played through the phone speaker. */
    val playIncomingPcm: Boolean get() = false
    val uiState: StateFlow<BleUiState>

    fun connect()

    fun disconnect()

    fun writeText(text: String)

    fun consumeEvents(): List<BleEvent>
}
