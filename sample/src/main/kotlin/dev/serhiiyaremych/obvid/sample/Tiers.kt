// Copyright 2026, Serhii Yaremych (desugar-64) and the Obvid project contributors
// SPDX-License-Identifier: Apache-2.0

package dev.serhiiyaremych.obvid.sample

import android.os.Trace
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp as lerpColor
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor

/**
 * The tier scenarios, each drawn with `shadowImpl` = compose | cpu | none (none: the same screen, layout and
 * animation without any shadow modifier). Every shadow goes through [measuredDropShadow] / [measuredInnerShadow], so
 * its trace section holds the shadow draw only. Animations are pure functions of one clock ([tierClock]), the same
 * for every implementation.
 * - `t0_single`: one 200 dp card (corner 24 dp) with a drop shadow (radius 24 dp), nothing else, hidden until a tap
 *   on [TierTargetTag] reveals it ([RevealScreen], with a warm-up row without text): the first frame of one shadow.
 * - `t1_static`: a static screen (3 cards, 2 pill buttons, a text field with an inner shadow), hidden until a tap on
 *   [TierTargetTag] reveals it ([RevealScreen]).
 * - `t2_menu`: a menu that expands and collapses (size, corners and shadow radius together, 2.4 s period), 3 pill
 *   buttons pressed in turn (inner shadow deepens, drop shadow drops, 1.8 s period), a MaterialShapes morph (2 s).
 * - `t3_gears`: [GearsScreen], a train of three meshing gears whose outlines turn (drop shadows, one gear pulsing in
 *   scale) over a large turning gear with a circling inner shadow.
 * - `t4_field`: [FieldScreen], a field of about 3 × 3 screens of animated tiles (round rects, MaterialShapes morphs,
 *   gears) with palette and hue-cycling shadow colours, under a camera flying a figure eight; only tiles near the
 *   viewport are composed.
 * - `t5_list`: [TierList], a feed of 100 index-seeded items, scrolled by the benchmark.
 * - `t6_nav`: [TierNav], a light screen and a shadow-heavy one, switched by a tap on [TierNavTag].
 * - `t8_glow_spin`: [GlowScreen], three dark surfaces with wide glows filled by a turning sweep gradient (brush only);
 *   `t8_glow_pulse`: the same with the glow radius pulsing too (brush and geometry).
 */
val TierScenarios = listOf(
  "t0_single", "t1_static", "t2_menu", "t3_gears", "t4_field", "t5_list", "t6_nav", "t8_glow_spin", "t8_glow_pulse",
)

/** Resource id of the full-screen tap target that reveals `t0_single` and `t1_static`. */
const val TierTargetTag = "tier"

/** Async trace section from the reveal tap to two frames after the revealed content is composed. */
const val RevealTraceSection = "demo:reveal"

@Composable
fun TierScenario(name: String) {
  when (name) {
    "t0_single" -> RevealScreen(label = false) { SingleCardScreen() }
    "t1_static" -> RevealScreen { StaticScreen() }
    "t2_menu" -> MenuScreen()
    "t3_gears" -> GearsScreen()
    "t4_field" -> FieldScreen()
    "t5_list" -> TierList()
    "t6_nav" -> TierNav()
    "t8_glow_spin" -> GlowScreen(pulse = false)
    "t8_glow_pulse" -> GlowScreen(pulse = true)
    else -> error("Unknown tier scenario '$name'")
  }
}

/** Seconds 0 → 60, linear, restarting; every period used divides 60 s, so poses stay continuous at the wrap. */
@Composable
fun tierClock(): State<Float> =
  rememberInfiniteTransition("tier")
    .animateFloat(0f, 60f, infiniteRepeatable(tween(60_000, easing = LinearEasing), RepeatMode.Restart), "clock")

/** Phase 0..1 of [seconds] in a cycle of [period] seconds shifted by [phase] cycles. */
internal fun cycle(seconds: Float, period: Float, phase: Float = 0f): Float {
  val p = seconds / period + phase
  return p - floor(p)
}

/** Smooth 0 → 1 → 0 over one phase. */
internal fun tierWave(phase: Float): Float = 0.5f - 0.5f * cos(2f * PI.toFloat() * phase)

private var asyncCookie = 0

/** Opens async trace section [name]; returns the cookie that closes it ([endEvent]). */
fun beginEvent(name: String): Int = (++asyncCookie).also { Trace.beginAsyncSection(name, it) }

fun endEvent(name: String, cookie: Int) = Trace.endAsyncSection(name, cookie)

