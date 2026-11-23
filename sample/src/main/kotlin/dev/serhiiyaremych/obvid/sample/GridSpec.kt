// Copyright 2026, Serhii Yaremych (desugar-64) and the Obvid project contributors
// SPDX-License-Identifier: Apache-2.0

package dev.serhiiyaremych.obvid.sample

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialShapes
import androidx.compose.runtime.Immutable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.Cubic
import androidx.graphics.shapes.Morph
import androidx.graphics.shapes.RoundedPolygon
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/** Number of tiles in the stress grid. */
const val GridItemCount = 600

/**
 * Which shadows the grid draws (intent extra `shadows`).
 * - [None]: the control. Identical tiles and tile motion (morph, breathe scale, lift), no shadows.
 * - [Static]: every shadowed tile has its shadow, but the clock is frozen at 0: animated tiles show
 *   their pose at their own phase, nothing animates.
 * - [Mix]: the clock runs; animated tiles animate their shadow (and morph tiles their shape).
 */
enum class ShadowMode(val label: String) {
  None("none"), Static("static"), Mix("mix");

  companion object {
    fun parse(value: String?): ShadowMode = entries.firstOrNull { it.label == value } ?: Mix
  }
}

/** What animates on a tile. Every animated tile has its own period and phase from its seed. */
enum class Motion {
  /** Static shadow. */
  Still,
  /** Radius pulse (block form) plus a slight scale (graphics layer). Rebuilds every frame. */
  Breathe,
  /** Shape morphs between two MaterialShapes; background and shadow follow. Rebuilds every frame. */
  Morph,
  /** Offset circles the tile (block form): the cheap case, stays cached. */
  Orbit,
  /** Offset-y and radius rise together, the tile lifts (hover/press elevation). Rebuilds every frame. */
  Lift,
}

/** A tile color and its darker tinted shadow (also the ink of the tile numeral). */
@Immutable
class Tone(val tile: Color, val shadow: Color)

val Tones = listOf(
  Tone(Color(0xFFE9DDFF), Color(0xFF5B3FA8)), // lavender
  Tone(Color(0xFFFFDCC8), Color(0xFFB0542A)), // peach
  Tone(Color(0xFFC8F1DC), Color(0xFF1F7A54)), // mint
  Tone(Color(0xFFD2E6FF), Color(0xFF2A5FA8)), // sky
  Tone(Color(0xFFFFEDB0), Color(0xFFA67C00)), // butter
  Tone(Color(0xFFFFD9E3), Color(0xFFB0305E)), // rose
  Tone(Color(0xFFE0EBC9), Color(0xFF587A2C)), // sage
  Tone(Color(0xFFC6EEF1), Color(0xFF137780)), // aqua
  Tone(Color(0xFFFFD7D0), Color(0xFFC0402E)), // coral
  Tone(Color(0xFFDCE0FF), Color(0xFF3E4BB0)), // periwinkle
)

/** The tile outline. Shapes compare by value, so equal static tiles share Compose's shadow cache. */
@Immutable
sealed interface TileForm {
  val name: String

  /** Round rect (uniform corners take Skia's round-rect branch, mixed ones the path branch). */
  class Rect(override val name: String, val shape: RoundedCornerShape, val height: Dp) : TileForm

  /** One MaterialShape, kept at its own proportions inside a box of [aspect] (width / height). */
  class Polygon(val shape: PolygonShape, val aspect: Float) : TileForm {
    override val name get() = shape.name
  }

  /** Two MaterialShapes morphing (pair index into [MorphPairs]). */
  class Morphing(val pair: Int, val aspect: Float) : TileForm {
    override val name get() = MorphPairs[pair].let { "${it.first}↔${it.second}" }
  }
}

/** Static shadow parameters of a tile, in whole dp (they are printed in the caption). */
@Immutable
class ShadowSpec(val radius: Int, val spread: Int, val dx: Int, val dy: Int, val color: Color, val alpha: Float) {
  val shadow = Shadow(radius.dp, color, spread.dp, DpOffset(dx.dp, dy.dp), alpha)
}

