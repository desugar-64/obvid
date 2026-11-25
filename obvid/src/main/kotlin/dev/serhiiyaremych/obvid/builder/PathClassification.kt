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
 * Whether [path] is a canonical frame ([ShadowBitmaps.isFrame]): not an inverse fill, and Compose's iterator reports
 * exactly [FRAME_VERBS] (then done). The move's point and each line's end go to [out] (16 floats: 8 points); [points]
 * is the scratch for one segment (8 floats).
 */
internal fun readFrame(path: Path, points: FloatArray, out: FloatArray): Boolean {
  if (path.asAndroidPath().isInverseFillType) return false
  val iterator = path.iterator(PathIterator.ConicEvaluation.AsConic, 0.25f)
  var verb = 0
  var n = 0
  while (iterator.hasNext()) {
    val type = iterator.next(points, 0)
    if (type == PathSegment.Type.Done) break
    if (verb == FRAME_VERBS.size || type != FRAME_VERBS[verb++]) return false
    when (type) {
      PathSegment.Type.Move -> {
        out[2 * n] = points[0]
        out[2 * n + 1] = points[1]
        n++
      }
      PathSegment.Type.Line -> {
        // The segment's start is the previous point; its end is the next one.
        out[2 * n] = points[2]
        out[2 * n + 1] = points[3]
        n++
      }
      else -> Unit
    }
  }
  return verb == FRAME_VERBS.size
}

/**
 * Whether Skia may blur [path] as nested rects (`SkPathPriv::IsNestedFillRects`, then `filterRectsToNine`), by a
 * superset of Skia's test: only move, line and close verbs, every edge axis-aligned (x or y equal within
 * [AXIS_TOLERANCE]), and exactly two contours with an edge. A fill closes every contour, with or without a close verb,
 * so the edge back to a contour's start counts as an edge. A path this lets through that Skia does not blur as nested
 * rects only takes the slower Skia route. Skia tests the path moved by Compose's translate, whose float sums keep an
 * equal x or y equal but can also round two nearby ones to one value; the tolerance covers those, so the answer holds
 * for the moved path. [points] is the scratch for one segment (8 floats).
 */
internal fun mayBeNestedRects(path: Path, points: FloatArray): Boolean {
  val iterator = path.iterator(PathIterator.ConicEvaluation.AsConic, 0.25f)
  var contours = 0
  var hasEdge = false
  var startX = 0f
  var startY = 0f
  var x = 0f
  var y = 0f
  while (iterator.hasNext()) {
    when (iterator.next(points, 0)) {
      PathSegment.Type.Move -> {
        if (hasEdge && !axisAligned(x, y, startX, startY)) return false
        hasEdge = false
        startX = points[0]
        startY = points[1]
        x = startX
        y = startY
      }
      PathSegment.Type.Line -> {
        // The segment's start, then its end.
        if (!axisAligned(points[0], points[1], points[2], points[3])) return false
        if (!hasEdge) {
          hasEdge = true
          if (++contours > 2) return false
        }
        x = points[2]
        y = points[3]
      }
      PathSegment.Type.Close -> {
        if (!axisAligned(x, y, startX, startY)) return false
        // A segment after a close without a move starts a new contour at this contour's start.
        hasEdge = false
        x = startX
        y = startY
      }
      PathSegment.Type.Done -> break
      else -> return false
    }
  }
  return contours == 2 && !(hasEdge && !axisAligned(x, y, startX, startY))
}

/**
 * Whether [stroke] (Compose's spread stroke in local space, as the platform's stroker, which is Skia's, made it) may be
 * one rect or two nested rects for Skia's `countNestedRects` on it moved by the draw's translate: `isRect`, or
 * `IsNestedFillRects` (two rect contours, one containing the other, in any direction). A superset of both: no curve
 * verb (they reject curves), every line axis-aligned within [AXIS_TOLERANCE], at most two contours with a line
 * longer than [AXIS_TOLERANCE] (Skia's rect test skips zero-length lines, so a contour whose lines are all within the
 * tolerance, which the translate may collapse, does not count)
 * (a translate below Skia's 32767 rect limit rounds a coordinate by at most half an ulp, ≤ 1/512 px, so it can only
 * make an edge within the tolerance exact), and at most five direction runs per contour over those longer lines (a rect
 * has four edges, five runs when it starts inside one; skipping the short lines only merges or drops runs). Collinear
 * points on an edge pass, as Skia's rect test allows them, and the closing segments are not tested (which only widens
 * the superset). An open polyline's stroke, such as an L's six-sided outline, has more runs. A stroke this lets through
 * that Skia does not blur as rects only takes the slower Skia route. Stops at the first curve verb, where a curved
 * shape's stroke fails within its first corner. [points] is the scratch for one segment (8 floats). Allocates the
 * iterator.
 */
internal fun mayBeStrokeRects(stroke: Path, points: FloatArray): Boolean {
  val iterator = stroke.iterator(PathIterator.ConicEvaluation.AsConic, 0.25f)
  var contours = 0
  // The current contour's direction runs and the last run's direction (0 +x, 1 +y, 2 −x, 3 −y; −1: none yet).
  var runs = 0
  var direction = -1
  while (iterator.hasNext()) {
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
        if (!axisAligned(points[0], points[1], points[2], points[3])) return false
        if (!nearPoint(points[0], points[1], points[2], points[3])) {
          val d = if (abs(dx) >= abs(dy)) (if (dx > 0f) 0 else 2) else (if (dy > 0f) 1 else 3)
          if (d != direction) {
            if (runs == 0 && ++contours > 2) return false
            if (++runs > 5) return false
            direction = d
          }
        }
      }
      PathSegment.Type.Done -> break
      else -> return false
    }
  }
  return true
}

/**
 * Whether Skia draws the drop shadow's spread stroke (`2·spread` wide, anti-aliased) as a hairline: under a translate
 * `DrawTreatAAStrokeAsHairline` measures the width itself, and takes a width ≤ 1 px.
 */
internal fun hairlineSpread(spread: Float): Boolean = spread > 0f && spread * 2f <= 1f

/** The segment from ([x0], [y0]) to ([x1], [y1]) is shorter than [AXIS_TOLERANCE] on both axes. */
private fun nearPoint(x0: Float, y0: Float, x1: Float, y1: Float): Boolean =
  abs(x1 - x0) <= AXIS_TOLERANCE && abs(y1 - y0) <= AXIS_TOLERANCE

/** The segment from ([x0], [y0]) to ([x1], [y1]) is vertical or horizontal within [AXIS_TOLERANCE]. */
private fun axisAligned(x0: Float, y0: Float, x1: Float, y1: Float): Boolean =
  abs(x1 - x0) <= AXIS_TOLERANCE || abs(y1 - y0) <= AXIS_TOLERANCE

// The superset must also cover paths that only become axis-aligned after Compose's translate rounds; any looser path
// only takes the Skia route.
private const val AXIS_TOLERANCE = 1f / 64f
