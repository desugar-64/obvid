// Copyright 2026, Serhii Yaremych (desugar-64) and the Obvid project contributors
// SPDX-License-Identifier: Apache-2.0

package dev.serhiiyaremych.obvid.builder

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.PorterDuff
import android.graphics.RectF
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.PaintingStyle
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.nativePaint
import dev.serhiiyaremych.obvid.TRACE_OUTLINE
import dev.serhiiyaremych.obvid.TRACE_SKIA
import dev.serhiiyaremych.obvid.traceSection
import kotlin.math.ceil
import kotlin.math.max

/**
 * [ShadowBitmapBuilder.Route.Skia]: Compose's own draw calls (`createOuterShadowBitmap`, `InnerShadowRenderer`) with
 * its `Paint` configuration, on a reused canvas bound to the shadow bitmap; also the inner path + spread
 * intermediate, which both inner routes read. [bounds] is the builder's scratch rect.
 */
internal class SkiaRoute(private val bounds: RectF) {
  private val shadowCanvas = android.graphics.Canvas()
  private var canvasBitmap: Bitmap? = null
  private var canvasWidth = 0
  private var canvasHeight = 0
  private val composeCanvas = androidx.compose.ui.graphics.Canvas(shadowCanvas)

  // Compose's paint (`configureShadow`'s color and blend mode never change) and filter.
  private val paint =
    Paint().apply {
      color = Color.Black
      blendMode = BlendMode.SrcOver
    }
  private var blurFilter: BlurMaskFilter? = null
  private var blurFilterRadius = Float.NaN

  // Inner: Compose's renderer `Paint`, reset per build, and the path + spread intermediate.
  private val innerPaint = Paint()
  private var intermediate: Bitmap? = null
  private var intermediateImage: ImageBitmap? = null
  private val intermediateCanvas = android.graphics.Canvas()
  private val intermediateComposeCanvas = androidx.compose.ui.graphics.Canvas(intermediateCanvas)

  /** The intermediate of the last [buildIntermediate]. */
  val intermediateBitmap: Bitmap
    get() = checkNotNull(intermediate)

  /**
   * Compose's draw calls into [target] (the area's size), after the area's translate for the full extent (the canvas
   * translate of a larger canvas).
   */
  fun build(
    target: Bitmap,
    area: DropArea,
    path: Path?,
    cornerRadius: CornerRadius,
    shadowWidth: Float,
    shadowHeight: Float,
    radius: Float,
    spread: Float,
    t: Float,
  ) {
    traceSection(TRACE_SKIA) {
      val filter = if (radius > 0f) blurFilter(radius) else null
      bindCanvas(target)
      shadowCanvas.save()
      shadowCanvas.clipRect(0, 0, area.width, area.height)
      shadowCanvas.drawColor(0, PorterDuff.Mode.CLEAR)
      if (area.full) shadowCanvas.translate(area.x, area.y)
      with(composeCanvas) {
        if (path != null) {
          translate(t, t)
          drawPath(path, paint.configureShadow(filter))
          if (spread > 0f) {
            drawPath(path, paint.configureShadow(filter, PaintingStyle.Stroke).apply { strokeWidth = spread * 2f })
          }
        } else {
          drawRoundRect(
            radius,
            radius,
            shadowWidth - radius,
            shadowHeight - radius,
            cornerRadius.x,
            cornerRadius.y,
            paint.configureShadow(filter),
          )
        }
      }
      shadowCanvas.restore()
    }
  }

  /**
   * `createInnerPathShadowBrush`'s intermediate for a positive [spread]: an A8 bitmap of `ceil(bounds)` of the path
   * (the path at its own coordinates, not moved to the origin), the path filled, then, clipped to the bounds' size,
   * stroked `2·spread` wide with `Clear` (eroded by the spread). Compose's calls with its renderer `Paint`, reset to a
   * fresh one's state first; the bitmap is reused (exactly this size, cleared), so it is read whole as Compose's.
   */
  fun buildIntermediate(path: Path, spread: Float): ImageBitmap {
    // Path.getBounds: the Android path's bounds.
    @Suppress("DEPRECATION") path.asAndroidPath().computeBounds(bounds, true)
    val pathWidth = bounds.width()
    val pathHeight = bounds.height()
    val iw = ceil(pathWidth).toInt()
    val ih = ceil(pathHeight).toInt()
    require(iw > 0 && ih > 0) { "empty path bitmap ${iw}x$ih" }
    val image = ensureIntermediate(iw, ih)
    innerPaint.resetToFresh()
    traceSection(TRACE_OUTLINE) {
      intermediateCanvas.save()
      intermediateCanvas.drawColor(0, PorterDuff.Mode.CLEAR)
      with(intermediateComposeCanvas) {
        drawPath(path, innerPaint)
        clipRect(0f, 0f, pathWidth, pathHeight)
        drawPath(
          path,
          innerPaint.configureInnerShadow(style = PaintingStyle.Stroke, blendMode = BlendMode.Clear).apply {
            strokeWidth = spread * 2f
          },
        )
      }
      intermediateCanvas.restore()
    }
    return image
  }