/**
 * Everything about one tile, from `Random(seed = index)` only, so every run (and every shadow
 * implementation) renders the identical grid. [shadow] is null for the ~15 % unshadowed tiles.
 * Animation pose at clock `t` seconds: `fraction(t / period + phase)`.
 */
@Immutable
class TileSpec(
  val index: Int,
  val tone: Tone,
  val form: TileForm,
  val shadow: ShadowSpec?,
  val motion: Motion,
  val period: Float,
  val phase: Float,
) {
  companion object {
    fun of(index: Int): TileSpec {
      val random = Random(index)
      val tone = Tones[random.nextInt(Tones.size)]
      val roll = random.nextFloat()
      val motion = when {
        roll < 0.15f -> null // no shadow
        roll < 0.75f -> Motion.Still
        else -> Motion.entries[1 + random.nextInt(4)]
      }
      val form = if (motion == Motion.Morph) {
        TileForm.Morphing(random.nextInt(MorphPairs.size), aspect = random.range(0.85f, 1.15f))
      } else {
        randomForm(random)
      }
      val shadow = motion?.let { randomShadow(random, tone) }
      return TileSpec(
        index, tone, form, shadow, motion ?: Motion.Still,
        // Periods divide the 60 s clock, so poses stay continuous when the clock wraps.
        period = Periods[random.nextInt(Periods.size)],
        phase = random.nextFloat(),
      )
    }

    private val Periods = floatArrayOf(2f, 2.5f, 3f, 4f, 5f, 6f)
    private val Corners = intArrayOf(12, 20, 28, 36, 48)
    private val MixedCorners = intArrayOf(4, 8, 16, 32, 48, 64)

    private fun randomForm(random: Random): TileForm {
      val kind = random.nextFloat()
      return when {
        kind < 0.35f -> {
          val corner = Corners[random.nextInt(Corners.size)]
          TileForm.Rect("RoundRect $corner", RoundedCornerShape(corner.dp), random.range(120f, 260f).dp)
        }
        kind < 0.5f -> {
          val c = IntArray(4) { MixedCorners[random.nextInt(MixedCorners.size)] }
          TileForm.Rect(
            "Corners ${c.joinToString("·")}",
            RoundedCornerShape(c[0].dp, c[1].dp, c[2].dp, c[3].dp),
            random.range(120f, 260f).dp,
          )
        }
        else -> TileForm.Polygon(PolygonShapes[random.nextInt(PolygonShapes.size)], random.range(0.8f, 1.2f))
      }
    }

    private fun randomShadow(random: Random, tone: Tone): ShadowSpec {
      val radius = 4 + random.nextInt(45) // 4..48
      val spread = if (random.nextBoolean()) 0 else 1 + random.nextInt(8) // 0..8
      val distance = random.nextInt(13) // 0..12
      // Mostly light from above (shadow falls down), sometimes from anywhere.
      val degrees = if (random.nextFloat() < 0.8f) random.range(30f, 150f) else random.range(0f, 360f)
      val angle = degrees * PI.toFloat() / 180f
      return ShadowSpec(
        radius, spread,
        dx = (distance * cos(angle)).roundToInt(), dy = (distance * sin(angle)).roundToInt(),
        color = tone.shadow, alpha = random.range(0.3f, 0.55f),
      )
    }

    private fun Random.range(from: Float, to: Float) = from + (to - from) * nextFloat()
  }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
private val Polygons: Map<String, RoundedPolygon> by lazy {
  with(MaterialShapes) {
    mapOf(
      "Cookie4Sided" to Cookie4Sided, "Cookie6Sided" to Cookie6Sided, "Cookie9Sided" to Cookie9Sided,
      "Cookie12Sided" to Cookie12Sided, "Clover4Leaf" to Clover4Leaf, "Clover8Leaf" to Clover8Leaf,
      "Sunny" to Sunny, "VerySunny" to VerySunny, "Gem" to Gem, "Pill" to Pill, "Arch" to Arch,
      "Slanted" to Slanted, "Burst" to Burst, "SoftBurst" to SoftBurst, "Flower" to Flower,
      "Puffy" to Puffy, "Ghostish" to Ghostish, "Pentagon" to Pentagon, "Heart" to Heart, "Bun" to Bun,
      "SoftBoom" to SoftBoom, "PuffyDiamond" to PuffyDiamond, "Diamond" to Diamond, "Fan" to Fan,
      "Circle" to Circle,
    )
  }
}

/** Names of the polygons static tiles draw from (one shared [PolygonShape] instance each). */
private val PolygonNames = listOf(
  "Cookie4Sided", "Cookie6Sided", "Cookie9Sided", "Cookie12Sided", "Clover4Leaf", "Clover8Leaf", "Sunny",
  "VerySunny", "Gem", "Pill", "Arch", "Slanted", "Burst", "SoftBurst", "Flower", "Puffy", "Ghostish",
  "Pentagon", "Heart", "Bun", "SoftBoom", "PuffyDiamond", "Diamond", "Fan",
)
private val PolygonShapes = PolygonNames.map(::PolygonShape)

val MorphPairs = listOf(
  "Cookie9Sided" to "Pill", "Sunny" to "Cookie6Sided", "Flower" to "Puffy", "Gem" to "Heart",
  "SoftBurst" to "Circle", "Ghostish" to "Bun", "Pentagon" to "Clover4Leaf", "Arch" to "PuffyDiamond",
  "Cookie4Sided" to "VerySunny", "Clover8Leaf" to "SoftBoom",
)
private val Morphs = arrayOfNulls<Morph>(MorphPairs.size)

private fun morph(pair: Int): Morph =
  Morphs[pair] ?: MorphPairs[pair].let { (a, b) -> Morph(Polygons.getValue(a), Polygons.getValue(b)) }.also { Morphs[pair] = it }

/**
 * A MaterialShape scaled to the layout box like material3's `RoundedPolygon.toShape()` (unit
 * square to box, centered), but one app-wide instance per name with value equality, so a static
 * tile that scrolls back in hits Compose's shadow cache as it would in a real app.
 */
class PolygonShape(val name: String) : Shape {
  private val cubics by lazy { Polygons.getValue(name).cubics }

  override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density) =
    Outline.Generic(cubics.toUnitPath().fitTo(size))

  override fun equals(other: Any?) = other is PolygonShape && other.name == name

  override fun hashCode() = name.hashCode()
}

