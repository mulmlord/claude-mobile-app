package dev.mulm.claudemobile.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Shapes
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.TextFieldColors
import androidx.compose.material3.TopAppBarColors
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.mulm.claudemobile.R

/**
 * claude.ai's palette: warm ivory and charcoal neutrals, hairline borders made of
 * low-alpha ink, and the terracotta accent kept for the spark, the send button and
 * things that need you. Primary buttons are ink-on-paper, as on the site.
 */
@Immutable
data class ClaudePalette(
    val bg: Color,           // page
    val surface: Color,      // cards, composer
    val soft: Color,         // code blocks, quiet fills
    val bubble: Color,       // your messages
    val raised: Color,       // pressed / stronger fill
    val text: Color,
    val muted: Color,
    val faint: Color,
    val border: Color,
    val borderStrong: Color,
    val accent: Color,       // brand terracotta (spark)
    val accentButton: Color, // send button, switches, focus
    val onAccent: Color,
    val danger: Color,
    val success: Color,
    val terminalBg: Color,
    val terminalText: Color,
    val dark: Boolean,
)

private val LightPalette = ClaudePalette(
    bg = Color(0xFFFAF9F5), surface = Color(0xFFFFFFFF), soft = Color(0xFFF5F4ED),
    bubble = Color(0xFFF0EEE6), raised = Color(0xFFE8E6DC),
    text = Color(0xFF141413), muted = Color(0xFF5E5D59), faint = Color(0xFF8E8C85),
    border = Color(0x261F1E1D), borderStrong = Color(0x4D1F1E1D),
    accent = Color(0xFFD97757), accentButton = Color(0xFFC6613F), onAccent = Color.White,
    danger = Color(0xFFB53333), success = Color(0xFF3F7D4E),
    terminalBg = Color(0xFF1F1E1D), terminalText = Color(0xFFE8E6DC),
    dark = false,
)

private val DarkPalette = ClaudePalette(
    bg = Color(0xFF262624), surface = Color(0xFF30302E), soft = Color(0xFF1F1E1D),
    bubble = Color(0xFF141413), raised = Color(0xFF3D3D3A),
    text = Color(0xFFFAF9F5), muted = Color(0xFFC2C0B6), faint = Color(0xFF9C9A92),
    border = Color(0x26DEDCD1), borderStrong = Color(0x4DDEDCD1),
    accent = Color(0xFFD97757), accentButton = Color(0xFFD97757), onAccent = Color.White,
    danger = Color(0xFFE5735F), success = Color(0xFF7DB38A),
    terminalBg = Color(0xFF141413), terminalText = Color(0xFFE8E6DC),
    dark = true,
)

private val LocalClaude = staticCompositionLocalOf { LightPalette }

object Claude {
    val colors: ClaudePalette
        @Composable @ReadOnlyComposable get() = LocalClaude.current
}

// Claude's replies are set in a serif on claude.ai; Source Serif 4 (OFL) stands in for it.
private fun serif(weight: Int) = Font(
    R.font.source_serif_4, FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight), FontVariation.Setting("opsz", 18f)),
)

val Serif = FontFamily(serif(300), serif(400), serif(500), serif(600), serif(700))

/** Body text of Claude's replies. */
val ReplyStyle = TextStyle(fontFamily = Serif, fontSize = 16.5.sp, lineHeight = 26.sp, fontWeight = FontWeight.Normal)