  /**
   * The intermediate of exactly [w] × [h], bound to its canvas: reconfigured inside its grow-only allocation, or a new
   * one (the canvas is rebound after either: a reconfigure changes the bitmap's dimensions).
   */
  private fun ensureIntermediate(w: Int, h: Int): ImageBitmap {
    val current = intermediate
    if (current != null && current.width == w && current.height == h) return checkNotNull(intermediateImage)
    if (current != null && current.allocationByteCount >= w * h) {
      current.reconfigure(w, h, Bitmap.Config.ALPHA_8)
      intermediateCanvas.setBitmap(current)
      return checkNotNull(intermediateImage)
    }
    val next = newA8(w, h)
    if (next.width != w || next.height != h) next.reconfigure(w, h, Bitmap.Config.ALPHA_8)
    intermediateCanvas.setBitmap(next)
    current?.recycle()
    intermediate = next
    return next.asImageBitmap().also { intermediateImage = it }
  }

  /**
   * `createInnerPathShadowBrush` / `createInnerShadowBrush`'s draw calls on the reused bitmap, cleared first (Compose's
   * is new); [image] is the path + spread intermediate ([buildIntermediate]), or null.
   */
  fun buildInner(
    target: Bitmap,
    path: Path?,
    image: ImageBitmap?,
    cornerRadius: CornerRadius,
    size: Size,
    w: Int,
    h: Int,
    radius: Float,
    spread: Float,
    offsetX: Float,
    offsetY: Float,
  ) {
    traceSection(TRACE_SKIA) {
      val filter = if (radius > 0f) blurFilter(radius) else null
      // The intermediate's calls already started from a fresh paint; the shadow's calls continue with it, as Compose's.
      if (image == null) innerPaint.resetToFresh()
      val bw = w.toFloat()
      val bh = h.toFloat()
      bindCanvas(target)
      shadowCanvas.save()
      shadowCanvas.clipRect(0, 0, w, h)
      shadowCanvas.drawColor(0, PorterDuff.Mode.CLEAR)
      with(composeCanvas) {
        when {
          image != null -> {
            drawRect(0f, 0f, bw, bh, innerPaint.configureInnerShadow())
            drawImage(image, Offset(offsetX, offsetY), innerPaint.configureInnerShadow(blendMode = BlendMode.Xor, blurFilter = filter))
          }
          path != null -> {
            save()
            translate(offsetX, offsetY)
            drawPath(path, innerPaint.configureInnerShadow(blurFilter = filter))
            restore()
            drawRect(0f, 0f, bw, bh, innerPaint.configureInnerShadow(blendMode = BlendMode.Xor))
          }
          else -> {
            val left = offsetX + spread
            val top = offsetY + spread
            val right = max(left, offsetX + size.width - spread)
            val bottom = max(top, offsetY + size.height - spread)
            drawRoundRect(left, top, right, bottom, cornerRadius.x, cornerRadius.y, innerPaint.configureInnerShadow(blurFilter = filter))
            drawRect(0f, 0f, bw, bh, innerPaint.configureInnerShadow(blendMode = BlendMode.Xor))
          }
        }
      }
      shadowCanvas.restore()
    }
  }

  /** The state of Compose's `Paint()` that `configureShadow` and the intermediate's stroke change. */
  private fun Paint.resetToFresh() {
    color = Color.Black
    blendMode = BlendMode.SrcOver
    style = PaintingStyle.Fill
    strokeWidth = 0f
    nativePaint.setMaskFilter(null)
  }

  /**
   * Compose's `Paint.configureShadow` (Blur.kt) with `setBlurFilter`, as the inner renderer calls it: color, blend
   * mode, style and mask filter; the stroke width of an earlier call stays.
   */
  private fun Paint.configureInnerShadow(
    blendMode: BlendMode = BlendMode.SrcOver,
    blurFilter: BlurMaskFilter? = null,
    style: PaintingStyle = PaintingStyle.Fill,
  ): Paint {
    this.color = Color.Black
    this.blendMode = blendMode
    this.style = style
    nativePaint.setMaskFilter(blurFilter)
    return this
  }

  /** Compose's `Paint.configureShadow` (Shadow.kt) with `setBlurFilter`; stroke width reset as on a fresh paint. */
  private fun Paint.configureShadow(blurFilter: BlurMaskFilter?, style: PaintingStyle = PaintingStyle.Fill): Paint {
    this.style = style
    this.strokeWidth = 0f
    nativePaint.setMaskFilter(blurFilter)
    return this
  }

  private fun blurFilter(radius: Float): BlurMaskFilter {
    val cached = blurFilter
    if (cached != null && radius == blurFilterRadius) return cached
    return BlurMaskFilter(radius, BlurMaskFilter.Blur.NORMAL).also {
      blurFilter = it
      blurFilterRadius = radius
    }
  }

  /** (Re)binds [shadowCanvas] to [target] when the bitmap or its dimensions changed (a reconfigure needs a rebind). */
  private fun bindCanvas(target: Bitmap) {
    if (canvasBitmap !== target || canvasWidth != target.width || canvasHeight != target.height) {
      shadowCanvas.setBitmap(target)
      canvasBitmap = target
      canvasWidth = target.width
      canvasHeight = target.height
    }
  }

  fun close() {
    shadowCanvas.setBitmap(null)
    canvasBitmap = null
    // The intermediate never leaves the builder.
    intermediateCanvas.setBitmap(null)
    intermediate?.recycle()
    intermediate = null
    intermediateImage = null
  }
}
