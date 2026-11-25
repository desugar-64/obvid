// Copyright 2026, Serhii Yaremych (desugar-64) and the Obvid project contributors
// SPDX-License-Identifier: Apache-2.0

package dev.serhiiyaremych.obvid.builder

import android.graphics.RectF
import androidx.compose.ui.geometry.Offset
import dev.serhiiyaremych.obvid.ObvidNative

/** Skia's nested-rects test on a canonical frame ([ShadowBitmaps.isFrame]) moved by a translate, and its result. */
internal class NestedRects {
  /** The canonical frame's points moved by the last [test]'s translate (x, y pairs). */
  private val moved = FloatArray(16)

  /** The outer rect of the last [test] that answered [Answer.Nested]. */
  val outer = RectF()

  /** The inner rect of the last [test] that answered [Answer.Nested]. */
  val inner = RectF()

  // Each contour's rect, scratch of [nestedOrNot].
  private val first = RectF()
  private val second = RectF()

  /** [test]'s answers. */
  enum class Answer {
    /** A point is not finite, or a contour is not an exact axis-aligned rect: decide as for any other path. */
    Unknown,

    /** Both contours are rects and one contains the other: [outer] and [inner] hold them. */
    Nested,

    /** Both contours are rects and neither contains the other. */
    NotNested,
  }

  /** Skia's `SkPathPriv::IsNestedFillRects` on the frame's [points] moved by [translate]. */
  fun test(points: FloatArray, translate: Offset): Answer {
    val m = moved
    val tx = translate.x
    val ty = translate.y
    for (i in m.indices) m[i] = points[i] + if ((i and 1) == 0) tx else ty
    val bothRects = m.all { it.isFinite() } && trivialRect(m, 0) && trivialRect(m, 8)
    return if (bothRects) nestedOrNot(m) else Answer.Unknown
  }

  /** Whether two rect contours are nested; sets [outer] and [inner] when they are. */
  private fun nestedOrNot(m: FloatArray): Answer {
    first.set(m[2], m[3], m[6], m[7])
    first.sort()
    second.set(m[8], m[9], m[12], m[13])
    second.sort()
    return when {
      first.contains(second) -> {
        outer.set(first)
        inner.set(second)
        Answer.Nested
      }
      second.contains(first) -> {
        outer.set(second)
        inner.set(first)
        Answer.Nested
      }
      else -> Answer.NotNested
    }
  }

  /** Sets [path] to the frame moved by the last [test], as move, three lines and close per contour. */
  fun setMovedPath(path: android.graphics.Path, fillType: android.graphics.Path.FillType) {
    val m = moved
    path.rewind()
    path.addContour(m, 0)
    path.addContour(m, 8)
    path.fillType = fillType
  }

  /** `ObvidNative.builderPlanRects` for [outer] and [inner] blurred with [radius]. */
  fun plan(radius: Float): Long =
    ObvidNative.builderPlanRects(outer.left, outer.top, outer.right, outer.bottom, inner.left, inner.top, inner.right, inner.bottom, radius)
}

/** `addRect(l, t, r, b, CW)`'s contour, written as move, three lines and close. */
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
