/* SPDX-License-Identifier: Apache-2.0 */
package org.rab1d.petal.music.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/** Material roles derived from the Petal tokens, so stock components match. */
fun PetalColors.toMaterial(): ColorScheme {
    val common: ColorScheme = if (dark) darkColorScheme() else lightColorScheme()
    return common.copy(
        primary = accent,
        onPrimary = onAccent,
        primaryContainer = accentSoft,
        onPrimaryContainer = ink,
        secondary = text2,
        onSecondary = bg,
        secondaryContainer = surface2,
        onSecondaryContainer = ink,
        tertiary = accent,
        onTertiary = onAccent,
        tertiaryContainer = accentSoft,
        onTertiaryContainer = ink,
        background = bg,
        onBackground = ink,
        surface = bg,
        onSurface = ink,
        surfaceVariant = surface1,
        onSurfaceVariant = text2,
        surfaceTint = accent,
        surfaceBright = surface2,
        surfaceDim = bg,
        surfaceContainerLowest = bg,
        surfaceContainerLow = surface1,
        surfaceContainer = surface1,
        surfaceContainerHigh = surface2,
        surfaceContainerHighest = surface2,
        inverseSurface = ink,
        inverseOnSurface = bg,
        inversePrimary = accent,
        outline = line.copy(alpha = (line.alpha * 2.4f).coerceAtMost(1f)),
        outlineVariant = line,
        error = danger,
        onError = if (dark) bg else bg,
        errorContainer = dangerSoft,
        onErrorContainer = ink,
        scrim = androidx.compose.ui.graphics.Color.Black,
    )
}

/** True when [mode] resolves to the light theme right now. */
@Composable
fun ThemeMode.isPaper(): Boolean = when (this) {
    ThemeMode.PAPER -> true
    ThemeMode.NIGHT -> false
    ThemeMode.SYSTEM -> !isSystemInDarkTheme()
}

@Composable
fun PetalTheme(
    mode: ThemeMode = ThemeMode.SYSTEM,
    dye: Dye = Dye.ORCHID,
    content: @Composable () -> Unit,
) {
    val paper = mode.isPaper()
    val context = LocalContext.current
    val colors = remember(paper, dye) {
        if (dye == Dye.SYSTEM && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Material You: take the seed's primary for the accent, keep Petal neutrals.
            if (paper) {
                val s = dynamicLightColorScheme(context)
                paperColors(dye, accent = s.primary)
            } else {
                val s = dynamicDarkColorScheme(context)
                nightColors(dye, accent = s.primary, onAccent = s.onPrimary)
            }
        } else if (paper) paperColors(dye) else nightColors(dye)
    }
    CompositionLocalProvider(LocalPetalColors provides colors) {
        MaterialTheme(
            colorScheme = colors.toMaterial(),
            typography = PetalTypography,
            content = content,
        )
    }
}

/** Re-applies Material roles for a subtree that changed [LocalPetalColors] (e.g. [NightSurface]). */
@Composable
fun PetalMaterial(content: @Composable () -> Unit) {
    val colors = Petal.colors
    MaterialTheme(colorScheme = remember(colors) { colors.toMaterial() }, typography = PetalTypography, content = content)
}
