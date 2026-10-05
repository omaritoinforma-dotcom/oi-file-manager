package com.omaritoinforma.oiarchivos.ui.theme

import android.os.Build
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.omaritoinforma.oiarchivos.data.AccentColor

/** Tonos de un color de la app: principal y su contenedor, en claro y en oscuro. */
private class Tones(val light: Long, val lightContainer: Long, val dark: Long, val darkContainer: Long)

private val tones =
    mapOf(
        AccentColor.BLUE to Tones(0xFF1565C0, 0xFFD6E4FF, 0xFF9EC2FF, 0xFF16345F),
        AccentColor.RED to Tones(0xFFC62828, 0xFFFFDAD6, 0xFFFFB4AB, 0xFF5F1412),
        AccentColor.GREEN to Tones(0xFF2E7D32, 0xFFC8E6C9, 0xFFA5D6A7, 0xFF1B4D1E),
        AccentColor.ORANGE to Tones(0xFFE65100, 0xFFFFE0B2, 0xFFFFCC80, 0xFF5D2A00),
        AccentColor.PURPLE to Tones(0xFF6A1B9A, 0xFFE9D3F5, 0xFFD1A3E8, 0xFF3F1457),
        AccentColor.TEAL to Tones(0xFF00796B, 0xFFB2DFDB, 0xFF80CBC4, 0xFF00433B),
        AccentColor.PINK to Tones(0xFFAD1457, 0xFFF8BBD0, 0xFFF48FB1, 0xFF560A2B))

/** Color principal de [accent] en el tema claro u oscuro; el azul si es DYNAMIC (sin colores del sistema). */
fun accentPrimary(accent: AccentColor, dark: Boolean): Color {
    val t = tones[accent] ?: tones.getValue(AccentColor.BLUE)
    return Color(if (dark) t.dark else t.light)
}

/** Esquema de colores con el color [accent] (nunca los del sistema). */
fun staticScheme(accent: AccentColor, dark: Boolean): ColorScheme {
    val t = tones[accent] ?: tones.getValue(AccentColor.BLUE)
    return if (dark)
        darkColorScheme(
            primary = Color(t.dark),
            primaryContainer = Color(t.darkContainer),
            secondary = Color(0xFF80CBC4),
            tertiary = Color(0xFFFFE082))
    else
        lightColorScheme(
            primary = Color(t.light),
            onPrimary = Color.White,
            primaryContainer = Color(t.lightContainer),
            secondary = Color(0xFF00897B),
            tertiary = Color(0xFFF9A825))
}

/** Con el tema oscuro, el fondo y las superficies pasan a negro puro. */
fun withPureBlack(scheme: ColorScheme): ColorScheme =
    scheme.copy(
        background = Color.Black,
        surface = Color.Black,
        surfaceContainerLowest = Color.Black,
        surfaceContainerLow = Color.Black,
        surfaceContainer = Color(0xFF0A0A0A),
        surfaceContainerHigh = Color(0xFF121212),
        surfaceContainerHighest = Color(0xFF1A1A1A))

@Composable
fun OiTheme(
    dark: Boolean,
    accent: AccentColor = AccentColor.DYNAMIC,
    pureBlack: Boolean = false,
    /** Con imagen de fondo, el color de fondo se deja translúcido (0 a 1 de opacidad) para que se vea. */
    backgroundAlpha: Float = 1f,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val base =
        if (accent == AccentColor.DYNAMIC && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        else staticScheme(accent, dark)
    val scheme = if (dark && pureBlack) withPureBlack(base) else base
    MaterialTheme(
        colorScheme =
            if (backgroundAlpha < 1f) scheme.copy(background = scheme.background.copy(alpha = backgroundAlpha))
            else scheme,
        content = content)
}
