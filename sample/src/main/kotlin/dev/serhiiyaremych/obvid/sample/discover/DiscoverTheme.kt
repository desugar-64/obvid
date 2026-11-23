// Copyright 2026, Serhii Yaremych (desugar-64) and the Obvid project contributors
// SPDX-License-Identifier: Apache-2.0

package dev.serhiiyaremych.obvid.sample.discover

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.serhiiyaremych.obvid.sample.DemoPalette
import dev.serhiiyaremych.obvid.sample.LocalDemoPalette

/**
 * Colors of the Discover screens, mapped from the Material scheme's roles: the page on a mid container tone and cards
 * and raised elements on higher (dark) or lighter (light) ones; text from the on-surface roles; selection from
 * primary; actions from tertiary (the harmonized Android green); the warm rim from the harmonized amber custom color.
 */
@Immutable
class DiscoverColors(palette: DemoPalette) {
  private val scheme = palette.scheme
  val background = scheme.surfaceContainer
  val card = if (palette.dark) scheme.surfaceContainerHighest else scheme.surfaceContainerLowest
  val raised = if (palette.dark) scheme.surfaceBright else scheme.surfaceContainerLowest
  val well = if (palette.dark) scheme.surfaceContainerLowest else scheme.surfaceContainerHigh
  val text = scheme.onSurface
  val muted = scheme.onSurfaceVariant
  val primary = scheme.primary
  val onPrimary = scheme.onPrimary
  val action = scheme.tertiary
  val onAction = scheme.onTertiary
  val warm = palette.warm.color
  val warmContainer = palette.warm.container
  val divider = scheme.outlineVariant

  /** The drop shadows' deep shade: [primary] taken halfway to black in Oklab (light), black (dark). */
  val shade = if (palette.dark) Color.Black else lerp(scheme.primary, Color.Black, 0.5f)
}

/** Fills of the Discover screens. */
@Immutable
class DiscoverBrushes(colors: DiscoverColors) {
  val background: Brush = SolidColor(colors.background)
  val card: Brush = SolidColor(colors.card)

  /** The page background fading in from clear, over content scrolled under the bottom edge. */
  val scrimFade: Brush = Brush.verticalGradient(listOf(colors.background.copy(alpha = 0f), colors.background))

  /** Selection (chips, nav items): primary. */
  val accent: Brush = SolidColor(colors.primary)

  /** The brand mark (app icon, accent swatch): primary to the action green, corner to corner. */
  val brand: Brush = Brush.linearGradient(listOf(colors.primary, colors.action))

  /** Top-to-bottom gradient of [tint], lighter on top. */
  fun tint(tint: Color): Brush = Brush.verticalGradient(listOf(lerp(tint, Color.White, 0.22f), lerp(tint, Color.Black, 0.18f)))
}

/** Shapes of the Discover screens; one instance each, so equal shadows share their cache entries. */
object DiscoverShapes {
  private val CardRadius = 24.dp
  val Card = RoundedCornerShape(CardRadius)

  /** Photos flush in a card's padded area, concentric with the card: card radius less that card's padding. */
  val ThumbPhoto = insideCard(StandardCardPadding)
  val WidePhoto = insideCard(HeroCardPadding)
  val SmallPhoto = insideCard(CompactCardPadding)

  /** A photo standing on the page, outside any card. */
  val Photo = RoundedCornerShape(18.dp)
  val Pill = RoundedCornerShape(percent = 50)
  val Button = RoundedCornerShape(14.dp)
  val Toggle = RoundedCornerShape(12.dp)
  val IconTile = RoundedCornerShape(18.dp)
  val NavBar = RoundedCornerShape(30.dp)
  val NavItem = RoundedCornerShape(20.dp)
  val Swatch = RoundedCornerShape(16.dp)
  val Well = RoundedCornerShape(22.dp)
  val Circle = CircleShape

  private fun insideCard(padding: Dp) = RoundedCornerShape(CardRadius - padding)
}

/** Text styles of the Discover screens; [label] and [navLabel] take their color from the component. */
@Immutable
class DiscoverText(colors: DiscoverColors) {
  val headline = TextStyle(color = colors.text, fontSize = 28.sp, lineHeight = 32.sp, fontWeight = FontWeight.SemiBold)
  val detailTitle = headline.copy(fontSize = 24.sp, lineHeight = 29.sp)
  val subtitle = TextStyle(color = colors.muted, fontSize = 14.sp, lineHeight = 18.sp)
  val title = TextStyle(color = colors.text, fontSize = 17.sp, lineHeight = 21.sp, fontWeight = FontWeight.SemiBold)
  val smallTitle = TextStyle(color = colors.text, fontSize = 15.sp, lineHeight = 19.sp, fontWeight = FontWeight.SemiBold)
  val body = TextStyle(color = colors.muted, fontSize = 13.sp, lineHeight = 17.sp)
  val note = body.copy(color = colors.text, fontSize = 14.sp, lineHeight = 19.sp)
  val meta = TextStyle(color = colors.muted, fontSize = 12.sp, lineHeight = 16.sp)
  val caption = meta.copy(textAlign = TextAlign.Center)
  val captionStrong = caption.copy(color = colors.text)
  val label = TextStyle(fontSize = 15.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium)
  val smallLabel = label.copy(fontSize = 13.sp)
  val navLabel = TextStyle(fontSize = 12.sp, lineHeight = 14.sp, fontWeight = FontWeight.Medium)
  val section = TextStyle(color = colors.primary, fontSize = 18.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold)
}

/** Everything the Discover screens draw with in one mode; built once per mode. */
@Immutable
class DiscoverPalette private constructor(palette: DemoPalette) {
  val colors = DiscoverColors(palette)
  val brushes = DiscoverBrushes(colors)
  val text = DiscoverText(colors)
  val depth = DepthTokens(colors, palette.dark)
  val poses = DepthPoses(depth, colors, palette.dark)

  /** The play-button tint of [topic]. */
  fun tint(topic: Topic): Color =
    when (topic) {
      Topic.Travel, Topic.Nature -> colors.action
      Topic.Science, Topic.Tech -> colors.primary
      Topic.Design -> colors.warm
    }

  companion object {
    private val Light = DiscoverPalette(DemoPalette.Light)
    private val Dark = DiscoverPalette(DemoPalette.Dark)

    /** The palette for [LocalDemoPalette]'s mode. */
    val current: DiscoverPalette
      @Composable @ReadOnlyComposable
      get() = if (LocalDemoPalette.current.dark) Dark else Light
  }
}

/** Linear interpolation between two floats. */
fun mix(from: Float, to: Float, fraction: Float): Float = from + (to - from) * fraction

/** Linear interpolation between two colors. */
fun mix(from: Color, to: Color, fraction: Float): Color = lerp(from, to, fraction)
