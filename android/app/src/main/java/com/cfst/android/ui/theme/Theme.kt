package com.cfst.android.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val LightColors = lightColorScheme(
    primary = CfOrange,
    onPrimary = CfOnOrange,
    primaryContainer = CfOrangeDark,
    onPrimaryContainer = Color(0xFF261900),
    surfaceVariant = Color(0xFFF0E0D3),
)

private val DarkColors = darkColorScheme(
    primary = CfOrangeDark,
    onPrimary = Color(0xFF552700),
    primaryContainer = Color(0xFF7A3E00),
    onPrimaryContainer = CfOrangeDark,
)

@Composable
fun CfTheme(
    darkTheme: Boolean? = null,
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val effectiveDarkTheme = darkTheme ?: isSystemInDarkTheme()
    val useDynamicColor = dynamicColor &&
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val colorScheme = when {
        useDynamicColor -> {
            val context = LocalContext.current
            if (effectiveDarkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        effectiveDarkTheme -> DarkColors
        else -> LightColors
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content,
    )
}