/**
 * Morph pair [pair] at [progress], scaled like [PolygonShape]. Value equality on (pair, progress): a
 * new progress is a new shadow cache key (the animated case); a frozen pose stays cached.
 */
class MorphPoseShape(private val pair: Int, private val progress: Float) : Shape {
  private var outlineSize = Size.Unspecified
  private var outline: Outline? = null

  override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
    outline?.let { if (size == outlineSize) return it }
    return Outline.Generic(morph(pair).asCubics(progress).toUnitPath().fitTo(size)).also {
      outline = it
      outlineSize = size
    }
  }

  override fun equals(other: Any?) = other is MorphPoseShape && other.pair == pair && other.progress == progress

  override fun hashCode() = 31 * pair + progress.hashCode()
}

private fun List<Cubic>.toUnitPath(): Path = Path().apply {
  moveTo(first().anchor0X, first().anchor0Y)
  for (c in this@toUnitPath) cubicTo(c.control0X, c.control0Y, c.control1X, c.control1Y, c.anchor1X, c.anchor1Y)
  close()
}

/** Unit-square path scaled to [size] and centered, as material3's `toShape` does. */
private fun Path.fitTo(size: Size): Path = apply {
  transform(Matrix().apply { scale(size.width, size.height) })
  val bounds = getBounds()
  translate(Offset(size.width / 2 - bounds.center.x, size.height / 2 - bounds.center.y))
}
