// Copyright 2026, Serhii Yaremych (desugar-64) and the Obvid project contributors
// SPDX-License-Identifier: Apache-2.0

package dev.serhiiyaremych.obvid.sample

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

/** Intent extras of the `grid` scenario. */
const val ShadowsExtra = "shadows"
const val HudExtra = "hud"
const val FirstItemExtra = "first"

private val Paper = Color(0xFFFAF7F2)
private val Ink = Color(0xFF1D1B20)
private val InkMuted = Color(0xFF5E5A66)

private val GridColors = lightColorScheme(
  primary = Color(0xFF5B3FA8),
  secondaryContainer = Color(0xFFE9DDFF),
  onSecondaryContainer = Color(0xFF2E1A66),
  tertiaryContainer = Color(0xFFFFDCC8),
  onTertiaryContainer = Color(0xFF5A2208),
  background = Paper,
  surface = Paper,
  onSurface = Ink,
  onSurfaceVariant = InkMuted,
)

/**
 * The stress screen: a lazy staggered grid of [GridItemCount] tiles, each described by
 * [TileSpec.of] (index-seeded, deterministic), every shadow through [demoShadow].
 *
 * One clock drives every animation: seconds 0→60, linear, restarting; each tile's pose is a pure
 * function of (clock, its period, its phase), so the grid at a given time since start is the same
 * on every run. [ShadowMode.Static] freezes the clock at 0. [firstItem] is the item at the top at
 * start (Macrobenchmark's idle cases start at a window with a representative mix).
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalComposeUiApi::class)
@Composable
fun StressGrid(mode: ShadowMode, hud: Boolean, firstItem: Int = 0) {
  val specs = remember { List(GridItemCount, TileSpec::of) }
  val clock: State<Float> =
    if (mode == ShadowMode.Static) {
      remember { mutableFloatStateOf(0f) }
    } else {
      rememberInfiniteTransition("grid")
        .animateFloat(0f, 60f, infiniteRepeatable(tween(60_000, easing = LinearEasing), RepeatMode.Restart), "clock")
    }
  MaterialExpressiveTheme(colorScheme = GridColors) {
    val bars = WindowInsets.statusBars.asPaddingValues()
    val nav = WindowInsets.navigationBars.asPaddingValues()
    Box(Modifier.fillMaxSize().background(Paper).semantics { testTagsAsResourceId = true }) {
      LazyVerticalStaggeredGrid(
        columns = StaggeredGridCells.Adaptive(168.dp),
        state = rememberLazyStaggeredGridState(initialFirstVisibleItemIndex = firstItem),
        modifier = Modifier.fillMaxSize().testTag("grid"),
        contentPadding = PaddingValues(
          start = 20.dp, end = 20.dp,
          top = bars.calculateTopPadding() + TopBarHeight + 16.dp,
          bottom = nav.calculateBottomPadding() + 32.dp,
        ),
        verticalItemSpacing = 28.dp,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
      ) {
        items(specs.size, key = { it }, contentType = { specs[it].motion }) { index ->
          Tile(specs[index], mode, clock)
        }
      }
      TopBar(mode)
      if (hud) {
        FrameHud(Modifier.align(Alignment.BottomStart).windowInsetsPadding(WindowInsets.navigationBars).padding(16.dp))
      }
    }
  }
}

private val TopBarHeight = 56.dp

@Composable
private fun TopBar(mode: ShadowMode) {
  Row(
    Modifier
      .fillMaxWidth()
      .background(Paper)
      .windowInsetsPadding(WindowInsets.statusBars)
      .height(TopBarHeight)
      .padding(horizontal = 20.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    Text(
      buildAnnotatedString {
        withStyle(SpanStyle(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)) { append("dropShadow") }
        append(" stress")
      },
      Modifier.weight(1f),
      style = MaterialTheme.typography.titleMedium,
      color = Ink,
      maxLines = 1,
    )
    Chip(ShadowImplementationLabel, MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer)
    Chip(mode.label, MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer)
  }
}

@Composable
private fun Chip(text: String, container: Color, content: Color) {
  Text(
    text,
    Modifier.background(container, CircleShape).padding(horizontal = 10.dp, vertical = 5.dp),
    style = MaterialTheme.typography.labelMedium,
    color = content,
    maxLines = 1,
  )
}

@Composable
private fun Tile(spec: TileSpec, mode: ShadowMode, clock: State<Float>) {
  Column {
    TileSurface(spec, mode, clock)
    Caption(spec, Modifier.padding(start = 4.dp, top = 12.dp))
  }
}

/** Shape, motion layer, shadow (or only the trace section in the control), background, numeral. */
@Composable
private fun TileSurface(spec: TileSpec, mode: ShadowMode, clock: State<Float>) {
  val shape: Shape = when (val form = spec.form) {
    is TileForm.Rect -> form.shape
    is TileForm.Polygon -> form.shape
    // Read in composition: a new shape per frame recomposes this tile only.
    is TileForm.Morphing -> {
      val progress = wave(spec.pose(clock.value))
      remember(progress) { MorphPoseShape(form.pair, progress) }
    }
  }
  val box = when (val form = spec.form) {
    is TileForm.Rect -> Modifier.fillMaxWidth().height(form.height)
    is TileForm.Polygon -> Modifier.fillMaxWidth().aspectRatio(form.aspect)
    is TileForm.Morphing -> Modifier.fillMaxWidth().aspectRatio(form.aspect)
  }
  val shadow = spec.shadow
  val motion = when (spec.motion) {
    Motion.Breathe -> Modifier.graphicsLayer {
      val scale = 0.97f + 0.06f * wave(spec.pose(clock.value))
      scaleX = scale
      scaleY = scale
    }
    Motion.Lift -> Modifier.graphicsLayer { translationY = -6.dp.toPx() * wave(spec.pose(clock.value)) }
    else -> Modifier
  }
  val shadowed = when {
    shadow == null -> Modifier
    mode == ShadowMode.None -> Modifier.traceDraw() // control: same trace section, no shadow
    else -> when (spec.motion) {
      Motion.Still, Motion.Morph -> Modifier.demoShadow(shape, shadow.shadow)
      Motion.Breathe -> Modifier.demoShadow(shape) {
        radius = shadow.radius.dp.toPx() * (0.5f + wave(spec.pose(clock.value)))
        spread = shadow.spread.dp.toPx()
        color = shadow.color
        alpha = shadow.alpha
        offset = Offset(shadow.dx.dp.toPx(), shadow.dy.dp.toPx())
      }
      Motion.Orbit -> Modifier.demoShadow(shape) {
        val angle = 2f * PI.toFloat() * spec.pose(clock.value)
        val distance = shadow.orbit.dp.toPx()
        radius = shadow.radius.dp.toPx()
        spread = shadow.spread.dp.toPx()
        color = shadow.color
        alpha = shadow.alpha
        offset = Offset(distance * cos(angle), distance * sin(angle))
      }
      Motion.Lift -> Modifier.demoShadow(shape) {
        val lift = wave(spec.pose(clock.value))
        radius = (shadow.radius + 16f * lift).dp.toPx()
        spread = shadow.spread.dp.toPx()
        color = shadow.color
        alpha = shadow.alpha
        offset = Offset(shadow.dx.dp.toPx(), (shadow.dy + 10f * lift).dp.toPx())
      }
    }
  }
  Box(box.then(motion).then(shadowed).background(spec.tone.tile, shape), contentAlignment = Alignment.Center) {
    Text(
      spec.index.toString().padStart(3, '0'),
      color = spec.tone.shadow.copy(alpha = 0.55f),
      fontFamily = FontFamily.Monospace,
      fontWeight = FontWeight.SemiBold,
      fontSize = 15.sp,
    )
  }
}

