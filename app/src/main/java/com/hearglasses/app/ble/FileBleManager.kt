package com.hearglasses.app.ble

import android.content.Context
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class PcmAudioFile(
    val pcmBytes: ByteArray,
    val sampleRate: Int,
    val channelCount: Int,
    val bitsPerSample: Int,
)

class FileBleManager(
    context: Context,
) : BleManager {
    private val appContext = context.applicationContext
    private val _uiState = MutableStateFlow(BleUiState())
    override val uiState: StateFlow<BleUiState> = _uiState.asStateFlow()

    override val modeLabel: String = "File 音频"
    override val isAudioPcm: Boolean = true
    override val audioSampleRate: Int get() = audioFile?.sampleRate ?: 16_000
    override val audioInfo: String get() {
        val f = audioFile ?: return ""
        return "${f.sampleRate}Hz / ${f.channelCount}ch / ${f.bitsPerSample}bit"
    }

    private val audioFile: PcmAudioFile? by lazy {
        runCatching {
            appContext.assets.open(FILE_NAME).use { inputStream ->
                parseWaveFile(inputStream.readBytes())
            }
        }.getOrNull()
    }

    private val audioFrames: List<ByteArray> by lazy {
        val file = audioFile ?: return@lazy emptyList()
        val bytesPerSample = file.bitsPerSample / 8
        val frameSize = (file.sampleRate * FRAME_DURATION_MS / 1000) * file.channelCount * bytesPerSample
        if (frameSize <= 0) {
            emptyList()
        } else {
            buildList {
                var start = 0
                while (start < file.pcmBytes.size) {
                    val end = minOf(start + frameSize, file.pcmBytes.size)
                    add(file.pcmBytes.copyOfRange(start, end))
                    start = end
                }
            }
        }
    }

    private var isStreaming = false
    private var frameIndex = 0
    private var startCommandPending = false
    private var endCommandPending = false
    private val writes = mutableListOf<String>()

    override fun connect() {
        val hasAudio = audioFrames.isNotEmpty()
        _uiState.value = BleUiState(
            isConnected = hasAudio,
            statusText = if (hasAudio) "已连接(File)" else "音频文件缺失",
            batteryText = audioFile?.let { "${it.sampleRate}Hz" } ?: "--",
            mtu = 512,
        )
        isStreaming = hasAudio
        frameIndex = 0
        startCommandPending = hasAudio
        endCommandPending = false
    }

    override fun disconnect() {
        _uiState.value = BleUiState(statusText = "已断开(File)")
        isStreaming = false
        frameIndex = 0
        startCommandPending = false
        endCommandPending = false
    }

    override fun writeText(text: String) {
        if (text.isBlank()) {
            return
        }
        writes += text
    }

    override fun consumeEvents(): List<BleEvent> {
        if (!isStreaming) {
            return emptyList()
        }

        val events = mutableListOf<BleEvent>()

        if (startCommandPending) {
            startCommandPending = false
            events += BleEvent.CommandPacket(BleConstants.COMMAND_START_SPEECH)
        }

        var consumed = 0
        while (frameIndex < audioFrames.size && consumed < MAX_FRAMES_PER_POLL) {
            val frame = audioFrames[frameIndex]
            frameIndex += 1
            consumed += 1
            events += BleEvent.AudioPacket(frame)
        }

        if (frameIndex >= audioFrames.size && !startCommandPending) {
            endCommandPending = true
        }

        if (endCommandPending && events.none { it is BleEvent.AudioPacket } &&
            frameIndex >= audioFrames.size
        ) {
            endCommandPending = false
            frameIndex = 0
            startCommandPending = true
            events += BleEvent.CommandPacket(BleConstants.COMMAND_END_SPEECH)
        }

        return events
    }

    fun sentTexts(): List<String> = writes.toList()

    private fun parseWaveFile(bytes: ByteArray): PcmAudioFile {
        require(bytes.size >= 44) { "Wave file too small" }
        require(readAscii(bytes, 0, 4) == "RIFF") { "Invalid wave header" }
        require(readAscii(bytes, 8, 4) == "WAVE") { "Invalid wave format" }

        var offset = 12
        var sampleRate = 16_000
        var channelCount = 1
        var bitsPerSample = 16
        var audioData: ByteArray? = null

        while (offset + 8 <= bytes.size) {
            val chunkId = readAscii(bytes, offset, 4)
            val chunkSize = readLittleEndianInt(bytes, offset + 4)
            val chunkDataOffset = offset + 8
            val chunkEnd = (chunkDataOffset + chunkSize).coerceAtMost(bytes.size)

            when (chunkId) {
                "fmt " -> {
                    val audioFormat = readLittleEndianShort(bytes, chunkDataOffset)
                    require(audioFormat == 1) { "Only PCM wave is supported" }
                    channelCount = readLittleEndianShort(bytes, chunkDataOffset + 2)
                    sampleRate = readLittleEndianInt(bytes, chunkDataOffset + 4)
                    bitsPerSample = readLittleEndianShort(bytes, chunkDataOffset + 14)
                    require(bitsPerSample == 16) { "Only 16-bit PCM is supported" }
                }
                "data" -> {
                    audioData = bytes.copyOfRange(chunkDataOffset, chunkEnd)
                }
            }

            offset = chunkEnd + (chunkSize % 2)
        }

        return PcmAudioFile(
            pcmBytes = audioData ?: error("Wave data chunk missing"),
            sampleRate = sampleRate,
            channelCount = channelCount,
            bitsPerSample = bitsPerSample,
        )
    }

    private fun readAscii(bytes: ByteArray, offset: Int, length: Int): String =
        String(bytes, offset, length, StandardCharsets.US_ASCII)

    private fun readLittleEndianInt(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or
            ((bytes[offset + 1].toInt() and 0xff) shl 8) or
            ((bytes[offset + 2].toInt() and 0xff) shl 16) or
            ((bytes[offset + 3].toInt() and 0xff) shl 24)

    private fun readLittleEndianShort(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8)

    private companion object {
        private const val FILE_NAME = "sample_audio.wav"
        private const val FRAME_DURATION_MS = 20
        private const val MAX_FRAMES_PER_POLL = 50
    }
}
