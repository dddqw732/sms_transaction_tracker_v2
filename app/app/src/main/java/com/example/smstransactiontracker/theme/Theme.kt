package com.example.smstransactiontracker.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary            = TealPrimary,
    onPrimary          = TextPrimary,
    primaryContainer   = TealDark,
    onPrimaryContainer = TealLight,
    secondary          = AmberAccent,
    onSecondary        = BackgroundDark,
    background         = BackgroundDark,
    onBackground       = TextPrimary,
    surface            = SurfaceDark,
    onSurface          = TextPrimary,
    surfaceVariant     = SurfaceVariantDark,
    onSurfaceVariant   = TextSecondary,
    outline            = OutlineDark,
    error              = RedNegative,
    onError            = TextPrimary,
    errorContainer     = Color(0xFF7F1D1D),
    onErrorContainer   = Color(0xFFFECACA)
)

@Composable
fun SMSTransactionTrackerTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        typography  = AppTypography,
        content     = content
    )
}
