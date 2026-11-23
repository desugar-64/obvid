// Copyright 2026, Serhii Yaremych (desugar-64) and the Obvid project contributors
// SPDX-License-Identifier: Apache-2.0

package dev.serhiiyaremych.obvid.sample

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.layout.LazyLayout
import androidx.compose.foundation.lazy.layout.LazyLayoutItemProvider
import androidx.compose.foundation.lazy.layout.LazyLayoutMeasurePolicy
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random
import androidx.compose.ui.util.lerp as lerpFloat

/** Field cells across one viewport width; the cell is square. */
private const val CellsPerWidth = 4

/** Field size in cells: three viewport widths, and about three phone viewport heights. */
private const val FieldColumns = 3 * CellsPerWidth
private const val FieldRows = 24

/** Seconds per loop of the camera's figure eight: one vertical sweep, two horizontal ones. */
private const val CameraLoop = 6f

/** Share of the free travel (field minus viewport) the camera covers on each axis. */
private const val CameraReach = 0.85f

/** Composed and drawn beyond the viewport, dp: a tile's shadow reaches at most ~36 dp past its cell. */
private const val CullMargin = 48

/** Seconds per hue cycle of a colour-cycling tile. */
private const val HueCycle = 4f


/** One field tile, from `Random(index)` only. */
private class FieldTile(index: Int) {
  private val random = Random(index)
  val kind = random.nextFloat().let { if (it < 0.45f) 0 else if (it < 0.8f) 1 else 2 } // round rect, morph, gear
  private val pair = random.nextInt(MorphPairs.size)
  private val period = floatArrayOf(2f, 2.5f, 3f, 4f)[random.nextInt(4)]
  private val phase = random.nextFloat()
  private val driftPhase = random.nextFloat()
  private val radiusFrom = 3f + 5f * random.nextFloat()
  private val radiusTo = 14f + 14f * random.nextFloat()
  /** Index into [SceneColors.hues]: the tile's fill and shadow color. */
  val hue = random.nextInt(SceneHueCount)
  private val hueCycles = random.nextFloat() < 0.25f
  private val huePhase = random.nextFloat()
  private val gear = GearShape()
  private val poses = ShapeMemo { p, _ -> if (kind == 0) RoundedCornerShape(lerpFloat(6f, 32f, p).dp) else MorphPoseShape(pair, p) }

  /** Animation progress 0 → 1 → 0. */
  fun pose(seconds: Float) = tierWave(cycle(seconds, period, phase))

  /** Drift angle: the tile circles 4 dp around its cell center, once per two periods. */
  fun drift(seconds: Float) = 2f * PI.toFloat() * cycle(seconds, period * 2f, driftPhase)

  fun shape(seconds: Float): Shape = if (kind == 2) gear else poses.at(pose(seconds))

  fun radiusDp(p: Float) = lerpFloat(radiusFrom, radiusTo, p)

  /** The shadow color at [seconds]: [base] (the hue's shadow tone), or a cycling hue. */
  fun shadowColor(seconds: Float, base: Color): Color =
    if (hueCycles) Color.hsv(360f * cycle(seconds, HueCycle, huePhase), 0.75f, 0.95f) else base
}

/**
 * `t4_field`: a field of [FieldColumns] × [FieldRows] square cells (a quarter of the viewport width each, so about 3 × 3
 * phone screens), each with one animated tile: a round rect (corners 6 → 32 dp), a
 * MaterialShapes morph or a gear; size 72 → 95 % of the cell, a 4 dp drift, shadow radius per tile, offset 2 → 6 dp.
 * Each tile an opaque fill in its hue's container tone with a shadow in the same hue's vivid tone ([SceneColors]), on
 * a mid surface tone; a quarter of the tiles cycle their shadow hue every [HueCycle] s. [camera] places
 * the viewport; by default it flies a figure eight over the field every [CameraLoop] s ([FigureEightCamera]).
 *
 * Culling: a [LazyLayout] over a fixed cell grid. Its measure reads the clock and the camera (layout phase), places the
 * camera, and composes, measures and places only the cells within [CullMargin] of the viewport; cells that leave are
 * disposed (the layout reuses their compositions), cells that enter are composed. Same as a lazy list's scroll, in two
 * dimensions, without a scroll state (the scripted camera is a function of the clock, identical for every
 * implementation). Inside a tile every animated value is read in the layout phase (size, drift) or the draw phase
 * (shape, shadow parameters): no tile recomposes per frame. Shadows draw with [LocalShadowImpl].
 */
