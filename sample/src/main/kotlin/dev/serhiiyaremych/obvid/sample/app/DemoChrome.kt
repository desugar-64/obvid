// Copyright 2026, Serhii Yaremych (desugar-64) and the Obvid project contributors
// SPDX-License-Identifier: Apache-2.0

package dev.serhiiyaremych.obvid.sample.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastForEach
import kotlin.math.max

/** Opacity of the chrome scrims: the page color over content scrolled under the chrome. */
private const val ScrimAlpha = 0.9f

/** Length of a scrim's fade past the chrome's edge, to clear. */
val ChromeFade = 16.dp

/** How far the header slides under the app bar while it fades out. */
private val HeaderFade = 48.dp

/** Room around the header and the pinned row, inside their layers, for their shadows and glows. */
private val LayerOverflow = 48.dp

/** Space between the app bar and the pinned row once the header above it has scrolled away. */
private val PinnedGap = 8.dp

/**
 * Sizes of the demo's chrome in px, written by [ChromeScaffold] and [TopChrome] while they measure, before the content
 * measures; read through [ChromePadding] in the content's layout phase and by the scrims in the draw phase, so a change
 * remeasures and redraws and never recomposes. A part that leaves keeps its last size, so content padded by it holds
 * still while the part fades out.
 */
@Stable
class ChromeMetrics {
  /** The status bar and the app bar. */
  var bar by mutableIntStateOf(0)
    internal set

  /** The collapsing header with the gap below it: what scrolls away under the app bar. */
  var header by mutableIntStateOf(0)
    internal set

  /** The row that follows the header and then pins under the app bar. */
  var pinned by mutableIntStateOf(0)
    internal set

  /** The bottom chrome: its scrim's fade, the floating bar with its margins and the navigation bar. */
  var bottom by mutableIntStateOf(0)
    internal set
}

/**
 * Padding computed from [ChromeMetrics] when a layout asks for it (a lazy list or `Modifier.padding` measuring), so
 * chrome measured in the same pass applies without a recomposition.
 */
@Stable
class ChromePadding(
  private val density: Density,
  private val horizontal: Dp,
  private val top: () -> Int,
  private val bottom: () -> Int,
) : PaddingValues {
  override fun calculateLeftPadding(layoutDirection: LayoutDirection) = horizontal
  override fun calculateRightPadding(layoutDirection: LayoutDirection) = horizontal
  override fun calculateTopPadding() = with(density) { top().toDp() }
  override fun calculateBottomPadding() = with(density) { bottom().toDp() }
}

/**
 * The full-window [content] under [topChrome] and [bottomChrome] (z order: content, then each chrome with its scrim).
 * The chrome measures first, so [metrics] is current when the content measures in the same pass.
 */
@Composable
fun ChromeScaffold(
  metrics: ChromeMetrics,
  topChrome: @Composable () -> Unit,
  bottomChrome: @Composable () -> Unit,
  modifier: Modifier = Modifier,
  content: @Composable () -> Unit,
) {
  Layout(contents = listOf(topChrome, bottomChrome, content), modifier) { (topMeasurables, bottomMeasurables, contentMeasurables), constraints ->
    val loose = constraints.copy(minWidth = 0, minHeight = 0)
    val top = topMeasurables.map { it.measure(loose) }
    val bottom = bottomMeasurables.map { it.measure(loose) }
    val bottomHeight = bottom.maxOfOrNull { it.height } ?: 0
    if (bottomHeight > 0) metrics.bottom = bottomHeight
    val body = contentMeasurables.map { it.measure(constraints) }
    layout(constraints.maxWidth, constraints.maxHeight) {
      body.fastForEach { it.place(0, 0) }
      bottom.fastForEach { it.place(0, constraints.maxHeight - it.height) }
      top.fastForEach { it.place(0, 0) }
    }
  }
}

/**
 * The top chrome over a [scrim]: [bar] at the top, and below it, while [expanded] is above 0, a [header] that scrolls
 * away under the bar as the content scrolls ([scrolled], px from the content's top, read in the layout and draw phases)
 * and a [pinned] row that follows it, [headerGap] below, until it reaches the bar, then stays. Nothing is clipped: the
 * header fades out over [HeaderFade] of its slide under the bar, and header and row fade with [expanded] (both read in
 * the layer block), which the scrim follows too:
 * page color at [ScrimAlpha] down to the chrome's current bottom edge, then [ChromeFade] of smooth fade to clear.
 * Writes the sizes to [metrics] as it measures.
 */
