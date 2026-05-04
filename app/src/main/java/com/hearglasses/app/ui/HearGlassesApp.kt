package com.hearglasses.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.hearglasses.app.di.AppContainer
import com.hearglasses.app.service.AppUiState
import com.hearglasses.app.service.DebugPanelState
import com.hearglasses.app.service.HearGlassesController
import com.hearglasses.app.service.TranscriptItem

@Composable
fun HearGlassesApp(
    appContainer: AppContainer,
    onToggleListening: () -> Unit,
    needPermissions: Boolean = false,
    onRequestPermissions: () -> Unit = {},
    onGoToSettings: () -> Unit = {},
) {
    val generation by appContainer.generation.collectAsState()
    val controller = remember(generation) { appContainer.controller }
    val uiState by controller.uiState.collectAsState()
    val settings by appContainer.settings.collectAsState()
    var showSettings by remember { mutableStateOf(false) }
    var showPermissionsDialog by remember { mutableStateOf(false) }

    fun handleToggle() {
        if (needPermissions) {
            showPermissionsDialog = true
        } else {
            onToggleListening()
        }
    }

    val listState = rememberLazyListState()

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color.White,
    ) {
        HearGlassesScreen(
            controller = controller,
            uiState = uiState,
            listState = listState,
            onToggleListening = ::handleToggle,
            settingsSummary = settingsSummary(settings),
            onOpenSettings = { showSettings = true },
        )
    }

    if (showSettings) {
        GeekSettingsDialog(
            currentSettings = settings,
            onDismiss = { showSettings = false },
            onSave = { s -> appContainer.updateSettings { s }; showSettings = false },
        )
    }

    if (showPermissionsDialog) {
        PermissionMissingDialog(
            onDismiss = { showPermissionsDialog = false },
            onGoToSettings = {
                showPermissionsDialog = false
                onGoToSettings()
            },
            onRetry = {
                showPermissionsDialog = false
                onRequestPermissions()
            },
        )
    }
}

@Composable
fun PermissionMissingDialog(
    onDismiss: () -> Unit,
    onGoToSettings: () -> Unit,
    onRetry: () -> Unit,
) {
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
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    text = "需要权限",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.Black,
                )
                Text(
                    text = "HearGlasses 需要蓝牙和录音权限才能工作。\n\n" +
                        "请在系统设置中授予以下权限：\n" +
                        "• 附近设备 / 蓝牙\n" +
                        "• 麦克风 / 录音",
                    fontSize = 14.sp,
                    color = Color(0xFF666666),
                    lineHeight = 22.sp,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
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
                        Text("取消", fontSize = 13.sp)
                    }
                    Button(
                        onClick = onGoToSettings,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFFFFF3E0),
                            contentColor = Color(0xFFE65100),
                        ),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Text("去设置", fontSize = 13.sp)
                    }
                    Button(
                        onClick = onRetry,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF4CAF50),
                        ),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Text("重新授权", fontSize = 13.sp)
                    }
                }
            }
        }
    }
}

private fun settingsSummary(s: com.hearglasses.app.settings.GeekSettings): String {
    val vad = com.hearglasses.app.settings.VadPreset.entries
        .find { kotlin.math.abs(it.threshold - s.vadThreshold) < 0.01f }?.label ?: "自定义"
    return "音频: ${s.debugMode.label} | VAD: $vad | MTU: ${s.mtuSize}"
}

@Composable
private fun HearGlassesScreen(
    controller: HearGlassesController,
    uiState: AppUiState,
    listState: LazyListState,
    onToggleListening: () -> Unit,
    settingsSummary: String,
    onOpenSettings: () -> Unit,
) {
    var userScrolledUp by remember { mutableStateOf(false) }

    // Reset flag when user reaches or is force-navigated to the bottom
    val isAtBottom by remember {
        derivedStateOf {
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index
                ?: return@derivedStateOf true
            lastVisible >= listState.layoutInfo.totalItemsCount - 1
        }
    }

    LaunchedEffect(isAtBottom) {
        if (isAtBottom) userScrolledUp = false
    }

    // Detect when user manually scrolls away from bottom
    LaunchedEffect(controller) {
        controller.uiState.collect { state ->
            if (state.transcriptItems.isNotEmpty() && !userScrolledUp) {
                listState.animateScrollToItem(state.transcriptItems.lastIndex)
            }
        }
    }

    // Mark userScrolledUp = true when they scroll away (not at bottom and scroll in progress)
    LaunchedEffect(listState.isScrollInProgress) {
        if (!listState.isScrollInProgress) {
            // Scroll just finished — check if we ended away from bottom
            snapshotFlow { isAtBottom }
                .first()
                .let { atBottom ->
                    if (!atBottom) userScrolledUp = true
                }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 20.dp, vertical = 24.dp),
    ) {
        StatusBar(
            connectionText = uiState.connectionText,
            batteryText = uiState.batteryText,
            isConnected = uiState.connectionText.contains("已连接"),
        )
        Spacer(modifier = Modifier.height(20.dp))
        DebugPanel(debugPanelState = uiState.debugPanelState)
        Spacer(modifier = Modifier.height(20.dp))
        TranscriptPanel(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            listState = listState,
            items = uiState.transcriptItems,
            placeholderText = uiState.placeholderText,
            showJumpToBottom = userScrolledUp && uiState.transcriptItems.isNotEmpty(),
            onJumpToBottom = { userScrolledUp = false },
        )
        Spacer(modifier = Modifier.height(20.dp))
        ControlPanel(
            isListening = uiState.isListening,
            onToggle = onToggleListening,
            settingsSummary = settingsSummary,
            onOpenSettings = onOpenSettings,
        )
    }
}

