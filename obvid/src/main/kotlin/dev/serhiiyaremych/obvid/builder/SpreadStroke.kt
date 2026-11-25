// Copyright 2026, Serhii Yaremych (desugar-64) and the Obvid project contributors
// SPDX-License-Identifier: Apache-2.0

package dev.serhiiyaremych.obvid.builder

import android.graphics.RectF
import androidx.compose.ui.graphics.asComposePath

/** The drop shadow's spread stroke: the path stroked `2·spread` wide in local space, built once per build. */
internal class SpreadStroke {
  private val paint =
    android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
      style = android.graphics.Paint.Style.STROKE
    }

  /** The stroke's outline, filled. */
  val path = android.graphics.Path()

  /** [path] as a Compose path, for [ShadowBitmaps.strokeMayBeRects]' iterator. */
  val composePath = path.asComposePath()

  private val bounds = RectF()

  // [path] holds this build's stroke.
  private var ready = false

  /** Starts a build: the next [build] strokes again. */
  fun reset() {
    ready = false
  }

  /** [path] := Compose's stroke of [local] (`2·spread` wide) in local space, once per build. */
  fun build(local: android.graphics.Path, spread: Float) {
    if (ready) return
    paint.strokeWidth = spread * 2f
    path.rewind()
    @Suppress("DEPRECATION") paint.getFillPath(local, path)
    ready = true
  }

  /** The local bounds of Compose's path draw into [out]: the path's, with its spread stroke's for spread > 0. */
  fun drawBounds(local: android.graphics.Path, spread: Float, out: RectF) {
    @Suppress("DEPRECATION") local.computeBounds(out, true)
    if (hairlineSpread(spread)) {
      out.inset(-1f, -1f)
    } else if (spread > 0f) {
      build(local, spread)
      @Suppress("DEPRECATION") path.computeBounds(bounds, true)
      out.union(bounds)
    }
  }
}
