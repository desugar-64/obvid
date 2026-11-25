// Copyright 2026, Serhii Yaremych (desugar-64) and the Obvid project contributors
// SPDX-License-Identifier: Apache-2.0

package dev.serhiiyaremych.obvid.builder

import dev.serhiiyaremych.obvid.ObvidNative

/**
 * Skia's nested-rects test on a canonical frame ([ShadowBitmaps.isFrame]) moved by Compose's translate ([test]): the
 * moved points ([moved]) and the nested rects Skia finds in them ([rects]: outer, inner; LTRB), kept for the routes
 * that draw the frame ([setMovedPath], [plan], [NineRectsRoute]).
 */
internal class NestedRects {
  /** The canonical frame's points moved by the last [test]'s translate (x, y pairs). */
  private val moved = FloatArray(16)

  /** The outer and the inner rect of the last [test] that answered [Answer.Nested] (LTRB each). */
  val rects = FloatArray(8)

  /** [test]'s answers. */
  enum class Answer {
    /**
     * A point is not finite, or a contour is not an exact axis-aligned rect (`trivial_rect` fails: a zero-length side,
     * collinear points, an edge off axis). Skia's general rect loop may still take such a contour for a rect, so the
     * build decides as for any other path ([ShadowBitmaps.nestedRects]).
     */
    Unknown,

    /** Both contours are rects and one contains the other: [rects] holds the outer and the inner rect. */
    Nested,

    /**
     * Both contours are rects and neither contains the other: Skia's test fails, and `isRect` too (it rejects a
     * second contour), so Skia blurs the path's whole mask (the mask route).
     */
    NotNested,
  }

  /**
   * Skia's nested-rects test (`SkPathPriv::IsNestedFillRects`) on a canonical frame's [points] ([ShadowBitmaps.isFrame])
   * moved by Compose's translate ([tx], [ty]): each point `p + t` in float, as the CTM maps it.
   */
  fun test(points: FloatArray, tx: Float, ty: Float): Answer {
    val m = moved
    for (i in m.indices) m[i] = points[i] + if ((i and 1) == 0) tx else ty
    val bothRects = m.all { it.isFinite() } && trivialRect(m, 0) && trivialRect(m, 8)
    return if (bothRects) nestedOrNot(m) else Answer.Unknown
  }

  /** [test] for two contours that pass `trivial_rect`'s test: [Answer.Nested] (with [rects] set) or [Answer.NotNested]. */
  private fun nestedOrNot(m: FloatArray): Answer {
    // Each contour's rect as Skia computes it. Skia tests the first contour with its general loop (bounds of the second
    // and fourth point) and the second with `trivial_rect` (the sorted first and third point). For an exact rect both
    // give the same edges up to the sign of a zero, so each keeps Skia's choice.
    val firstL = skMin(m[2], m[6])
    val firstT = skMin(m[3], m[7])
    val firstR = skMax(m[2], m[6])
    val firstB = skMax(m[3], m[7])
    val secondL = skMin(m[8], m[12])
    val secondT = skMin(m[9], m[13])
    val secondR = skMax(m[8], m[12])
    val secondB = skMax(m[9], m[13])
    // SkRect::contains both ways, the first contour as the outer one first.
    return when {
      contains(firstL, firstT, firstR, firstB, secondL, secondT, secondR, secondB) -> {
        rects.setRects(firstL, firstT, firstR, firstB, secondL, secondT, secondR, secondB)
        Answer.Nested
      }
      contains(secondL, secondT, secondR, secondB, firstL, firstT, firstR, firstB) -> {
        rects.setRects(secondL, secondT, secondR, secondB, firstL, firstT, firstR, firstB)
        Answer.Nested
      }
      else -> Answer.NotNested
    }
  }

  /**
   * [path] := the canonical frame moved by the last [test] (each point `p + t` in float), as move, three lines and
   * close per contour, with [fillType]: the verbs and points `set` and `offset` give the source path, built without
   * `addRect` or `set` so that the platform's same-direction-rects shortcut (API 23–33, [MaskRoute.setDropPath])
   * cannot apply.
   */
  fun setMovedPath(path: android.graphics.Path, fillType: android.graphics.Path.FillType) {
    val m = moved
    path.rewind()
    path.addContour(m, 0)
    path.addContour(m, 8)
    path.fillType = fillType
  }

