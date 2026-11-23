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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import dev.serhiiyaremych.obvid.fixtures.CookiePillMorph
import dev.serhiiyaremych.obvid.fixtures.MorphShape
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/** Intent string extra that picks the scenario. */
const val ScenarioExtra = "scenario"

/**
 * Scenario names, the first is the default. Every card is a white box with a black, 0.4 alpha
 * shadow drawn by [demoShadow] (Compose's `dropShadow` or Obvid's, per the `shadowImpl` extra):
 * - `static`: one 200 dp card, radius 24, nothing animates (the cached case).
 * - `radius`: radius 8→48 dp. `size`: card 100→300 dp. `color`: black→blue, alpha 0.2→0.6.
 * - `morph`: one 200 dp Cookie9Sided↔Pill card; `morph6`: six 120 dp ones, radius 16.
 * - `offset12`, `offset24`: 80 dp cards whose offset circles at 8 dp (a moving light);
 *   `offset24Lambda` does the same through the block form, read in the draw phase.
 * - `radius24`: 24 cards of 80 dp, radius 4→24 dp.
 * - `morph6` and `radius24` are phased: each card runs the same animation shifted by its share of the
 *   period, so every card has its own radius or shape each frame (N rebuilds per frame).
 *   `morph6Sync` and `radius24Sync` keep the cards in step: Compose's shadow cache is shared per
 *   window and keyed on shape, size and shadow, so identical cards rebuild once per frame.
 * - `reentry_unique`, `reentry_same`: a scrolling list of 80 static cards ([ReentryList]), each with its own
 *   height (no two nearby cards share a shadow geometry) or all of one height. Nothing animates; frames come
 *   from scrolling, and a card that scrolls out and back in draws its shadow again.
 * - [TierScenarios] (`t0_single` … `t6_nav`, `t8_glow_spin`, `t8_glow_pulse`): realistic screens for the per-frame comparison, see [TierScenario].
 * - `t7_discover`, `t7_discover_reveal`: the Discover showcase (package `discover`, `DiscoverScreen`); started by
 *   `MainActivity` directly (full screen, dark), not through [Scenario].
 */
val Scenarios = listOf(
  "static", "radius", "size", "morph", "morph6", "morph6Sync", "offset12", "offset24", "offset24Lambda",
  "radius24", "radius24Sync", "color", "reentry_unique", "reentry_same",
)

private val Shape24 = RoundedCornerShape(24.dp)
private val Shape16 = RoundedCornerShape(16.dp)
private const val ShadowAlpha = 0.4f

@Composable
fun Scenario(name: String) {
  when (name) {
    "static" -> Card(200.dp, Shape24, Shadow(radius = 24.dp, alpha = ShadowAlpha))
    "radius" -> {
      val radius by animate(8f, 48f)
      Card(200.dp, Shape24, Shadow(radius = radius.dp, alpha = ShadowAlpha))
    }
    "size" -> {
      val size by animate(100f, 300f)
      Card(size.dp, Shape24, Shadow(radius = 24.dp, alpha = ShadowAlpha))
    }
    "morph" -> MorphCards(count = 1, columns = 1, size = 200.dp, radius = 24.dp)
    "morph6" -> PhasedMorphCards(count = 6, columns = 2, size = 120.dp, radius = 16.dp)
    "morph6Sync" -> MorphCards(count = 6, columns = 2, size = 120.dp, radius = 16.dp)
    "offset12" -> OffsetGrid(count = 12, columns = 3)
    "offset24" -> OffsetGrid(count = 24, columns = 4)
    "offset24Lambda" -> OffsetLambdaGrid(count = 24, columns = 4)
    "radius24" -> {
      val clock by phaseClock()
      Grid(count = 24, columns = 4) { index ->
        val radius = 4f + 20f * triangle(clock + index / 24f)
        Card(80.dp, Shape16, Shadow(radius = radius.dp, alpha = ShadowAlpha))
      }
    }
    "radius24Sync" -> {
      val radius by animate(4f, 24f)
      Grid(count = 24, columns = 4) { Card(80.dp, Shape16, Shadow(radius = radius.dp, alpha = ShadowAlpha)) }
    }
    "color" -> {
      val fraction by animate(0f, 1f)
      val shadow = Shadow(radius = 24.dp, color = lerp(Color.Black, Color.Blue, fraction), alpha = 0.2f + 0.4f * fraction)
      Card(200.dp, Shape24, shadow)
    }
    "reentry_unique" -> ReentryList(unique = true)
    "reentry_same" -> ReentryList(unique = false)
    in TierScenarios -> TierScenario(name)
    else -> error("Unknown scenario '$name', expected one of ${Scenarios + TierScenarios}")
  }
}

