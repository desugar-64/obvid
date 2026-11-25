// Copyright 2026, Serhii Yaremych (desugar-64) and the Obvid project contributors
// SPDX-License-Identifier: Apache-2.0

package dev.serhiiyaremych.obvid.builder

import android.graphics.Bitmap
import android.graphics.PorterDuff
import android.graphics.RectF
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asAndroidPath
import dev.serhiiyaremych.obvid.ObvidNative
import dev.serhiiyaremych.obvid.TRACE_BLUR
import dev.serhiiyaremych.obvid.TRACE_OUTLINE
import dev.serhiiyaremych.obvid.builder.NativeBuilder.Companion.MODE_REPLACE
import dev.serhiiyaremych.obvid.builder.NativeBuilder.Companion.MODE_SRC_OVER
import dev.serhiiyaremych.obvid.traceSection
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * [ShadowBitmapBuilder.Route.Mask] and [ShadowBitmapBuilder.Route.MaskRoundRect]: the outline drawn into [mask] at
 * Skia's mask bounds, then blurred natively. `set…` decides whether this route takes a build, `draw…` writes it.
 */
internal class MaskRoute(
  private val native: NativeBuilder,
  private val mask: MaskBitmap,
  private val nested: NestedRects,
  private val stroke: SpreadStroke,
  private val area: DropArea,
  private val bounds: RectF,
) {
  // The device path of the current build.
  private val devPath = android.graphics.Path()

  // The corner radii of the round rect [blurMask] draws when it has no path.
  private var corner = CornerRadius.Zero

  /**
   * Sets the device path of a drop build: [local] moved by [translate], or rebuilt from the canonical frame when
   * [fromFrame]. Returns true when this route takes the build ([drawDrop] then draws it). Returns false for an inverse
   * fill, a hairline spread stroke, a rect, or a spread stroke that may be rects: the Skia route draws those.
   */
  fun setDropPath(local: android.graphics.Path, spread: Float, translate: Offset, fromFrame: Boolean): Boolean {
    if (local.isInverseFillType || (spread > 0f && hairlineSpread(spread))) return false
    if (fromFrame) {
      nested.setMovedPath(devPath, local.fillType)
    } else {
      devPath.set(local)
      devPath.offset(translate.x, translate.y)
    }
    return !devPath.isRect(null) && !(spread > 0f && strokeMayBeRects(local, spread))
  }

  /** Whether the spread stroke of [local] (2·spread wide, in local space) may be one rect or two nested rects. */
  private fun strokeMayBeRects(local: android.graphics.Path, spread: Float): Boolean {
    stroke.build(local, spread)
    return checkNotNull(area.target).strokeMayBeRects(spread, stroke.composePath)
  }

  /** Writes the drop build [setDropPath] took into [area]'s bitmap: the fill, then the stroke source-over. */
  fun drawDrop(radius: Float, spread: Float, translate: Offset) {
    native.updateBorder(radius)
    val target = area.prepare()
    blurPath(devPath, target, area.size.width, area.size.height, radius, MODE_REPLACE)
    if (spread > 0f) {
      stroke.path.offset(translate.x, translate.y)
      blurPath(stroke.path, target, area.size.width, area.size.height, radius, MODE_SRC_OVER)
    }
  }

  /**
   * Sets the device path of an inner build without spread: [path] moved by [offset], or rebuilt from the canonical
   * frame when [fromFrame]. Returns true when this route takes the build ([drawInner] then draws it). Returns false for
   * an inverse fill or a rect: the Skia route draws those.
   */
  fun setInnerPath(path: Path, offset: Offset, fromFrame: Boolean): Boolean {
    if (path.asAndroidPath().isInverseFillType) return false
    if (fromFrame) {
      nested.setMovedPath(devPath, path.asAndroidPath().fillType)
    } else {
      devPath.set(path.asAndroidPath())
      devPath.offset(offset.x, offset.y)
    }
    return !devPath.isRect(null)
  }

  /** Writes B of the inner build [setInnerPath] took into [target]. */
  fun drawInner(target: Bitmap, w: Int, h: Int, radius: Float) {
    native.updateBorder(radius)
    blurPath(devPath, target, w, h, radius, MODE_REPLACE)
  }

  /** Sets the round rect [rect] with [corner] radii for [blurRoundRect]. */
  fun setRoundRect(rect: RectF, corner: CornerRadius, radius: Float) {
    native.updateBorder(radius)
    bounds.set(rect)
    this.corner = corner
  }

  /** The round rect of [setRoundRect], drawn by the mask canvas's `drawRoundRect` and blurred into [target] (replace). */
  fun blurRoundRect(target: Bitmap, w: Int, h: Int, radius: Float) {
    blurMask(null, target, w, h, radius, MODE_REPLACE)
  }

  /** [blurMask] of [devicePath], with its bounds. */
  private fun blurPath(devicePath: android.graphics.Path, target: Bitmap, w: Int, h: Int, radius: Float, mode: Int) {
    @Suppress("DEPRECATION") devicePath.computeBounds(bounds, true)
    blurMask(devicePath, target, w, h, radius, mode)
  }

  /**
   * Draws [devicePath] (null: the round rect [bounds]) into the mask at Skia's mask bounds (`compute_mask_bounds`) and
   * blurs it into [target].
   */
  private fun blurMask(devicePath: android.graphics.Path?, target: Bitmap, w: Int, h: Int, radius: Float, mode: Int) {
    val m = min(native.border, 128)
    val l = max(floor(bounds.left - 0.5f).toInt(), -m)
    val t = max(floor(bounds.top - 0.5f).toInt(), -m)
    val r = min(ceil(bounds.right + 0.5f).toInt(), w + m)
    val b = min(ceil(bounds.bottom + 0.5f).toInt(), h + m)
    val empty = l >= r || t >= b
    if (empty && mode == MODE_SRC_OVER) return
    val mw = if (empty) 0 else r - l
    val mh = if (empty) 0 else b - t
    val maskBitmap = mask.ensure(max(mw, 1), max(mh, 1))
    if (!empty) {
      traceSection(TRACE_OUTLINE) {
        val maskCanvas = mask.canvas
        maskCanvas.save()
        maskCanvas.clipRect(0, 0, mw, mh)
        maskCanvas.drawColor(0, PorterDuff.Mode.CLEAR)
        maskCanvas.translate(-l.toFloat(), -t.toFloat())
        if (devicePath != null) {
          maskCanvas.drawPath(devicePath, mask.paint)
        } else {
          maskCanvas.drawRoundRect(bounds, corner.x, corner.y, mask.paint)
        }
        maskCanvas.restore()
      }
    }
    // The bitmap is exactly the used size: the clear rect is the used rect.
    val status =
      traceSection(TRACE_BLUR) {
        ObvidNative.builderBlur(native.handle, maskBitmap, mw, mh, l, t, radius, target, w, h, w, h, mode)
      }
    check(status == 0) { "builderBlur failed: $status" }
  }
}
