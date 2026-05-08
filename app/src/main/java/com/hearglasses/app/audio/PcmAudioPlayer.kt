package com.hearglasses.app.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Process
import android.util.Log
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

class PcmAudioPlayer {
    private var audioTrack: AudioTrack? = null
    private var currentSampleRate: Int = 0
    private var playbackThread: Thread? = null
    @Volatile private var running = false

    private val queue = ArrayBlockingQueue<ShortArray>(QUEUE_CAPACITY)

    @Synchronized
    fun start(sampleRate: Int) {
        if (audioTrack != null && currentSampleRate == sampleRate && running) return

        stop()
        queue.clear()

        val minBufferSize = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBufferSize <= 0) {
            Log.w(TAG, "Invalid AudioTrack buffer size: $minBufferSize")
            return
        }

        val format = AudioFormat.Builder()
            .setSampleRate(sampleRate)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .build()

        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .setLegacyStreamType(AudioManager.STREAM_MUSIC)
                    .build(),
            )
            .setAudioFormat(format)
            .setBufferSizeInBytes(maxOf(minBufferSize * BUFFER_MULTIPLIER, sampleRate))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        currentSampleRate = sampleRate
        running = true
        playbackThread = Thread(::playbackLoop, "PcmAudioPlayer").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    fun write(samples: ShortArray) {
        if (samples.isEmpty() || !running) return
        val copy = samples.copyOf()
        if (!queue.offer(copy)) {
            queue.poll()
            queue.offer(copy)
        }
    }

    @Synchronized
    fun stop() {
        running = false
        playbackThread?.interrupt()
        playbackThread = null
        queue.clear()

        val track = audioTrack ?: return
        try {
            track.pause()
            track.flush()
            track.release()
        } catch (e: Exception) {
            Log.w(TAG, "AudioTrack release error: ${e.message}")
        } finally {
            audioTrack = null
            currentSampleRate = 0
        }
    }

    fun description(): String = if (audioTrack != null) {
        "PCM playback ${currentSampleRate}Hz/mono buffered"
    } else {
        "PCM playback idle"
    }

    private fun playbackLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
        val track = audioTrack ?: return
        track.play()

        // Add a small startup jitter buffer so BLE notification bursts do not
        // immediately underrun the speaker.
        while (running && queue.size < STARTUP_QUEUE_FRAMES) {
            sleepQuietly(5)
        }

        while (running) {
            val samples = try {
                queue.poll(100, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                null
            }

            if (samples == null) {
                continue
            }

            var offset = 0
            while (running && offset < samples.size) {
                val written = track.write(
                    samples,
                    offset,
                    samples.size - offset,
                    AudioTrack.WRITE_BLOCKING,
                )
                if (written <= 0) {
                    break
                }
                offset += written
            }
        }
    }

    private fun sleepQuietly(millis: Long) {
        try {
            Thread.sleep(millis)
        } catch (_: InterruptedException) {
        }
    }

    private companion object {
        private const val TAG = "PcmAudioPlayer"
        private const val BUFFER_MULTIPLIER = 8
        private const val QUEUE_CAPACITY = 80
        private const val STARTUP_QUEUE_FRAMES = 8
    }
}