@Composable
fun TopChrome(
  metrics: ChromeMetrics,
  scrim: Color,
  expanded: State<Float>,
  scrolled: () -> Int,
  headerGap: Dp,
  bar: @Composable () -> Unit,
  header: (@Composable () -> Unit)?,
  pinned: (@Composable () -> Unit)?,
  modifier: Modifier = Modifier,
) {
  val collapse = { pinnedGap: Int -> scrolled().coerceIn(0, max(0, metrics.header - pinnedGap)) }
  // Each part is in its own layer (for its fade), padded by LayerOverflow so the layer's bounds hold the part's shadows
  // and glows: what a node draws past its layer's bounds is not redrawn when only that layer changes.
  val headerSlot: @Composable () -> Unit = { if (header != null) Box(Modifier.padding(LayerOverflow)) { header() } }
  val pinnedSlot: @Composable () -> Unit = { if (pinned != null) Box(Modifier.padding(LayerOverflow)) { pinned() } }
  // Both parts fade per draw op rather than through an offscreen buffer the size of the part, so their shadows and
  // glows past its bounds stay whole. The header fades out as it slides under the bar instead of being clipped there.
  val pinnedFade: GraphicsLayerScope.() -> Unit = remember(expanded) {
    {
      alpha = expanded.value
      compositingStrategy = CompositingStrategy.ModulateAlpha
    }
  }
  val headerFade: GraphicsLayerScope.() -> Unit = remember(expanded) {
    {
      val under = collapse(PinnedGap.roundToPx()) / HeaderFade.toPx()
      alpha = expanded.value * (1f - under).coerceIn(0f, 1f)
      compositingStrategy = CompositingStrategy.ModulateAlpha
    }
  }
  Layout(
    contents = listOf(bar, headerSlot, pinnedSlot),
    modifier = modifier.topScrim(metrics, scrim, expanded, collapse),
  ) { (barMeasurables, headerMeasurables, pinnedMeasurables), constraints ->
    val loose = constraints.copy(minWidth = 0, minHeight = 0)
    val overflow = LayerOverflow.roundToPx()
    // The parts measure at the chrome's width plus their overflow on both sides, and are placed that far up and left.
    val wide = loose.copy(maxWidth = loose.maxWidth + 2 * overflow, maxHeight = Constraints.Infinity)
    val barPlaceable = barMeasurables.single().measure(loose)
    val headerPlaceable = headerMeasurables.firstOrNull()?.measure(wide)
    val pinnedPlaceable = pinnedMeasurables.firstOrNull()?.measure(wide)
    val barHeight = barPlaceable.height
    val headerSpan = headerPlaceable?.let { it.height - 2 * overflow + headerGap.roundToPx() } ?: 0
    val pinnedHeight = pinnedPlaceable?.let { it.height - 2 * overflow } ?: 0
    metrics.bar = barHeight
    if (headerPlaceable != null) metrics.header = headerSpan
    if (pinnedPlaceable != null) metrics.pinned = pinnedHeight
    val pinnedGap = PinnedGap.roundToPx()
    layout(constraints.maxWidth, barHeight + headerSpan + pinnedHeight) {
      val c = collapse(pinnedGap)
      headerPlaceable?.placeWithLayer(-overflow, barHeight - c - overflow, layerBlock = headerFade)
      pinnedPlaceable?.placeWithLayer(-overflow, barHeight + headerSpan - c - overflow, layerBlock = pinnedFade)
      barPlaceable.place(0, 0)
    }
  }
}

/** Smooth-step falloff of a fade, as (position, share of [ScrimAlpha]) from the chrome's edge outwards. */
private val FadeSteps = listOf(0f to 1f, 0.25f to 0.844f, 0.5f to 0.5f, 0.75f to 0.156f, 1f to 0f)

private fun fadeBrush(scrim: Color, length: Float, outwardDown: Boolean): Brush {
  val stops = FadeSteps.map { (t, share) -> (if (outwardDown) t else 1f - t) to scrim.copy(alpha = ScrimAlpha * share) }
  return Brush.verticalGradient(*stops.sortedBy { it.first }.toTypedArray(), startY = 0f, endY = length)
}

/**
 * The top scrim, drawn behind the top chrome: [scrim] at [ScrimAlpha] from the window's top to the chrome's current
 * bottom edge (the bar, plus the header and pinned row by [expanded], less their [collapse]), then [ChromeFade] to
 * clear.
 */
private fun Modifier.topScrim(metrics: ChromeMetrics, scrim: Color, expanded: State<Float>, collapse: (pinnedGap: Int) -> Int): Modifier =
  drawWithCache {
    val fadeLength = ChromeFade.toPx()
    val fade = fadeBrush(scrim, fadeLength, outwardDown = true)
    val solid = scrim.copy(alpha = ScrimAlpha)
    onDrawBehind {
      val bar = metrics.bar.toFloat()
      val block = (metrics.header + metrics.pinned - collapse(PinnedGap.roundToPx())).toFloat()
      val edge = bar + block * expanded.value
      drawRect(solid, size = Size(size.width, edge))
      translate(top = edge) { drawRect(fade, size = Size(size.width, fadeLength)) }
    }
  }

/**
 * The bottom scrim, behind bottom chrome laid out with [ChromeFade] of free space on top: that space fades in from
 * clear, then [scrim] at [ScrimAlpha] to the window's bottom edge.
 */
fun Modifier.bottomScrim(scrim: Color): Modifier =
  drawWithCache {
    val fadeLength = ChromeFade.toPx()
    val fade = fadeBrush(scrim, fadeLength, outwardDown = false)
    val solid = scrim.copy(alpha = ScrimAlpha)
    onDrawBehind {
      drawRect(fade, size = Size(size.width, fadeLength))
      translate(top = fadeLength) { drawRect(solid, size = Size(size.width, size.height - fadeLength)) }
    }
  }
