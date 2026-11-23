// Copyright 2026, Serhii Yaremych (desugar-64) and the Obvid project contributors
// SPDX-License-Identifier: Apache-2.0

package dev.serhiiyaremych.obvid.sample

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * A spur gear of [teeth] trapezoid teeth filling the layout box, turned by [angle] (radians, clockwise on screen) and
 * scaled by [scale] about the center: tip radius `scale` × half the box's shorter side, module (tooth size) tip / (teeth
 * / 2 + 1), so gears of one module mesh at a center distance of the sum of their pitch radii. Tooth `k` is centered at
 * `angle + k · 2π / teeth`; at the pitch circle a tooth takes 0.44 of the tooth pitch, the gap 0.56. A hub hole and
 * [holes] round holes on a ring, filled even-odd: an `Outline.Generic` with holes. Value equality: a new pose is a new
 * shadow key.
 */
class SpurGearShape(val teeth: Int, val holes: Int, val angle: Float, val scale: Float = 1f) : Shape {
  private var outlineSize = Size.Unspecified
  private var outline: Outline? = null

  override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
    outline?.let { if (size == outlineSize) return it }
    val tip = min(size.width, size.height) / 2f * scale
    val module = tip / (teeth / 2f + 1f)
    val root = tip - 2.25f * module
    val step = 2f * PI.toFloat() / teeth
    val tipHalf = 0.16f * step
    val rootHalf = 0.30f * step
    val cx = size.width / 2f
    val cy = size.height / 2f
    val path = Path().apply {
      fillType = PathFillType.EvenOdd
      for (k in 0 until teeth) {
        val a = angle + k * step
        val (x0, y0) = polar(cx, cy, root, a - rootHalf)
        if (k == 0) moveTo(x0, y0) else lineTo(x0, y0)
        polar(cx, cy, tip, a - tipHalf).let { (x, y) -> lineTo(x, y) }
        polar(cx, cy, tip, a + tipHalf).let { (x, y) -> lineTo(x, y) }
        polar(cx, cy, root, a + rootHalf).let { (x, y) -> lineTo(x, y) }
      }
      close()
      circle(cx, cy, 0.2f * root)
      for (k in 0 until holes) {
        val (x, y) = polar(cx, cy, 0.58f * root, angle + (k + 0.5f) * 2f * PI.toFloat() / holes)
        circle(x, y, 0.17f * root)
      }
    }
    return Outline.Generic(path).also {
      outline = it
      outlineSize = size
    }
  }

  override fun equals(other: Any?) =
    other is SpurGearShape && other.teeth == teeth && other.holes == holes && other.angle == angle && other.scale == scale

  override fun hashCode() = ((teeth * 31 + holes) * 31 + angle.hashCode()) * 31 + scale.hashCode()
}

private fun polar(cx: Float, cy: Float, r: Float, a: Float) = Offset(cx + r * cos(a), cy + r * sin(a))

private fun Path.circle(x: Float, y: Float, r: Float) = addOval(Rect(x - r, y - r, x + r, y + r))

/**
 * Angle of a gear of [teeth] meshing with a driver of [driverTeeth] at [driverAngle], the driven gear's center in
 * direction [direction] (radians) from the driver's. In tooth units (`u = teeth · (angle - facing) / 2π`, `facing` the
 * direction to the other gear), a driver tooth facing the other gear (u₁ = 0) meets a gap (u₂ = ½), and u₁ + u₂ stays
 * ½ as they turn: the driven gear turns the other way at driverTeeth / teeth of the speed.
 */
private fun meshedAngle(driverAngle: Float, driverTeeth: Int, teeth: Int, direction: Float): Float {
  val u1 = driverTeeth * (driverAngle - direction) / (2f * PI.toFloat())
  return direction + PI.toFloat() + 2f * PI.toFloat() / teeth * (0.5f - u1)
}

private val GearShadowColor = Color.Black

/** Tooth size of the gear train, dp. */
private const val TrainModule = 6f

/** Seconds per turn of the train's driver; the driven gears follow by their teeth ratios. */
private const val DriverTurn = 6f

/** Seconds per scale pulse of the third gear. */
private const val PulsePeriod = 2f

/** Largest scale of the pulsing gear; its center distance meshes at this scale. */
private const val PulseMax = 1.1f

/** Added to each centre distance, dp: a hairline between tips and roots, so the mesh reads at a glance. */
private const val MeshClearance = 1f

/** Seconds per turn of the inner-shadow gear. */
private const val BigTurn = 20f

/** The bottom gear's inner shadow offset circles at this radius, dp, once per [OffsetTurn] s. */
private const val OffsetCircle = 5.5f
private const val OffsetTurn = 2f

/** One gear of the train: teeth, center in the train box (dp), scale range, and its angle and scale by the clock. */
private class TrainGear(
  val teeth: Int,
  val center: DpOffset,
  val maxScale: Float,
  val angle: (Float) -> Float,
  val scale: (Float) -> Float,
) {
  /** Box side: twice the tip radius at [maxScale]. */
  val box: Dp = (TrainModule * (teeth / 2f + 1f) * 2f * maxScale).dp
  private val memo = ShapeMemo { a, s -> SpurGearShape(teeth, holes = 6, angle = a, scale = s / maxScale) }

  fun shape(seconds: Float): Shape = memo.at(angle(seconds), scale(seconds))
}

