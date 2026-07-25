package ai.sotto.assistant.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * The Sotto palette.
 *
 * One rule governs everything here: **teal means "recognised", and nothing else ever
 * uses teal.** The user is glancing at this screen for a fraction of a second while
 * talking to a human being, so a colour that means exactly one thing is worth more than
 * a colour that looks nice.
 */
object SottoColors {
    // Neutrals — the app lives in the dark because it lives behind a viewfinder.
    val Ink = Color(0xFF0A0E1A)
    val InkElevated = Color(0xFF141A2B)
    val InkSurface = Color(0xFF1C2437)
    val InkOutline = Color(0xFF2C3650)

    // Brand
    val Violet = Color(0xFF7C6CFF)
    val VioletBright = Color(0xFF9C90FF)
    val VioletDeep = Color(0xFF4A3BC7)
    val VioletMuted = Color(0xFF2A2555)

    // Reserved for "this person has been recognised".
    val Teal = Color(0xFF38E1C6)
    val TealDeep = Color(0xFF11897A)
    val TealMuted = Color(0xFF10322F)

    // Status
    val Amber = Color(0xFFFFB86B)
    val AmberMuted = Color(0xFF3A2A16)
    val Coral = Color(0xFFFF6B7A)
    val CoralMuted = Color(0xFF3A1A20)

    // Text
    val Cloud = Color(0xFFF6F7FB)
    val Slate = Color(0xFF8892AB)
    val SlateDim = Color(0xFF5A6274)

    // Light-theme surfaces, for people who use their phone outdoors.
    val AmberDeep = Color(0xFF9A5C10)
    val CoralDeep = Color(0xFFB3283A)

    val Paper = Color(0xFFFAFBFF)
    val PaperElevated = Color(0xFFFFFFFF)
    val PaperSurface = Color(0xFFF0F2F8)
    val PaperOutline = Color(0xFFDCE0EC)
    val PaperInk = Color(0xFF0E1220)
}
