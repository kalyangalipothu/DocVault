package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

private val ColorSurfaceLight = androidx.compose.ui.graphics.Color(0xFFF2EFE8)

private val LightColors = lightColorScheme(
    primary = DeepTeal,
    onPrimary = OnDeepTeal,
    primaryContainer = TealContainer,
    onPrimaryContainer = OnTealContainer,
    secondary = WarmAmber,
    onSecondary = OnWarmAmber,
    secondaryContainer = AmberContainer,
    onSecondaryContainer = OnAmberContainer,
    tertiary = ArchivalSlate,
    onTertiary = OnArchivalSlate,
    tertiaryContainer = SlateContainer,
    onTertiaryContainer = OnSlateContainer,
    background = IvoryBackground,
    onBackground = InkPrimary,
    surface = IvorySurface,
    onSurface = InkPrimary,
    surfaceVariant = IvorySurfaceVariant,
    onSurfaceVariant = InkSecondary,
    surfaceContainerLowest = IvoryCard,
    surfaceContainerLow = IvorySurface,
    surfaceContainer = ColorSurfaceLight,
    surfaceContainerHigh = IvorySurfaceVariant,
    outline = WarmOutline,
    outlineVariant = WarmOutlineVariant,
    error = VaultErrorLight
)

private val DarkColors = darkColorScheme(
    primary = LuminousTeal,
    onPrimary = OnLuminousTeal,
    primaryContainer = DarkTealContainer,
    onPrimaryContainer = OnDarkTealContainer,
    secondary = VaultGold,
    onSecondary = OnVaultGold,
    secondaryContainer = DarkGoldContainer,
    onSecondaryContainer = OnDarkGoldContainer,
    tertiary = MistBlue,
    onTertiary = OnMistBlue,
    tertiaryContainer = DarkBlueContainer,
    onTertiaryContainer = OnDarkBlueContainer,
    background = ObsidianBackground,
    onBackground = AlabasterPrimary,
    surface = ObsidianSurface,
    onSurface = AlabasterPrimary,
    surfaceVariant = ObsidianSurfaceVariant,
    onSurfaceVariant = AlabasterSecondary,
    surfaceContainerLowest = ObsidianBackground,
    surfaceContainerLow = ObsidianSurface,
    surfaceContainer = ObsidianCard,
    surfaceContainerHigh = ObsidianSurfaceVariant,
    outline = DarkOutline,
    outlineVariant = DarkOutlineVariant,
    error = VaultErrorDark
)

val DocVaultShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

@Composable
fun DocVaultTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColors else LightColors
    MaterialTheme(
        colorScheme = colorScheme,
        typography = DocVaultTypography,
        shapes = DocVaultShapes,
        content = content
    )
}
