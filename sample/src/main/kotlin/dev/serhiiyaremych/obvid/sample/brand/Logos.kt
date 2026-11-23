// Copyright 2026, Serhii Yaremych (desugar-64) and the Obvid project contributors
// SPDX-License-Identifier: Apache-2.0

package dev.serhiiyaremych.obvid.sample.brand

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import dev.serhiiyaremych.obvid.sample.DemoPalette
import dev.serhiiyaremych.obvid.sample.LocalDemoPalette
import dev.serhiiyaremych.obvid.sample.LocalShadowImpl
import dev.serhiiyaremych.obvid.sample.ShadowImpl
import dev.serhiiyaremych.obvid.sample.discover.DiscoverPalette
import dev.serhiiyaremych.obvid.sample.measuredDropShadow
import dev.serhiiyaremych.obvid.sample.measuredInnerShadow
import kotlin.math.sqrt

// The ring's shadows are sized in units of its diameter / (RingFraction × 512) (`u` below), the 512-unit design grid of
// the logo, so one composable serves any size. Below CompactBelowPx pixels the logo switches to compact proportions:
// a larger, thicker ring and tighter shadows that still read at favicon size. Every shadow is Obvid's
// (ShadowImpl.Cpu); colors come from the light Discover palette.

/** Side of the design grid, in logo units. */
private const val DesignUnits = 512f

/** Logos smaller than this, in pixels, use the compact proportions. */
private const val CompactBelowPx = 96f

/** Ring diameter over the logo's side: standard, compact, and the avatar's (inside a circular crop with its glow). */
private const val RingFraction = 0.70f
private const val CompactRingFraction = 0.78f
private const val AvatarRingFraction = 0.58f

/** Hole diameter over the ring's diameter: standard (also the wordmark's), and thicker at compact size. */
const val RingHole = 0.50f
private const val CompactRingHole = 0.46f

/**
 * The Obvid logo, the "split O": a thick ring cut on the top-left to bottom-right diagonal into primary blue (top
 * right) and tertiary green (bottom left) halves, each in its own tonal gradient, lifted off the page by a blue-to-green
 * glow and a close key shadow, with an inner shadow inside its hole so the hole reads as recessed, and a thin rim light
 * along the edges facing up. [page] fills the square with the Discover page color; without it the background is
 * transparent (favicon). [avatar] shrinks and centres the ring so it and its glow fit a circular crop. Square: draws in
 * the largest centred square of its bounds.
 */
@Composable
fun ObvidLogo(modifier: Modifier = Modifier, page: Boolean = false, avatar: Boolean = false) {
  val palette = DiscoverPalette.current
  BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
    val side = min(maxWidth, maxHeight)
    val compact = with(LocalDensity.current) { side.toPx() } < CompactBelowPx
    val fraction = when {
      compact -> CompactRingFraction
      avatar -> AvatarRingFraction
      else -> RingFraction
    }
    val diameter = side * fraction
    // Optical centring: raised a little, as the glow below weighs the mark down; the avatar stays centred for its crop.
    val raise = if (compact || avatar) 0.dp else side / DesignUnits * 8f
    Box(
      Modifier.size(side).then(if (page) Modifier.background(palette.colors.background) else Modifier),
      contentAlignment = Alignment.Center,
    ) {
      RingMark(
        Modifier.offset(y = -raise).size(diameter),
        u = ringUnit(diameter),
        compact = compact,
        hole = if (compact) CompactRingHole else RingHole,
      )
    }
  }
}

/** The shadow unit `u` of a ring of [diameter] (see the note at the top of this file). */
fun ringUnit(diameter: Dp): Dp = diameter / (RingFraction * DesignUnits)

/** The ring's shadows in unit [u]: [glow] and [key] below it, [hole] inside its hole, [rim] on it (null: none). */
private class RingShadows(palette: DiscoverPalette, u: Dp, compact: Boolean) {
  val glow = Shadow(
    radius = if (compact) u * 20f else u * 40f,
    brush = AntiDiagonalBrush(palette.colors.primary, palette.colors.action),
    offset = DpOffset(0.dp, if (compact) u * 6f else u * 14f),
    alpha = 0.3f,
  )

  val key = if (compact) Shadow(radius = u * 6f, color = palette.colors.shade, offset = DpOffset(0.dp, u * 3f), alpha = 0.3f)
  else Shadow(radius = u * 12f, color = palette.colors.shade, offset = DpOffset(0.dp, u * 6f), alpha = 0.3f)

