// Copyright 2026, Serhii Yaremych (desugar-64) and the Obvid project contributors
// SPDX-License-Identifier: Apache-2.0

package dev.serhiiyaremych.obvid.builder

import android.graphics.Bitmap
import android.graphics.PorterDuff
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
 * `createInnerPathShadowBrush` with spread > 0 (radius > 0) on Obvid's pipeline. Compose draws the intermediate
 * ([SkiaRoute.buildIntermediate], already rasterized) with `drawImage(…, offset, blur + Xor)` over the opaque bitmap;
 * Skia's `drawImage` of an A8 image with a mask filter is `drawBitmapAsMask` (the image's alpha is the blur source), so
 * B is the blur of the mask Skia forms from the intermediate at the offset, by the newest Skia's rule on every API
 * level:
 * - clipped out: the intermediate's rounded-out device rect misses the bitmap; B is empty;
 * - sprite ([treatAsSprite]): the intermediate itself at (round(ox), round(oy)), not clipped before the blur;
 * - filtered: Skia draws the intermediate at the offset, bilinear, into a temporary mask of exactly the size of the
 *   rounded mapped bounds clipped to the bitmap (the cut rect), translated by minus its top-left. The platform's
 *   `drawBitmap` with that paint draws it here the same way: into a reused A8 buffer reconfigured to exactly the cut
 *   rect's size ([ensurePlaced]), cleared, under `translate(−l, −t)`, at the offset; the blur reads that buffer with
 *   its origin at the cut rect's top-left. Drawing at device coordinates into a larger bitmap clipped to the cut rect
 *   instead can differ by 1 (seen in a one-pixel-wide cut rect).
 *
 * Skia's `drawImage` is a `drawImageRect` into `(ox, oy, ox + iw, oy + ih)`: when the float sum `ox + iw` minus `ox` is
 * not `iw` (or the same in y), its rect-to-rect matrix scales, and Skia fills that rect with the intermediate as a
 * shader under the mask filter (the blurred rect times the intermediate), not `drawBitmapAsMask`. That draw is left to
 * the Skia route ([fits]).
 */
internal class InnerPathSpread(private val native: NativeBuilder) {
  // Skia's paint for the filtered `drawImage` of the intermediate, and the buffer of exactly the cut rect's size it is
  // drawn into (Skia's temporary mask).
  private val imagePaint =
    android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG or android.graphics.Paint.FILTER_BITMAP_FLAG)
  private var placed: Bitmap? = null
  private val placedCanvas = android.graphics.Canvas()

  /** Whether `drawBitmapAsMask` draws [image] at the offset ([offsetX], [offsetY]): no scale in the rect-to-rect matrix. */
  fun fits(image: Bitmap, offsetX: Float, offsetY: Float): Boolean =
    (offsetX + image.width) - offsetX == image.width.toFloat() && (offsetY + image.height) - offsetY == image.height.toFloat()

  /** Writes B of [image] at the offset ([offsetX], [offsetY]) into [target] (replace mode: the rest is zeroed). */
  fun build(image: Bitmap, target: Bitmap, w: Int, h: Int, radius: Float, offsetX: Float, offsetY: Float) {
    val iw = image.width
    val ih = image.height
    // The intermediate's device rect: SkMatrix::mapRect of the translate.
    val left = offsetX
    val top = offsetY
    val right = offsetX + iw
    val bottom = offsetY + ih
    val clippedOut = !(floor(left) < w && ceil(right) > 0 && floor(top) < h && ceil(bottom) > 0)
    when {
      clippedOut -> blurInto(image, 0, 0, 0, 0, 0, 0, target, w, h, radius)
      treatAsSprite(left, top, right, bottom, iw, ih) ->
        blurInto(image, 0, 0, iw, ih, skRound(left), skRound(top), target, w, h, radius)
      else -> buildFiltered(image, target, w, h, radius, offsetX, offsetY, left, top, right, bottom)
    }
  }

  /** The filtered case of [build]: the cut rect of ([left], [top], [right], [bottom]) placed, then blurred. */
  private fun buildFiltered(
    image: Bitmap,
    target: Bitmap,
    w: Int,
    h: Int,
    radius: Float,
    offsetX: Float,
    offsetY: Float,
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
        placedCanvas.drawBitmap(image, offsetX, offsetY, imagePaint)
        placedCanvas.restore()
      }
      blurInto(placedBitmap, 0, 0, mw, mh, l, t, target, w, h, radius)
    }
  }

  /**
   * The placement buffer of exactly [w] × [h], bound to its canvas: reconfigured inside its grow-only allocation, or a
   * new one (the canvas is rebound after either: a reconfigure changes the bitmap's dimensions).
   */
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

  /**
   * Blurs the [mw] × [mh] window at ([srcX], [srcY]) of [source] (its origin at ([x], [y]) in [target]) into [target],
   * replace mode (the rest of the bitmap, exactly the used size, is zeroed).
   */
  private fun blurInto(source: Bitmap, srcX: Int, srcY: Int, mw: Int, mh: Int, x: Int, y: Int, target: Bitmap, w: Int, h: Int, radius: Float) {
    val status =
      traceSection(TRACE_BLUR) {
        ObvidNative.builderBlurFrom(native.handle, source, srcX, srcY, mw, mh, x, y, radius, target, w, h, w, h, MODE_REPLACE)
      }
    check(status == 0) { "builderBlurFrom failed: $status" }
  }

  /**
   * `SkTreatAsSprite` for the translate that maps the [iw] × [ih] image to ([left], [top], [right], [bottom]) with
   * Compose's paint (anti-aliased, linear filtering), as the newest Skia decides it: no fractional translate (linear
   * filtering refuses any since Android 15, b/41322892), and the mapped bounds at 4 subpixel bits round to the integer
   * bounds moved by the rounded translate.
   */
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