private val CardShadowColor = Color(0xFF1D1B20)
private val Surface = Color.White
private val FieldColor = Color(0xFFF1EDF6)
private val Accent = Color(0xFF5B3FA8)
private val AccentSoft = Color(0xFFE9DDFF)

/**
 * Shows only a small warm-up row until a tap anywhere on the [TierTargetTag] target, then [content] too. The warm-up
 * row draws a drop and an inner shadow of a geometry used nowhere else, so the first revealed frame does not also pay
 * for loading the implementation's classes and native code. [label] adds a text label to the row. The tap opens
 * [RevealTraceSection].
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun RevealScreen(label: Boolean = true, content: @Composable () -> Unit) {
  var revealed by remember { mutableStateOf(false) }
  var cookie by remember { mutableIntStateOf(0) }
  LaunchedEffect(revealed) {
    if (!revealed) return@LaunchedEffect
    withFrameNanos {}
    withFrameNanos {}
    endEvent(RevealTraceSection, cookie)
  }
  Column(
    Modifier
      .fillMaxSize()
      .semantics { testTagsAsResourceId = true }
      .testTag(TierTargetTag)
      .clickable(interactionSource = null, indication = null) {
        if (!revealed) {
          cookie = beginEvent(RevealTraceSection)
          revealed = true
        }
      }
      .padding(20.dp),
  ) {
    WarmUpRow(label)
    if (revealed) content()
  }
}

@Composable
private fun WarmUpRow(label: Boolean) {
  Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
    Box(Modifier.size(36.dp).measuredDropShadow(CircleShape, Shadow(radius = 3.dp, alpha = 0.3f)).background(AccentSoft, CircleShape))
    Box(Modifier.size(36.dp).background(FieldColor, CircleShape).measuredInnerShadow(CircleShape, Shadow(radius = 3.dp, alpha = 0.3f)))
    if (label) Text("Inbox", style = MaterialTheme.typography.titleLarge)
  }
}

/** `t0_single`: one 200 dp card, corner 24 dp, drop shadow radius 24 dp, no offset or spread. */
@Composable
private fun SingleCardScreen() {
  Box(Modifier.padding(top = 20.dp).size(200.dp).measuredDropShadow(SingleCardShape, SingleCardShadow).background(Surface, SingleCardShape))
}

private val SingleCardShape = RoundedCornerShape(24.dp)
private val SingleCardShadow = Shadow(radius = 24.dp, color = CardShadowColor, alpha = 0.25f)

/** `t1_static`: three cards (radius 12, 18, 24 dp), two pill buttons, a search field with an inner shadow. */
@Composable
private fun StaticScreen() {
  Column(Modifier.padding(top = 20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
    ContentCard(140.dp, radius = 12.dp, dy = 4.dp, "Weekly summary", "12 tasks done, 3 waiting for review.")
    ContentCard(120.dp, radius = 18.dp, dy = 6.dp, "Design sync", "Thursday 10:00, room 4B.")
    ContentCard(160.dp, radius = 24.dp, dy = 8.dp, "Release notes", "Shadows now rebuild faster while they animate.")
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
      PillButton("Archive", Modifier.weight(1f))
      PillButton("Reply", Modifier.weight(1f))
    }
    SearchField("Search mail", Modifier.fillMaxWidth())
  }
}

private val CardShape = RoundedCornerShape(16.dp)
private val PillShape = RoundedCornerShape(percent = 50)
private val FieldShape = RoundedCornerShape(12.dp)

@Composable
fun ContentCard(
  height: Dp,
  radius: Dp,
  dy: Dp,
  title: String,
  body: String,
  modifier: Modifier = Modifier,
  shape: Shape = CardShape,
  alpha: Float = 0.25f,
) {
  val shadow = remember(radius, dy, alpha) { Shadow(radius = radius, color = CardShadowColor, offset = DpOffset(0.dp, dy), alpha = alpha) }
  Column(
    modifier
      .fillMaxWidth()
      .height(height)
      .measuredDropShadow(shape, shadow)
      .background(Surface, shape)
      .padding(16.dp),
  ) {
    Text(title, style = MaterialTheme.typography.titleMedium)
    Text(body, style = MaterialTheme.typography.bodyMedium, color = Color(0xFF5E5A66))
  }
}

@Composable
fun PillButton(label: String, modifier: Modifier = Modifier, shadow: Shadow = SmallPillShadow) {
  Box(
    modifier.height(48.dp).measuredDropShadow(PillShape, shadow).background(Accent, PillShape),
    contentAlignment = Alignment.Center,
  ) {
    Text(label, color = Color.White, style = MaterialTheme.typography.labelLarge)
  }
}

