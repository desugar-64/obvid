// Copyright 2026, Serhii Yaremych (desugar-64) and the Obvid project contributors
// SPDX-License-Identifier: Apache-2.0

package dev.serhiiyaremych.obvid.builder

import android.graphics.Bitmap
import android.graphics.PorterDuff
import androidx.compose.ui.geometry.Offset
import dev.serhiiyaremych.obvid.ObvidNative
import dev.serhiiyaremych.obvid.TRACE_BLUR
import dev.serhiiyaremych.obvid.TRACE_OUTLINE
import dev.serhiiyaremych.obvid.builder.NativeBuilder.Companion.MODE_REPLACE
import dev.serhiiyaremych.obvid.traceSection
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * The inner shadow of a path with spread > 0: blurs the spread intermediate ([SkiaRoute.buildIntermediate]) placed at
 * the offset. The intermediate is clipped out, used as is (a sprite), or drawn filtered into a buffer of the cut rect's
 * size first.
 */
internal class InnerPathSpread(private val native: NativeBuilder) {
  // The paint of the filtered draw and the buffer it draws into.
  private val imagePaint =
    android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG or android.graphics.Paint.FILTER_BITMAP_FLAG)
  private var placed: Bitmap? = null
  private val placedCanvas = android.graphics.Canvas()

  /** Whether this route can draw [image] at [offset]; false leaves it to the Skia route. */
  fun fits(image: Bitmap, offset: Offset): Boolean =
    (offset.x + image.width) - offset.x == image.width.toFloat() &&
      (offset.y + image.height) - offset.y == image.height.toFloat()

  /** Writes B of [image] at [offset] into [target] (replace mode: the rest is zeroed). */
  fun build(image: Bitmap, target: Bitmap, w: Int, h: Int, radius: Float, offset: Offset) {
    val iw = image.width
    val ih = image.height
    // The intermediate's device rect: SkMatrix::mapRect of the translate.
    val left = offset.x
    val top = offset.y
    val right = offset.x + iw
    val bottom = offset.y + ih
    val clippedOut = !(floor(left) < w && ceil(right) > 0 && floor(top) < h && ceil(bottom) > 0)
    when {
      clippedOut -> blurInto(image, 0, 0, 0, 0, 0, 0, target, w, h, radius)
      treatAsSprite(left, top, right, bottom, iw, ih) ->
        blurInto(image, 0, 0, iw, ih, skRound(left), skRound(top), target, w, h, radius)
      else -> buildFiltered(image, target, w, h, radius, left, top, right, bottom)
    }
  }

  /** The filtered case of [build]: the cut rect of ([left], [top], [right], [bottom]) placed, then blurred. */
  private fun buildFiltered(
    image: Bitmap,
    target: Bitmap,
    w: Int,
    h: Int,
    radius: Float,
    left: Float,
    top: Float,
    right: Float,
    bottom: Float,
  ) {
    val l = max(skRound(left), 0)
    val t = max(skRound(top), 0)
    val r = min(skRound(right), w)
    val b = min(skRound(bottom), h)
    if (l >= r || t >= b) {
      blurInto(image, 0, 0, 0, 0, 0, 0, target, w, h, radius)
    } else {
      val mw = r - l
      val mh = b - t
      val placedBitmap = ensurePlaced(mw, mh)
      traceSection(TRACE_OUTLINE) {
        placedCanvas.drawColor(0, PorterDuff.Mode.CLEAR)
        placedCanvas.save()
        placedCanvas.translate(-l.toFloat(), -t.toFloat())
        placedCanvas.drawBitmap(image, left, top, imagePaint)
        placedCanvas.restore()
      }
      blurInto(placedBitmap, 0, 0, mw, mh, l, t, target, w, h, radius)
    }
  }

  /** The placement buffer of exactly [w] × [h], bound to its canvas. */
  private fun ensurePlaced(w: Int, h: Int): Bitmap {
    val current = placed
    if (current != null && current.width == w && current.height == h) return current
    val next = if (current != null && current.allocationByteCount >= w * h) current else newA8(w, h)
    if (next.width != w || next.height != h) next.reconfigure(w, h, Bitmap.Config.ALPHA_8)
    placedCanvas.setBitmap(next)
    // setBitmap takes the bitmap's density; with none, drawBitmap never scales by density.
    placedCanvas.density = Bitmap.DENSITY_NONE
    if (next !== current) {
      current?.recycle()
      placed = next
    }
    return next
  }

  /** Blurs the [mw] × [mh] window at ([srcX], [srcY]) of [source], placed at ([x], [y]), into [target] (replace). */
  private fun blurInto(source: Bitmap, srcX: Int, srcY: Int, mw: Int, mh: Int, x: Int, y: Int, target: Bitmap, w: Int, h: Int, radius: Float) {
    val status =
      traceSection(TRACE_BLUR) {
        ObvidNative.builderBlurFrom(native.handle, source, srcX, srcY, mw, mh, x, y, radius, target, w, h, w, h, MODE_REPLACE)
      }
    check(status == 0) { "builderBlurFrom failed: $status" }
  }

  /** Skia's `SkTreatAsSprite` for the [iw] × [ih] image mapped to ([left], [top], [right], [bottom]). */
  private fun treatAsSprite(left: Float, top: Float, right: Float, bottom: Float, iw: Int, ih: Int): Boolean {
    if (left != left.toInt().toFloat() || top != top.toInt().toFloat()) return false
    val ix = skRound(left)
    val iy = skRound(top)
    return (ix shl 4) == skRound(left * 16f) &&
      (iy shl 4) == skRound(top * 16f) &&
      ((ix + iw) shl 4) == skRound(right * 16f) &&
      ((iy + ih) shl 4) == skRound(bottom * 16f)
  }

  /** `SkScalarRoundToInt`: floor(x + 0.5) in double. */
  private fun skRound(x: Float): Int = floor(x.toDouble() + 0.5).toInt()

  fun close() {
    placedCanvas.setBitmap(null)
    placed?.recycle()
    placed = null
  }
}
