package com.hearglasses.app.audio

import android.content.Context
import android.os.Environment
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class PcmAudioRecorder(context: Context) {
    private val appContext = context.applicationContext
    private val writeQueue = LinkedBlockingQueue<ShortArray>()
    private var outputFile: File? = null
    private var outputStream: FileOutputStream? = null
    private var writerThread: Thread? = null
    @Volatile private var acceptingWrites = false
    private var sampleRate: Int = 0
    private var pcmDataBytes: Long = 0

    @Synchronized
    fun start(sampleRate: Int) {
        stop()

        val directory = File(
            appContext.getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: appContext.filesDir,
            RECORDINGS_DIR,
        )
        if (!directory.exists() && !directory.mkdirs()) {
            Log.w(TAG, "Unable to create recordings directory: ${directory.absolutePath}")
            return
        }

        val timestamp = LocalDateTime.now().format(FILE_TIME_FORMATTER)
        val file = File(directory, "HearGlasses_$timestamp.wav")
        runCatching {
            outputStream = FileOutputStream(file)
            outputStream?.write(ByteArray(WAV_HEADER_BYTES))
            outputFile = file
            this.sampleRate = sampleRate
            pcmDataBytes = 0
            writeQueue.clear()
            acceptingWrites = true
            writerThread = Thread(::writerLoop, "PcmAudioRecorder").apply {
                isDaemon = true
                start()
            }
            Log.i(TAG, "Recording started: ${file.absolutePath}")
        }.onFailure { error ->
            Log.w(TAG, "Recording start failed: ${error.message}", error)
            acceptingWrites = false
            outputStream = null
            outputFile = null
            this.sampleRate = 0
            pcmDataBytes = 0
        }
    }

    @Synchronized
    fun write(samples: ShortArray) {
        if (!acceptingWrites || outputStream == null) return
        if (samples.isEmpty()) return
        writeQueue.offer(samples.copyOf())
    }

    @Synchronized
    fun stop() {
        val thread = writerThread ?: return
        acceptingWrites = false
        thread.interrupt()
        runCatching {
            thread.join(STOP_JOIN_TIMEOUT_MILLIS)
        }.onFailure { error ->
            Log.w(TAG, "Recording thread join failed: ${error.message}", error)
        }
        writerThread = null
    }

    @Synchronized
    fun description(): String = outputFile?.absolutePath ?: "未录音"

    private fun writerLoop() {
        while (acceptingWrites || writeQueue.isNotEmpty()) {
            val samples = try {
                writeQueue.poll(100, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                null
            } ?: continue
            writeSamplesToFile(samples)
        }

        finishFile()
    }

    private fun writeSamplesToFile(samples: ShortArray) {
        val stream = outputStream ?: return

        val bytes = ByteArray(samples.size * BYTES_PER_SAMPLE)
        samples.forEachIndexed { index, sample ->
            val value = sample.toInt()
            bytes[index * 2] = (value and 0xff).toByte()
            bytes[index * 2 + 1] = ((value shr 8) and 0xff).toByte()
        }

        runCatching {
            stream.write(bytes)
            pcmDataBytes += bytes.size
        }.onFailure { error ->
            Log.w(TAG, "Recording write failed: ${error.message}", error)
        }
    }

    private fun finishFile() {
        val stream = outputStream ?: return
        val file = outputFile
        val rate = sampleRate
        val dataBytes = pcmDataBytes
        runCatching {
            stream.flush()
            stream.close()
            if (file != null && rate > 0) {
                writeWaveHeader(file, rate, dataBytes)
                Log.i(TAG, "Recording saved: ${file.absolutePath}")
            }
        }.onFailure { error ->
            Log.w(TAG, "Recording stop failed: ${error.message}", error)
        }

        outputStream = null
        outputFile = file
        sampleRate = 0
        pcmDataBytes = 0
    }

    private fun writeWaveHeader(file: File, sampleRate: Int, dataBytes: Long) {
        RandomAccessFile(file, "rw").use { wav ->
            wav.seek(0)
            wav.write(ascii("RIFF"))
            wav.writeIntLe((36L + dataBytes).coerceAtMost(UInt.MAX_VALUE.toLong()).toInt())
            wav.write(ascii("WAVE"))
            wav.write(ascii("fmt "))
            wav.writeIntLe(16)
            wav.writeShortLe(1)
            wav.writeShortLe(CHANNEL_COUNT)
            wav.writeIntLe(sampleRate)
            wav.writeIntLe(sampleRate * CHANNEL_COUNT * BYTES_PER_SAMPLE)
            wav.writeShortLe(CHANNEL_COUNT * BYTES_PER_SAMPLE)
            wav.writeShortLe(BITS_PER_SAMPLE)
            wav.write(ascii("data"))
            wav.writeIntLe(dataBytes.coerceAtMost(UInt.MAX_VALUE.toLong()).toInt())
        }
    }

    private fun RandomAccessFile.writeIntLe(value: Int) {
        write(value and 0xff)
        write((value shr 8) and 0xff)
        write((value shr 16) and 0xff)
        write((value shr 24) and 0xff)
    }

    private fun RandomAccessFile.writeShortLe(value: Int) {
        write(value and 0xff)
        write((value shr 8) and 0xff)
    }

    private fun ascii(value: String): ByteArray = value.toByteArray(Charsets.US_ASCII)

    private companion object {
        private const val TAG = "PcmAudioRecorder"
        private const val RECORDINGS_DIR = "recordings"
        private const val WAV_HEADER_BYTES = 44
        private const val STOP_JOIN_TIMEOUT_MILLIS = 5_000L
        private const val CHANNEL_COUNT = 1
        private const val BYTES_PER_SAMPLE = 2
        private const val BITS_PER_SAMPLE = 16
        private val FILE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")
    }
}
