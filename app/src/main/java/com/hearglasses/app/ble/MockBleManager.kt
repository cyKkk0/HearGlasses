package com.hearglasses.app.ble

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class MockBleManager : BleManager {
    private val _uiState = MutableStateFlow(BleUiState())
    override val uiState: StateFlow<BleUiState> = _uiState.asStateFlow()

    override val modeLabel: String = "Mock BLE"

    private val queuedEvents = mutableListOf<BleEvent>()
    private val scriptedPackets = List(6) { index -> ByteArray(160) { (index + 1).toByte() } }
    private val writes = mutableListOf<String>()

    private var isStreaming = false
    private var sentenceIndex = 0
    private var packetIndex = 0
    private var startCommandPending = false

    override fun connect() {
        _uiState.value = BleUiState(
            isConnected = true,
            statusText = "已连接(Mock)",
            batteryText = "92%",
            mtu = 512,
        )
        isStreaming = true
        sentenceIndex = 0
        packetIndex = 0
        startCommandPending = true
        queuedEvents.clear()
    }

    override fun disconnect() {
        _uiState.value = BleUiState(statusText = "已断开(Mock)")
        isStreaming = false
        sentenceIndex = 0
        packetIndex = 0
        startCommandPending = false
        queuedEvents.clear()
    }

    override fun writeText(text: String) {
        if (text.isBlank()) {
            return
        }
        writes += text
    }

    override fun consumeEvents(): List<BleEvent> {
        val events = queuedEvents.toMutableList()
        queuedEvents.clear()

        if (!isStreaming) {
            return events
        }

        when {
            startCommandPending -> {
                events += BleEvent.CommandPacket(BleConstants.COMMAND_START_SPEECH)
                startCommandPending = false
            }
            packetIndex < PACKETS_PER_SENTENCE -> {
                val packet = scriptedPackets[(sentenceIndex + packetIndex) % scriptedPackets.size]
                events += BleEvent.AudioPacket(packet)
                packetIndex += 1
            }
            else -> {
                events += BleEvent.CommandPacket(BleConstants.COMMAND_END_SPEECH)
                sentenceIndex += 1
                packetIndex = 0
                startCommandPending = true
            }
        }

        return events
    }

    fun sentTexts(): List<String> = writes.toList()

    private companion object {
        const val PACKETS_PER_SENTENCE = 4
    }
}