@Composable
private fun Caption(spec: TileSpec, modifier: Modifier = Modifier) {
  Column(modifier) {
    Text(
      spec.form.name,
      style = MaterialTheme.typography.labelMedium,
      fontFamily = FontFamily.Monospace,
      fontWeight = FontWeight.SemiBold,
      color = Ink,
      maxLines = 1,
    )
    Text(
      spec.params(),
      style = MaterialTheme.typography.labelSmall,
      fontFamily = FontFamily.Monospace,
      color = InkMuted,
      maxLines = 2,
    )
  }
}

/** Caption line 2: shadow radius, spread, offset (dp) and the motion. */
private fun TileSpec.params(): String {
  val s = shadow ?: return "no shadow"
  val offset = "o(${s.dx},${s.dy})"
  return when (motion) {
    Motion.Still -> "r${s.radius} s${s.spread} $offset"
    Motion.Breathe -> "r${s.radius / 2}→${s.radius * 3 / 2} s${s.spread} · breathe"
    Motion.Morph -> "r${s.radius} s${s.spread} $offset · morph"
    Motion.Orbit -> "r${s.radius} s${s.spread} o${s.orbit}↻ · orbit"
    Motion.Lift -> "r${s.radius}→${s.radius + 16} s${s.spread} · lift"
  }
}

/** Orbit distance: the static offset's length, at least 6 dp. */
private val ShadowSpec.orbit: Int get() = max(6, hypot(dx.toFloat(), dy.toFloat()).roundToInt())

/** This tile's phase at clock [seconds], 0..1. */
private fun TileSpec.pose(seconds: Float): Float {
  val p = seconds / period + phase
  return p - floor(p)
}

/** Smooth 0→1→0 over one phase. */
private fun wave(phase: Float): Float = 0.5f - 0.5f * cos(2f * PI.toFloat() * phase)