/** Card chain: size → trace section → shadow ([demoShadow]) → background. */
@Composable
private fun Card(size: Dp, shape: Shape, shadow: Shadow) {
  Box(Modifier.size(size).demoShadow(shape, shadow).background(Color.White, shape))
}

/** A new [MorphShape] per progress value, so the shadow follows the outline (and rebuilds). */
@Composable
private fun MorphCards(count: Int, columns: Int, size: Dp, radius: Dp) {
  val progress by animate(0f, 1f)
  val shape = remember(progress) { MorphShape(CookiePillMorph, progress) }
  val shadow = remember(radius) { Shadow(radius = radius, alpha = ShadowAlpha) }
  Grid(count, columns, gap = 40.dp) { Card(size, shape, shadow) }
}

/** Each card shifted by its share of the period, each with its own [MorphShape] per frame. */
@Composable
private fun PhasedMorphCards(count: Int, columns: Int, size: Dp, radius: Dp) {
  val clock by phaseClock()
  val shadow = remember(radius) { Shadow(radius = radius, alpha = ShadowAlpha) }
  Grid(count, columns, gap = 40.dp) { index ->
    val progress = triangle(clock + index.toFloat() / count)
    val shape = remember(progress) { MorphShape(CookiePillMorph, progress) }
    Card(size, shape, shadow)
  }
}

/** Common form: the offset is read in composition, so each frame recomposes with a new [Shadow]. */
@Composable
private fun OffsetGrid(count: Int, columns: Int) {
  val angle by animate(0f, 2 * PI.toFloat(), millis = 2000, repeatMode = RepeatMode.Restart)
  val shadow = Shadow(radius = 16.dp, offset = DpOffset(8.dp * cos(angle), 8.dp * sin(angle)), alpha = ShadowAlpha)
  Grid(count, columns) { Card(80.dp, Shape16, shadow) }
}

/** Block form: the angle is read inside the shadow block, in the draw phase, so nothing recomposes. */
@Composable
private fun OffsetLambdaGrid(count: Int, columns: Int) {
  val angle = animate(0f, 2 * PI.toFloat(), millis = 2000, repeatMode = RepeatMode.Restart)
  Grid(count, columns) {
    Box(
      Modifier.size(80.dp)
        .demoShadow(Shape16) {
          radius = 16.dp.toPx()
          color = Color.Black
          alpha = ShadowAlpha
          val distance = 8.dp.toPx()
          offset = Offset(distance * cos(angle.value), distance * sin(angle.value))
        }
        .background(Color.White, Shape16)
    )
  }
}

@Composable
private fun Grid(count: Int, columns: Int, gap: Dp = 20.dp, card: @Composable (index: Int) -> Unit) {
  Column(verticalArrangement = Arrangement.spacedBy(gap)) {
    repeat(count / columns) { row ->
      Row(horizontalArrangement = Arrangement.spacedBy(gap)) { repeat(columns) { card(row * columns + it) } }
    }
  }
}

/** 0→1 over one full period (2000 ms: there and back of a 1000 ms reversing animation), restarting. */
@Composable
private fun phaseClock(): State<Float> = animate(0f, 1f, millis = 2000, repeatMode = RepeatMode.Restart)

/** A linear reversing animation at [phase] of its period: 0→1→0 as [phase] goes 0→1, wrapping. */
private fun triangle(phase: Float): Float {
  val t = phase - floor(phase)
  return 1f - abs(2f * t - 1f)
}

/** Linear infinite animation, reversing by default. */
@Composable
private fun animate(from: Float, to: Float, millis: Int = 1000, repeatMode: RepeatMode = RepeatMode.Reverse): State<Float> =
  rememberInfiniteTransition("scenario")
    .animateFloat(from, to, infiniteRepeatable(tween(millis, easing = LinearEasing), repeatMode), "value")
