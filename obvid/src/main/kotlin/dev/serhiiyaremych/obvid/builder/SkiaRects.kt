// Copyright 2025 Google LLC
// Copyright 2026, Serhii Yaremych (desugar-64) and the Obvid project contributors (Kotlin port)
// SPDX-License-Identifier: BSD-3-Clause
// See LICENSES/Skia-BSD-3-Clause.txt

package dev.serhiiyaremych.obvid.builder

/** Skia's `trivial_rect` test (SkPathPriv.cpp) on the contour of the 4 points at [base] in [m] (x, y pairs). */
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
