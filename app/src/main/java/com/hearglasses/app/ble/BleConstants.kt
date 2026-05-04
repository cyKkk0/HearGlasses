package com.hearglasses.app.ble

import java.util.UUID

data class BleConfig(
    val serviceUuid: UUID,
    val audioTxUuid: UUID,
    val commandTxUuid: UUID,
    val textRxUuid: UUID,
    val mtuRequest: Int,
)

object BleConstants {
    private fun uuid16(shortCode: String): UUID =
        UUID.fromString("0000$shortCode-0000-1000-8000-00805f9b34fb")

    val defaultConfig = BleConfig(
        serviceUuid = uuid16("18FD"),
        audioTxUuid = uuid16("2A3D"),
        commandTxUuid = uuid16("2A3E"),
        textRxUuid = uuid16("2A3F"),
        mtuRequest = 512,
    )

    const val COMMAND_START_SPEECH: Byte = 0x01
    const val COMMAND_END_SPEECH: Byte = 0x00
}
