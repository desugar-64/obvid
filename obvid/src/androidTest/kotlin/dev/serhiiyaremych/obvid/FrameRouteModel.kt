// Copyright 2026, Serhii Yaremych (desugar-64) and the Obvid project contributors
// SPDX-License-Identifier: Apache-2.0

package dev.serhiiyaremych.obvid

import android.os.Build
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.PathIterator as ComposePathIterator
import androidx.compose.ui.graphics.PathSegment
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import dev.serhiiyaremych.obvid.builder.ShadowBitmapBuilder
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max

/**
 * The tests' own model of the route [ShadowBitmapBuilder] takes for a path outline drawn with one blurred fill (a drop
 * shadow at spread ≤ 0, an inner shadow at spread ≤ 0) with radius > 0, written from Android 17's Skia independently
 * of the builder's code, to assert its routes:
 * - The path is a frame Skia's `IsNestedFillRects` can be decided on exactly only when its verbs are move, three lines,
 *   close, twice (read with the platform's `PathIterator` on API 34+). Its points are moved by the translate in float.
 * - Each contour is a rect when its four side vectors (the last one back to the start) are each non-zero along exactly
 *   one axis, alternating horizontal and vertical; its rect is the bounds of its points.
 * - Nested when one rect contains the other (both non-empty, edges on or outside), the first contour first. Two rects
 *   that are not nested: Skia blurs the path's mask (the mask route).
 * - `filterRectsToNine`: an outer rect past ±32767 (or wider or higher than that) or a `roundIn(inner)` hole narrower
 *   or lower than `2m + 3` (m the blur border), or a small frame under 2 px: the mask route; otherwise the frame
 *   nine-patch.
 * - Any other path (not canonical, or a contour that is not a rect by that test): the Skia route when the builder's
 *   superset flag may take it for nested rects, else the mask route.
 */
internal object FrameRouteModel {

  /** No hole (the rects are not nested, or the path is not a canonical frame). */
  const val NO_HOLE = Int.MIN_VALUE

  /**
   * The model's answer: the route name, why ([kind]: `notCanonical`, `notRects`, `notNested`, `exceeds`, `hole`,
   * `small`, `nine`), and for nested rects the hole minus `2m + 3` on each axis.
   */
  class Decision(val route: String, val kind: String, val holeX: Int = NO_HOLE, val holeY: Int = NO_HOLE) {
    /** The hole against the threshold, `x:k,y:k` (k clamped to −2..2 as `<` / `>`), or `-` without nested rects. */
    val holeLabel: String
      get() = if (holeX == NO_HOLE) "-" else "x:${bucket(holeX)},y:${bucket(holeY)}"

    private fun bucket(k: Int) = if (k < -1) "<-1" else if (k > 1) ">1" else k.toString()
  }

  /** The 8 points of [outline]'s path if it is a canonical frame (see the class docs), else null. */
  fun canonicalPoints(outline: Outline): FloatArray? {
    val path = (outline as? Outline.Generic)?.path?.asAndroidPath() ?: return null
    if (path.isInverseFillType) return null
    val verbs = ArrayList<Char>()
    val points = ArrayList<Float>()
    val buf = FloatArray(8)
    if (Build.VERSION.SDK_INT >= 34) {
      val iterator = path.pathIterator
      while (iterator.hasNext()) {
        when (iterator.next(buf, 0)) {
          android.graphics.PathIterator.VERB_MOVE -> {
            verbs += 'M'
            points += buf[0]
            points += buf[1]
          }
          android.graphics.PathIterator.VERB_LINE -> {
            verbs += 'L'
            points += buf[2]
            points += buf[3]
          }
          android.graphics.PathIterator.VERB_CLOSE -> verbs += 'Z'
          android.graphics.PathIterator.VERB_DONE -> break
          else -> return null
        }
      }
    } else {
      val iterator = path.asComposePath().iterator(ComposePathIterator.ConicEvaluation.AsConic, 0.25f)
      while (iterator.hasNext()) {
        when (iterator.next(buf, 0)) {
          PathSegment.Type.Move -> {
            verbs += 'M'
            points += buf[0]
            points += buf[1]
          }
          PathSegment.Type.Line -> {
            verbs += 'L'
            points += buf[2]
            points += buf[3]
          }
          PathSegment.Type.Close -> verbs += 'Z'
          PathSegment.Type.Done -> break
          else -> return null
        }
      }
    }
    return if (verbs.joinToString("") == "MLLLZMLLLZ") points.toFloatArray() else null
  }

  /**
   * The route for a path whose canonical-frame points are [points] (null: not canonical) moved by ([tx], [ty]), blurred
   * with [radius] > 0; [superset] is whether the builder's nested-rects superset takes the path (its route for a path
   * the model cannot decide).
   */
  fun decide(points: FloatArray?, superset: Boolean, tx: Float, ty: Float, radius: Float): Decision {
    val fallback = if (superset) "Skia" else "Mask"
    if (points == null) return Decision(fallback, "notCanonical")
    val m = FloatArray(16) { i -> points[i] + if (i % 2 == 0) tx else ty }
    if (m.any { !it.isFinite() } || !isRect(m, 0) || !isRect(m, 8)) return Decision(fallback, "notRects")
    val a = bounds(m, 0)
    val b = bounds(m, 8)
    val (outer, inner) =
      when {
        contains(a, b) -> a to b
        contains(b, a) -> b to a
        else -> return Decision("Mask", "notNested")
      }
    return plan(outer, inner, radius)
  }

