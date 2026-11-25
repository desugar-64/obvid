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

  /** The outer and the inner rect of the last [test] that answered [NESTED] (LTRB each). */
  val rects = FloatArray(8)

  /**
   * Skia's nested-rects test (`SkPathPriv::IsNestedFillRects`) on [bitmaps]' canonical frame ([ShadowBitmaps.isFrame])
   * moved by Compose's translate ([tx], [ty]): each point `p + t` in float, as the CTM maps it.
   *
   * Skia runs `IsRectContour` on the whole path for the first contour (`trivial_rect` needs exactly one contour, so its
   * general loop runs) and on the rest for the second (`trivial_rect` first). For a contour of move, three lines and a
   * close whose side vectors pass `trivial_rect`'s test (finite, the first with exactly one zero component, each next
   * one orthogonal to it in the other dimension), both give the same answer: four non-zero sides alternating
   * horizontal and vertical close a rect with opposite sides in opposite directions, which the general loop accepts
   * with its corners at the second and fourth point (`bounds(firstCorner, thirdCorner)`), the same rect as
   * `trivial_rect`'s sorted first and third point. Then `SkRect::contains` either way, the first contour first.
   * - [NESTED]: both contours pass and one contains the other; [rects] holds the outer and the inner rect.
   * - [NOT_NESTED]: both pass, neither contains the other: Skia's test fails, and `isRect` too (a second contour after a
   *   closed rect is rejected), so Skia blurs the path's whole mask (the mask route).
   * - [UNKNOWN]: a point is not finite or a contour fails `trivial_rect`'s test (a zero-length side, collinear points,
   *   an edge off axis). Skia's general loop may still take such a contour for a rect, so the build decides as for any
   *   other path ([ShadowBitmaps.nestedRects]).
   */
  fun test(bitmaps: ShadowBitmaps, tx: Float, ty: Float): Int {
    val p = bitmaps.framePoints
    val m = moved
    for (i in 0 until 8) {
      m[2 * i] = p[2 * i] + tx
      m[2 * i + 1] = p[2 * i + 1] + ty
      if (!m[2 * i].isFinite() || !m[2 * i + 1].isFinite()) return UNKNOWN
    }
    if (!trivialRect(m, 0) || !trivialRect(m, 8)) return UNKNOWN
    // First contour: the general loop's bounds of its second and fourth point (SkRect::set: std::min / std::max).
    val al = skMin(m[2], m[6])
    val at = skMin(m[3], m[7])
    val ar = skMax(m[2], m[6])
    val ab = skMax(m[3], m[7])
    // Second contour: trivial_rect's MakeLTRB(first point, third point).makeSorted().
    val bl = skMin(m[8], m[12])
    val bt = skMin(m[9], m[13])
    val br = skMax(m[8], m[12])
    val bb = skMax(m[9], m[13])
    val r = rects
    when {
      contains(al, at, ar, ab, bl, bt, br, bb) -> r.setRects(al, at, ar, ab, bl, bt, br, bb)
      contains(bl, bt, br, bb, al, at, ar, ab) -> r.setRects(bl, bt, br, bb, al, at, ar, ab)
      else -> return NOT_NESTED
    }
    return NESTED
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

  companion object {
    /** [test]'s answers. */
    const val UNKNOWN = 0
    const val NESTED = 1
    const val NOT_NESTED = 2
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
