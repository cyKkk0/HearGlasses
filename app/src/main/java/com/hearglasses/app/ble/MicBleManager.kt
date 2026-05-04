package com.hearglasses.app.ble

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.ConcurrentLinkedQueue

class MicBleManager(
    context: Context,
) : BleManager {
    private val appContext = context.applicationContext
    private val eventQueue = ConcurrentLinkedQueue<BleEvent>()

    private val _uiState = MutableStateFlow(BleUiState())
    override val uiState: StateFlow<BleUiState> = _uiState.asStateFlow()

    override val modeLabel: String = "手机麦克风"
    override val isAudioPcm: Boolean = true
    override val audioSampleRate: Int = SAMPLE_RATE
    override val audioInfo: String = "${SAMPLE_RATE}Hz/1ch/16bit"

    @Volatile private var isCapturing = false
    private var audioRecord: AudioRecord? = null
    private var captureThread: Thread? = null

    override fun connect() {
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            emitError("缺少录音权限")
            return
        }

        val bufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (bufferSize == AudioRecord.ERROR || bufferSize == AudioRecord.ERROR_BAD_VALUE) {
            emitError("录音初始化失败")
            return
        }

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize.coerceAtLeast(FRAME_SIZE_BYTES * 4),
            )
        } catch (e: SecurityException) {
            emitError("录音权限被拒绝")
            return
        }

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            emitError("AudioRecord 不可用")
            audioRecord?.release()
            audioRecord = null
            return
        }

        audioRecord?.startRecording()
        isCapturing = true

        _uiState.value = BleUiState(
            isConnected = true,
            statusText = "已连接(Mic)",
            batteryText = "有线",
            mtu = 0,
        )

        startCaptureLoop()
    }

    override fun disconnect() {
        isCapturing = false
        captureThread?.interrupt()
        captureThread = null

        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (_: Exception) {
        }
        audioRecord = null

        _uiState.value = BleUiState(statusText = "已断开(Mic)")
        eventQueue.clear()
    }

    override fun writeText(text: String) {
        // Mic mode doesn't write back to device; log for debugging
        if (text.isNotBlank()) {
            Log.d(TAG, "Recognized: $text")
        }
    }

    override fun consumeEvents(): List<BleEvent> {
        val events = mutableListOf<BleEvent>()
        while (true) {
            val event = eventQueue.poll() ?: break
            events += event
        }
        return events
    }

    private fun startCaptureLoop() {
        captureThread = Thread {
            val buffer = ByteArray(FRAME_SIZE_BYTES)
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)

            while (isCapturing) {
                val record = audioRecord ?: break
                val read = try {
                    record.read(buffer, 0, FRAME_SIZE_BYTES)
                } catch (e: Exception) {
                    Log.w(TAG, "AudioRecord read error: ${e.message}")
                    -1
                }

                if (read > 0) {
                    // Always copy — the buffer will be reused on the next read
                    eventQueue += BleEvent.AudioPacket(buffer.copyOf(read))
                } else if (read == AudioRecord.ERROR_INVALID_OPERATION ||
                    read == AudioRecord.ERROR_DEAD_OBJECT
                ) {
                    break
                }
            }
        }.apply {
            name = "MicCapture"
            start()
        }
    }

    private fun emitError(message: String) {
        eventQueue += BleEvent.Error(message)
        _uiState.update { it.copy(statusText = message) }
    }

    private companion object {
        private const val TAG = "MicBleManager"
        private const val SAMPLE_RATE = 16_000
        private const val FRAME_DURATION_MS = 20
        private const val FRAME_SIZE_SAMPLES = SAMPLE_RATE * FRAME_DURATION_MS / 1000 // 320
        private const val FRAME_SIZE_BYTES = FRAME_SIZE_SAMPLES * 2 // 640
    }
}
