// Copyright 2026, Serhii Yaremych (desugar-64) and the Obvid project contributors
// SPDX-License-Identifier: Apache-2.0

package dev.serhiiyaremych.obvid.sample.discover

import androidx.compose.runtime.Immutable
import androidx.compose.ui.draw.ShadowScope
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.LinearGradientShader
import androidx.compose.ui.graphics.Shader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp

/**
 * The five shadow tokens every Discover element draws with (the design's shadow palette), plus [photo], the darker
 * [lift] under photos, [sheen], the light half of [press], and the accent pair: [glow] under and [rim] inside every
 * accented element (with [RimBorder]). On the light page the elevation shadows take the primary hue's deep
 * [DiscoverColors.shade] at a low alpha; on the dark page [surface] is a faint primary halo, which reads on the dark
 * background where a black one would not, and [lift] stays black. [photo] and [press] are black in both modes,
 * lighter on the light page.
 */
@Immutable
class DepthTokens(colors: DiscoverColors, dark: Boolean) {
  private val k = if (dark) 1f else 0.6f

  /** Surface (ambient): wide, soft and far below; the first of a card's two stacked drop shadows. */
  val surface = Shadow(
    radius = 28.dp,
    color = if (dark) colors.primary else colors.shade,
    offset = DpOffset(0.dp, 14.dp),
    alpha = if (dark) 0.2f else 0.22f,
  )

  /** Lift (8 dp): the close key shadow under a raised element. */
  val lift = Shadow(radius = 8.dp, color = colors.shade, offset = DpOffset(0.dp, 4.dp), alpha = if (dark) 0.5f else 0.2f)

  /** Photo: [lift]'s geometry, black and darker, under a photo. */
  val photo = Shadow(radius = 8.dp, color = Color.Black, offset = DpOffset(0.dp, 4.dp), alpha = if (dark) 0.5f else 0.45f)

  /** Press (inner): a dark inner shadow from the top edge, a surface pushed in. */
  val press = Shadow(radius = 8.dp, color = Color.Black, offset = DpOffset(0.dp, 3.dp), alpha = 0.6f * k)

  /** Accent (coloured): a primary glow close under a selected or primary element. */
  val accent = Shadow(radius = 16.dp, color = colors.primary, offset = DpOffset(0.dp, 4.dp), alpha = 0.6f)

  /**
   * Focus (rim): an inner shadow lit by the warm custom color from the top-right, a light catching the corner; on the
   * light page its pale container tone, faint.
   */
  val focus = Shadow(
    radius = 10.dp,
    brush = WarmRimBrush(if (dark) colors.warm else colors.warmContainer),
    offset = DpOffset((-3).dp, 3.dp),
    alpha = if (dark) 1f else 0.4f,
  )

  /** A thin white inner shadow along the top edge that lights the surface. */
  val sheen = Shadow(radius = 2.dp, color = Color.White, offset = DpOffset(0.dp, 1.dp), alpha = 0.14f)

  /** Outer glow: wide, soft and faint, in the element's accent color, under its own elevation shadow. */
  val glow = Shadow(radius = 36.dp, color = colors.primary, offset = DpOffset(0.dp, 10.dp), alpha = 0.3f)

  /** Rim light: a narrow bright inner shadow under the [RimBorder], the glassy top edge of an accented element. */
  val rim = Shadow(radius = 1.5.dp, color = Color.White, offset = DpOffset(0.dp, 1.dp), alpha = 0.5f)
}

/** Width of the rim's border. */
val RimWidth = 1.dp

/** The rim's border: bright along the top edge, clear by the middle. */
val RimBorder = Brush.verticalGradient(0f to Color.White.copy(alpha = 0.6f), 0.55f to Color.Transparent)

/**
 * The end poses of the animated shadows, derived from [DepthTokens]; each pair differs only in what animates (see the
 * component that uses it).
 */
@Immutable
class DepthPoses(depth: DepthTokens, colors: DiscoverColors, dark: Boolean) {
  /** Black shadows here are lighter on the light page. */
  private val k = if (dark) 1f else 0.6f

  /** Unselected chip: [DepthTokens.accent]'s geometry, dark and faint; its dark inner edge, and that edge faded out. */
  val chipGlowRest = depth.accent.withColor(Color.Black, alpha = 0.35f * k)
  val chipEdgeRest = Shadow(radius = 5.dp, color = depth.press.color, offset = DpOffset(0.dp, 2.dp), alpha = depth.press.alpha)
  val chipEdgeOff = chipEdgeRest.withAlpha(0f)

  /** Unselected nav item: its accent shadow faded out. */
  val navAccentOff = depth.accent.withAlpha(0f)

  val bookmarkDropOff = Shadow(radius = 4.dp, color = Color.Black, offset = DpOffset(0.dp, 2.dp), alpha = 0.45f * k)
  val bookmarkDropOn = Shadow(radius = 12.dp, color = colors.action, offset = DpOffset(0.dp, 3.dp), alpha = 0.5f)
  val bookmarkWell = Shadow(radius = 5.dp, color = depth.press.color, offset = DpOffset(0.dp, 2.5.dp), alpha = 0.5f)

  /** Play button drop shadow at rest and pressed, recolored with the button's tint. */
  val playDropRest = Shadow(radius = 12.dp, color = colors.action, offset = DpOffset(0.dp, 5.dp), alpha = 0.55f)
  val playDropPressed = Shadow(radius = 3.dp, color = colors.action, offset = DpOffset(0.dp, 1.dp), alpha = 0.3f)
}

/** [warm] at the top-right corner fading to clear towards the centre, scaled to the element it paints. */
private class WarmRimBrush(private val warm: Color) : ShaderBrush() {
  override fun createShader(size: Size): Shader {
    val reach = 0.6f * size.minDimension
    return LinearGradientShader(
      from = Offset(size.width, 0f),
      to = Offset(size.width - reach, reach),
      colors = listOf(warm, warm.copy(alpha = 0f)),
    )
  }
}

/** This color shadow with [alpha]. */
fun Shadow.withAlpha(alpha: Float): Shadow = withColor(color, alpha)

/** This color shadow with [color] and [alpha]. */
fun Shadow.withColor(color: Color, alpha: Float = this.alpha): Shadow =
  Shadow(radius = radius, color = color, spread = spread, offset = offset, alpha = alpha, blendMode = blendMode)

/**
 * Sets a shadow block to [from] blended towards [to] by [fraction] (radius, offset, color, alpha; spread from [from]),
 * for shadows animated in the draw phase. Both must be color shadows.
 */
fun ShadowScope.blend(from: Shadow, to: Shadow = from, fraction: Float = 0f) {
  radius = mix(from.radius.toPx(), to.radius.toPx(), fraction)
  spread = from.spread.toPx()
  offset = Offset(mix(from.offset.x.toPx(), to.offset.x.toPx(), fraction), mix(from.offset.y.toPx(), to.offset.y.toPx(), fraction))
  color = mix(from.color, to.color, fraction)
  alpha = mix(from.alpha, to.alpha, fraction)
}
