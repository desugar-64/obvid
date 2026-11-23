// Copyright 2026, Serhii Yaremych (desugar-64) and the Obvid project contributors
// SPDX-License-Identifier: Apache-2.0

package dev.serhiiyaremych.obvid

import android.graphics.Bitmap
import android.graphics.RectF
import dev.serhiiyaremych.obvid.fixtures.alphaOf
import dev.serhiiyaremych.obvid.fixtures.maskBounds
import dev.serhiiyaremych.obvid.fixtures.maskPaint
import java.nio.ByteBuffer
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.min

// Skia's CPU mask-filter pipeline as the exactness tests model it: an unblurred A8 mask drawn by the platform
// Canvas, blurred by the JNI port, blitted by Android's own A8 drawBitmap; and which route Skia takes.

/** A blurred mask and its device position (Skia's dstM.fBounds): scalar port and NEON blur of the same mask. */
internal class Blurred(val x: Int, val y: Int, val w: Int, val h: Int, val pixels: ByteArray, val neonPixels: ByteArray)

private val plainPaint = maskPaint()

/**
 * Blurs the tightly packed A8 [src] of [w] × [h] whose origin is ([x], [y]) on the device, by the scalar port and by
 * NEON; the result sits at (origin − [border]) and is [border] larger on each side.
 */
internal fun blurMask(src: ByteArray, x: Int, y: Int, w: Int, h: Int, radius: Float, border: Int): Blurred {
  val dst = ObvidTesting.blurA8(src, w, h, radius, neon = false)
  val neon = ObvidTesting.blurA8(src, w, h, radius, neon = true)
  val bw = w + 2 * border
  val bh = h + 2 * border
  check(dst.size == bw * bh) { "blur returned ${dst.size} bytes, expected ${bw * bh}" }
  check(neon.size == bw * bh) { "NEON blur returned ${neon.size} bytes, expected ${bw * bh}" }
  return Blurred(x - border, y - border, bw, bh, dst, neon)
}

/** Draws [draw] into an A8 mask at [mb] (CTM = translate(−left, −top), as `draw_into_mask`) and blurs it. */
internal fun blurInto(mb: IntArray, radius: Float, border: Int, draw: (android.graphics.Canvas) -> Unit): Blurred {
  val mw = mb[2] - mb[0]
  val mh = mb[3] - mb[1]
  val mask = Bitmap.createBitmap(mw, mh, Bitmap.Config.ALPHA_8)
  android.graphics.Canvas(mask).apply {
    translate(-mb[0].toFloat(), -mb[1].toFloat())
    draw(this)
  }
  val src = alphaOf(mask).let { px -> ByteArray(px.size) { px[it].toByte() } }
  mask.recycle()
  return blurMask(src, mb[0], mb[1], mw, mh, radius, border)
}

/** The full-mask route of a device-space path on a [w] × [h] device: mask bounds by `compute_mask_bounds`, then blur. */
internal fun blurPath(devPath: android.graphics.Path, w: Int, h: Int, radius: Float, border: Int): Blurred {
  val b = RectF().also { devPath.computeBounds(it, true) }
  val mb = maskBounds(b, w, h, border) ?: error("empty mask")
  return blurInto(mb, radius, border) { it.drawPath(devPath, plainPaint) }
}

/**
 * The round-rect outline as Compose draws it (`drawRoundRect`; a zero radius becomes `drawRect` in
 * `SkCanvas::drawRoundRect`), but as a path for a plain rect, because Skia's fallback from the rect nine-patch
 * draws `SkPath::Rect` into the mask (`draw_rect_as_path`).
 */
internal fun blurRoundRect(
  rect: RectF,
  rx: Float,
  ry: Float,
  w: Int,
  h: Int,
  radius: Float,
  border: Int,
  tight: Boolean = false,
): Blurred {
  // tight: the mask is exactly roundOut(rect), as the nine-patch's prepare_to_draw_into_mask makes it (no ½
  // outset), so anti-aliasing that spills past the rect's integer edges is clipped as in Skia's small mask.
  val mb =
    if (tight) intArrayOf(floor(rect.left).toInt(), floor(rect.top).toInt(), ceil(rect.right).toInt(), ceil(rect.bottom).toInt())
    else maskBounds(rect, w, h, border) ?: error("empty mask")
  return blurInto(mb, radius, border) { canvas ->
    if (rx > 0f && ry > 0f) canvas.drawRoundRect(rect, rx, ry, plainPaint)
    else
      canvas.drawPath(
        android.graphics.Path().apply { addRect(rect, android.graphics.Path.Direction.CW) },
        plainPaint,
      )
  }
}

