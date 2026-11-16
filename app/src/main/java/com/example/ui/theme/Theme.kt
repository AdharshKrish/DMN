package com.example.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Immutable
data class SynapseSemanticColors(
    val emerald: Color,
    val emeraldContainer: Color,
    val dailyBlue: Color,
    val dailyBlueContainer: Color,
    val insightPurple: Color,
    val insightPurpleContainer: Color,
    val amber: Color,
    val amberContainer: Color,
    val recordingCrimson: Color,
    val recordingCrimsonContainer: Color,
    val ghostNode: Color,
    val cardBorder: Color
)

private val DarkSemanticColors = SynapseSemanticColors(
    emerald = ActionEmerald,
    emeraldContainer = ActionEmeraldDim,
    dailyBlue = DailyBlue,
    dailyBlueContainer = DailyBlueDim,
    insightPurple = InsightPurple,
    insightPurpleContainer = InsightPurpleDim,
    amber = RealizationAmber,
    amberContainer = RealizationAmberDim,
    recordingCrimson = RecordingCrimson,
    recordingCrimsonContainer = RecordingCrimsonDim,
    ghostNode = GhostSlate,
    cardBorder = SlateOutline
)

private val LightSemanticColors = SynapseSemanticColors(
    emerald = Color(0xFF059669),
    emeraldContainer = Color(0xFFD1FAE5),
    dailyBlue = Color(0xFF2563EB),
    dailyBlueContainer = Color(0xFFDBEAFE),
    insightPurple = PurpleLight,
    insightPurpleContainer = PurpleContainerLight,
    amber = AmberLight,
    amberContainer = AmberContainerLight,
    recordingCrimson = Color(0xFFDC2626),
    recordingCrimsonContainer = Color(0xFFFEE2E2),
    ghostNode = Color(0xFF64748B),
    cardBorder = PaperOutline
)

val LocalSynapseColors = staticCompositionLocalOf { DarkSemanticColors }

private val DarkColorScheme = darkColorScheme(
    primary = NeuralIndigo,
    onPrimary = Color(0xFF0A0D18),
    primaryContainer = NeuralIndigoDim,
    onPrimaryContainer = Color(0xFFDDE2FF),
    secondary = InsightPurple,
    onSecondary = Color(0xFF140B29),
    secondaryContainer = InsightPurpleDim,
    onSecondaryContainer = Color(0xFFEDE0FF),
    tertiary = RealizationAmber,
    onTertiary = Color(0xFF211600),
    tertiaryContainer = RealizationAmberDim,
    onTertiaryContainer = Color(0xFFFFE5AE),
    background = ObsidianInk,
    onBackground = TextPrimaryDark,
    surface = SlateSurface,
    onSurface = TextPrimaryDark,
    surfaceVariant = SlateElevated,
    onSurfaceVariant = TextSecondaryDark,
    surfaceContainerLowest = ObsidianInk,
    surfaceContainerLow = SlateSurface,
    surfaceContainer = SlateCard,
    surfaceContainerHigh = SlateElevated,
    surfaceContainerHighest = Color(0xFF273146),
    outline = SlateOutline,
    outlineVariant = SlateOutlineSubtle,
    error = RecordingCrimson,
    onError = Color(0xFF2A0909),
    errorContainer = RecordingCrimsonDim,
    onErrorContainer = Color(0xFFFFDAD8)
)

private val LightColorScheme = lightColorScheme(
    primary = IndigoLight,
    onPrimary = Color.White,
    primaryContainer = IndigoContainerLight,
    onPrimaryContainer = Color(0xFF0F1B66),
    secondary = PurpleLight,
    onSecondary = Color.White,
    secondaryContainer = PurpleContainerLight,
    onSecondaryContainer = Color(0xFF2B106B),
    tertiary = AmberLight,
    onTertiary = Color.White,
    tertiaryContainer = AmberContainerLight,
    onTertiaryContainer = Color(0xFF3E2200),
    background = PaperBackground,
    onBackground = TextPrimaryLight,
    surface = PaperSurface,
    onSurface = TextPrimaryLight,
    surfaceVariant = PaperCard,
    onSurfaceVariant = TextSecondaryLight,
    surfaceContainerLowest = PaperSurface,
    surfaceContainerLow = PaperBackground,
    surfaceContainer = PaperCard,
    surfaceContainerHigh = Color(0xFFE4E8F2),
    surfaceContainerHighest = Color(0xFFDADFEB),
    outline = PaperOutline,
    outlineVariant = Color(0xFFE3E7F0),
    error = Color(0xFFDC2626),
    onError = Color.White,
    errorContainer = Color(0xFFFEE2E2),
    onErrorContainer = Color(0xFF410E0B)
)

val SynapseShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

@Composable
fun SynapseDmnTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    val semanticColors = if (darkTheme) DarkSemanticColors else LightSemanticColors

    CompositionLocalProvider(LocalSynapseColors provides semanticColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = SynapseTypography,
            shapes = SynapseShapes,
            content = content
        )
    }
}
