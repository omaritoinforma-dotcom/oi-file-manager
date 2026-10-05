package com.omaritoinforma.oiarchivos

import android.content.pm.ActivityInfo
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import com.omaritoinforma.oiarchivos.data.BackgroundImage
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.runtime.produceState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.omaritoinforma.oiarchivos.data.AppInstaller
import com.omaritoinforma.oiarchivos.data.ScreenOrientation
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
            // «Orientación de la pantalla»: automática (la del teléfono), vertical u horizontal.
            val orientation = vm.screenOrientation.value
            LaunchedEffect(orientation) {
                requestedOrientation =
                    when (orientation) {
                        ScreenOrientation.AUTO -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                        ScreenOrientation.PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                        ScreenOrientation.LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                    }
            }
            // «Idioma»: al cambiarlo (en Ajustes o al restaurar una copia) se carga el catálogo y la
            // pantalla se vuelve a crear para que todos los textos salgan en el idioma nuevo.
            val language = vm.appLanguage.value
            val startLanguage = remember { language }
            LaunchedEffect(language) {
                if (language != startLanguage) {
                    com.omaritoinforma.oiarchivos.data.I18n.apply(this@MainActivity, language)
                    recreate()
                }
            }
            // «Diseño grande»: la interfaz entera, un 20 % más grande.
            val density = LocalDensity.current
            val scaled =
                if (vm.largeLayout.value) Density(density.density * 1.2f, density.fontScale) else density
            // Imagen de fondo: se dibuja debajo de todo y el color de fondo se vuelve translúcido.
            val context = LocalContext.current
            var image by remember { mutableStateOf<ImageBitmap?>(null) }
            LaunchedEffect(vm.backgroundImage.value, vm.backgroundVersion) {
                image =
                    if (vm.backgroundImage.value)
                        withContext(Dispatchers.IO) { BackgroundImage.load(context)?.asImageBitmap() }
                    else null
            }
            val overlay =
                if (image != null) BackgroundImage.overlayAlpha(vm.backgroundStrength.value) else 1f
            CompositionLocalProvider(LocalDensity provides scaled) {
                OiTheme(
                    dark = dark,
                    accent = vm.accent.value,
                    pureBlack = vm.pureBlack.value,
                    backgroundAlpha = overlay) {
                        val solid = MaterialTheme.colorScheme.background.copy(alpha = 1f)
                        Box(Modifier.fillMaxSize().background(solid)) {
                            image?.let {
                                Image(
                                    it,
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize())
                            }
                            // Transparente: cada pantalla pone su propio fondo (translúcido con imagen).
                            // Si lo pusiera también aquí, las capas se sumarían y la imagen casi no se vería.
                            Surface(modifier = Modifier.fillMaxSize(), color = androidx.compose.ui.graphics.Color.Transparent) {
                                AppRoot(vm)
                            }
                        }
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