  /** `ObvidNative.builderPlanRects` for [rects] blurred with [radius]. */
  fun plan(radius: Float): Long {
    val r = rects
    return ObvidNative.builderPlanRects(r[0], r[1], r[2], r[3], r[4], r[5], r[6], r[7], radius)
  }
}

/**
 * `trivial_rect`'s test (SkPathPriv.cpp) on the contour of the 4 points at [base] in [m] (x, y pairs): the side vectors
 * `v0 = p1 − p0`, `v1 = p2 − p1`, `v2 = p3 − p2`, `v3 = p0 − p3` in float; `v0` has exactly one zero component and each
 * next vector is orthogonal to the one before in the other dimension.
 */
private fun trivialRect(m: FloatArray, base: Int): Boolean {
  val v0x = m[base + 2] - m[base]
  val v0y = m[base + 3] - m[base + 1]
  val v1x = m[base + 4] - m[base + 2]
  val v1y = m[base + 5] - m[base + 3]
  val v2x = m[base + 6] - m[base + 4]
  val v2y = m[base + 7] - m[base + 5]
  val v3x = m[base] - m[base + 6]
  val v3y = m[base + 1] - m[base + 7]
  return ((v0x == 0f) xor (v0y == 0f)) &&
    orthogonal(v0x, v0y, v1x, v1y) &&
    orthogonal(v1x, v1y, v2x, v2y) &&
    orthogonal(v2x, v2y, v3x, v3y)
}

/** `trivial_rect`'s `axis_aligned_orthogonal`: exactly one of the two x components is zero, and one of the y ones. */
private fun orthogonal(ax: Float, ay: Float, bx: Float, by: Float): Boolean = ((ax == 0f) xor (bx == 0f)) && ((ay == 0f) xor (by == 0f))

/** `std::min(a, b)` on floats: `(b < a) ? b : a`. */
private fun skMin(a: Float, b: Float): Float = if (b < a) b else a

/** `std::max(a, b)` on floats: `(a < b) ? b : a`. */
private fun skMax(a: Float, b: Float): Float = if (a < b) b else a

/**
 * `SkRect::contains(const SkRect&)` of rect a (`al`, `at`, `ar`, `ab`) and rect b: both non-empty (`l < r && t < b`)
 * and a's edges on or outside b's.
 */
private fun contains(al: Float, at: Float, ar: Float, ab: Float, bl: Float, bt: Float, br: Float, bb: Float): Boolean =
  (bl < br && bt < bb) && (al < ar && at < ab) && al <= bl && at <= bt && ar >= br && ab >= bb

/**
 * `addRect(l, t, r, b, CW)`'s contour (Skia's start index 0: `(l, t) (r, t) (r, b) (l, b)`, then close), written as
 * move, three lines and close, which clears the platform's same-direction-rects shortcut (API 23–33).
 */
internal fun android.graphics.Path.addClockwiseRect(l: Float, t: Float, r: Float, b: Float) {
  moveTo(l, t)
  lineTo(r, t)
  lineTo(r, b)
  lineTo(l, b)
  close()
}

/** The contour of the 4 points at [base] in [m] ((x, y) pairs): move, three lines, close. */
private fun android.graphics.Path.addContour(m: FloatArray, base: Int) {
  moveTo(m[base], m[base + 1])
  lineTo(m[base + 2], m[base + 3])
  lineTo(m[base + 4], m[base + 5])
  lineTo(m[base + 6], m[base + 7])
  close()
}

/** Stores the outer rect ([outerL], [outerT], [outerR], [outerB]) and the inner rect into the first 8 floats. */
private fun FloatArray.setRects(
  outerL: Float,
  outerT: Float,
  outerR: Float,
  outerB: Float,
  innerL: Float,
  innerT: Float,
  innerR: Float,
  innerB: Float,
) {
  this[0] = outerL
  this[1] = outerT
  this[2] = outerR
  this[3] = outerB
  this[4] = innerL
  this[5] = innerT
  this[6] = innerR
  this[7] = innerB
}
