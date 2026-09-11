package bes.max.bmaps.feature.shell

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import bmaps.feature.shell.generated.resources.*
import org.jetbrains.compose.resources.Font

@Composable
internal fun BmapsTheme(darkTheme: Boolean, content: @Composable () -> Unit) {
    val inter = FontFamily(
        Font(Res.font.inter_regular, FontWeight.Normal),
        Font(Res.font.inter_semibold, FontWeight.SemiBold),
        Font(Res.font.inter_bold, FontWeight.Bold),
    )
    BoxWithConstraints {
        MaterialTheme(
            colorScheme = if (darkTheme) BmapsDarkColors else BmapsLightColors,
            typography = bmapsTypography(inter, compact = maxWidth < 600.dp),
            shapes = BmapsShapes,
            content = content,
        )
    }
}

private fun bmapsTypography(font: FontFamily, compact: Boolean): Typography {
    fun style(size: Int, line: Int, weight: FontWeight = FontWeight.Normal, tracking: Float = 0f) = TextStyle(
        fontFamily = font, fontSize = size.sp, lineHeight = line.sp,
        fontWeight = weight, letterSpacing = tracking.em, fontFeatureSettings = "tnum",
    )
    return Typography(
        displayLarge = style(32, 40, FontWeight.Bold, -0.02f),
        displayMedium = style(28, 32, FontWeight.Bold, -0.03f),
        displaySmall = style(18, 22, FontWeight.Bold, -0.02f),
        headlineLarge = if (compact) style(26, 32, FontWeight.Bold, -0.01f) else style(32, 40, FontWeight.Bold, -0.02f),
        headlineMedium = style(22, 28, FontWeight.SemiBold, -0.01f),
        headlineSmall = style(18, 24, FontWeight.SemiBold),
        titleLarge = style(18, 24, FontWeight.SemiBold),
        titleMedium = style(16, 22, FontWeight.SemiBold),
        titleSmall = style(14, 20, FontWeight.SemiBold),
        bodyLarge = style(16, 24), bodyMedium = style(14, 20), bodySmall = style(12, 16),
        labelLarge = style(14, 18, FontWeight.SemiBold, 0.02f),
        labelMedium = style(12, 16, FontWeight.SemiBold, 0.04f),
        labelSmall = style(10, 14, FontWeight.Bold, 0.06f),
    )
}

private val BmapsShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp), small = RoundedCornerShape(16.dp),
    medium = RoundedCornerShape(24.dp), large = RoundedCornerShape(32.dp),
    extraLarge = RoundedCornerShape(48.dp),
)

internal val BmapsDarkColors = darkColorScheme(
    surface = Color(0xFF0F141A),
    surfaceDim = Color(0xFF0F141A),
    surfaceBright = Color(0xFF353941),
    surfaceContainerLowest = Color(0xFF0A0E15),
    surfaceContainerLow = Color(0xFF181C23),
    surfaceContainer = Color(0xFF1C2027),
    surfaceContainerHigh = Color(0xFF262A31),
    surfaceContainerHighest = Color(0xFF31353C),
    onSurface = Color(0xFFDFE2EC),
    onSurfaceVariant = Color(0xFFE1BFB5),
    inverseSurface = Color(0xFFDFE2EC),
    inverseOnSurface = Color(0xFF2D3138),
    outline = Color(0xFFA98A80),
    outlineVariant = Color(0xFF594139),
    surfaceTint = Color(0xFFFFB59D),
    primary = Color(0xFFFFB59D),
    onPrimary = Color(0xFF5D1900),
    primaryContainer = Color(0xFFFF6B35),
    onPrimaryContainer = Color(0xFF5F1900),
    inversePrimary = Color(0xFFAB3500),
    secondary = Color(0xFF4EDEA3),
    onSecondary = Color(0xFF003824),
    secondaryContainer = Color(0xFF00A572),
    onSecondaryContainer = Color(0xFF00311F),
    tertiary = Color(0xFF7BD0FF),
    onTertiary = Color(0xFF00354A),
    tertiaryContainer = Color(0xFF00A5DE),
    onTertiaryContainer = Color(0xFF00364B),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    primaryFixed = Color(0xFFFFDBD0),
    primaryFixedDim = Color(0xFFFFB59D),
    onPrimaryFixed = Color(0xFF390C00),
    onPrimaryFixedVariant = Color(0xFF832600),
    secondaryFixed = Color(0xFF6FFBBE),
    secondaryFixedDim = Color(0xFF4EDEA3),
    onSecondaryFixed = Color(0xFF002113),
    onSecondaryFixedVariant = Color(0xFF005236),
    tertiaryFixed = Color(0xFFC4E7FF),
    tertiaryFixedDim = Color(0xFF7BD0FF),
    onTertiaryFixed = Color(0xFF001E2C),
    onTertiaryFixedVariant = Color(0xFF004C69),
    background = Color(0xFF0F141A),
    onBackground = Color(0xFFDFE2EC),
    surfaceVariant = Color(0xFF31353C),
    scrim = Color(0xFF000000),
)

internal val BmapsLightColors = BmapsDarkColors.copy(
    surface = Color(0xFFFFFFFF),
    surfaceDim = Color(0xFFDCE0E8),
    surfaceBright = Color(0xFFFFFFFF),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF5F7FB),
    surfaceContainer = Color(0xFFEFF1F6),
    surfaceContainerHigh = Color(0xFFE9ECF2),
    surfaceContainerHighest = Color(0xFFE2E6EE),
    onSurface = Color(0xFF181C23),
    onSurfaceVariant = Color(0xFF594139),
    inverseSurface = Color(0xFF2D3138),
    inverseOnSurface = Color(0xFFDFE2EC),
    outline = Color(0xFF8C7168),
    outlineVariant = Color(0xFFE1BFB5),
    surfaceTint = Color(0xFFAB3500),
    primary = Color(0xFFAB3500),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFF6B35),
    onPrimaryContainer = Color(0xFF5F1900),
    inversePrimary = Color(0xFFFFB59D),
    secondary = Color(0xFF006C49),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFF6FFBBE),
    onSecondaryContainer = Color(0xFF00311F),
    tertiary = Color(0xFF00658A),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFC4E7FF),
    onTertiaryContainer = Color(0xFF00364B),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFF5F7FB),
    onBackground = Color(0xFF181C23),
    surfaceVariant = Color(0xFFE2E6EE),
)
