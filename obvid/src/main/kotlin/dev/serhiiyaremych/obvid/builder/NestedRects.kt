// Copyright 2026, Serhii Yaremych (desugar-64) and the Obvid project contributors
// SPDX-License-Identifier: Apache-2.0

package dev.serhiiyaremych.obvid.builder

/**
 * `trivial_rect`'s test (SkPathPriv.cpp) on the contour of the 4 points at [base] in [m] (x, y pairs): the side vectors
 * `v0 = p1 − p0`, `v1 = p2 − p1`, `v2 = p3 − p2`, `v3 = p0 − p3` in float; `v0` has exactly one zero component and each
 * next vector is orthogonal to the one before in the other dimension.
 */
internal fun trivialRect(m: FloatArray, base: Int): Boolean {
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
internal fun skMin(a: Float, b: Float): Float = if (b < a) b else a

/** `std::max(a, b)` on floats: `(a < b) ? b : a`. */
internal fun skMax(a: Float, b: Float): Float = if (a < b) b else a

/**
 * `SkRect::contains(const SkRect&)` of rect a (`al`, `at`, `ar`, `ab`) and rect b: both non-empty (`l < r && t < b`)
 * and a's edges on or outside b's.
 */
internal fun contains(al: Float, at: Float, ar: Float, ab: Float, bl: Float, bt: Float, br: Float, bb: Float): Boolean =
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
internal fun android.graphics.Path.addContour(m: FloatArray, base: Int) {
  moveTo(m[base], m[base + 1])
  lineTo(m[base + 2], m[base + 3])
  lineTo(m[base + 4], m[base + 5])
  lineTo(m[base + 6], m[base + 7])
  close()
}

/** Stores the outer rect ([outerL], [outerT], [outerR], [outerB]) and the inner rect into the first 8 floats. */
internal fun FloatArray.setRects(
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