@Composable
fun FieldScreen(modifier: Modifier = Modifier, camera: FieldCamera = FigureEightCamera) {
  val clock = tierClock()
  val tiles = remember { List(FieldColumns * FieldRows, ::FieldTile) }
  val provider = remember {
    object : LazyLayoutItemProvider {
      override val itemCount = tiles.size

      @Composable
      override fun Item(index: Int, key: Any) = FieldTileBox(tiles[index], clock)

      override fun getContentType(index: Int): Any = tiles[index].kind
    }
  }
  val policy = remember(camera) {
    LazyLayoutMeasurePolicy { constraints ->
      val width = constraints.maxWidth
      val height = constraints.maxHeight
      val cell = width / CellsPerWidth
      val (camX, camY) = camera.position(clock.value, IntSize(FieldColumns * cell - width, FieldRows * cell - height))
      val margin = CullMargin.dp.roundToPx()
      val columns = cellRange(camX - margin, camX + width + margin, cell, FieldColumns)
      val rows = cellRange(camY - margin, camY + height + margin, cell, FieldRows)
      val fixed = Constraints.fixed(cell, cell)
      val placed = ArrayList<Triple<Int, Int, List<Placeable>>>()
      for (row in rows) {
        for (column in columns) {
          placed += Triple(column * cell - camX, row * cell - camY, compose(row * FieldColumns + column).map { it.measure(fixed) })
        }
      }
      layout(width, height) {
        for ((x, y, placeables) in placed) placeables.forEach { it.place(x, y) }
      }
    }
  }
  LazyLayout(itemProvider = { provider }, modifier = modifier.fillMaxSize().background(SceneColors.current.field), measurePolicy = policy)
}

/** Places the field's viewport; called in the layout phase, so state it reads invalidates layout only. */
@Stable
fun interface FieldCamera {
  /** The viewport's top-left in field px at [seconds], within 0..[free] (field minus viewport) on each axis. */
  fun position(seconds: Float, free: IntSize): IntOffset
}

/**
 * A figure eight centered on the free travel (field minus viewport), [CameraReach] of it on each axis, two horizontal
 * sweeps per vertical one, once per [CameraLoop] s.
 */
val FigureEightCamera = FieldCamera { seconds, free ->
  val t = 2f * PI.toFloat() * cycle(seconds, CameraLoop)
  val x = free.width / 2f * (1f + CameraReach * sin(2f * t))
  val y = free.height / 2f * (1f + CameraReach * sin(t))
  IntOffset(x.roundToInt(), y.roundToInt())
}

/** Cells of size [cell] overlapping [from] until [to] px, within 0 until [count]. */
private fun cellRange(from: Int, to: Int, cell: Int, count: Int): IntRange =
  maxOf(0, floor(from / cell.toFloat()).toInt())..minOf(count - 1, floor(to / cell.toFloat()).toInt())

@Composable
private fun FieldTileBox(tile: FieldTile, clock: State<Float>) {
  val shape = remember(tile) { { tile.shape(clock.value) } }
  val hue = SceneColors.current.hues[tile.hue]
  Box(
    Modifier
      .layout { measurable, constraints ->
        val seconds = clock.value
        val p = tile.pose(seconds)
        val drift = tile.drift(seconds)
        val side = (constraints.maxWidth * (0.72f + 0.23f * p)).roundToInt()
        val placeable = measurable.measure(Constraints.fixed(side, side))
        val dx = (4f * cos(drift)).dp.roundToPx()
        val dy = (4f * sin(drift)).dp.roundToPx()
        layout(constraints.maxWidth, constraints.maxHeight) {
          placeable.place((constraints.maxWidth - side) / 2 + dx, (constraints.maxHeight - side) / 2 + dy)
        }
      }
      .outlineAhead(shape)
      .measuredLiveDropShadow(shape, LocalShadowImpl.current) {
        val seconds = clock.value
        val p = tile.pose(seconds)
        radius = tile.radiusDp(p).dp.toPx()
        color = tile.shadowColor(seconds, hue.shadow)
        alpha = 0.75f
        offset = Offset(0f, lerpFloat(2f, 6f, p).dp.toPx())
      }
      .liveShapeFill(shape, hue.container),
  )
}
