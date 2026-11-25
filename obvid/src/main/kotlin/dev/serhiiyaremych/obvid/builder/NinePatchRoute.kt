// Copyright 2026, Serhii Yaremych (desugar-64) and the Obvid project contributors
// SPDX-License-Identifier: Apache-2.0

package dev.serhiiyaremych.obvid.builder

import android.graphics.Bitmap
import android.graphics.PorterDuff
import android.graphics.RectF
import androidx.compose.ui.geometry.CornerRadius
import dev.serhiiyaremych.obvid.NineMaskCache
import dev.serhiiyaremych.obvid.NineMaskKey
import dev.serhiiyaremych.obvid.ObvidNative
import dev.serhiiyaremych.obvid.TRACE_NINE_PATCH
import dev.serhiiyaremych.obvid.traceSection
import kotlin.math.ceil

/**
 * [ShadowBitmapBuilder.Route.NinePatch]: Skia's nine-patch of a simple round rect, from a kept blurred small round
 * rect or a new one.
 */
internal class NinePatchRoute(private val native: NativeBuilder, private val mask: MaskBitmap) {
  /** After an `inverted` [build]: the packed hole of the bitmap it wrote (0: none). */
  var hole = 0L
    private set

  /**
   * The nine-patch of [plan] for the round rect [rect] with [corner] radii. A kept blurred small round rect of this
   * plan, corner radii and radius ([NineMaskKey]) is only stretched into [target] again: on a rebuild
   * ([ShadowBitmaps.isRebuild]) [bitmaps]' private one first, then on any build one published in [masks] (whose last
   * use moves to now). Otherwise the small round rect
   * `(0, 0, rw, rh)` of the plan with the device radii (the given ones: a nine-patch's are never scaled),
   * anti-aliased, no mask filter, is drawn at the mask's origin as Skia's `draw_rrect_into_mask` draws it into its
   * `roundOut()` (`ceil(rw) × ceil(rh)`), then blurred and stretched into [target] natively:
   * - a rebuild blurs into [bitmaps]' private buffer (`builderNineKeep`; rewritten in place, grown only when too small,
   *   never published), so an animation allocates nothing per frame;
   * - a first build blurs into a new mask published in [masks] (`builderNineKeep`) when the caller allows it
   *   ([mayPublish]: a node's first appearance) and the cache keeps masks ([NineMaskCache.canPublish]), else through
   *   the native builder's own buffer (`builderNine`, nothing kept, nothing allocated): a new entry forked by a node
   *   that animates away from a shared entry is forked again on its next frame, so neither publishing nor a private
   *   buffer would be used twice.
   * With [inverted] (an inner shadow) every value v of [target] is written as `255 − v` by the `…InvertedHole` twin
   * of each call, which also returns the bitmap's hole ([hole]); a kept mask holds the same blurred small round rect
   * either way, so drop and inner shadows share it.
   */
  fun build(
    bitmaps: ShadowBitmaps,
    masks: NineMaskCache,
    mayPublish: Boolean,
    target: Bitmap,
    w: Int,
    h: Int,
    radius: Float,
    rect: RectF,
    corner: CornerRadius,
    plan: Long,
    inverted: Boolean,
  ) {
    val l = rect.left
    val t = rect.top
    val r = rect.right
    val b = rect.bottom
    val rx = corner.x
    val ry = corner.y
    traceSection(TRACE_NINE_PATCH) {
      val rebuild = bitmaps.isRebuild
      val own = if (rebuild) bitmaps.keptNineMask(plan, rx, ry, radius) else null
      val published = if (own == null) masks.find(plan, rx, ry, radius) else null
      val kept = own ?: published?.bitmap
      hole = 0L
      if (kept != null) {
        if (published != null) masks.touch(published)
        // The bitmap is exactly the used size: the clear rect is the used rect.
        if (inverted) {
          // The kept mask's rect is found once and only mapped by each placement.
          var maskHole = if (published != null) published.maskHole else bitmaps.nineMaskHole
          if (maskHole < 0L) {
            maskHole = ObvidNative.builderNineMaskHole(kept, ObvidNative.SKIA_CLASS_B, l, t, r, b, rx, ry, radius)
            check(maskHole >= 0L) { "builderNineMaskHole failed: $maskHole" }
            if (published != null) published.maskHole = maskHole else bitmaps.nineMaskHole = maskHole
          }
          hole =
            ObvidNative.builderNinePlaceInvertedHole(
              kept, maskHole, ObvidNative.SKIA_CLASS_B, l, t, r, b, rx, ry, radius, target, w, h, w, h,
            )
          check(hole >= 0L) { "builderNinePlaceInvertedHole failed: $hole" }
        } else {
          val status = ObvidNative.builderNinePlace(kept, ObvidNative.SKIA_CLASS_B, l, t, r, b, rx, ry, radius, target, w, h, w, h)
          check(status == 0) { "builderNinePlace failed: $status" }
        }
        masks.countReuse(bitmaps, shared = published != null)
      } else {
        blurNew(bitmaps, masks, mayPublish, rebuild, target, w, h, radius, rect, corner, plan, inverted)
      }
    }
  }

