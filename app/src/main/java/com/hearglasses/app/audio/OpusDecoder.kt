package com.hearglasses.app.audio

import android.util.Log
import io.github.jaredmdobson.concentus.OpusDecoder as ConcentusOpusDecoder

class OpusDecoder(
    private val sampleRate: Int,
    private val channelCount: Int,
) {
    private var decoder: ConcentusOpusDecoder? = null
    private val maxDecodedSamples = sampleRate * MAX_FRAME_MILLIS / 1_000 * channelCount
    @Volatile private var initialized = false
    @Volatile private var codecError: String? = null

    init {
        initialize()
    }

    @Synchronized
    fun decode(packet: ByteArray): ShortArray {
        if (packet.isEmpty()) return ShortArray(0)
        val d = decoder ?: return ShortArray(0)

        return try {
            val output = ShortArray(maxDecodedSamples)
            val decodedSamples = d.decode(
                packet,
                0,
                packet.size,
                output,
                0,
                maxDecodedSamples / channelCount,
                false,
            )
            output.copyOf(decodedSamples * channelCount)
        } catch (e: Exception) {
            codecError = "${e.javaClass.simpleName}: ${e.message}"
            Log.w(TAG, "Opus decode error: $codecError")
            ShortArray(0)
        }
    }

    @Synchronized
    fun reset() {
        initialized = false
        codecError = null
        initialize()
    }

    fun description(): String = when {
        codecError != null -> "Opus error: $codecError"
        initialized -> "Opus-Concentus ${sampleRate}Hz/${channelCount}ch"
        else -> "Opus not initialized"
    }

    private fun initialize() {
        if (initialized) return
        try {
            decoder = ConcentusOpusDecoder(sampleRate, channelCount)
            initialized = true
            Log.i(TAG, "Opus Concentus initialized: ${sampleRate}Hz / ${channelCount}ch")
        } catch (e: Exception) {
            codecError = "${e.javaClass.simpleName}: ${e.message}"
            Log.w(TAG, "Opus decoder unavailable: $codecError")
            decoder = null
            initialized = false
        }
    }

    private companion object {
        private const val TAG = "OpusDecoder"
        private const val MAX_FRAME_MILLIS = 120
    }
}
