package ai.sotto.assistant.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.sp
import ai.sotto.assistant.R

/**
 * Type. Inter for body and UI, Inter Display for the large headings where its tighter
 * spacing reads better. Bundled under the SIL Open Font License (see licenses/).
 */
val InterFamily = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
    Font(R.font.inter_bold, FontWeight.Bold),
)

val InterDisplayFamily = FontFamily(
    Font(R.font.inter_display_semibold, FontWeight.SemiBold),
    Font(R.font.inter_display_bold, FontWeight.Bold),
)

private val trimBoth = LineHeightStyle(
    alignment = LineHeightStyle.Alignment.Center,
    trim = LineHeightStyle.Trim.None,
)

val SottoTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = InterDisplayFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 44.sp,
        lineHeight = 50.sp,
        letterSpacing = (-1.2).sp,
        lineHeightStyle = trimBoth,
    ),
    displayMedium = TextStyle(
        fontFamily = InterDisplayFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 34.sp,
        lineHeight = 40.sp,
        letterSpacing = (-0.8).sp,
        lineHeightStyle = trimBoth,
    ),
    headlineLarge = TextStyle(
        fontFamily = InterDisplayFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 28.sp,
        lineHeight = 34.sp,
        letterSpacing = (-0.5).sp,
        lineHeightStyle = trimBoth,
    ),
    headlineMedium = TextStyle(
        fontFamily = InterDisplayFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 23.sp,
        lineHeight = 29.sp,
        letterSpacing = (-0.3).sp,
        lineHeightStyle = trimBoth,
    ),
    headlineSmall = TextStyle(
        fontFamily = InterFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 19.sp,
        lineHeight = 25.sp,
        letterSpacing = (-0.2).sp,
        lineHeightStyle = trimBoth,
    ),
    titleLarge = TextStyle(
        fontFamily = InterFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 17.sp,
        lineHeight = 23.sp,
        letterSpacing = (-0.1).sp,
        lineHeightStyle = trimBoth,
    ),
    titleMedium = TextStyle(
        fontFamily = InterFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 15.sp,
        lineHeight = 21.sp,
        lineHeightStyle = trimBoth,
    ),
    titleSmall = TextStyle(
        fontFamily = InterFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        lineHeightStyle = trimBoth,
    ),
    bodyLarge = TextStyle(
        fontFamily = InterFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        lineHeightStyle = trimBoth,
    ),
    bodyMedium = TextStyle(
        fontFamily = InterFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 21.sp,
        lineHeightStyle = trimBoth,
    ),
    bodySmall = TextStyle(
        fontFamily = InterFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 12.5.sp,
        lineHeight = 18.sp,
        lineHeightStyle = trimBoth,
    ),
    labelLarge = TextStyle(
        fontFamily = InterFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 15.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp,
        lineHeightStyle = trimBoth,
    ),
    labelMedium = TextStyle(
        fontFamily = InterFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 12.5.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.3.sp,
        lineHeightStyle = trimBoth,
    ),
    labelSmall = TextStyle(
        fontFamily = InterFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 11.sp,
        lineHeight = 14.sp,
        letterSpacing = 0.7.sp,
        lineHeightStyle = trimBoth,
    ),
)

/** The whisper itself — the one piece of text the user reads mid-conversation. */
val WhisperTextStyle = TextStyle(
    fontFamily = InterDisplayFamily,
    fontWeight = FontWeight.SemiBold,
    fontSize = 25.sp,
    lineHeight = 33.sp,
    letterSpacing = (-0.4).sp,
    lineHeightStyle = trimBoth,
)
