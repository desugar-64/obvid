// Copyright 2026, Serhii Yaremych (desugar-64) and the Obvid project contributors
// SPDX-License-Identifier: Apache-2.0

package dev.serhiiyaremych.obvid.sample

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

// Generated once with Material Color Utilities 0.3.0, as Material Theme Builder does: seed #4285F4, SchemeTonalSpot,
// contrast 0; custom colors harmonized to the seed (Blend.harmonize, tones 40/100/90/10 light, 80/20/30/90 dark).

/** Android green #3DDC84 as a custom color harmonized to the seed: the actions' accent, and the schemes' tertiary. */
private val GreenLight = CustomColorRoles(Color(0xFF006C52), Color(0xFFFFFFFF), Color(0xFF4DFDC9), Color(0xFF002117))
private val GreenDark = CustomColorRoles(Color(0xFF16E0AE), Color(0xFF003829), Color(0xFF00513D), Color(0xFF4DFDC9))

/** Amber #FFB35C as a custom color harmonized to the seed: the warm rim light. */
private val AmberLight = CustomColorRoles(Color(0xFF954906), Color(0xFFFFFFFF), Color(0xFFFFDBC7), Color(0xFF311300))
private val AmberDark = CustomColorRoles(Color(0xFFFFB688), Color(0xFF512400), Color(0xFF733600), Color(0xFFFFDBC7))

/** Seed #4285F4, TonalSpot, light; tertiary from [GreenLight]. */
val DemoLightScheme: ColorScheme = lightColorScheme(
  primary = Color(0xFF445E91),
  onPrimary = Color(0xFFFFFFFF),
  primaryContainer = Color(0xFFD8E2FF),
  onPrimaryContainer = Color(0xFF2B4678),
  inversePrimary = Color(0xFFADC6FF),
  secondary = Color(0xFF575E71),
  onSecondary = Color(0xFFFFFFFF),
  secondaryContainer = Color(0xFFDBE2F9),
  onSecondaryContainer = Color(0xFF3F4759),
  tertiary = GreenLight.color,
  onTertiary = GreenLight.onColor,
  tertiaryContainer = GreenLight.container,
  onTertiaryContainer = GreenLight.onContainer,
  background = Color(0xFFF9F9FF),
  onBackground = Color(0xFF1A1B20),
  surface = Color(0xFFF9F9FF),
  onSurface = Color(0xFF1A1B20),
  surfaceVariant = Color(0xFFE1E2EC),
  onSurfaceVariant = Color(0xFF44474F),
  surfaceTint = Color(0xFF445E91),
  inverseSurface = Color(0xFF2F3036),
  inverseOnSurface = Color(0xFFF0F0F7),
  error = Color(0xFFBA1A1A),
  onError = Color(0xFFFFFFFF),
  errorContainer = Color(0xFFFFDAD6),
  onErrorContainer = Color(0xFF93000A),
  outline = Color(0xFF74777F),
  outlineVariant = Color(0xFFC4C6D0),
  scrim = Color(0xFF000000),
  surfaceBright = Color(0xFFF9F9FF),
  surfaceDim = Color(0xFFD9D9E0),
  surfaceContainer = Color(0xFFEDEDF4),
  surfaceContainerHigh = Color(0xFFE8E7EE),
  surfaceContainerHighest = Color(0xFFE2E2E9),
  surfaceContainerLow = Color(0xFFF3F3FA),
  surfaceContainerLowest = Color(0xFFFFFFFF),
)