/** Android's own blit: each blurred mask as an A8 bitmap drawn with a default paint (black, srcover). */
internal fun compositeAndroid(masks: List<Blurred>, w: Int, h: Int, neon: Boolean = false): IntArray {
  val target = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8)
  val canvas = android.graphics.Canvas(target)
  for (m in masks) {
    val bmp = Bitmap.createBitmap(m.w, m.h, Bitmap.Config.ALPHA_8)
    bmp.density = target.density
    val rb = bmp.rowBytes
    val buf = ByteArray(rb * m.h)
    val pixels = if (neon) m.neonPixels else m.pixels
    for (y in 0 until m.h) System.arraycopy(pixels, y * m.w, buf, y * rb, m.w)
    bmp.copyPixelsFromBuffer(ByteBuffer.wrap(buf))
    canvas.drawBitmap(bmp, m.x.toFloat(), m.y.toFloat(), null)
    bmp.recycle()
  }
  return alphaOf(target).also { target.recycle() }
}

// -----------------------------------------------------------------------------------------------------------------
// Which route Skia's CPU path takes for a blurred drawRoundRect (SkDraw::drawRRect → nine-patch or path)
// -----------------------------------------------------------------------------------------------------------------

/**
 * `nine(rrect)` (the nine-patch), `mask(rrect)` (its full-mask fallback), `mask(oval)` (an rrect that `setRectXY`
 * makes an oval), or for a zero radius [predictRectRoute]; the nine-patch size test is Android 16–17's (integer
 * pieces).
 */
internal fun predictRoundRectRoute(rect: RectF, rx0: Float, ry0: Float, radius: Float, margin: Int): String {
  val width = rect.right - rect.left
  val height = rect.bottom - rect.top
  if (!(rx0 > 0f && ry0 > 0f)) return predictRectRoute(rect, radius)
  // SkRRect::setRectXY
  var rx = rx0
  var ry = ry0
  if (width < rx + rx || height < ry + ry) {
    val scale = min(width / (rx + rx), height / (ry + ry))
    rx *= scale
    ry *= scale
  }
  if (rx >= width / 2 && ry >= height / 2) return "mask(oval)"
  // Skia main / android17 SkBlurMaskFilterImpl::filterRRectToNine: integer pieces.
  val tw = (ceil(rx).toInt() + margin) * 2 + 1
  val th = (ceil(ry).toInt() + margin) * 2 + 1
  return if (tw < width && th < height) "nine(rrect)" else "mask(rrect)"
}

/** SkBlurMaskFilterImpl::filterRectsToNine (one rect) with SkBlurMask::BlurRect's bounds. */
internal fun predictRectRoute(rect: RectF, radius: Float): String {
  val sigma = min(0.57735f * radius + 0.5f, 128f)
  val profileSize = ceil(6 * sigma).toInt()
  val pad = profileSize / 2
  val srcL = floor(rect.left).toInt()
  val srcT = floor(rect.top).toInt()
  val srcR = ceil(rect.right).toInt()
  val srcB = ceil(rect.bottom).toInt()
  val dstW = roundToInt(rect.right + pad) - roundToInt(rect.left - pad)
  val dstH = roundToInt(rect.bottom + pad) - roundToInt(rect.top - pad)
  val smallW = dstW - (srcR - srcL) + 2 + 1
  val smallH = dstH - (srcB - srcT) + 2 + 1
  val dx = (srcR - srcL) - smallW
  val dy = (srcB - srcT) - smallH
  if (dx < 0 || dy < 0) return "mask(rect)"
  if ((rect.right - dx) - rect.left < 2 || (rect.bottom - dy) - rect.top < 2) return "mask(rect)"
  return "nine(rect:BlurRect)"
}

/** SkScalarRoundToInt: floor(x + 0.5). */
private fun roundToInt(x: Float) = floor(x + 0.5f).toInt()