/**
 * The train: A (20 teeth) drives B (12 teeth, up-right of A), B drives C (16 teeth, down-right of B), all of module
 * [TrainModule]; C's scale pulses 0.9 → 1.1 and its center distance meshes at 1.1.
 */
private fun gearTrain(): List<TrainGear> {
  val pitchA = TrainModule * 20 / 2f
  val pitchB = TrainModule * 12 / 2f
  val pitchC = TrainModule * 16 / 2f
  val ab = (-35f).toRadians()
  val bc = 35f.toRadians()
  val a = Offset(95f, 180f)
  val b = a + Offset(cos(ab), sin(ab)) * (pitchA + pitchB + MeshClearance)
  val c = b + Offset(cos(bc), sin(bc)) * (pitchB + pitchC * PulseMax + MeshClearance)
  // Not wrapped per turn: a driver jump of 2π moves C by 450°, not a symmetry of its 6 holes. Over the clock's 60 s
  // the driver makes 10 turns, B 16⅔ and C 12½, each a symmetry of its teeth and holes.
  val angleA = { s: Float -> 2f * PI.toFloat() * s / DriverTurn }
  val angleB = { s: Float -> meshedAngle(angleA(s), 20, 12, ab) }
  val angleC = { s: Float -> meshedAngle(angleB(s), 12, 16, bc) }
  val one = { _: Float -> 1f }
  return listOf(
    TrainGear(20, a.toDpOffset(), 1f, angleA, one),
    TrainGear(12, b.toDpOffset(), 1f, angleB, one),
    TrainGear(16, c.toDpOffset(), PulseMax, angleC) { s -> 1f + 0.1f * sin(2f * PI.toFloat() * cycle(s, PulsePeriod)) },
  )
}

private fun Float.toRadians() = this * PI.toFloat() / 180f

private fun Offset.toDpOffset() = DpOffset(x.dp, y.dp)

/**
 * `t3_gears`: top half, a train of three meshing gears with drop shadows (radius 10 dp, a fixed 6 dp offset down: the
 * light stays put while the outlines turn; all three shadows drawn before the gears); bottom half, a 220 dp 24-tooth gear turning once per [BigTurn] s with an
 * inner shadow whose offset circles at [OffsetCircle] dp. The shapes themselves turn (a new [SpurGearShape] per pose,
 * read in the draw phase through [measuredLiveDropShadow] / [measuredLiveInnerShadow]), so every shadow is rebuilt
 * from the turned outline each frame; nothing recomposes. Shadows draw with [LocalShadowImpl].
 */
@Composable
fun GearsScreen(modifier: Modifier = Modifier) {
  val clock = tierClock()
  val impl = LocalShadowImpl.current
  val train = remember { gearTrain() }
  val big = remember { ShapeMemo { a, _ -> SpurGearShape(teeth = 24, holes = 6, angle = a) } }
  val colors = SceneColors.current
  Column(modifier.fillMaxSize().background(colors.gears)) {
    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
      Box(Modifier.size(320.dp, 300.dp)) {
        // All shadows under all gears: the gears lie in one plane, so no gear's shadow falls on a neighbour.
        for (gear in train) TrainGearShadow(gear, clock, impl)
        for (gear in train) TrainGearFill(gear, clock, colors.gearMetal)
      }
    }
    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
      val shape = remember { { big.at(2f * PI.toFloat() * cycle(clock.value, BigTurn)) } }
      Box(
        Modifier
          .size(220.dp)
          .outlineAhead(shape)
          .liveShapeFill(shape, colors.gearMetal)
          .measuredLiveInnerShadow(shape, impl) {
            val turn = 2f * PI.toFloat() * cycle(clock.value, OffsetTurn)
            val distance = OffsetCircle.dp.toPx()
            radius = 12.dp.toPx()
            color = GearShadowColor
            alpha = 0.6f
            offset = Offset(distance * cos(turn), distance * sin(turn))
          },
      )
    }
  }
}

/** A gear's drop shadow alone, in the gear's place. */
@Composable
private fun TrainGearShadow(gear: TrainGear, clock: State<Float>, impl: ShadowImpl) {
  val shape = remember(gear) { { gear.shape(clock.value) } }
  Box(
    Modifier
      .trainGearPlace(gear)
      .outlineAhead(shape)
      .measuredLiveDropShadow(shape, impl) {
        radius = 10.dp.toPx()
        color = GearShadowColor
        alpha = 0.55f
        offset = Offset(0f, 6.dp.toPx())
      },
  )
}

/** The gear itself, drawn after every [TrainGearShadow]; the same pose's shape instance as its shadow ([ShapeMemo]). */
@Composable
private fun TrainGearFill(gear: TrainGear, clock: State<Float>, metal: Color) {
  val shape = remember(gear) { { gear.shape(clock.value) } }
  Box(Modifier.trainGearPlace(gear).liveShapeFill(shape, metal))
}

private fun Modifier.trainGearPlace(gear: TrainGear): Modifier =
  offset(gear.center.x - gear.box / 2, gear.center.y - gear.box / 2).size(gear.box)
