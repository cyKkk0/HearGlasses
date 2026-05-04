package com.hearglasses.app.service

import com.hearglasses.app.asr.RecognitionResult
import com.hearglasses.app.asr.SpeechRecognizerEngine
import com.hearglasses.app.audio.OpusDecoder
import com.hearglasses.app.ble.BleConstants
import com.hearglasses.app.ble.BleEvent
import com.hearglasses.app.ble.BleManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.time.Duration.Companion.seconds

data class TranscriptItem(
    val id: Long,
    val text: String,
    val timestampMillis: Long,
    val isActive: Boolean = false,
)

data class DebugPanelState(
    val modeLabel: String = "未知模式",
    val asrMode: String = "未知",
    val asrInitError: String = "",
    val audioInfo: String = "",
    val decoderInfo: String = "",
    val mtu: Int = 23,
    val packetCount: Int = 0,
    val peakAmplitude: Int = 0,
    val lastPartialText: String = "",
    val lastFinalText: String = "",
)

data class AppUiState(
    val isListening: Boolean = false,
    val connectionText: String = "未连接",
    val batteryText: String = "--%",
    val transcriptItems: List<TranscriptItem> = emptyList(),
    val placeholderText: String = "点击下方按钮开始收音",
    val geekSettingsSummary: String = "",
    val debugPanelState: DebugPanelState = DebugPanelState(),
)

class HearGlassesController(
    private val bleManager: BleManager,
    private val opusDecoder: OpusDecoder,
    private val speechRecognizerEngine: SpeechRecognizerEngine,
) {
    private var nextId = 0L
    private var pollingJob: Job? = null

    private val _uiState = MutableStateFlow(
        AppUiState(
            debugPanelState = DebugPanelState(
                modeLabel = bleManager.modeLabel,
            ),
        ),
    )
    val uiState: StateFlow<AppUiState> = _uiState.asStateFlow()

    init {
        syncBleState()
    }

    fun startListening() {
        if (_uiState.value.isListening) return
        bleManager.connect()
        _uiState.update {
            it.copy(
                isListening = true,
                connectionText = "连接中",
                transcriptItems = emptyList(),
                debugPanelState = it.debugPanelState.copy(
                    packetCount = 0,
                    peakAmplitude = 0,
                    lastPartialText = "",
                    lastFinalText = "",
                ),
            )
        }
        startPolling()
    }

    fun stopListening() {
        if (!_uiState.value.isListening) return
        stopPolling()
        bleManager.disconnect()
        finalizeActiveTranscript()
        syncBleState()
        _uiState.update {
            it.copy(
                isListening = false,
            )
        }
    }

    fun toggleListening() {
        if (_uiState.value.isListening) {
            stopListening()
        } else {
            startListening()
        }
    }

    private fun syncFromBle() {
        syncBleState()

        bleManager.consumeEvents().forEach { event ->
            when (event) {
                is BleEvent.AudioPacket -> {
                    val decoded = if (bleManager.isAudioPcm) {
                        pcmBytesToShortArray(event.bytes)
                    } else {
                        opusDecoder.decode(event.bytes)
                    }
                    val peak = decoded.maxOfOrNull { kotlin.math.abs(it.toInt()) } ?: 0
                    val result = speechRecognizerEngine.acceptWaveform(
                        decoded,
                        audioSampleRate = bleManager.audioSampleRate,
                    )
                    _uiState.update {
                        it.copy(
                            debugPanelState = it.debugPanelState.copy(
                                asrMode = speechRecognizerEngine.modeLabel,
                                asrInitError = speechRecognizerEngine.fullInitError() ?: "",
                                packetCount = it.debugPanelState.packetCount + 1,
                                peakAmplitude = maxOf(it.debugPanelState.peakAmplitude, peak),
                                lastPartialText = result.partialText,
                            ),
                        )
                    }
                    if (result.partialText.isNotBlank()) {
                        pushTranscript(result.partialText, active = true, replaceActive = true)
                    }
                    if (result.isEndpoint) {
                        // Finalize the current utterance → marks it as a completed line
                        finalizeActiveTranscript()
                        speechRecognizerEngine.resetStream()
                    }
                }
                is BleEvent.CommandPacket -> {
                    if (event.command == BleConstants.COMMAND_END_SPEECH) {
                        val result = speechRecognizerEngine.forceFinalize()
                        applyRecognitionResult(result)
                    }
                }
                is BleEvent.Error -> {
                    _uiState.update { it.copy(connectionText = event.message) }
                }
            }
        }
        syncBleState()
        pruneOldTranscripts()
    }

    private fun syncBleState() {
        val bleState = bleManager.uiState.value
        _uiState.update {
            it.copy(
                connectionText = bleState.statusText,
                batteryText = bleState.batteryText,
                debugPanelState = it.debugPanelState.copy(
                    modeLabel = bleManager.modeLabel,
                    audioInfo = bleManager.audioInfo,
                    decoderInfo = opusDecoder.description(),
                    mtu = bleState.mtu,
                ),
            )
        }
    }

    private fun startPolling() {
        pollingJob?.cancel()
        pollingJob = MainScope().launch {
            while (_uiState.value.isListening) {
                syncFromBle()
                delay(100)
            }
        }
    }

    private fun stopPolling() {
        pollingJob?.cancel()
        pollingJob = null
    }

    private fun applyRecognitionResult(result: RecognitionResult) {
        if (result.finalText.isBlank()) {
            return
        }
        pushTranscript(result.finalText, active = false, replaceActive = true)
        bleManager.writeText(result.finalText)
        _uiState.update {
            it.copy(
                debugPanelState = it.debugPanelState.copy(
                    lastFinalText = result.finalText,
                ),
            )
        }
    }

    private fun pushTranscript(text: String, active: Boolean, replaceActive: Boolean) {
        val now = System.currentTimeMillis()
        _uiState.update { state ->
            val existingActiveIndex = state.transcriptItems.indexOfLast { it.isActive }
            val normalized = if (replaceActive) {
                state.transcriptItems.map { it.copy(isActive = false) }
            } else {
                state.transcriptItems
            }

            val updatedItems = if (replaceActive && existingActiveIndex >= 0) {
                normalized.mapIndexed { index, item ->
                    if (index == existingActiveIndex) {
                        item.copy(text = text, timestampMillis = now, isActive = active)
                    } else {
                        item
                    }
                }
            } else {
                normalized + TranscriptItem(
                    id = nextId++,
                    text = text,
                    timestampMillis = now,
                    isActive = active,
                )
            }

            state.copy(
                transcriptItems = updatedItems.filter { now - it.timestampMillis <= TRANSCRIPT_RETENTION_MILLIS },
            )
        }
    }

    private fun finalizeActiveTranscript() {
        _uiState.update { state ->
            state.copy(
                transcriptItems = state.transcriptItems.map { it.copy(isActive = false) },
            )
        }
    }

    private fun pruneOldTranscripts() {
        val now = System.currentTimeMillis()
        _uiState.update { state ->
            state.copy(
                transcriptItems = state.transcriptItems.filter {
                    now - it.timestampMillis <= TRANSCRIPT_RETENTION_MILLIS
                },
            )
        }
    }

    private fun pcmBytesToShortArray(bytes: ByteArray): ShortArray {
        val shorts = ShortArray(bytes.size / 2)
        for (i in shorts.indices) {
            shorts[i] = ((bytes[i * 2 + 1].toInt() shl 8) or (bytes[i * 2].toInt() and 0xff)).toShort()
        }
        return shorts
    }

    private companion object {
        val TRANSCRIPT_RETENTION_MILLIS = 30.seconds.inWholeMilliseconds
    }
}
