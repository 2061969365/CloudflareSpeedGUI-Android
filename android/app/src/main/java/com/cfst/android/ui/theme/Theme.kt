package com.cfst.android.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
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
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content,
    )
}