@Composable
private fun StatusBar(
    connectionText: String,
    batteryText: String,
    isConnected: Boolean,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(16.dp)
                    .clip(CircleShape)
                    .background(if (isConnected) Color(0xFF4CAF50) else Color(0xFFF44336)),
            )
            Text(
                text = connectionText,
                color = if (isConnected) Color.Black else Color(0xFF666666),
                fontSize = 24.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Text(
            text = batteryText,
            color = Color(0xFF4CAF50),
            fontSize = 24.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun DebugPanel(debugPanelState: DebugPanelState) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFFF7F7F7)),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "调试面板",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = Color.Black,
            )
            DebugRow(label = "模式", value = debugPanelState.modeLabel)
            if (debugPanelState.audioInfo.isNotBlank()) {
                DebugRow(label = "音频信息", value = debugPanelState.audioInfo)
            }
            if (debugPanelState.decoderInfo.isNotBlank()) {
                DebugRow(label = "解码器", value = debugPanelState.decoderInfo)
            }
            DebugRow(label = "ASR引擎", value = debugPanelState.asrMode)
            if (debugPanelState.asrInitError.isNotBlank()) {
                DebugRow(label = "ASR错误", value = debugPanelState.asrInitError)
            }
            DebugRow(label = "MTU", value = debugPanelState.mtu.toString())
            DebugRow(label = "音频包数", value = debugPanelState.packetCount.toString())
            DebugRow(label = "峰值振幅", value = debugPanelState.peakAmplitude.toString())
            DebugRow(label = "最近 partial", value = debugPanelState.lastPartialText.ifBlank { "-" })
            DebugRow(label = "最近 final", value = debugPanelState.lastFinalText.ifBlank { "-" })
        }
    }
}

@Composable
private fun DebugRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = label,
            color = Color(0xFF666666),
            fontSize = 14.sp,
        )
        Text(
            text = value,
            color = Color.Black,
            fontSize = 14.sp,
            textAlign = TextAlign.End,
            modifier = Modifier.padding(start = 16.dp),
        )
    }
}

@Composable
private fun TranscriptPanel(
    modifier: Modifier = Modifier,
    listState: LazyListState,
    items: List<TranscriptItem>,
    placeholderText: String,
    showJumpToBottom: Boolean,
    onJumpToBottom: () -> Unit,
) {
    val coroutineScope = rememberCoroutineScope()

    if (items.isEmpty()) {
        Box(
            modifier = modifier,
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = placeholderText,
                color = Color(0xFF999999),
                textAlign = TextAlign.Center,
                fontSize = 24.sp,
            )
        }
        return
    }

    Box(modifier = modifier) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = listState,
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            items(items, key = { it.id }) { item ->
                TranscriptLine(item = item)
            }
        }

        if (showJumpToBottom) {
            Button(
                onClick = {
                    onJumpToBottom()
                    if (items.isNotEmpty()) {
                        coroutineScope.launch {
                            listState.animateScrollToItem(items.lastIndex)
                        }
                    }
                },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(12.dp),
                shape = RoundedCornerShape(999.dp),
            ) {
                Text(text = "回到底部")
            }
        }
    }
}

@Composable
private fun TranscriptLine(item: TranscriptItem) {
    Text(
        text = item.text,
        color = if (item.isActive) Color.Black else Color(0xFFAAAAAA),
        fontSize = if (item.isActive) 36.sp else 32.sp,
        fontWeight = if (item.isActive) FontWeight.Bold else FontWeight.Normal,
        lineHeight = if (item.isActive) 44.sp else 40.sp,
    )
}

@Composable
private fun ControlPanel(
    isListening: Boolean,
    onToggle: () -> Unit,
    settingsSummary: String,
    onOpenSettings: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(
                onClick = onToggle,
                modifier = Modifier
                    .weight(1f)
                    .height(88.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isListening) Color(0xFFE8F5E9) else Color(0xFFF5F5F5),
                    contentColor = if (isListening) Color(0xFF4CAF50) else Color(0xFFF44336),
                ),
                shape = RoundedCornerShape(20.dp),
            ) {
                Text(
                    text = if (isListening) "停止收音" else "开启收音",
                    fontSize = 32.sp,
                    fontWeight = FontWeight.ExtraBold,
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFFF5F5F5))
                    .clickable { onOpenSettings() },
                contentAlignment = Alignment.Center,
            ) {
                Text("⚙", fontSize = 24.sp)
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = settingsSummary,
            color = MaterialTheme.colorScheme.outline,
            fontSize = 14.sp,
            textAlign = TextAlign.Center,
        )
    }
}