/** Seed #4285F4, TonalSpot, dark; tertiary from [GreenDark]. */
val DemoDarkScheme: ColorScheme = darkColorScheme(
  primary = Color(0xFFADC6FF),
  onPrimary = Color(0xFF102F60),
  primaryContainer = Color(0xFF2B4678),
  onPrimaryContainer = Color(0xFFD8E2FF),
  inversePrimary = Color(0xFF445E91),
  secondary = Color(0xFFBFC6DC),
  onSecondary = Color(0xFF293041),
  secondaryContainer = Color(0xFF3F4759),
  onSecondaryContainer = Color(0xFFDBE2F9),
  tertiary = GreenDark.color,
  onTertiary = GreenDark.onColor,
  tertiaryContainer = GreenDark.container,
  onTertiaryContainer = GreenDark.onContainer,
  background = Color(0xFF111318),
  onBackground = Color(0xFFE2E2E9),
  surface = Color(0xFF111318),
  onSurface = Color(0xFFE2E2E9),
  surfaceVariant = Color(0xFF44474F),
  onSurfaceVariant = Color(0xFFC4C6D0),
  surfaceTint = Color(0xFFADC6FF),
  inverseSurface = Color(0xFFE2E2E9),
  inverseOnSurface = Color(0xFF2F3036),
  error = Color(0xFFFFB4AB),
  onError = Color(0xFF690005),
  errorContainer = Color(0xFF93000A),
  onErrorContainer = Color(0xFFFFDAD6),
  outline = Color(0xFF8E9099),
  outlineVariant = Color(0xFF44474F),
  scrim = Color(0xFF000000),
  surfaceBright = Color(0xFF37393E),
  surfaceDim = Color(0xFF111318),
  surfaceContainer = Color(0xFF1E1F25),
  surfaceContainerHigh = Color(0xFF282A2F),
  surfaceContainerHighest = Color(0xFF33353A),
  surfaceContainerLow = Color(0xFF1A1B20),
  surfaceContainerLowest = Color(0xFF0C0E13),
)

/** A Theme Builder custom color's four roles. */
@Immutable
class CustomColorRoles(val color: Color, val onColor: Color, val container: Color, val onContainer: Color)

/**
 * One accent hue of the scenes, from a source color harmonized to the seed: [vivid] (the harmonized source, for the
 * glows), and this mode's [container] (tone 90 light, 30 dark; tile fills) and [shadow] (tone 45 light,
 * 70 dark; the tile's coloured shadow), at chroma 48 or more.
 */
@Immutable
class SceneHue(val vivid: Color, val container: Color, val shadow: Color)

/** Scene hues for blue #4285F4, teal #00BFA5, green #3DDC84, amber #FFB300 and coral #FF6E6E, in that order. */
private val SceneHuesLight = listOf(
  SceneHue(Color(0xFF4285F4), Color(0xFFD8E2FF), Color(0xFF1267D4)),
  SceneHue(Color(0xFF00BDBB), Color(0xFF69F7F5), Color(0xFF007776)),
  SceneHue(Color(0xFF00DCAA), Color(0xFF4DFDC9), Color(0xFF00795C)),
  SceneHue(Color(0xFFFFB160), Color(0xFFFFDCBE), Color(0xFF9C5B00)),
  SceneHue(Color(0xFFFB6C94), Color(0xFFFFD9DF), Color(0xFFBB3B63)),
)
private val SceneHuesDark = listOf(
  SceneHue(Color(0xFF4285F4), Color(0xFF004494), Color(0xFF80AAFF)),
  SceneHue(Color(0xFF00BDBB), Color(0xFF00504F), Color(0xFF08BFBC)),
  SceneHue(Color(0xFF00DCAA), Color(0xFF00513D), Color(0xFF00C296)),
  SceneHue(Color(0xFFFFB160), Color(0xFF693C00), Color(0xFFEB993E)),
  SceneHue(Color(0xFFFB6C94), Color(0xFF8A1240), Color(0xFFFF85A4)),
)

/** The demo's colors in one mode: the Material [scheme], the [action] and [warm] custom colors and the [sceneHues]. */
@Immutable
class DemoPalette private constructor(
  val dark: Boolean,
  val scheme: ColorScheme,
  val action: CustomColorRoles,
  val warm: CustomColorRoles,
  val sceneHues: List<SceneHue>,
) {
  companion object {
    val Light = DemoPalette(dark = false, DemoLightScheme, GreenLight, AmberLight, SceneHuesLight)
    val Dark = DemoPalette(dark = true, DemoDarkScheme, GreenDark, AmberDark, SceneHuesDark)
  }
}

/**
 * The palette in effect. Dark without a provider: the benchmark scenes always draw in the dark palette, whatever the
 * device's setting; the demo app provides the one matching the system setting.
 */
val LocalDemoPalette = staticCompositionLocalOf { DemoPalette.Dark }
