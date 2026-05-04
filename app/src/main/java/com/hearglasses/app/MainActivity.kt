package com.hearglasses.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.hearglasses.app.di.AppContainer
import com.hearglasses.app.service.HearGlassesService
import com.hearglasses.app.ui.HearGlassesApp
import com.hearglasses.app.ui.theme.HearGlassesTheme

class MainActivity : ComponentActivity() {
    private lateinit var appContainer: AppContainer
    private var permissionsDenied by mutableStateOf(false)

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        permissionsDenied = results.values.any { !it }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        appContainer = AppContainer.getInstance(applicationContext)
        requestRuntimePermissions()
        enableEdgeToEdge()
        setContent {
            HearGlassesTheme {
                HearGlassesApp(
                    appContainer = appContainer,
                    onToggleListening = ::handleToggle,
                    needPermissions = permissionsDenied,
                    onRequestPermissions = ::requestRuntimePermissions,
                    onGoToSettings = ::openAppSettings,
                )
            }
        }
    }

    private fun handleToggle() {
        val isListening = appContainer.controller.uiState.value.isListening
        val intent = Intent(this, HearGlassesService::class.java)
        if (isListening) {
            stopService(intent)
        } else {
            // Switch audio source if the user changed it in settings
            if (appContainer.isSettingsOutdated()) {
                appContainer.switchAudioSource(appContainer.debugMode)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        }
    }

    private fun openAppSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", packageName, null)
        }
        startActivity(intent)
    }

    private fun requestRuntimePermissions() {
        val permissions = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_CONNECT)
                add(Manifest.permission.BLUETOOTH_SCAN)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
            add(Manifest.permission.RECORD_AUDIO)
        }

        val deniedPermissions = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (deniedPermissions.isNotEmpty()) {
            permissionLauncher.launch(deniedPermissions.toTypedArray())
        }
    }
}