  /** `filterRectsToNine`'s decision for nested [outer] and [inner] (see the class docs). */
  private fun plan(outer: Rect, inner: Rect, radius: Float): Decision {
    val limit = 32767f
    if (outer.left < -limit || outer.top < -limit || outer.right > limit || outer.bottom > limit ||
      outer.right - outer.left > limit || outer.bottom - outer.top > limit
    ) {
      return Decision("Mask", "exceeds")
    }
    val threshold = 2 * ObvidNative.builderBorder(radius) + 3
    val holeW = floor(inner.right).toInt() - ceil(inner.left).toInt()
    val holeH = floor(inner.bottom).toInt() - ceil(inner.top).toInt()
    val dx = holeW - threshold
    val dy = holeH - threshold
    if (dx < 0 || dy < 0) return Decision("Mask", "hole", dx, dy)
    if ((outer.right - dx) - outer.left < 2f || (outer.bottom - dy) - outer.top < 2f) return Decision("Mask", "small", dx, dy)
    return Decision(ShadowBitmapBuilder.Route.NineRects.name, "nine", dx, dy)
  }

  /** The 4 points at [base] form a rect: every side (the last back to the start) along exactly one axis, alternating. */
  private fun isRect(m: FloatArray, base: Int): Boolean {
    var previousHorizontal: Boolean? = null
    for (i in 0 until 4) {
      val j = (i + 1) % 4
      val vx = m[base + 2 * j] - m[base + 2 * i]
      val vy = m[base + 2 * j + 1] - m[base + 2 * i + 1]
      val horizontal = vx != 0f && vy == 0f
      val vertical = vx == 0f && vy != 0f
      if (!horizontal && !vertical) return false
      if (previousHorizontal == horizontal) return false
      previousHorizontal = horizontal
    }
    return true
  }

  private fun bounds(m: FloatArray, base: Int): Rect {
    val xs = (0 until 4).map { m[base + 2 * it] }
    val ys = (0 until 4).map { m[base + 2 * it + 1] }
    return Rect(xs.min(), ys.min(), xs.max(), ys.max())
  }

  private fun contains(a: Rect, b: Rect): Boolean =
    a.left < a.right && a.top < a.bottom && b.left < b.right && b.top < b.bottom &&
      a.left <= b.left && a.top <= b.top && a.right >= b.right && a.bottom >= b.bottom

  /**
   * A frame on a [side]-px box whose inner rect, moved by ([tx], [ty]), rounds in (`ceil` left, `floor` right) to about
   * `threshold + k` px on [axis] (0 = x, 1 = y) and to more than [threshold] on the other axis, its left and top edges
   * fractional by [frac] after the move: aims a hole just below, at or above Skia's `2m + 3`. Null when it does not
   * fit inside the box.
   */
  fun holeFrame(side: Float, threshold: Int, axis: Int, k: Int, frac: Float, tx: Float, ty: Float, evenOdd: Boolean): RectsFrameShape? {
    fun span(t: Float, w: Int): Pair<Float, Float>? {
      if (w < 0 || w + 4 > side) return null
      val movedLeft = floor((side - w) / 2f) + frac + t
      val roundedLeft = ceil(movedLeft)
      val movedRight = roundedLeft + w + (1f - frac) * 0.5f
      val l = movedLeft - t
      val r = movedRight - t
      return if (l > 0f && r < side && l < r) l to r else null
    }
    val main = threshold + k
    val other = max(threshold + 8, (side * 0.6f).toInt())
    val x = span(tx, if (axis == 0) main else other) ?: return null
    val y = span(ty, if (axis == 1) main else other) ?: return null
    return RectsFrameShape(Rect(0f, 0f, side, side), Rect(x.first, y.first, x.second, y.second), evenOdd)
  }
}

/**
 * Two `addRect` contours with explicit edges in px (the layout size is ignored): [outer] clockwise and [inner]
 * counter-clockwise (a hole under non-zero fill), or with [evenOdd] both clockwise under even-odd fill.
 */
internal class RectsFrameShape(private val outer: Rect, private val inner: Rect, private val evenOdd: Boolean = false) : Shape {
  override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
    val p = android.graphics.Path()
    p.addRect(outer.left, outer.top, outer.right, outer.bottom, android.graphics.Path.Direction.CW)
    val dir = if (evenOdd) android.graphics.Path.Direction.CW else android.graphics.Path.Direction.CCW
    p.addRect(inner.left, inner.top, inner.right, inner.bottom, dir)
    if (evenOdd) p.fillType = android.graphics.Path.FillType.EVEN_ODD
    return Outline.Generic(p.asComposePath())
  }
}
