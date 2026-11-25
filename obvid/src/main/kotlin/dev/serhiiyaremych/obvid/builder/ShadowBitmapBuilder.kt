// Copyright 2026, Serhii Yaremych (desugar-64) and the Obvid project contributors
// SPDX-License-Identifier: Apache-2.0

package dev.serhiiyaremych.obvid.builder

import android.graphics.Bitmap
import android.graphics.RectF
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asAndroidPath
import dev.serhiiyaremych.obvid.NineMaskCache
import dev.serhiiyaremych.obvid.NineMaskKey
import dev.serhiiyaremych.obvid.ObvidNative
import dev.serhiiyaremych.obvid.ShadowExtent
import dev.serhiiyaremych.obvid.ShadowPrefetch
import dev.serhiiyaremych.obvid.ShadowStore
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Builds the A8 drop-shadow bitmap of Compose 1.13.0's `DropShadowRenderer.createOuterShadowBitmap` into a
 * [ShadowBitmaps]: after [build], its bitmap is identical to the bitmap Compose allocates on Android 16–17's
 * Skia. On older Android the blur and the round-rect nine-patch keep that Skia's output rather than the device's; the
 * masks are still rasterized by the device's `Canvas`, and [Route.Skia] draws with the device's Skia.
 *
 * Routes, as Compose's `updateParamsFromOutline` and Skia's CPU routing split them:
 * - [Route.Mask]: `Outline.Generic` and non-simple `Outline.Rounded` with radius > 0, unless the path moved by
 *   Compose's translate is a rect (`Path.isRect`; Skia blurs a rect path with its analytic `BlurRect`) or may be
 *   nested rects ([ShadowBitmaps.nestedRects]; Skia's rect nine-patch, also with spread: the fill draw; canonical
 *   frames are decided exactly, below), or is a path whose mask Skia forms otherwise ([MaskRoute.setDropPath]: an inverse fill, a
 *   spread stroke Skia draws as a hairline, a spread stroke that may be rects). The outline is
 *   drawn without a mask filter into a reused A8 mask bitmap at Skia's mask bounds (`compute_mask_bounds`), blurred by
 *   the NEON kernel and written into the bitmap at (mask origin − border) (`ObvidNative.builderBlur`). Path +
 *   spread: the fill and the `2·spread` stroke are separate masks, the stroke composited source-over.
 * - [Route.Skia]: `Outline.Rectangle`, a rect path, nested rects (except the frames below), the paths [MaskRoute.setDropPath]
 *   leaves to Skia, simple `Outline.Rounded` where Skia's decision is not Obvid's, and any outline at radius ≤ 0
 *   (Compose then sets no mask filter): Compose's own draw calls with its `Paint` configuration.
 * - A canonical frame ([ShadowBitmaps.isFrame]: two `addRect`-shaped contours) with radius > 0 and spread ≤ 0, by
 *   Skia's nested-rects test on its points moved by the translate ([NestedRects.test]) and then Android 17's
 *   `filterRectsToNine` (`ObvidNative.builderPlanRects`):
 *   - [Route.NineRects]: Skia's frame nine-patch. The small frame is drawn into the mask bitmap, blurred by Obvid's
 *     kernel and stretched into the bitmap without its centre ([NineRectsRoute.build]).
 *   - [Route.Mask]: Skia returns unimplemented (a hole too small for the blur, a rect over 32767), or the two rects
 *     are not nested; Skia blurs the whole mask of the path.
 *   - The Skia route when Skia does not blur; a frame whose points fail the test goes the way of any other path.
 *   With spread > 0 the fill and the stroke are two draws: the Skia route, as for other nested rects.
 * - Simple `Outline.Rounded` (incl. `CircleShape`) with radius > 0 and both corner radii > 0, by the decision of
 *   Android 16–17's Skia for Compose's `drawRoundRect` (`ObvidNative.builderPlanRoundRect`, that Skia's
 *   `filterRRectToNine`), on every API level:
 *   - [Route.NinePatch]: Skia's nine-patch. The small round rect `(0, 0, w, h)` (whole pixels) is drawn into the mask
 *     bitmap at the origin, blurred by Obvid's kernel and stretched into the bitmap (`ObvidNative.builderNine`). The
 *     blurred small round rect depends only on the plan's small rect, the corner radii and the radius ([NineMaskKey]),
 *     not on the rect's position or size, the spread or the inner shadow's offset, so a build whose key is kept only
 *     stretches the kept one again (`builderNinePlace`), as Skia's nine-patch cache does for Compose. Kept in two
 *     places ([NinePatchRoute.build]): published in [nineMasks] by a target's first build (shared by every target of the cache,
 *     drop and inner, never rewritten), and in the target's private buffer ([ShadowBitmaps.keptNineMask]) by a
 *     rebuild that finds neither (an animation: rewritten in place by its next miss, never published).
 *   - [Route.MaskRoundRect]: nothing to stretch, or an oval (`CircleShape` without spread); Skia blurs the whole mask
 *     of `SkPath::RRect(rrect)` or `SkPath::Oval(rect)`. The mask route with the shape drawn by the mask canvas's
 *     `drawRoundRect`, which builds that same path from the same floats.
 *   - Otherwise (a rect after `setRectXY`, or over Skia's limits): the Skia route.
 *   Spread adds no stroke here: Compose's round-rect overload draws the rect grown by spread.
 *
 * The build context (native handle with the kernel scratch, mask bitmap, canvases, paints, paths) is shared by every
 * shadow built with this builder, held by one part per route ([MaskRoute], [NinePatchRoute], [NineRectsRoute],
 * [InnerPathSpread], [SkiaRoute]) and the parts they share ([NativeBuilder], [MaskBitmap], [DropArea], [SpreadStroke],
 * [NestedRects]); this class decides the route; a [build] that fits allocates nothing (except a `BlurMaskFilter` when the Skia
 * route's radius changes, a published nine-patch mask for a node's first build of a new key, and the target's private
 * one when it first rebuilds off the cache or must grow). The first write of a build replaces the whole bitmap
 * (replace mode of the first mask, or a `CLEAR` on the Skia route), so a reconfigured bitmap's stale bytes never show.
 *
 * HWUI: the bitmap's generation id (the key of HWUI's texture cache) changes on every [build]: the native unlock calls
 * `notifyPixelsChanged` (API 23+), and Skia's raster draws bump it themselves (`SkBitmapDevice` takes the pixels with
 * `accessPixels`, which notifies; API 23–25 through `accessBitmap(true)`).
 *
 * [buildInner] builds Compose's inner-shadow bitmap (`InnerShadowRenderer.buildShadow`) with the same routes. Every
 * inner build is "255 − B": B is what the blurred draw of the shape (inset by the spread for a round rect, moved by
 * the offset) leaves in an empty bitmap, and Compose's opaque `Xor` rect over A8 is exactly `255 − d`. The native
 * routes write B (clipped to the bitmap: the moved shape may lie partly or wholly outside it), then invert the bitmap
 * (`ObvidNative.builderInvertHole`); the nine-patch routes write `255 − B` in the same pass instead
 * (`builderNineInvertedHole`, `builderNineKeepInvertedHole`, `builderNinePlaceInvertedHole`, `builderNineRects`).
 * Except the frame's nine-patch, each also returns the bitmap's hole (a rect of 0s), from which the build sets the
 * bands of the final draw ([ShadowBitmaps.setBands]) unless the outline is a concave path or an inverse fill:
 * - [Route.Mask]: a path (`Outline.Generic`, non-simple `Outline.Rounded`) with radius > 0 and spread ≤ 0 (Compose
 *   ignores a negative spread there), moved by the offset, unless it is a rect (`Path.isRect`; Skia blurs a rect path
 *   with its analytic `BlurRect`), may be nested rects ([ShadowBitmaps.nestedRects]; Skia's rect nine-patch) or is an
 *   inverse fill (Skia's mask is the whole clip, [MaskRoute.setInnerPath]). Also a
 *   path, nested rects included, with spread > 0 and radius > 0: Compose's intermediate (the path eroded
 *   by a `Clear` stroke) is rasterized by Compose's own calls, and the mask Skia blurs for `drawImage` of it is formed
 *   by the newest Skia's placement rule ([InnerPathSpread]), unless Skia's rect-to-rect matrix for that
 *   `drawImage` scales (an offset whose float sum with the intermediate's size is inexact): then the Skia route.
 * - [Route.NinePatch] / [Route.MaskRoundRect]: a simple `Outline.Rounded` with both corner radii > 0 and radius > 0,
 *   by the same decision as the drop shadow's for Compose's inner `drawRoundRect` (the rect inset by the spread and
 *   moved by the offset, corner radii unchanged).
 * - [Route.NineRects] / [Route.Mask] / [Route.Skia] for a canonical frame with radius > 0 and spread ≤ 0, by the same
 *   decision as the drop shadow's, for the frame moved by the offset.
 * - [Route.Skia]: everything else (rects and zero corner radii, radius ≤ 0, an empty inset rect, Skia's own
 *   decisions): Compose's own calls on the reused, cleared bitmap, through a [Paint] reset to a fresh one's state per
 *   build (Compose's renderer builds once with a new `Paint`).
 *
 * A path with spread > 0 rasterizes Compose's intermediate into a reused A8 bitmap of exactly its size on either
 * route, before the shadow bitmap is prepared (Compose's order).
 *
 * Not thread-safe (one builder per [ShadowStore], used on that store's thread). [close] frees the native handle, the
 * mask, the intermediate and the buffer the intermediate is placed in; the published nine-patch masks are [nineMasks]'
 * (the store's for a store's builder, which outlives its builders; a builder's own otherwise, which keeps nothing).
 */
internal class ShadowBitmapBuilder(nineMasks: NineMaskCache = NineMaskCache()) : AutoCloseable {
  /** The published masks the next builds use; a worker's builder takes each job's own ([ShadowPrefetch.masks]). */
  var nineMasks: NineMaskCache = nineMasks

  enum class Route {
    Mask,
    Skia,
    NinePatch,
    MaskRoundRect,
    NineRects,
  }

  private val native = NativeBuilder()

  /** Test hook: called when [close] frees the native handle. */
  var onCloseForTest: (() -> Unit)? = null

  /** The route of the last [build]. */
  var route: Route = Route.Skia
    private set

  // The scratch rect of the build's bounds: the draw's local bounds, the mask's device bounds, the path's bounds.
  private val bounds = RectF()

  // [NinePatchRoute.build]: whether this build's first build of a target may publish its mask (the current build's
  // argument).
  private var mayPublish = true

  private val area = DropArea()
  private val stroke = SpreadStroke()
  private val mask = MaskBitmap()
  private val nested = NestedRects()
  private val maskRoute = MaskRoute(native, mask, nested, stroke, area, bounds)
  private val ninePatch = NinePatchRoute(native, mask)
  private val nineRects = NineRectsRoute(native, mask, nested)
  private val innerSpread = InnerPathSpread(native)
  private val skia = SkiaRoute(bounds)

  /**
   * Rebuilds [target]'s shadow of [outline] (created for the layout [size]) with [radius] and [spread] in px, as
   * Compose computes them (`shadow.radius.toPx()`, `shadow.spread.toPx()`). With [preserveCurrent] the current bitmap
   * is left untouched and the shadow is written into the other buffer, which becomes current. A build off the
   * nine-patch route frees [target]'s private small round rect. Without [mayPublish] a first build into [target]
   * publishes no nine-patch mask ([NinePatchRoute.build]).
   *
   * With [full] ([ShadowExtent.Full]) the bitmap is the full-extent area instead of Compose's: Compose's draw calls on
   * a larger canvas, translated by whole pixels ([ShadowBitmaps.originX], [ShadowBitmaps.originY]: where the bitmap
   * starts in Compose's), cropped to the shape drawn (the outline grown by the spread, as Compose draws it) grown by
   * the margin past which the blur is 0: [ObvidNative.builderFullMargin] on Obvid's routes, the device Skia's reach
   * ([ObvidNative.builderSkiaReachMargin]) on [Route.Skia]. Every route decides and draws on that canvas's device
   * coordinates, as Skia would. A shape reaching past its layout bounds is kept up to that margin past Compose's area
   * (the bitmap is at most Compose's grown by the margin on each side). An inverse fill keeps Compose's area: it
   * covers the whole canvas, so no area holds all of its shadow.
   */
  fun build(
    target: ShadowBitmaps,
    outline: Outline,
    size: Size,
    radius: Float,
    spread: Float,
    preserveCurrent: Boolean = false,
    mayPublish: Boolean = true,
    full: Boolean = false,
  ) {
    check(native.isOpen) { "closed" }
    this.mayPublish = mayPublish
    target.clearBands()
    try {
      buildRoutes(target, outline, size, radius, spread, preserveCurrent, full)
    } finally {
      area.end()
    }
    if (route != Route.NinePatch) target.releaseNineMask()
  }

  private fun buildRoutes(
    target: ShadowBitmaps,
    outline: Outline,
    size: Size,
    radius: Float,
    spread: Float,
    preserveCurrent: Boolean,
    fullExtent: Boolean,
  ) {
    target.setOutline(outline)
    val path = target.path
    // An inverse fill covers the whole canvas Compose draws on, however large: no area holds all of its shadow, so it
    // keeps Compose's (the same bitmap as without the full extent).
    val full = fullExtent && !(path != null && path.asAndroidPath().isInverseFillType)

    // DropShadowRenderer.createOuterShadowBitmap: the bitmap size. Compose's ImageBitmap throws
    // IllegalArgumentException for an empty one (a negative spread can make it empty); so does this, also for the full
    // extent.
    val outset = radius * 2 + spread * 2
    val shadowWidth = size.width + outset
    val shadowHeight = size.height + outset
    val cw = ceil(shadowWidth).toInt()
    val ch = ceil(shadowHeight).toInt()
    require(cw > 0 && ch > 0) { "empty shadow bitmap ${cw}x$ch" }
    area.begin(target, full, cw, ch, preserveCurrent)
    stroke.reset()

    // createOuterShadowBitmap(path): translated by radius + spread only for a positive spread.
    val t = if (spread > 0f) radius + spread else radius
    // Each sets the route when it takes the build, and prepares the bitmap ([DropArea.prepare]) once it has. A
    // canonical frame without spread (one fill draw) is decided by Skia's nested-rects test on the moved points; any
    // other path that may be nested rects is left to Skia, which blurs the fill draw with its rect nine-patch.
    if (path != null && radius > 0f) {
      val local = path.asAndroidPath()
      if (full) {
        stroke.drawBounds(local, spread, bounds)
        area.setFull(bounds, t, native.fullMargin(radius))
      } else {
        area.setCompose()
      }
      val tx = area.moved(t, area.x)
      val ty = area.moved(t, area.y)
      val frame = if (spread <= 0f && target.isFrame) nested.test(target, tx, ty) else NestedRects.UNKNOWN
      val taken =
        when (frame) {
          NestedRects.NESTED ->
            when (val plan = nested.plan(radius)) {
              ObvidNative.PLAN_SKIA -> false
              ObvidNative.PLAN_RECTS_MASK -> buildMask(local, radius, spread, tx, ty, fromFrame = true)
              else -> buildNineRects(area.prepare(), area.width, area.height, radius, plan, inverted = false)
            }
          NestedRects.NOT_NESTED -> buildMask(local, radius, spread, tx, ty, fromFrame = true)
          else -> !target.nestedRects && buildMask(local, radius, spread, tx, ty)
        }
      if (taken) return
    }
    if (path == null && radius > 0f && buildRoundRect(target, shadowWidth, shadowHeight, radius)) {
      return
    }
    route = Route.Skia
    if (full) {
      if (path != null) {
        stroke.drawBounds(path.asAndroidPath(), spread, bounds)
        area.setFull(bounds, t, native.skiaMargin(radius))
      } else {
        roundRectBounds(radius, shadowWidth, shadowHeight)
        area.setFull(bounds, 0f, native.skiaMargin(radius))
      }
    } else {
      area.setCompose()
    }
    skia.build(area.prepare(), area, path, target.cornerRadius, shadowWidth, shadowHeight, radius, spread, t)
  }

  /** The bounds of Compose's round-rect draw (`createOuterShadowBitmap`'s rect, sorted) into [bounds]. */
  private fun roundRectBounds(radius: Float, shadowWidth: Float, shadowHeight: Float) {
    val r = shadowWidth - radius
    val b = shadowHeight - radius
    bounds.set(min(radius, r), min(radius, b), max(radius, r), max(radius, b))
  }

  /** The drop mask route of [local] ([MaskRoute.setDropPath]); false leaves the build to the Skia route. */
  private fun buildMask(
    local: android.graphics.Path,
    radius: Float,
    spread: Float,
    tx: Float,
    ty: Float,
    fromFrame: Boolean = false,
  ): Boolean {
    val taken = maskRoute.setDropPath(local, spread, tx, ty, fromFrame)
    if (taken) {
      route = Route.Mask
      maskRoute.drawDrop(radius, spread, tx, ty)
    }
    return taken
  }

  /** [NineRectsRoute.build] of the frame [NestedRects.test] found nested; always true. */
  private fun buildNineRects(target: Bitmap, w: Int, h: Int, radius: Float, plan: Long, inverted: Boolean): Boolean {
    route = Route.NineRects
    nineRects.build(target, w, h, radius, plan, inverted)
    return true
  }

  /**
   * Builds a simple round rect (radius > 0) the way Android 16–17's Skia would, if that Skia's decision for Compose's
   * draw is one of Obvid's routes ([Route.NinePatch], [Route.MaskRoundRect]); sets [route] and returns true then. False
   * leaves the build to the Skia route.
   */
  private fun buildRoundRect(bitmaps: ShadowBitmaps, shadowWidth: Float, shadowHeight: Float, radius: Float): Boolean {
    val rx = bitmaps.cornerRadius.x
    val ry = bitmaps.cornerRadius.y
    if (!(rx > 0f && ry > 0f)) return false // drawRoundRect draws a rect
    // createOuterShadowBitmap(size, radius, spread, cornerRadius): drawRoundRect with these floats, no translate; the
    // full extent's canvas translate moves the device rect (`SkRRect::transform`: `mapRect`, each edge plus the
    // translate).
    if (area.full) {
      roundRectBounds(radius, shadowWidth, shadowHeight)
      area.setFull(bounds, 0f, native.fullMargin(radius))
    } else {
      area.setCompose()
    }
    val l = area.moved(radius, area.x)
    val t = area.moved(radius, area.y)
    val r = area.moved(shadowWidth - radius, area.x)
    val b = area.moved(shadowHeight - radius, area.y)
    val w = area.width
    val h = area.height
    val plan = ObvidNative.builderPlanRoundRect(ObvidNative.SKIA_CLASS_B, l, t, r, b, rx, ry, radius)
    val taken = plan != ObvidNative.PLAN_SKIA
    when (plan) {
      ObvidNative.PLAN_SKIA -> Unit
      ObvidNative.PLAN_FULL_MASK -> {
        route = Route.MaskRoundRect
        // Skia's fallback: drawPath(SkPath::RRect(rrect)) through filterPath, whose device bounds are the rect.
        //
        // An oval (CircleShape) is the same path call, by another way in. Compose's canvas: SkCanvas::drawRoundRect
        // → setRectXY gives an oval → onDrawRRect sends `isOval()` to drawOval(rect) before any device call, so
        // filterRRectToNine is never reached → SkBitmapDevice::drawOval → drawPath(SkPath::Oval(rect)) (Android 10:
        // path.addOval(rect); 17: via skcpu::Draw::drawOval; the mask canvas makes the same call) → drawDevPath →
        // filterPath: not a rect, so DrawToMask: mask bounds = (path bounds = rect) outset by ½, rounded out,
        // clipped to the clip grown by min(m, 128) (blurMask's l, t, r, b), then draw_into_mask fills the path
        // anti-aliased under translate(−l, −t) with the mask rect as clip (10–14: SkDraw::drawPath with that matrix;
        // 17: mapPoints, then SkScan::AntiFillPath), then filterMask blurs it. Android 17's onDrawOval first asks
        // canAttemptBlurredRRectDraw, which is null on a raster device (useDrawCoverageMaskForMaskFilters() is
        // false), and then aboutToDraw adds no mask-filter layer for the same reason.
        // The mask canvas, no mask filter: the same drawRoundRect floats → the same setRectXY oval → drawOval(rect)
        // → drawPath(SkPath::Oval(rect)) with the CTM translate(−l, −t) → the path mapped by that translate →
        // SkScan::AntiFillPath, clipped to (0, 0, mw, mh). Same contour, same float translate, same scan converter
        // and clip, so the same coverage; blitted opaque black onto zero, it is the A8 mask Skia blurs.
        maskRoute.blurRoundRect(l, t, r, b, rx, ry, area.prepare(), w, h, radius)
      }
      else -> {
        route = Route.NinePatch
        ninePatch.build(bitmaps, nineMasks, mayPublish, area.prepare(), w, h, radius, l, t, r, b, rx, ry, plan, inverted = false)
      }
    }
    return taken
  }

  /**
   * Rebuilds [target]'s inner shadow of [outline] (created for the layout [size]) with [radius], [spread] and the
   * offset ([offsetX], [offsetY]) in px, as Compose computes them (`shadow.radius.toPx()`, …), into the bitmap
   * Compose's `InnerShadowRenderer` allocates: `ceil(size)` per axis, grown by `ceil(radius)` on each side for a path
   * (the content is not inset by it). With [preserveCurrent] the current bitmap is left untouched and the shadow is
   * written into the other buffer, which becomes current. A build off the nine-patch route frees [target]'s private
   * small round rect. Without [mayPublish] a first build into [target] publishes no nine-patch mask
   * ([NinePatchRoute.build]).
   */
  fun buildInner(
    target: ShadowBitmaps,
    outline: Outline,
    size: Size,
    radius: Float,
    spread: Float,
    offsetX: Float,
    offsetY: Float,
    preserveCurrent: Boolean = false,
    mayPublish: Boolean = true,
  ) {
    check(native.isOpen) { "closed" }
    this.mayPublish = mayPublish
    target.clearBands()
    buildInnerRoutes(target, outline, size, radius, spread, offsetX, offsetY, preserveCurrent)
    if (route != Route.NinePatch) target.releaseNineMask()
  }

  private fun buildInnerRoutes(
    target: ShadowBitmaps,
    outline: Outline,
    size: Size,
    radius: Float,
    spread: Float,
    offsetX: Float,
    offsetY: Float,
    preserveCurrent: Boolean,
  ) {
    target.setOutline(outline)
    val path = target.path
    // Compose rasterizes the path + spread intermediate before it allocates the shadow bitmap. Its ImageBitmap throws
    // IllegalArgumentException for an empty bitmap (an empty path; a path's padding is negative for a negative
    // radius); so does this, in the same order.
    val image = if (path != null && spread > 0f) skia.buildIntermediate(path, spread) else null
    val pad = if (path != null) ceil(radius).toInt() else 0
    val w = ceil(size.width).toInt() + pad * 2
    val h = ceil(size.height).toInt() + pad * 2
    require(w > 0 && h > 0) { "empty shadow bitmap ${w}x$h" }
    target.setWindow(0, 0, w, h)
    val bitmap = target.prepare(w, h, preserveCurrent)
    if (radius > 0f) {
      // Sets the route when it takes the build.
      val taken =
        when {
          path == null -> buildInnerRoundRect(target, bitmap, size, w, h, radius, spread, offsetX, offsetY)
          image != null -> buildInnerPathSpread(bitmap, w, h, radius, offsetX, offsetY)
          // A canonical frame is decided by Skia's nested-rects test on the moved points; any other path that may be
          // nested rects is left to Skia, which blurs the path draw with its rect nine-patch.
          else ->
            when (if (target.isFrame) nested.test(target, offsetX, offsetY) else NestedRects.UNKNOWN) {
              NestedRects.NESTED ->
                when (val plan = nested.plan(radius)) {
                  ObvidNative.PLAN_SKIA -> false
                  ObvidNative.PLAN_RECTS_MASK -> buildInnerPath(path, bitmap, w, h, radius, offsetX, offsetY, fromFrame = true)
                  else -> buildNineRects(bitmap, w, h, radius, plan, inverted = true)
                }
              NestedRects.NOT_NESTED -> buildInnerPath(path, bitmap, w, h, radius, offsetX, offsetY, fromFrame = true)
              else -> !target.nestedRects && buildInnerPath(path, bitmap, w, h, radius, offsetX, offsetY)
            }
        }
      if (taken) {
        // The nine-patch routes have written the inverted bitmap themselves; the round rect's also returned its hole.
        // The others' hole is grown from the component's centre moved by half the offset (the middle of the overlap of
        // the shape and the shape moved by the offset, for a symmetric shape). The frame's nine-patch gives none: its
        // 0s lie in the frame's band, not around a point known in advance.
        val hole =
          when (route) {
            Route.NinePatch -> ninePatch.hole
            Route.NineRects -> 0L
            else ->
              native.invertWithHole(
                bitmap, w, h, floor((size.width + offsetX) / 2f).toInt(), floor((size.height + offsetY) / 2f).toInt(),
              )
          }
        // Bands only for an outline whose pixels do not depend on the clip it is drawn under: a rect, a round rect
        // (analytic on the GPU) or a convex path (Ganesh's convex path renderer). A concave path's renderer is chosen
        // by its clipped device bounds (an atlas for a small clipped path, a tessellation for a large one), whose
        // anti-aliased edges differ by a few levels. An inverse fill (convex or not) covers the whole clip, past any
        // band.
        if (hole > 0L && (path == null || (path.isConvex && !path.asAndroidPath().isInverseFillType))) {
          // A path may reach past the component: the bands must cover all of it.
          @Suppress("DEPRECATION") if (path != null) path.asAndroidPath().computeBounds(bounds, true)
          target.setBands(hole, size.width, size.height, if (path != null) bounds else null)
        }
        return
      }
    }
    route = Route.Skia
    skia.buildInner(bitmap, path, image, target.cornerRadius, size, w, h, radius, spread, offsetX, offsetY)
  }

  /** The inner mask route of [path] ([MaskRoute.setInnerPath]); false leaves the build to the Skia route. */
  private fun buildInnerPath(
    path: Path,
    target: Bitmap,
    w: Int,
    h: Int,
    radius: Float,
    offsetX: Float,
    offsetY: Float,
    fromFrame: Boolean = false,
  ): Boolean {
    val taken = maskRoute.setInnerPath(path, offsetX, offsetY, fromFrame)
    if (taken) {
      route = Route.Mask
      maskRoute.drawInner(target, w, h, radius)
    }
    return taken
  }

  /**
   * `createInnerShadowBrush`'s `drawRoundRect` (radius > 0) on Obvid's pipeline when Android 16–17's Skia takes a route
   * of Obvid's for it ([buildRoundRect]'s plan, for the inset, moved rect): writes B and sets the route. False leaves
   * the build to the Skia route (a rect or zero corner radius, an empty inset rect, Skia's own decisions).
   */
  private fun buildInnerRoundRect(
    bitmaps: ShadowBitmaps,
    target: Bitmap,
    size: Size,
    w: Int,
    h: Int,
    radius: Float,
    spread: Float,
    offsetX: Float,
    offsetY: Float,
  ): Boolean {
    val rx = bitmaps.cornerRadius.x
    val ry = bitmaps.cornerRadius.y
    if (!(rx > 0f && ry > 0f)) return false // drawRoundRect draws a rect
    val l = offsetX + spread
    val t = offsetY + spread
    val r = max(l, offsetX + size.width - spread)
    val b = max(t, offsetY + size.height - spread)
    val plan = ObvidNative.builderPlanRoundRect(ObvidNative.SKIA_CLASS_B, l, t, r, b, rx, ry, radius)
    val taken = plan != ObvidNative.PLAN_SKIA
    when (plan) {
      ObvidNative.PLAN_SKIA -> Unit
      ObvidNative.PLAN_FULL_MASK -> {
        // Skia's full mask of SkPath::RRect(rrect) or SkPath::Oval(rect), as in buildRoundRect; the mask bounds are
        // clipped to the bitmap grown by min(border, 128), so a rect moved partly or wholly off the bitmap is cut the
        // way Skia cuts it.
        route = Route.MaskRoundRect
        maskRoute.blurRoundRect(l, t, r, b, rx, ry, target, w, h, radius)
      }
      else -> {
        // draw_nine clips each piece to the device clip (the bitmap): write_nine's clip to the used rect. Written
        // inverted (255 − B), so buildInner skips its separate inversion for this route.
        route = Route.NinePatch
        ninePatch.build(bitmaps, nineMasks, mayPublish, target, w, h, radius, l, t, r, b, rx, ry, plan, inverted = true)
      }
    }
    return taken
  }

  /** The inner path + spread on Obvid's route ([InnerPathSpread]); false leaves the build to the Skia route. */
  private fun buildInnerPathSpread(target: Bitmap, w: Int, h: Int, radius: Float, offsetX: Float, offsetY: Float): Boolean {
    val image = skia.intermediateBitmap
    val taken = innerSpread.fits(image, offsetX, offsetY)
    if (taken) {
      route = Route.Mask
      innerSpread.build(image, target, w, h, radius, offsetX, offsetY)
    }
    return taken
  }

  override fun close() {
    if (native.close()) onCloseForTest?.invoke()
    skia.close()
    mask.close()
    innerSpread.close()
  }
}
