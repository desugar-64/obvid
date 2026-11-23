// Copyright 2026, Serhii Yaremych (desugar-64) and the Obvid project contributors
// SPDX-License-Identifier: Apache-2.0

package dev.serhiiyaremych.obvid.sample.discover

import androidx.compose.foundation.background
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import dev.serhiiyaremych.obvid.sample.ShadowImpl
import dev.serhiiyaremych.obvid.sample.measuredDropShadow
import dev.serhiiyaremych.obvid.sample.measuredInnerShadow

/**
 * A card drawn by [impl] in [palette]: ambient ([DepthTokens.surface]) and key ([DepthTokens.lift]) drop shadows stacked, the card
 * fill, a top [DepthTokens.sheen] and, with [rim], the warm [DepthTokens.focus] rim on the top-right corner.
 */
fun Modifier.cardSurface(impl: ShadowImpl, palette: DiscoverPalette, shape: Shape = DiscoverShapes.Card, rim: Boolean = true): Modifier =
  measuredDropShadow(shape, palette.depth.surface, impl)
    .measuredDropShadow(shape, palette.depth.lift, impl)
    .background(palette.brushes.card, shape)
    .measuredInnerShadow(shape, palette.depth.sheen, impl)
    .then(if (rim) Modifier.measuredInnerShadow(shape, palette.depth.focus, impl) else Modifier)

/** A raised element drawn by [impl] in [palette]: [DepthTokens.lift] below, [fill] and a [DepthTokens.sheen] on top. */
fun Modifier.raised(impl: ShadowImpl, palette: DiscoverPalette, shape: Shape, fill: Color = palette.colors.raised): Modifier =
  measuredDropShadow(shape, palette.depth.lift, impl).background(fill, shape).measuredInnerShadow(shape, palette.depth.sheen, impl)

/** [rest] (if any) crossfaded to [accent] by [fraction], read in the draw phase. */
fun Modifier.accentFill(shape: Shape, rest: Color?, accent: Brush, fraction: () -> Float): Modifier =
  drawWithCache {
    val outline = shape.createOutline(size, layoutDirection, this)
    onDrawBehind {
      val f = fraction()
      if (rest != null && f < 1f) drawOutline(outline, rest)
      if (f > 0f) drawOutline(outline, accent, alpha = f)
    }
  }

/** [DepthTokens.glow] in [color], drawn by [impl]. Place it before the element's own drop shadow, so it lies beneath. */
fun Modifier.outerGlow(impl: ShadowImpl, palette: DiscoverPalette, shape: Shape, color: Color): Modifier =
  measuredDropShadow(shape, palette.depth.glow.withColor(color), impl)

/** [outerGlow] faded by [fraction] (read in the draw phase), for a glow that comes with selection. */
fun Modifier.outerGlow(impl: ShadowImpl, palette: DiscoverPalette, shape: Shape, color: Color, fraction: () -> Float): Modifier =
  measuredDropShadow(shape, impl) {
    blend(palette.depth.glow)
    this.color = color
    alpha = palette.depth.glow.alpha * fraction()
  }

/**
 * The glassy top edge of an accented element: the narrow [DepthTokens.rim] light inside and the [RimBorder] along the
 * outline, both at [fraction] (read in the draw phase). Place it after the fill.
 */
fun Modifier.rim(impl: ShadowImpl, palette: DiscoverPalette, shape: Shape, fraction: () -> Float = { 1f }): Modifier =
  measuredInnerShadow(shape, impl) {
    blend(palette.depth.rim)
    alpha = palette.depth.rim.alpha * fraction()
  }.rimBorder(shape, fraction)

/** The [RimBorder] alone, at [fraction] (read in the draw phase), for an element that animates its rim light itself. */
fun Modifier.rimBorder(shape: Shape, fraction: () -> Float = { 1f }): Modifier =
  drawWithCache {
    val outline = shape.createOutline(size, layoutDirection, this)
    val stroke = Stroke(RimWidth.toPx())
    onDrawBehind {
      val f = fraction()
      if (f > 0f) drawOutline(outline, RimBorder, alpha = f, style = stroke)
    }
  }