  val hole = if (compact) Shadow(radius = u * 12f, color = Color.Black, offset = DpOffset(0.dp, u * 4f), alpha = 0.28f)
  else Shadow(radius = u * 16f, color = Color.Black, offset = DpOffset(0.dp, u * 6f), alpha = 0.3f)

  /** Sub-pixel at favicon size, so none there. */
  val rim: Shadow? = if (compact) null
  else Shadow(radius = u * 3f, color = Color.White, offset = DpOffset(0.dp, u * 2.5f), alpha = 0.5f)
}

/**
 * The split ring itself, filling [modifier]'s square, with a hole of [hole] times its diameter and its [RingShadows]
 * in unit [u] ([ringUnit]).
 */
@Composable
fun RingMark(modifier: Modifier, u: Dp, compact: Boolean = false, hole: Float = RingHole) {
  val palette = DiscoverPalette.current
  val impl = ShadowImpl.Cpu
  val ring = remember(hole) { RingShape(hole) }
  val shadows = remember(palette, u, compact) { RingShadows(palette, u, compact) }
  Box(modifier.measuredDropShadow(ring, shadows.glow, impl).measuredDropShadow(ring, shadows.key, impl)) {
    // The hole's circle, a touch wider than the hole so the ring covers its edge (no seam between the two).
    Box(Modifier.align(Alignment.Center).fillMaxSize(hole + 0.01f).measuredInnerShadow(CircleShape, shadows.hole, impl))
    Box(
      Modifier
        .fillMaxSize()
        .splitFill(ring, palette.colors.primary, palette.colors.action)
        .then(shadows.rim?.let { Modifier.measuredInnerShadow(ring, it, impl) } ?: Modifier),
    )
  }
}

/**
 * [ring] filled with [first] in the top-right half and [second] in the bottom-left one: one gradient along the
 * top-right to bottom-left diagonal, so the colour edge at its middle runs top-left to bottom-right; the edge is a
 * hard stop softened over about one pixel.
 */
private fun Modifier.splitFill(ring: RingShape, first: Color, second: Color): Modifier =
  drawWithCache {
    val path = (ring.createOutline(size, layoutDirection, this) as Outline.Generic).path
    val half = 0.5f / (size.width * sqrt(2f))
    val fill = Brush.linearGradient(
      0f to lerp(first, Color.White, 0.24f),
      0.5f - half to lerp(first, Color.Black, 0.08f),
      0.5f + half to lerp(second, Color.White, 0.14f),
      1f to lerp(second, Color.Black, 0.16f),
      start = Offset(size.width, 0f),
      end = Offset(0f, size.height),
    )
    onDrawBehind { drawPath(path, fill) }
  }

/** The brand art's frame: the light palette and Obvid's shadows, whatever the device's mode or the sample's switch. */
@Composable
fun BrandTheme(content: @Composable () -> Unit) {
  CompositionLocalProvider(LocalDemoPalette provides DemoPalette.Light, LocalShadowImpl provides ShadowImpl.Cpu, content = content)
}

@Preview(name = "Logo, on page", widthDp = 256, heightDp = 256)
@Composable
private fun LogoPreview() = BrandTheme { ObvidLogo(Modifier.size(256.dp), page = true) }

@Preview(name = "Logo, avatar", widthDp = 256, heightDp = 256)
@Composable
private fun LogoAvatarPreview() = BrandTheme { ObvidLogo(Modifier.size(256.dp), page = true, avatar = true) }

@Preview(name = "Logo, transparent", widthDp = 256, heightDp = 256, showBackground = true, backgroundColor = 0xFFFFFFFF)
@Composable
private fun LogoTransparentPreview() = BrandTheme { ObvidLogo(Modifier.size(256.dp)) }

@Preview(name = "Logo, favicon sizes", widthDp = 100, heightDp = 40, showBackground = true, backgroundColor = 0xFFFFFFFF)
@Composable
private fun LogoSmallPreview() = BrandTheme {
  // In a preview a dp is several pixels, so these are not the 32 px render; BrandRenderActivity draws that one.
  Row {
    ObvidLogo(Modifier.size(16.dp))
    Spacer(Modifier.width(8.dp))
    ObvidLogo(Modifier.size(32.dp))
  }
}