private val SmallPillShadow = Shadow(radius = 6.dp, color = Accent, offset = DpOffset(0.dp, 2.dp), alpha = 0.35f)
private val FieldShadow = Shadow(radius = 8.dp, color = CardShadowColor, offset = DpOffset(0.dp, 2.dp), alpha = 0.3f)

@Composable
fun SearchField(hint: String, modifier: Modifier = Modifier, shape: Shape = FieldShape) {
  Box(
    modifier.height(56.dp).background(FieldColor, shape).measuredInnerShadow(shape, FieldShadow).padding(horizontal = 16.dp),
    contentAlignment = Alignment.CenterStart,
  ) {
    Text(hint, style = MaterialTheme.typography.bodyLarge, color = Color(0xFF7A7484))
  }
}

/** `t2_menu`. */
@Composable
private fun MenuScreen() {
  val clock by tierClock()
  Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(32.dp)) {
    Box(Modifier.size(MenuOpenWidth, MenuOpenHeight)) { Menu(menuFraction(clock)) }
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
      repeat(3) { PressedButton(listOf("Cut", "Copy", "Paste")[it], press(clock, it)) }
    }
    val progress = tierWave(cycle(clock, 2f))
    val shape = remember(progress) { MorphPoseShape(pair = 0, progress = progress) }
    Box(Modifier.size(140.dp).measuredDropShadow(shape, MorphShadow).background(AccentSoft, shape))
  }
}

private val MenuOpenWidth = 280.dp
private val MenuOpenHeight = 320.dp
private val MorphShadow = Shadow(radius = 16.dp, color = Accent, offset = DpOffset(0.dp, 6.dp), alpha = 0.35f)

/** Menu openness over a 2.4 s cycle: 0.7 s opening (emphasized easing), 0.5 s open, 0.7 s closing, 0.5 s closed. */
private fun menuFraction(clock: Float): Float {
  val t = cycle(clock, 2.4f) * 2.4f
  return when {
    t < 0.7f -> FastOutSlowInEasing.transform(t / 0.7f)
    t < 1.2f -> 1f
    t < 1.9f -> 1f - FastOutSlowInEasing.transform((t - 1.2f) / 0.7f)
    else -> 0f
  }
}

/** A collapsed 200 × 56 dp pill growing into a 280 × 320 dp menu; corners 28 → 16 dp, shadow radius 6 → 24 dp. */
@Composable
private fun Menu(fraction: Float) {
  val shape = RoundedCornerShape(lerp(28f, 16f, fraction).dp)
  val shadow = Shadow(
    radius = lerp(6f, 24f, fraction).dp,
    color = CardShadowColor,
    offset = DpOffset(0.dp, lerp(2f, 10f, fraction).dp),
    alpha = 0.3f,
  )
  Column(
    Modifier
      .size(lerp(200f, MenuOpenWidth.value, fraction).dp, lerp(56f, MenuOpenHeight.value, fraction).dp)
      .measuredDropShadow(shape, shadow)
      .background(Surface, shape)
      .padding(horizontal = 20.dp, vertical = 16.dp),
    verticalArrangement = Arrangement.spacedBy(20.dp),
  ) {
    Text("Actions", style = MaterialTheme.typography.titleMedium)
    Column(Modifier.graphicsLayer { alpha = fraction }, verticalArrangement = Arrangement.spacedBy(20.dp)) {
      listOf("New folder", "Rename", "Move to…", "Share", "Delete").forEach { Text(it, style = MaterialTheme.typography.bodyLarge) }
    }
  }
}

/** Press depth of button [index]: pressed in 0.25 s, released in 0.25 s, once per 1.8 s cycle, buttons 0.6 s apart. */
private fun press(clock: Float, index: Int): Float {
  val t = cycle(clock, 1.8f, -index / 3f) * 1.8f
  return when {
    t < 0.25f -> FastOutSlowInEasing.transform(t / 0.25f)
    t < 0.5f -> 1f - FastOutSlowInEasing.transform((t - 0.25f) / 0.25f)
    else -> 0f
  }
}

/** A pill whose press deepens its inner shadow (radius 2 → 10 dp, offset 0.5 → 4 dp) and flattens its drop shadow. */
@Composable
private fun PressedButton(label: String, depth: Float) {
  val drop = Shadow(radius = lerp(6f, 1.5f, depth).dp, color = Accent, offset = DpOffset(0.dp, lerp(3f, 0.5f, depth).dp), alpha = 0.35f)
  val inner = Shadow(radius = lerp(2f, 10f, depth).dp, color = Color.Black, offset = DpOffset(0.dp, lerp(0.5f, 4f, depth).dp), alpha = 0.4f)
  Box(
    Modifier
      .size(96.dp, 48.dp)
      .measuredDropShadow(PillShape, drop)
      .background(AccentSoft, PillShape)
      .measuredInnerShadow(PillShape, inner),
    contentAlignment = Alignment.Center,
  ) {
    Text(label, color = Accent, style = MaterialTheme.typography.labelLarge)
  }
}

