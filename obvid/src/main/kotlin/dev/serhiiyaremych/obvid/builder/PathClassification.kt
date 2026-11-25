// Copyright 2026, Serhii Yaremych (desugar-64) and the Obvid project contributors
// SPDX-License-Identifier: Apache-2.0

package dev.serhiiyaremych.obvid.builder

import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathIterator
import androidx.compose.ui.graphics.PathSegment
import androidx.compose.ui.graphics.asAndroidPath
import kotlin.math.abs

/** The verbs of a canonical frame: two `addRect` contours. */
private val FRAME_VERBS =
  arrayOf(
    PathSegment.Type.Move,
    PathSegment.Type.Line,
    PathSegment.Type.Line,
    PathSegment.Type.Line,
    PathSegment.Type.Close,
    PathSegment.Type.Move,
    PathSegment.Type.Line,
    PathSegment.Type.Line,
    PathSegment.Type.Line,
    PathSegment.Type.Close,
  )

/**
 * Whether [path] is a canonical frame ([ShadowBitmaps.isFrame]); its 8 points go to [out]. [points] is the scratch for
 * one segment (8 floats).
 */
internal fun readFrame(path: Path, points: FloatArray, out: FloatArray): Boolean {
  if (path.asAndroidPath().isInverseFillType) return false
  val iterator = path.iterator(PathIterator.ConicEvaluation.AsConic, 0.25f)
  var verb = 0
  var n = 0
  var matches = true
  while (matches && iterator.hasNext()) {
    val type = iterator.next(points, 0)
    if (type == PathSegment.Type.Done) break
    when {
      verb == FRAME_VERBS.size || type != FRAME_VERBS[verb++] -> matches = false
      type == PathSegment.Type.Move -> {
        out[2 * n] = points[0]
        out[2 * n + 1] = points[1]
        n++
      }
      type == PathSegment.Type.Line -> {
        // The segment's start is the previous point; its end is the next one.
        out[2 * n] = points[2]
        out[2 * n + 1] = points[3]
        n++
      }
    }
  }
  return matches && verb == FRAME_VERBS.size
}

/**
 * Whether Skia may blur [path] as nested rects: only lines, every edge axis-aligned within [AXIS_TOLERANCE], and
 * exactly two contours. [points] is the scratch for one segment (8 floats).
 */
internal fun mayBeNestedRects(path: Path, points: FloatArray): Boolean {
  val iterator = path.iterator(PathIterator.ConicEvaluation.AsConic, 0.25f)
  var contours = 0
  var hasEdge = false
  var startX = 0f
  var startY = 0f
  var x = 0f
  var y = 0f
  var ok = true
  while (ok && iterator.hasNext()) {
    when (iterator.next(points, 0)) {
      PathSegment.Type.Move -> {
        if (hasEdge && !axisAligned(x, y, startX, startY)) {
          ok = false
        } else {
          hasEdge = false
          startX = points[0]
          startY = points[1]
          x = startX
          y = startY
        }
      }
      PathSegment.Type.Line -> {
        // The segment's start, then its end.
        if (!axisAligned(points[0], points[1], points[2], points[3])) {
          ok = false
        } else if (!hasEdge) {
          hasEdge = true
          ok = ++contours <= 2
        }
        x = points[2]
        y = points[3]
      }
      PathSegment.Type.Close -> {
        if (!axisAligned(x, y, startX, startY)) {
          ok = false
        } else {
          // A segment after a close without a move starts a new contour at this contour's start.
          hasEdge = false
          x = startX
          y = startY
        }
      }
      PathSegment.Type.Done -> break
      else -> ok = false
    }
  }
  return ok && contours == 2 && !(hasEdge && !axisAligned(x, y, startX, startY))
}

/**
 * Whether [stroke], the spread stroke, may be one rect or two nested rects: only lines, every line axis-aligned within
 * [AXIS_TOLERANCE], at most two contours and at most five direction runs per contour. [points] is the scratch for one
 * segment (8 floats).
 */
internal fun mayBeStrokeRects(stroke: Path, points: FloatArray): Boolean {
  val iterator = stroke.iterator(PathIterator.ConicEvaluation.AsConic, 0.25f)
  var contours = 0
  // The current contour's direction runs and the last run's direction (0 +x, 1 +y, 2 −x, 3 −y; −1: none yet).
  var runs = 0
  var direction = -1
  var ok = true
  while (ok && iterator.hasNext()) {
    when (iterator.next(points, 0)) {
      PathSegment.Type.Move,
      PathSegment.Type.Close -> {
        runs = 0
        direction = -1
      }
      PathSegment.Type.Line -> {
        // The segment's start, then its end.
        val dx = points[2] - points[0]
        val dy = points[3] - points[1]
        if (!axisAligned(points[0], points[1], points[2], points[3])) {
          ok = false
        } else if (!nearPoint(points[0], points[1], points[2], points[3])) {
          val d = if (abs(dx) >= abs(dy)) (if (dx > 0f) 0 else 2) else (if (dy > 0f) 1 else 3)
          if (d != direction) {
            if ((runs == 0 && ++contours > 2) || ++runs > 5) ok = false else direction = d
          }
        }
      }
      PathSegment.Type.Done -> break
      else -> ok = false
    }
  }
  return ok
}

/** Whether Skia draws the drop shadow's `2·spread` stroke as a hairline: a width ≤ 1 px. */
internal fun hairlineSpread(spread: Float): Boolean = spread > 0f && spread * 2f <= 1f

/** The segment from ([x0], [y0]) to ([x1], [y1]) is shorter than [AXIS_TOLERANCE] on both axes. */
private fun nearPoint(x0: Float, y0: Float, x1: Float, y1: Float): Boolean =
  abs(x1 - x0) <= AXIS_TOLERANCE && abs(y1 - y0) <= AXIS_TOLERANCE

/** The segment from ([x0], [y0]) to ([x1], [y1]) is vertical or horizontal within [AXIS_TOLERANCE]. */
private fun axisAligned(x0: Float, y0: Float, x1: Float, y1: Float): Boolean =
  abs(x1 - x0) <= AXIS_TOLERANCE || abs(y1 - y0) <= AXIS_TOLERANCE

private const val AXIS_TOLERANCE = 1f / 64f
