package com.sidespot

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.sidespot.auth.AuthManager
import com.sidespot.bridge.NativeBridge
import com.sidespot.settings.SettingsManager
import com.sidespot.ui.SidespotNavigation
import com.sidespot.ui.SidespotTheme
import com.sidespot.viewmodel.PlayerViewModel
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : ComponentActivity() {

    private var playerViewModel: PlayerViewModel? = null
    private lateinit var authManager: AuthManager
    private lateinit var settingsManager: SettingsManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        authManager = AuthManager(this)
        settingsManager = SettingsManager(this)

        // Push initial config to native before any connect
        settingsManager.pushConfigToNative()

        // Handle deep link on cold start
        handleAuthCallback(intent)

        setContent {
            SidespotTheme {
                val vm: PlayerViewModel = viewModel()
                playerViewModel = vm
                vm.initPlatform(this@MainActivity)
                vm.initApi(authManager)

                // Dev shortcut: auto-load token from ADB push (only if not already authenticated)
                if (!authManager.state.value.isAuthenticated) {
                    val tokenFile = File("/data/local/tmp/token.txt")
                    if (!vm.uiState.value.isConnected && tokenFile.canRead()) {
                        val token = tokenFile.readText().trim()
                        if (token.isNotEmpty()) {
                            tokenFile.delete()
                            val settings = settingsManager.state.value
                            vm.connect(
                                accessToken = token,
                                connectEnabled = settings.connectEnabled,
                                deviceName = settings.deviceName,
                            )
                        }
                    }
                }

                SidespotNavigation(
                    playerViewModel = vm,
                    authManager = authManager,
                    settingsManager = settingsManager,
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleAuthCallback(intent)
    }

    private fun handleAuthCallback(intent: Intent?) {
        val uri = intent?.data ?: return
        if (uri.scheme == "sidespot" && uri.host == "callback") {
            val code = uri.getQueryParameter("code") ?: return
            lifecycleScope.launch {
                authManager.exchangeCode(code)
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        val step = 65535 / 20 // 20 steps
        return when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> {
                val current = NativeBridge.playerGetVolume()
                val newVol = (current + step).coerceAtMost(65535)
                NativeBridge.playerSetVolume(newVol)
                playerViewModel?.onVolumeChanged(newVol)
                true
            }
            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                val current = NativeBridge.playerGetVolume()
                val newVol = (current - step).coerceAtLeast(0)
                NativeBridge.playerSetVolume(newVol)
                playerViewModel?.onVolumeChanged(newVol)
                true
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }
}
