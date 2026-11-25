// Copyright 2026, Serhii Yaremych (desugar-64) and the Obvid project contributors
// SPDX-License-Identifier: Apache-2.0

package dev.serhiiyaremych.obvid.builder

import android.graphics.Bitmap
import kotlin.math.max

/** The reused A8 bitmap a route draws its mask into before the native blur, with its canvas and paint. */
internal class MaskBitmap {
  private var bitmap: Bitmap? = null

  val canvas = android.graphics.Canvas()

  val paint =
    android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
      color = android.graphics.Color.BLACK
      style = android.graphics.Paint.Style.FILL
    }

  /** The mask is read as a window with its own stride: it grows per axis, never reconfigured. */
  fun ensure(w: Int, h: Int): Bitmap {
    val current = bitmap
    if (current != null && current.width >= w && current.height >= h) return current
    val next = newA8(max(w, current?.width ?: 0), max(h, current?.height ?: 0))
    canvas.setBitmap(next)
    // setBitmap takes the bitmap's density; with none, drawBitmap never scales by density.
    canvas.density = Bitmap.DENSITY_NONE
    current?.recycle()
    bitmap = next
    return next
  }

  fun close() {
    canvas.setBitmap(null)
    bitmap?.recycle()
    bitmap = null
  }
}
