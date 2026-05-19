package com.hearglasses.app.di

import android.content.Context
import com.hearglasses.app.asr.SherpaModelConfig
import com.hearglasses.app.asr.SpeechRecognizerEngine
import com.hearglasses.app.audio.OpusDecoder
import com.hearglasses.app.audio.PcmAudioPlayer
import com.hearglasses.app.audio.PcmAudioRecorder
import com.hearglasses.app.ble.BleConstants
import com.hearglasses.app.ble.BleManager
import com.hearglasses.app.ble.FileBleManager
import com.hearglasses.app.ble.MicBleManager
import com.hearglasses.app.ble.RealBleManager
import com.hearglasses.app.logging.AppLogger
import com.hearglasses.app.service.HearGlassesController
import com.hearglasses.app.settings.GeekSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class DebugMode(val label: String) {
    REAL_BLE("硬件 BLE"),
    FILE("本地文件"),
    MIC("手机麦克风"),
}

class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    companion object {
        @Volatile
        private var instance: AppContainer? = null

        fun getInstance(context: Context): AppContainer {
            return instance ?: synchronized(this) {
                instance ?: AppContainer(context.applicationContext).also { instance = it }
            }
        }
    }

    private val _settings = MutableStateFlow(GeekSettings())
    val settings: StateFlow<GeekSettings> = _settings.asStateFlow()

    fun updateSettings(transform: (GeekSettings) -> GeekSettings) {
        _settings.value = transform(_settings.value)
    }

    val debugMode: DebugMode get() = _settings.value.debugMode

    /** The debug mode currently applied to the running bleManager/controller. */
    private var appliedDebugMode: DebugMode = _settings.value.debugMode

    var bleManager: BleManager = createBleManager(appliedDebugMode)
        private set

    var controller: HearGlassesController
        private set

    private fun createBleManager(mode: DebugMode): BleManager = when (mode) {
        DebugMode.FILE -> FileBleManager(appContext)
        DebugMode.REAL_BLE -> RealBleManager(
            context = appContext,
            config = BleConstants.defaultConfig,
        )
        DebugMode.MIC -> MicBleManager(appContext)
    }

    val opusDecoder = OpusDecoder(sampleRate = 16_000, channelCount = 1)
    val pcmAudioPlayer = PcmAudioPlayer()
    val pcmAudioRecorder = PcmAudioRecorder(appContext)
    val appLogger = AppLogger(appContext)

    private val sherpaModelConfig = SherpaModelConfig(
        model = "sherpa-onnx-streaming-zipformer-ctc-zh-int8-2025-06-30/model.int8.onnx",
        tokens = "sherpa-onnx-streaming-zipformer-ctc-zh-int8-2025-06-30/tokens.txt",
    )

    var speechRecognizerEngine: SpeechRecognizerEngine = createSpeechRecognizerEngine(_settings.value.vadThreshold)
        private set

    private fun createSpeechRecognizerEngine(vadThreshold: Float) = SpeechRecognizerEngine(
        context = appContext,
        sherpaModelConfig = sherpaModelConfig,
        vadThreshold = vadThreshold,
    )

    private val _generation = MutableStateFlow(0)
    val generation: StateFlow<Int> = _generation.asStateFlow()

    private fun createController() = HearGlassesController(
        context = appContext,
        bleManager = bleManager,
        opusDecoder = opusDecoder,
        pcmAudioPlayer = pcmAudioPlayer,
        pcmAudioRecorder = pcmAudioRecorder,
        speechRecognizerEngine = speechRecognizerEngine,
        appLogger = appLogger,
    )

    /**
     * Re-create the BLE manager and controller for a new audio source.
     * Call this before starting the service / listening.
     */
    fun switchAudioSource(mode: DebugMode) {
        bleManager.disconnect()
        pcmAudioPlayer.stop()
        pcmAudioRecorder.stop()
        bleManager = createBleManager(mode)
        speechRecognizerEngine = createSpeechRecognizerEngine(_settings.value.vadThreshold)
        controller = createController()
        appliedDebugMode = mode
        _generation.value++
    }

    /** Returns true if settings have changed since last apply. */
    fun isSettingsOutdated(): Boolean = _settings.value.debugMode != appliedDebugMode

    init {
        controller = createController()
    }
}
