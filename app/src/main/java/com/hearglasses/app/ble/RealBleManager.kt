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
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.ConcurrentLinkedQueue

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

    private val _uiState = MutableStateFlow(BleUiState())
    override val uiState: StateFlow<BleUiState> = _uiState.asStateFlow()

    override val modeLabel: String = "真实 BLE"
    override val isAudioPcm: Boolean = false
    override val audioSampleRate: Int get() = 16_000
    override val audioInfo: String get() = "Opus 16kHz/1ch"

    private var targetAddress: String? = null
    private var isScanning = false

    @SuppressLint("MissingPermission")
    override fun connect() {
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) {
            emitError("蓝牙未开启")
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

        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(config.serviceUuid))
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        isScanning = true
        scanner.startScan(listOf(filter), settings, scanCallback)

        // 15-second scan timeout
        appContext.mainLooper.let { looper ->
            android.os.Handler(looper).postDelayed({
                if (isScanning) {
                    stopScan()
                    _uiState.update { it.copy(statusText = "未找到设备") }
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
            stopScan()
            targetAddress = device.address
            _uiState.update { it.copy(statusText = "连接中 (${device.name ?: device.address})") }
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
                _uiState.update { it.copy(isConnected = false, statusText = "连接失败") }
                return
            }
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    bluetoothGatt = gatt
                    _uiState.update { it.copy(isConnected = true, statusText = "发现服务中") }
                    gatt.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    bluetoothGatt = null
                    audioCharacteristic = null
                    commandCharacteristic = null
                    textRxCharacteristic = null
                    _uiState.update { it.copy(isConnected = false, statusText = "已断开") }
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
                _uiState.update { it.copy(statusText = "服务不匹配") }
                return
            }

            audioCharacteristic = service.getCharacteristic(config.audioTxUuid)
            commandCharacteristic = service.getCharacteristic(config.commandTxUuid)
            textRxCharacteristic = service.getCharacteristic(config.textRxUuid)

            Log.i(TAG, "Services discovered. audioChar=${audioCharacteristic != null}, " +
                "cmdChar=${commandCharacteristic != null}, textRx=${textRxCharacteristic != null}")

            // Request MTU upgrade
            gatt.requestMtu(config.mtuRequest)
        }

        @SuppressLint("MissingPermission")
        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            _uiState.update { it.copy(mtu = mtu) }
            Log.i(TAG, "MTU changed: $mtu")

            // After MTU negotiation, enable notifications
            enableNotification(audioCharacteristic)
            enableNotification(commandCharacteristic)
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
                    if (enabled) {
                        _uiState.update { it.copy(statusText = "已连接") }
                    }
                }
                config.commandTxUuid -> {
                    Log.i(TAG, "Command notification enabled: $enabled")
                }
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            val value = characteristic.value ?: return
            when (characteristic.uuid) {
                config.audioTxUuid -> {
                    eventQueue += BleEvent.AudioPacket(value)
                }
                config.commandTxUuid -> {
                    val command = value.firstOrNull() ?: return
                    eventQueue += BleEvent.CommandPacket(command)
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun enableNotification(characteristic: BluetoothGattCharacteristic?) {
        if (characteristic == null) return
        val gatt = bluetoothGatt ?: return
        gatt.setCharacteristicNotification(characteristic, true)
        val descriptor = characteristic.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG_UUID) ?: return
        descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        gatt.writeDescriptor(descriptor)
    }

    private fun emitError(message: String) {
        eventQueue += BleEvent.Error(message)
        _uiState.update { it.copy(statusText = message) }
    }

    private companion object {
        private const val TAG = "RealBleManager"
        private const val SCAN_TIMEOUT_MS = 15_000L

        // Standard CCCD UUID for enabling BLE notifications
        private val CLIENT_CHARACTERISTIC_CONFIG_UUID =
            java.util.UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}
