package com.hearglasses.app.service

import android.content.Context
import android.os.BatteryManager
import com.hearglasses.app.asr.RecognitionResult
import com.hearglasses.app.asr.SpeechRecognizerEngine
import com.hearglasses.app.audio.OpusDecoder
import com.hearglasses.app.audio.PcmAudioPlayer
import com.hearglasses.app.audio.PcmAudioRecorder
import com.hearglasses.app.ble.BleConstants
import com.hearglasses.app.ble.BleEvent
import com.hearglasses.app.ble.BleManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.time.LocalTime
import java.time.format.DateTimeFormatter

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
    val bleReceivedPackets: Long = 0,
    val bleLostPackets: Long = 0,
    val peakAmplitude: Int = 0,
    val lastPartialText: String = "",
    val lastFinalText: String = "",
    val recordingInfo: String = "",
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
    context: Context,
    private val bleManager: BleManager,
    private val opusDecoder: OpusDecoder,
    private val pcmAudioPlayer: PcmAudioPlayer,
    private val pcmAudioRecorder: PcmAudioRecorder,
    private val speechRecognizerEngine: SpeechRecognizerEngine,
) {
    private val appContext = context.applicationContext
    private var nextId = 0L
    private var pollingJob: Job? = null
    private val controllerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val audioBufferLock = Any()
    private val pendingAudioChunks = ArrayDeque<ShortArray>()
    private var pendingAudioSampleCount = 0
    private var pendingAudioPacketCount = 0
    private var lastBleDisplayText = ""
    private var lastBleDisplayWriteMillis = 0L
    private var lastBleStatusWriteMillis = 0L

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
        if (bleManager.playIncomingPcm) {
            pcmAudioPlayer.start(bleManager.audioSampleRate)
        }
        pcmAudioRecorder.start(bleManager.audioSampleRate)
        lastBleDisplayText = ""
        lastBleDisplayWriteMillis = 0L
        lastBleStatusWriteMillis = 0L
        _uiState.update {
            it.copy(
                isListening = true,
                connectionText = "连接中",
                transcriptItems = emptyList(),
                debugPanelState = it.debugPanelState.copy(
                    packetCount = 0,
                    bleReceivedPackets = 0,
                    bleLostPackets = 0,
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
        _uiState.update {
            it.copy(
                isListening = false,
            )
        }
        stopPolling()
        bleManager.disconnect()
        pcmAudioPlayer.stop()
        pcmAudioRecorder.stop()
        finalizeActiveTranscript()
        syncBleState()
    }

    fun toggleListening() {
        if (_uiState.value.isListening) {
            stopListening()
        } else {
            startListening()
        }
    }

    private fun syncFromBle() {
        if (!_uiState.value.isListening) {
            return
        }
        syncBleState()

        val events = bleManager.consumeEvents()
        events.forEach { event ->
            when (event) {
                is BleEvent.AudioPacket -> {
                    appendAudioPacket(event.bytes)
                }
                is BleEvent.CommandPacket -> {
                    if (event.command == BleConstants.COMMAND_END_SPEECH) {
                        processReadyAudioChunks(force = true)
                        val result = speechRecognizerEngine.forceFinalize()
                        applyRecognitionResult(result)
                    }
                }
                is BleEvent.Error -> {
                    _uiState.update { it.copy(connectionText = event.message) }
                }
            }
        }
        processReadyAudioChunks(force = false)
        if (!_uiState.value.isListening) {
            return
        }
        syncBleState()
        writeDisplayStatus(force = false)
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
                    recordingInfo = pcmAudioRecorder.description(),
                    mtu = bleState.mtu,
                    bleReceivedPackets = bleState.receivedAudioPackets,
                    bleLostPackets = bleState.lostAudioPackets,
                ),
            )
        }
    }

    private fun startPolling() {
        pollingJob?.cancel()
        resetPendingAudio()
        pollingJob = controllerScope.launch {
            while (_uiState.value.isListening) {
                syncFromBle()
                delay(POLL_INTERVAL_MILLIS)
            }
        }
    }

    private fun stopPolling() {
        pollingJob?.cancel()
        pollingJob = null
        resetPendingAudio()
    }

    private fun applyRecognitionResult(result: RecognitionResult) {
        if (result.finalText.isBlank()) {
            return
        }
        pushTranscript(result.finalText, active = false, replaceActive = true)
        writeDisplayText(result.finalText, force = true)
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

    private fun appendAudioPacket(bytes: ByteArray) {
        val decoded = if (bleManager.isAudioPcm) {
            pcmBytesToShortArray(bytes)
        } else {
            opusDecoder.decode(bytes)
        }
        if (decoded.isEmpty()) {
            return
        }

        if (bleManager.playIncomingPcm) {
            pcmAudioPlayer.write(decoded)
        }
        pcmAudioRecorder.write(decoded)

        synchronized(audioBufferLock) {
            pendingAudioChunks.addLast(decoded)
            pendingAudioSampleCount += decoded.size
            pendingAudioPacketCount += 1
            dropStaleAudioBacklogLocked()
        }
    }

    private fun resetPendingAudio() {
        synchronized(audioBufferLock) {
            pendingAudioChunks.clear()
            pendingAudioSampleCount = 0
            pendingAudioPacketCount = 0
        }
    }

    private fun dropStaleAudioBacklogLocked() {
        while (pendingAudioSampleCount > MAX_AUDIO_BACKLOG_SAMPLES && pendingAudioChunks.isNotEmpty()) {
            val dropped = pendingAudioChunks.removeFirst()
            pendingAudioSampleCount -= dropped.size
        }
    }

    private fun processReadyAudioChunks(force: Boolean) {
        while (_uiState.value.isListening) {
            val nextChunk = synchronized(audioBufferLock) {
                if (pendingAudioSampleCount >= ASR_CHUNK_SAMPLES || (force && pendingAudioSampleCount > 0)) {
                    val sampleCount = if (force && pendingAudioSampleCount < ASR_CHUNK_SAMPLES) {
                        pendingAudioSampleCount
                    } else {
                        ASR_CHUNK_SAMPLES
                    }
                    val packetCount = pendingAudioPacketCount
                    pendingAudioPacketCount = 0
                    takeAudioSamplesLocked(sampleCount) to packetCount
                } else {
                    null
                }
            } ?: return

            processAudioChunk(nextChunk.first, nextChunk.second)
        }
    }

    private fun takeAudioSamplesLocked(sampleCount: Int): ShortArray {
        val output = ShortArray(sampleCount)
        var copied = 0
        while (copied < sampleCount && pendingAudioChunks.isNotEmpty()) {
            val chunk = pendingAudioChunks.removeFirst()
            val samplesToCopy = minOf(chunk.size, sampleCount - copied)
            chunk.copyInto(output, destinationOffset = copied, endIndex = samplesToCopy)
            copied += samplesToCopy

            if (samplesToCopy < chunk.size) {
                pendingAudioChunks.addFirst(chunk.copyOfRange(samplesToCopy, chunk.size))
            }
            pendingAudioSampleCount -= samplesToCopy
        }
        return output
    }

    private fun processAudioChunk(samples: ShortArray, packetCount: Int) {
        if (samples.isEmpty() || !_uiState.value.isListening) {
            return
        }

        val peak = samples.maxOfOrNull { kotlin.math.abs(it.toInt()) } ?: 0
        val result = speechRecognizerEngine.acceptWaveform(
            samples,
            audioSampleRate = bleManager.audioSampleRate,
        )
        if (!_uiState.value.isListening) {
            return
        }
        _uiState.update {
            it.copy(
                debugPanelState = it.debugPanelState.copy(
                    asrMode = speechRecognizerEngine.modeLabel,
                    asrInitError = speechRecognizerEngine.fullInitError() ?: "",
                    packetCount = it.debugPanelState.packetCount + packetCount,
                    peakAmplitude = maxOf(it.debugPanelState.peakAmplitude, peak),
                    lastPartialText = result.partialText,
                ),
            )
        }
        if (result.partialText.isNotBlank()) {
            pushTranscript(result.partialText, active = true, replaceActive = true)
            writeDisplayText(result.partialText, force = false)
        }
        if (result.isEndpoint) {
            val finalResult = speechRecognizerEngine.forceFinalize()
            if (finalResult.finalText.isNotBlank()) {
                applyRecognitionResult(finalResult)
            } else {
                finalizeActiveTranscript()
                commitDisplayText()
                speechRecognizerEngine.resetStream()
            }
        }
    }

    private fun writeDisplayText(text: String, force: Boolean) {
        val normalizedText = text.trim()
        if (normalizedText.isBlank()) {
            return
        }

        val now = System.currentTimeMillis()
        if (!force && (
                normalizedText == lastBleDisplayText ||
                    now - lastBleDisplayWriteMillis < PARTIAL_TEXT_WRITE_INTERVAL_MILLIS
                )
        ) {
            return
        }

        lastBleDisplayText = normalizedText
        lastBleDisplayWriteMillis = now
        val displayText = formatDisplayText(
            text = normalizedText,
            maxChars = if (force) MAX_FINAL_DISPLAY_TEXT_CHARS else MAX_PARTIAL_DISPLAY_TEXT_CHARS,
        )
        if (force) {
            writeDisplayTextChunks(displayText)
        } else {
            bleManager.writeText("$TEXT_PREFIX$displayText")
        }
    }

    private fun writeDisplayTextChunks(text: String) {
        val chunks = splitDisplayTextForBle(text)
        chunks.forEachIndexed { index, chunk ->
            val prefix = if (index == 0) FINAL_TEXT_PREFIX else APPEND_TEXT_PREFIX
            bleManager.writeText("$prefix$chunk")
            if (index < chunks.lastIndex) {
                Thread.sleep(TEXT_CHUNK_WRITE_GAP_MILLIS)
            }
        }
    }

    private fun splitDisplayTextForBle(text: String): List<String> {
        if (text.isBlank()) {
            return emptyList()
        }

        val maxChunkBytes = (bleManager.uiState.value.mtu - 3 - APPEND_TEXT_PREFIX.toByteArray(Charsets.UTF_8).size)
            .coerceIn(MIN_DISPLAY_TEXT_CHUNK_BYTES, MAX_DISPLAY_TEXT_CHUNK_BYTES)
        val chunks = mutableListOf<String>()
        val current = StringBuilder()
        var currentBytes = 0
        text.forEach { char ->
            val charBytes = char.toString().toByteArray(Charsets.UTF_8).size
            if (current.isNotEmpty() && currentBytes + charBytes > maxChunkBytes) {
                chunks += current.toString()
                current.clear()
                currentBytes = 0
            }
            current.append(char)
            currentBytes += charBytes
        }
        if (current.isNotEmpty()) {
            chunks += current.toString()
        }
        return chunks
    }

    private fun commitDisplayText() {
        if (lastBleDisplayText.isBlank()) {
            return
        }
        bleManager.writeText(COMMIT_TEXT_COMMAND)
        lastBleDisplayText = ""
    }

    private fun formatDisplayText(text: String, maxChars: Int): String {
        val output = StringBuilder()
        var lineUnits = 0

        text.take(maxChars).forEach { char ->
            if (char == '\n') {
                output.append(char)
                lineUnits = 0
                return@forEach
            }

            val charUnits = if (char.code <= 0x7f) 1 else 2
            if (lineUnits > 0 && lineUnits + charUnits > DISPLAY_LINE_UNITS) {
                output.append('\n')
                lineUnits = 0
            }
            output.append(char)
            lineUnits += charUnits
        }
        return output.toString()
    }

    private fun writeDisplayStatus(force: Boolean) {
        val bleState = bleManager.uiState.value
        if (!bleState.isConnected || bleState.statusText != "已连接") {
            return
        }

        val now = System.currentTimeMillis()
        if (!force && now - lastBleDisplayWriteMillis < STATUS_AFTER_TEXT_COOLDOWN_MILLIS) {
            return
        }
        if (!force && now - lastBleStatusWriteMillis < STATUS_WRITE_INTERVAL_MILLIS) {
            return
        }

        lastBleStatusWriteMillis = now
        bleManager.writeText("$STATUS_PREFIX${buildDisplayStatusText()}")
    }

    private fun buildDisplayStatusText(): String {
        val batteryText = readPhoneBatteryPercent()?.let { "$it%" } ?: "--%"
        val timeText = LocalTime.now().format(DISPLAY_TIME_FORMATTER)
        return "电量 $batteryText  $timeText"
    }

    private fun readPhoneBatteryPercent(): Int? {
        val batteryManager = appContext.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
            ?: return null
        return batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            .takeIf { it in 0..100 }
    }

    private companion object {
        const val TRANSCRIPT_RETENTION_MILLIS = 30_000L
        const val POLL_INTERVAL_MILLIS = 20L
        const val ASR_CHUNK_MILLIS = 100
        const val MAX_AUDIO_BACKLOG_MILLIS = 60_000
        const val ASR_CHUNK_SAMPLES = 16_000 * ASR_CHUNK_MILLIS / 1_000
        const val MAX_AUDIO_BACKLOG_SAMPLES = 16_000 * MAX_AUDIO_BACKLOG_MILLIS / 1_000
        const val MAX_PARTIAL_DISPLAY_TEXT_CHARS = 96
        const val MAX_FINAL_DISPLAY_TEXT_CHARS = 480
        const val MIN_DISPLAY_TEXT_CHUNK_BYTES = 8
        const val MAX_DISPLAY_TEXT_CHUNK_BYTES = 140
        const val TEXT_CHUNK_WRITE_GAP_MILLIS = 15L
        const val DISPLAY_LINE_UNITS = 24
        const val PARTIAL_TEXT_WRITE_INTERVAL_MILLIS = 2_000L
        const val STATUS_AFTER_TEXT_COOLDOWN_MILLIS = 1_000L
        const val STATUS_WRITE_INTERVAL_MILLIS = 60_000L
        const val STATUS_PREFIX = "@STATUS "
        const val TEXT_PREFIX = "@TEXT "
        const val FINAL_TEXT_PREFIX = "@TEXTF "
        const val APPEND_TEXT_PREFIX = "@TEXTA "
        const val COMMIT_TEXT_COMMAND = "@COMMIT"
        val DISPLAY_TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    }
}
