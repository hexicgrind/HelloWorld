package ai.sotto.assistant.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat

private val DarkScheme = darkColorScheme(
    primary = SottoColors.Violet,
    onPrimary = SottoColors.Cloud,
    primaryContainer = SottoColors.VioletMuted,
    onPrimaryContainer = SottoColors.VioletBright,

    secondary = SottoColors.Teal,
    onSecondary = SottoColors.Ink,
    secondaryContainer = SottoColors.TealMuted,
    onSecondaryContainer = SottoColors.Teal,

    tertiary = SottoColors.Amber,
    onTertiary = SottoColors.Ink,
    tertiaryContainer = SottoColors.AmberMuted,
    onTertiaryContainer = SottoColors.Amber,

    background = SottoColors.Ink,
    onBackground = SottoColors.Cloud,
    surface = SottoColors.InkElevated,
    onSurface = SottoColors.Cloud,
    surfaceVariant = SottoColors.InkSurface,
    onSurfaceVariant = SottoColors.Slate,
    surfaceContainerHighest = SottoColors.InkSurface,

    outline = SottoColors.InkOutline,
    outlineVariant = SottoColors.InkSurface,

    error = SottoColors.Coral,
    onError = SottoColors.Cloud,
    errorContainer = SottoColors.CoralMuted,
    onErrorContainer = SottoColors.Coral,

    scrim = SottoColors.Ink,
)

private val LightScheme = lightColorScheme(
    primary = SottoColors.VioletDeep,
    onPrimary = SottoColors.Cloud,
    primaryContainer = SottoColors.Violet.copy(alpha = 0.16f),
    onPrimaryContainer = SottoColors.VioletDeep,

    secondary = SottoColors.TealDeep,
    onSecondary = SottoColors.Cloud,
    secondaryContainer = SottoColors.Teal.copy(alpha = 0.18f),
    onSecondaryContainer = SottoColors.TealDeep,

    tertiary = SottoColors.AmberDeep,
    onTertiary = SottoColors.Cloud,

    background = SottoColors.Paper,
    onBackground = SottoColors.PaperInk,
    surface = SottoColors.PaperElevated,
    onSurface = SottoColors.PaperInk,
    surfaceVariant = SottoColors.PaperSurface,
    onSurfaceVariant = SottoColors.SlateDim,
    surfaceContainerHighest = SottoColors.PaperSurface,

    outline = SottoColors.PaperOutline,
    outlineVariant = SottoColors.PaperSurface,

    error = SottoColors.CoralDeep,
    onError = SottoColors.Cloud,
    errorContainer = SottoColors.Coral.copy(alpha = 0.14f),
    onErrorContainer = SottoColors.CoralDeep,
)

private val SottoShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

/**
 * Sotto's theme. Deliberately does *not* opt into Material You dynamic colour: the
 * teal-means-recognised rule only works if the palette is ours and constant.
 */
@Composable
fun SottoTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val scheme = if (darkTheme) DarkScheme else LightScheme
    val view = LocalView.current

    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            WindowCompat.setDecorFitsSystemWindows(window, false)
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    MaterialTheme(
        colorScheme = scheme,
        typography = SottoTypography,
        shapes = SottoShapes,
        content = content,
    )
}