private val base = Typography()
private val AppTypography = Typography(
    titleLarge = base.titleLarge.copy(fontSize = 20.sp, fontWeight = FontWeight.Medium),
    titleMedium = base.titleMedium.copy(fontSize = 16.sp, fontWeight = FontWeight.Medium),
    titleSmall = base.titleSmall.copy(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = base.bodyLarge.copy(fontSize = 16.sp, lineHeight = 23.sp, letterSpacing = 0.sp),
    bodyMedium = base.bodyMedium.copy(fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.sp),
    bodySmall = base.bodySmall.copy(fontSize = 12.5.sp, lineHeight = 17.sp, letterSpacing = 0.sp),
    labelLarge = base.labelLarge.copy(fontSize = 14.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.sp),
    labelMedium = base.labelMedium.copy(fontSize = 12.5.sp, letterSpacing = 0.sp),
)

private fun scheme(p: ClaudePalette): ColorScheme = (if (p.dark) darkColorScheme() else lightColorScheme()).copy(
    primary = p.accentButton, onPrimary = p.onAccent,
    primaryContainer = p.bubble, onPrimaryContainer = p.text,
    secondary = p.muted, onSecondary = p.bg,
    secondaryContainer = p.bubble, onSecondaryContainer = p.text,
    tertiary = p.accent, onTertiary = p.onAccent,
    tertiaryContainer = p.soft, onTertiaryContainer = p.text,
    background = p.bg, onBackground = p.text,
    surface = p.bg, onSurface = p.text,
    surfaceVariant = p.bubble, onSurfaceVariant = p.muted,
    surfaceTint = p.bg,
    surfaceBright = p.surface, surfaceDim = p.soft,
    surfaceContainerLowest = p.surface, surfaceContainerLow = p.soft, surfaceContainer = p.soft,
    surfaceContainerHigh = p.bubble, surfaceContainerHighest = p.raised,
    inverseSurface = p.text, inverseOnSurface = p.bg, inversePrimary = p.accent,
    outline = p.borderStrong, outlineVariant = p.border,
    error = p.danger, onError = Color.White,
    errorContainer = p.danger.copy(alpha = 0.12f).compositeOver(p.bg), onErrorContainer = p.danger,
)

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val p = if (isSystemInDarkTheme()) DarkPalette else LightPalette
    CompositionLocalProvider(LocalClaude provides p) {
        MaterialTheme(
            colorScheme = scheme(p),
            typography = AppTypography,
            shapes = Shapes(
                extraSmall = RoundedCornerShape(6.dp), small = RoundedCornerShape(8.dp),
                medium = RoundedCornerShape(12.dp), large = RoundedCornerShape(16.dp),
                extraLarge = RoundedCornerShape(24.dp),
            ),
            content = content,
        )
    }
}

// ---- Shared pieces ----

@Composable
fun claudeTopBarColors(): TopAppBarColors {
    val c = Claude.colors
    return TopAppBarDefaults.topAppBarColors(
        containerColor = c.bg, scrolledContainerColor = c.bg,
        navigationIconContentColor = c.text, titleContentColor = c.text, actionIconContentColor = c.muted,
    )
}

@Composable
fun claudeFieldColors(): TextFieldColors {
    val c = Claude.colors
    return OutlinedTextFieldDefaults.colors(
        focusedBorderColor = c.accentButton, unfocusedBorderColor = c.borderStrong,
        focusedLabelColor = c.accentButton, unfocusedLabelColor = c.muted,
        cursorColor = c.accentButton,
        focusedContainerColor = c.surface, unfocusedContainerColor = c.surface,
        focusedSupportingTextColor = c.faint, unfocusedSupportingTextColor = c.faint,
    )
}

@Composable
fun claudeSwitchColors() = Claude.colors.let { c ->
    SwitchDefaults.colors(
        checkedThumbColor = Color.White, checkedTrackColor = c.accentButton, checkedBorderColor = c.accentButton,
        uncheckedThumbColor = c.faint, uncheckedTrackColor = c.soft, uncheckedBorderColor = c.borderStrong,
    )
}

private val ButtonShape = RoundedCornerShape(10.dp)
private val ButtonPadding = PaddingValues(horizontal = 18.dp, vertical = 10.dp)

/** Ink-on-paper primary button, as claude.ai uses for its main actions. */
@Composable
fun PrimaryButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, content: @Composable RowScope.() -> Unit) {
    val c = Claude.colors
    Button(
        onClick = onClick, modifier = modifier, enabled = enabled, shape = ButtonShape, contentPadding = ButtonPadding,
        colors = ButtonDefaults.buttonColors(
            containerColor = c.text, contentColor = c.bg,
            disabledContainerColor = c.raised, disabledContentColor = c.faint,
        ),
        content = content,
    )
}

/** Hairline-bordered secondary button. */
@Composable
fun SecondaryButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, content: @Composable RowScope.() -> Unit) {
    val c = Claude.colors
    OutlinedButton(
        onClick = onClick, modifier = modifier, enabled = enabled, shape = ButtonShape, contentPadding = ButtonPadding,
        border = BorderStroke(1.dp, c.borderStrong),
        colors = ButtonDefaults.outlinedButtonColors(containerColor = c.surface, contentColor = c.text),
        content = content,
    )
}