/** Background of the dark tier scenes in their fixed benchmark colors (the host paints it behind the system bars too); null for the light ones. */
fun tierBackground(name: String): Color? =
  when (name) {
    "t3_gears" -> SceneColors.Dark.gears
    "t4_field" -> SceneColors.Dark.field
    "t8_glow_spin", "t8_glow_pulse" -> GlowColors.Dark.background
    else -> null
  }
private val GlowCardShape = RoundedCornerShape(24.dp)

/** Seconds per turn of the glow's gradient. */
private const val GlowTurn = 3f

/** Seconds per glow radius pulse (`t8_glow_pulse`). */
private const val GlowPulse = 2f

/**
 * `t8_glow_spin`, `t8_glow_pulse`: in [GlowColors] for [LocalDemoPalette]'s mode (dark in the benchmarks), a 300 × 180 dp
 * card (24 dp corners), a 320 × 56 dp pill field and a 96 dp circle, surfaces each with a glow: a drop shadow (spread
 * 4 dp, alpha 0.9) filled with a sweep gradient of [GlowColors.vivid] that turns once per [GlowTurn] s. The block reads
 * the clock in the draw phase, so nothing recomposes per frame. Without [pulse] the radius stays 36 dp (only the brush
 * changes); with it the radius goes 24 → 44 dp and back every [GlowPulse] s (brush and geometry change). Shadows draw
 * with [LocalShadowImpl].
 */
@Composable
fun GlowScreen(pulse: Boolean, modifier: Modifier = Modifier) {
  val clock = tierClock()
  val colors = GlowColors.current
  Column(
    modifier.fillMaxSize().background(colors.background).padding(horizontal = 24.dp),
    verticalArrangement = Arrangement.spacedBy(72.dp, Alignment.CenterVertically),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Glowing(Modifier.size(300.dp, 180.dp), GlowCardShape, colors, clock, pulse) {
      Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Weekly focus", style = MaterialTheme.typography.titleMedium, color = colors.text)
        Text("Three goals set, two done. Next review on Friday.", style = MaterialTheme.typography.bodyMedium, color = colors.hint)
      }
    }
    Glowing(Modifier.size(320.dp, 56.dp), PillShape, colors, clock, pulse) {
      Text(
        "Type a message",
        Modifier.align(Alignment.CenterStart).padding(horizontal = 24.dp),
        style = MaterialTheme.typography.bodyLarge,
        color = colors.hint,
      )
    }
    Glowing(Modifier.size(96.dp), CircleShape, colors, clock, pulse)
  }
}

/** A [GlowColors.surface] surface of [shape] with the glow, through [colors]' vivid hues, drawn behind it. */
@Composable
private fun Glowing(
  modifier: Modifier,
  shape: Shape,
  colors: GlowColors,
  clock: State<Float>,
  pulse: Boolean,
  content: @Composable BoxScope.() -> Unit = {},
) {
  Box(
    modifier
      .measuredDropShadow(shape, LocalShadowImpl.current) {
        val seconds = clock.value
        radius = (if (pulse) lerp(24f, 44f, tierWave(cycle(seconds, GlowPulse))) else 36f).dp.toPx()
        spread = 4.dp.toPx()
        alpha = 0.9f
        brush = glowBrush(colors.vivid, cycle(seconds, GlowTurn))
      }
      .background(colors.surface, shape),
    content = content,
  )
}

/**
 * A sweep gradient through [vivid] (evenly spaced, closing back to the first) turned by [turn] of a full turn:
 * every stop moves by [turn], and the stops at 0 and 1 take the colour where the wrapped arc crosses the start angle.
 */
private fun glowBrush(vivid: List<Color>, turn: Float): Brush {
  val n = vivid.size
  val stops = Array(n) { i ->
    val p = i.toFloat() / n + turn
    (p - floor(p)) to vivid[i]
  }
  stops.sortBy { it.first }
  val first = stops.first()
  val last = stops.last()
  val edge = lerpColor(last.second, first.second, (1f - last.first) / (1f - last.first + first.first))
  return Brush.sweepGradient(0f to edge, *stops, 1f to edge)
}
