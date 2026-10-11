package ai.mobileagent.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// User palette: cream, ice, mist, and slate. Dark ink preserves text contrast.
private val GlacierColors = lightColorScheme(
    primary = Color(0xFF365D7D),
    onPrimary = Color(0xFFF8FAFC),
    primaryContainer = Color(0xFFCFE8F3),
    onPrimaryContainer = Color(0xFF24394B),
    secondary = Color(0xFF526A80),
    onSecondary = Color(0xFFF8FAFC),
    secondaryContainer = Color(0xFF9DB7D6),
    onSecondaryContainer = Color(0xFF24394B),
    tertiary = Color(0xFF526B78),
    onTertiary = Color(0xFFF8FAFC),
    tertiaryContainer = Color(0xFFDCEBF0),
    onTertiaryContainer = Color(0xFF24394B),
    background = Color(0xFFF8FAFC),
    onBackground = Color(0xFF24394B),
    surface = Color(0xFFF8FAFC),
    onSurface = Color(0xFF24394B),
    surfaceVariant = Color(0xFFE4EDF5),
    onSurfaceVariant = Color(0xFF526A80),
    surfaceTint = Color(0xFF7A97B6),
    inverseSurface = Color(0xFF24394B),
    inverseOnSurface = Color(0xFFF8FAFC),
    inversePrimary = Color(0xFF9DB7D6),
    outline = Color(0xFF7A97B6),
    outlineVariant = Color(0xFFD5E2EC),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF0F5F9),
    surfaceContainer = Color(0xFFEAF1F7),
    surfaceContainerHigh = Color(0xFFE4EDF5),
    surfaceContainerHighest = Color(0xFFD9E6EF),
    surfaceBright = Color(0xFFF8FAFC),
    surfaceDim = Color(0xFFE3EBF2),
    error = Color(0xFF9C4147),
    onError = Color.White,
    errorContainer = Color(0xFFFCEAEC),
    onErrorContainer = Color(0xFF6D242C),
)

private val GlacierTypography = Typography().let { base ->
    base.copy(
        headlineLarge = base.headlineLarge.copy(fontSize = 32.sp, lineHeight = 43.sp, fontWeight = FontWeight.SemiBold),
        titleLarge = base.titleLarge.copy(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold),
        titleMedium = base.titleMedium.copy(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
        bodyLarge = base.bodyLarge.copy(fontSize = 16.sp, lineHeight = 25.sp),
        bodyMedium = base.bodyMedium.copy(fontSize = 14.sp, lineHeight = 23.sp),
        bodySmall = base.bodySmall.copy(fontSize = 12.sp, lineHeight = 19.sp),
    )
}

@Composable
fun GlacierTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = GlacierColors, typography = GlacierTypography, content = content)
}
