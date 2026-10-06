package org.cryomonitor.companion.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/**
 * Material 3 theme (design D3): dynamic colour on Android 12+, a deep-teal
 * brand fallback, plus the caution tokens Material lacks — "Needs
 * attention" and Check-in are amber so red stays reserved for Countdown
 * and Alarm (DESIGN §3.1).
 */
@Immutable
data class CautionColors(
    val caution: Color,
    val onCaution: Color,
    val cautionContainer: Color,
    val onCautionContainer: Color,
)

val LocalCaution = staticCompositionLocalOf {
    CautionColors(Color(0xFF7A5200), Color.White, Color(0xFFFFDEA6), Color(0xFF271900))
}

private val LightCaution = CautionColors(
    caution = Color(0xFF7A5200), onCaution = Color.White,
    cautionContainer = Color(0xFFFFDEA6), onCautionContainer = Color(0xFF271900))
private val DarkCaution = CautionColors(
    caution = Color(0xFFF5B942), onCaution = Color(0xFF412D00),
    cautionContainer = Color(0xFF5D3F00), onCautionContainer = Color(0xFFFFDEA6))

private val BrandLight: ColorScheme = lightColorScheme(
    primary = Color(0xFF00696B), onPrimary = Color.White,
    primaryContainer = Color(0xFF6FF6F8), onPrimaryContainer = Color(0xFF002020),
    tertiary = Color(0xFF3F5F90), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFD5E3FF), onTertiaryContainer = Color(0xFF001B3C),
)
private val BrandDark: ColorScheme = darkColorScheme(
    primary = Color(0xFF4CDADC), onPrimary = Color(0xFF003738),
    primaryContainer = Color(0xFF004F51), onPrimaryContainer = Color(0xFF6FF6F8),
    tertiary = Color(0xFFA7C8FF), onTertiary = Color(0xFF07305F),
    tertiaryContainer = Color(0xFF264777), onTertiaryContainer = Color(0xFFD5E3FF),
)

/** The alarm screen never follows theme or wallpaper (DESIGN §7). */
object AlarmPalette {
    val countdownBackground = Color(0xFF8A3B00)
    val alarmBackground = Color(0xFFB00020)
    val onAlarm = Color.White
    val cancelContainer = Color.White
    val onCancel = Color(0xFF1B1B1B)
}

@Composable
fun CmTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= 31 && dark -> dynamicDarkColorScheme(context)
        Build.VERSION.SDK_INT >= 31 -> dynamicLightColorScheme(context)
        dark -> BrandDark
        else -> BrandLight
    }
    CompositionLocalProvider(LocalCaution provides if (dark) DarkCaution else LightCaution) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}

/** Shorthand used by the state cards. */
object CmColors {
    val caution: CautionColors
        @Composable get() = LocalCaution.current
}