  /** [build] without a kept mask: the small round rect drawn, blurred and stretched, kept or published when allowed. */
  private fun blurNew(
    bitmaps: ShadowBitmaps,
    masks: NineMaskCache,
    mayPublish: Boolean,
    rebuild: Boolean,
    target: Bitmap,
    w: Int,
    h: Int,
    radius: Float,
    rect: RectF,
    corner: CornerRadius,
    plan: Long,
    inverted: Boolean,
  ) {
    val l = rect.left
    val t = rect.top
    val r = rect.right
    val b = rect.bottom
    val rx = corner.x
    val ry = corner.y
    val rw = ObvidNative.planRectWidth(plan)
    val rh = ObvidNative.planRectHeight(plan)
    val sw = ceil(rw).toInt()
    val sh = ceil(rh).toInt()
    val maskBitmap = mask.ensure(sw, sh)
    val maskCanvas = mask.canvas
    maskCanvas.save()
    maskCanvas.clipRect(0, 0, sw, sh)
    maskCanvas.drawColor(0, PorterDuff.Mode.CLEAR)
    maskCanvas.drawRoundRect(0f, 0f, rw, rh, rx, ry, mask.paint)
    maskCanvas.restore()
    // The plan's border is the radius' border.
    native.updateBorder(radius)
    val kw = sw + 2 * native.border
    val kh = sh + 2 * native.border
    if (rebuild) {
      val allocations = bitmaps.nineAllocationCount
      val keep = bitmaps.nineMaskFor(kw, kh)
      if (bitmaps.nineAllocationCount != allocations) masks.countAllocation()
      nineKeep(maskBitmap, keep, rect, corner, radius, target, w, h, inverted)
      bitmaps.keepNine(plan, rx, ry, radius)
    } else if (mayPublish && masks.canPublish(kw, kh)) {
      val allocated = masks.allocate(kw, kh)
      try {
        nineKeep(maskBitmap, allocated.bitmap, rect, corner, radius, target, w, h, inverted)
      } catch (e: Throwable) {
        allocated.bitmap.recycle()
        throw e
      }
      masks.publish(allocated, plan, rx, ry, radius)
    } else if (inverted) {
      // The bitmap is exactly the used size: the clear rect is the used rect.
      hole =
        ObvidNative.builderNineInvertedHole(
          native.handle, maskBitmap, ObvidNative.SKIA_CLASS_B, l, t, r, b, rx, ry, radius, target, w, h, w, h,
        )
      check(hole >= 0L) { "builderNineInvertedHole failed: $hole" }
    } else {
      val status =
        ObvidNative.builderNine(native.handle, maskBitmap, ObvidNative.SKIA_CLASS_B, l, t, r, b, rx, ry, radius, target, w, h, w, h)
      check(status == 0) { "builderNine failed: $status" }
    }
    masks.countBlur(bitmaps)
  }

  /**
   * `builderNineKeep` (or with [inverted] `builderNineKeepInvertedHole`, which sets [hole]) of the small round rect in
   * [maskBitmap], kept in [keep].
   */
  private fun nineKeep(
    maskBitmap: Bitmap,
    keep: Bitmap,
    rect: RectF,
    corner: CornerRadius,
    radius: Float,
    target: Bitmap,
    w: Int,
    h: Int,
    inverted: Boolean,
  ) {
    val l = rect.left
    val t = rect.top
    val r = rect.right
    val b = rect.bottom
    val rx = corner.x
    val ry = corner.y
    // The bitmap is exactly the used size: the clear rect is the used rect.
    if (inverted) {
      hole =
        ObvidNative.builderNineKeepInvertedHole(
          native.handle, maskBitmap, keep, ObvidNative.SKIA_CLASS_B, l, t, r, b, rx, ry, radius, target, w, h, w, h,
        )
      check(hole >= 0L) { "builderNineKeepInvertedHole failed: $hole" }
    } else {
      val status =
        ObvidNative.builderNineKeep(
          native.handle, maskBitmap, keep, ObvidNative.SKIA_CLASS_B, l, t, r, b, rx, ry, radius, target, w, h, w, h,
        )
      check(status == 0) { "builderNineKeep failed: $status" }
    }
  }
}
