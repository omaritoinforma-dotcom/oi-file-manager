package com.omaritoinforma.oiarchivos

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.omaritoinforma.oiarchivos.data.AppInstaller
import com.omaritoinforma.oiarchivos.data.ThemeMode
import kotlinx.coroutines.launch
import com.omaritoinforma.oiarchivos.ui.AppRoot
import com.omaritoinforma.oiarchivos.ui.MainViewModel
import com.omaritoinforma.oiarchivos.ui.theme.OiTheme

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        vm.receive(intent)
        // Instalar o desinstalar por lotes: Android pide confirmar cada app. La ventana se abre
        // solo con la app a la vista; si estaba en segundo plano, se abre al volver.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                AppInstaller.confirm.collect { ask ->
                    if (ask != null) {
                        AppInstaller.confirm.value = null
                        runCatching { startActivity(ask) }
                            .onFailure {
                                AppInstaller.confirmFailed(
                                    this@MainActivity, "no se pudo abrir la confirmación de Android")
                            }
                    }
                }
            }
        }
        setContent {
            val dark =
                when (vm.themeMode) {
                    ThemeMode.SYSTEM -> isSystemInDarkTheme()
                    ThemeMode.LIGHT -> false
                    ThemeMode.DARK -> true
                }
            DisposableEffect(dark) {
                enableEdgeToEdge(
                    statusBarStyle =
                        SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark },
                    navigationBarStyle = SystemBarStyle.auto(LIGHT_SCRIM, DARK_SCRIM) { dark },
                )
                onDispose {}
            }
            OiTheme(dark = dark) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background) {
                        AppRoot(vm)
                    }
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        vm.receive(intent)
    }

    override fun onResume() {
        super.onResume()
        vm.onResume()
    }

    override fun onStart() {
        super.onStart()
        vm.onForeground()
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) vm.onBackground()
    }

    override fun onDestroy() {
        // Salir con «Atrás» (en Android 11 y anteriores cierra la Activity) cuenta como salir de la app.
        if (isFinishing && !isChangingConfigurations) vm.exit()
        super.onDestroy()
    }

    private companion object {
        val LIGHT_SCRIM = Color.argb(0xe6, 0xFF, 0xFF, 0xFF)
        val DARK_SCRIM = Color.argb(0x80, 0x1b, 0x1b, 0x1b)
    }
}
