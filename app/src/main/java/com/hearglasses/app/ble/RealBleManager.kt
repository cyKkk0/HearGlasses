package com.hearglasses.app.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.UUID

class RealBleManager(
    context: Context,
    private val config: BleConfig,
) : BleManager {
    private val appContext = context.applicationContext
    private val btManager = appContext.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = btManager.adapter
    private var bluetoothGatt: BluetoothGatt? = null
    private var audioCharacteristic: BluetoothGattCharacteristic? = null
    private var commandCharacteristic: BluetoothGattCharacteristic? = null
    private var textRxCharacteristic: BluetoothGattCharacteristic? = null

    // Thread-safe event queue
    private val eventQueue = ConcurrentLinkedQueue<BleEvent>()
    private val notificationQueueLock = Any()
    private val pendingNotificationCharacteristics = ArrayDeque<BluetoothGattCharacteristic>()
    private var expectedAudioPacketSequence: Int? = null
    private var receivedAudioPacketCount = 0L
    private var lostAudioPacketCount = 0L

    private val _uiState = MutableStateFlow(BleUiState())
    override val uiState: StateFlow<BleUiState> = _uiState.asStateFlow()

    override val modeLabel: String = "真实 BLE"
    override val isAudioPcm: Boolean = false
    override val audioSampleRate: Int get() = 16_000
    override val audioInfo: String get() = "Opus 16000Hz/1ch"
    override val playIncomingPcm: Boolean = false

    private var targetAddress: String? = null
    private var isScanning = false
    private var probingAddress: String? = null
    private val probedAddresses = mutableSetOf<String>()
    private var scanResultCount = 0

    @SuppressLint("MissingPermission")
    override fun connect() {
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) {
            emitError("蓝牙未开启")
            return
        }

        if (FIXED_DEVICE_ADDRESS.isNotBlank()) {
            targetAddress = FIXED_DEVICE_ADDRESS
            val device = bluetoothAdapter.getRemoteDevice(FIXED_DEVICE_ADDRESS)
            _uiState.update { it.copy(statusText = "直连中 ($FIXED_DEVICE_ADDRESS)") }
            connectToDevice(device)
            return
        }

        // If we have a previously connected address, try direct connect first
        targetAddress?.let { addr ->
            val device = bluetoothAdapter.getRemoteDevice(addr)
            _uiState.update { it.copy(statusText = "连接中 ($addr)") }
            connectToDevice(device)
            return
        }

        // Otherwise scan for the device
        startScan()
    }

    @SuppressLint("MissingPermission")
    override fun disconnect() {
        stopScan()
        bluetoothGatt?.disconnect()
        bluetoothGatt?.close()
        bluetoothGatt = null
        audioCharacteristic = null
        commandCharacteristic = null
        textRxCharacteristic = null
        expectedAudioPacketSequence = null
        synchronized(notificationQueueLock) {
            pendingNotificationCharacteristics.clear()
        }
        probingAddress = null
        probedAddresses.clear()
        _uiState.value = BleUiState(statusText = "已断开")
    }

    @SuppressLint("MissingPermission")
    override fun writeText(text: String) {
        if (text.isBlank()) return
        val char = textRxCharacteristic ?: run {
            Log.w(TAG, "textRxCharacteristic not available, cannot write: $text")
            return
        }
        val bytes = text.toByteArray(Charsets.UTF_8)
        // BLE MTU limits payload; chunk if needed (simple: single write up to MTU-3)
        val maxPayload = (_uiState.value.mtu - 3).coerceAtLeast(20)
        val payload = if (bytes.size > maxPayload) bytes.copyOf(maxPayload) else bytes
        char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        char.value = payload
        val success = bluetoothGatt?.writeCharacteristic(char) ?: false
        if (!success) {
            Log.w(TAG, "GATT writeCharacteristic returned false")
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

    // ── Scanning ──────────────────────────────────────────────────────

    @SuppressLint("MissingPermission")
    private fun startScan() {
        if (isScanning) return
        val scanner = bluetoothAdapter?.bluetoothLeScanner ?: run {
            emitError("不支持 BLE 扫描")
            return
        }

        _uiState.update { it.copy(statusText = "扫描中…") }
        probingAddress = null
        probedAddresses.clear()
        scanResultCount = 0

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setReportDelay(0)
            .build()

        isScanning = true
        try {
            scanner.startScan(null, settings, scanCallback)
        } catch (e: SecurityException) {
            isScanning = false
            emitError("缺少蓝牙扫描权限")
            Log.w(TAG, "BLE scan permission error", e)
            return
        } catch (e: Exception) {
            isScanning = false
            emitError("扫描启动失败: ${e.javaClass.simpleName}")
            Log.w(TAG, "BLE scan start error", e)
            return
        }

        // 15-second scan timeout
        appContext.mainLooper.let { looper ->
            android.os.Handler(looper).postDelayed({
                if (isScanning) {
                    stopScan()
                    _uiState.update { it.copy(statusText = "未找到 HearGlasses ($scanResultCount 个广播)") }
                }
            }, SCAN_TIMEOUT_MS)
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopScan() {
        isScanning = false
        bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback)
    }

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            scanResultCount++
            val label = result.scanRecord?.deviceName ?: device.name ?: device.address
            _uiState.update { it.copy(statusText = "扫描中: $scanResultCount 个, 最近 $label") }
            val matchedByAdvertisement = matchesTargetDevice(result)
            if (!matchedByAdvertisement && !shouldProbeDevice(device)) {
                return
            }

            stopScan()
            targetAddress = device.address
            probingAddress = if (matchedByAdvertisement) null else device.address
            _uiState.update {
                val prefix = if (matchedByAdvertisement) "连接中" else "探测设备"
                it.copy(statusText = "$prefix ($label)")
            }
            connectToDevice(device)
        }

        override fun onScanFailed(errorCode: Int) {
            isScanning = false
            val msg = when (errorCode) {
                ScanCallback.SCAN_FAILED_ALREADY_STARTED -> "扫描已在运行"
                ScanCallback.SCAN_FAILED_APPLICATION_REGISTRATION_FAILED -> "扫描注册失败"
                ScanCallback.SCAN_FAILED_FEATURE_UNSUPPORTED -> "不支持 BLE 扫描"
                else -> "扫描失败 (code=$errorCode)"
            }
            emitError(msg)
        }
    }

    @SuppressLint("MissingPermission")
    private fun matchesTargetDevice(result: ScanResult): Boolean {
        val advertisedName = result.scanRecord?.deviceName
        val deviceName = result.device.name
        val serviceMatches = result.scanRecord
            ?.serviceUuids
            ?.any { it.uuid == config.serviceUuid } == true
        val nameMatches = advertisedName == DEVICE_NAME || deviceName == DEVICE_NAME

        if (serviceMatches || nameMatches) {
            Log.i(
                TAG,
                "Matched BLE device name=${advertisedName ?: deviceName}, " +
                    "address=${result.device.address}, serviceMatches=$serviceMatches",
            )
            return true
        }
        return false
    }

    private fun shouldProbeDevice(device: BluetoothDevice): Boolean {
        if (!PROBE_UNMATCHED_SCAN_RESULTS) return false
        if (probingAddress != null) return false
        if (probedAddresses.contains(device.address)) return false
        probedAddresses += device.address
        return true
    }

    // ── GATT Connection ───────────────────────────────────────────────

    @SuppressLint("MissingPermission")
    private fun connectToDevice(device: BluetoothDevice) {
        // Close any previous GATT
        bluetoothGatt?.close()
        bluetoothGatt = device.connectGatt(appContext, false, gattCallback)
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.w(TAG, "Connection state change error: status=$status")
                bluetoothGatt?.close()
                bluetoothGatt = null
                val wasProbing = probingAddress != null
                probingAddress = null
                _uiState.update { it.copy(isConnected = false, statusText = if (wasProbing) "继续扫描…" else "连接失败") }
                if (wasProbing) {
                    startScan()
                }
                return
            }
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    bluetoothGatt = gatt
                    _uiState.update { it.copy(isConnected = true, statusText = "发现服务中") }
                    gatt.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    val wasProbing = probingAddress != null
                    bluetoothGatt = null
                    audioCharacteristic = null
                    commandCharacteristic = null
                    textRxCharacteristic = null
                    expectedAudioPacketSequence = null
                    synchronized(notificationQueueLock) {
                        pendingNotificationCharacteristics.clear()
                    }
                    probingAddress = null
                    _uiState.update { it.copy(isConnected = false, statusText = if (wasProbing) "继续扫描…" else "已断开") }
                    if (wasProbing) {
                        startScan()
                    }
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.w(TAG, "Service discovery failed: status=$status")
                _uiState.update { it.copy(statusText = "服务发现失败") }
                return
            }

            val service = gatt.getService(config.serviceUuid)
            if (service == null) {
                Log.w(TAG, "Target service ${config.serviceUuid} not found")
                val wasProbing = probingAddress != null
                probingAddress = null
                bluetoothGatt?.disconnect()
                bluetoothGatt?.close()
                bluetoothGatt = null
                _uiState.update { it.copy(isConnected = false, statusText = if (wasProbing) "继续扫描…" else "服务不匹配") }
                if (wasProbing) {
                    startScan()
                }
                return
            }

            probingAddress = null
            targetAddress = gatt.device.address
            expectedAudioPacketSequence = null
            receivedAudioPacketCount = 0L
            lostAudioPacketCount = 0L
            audioCharacteristic = service.getCharacteristic(config.audioTxUuid)
            commandCharacteristic = service.getCharacteristic(config.commandTxUuid)
            textRxCharacteristic = service.getCharacteristic(config.textRxUuid)
            _uiState.update { it.copy(receivedAudioPackets = 0, lostAudioPackets = 0) }

            Log.i(TAG, "Services discovered. audioChar=${audioCharacteristic != null}, " +
                "cmdChar=${commandCharacteristic != null}, textRx=${textRxCharacteristic != null}")

            // Request MTU upgrade. If the request cannot be queued, still enable
            // notifications at the default MTU so audio can start.
            if (!gatt.requestMtu(config.mtuRequest)) {
                Log.w(TAG, "requestMtu returned false")
                enableNotificationsSequentially(listOfNotNull(audioCharacteristic, commandCharacteristic))
            }
        }

        @SuppressLint("MissingPermission")
        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            _uiState.update { it.copy(mtu = mtu) }
            Log.i(TAG, "MTU changed: $mtu")

            // After MTU negotiation, enable notifications
            enableNotificationsSequentially(listOfNotNull(audioCharacteristic, commandCharacteristic))
        }

        @SuppressLint("MissingPermission")
        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int,
        ) {
            val char = descriptor.characteristic
            val enabled = status == BluetoothGatt.GATT_SUCCESS &&
                descriptor.value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            when (char.uuid) {
                config.audioTxUuid -> {
                    Log.i(TAG, "Audio notification enabled: $enabled")
                }
                config.commandTxUuid -> {
                    Log.i(TAG, "Command notification enabled: $enabled")
                }
            }
            writeNextNotificationDescriptor()
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            val value = characteristic.value ?: return
            handleCharacteristicChanged(characteristic.uuid, value)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            handleCharacteristicChanged(characteristic.uuid, value)
        }
    }

    @SuppressLint("MissingPermission")
    private fun enableNotificationsSequentially(characteristics: List<BluetoothGattCharacteristic>) {
        synchronized(notificationQueueLock) {
            pendingNotificationCharacteristics.clear()
            pendingNotificationCharacteristics.addAll(characteristics)
        }
        writeNextNotificationDescriptor()
    }

    @SuppressLint("MissingPermission")
    private fun writeNextNotificationDescriptor() {
        val gatt = bluetoothGatt ?: return
        val characteristic = synchronized(notificationQueueLock) {
            pendingNotificationCharacteristics.removeFirstOrNull()
        } ?: run {
            _uiState.update { it.copy(statusText = "已连接") }
            return
        }

        if (!gatt.setCharacteristicNotification(characteristic, true)) {
            Log.w(TAG, "setCharacteristicNotification failed for ${characteristic.uuid}")
            writeNextNotificationDescriptor()
            return
        }

        val descriptor = characteristic.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG_UUID) ?: run {
            Log.w(TAG, "CCCD missing for ${characteristic.uuid}")
            writeNextNotificationDescriptor()
            return
        }
        descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        if (!gatt.writeDescriptor(descriptor)) {
            Log.w(TAG, "writeDescriptor returned false for ${characteristic.uuid}")
            writeNextNotificationDescriptor()
        }
    }

    private fun handleCharacteristicChanged(uuid: UUID, value: ByteArray) {
        when (uuid) {
            config.audioTxUuid -> {
                val payload = stripAudioPacketHeader(value)
                if (payload.isNotEmpty()) {
                    eventQueue += BleEvent.AudioPacket(payload)
                }
            }
            config.commandTxUuid -> {
                val command = value.firstOrNull() ?: return
                eventQueue += BleEvent.CommandPacket(command)
            }
        }
    }

    private fun stripAudioPacketHeader(value: ByteArray): ByteArray {
        if (value.size >= AUDIO_PACKET_HEADER_BYTES &&
            value[0] == AUDIO_PACKET_MAGIC_0 &&
            value[1] == AUDIO_PACKET_MAGIC_1
        ) {
            val sequence = (value[2].toInt() and 0xff) or ((value[3].toInt() and 0xff) shl 8)
            val expected = expectedAudioPacketSequence
            if (expected != null && sequence != expected) {
                val lost = (sequence - expected) and 0xffff
                if (lost > 0) {
                    lostAudioPacketCount += lost
                    publishAudioPacketCounters()
                    Log.w(TAG, "Audio packet gap: expected=$expected actual=$sequence lost=$lost")
                }
            }
            expectedAudioPacketSequence = (sequence + 1) and 0xffff
            receivedAudioPacketCount += 1
            if (receivedAudioPacketCount % AUDIO_COUNTER_PUBLISH_INTERVAL == 0L) {
                publishAudioPacketCounters()
            }
            return value.copyOfRange(AUDIO_PACKET_HEADER_BYTES, value.size)
        }

        receivedAudioPacketCount += 1
        if (receivedAudioPacketCount % AUDIO_COUNTER_PUBLISH_INTERVAL == 0L) {
            publishAudioPacketCounters()
        }
        return value.copyOf()
    }

    private fun publishAudioPacketCounters() {
        _uiState.update {
            it.copy(
                receivedAudioPackets = receivedAudioPacketCount,
                lostAudioPackets = lostAudioPacketCount,
            )
        }
    }

    private fun emitError(message: String) {
        eventQueue += BleEvent.Error(message)
        _uiState.update { it.copy(statusText = message) }
    }

    private companion object {
        private const val TAG = "RealBleManager"
        private const val DEVICE_NAME = "HearGlasses"
        private const val FIXED_DEVICE_ADDRESS = "14:C1:9F:26:C5:61"
        private const val SCAN_TIMEOUT_MS = 45_000L
        private const val PROBE_UNMATCHED_SCAN_RESULTS = true
        private const val AUDIO_PACKET_HEADER_BYTES = 4
        private const val AUDIO_COUNTER_PUBLISH_INTERVAL = 32L
        private const val AUDIO_PACKET_MAGIC_0: Byte = 0x48 // H
        private const val AUDIO_PACKET_MAGIC_1: Byte = 0x47 // G

        // Standard CCCD UUID for enabling BLE notifications
        private val CLIENT_CHARACTERISTIC_CONFIG_UUID =
            java.util.UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}
