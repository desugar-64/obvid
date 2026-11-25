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
 * [ShadowBitmapBuilder.Route.Mask] and [ShadowBitmapBuilder.Route.MaskRoundRect]: the outline in device space
 * ([devPath], or a round rect) drawn without a mask filter into [mask] at Skia's mask bounds, then blurred natively
 * into the bitmap. A path build is two calls: `set…Path` decides whether this route takes it, `draw…` writes it.
 * [bounds] is the builder's scratch rect.
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
   * `createOuterShadowBitmap(path)` on Obvid's pipeline: Compose translates by its t and draws [local] blurred (then
   * stroked with spread > 0); Skia fills the path moved by the CTM's [translate] (t, or the area's translate plus t)
   * in device space, through `filterPath`, unless the moved path is a rect (`countNestedRects`: Skia's `BlurRect`, not
   * Obvid's; nested rects reach this only where Skia's `filterRectsToNine` returns unimplemented). Sets [devPath]; true
   * when this route takes the build ([drawDrop] then writes it), false leaves it to the Skia route.
   *
   * Also left to the Skia route, decided before anything is written, the paths whose masks Skia forms otherwise:
   * - an inverse fill: `DrawToMask` bounds it by the clip grown by the blur's margin, not by the path (its stroke is an
   *   inverse fill too);
   * - spread > 0 with a stroke Skia draws as a hairline ([hairlineSpread]): an anti-aliased hairline with the paint's
   *   alpha scaled by the width, not a fill of the stroke's outline;
   * - spread > 0 with a stroke that may be one rect or two nested rects for `countNestedRects`, which Skia blurs with
   *   `BlurRect` or its rect nine-patch: decided on the stroke itself ([SpreadStroke.build]: the platform's stroker is
   *   Skia's, so the same degenerate curves reduce to lines), by [ShadowBitmaps.strokeMayBeRects], kept per outline and
   *   spread. A superset of Skia's test, which also takes the rects Skia blurs whole. A build with its outline and
   *   spread kept costs a fill-type read, a float compare and the stroke it draws anyway.
   *
   * The moved path is `local` copied and offset; with [fromFrame] (a canonical frame, spread ≤ 0) it is rebuilt from
   * the frame's moved points instead ([NestedRects.setMovedPath]: the same verbs and points). On API 23–33
   * `android.graphics.Path` keeps a shortcut for a path made only of same-direction `addRect` calls (`isSimplePath`,
   * which `set` copies and a whole-pixel `offset` keeps): `Canvas.drawPath` then fills the union of the int-truncated
   * rects as a region, without anti-aliasing. Other such paths still take it here; a rebuilt frame does not (`moveTo`
   * clears it).
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

  /**
   * Second Compose draw: the same path stroked 2·spread wide. Skia strokes in local space (resScale 1 for a translate),
   * then translates by the CTM. Tested in local space: the translate keeps an axis-aligned edge so.
   */
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
   * `createInnerPathShadowBrush` without spread: Compose translates by the offset and draws the path blurred; Skia
   * fills the path moved by that translate in device space, through `filterPath` (as the drop shadow's mask route),
   * unless the moved path is a rect (`countNestedRects`: Skia's `BlurRect`, not Obvid's; nested rects reach this only
   * where Skia's `filterRectsToNine` returns unimplemented), or the path is an inverse fill (`DrawToMask` bounds its mask
   * by the clip grown by the blur's margin, not by the path).
   * Sets [devPath]; true when this route takes the build ([drawInner] then writes B), false leaves it to the Skia
   * route. With [fromFrame] (a canonical frame) the moved path is rebuilt from the frame's moved points, as in
   * [setDropPath] (which explains the platform shortcut this avoids on API 23–33).
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

  /**
   * Skia's full mask of a simple round rect (`SkPath::RRect(rrect)` or `SkPath::Oval(rect)`), first call: [rect] with
   * [corner] radii, blurred with [radius]. [blurRoundRect] then draws and blurs it.
   */
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
   * `compute_mask_bounds` (SkDraw.cpp): the device path bounds ([bounds]) outset by ½, rounded out, intersected with
   * the clip (Compose's bitmap) grown by min(border, 128); then the mask is drawn at that origin and blurred into
   * [target]. Replace mode also zeroes the rest of [target]. The mask is [devicePath], or with null the round rect
   * [bounds] with [corner] radii.
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
