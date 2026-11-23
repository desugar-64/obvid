// Copyright 2026, Serhii Yaremych (desugar-64) and the Obvid project contributors
// SPDX-License-Identifier: Apache-2.0

package dev.serhiiyaremych.obvid.fixtures

import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * A case's geometry in px as a layout gives it: an integer-px square layout box ([sizePx] = `roundToPx`), radius and
 * spread as `Dp.toPx()` floats, as in `DropShadowRenderer.buildShadow`.
 */
class CaseGeometry(density: Density, sizeDp: Int, radiusDp: Int, spreadDp: Int) {
  val sizePx: Float = with(density) { sizeDp.dp.roundToPx() }.toFloat()
  val size = Size(sizePx, sizePx)
  val radius: Float = with(density) { radiusDp.dp.toPx() }
  val spread: Float = with(density) { spreadDp.dp.toPx() }
}

/** Compose's `Paint()` as Android's: anti-aliased, black, src-over, no mask filter; draws the unblurred masks. */
fun maskPaint(style: Paint.Style = Paint.Style.FILL): Paint =
  Paint(Paint.ANTI_ALIAS_FLAG).apply {
    color = android.graphics.Color.BLACK
    this.style = style
  }

/**
 * Compose's second draw with spread, as Skia rasterizes it: [local] stroked 2·[spread] wide (Skia strokes in local
 * space, `FillPathWithPaint`, resScale 1 for a translate), then moved by the CTM's translate ([t], [t]).
 */
fun strokeFillPath(local: Path, spread: Float, t: Float): Path {
  val fill = Path()
  @Suppress("DEPRECATION") maskPaint(Paint.Style.STROKE).apply { strokeWidth = spread * 2f }.getFillPath(local, fill)
  fill.offset(t, t)
  return fill
}

/**
 * `compute_mask_bounds` (SkDraw.cpp, same in Android 14, 17 and Skia main): the device path bounds [b] outset by ½,
 * rounded out, intersected with the clip (the [w] × [h] bitmap) grown by min(margin, 128). Null when empty.
 */
fun maskBounds(b: RectF, w: Int, h: Int, border: Int): IntArray? {
  val m = min(border, 128)
  val l = max(floor(b.left - 0.5f).toInt(), -m)
  val t = max(floor(b.top - 0.5f).toInt(), -m)
  val r = min(ceil(b.right + 0.5f).toInt(), w + m)
  val bt = min(ceil(b.bottom + 0.5f).toInt(), h + m)
  return if (l < r && t < bt) intArrayOf(l, t, r, bt) else null
}

/** Alpha of every pixel of the top-left [w] × [h] of [bitmap] (A8 or ARGB_8888), row-major. */
fun alphaOf(bitmap: Bitmap, w: Int = bitmap.width, h: Int = bitmap.height): IntArray {
  val px = IntArray(w * h)
  bitmap.getPixels(px, 0, w, 0, 0, w, h)
  for (i in px.indices) px[i] = px[i] ushr 24
  return px
}
