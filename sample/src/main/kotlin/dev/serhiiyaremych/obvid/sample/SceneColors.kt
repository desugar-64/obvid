// Copyright 2026, Serhii Yaremych (desugar-64) and the Obvid project contributors
// SPDX-License-Identifier: Apache-2.0

package dev.serhiiyaremych.obvid.sample

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color

/** Number of [SceneColors.hues]. */
const val SceneHueCount = 5

/**
 * Colors of the dark-or-light tier scenes (the field, the gears), mapped from [DemoPalette] roles: the field on a mid
 * surface tone with opaque container-tone tiles ([DemoPalette.sceneHues]), the gears in the lightest surface tone of
 * the mode on a container tone. The glow scene's colors are [GlowColors].
 */
@Immutable
class SceneColors private constructor(palette: DemoPalette) {
  private val scheme = palette.scheme
  val field = if (palette.dark) scheme.surfaceContainerHigh else scheme.surface
  val gears = if (palette.dark) scheme.surfaceContainerHighest else scheme.surfaceContainerHigh
  val gearMetal = if (palette.dark) scheme.onSurfaceVariant else scheme.surfaceContainerLowest
  val hues = palette.sceneHues

  companion object {
    val Light = SceneColors(DemoPalette.Light)

    /** The benchmark scenes' fixed colors (the dark palette). */
    val Dark = SceneColors(DemoPalette.Dark)

    /** The colors for [LocalDemoPalette]'s mode. */
    val current: SceneColors
      @Composable @ReadOnlyComposable
      get() = if (LocalDemoPalette.current.dark) Dark else Light
  }
}

/**
 * The glow scene's colors, mapped from [DemoPalette] roles: the page behind (the darkest surface in dark mode, the
 * Discover page tone in light), the surfaces (a container tone in dark, the lightest surface in light), on-surface
 * text, and the [vivid] scene hues (harmonized to the seed) for the gradient.
 */
@Immutable
class GlowColors private constructor(palette: DemoPalette) {
  private val scheme = palette.scheme
  val background: Color = if (palette.dark) scheme.surfaceContainerLowest else scheme.surfaceContainer
  val surface: Color = if (palette.dark) scheme.surfaceContainerHigh else scheme.surfaceContainerLowest
  val text: Color = scheme.onSurface
  val hint: Color = scheme.onSurfaceVariant
  val vivid: List<Color> = palette.sceneHues.map { it.vivid }

  companion object {
    val Light = GlowColors(DemoPalette.Light)

    /** The benchmark scenes' fixed colors (the dark palette). */
    val Dark = GlowColors(DemoPalette.Dark)

    /** The colors for [LocalDemoPalette]'s mode. */
    val current: GlowColors
      @Composable @ReadOnlyComposable
      get() = if (LocalDemoPalette.current.dark) Dark else Light
  }
}
