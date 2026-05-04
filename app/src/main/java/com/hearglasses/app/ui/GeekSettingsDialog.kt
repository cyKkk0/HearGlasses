package com.hearglasses.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.hearglasses.app.di.DebugMode
import com.hearglasses.app.settings.GeekSettings
import com.hearglasses.app.settings.MtuPreset
import com.hearglasses.app.settings.VadPreset

@Composable
fun GeekSettingsDialog(
    currentSettings: GeekSettings,
    onDismiss: () -> Unit,
    onSave: (GeekSettings) -> Unit,
) {
    var vadThreshold by remember(currentSettings) { mutableStateOf(currentSettings.vadThreshold) }
    var mtuSize by remember(currentSettings) { mutableStateOf(currentSettings.mtuSize) }
    var debugMode by remember(currentSettings) { mutableStateOf(currentSettings.debugMode) }
    var keepAlive by remember(currentSettings) { mutableStateOf(currentSettings.keepAlive) }

    val selectedVad = VadPreset.entries.minByOrNull {
        kotlin.math.abs(it.threshold - vadThreshold)
    } ?: VadPreset.MEDIUM

    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                Text(
                    text = "Geek 设置",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.Black,
                )

                // ── VAD Sensitivity ──
                SettingLabel(text = "VAD 灵敏度")
                VADSlider(
                    value = vadThreshold,
                    onValueChange = { vadThreshold = it },
                )

                // ── BLE MTU ──
                SettingLabel(text = "BLE MTU")
                MtuSelector(
                    current = mtuSize,
                    onSelect = { mtuSize = it },
                )

                // ── 音频来源 ──
                SettingLabel(text = "音频来源")
                DebugModeSelector(
                    current = debugMode,
                    onSelect = { debugMode = it },
                )

                // ── 后台保活 ──
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SettingLabel(text = "后台保活")
                    Switch(
                        checked = keepAlive,
                        onCheckedChange = { keepAlive = it },
                        colors = SwitchDefaults.colors(
                            checkedTrackColor = Color(0xFF4CAF50),
                        ),
                    )
                }

                // ── Note ──
                Text(
                    text = "部分设置将在下次\"开启收音\"时生效",
                    fontSize = 12.sp,
                    color = Color(0xFF999999),
                )

                // ── Buttons ──
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Button(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFFF5F5F5),
                            contentColor = Color.Black,
                        ),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Text("取消")
                    }
                    Button(
                        onClick = {
                            onSave(
                                currentSettings.copy(
                                    vadThreshold = vadThreshold,
                                    mtuSize = mtuSize,
                                    debugMode = debugMode,
                                    keepAlive = keepAlive,
                                ),
                            )
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF4CAF50),
                        ),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Text("保存")
                    }
                }
            }
        }
    }
}

@Composable
private fun DebugModeSelector(current: DebugMode, onSelect: (DebugMode) -> Unit) {
    var expanded by remember { mutableStateOf(false) }

    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = true }
                .padding(vertical = 8.dp, horizontal = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(current.label, fontSize = 14.sp, color = Color.Black)
            Text("▼", fontSize = 12.sp, color = Color(0xFF999999))
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            DebugMode.entries.forEach { mode ->
                DropdownMenuItem(
                    text = { Text(mode.label) },
                    onClick = {
                        onSelect(mode)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun SettingLabel(text: String) {
    Text(
        text = text,
        fontSize = 16.sp,
        fontWeight = FontWeight.SemiBold,
        color = Color(0xFF333333),
    )
}

@Composable
private fun VADSlider(value: Float, onValueChange: (Float) -> Unit) {
    Column {
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = 0.1f..1.0f,
            colors = SliderDefaults.colors(
                thumbColor = Color(0xFF4CAF50),
                activeTrackColor = Color(0xFF4CAF50),
            ),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("低 (更快判停)", fontSize = 12.sp, color = Color(0xFF999999))
            Text(
                "${(value * 100).toInt()}%",
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = Color(0xFF4CAF50),
            )
            Text("高 (更久)", fontSize = 12.sp, color = Color(0xFF999999))
        }
    }
}

@Composable
private fun MtuSelector(current: Int, onSelect: (Int) -> Unit) {
    val currentLabel = MtuPreset.entries.find { it.mtu == current }?.label ?: "$current"
    var expanded by remember { mutableStateOf(false) }

    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = true }
                .padding(vertical = 8.dp, horizontal = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(currentLabel, fontSize = 14.sp, color = Color.Black)
            Text("▼", fontSize = 12.sp, color = Color(0xFF999999))
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            MtuPreset.entries.forEach { preset ->
                DropdownMenuItem(
                    text = { Text(preset.label) },
                    onClick = {
                        onSelect(preset.mtu)
                        expanded = false
                    },
                )
            }
        }
    }
}
