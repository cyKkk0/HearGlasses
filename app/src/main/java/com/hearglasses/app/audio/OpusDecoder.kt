package com.hearglasses.app.audio

import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log

class OpusDecoder(
    private val sampleRate: Int,
    private val channelCount: Int,
) {
    private var codec: MediaCodec? = null
    private val bufferInfo = MediaCodec.BufferInfo()
    @Volatile private var initialized = false
    @Volatile private var codecError: String? = null

    init {
        initialize()
    }

    fun decode(packet: ByteArray): ShortArray {
        if (packet.isEmpty()) return ShortArray(0)
        if (!initialized) return ShortArray(0)

        val c = codec ?: return ShortArray(0)

        return try {
            // Queue input packet
            val inIdx = c.dequeueInputBuffer(0)
            if (inIdx >= 0) {
                val buf = c.getInputBuffer(inIdx)!!
                buf.clear()
                buf.put(packet)
                c.queueInputBuffer(inIdx, 0, packet.size, 0, 0)
            }

            // Collect all available output
            val result = mutableListOf<Short>()
            while (true) {
                val outIdx = c.dequeueOutputBuffer(bufferInfo, 0)
                when {
                    outIdx >= 0 -> {
                        val outBuf = c.getOutputBuffer(outIdx)!!
                        val shortBuf = ShortArray(bufferInfo.size / 2)
                        outBuf.asShortBuffer().also { it.position(0) }.get(shortBuf)
                        result.addAll(shortBuf.toList())
                        c.releaseOutputBuffer(outIdx, false)
                    }
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        continue // ignore, but check for next output
                    }
                    else -> break // no more output
                }
            }

            result.toShortArray()
        } catch (e: Exception) {
            Log.w(TAG, "Opus decode error: ${e.message}")
            ShortArray(0)
        }
    }

    fun reset() {
        releaseInternal()
        initialized = false
        codecError = null
    }

    fun description(): String = when {
        codecError != null -> "Opus error: $codecError"
        initialized -> "Opus-MediaCodec ${sampleRate}Hz/${channelCount}ch"
        else -> "Opus not initialized"
    }

    private fun initialize() {
        if (initialized) return
        try {
            codec = MediaCodec.createDecoderByType("audio/opus")
            val format = MediaFormat.createAudioFormat(
                "audio/opus", sampleRate, channelCount
            )
            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 1024 * 16)
            codec?.configure(format, null, null, 0)
            codec?.start()
            initialized = true
            Log.i(TAG, "Opus MediaCodec initialized: ${sampleRate}Hz / ${channelCount}ch")
        } catch (e: Exception) {
            codecError = "${e.javaClass.simpleName}: ${e.message}"
            Log.w(TAG, "Opus codec unavailable: $codecError")
            releaseInternal()
        }
    }

    private fun releaseInternal() {
        try {
            codec?.stop()
            codec?.release()
        } catch (_: Exception) {
        }
        codec = null
    }

    private companion object {
        private const val TAG = "OpusDecoder"
    }
}
