package com.hearglasses.app.asr

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineZipformer2CtcModelConfig
import kotlin.math.abs

data class RecognitionResult(
    val partialText: String = "",
    val finalText: String = "",
    val isEndpoint: Boolean = false,
)

data class SherpaModelConfig(
    val model: String,
    val tokens: String,
    val sampleRate: Int = 16_000,
    val featureDim: Int = 80,
    val numThreads: Int = 2,
    val decodingMethod: String = "greedy_search",
)

class SpeechRecognizerEngine(
    context: Context? = null,
    private val sherpaModelConfig: SherpaModelConfig? = null,
    private val vadThreshold: Float = 0.5f,
) {
    private val scriptedSentences = listOf(
        "哎，你听得清我说话吗？",
        "今天外面的天气真不错。",
        "中午想吃点什么呢？",
        "这副眼镜戴着还舒服吗？",
    )

    private val appContext = context?.applicationContext

    /** Precondition check (assets present). If not null → recognizer won't be created. */
    val initError: String? by lazy { checkPrerequisites() }

    /** Non-null if precondition passed but OnlineRecognizer constructor threw. */
    @Volatile
    var creationError: String? = null
        private set

    private val onlineRecognizer: OnlineRecognizer? by lazy {
        if (initError != null) {
            null
        } else {
            val modelConfig = sherpaModelConfig!!
            val am = appContext!!.assets
            runCatching {
                val rc = OnlineRecognizer(
                    assetManager = am,
                    config = OnlineRecognizerConfig(
                        featConfig = FeatureConfig(
                            sampleRate = modelConfig.sampleRate,
                            featureDim = modelConfig.featureDim,
                        ),
                        modelConfig = OnlineModelConfig(
                            zipformer2Ctc = OnlineZipformer2CtcModelConfig(model = modelConfig.model),
                            tokens = modelConfig.tokens,
                            numThreads = modelConfig.numThreads,
                        ),
                        endpointConfig = EndpointConfig(),
                        enableEndpoint = true,
                        decodingMethod = modelConfig.decodingMethod,
                        maxActivePaths = 4,
                    ),
                )
                Log.i(TAG, "Sherpa-onnx initialized OK: model=${modelConfig.model}")
                rc
            }.getOrElse { e ->
                val msg = "${e.javaClass.simpleName}: ${e.message}"
                Log.e(TAG, "Sherpa init failed: $msg", e)
                creationError = msg
                null
            }
        }
    }

    /** Full diagnostic: precondition error or creation error. */
    fun fullInitError(): String? = initError ?: creationError

    private fun checkPrerequisites(): String? {
        val modelConfig = sherpaModelConfig
        if (modelConfig == null) {
            return "SherpaModelConfig is null".also { Log.w(TAG, it) }
        }
        if (appContext == null) {
            return "App context is null".also { Log.w(TAG, it) }
        }
        val am = appContext.assets
        return try {
            am.open(modelConfig.model).use { it.read() }
            am.open(modelConfig.tokens).use { it.read() }
            null
        } catch (e: Exception) {
            "Asset missing: ${e.message}".also { Log.w(TAG, it) }
        }
    }

    private var onlineStream: OnlineStream? = null
    private var utteranceIndex = 0
    private var chunkCount = 0
    private var fallbackEnergy = 0f
    private var lastSherpaText = ""

    val modeLabel: String
        get() = if (onlineRecognizer != null) "Sherpa-onnx" else "Mock ASR"

    fun acceptWaveform(samples: ShortArray, audioSampleRate: Int = sherpaModelConfig?.sampleRate ?: DEFAULT_SAMPLE_RATE): RecognitionResult {
        if (samples.isEmpty()) {
            return RecognitionResult()
        }

        onlineRecognizer?.let { recognizer ->
            val stream = onlineStream ?: recognizer.createStream().also { onlineStream = it }
            val floatSamples = FloatArray(samples.size) { index -> samples[index] / 32768.0f }
            stream.acceptWaveform(floatSamples, audioSampleRate)
            while (recognizer.isReady(stream)) {
                recognizer.decode(stream)
            }
            val text = recognizer.getResult(stream).text.trim()
            val partial = if (text == lastSherpaText) "" else text
            lastSherpaText = text
            val isEndpoint = recognizer.isEndpoint(stream)
            return RecognitionResult(partialText = partial, isEndpoint = isEndpoint)
        }

        chunkCount += 1
        fallbackEnergy = samples.fold(0f) { acc, sample -> acc + abs(sample.toInt()) } / samples.size
        val sentence = scriptedSentences[utteranceIndex % scriptedSentences.size]
        val progressFactor = (fallbackEnergy / 4_000f).coerceIn(0.2f, 1f)
        val partialLength = (sentence.length * (chunkCount.coerceAtMost(4) / 4f) * progressFactor)
            .toInt()
            .coerceIn(1, sentence.length)
        return RecognitionResult(partialText = sentence.take(partialLength))
    }

    fun resetStream() {
        onlineStream?.release()
        onlineStream = null
        lastSherpaText = ""
    }

    fun forceFinalize(): RecognitionResult {
        onlineRecognizer?.let { recognizer ->
            val stream = onlineStream ?: return RecognitionResult()
            stream.inputFinished()
            while (recognizer.isReady(stream)) {
                recognizer.decode(stream)
            }
            val finalText = recognizer.getResult(stream).text.trim()
            val endpointReached = recognizer.isEndpoint(stream)
            stream.release()
            onlineStream = null
            lastSherpaText = ""
            return RecognitionResult(
                finalText = finalText,
                isEndpoint = endpointReached,
            )
        }

        if (chunkCount == 0) {
            return RecognitionResult()
        }

        val sentence = scriptedSentences[utteranceIndex % scriptedSentences.size]
        chunkCount = 0
        utteranceIndex += 1
        fallbackEnergy = 0f
        return RecognitionResult(finalText = sentence, isEndpoint = true)
    }

    private companion object {
        private const val TAG = "SpeechRecognizerEngine"
        private const val DEFAULT_SAMPLE_RATE = 16_000
    }
}
