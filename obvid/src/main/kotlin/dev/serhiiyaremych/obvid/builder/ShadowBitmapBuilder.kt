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
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.PaintingStyle
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.nativePaint
import dev.serhiiyaremych.obvid.NineMaskCache
import dev.serhiiyaremych.obvid.NineMaskKey
import dev.serhiiyaremych.obvid.ObvidNative
import dev.serhiiyaremych.obvid.ShadowExtent
import dev.serhiiyaremych.obvid.ShadowPrefetch
import dev.serhiiyaremych.obvid.ShadowStore
import dev.serhiiyaremych.obvid.TRACE_BLUR
import dev.serhiiyaremych.obvid.TRACE_NINE_PATCH
import dev.serhiiyaremych.obvid.TRACE_OUTLINE
import dev.serhiiyaremych.obvid.TRACE_SKIA
import dev.serhiiyaremych.obvid.traceSection
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
 *   frames are decided exactly, below), or is a path whose mask Skia forms otherwise ([buildMask]: an inverse fill, a
 *   spread stroke Skia draws as a hairline, a spread stroke that may be rects). The outline is
 *   drawn without a mask filter into a reused A8 mask bitmap at Skia's mask bounds (`compute_mask_bounds`), blurred by
 *   the NEON kernel and written into the bitmap at (mask origin − border) (`ObvidNative.builderBlur`). Path +
 *   spread: the fill and the `2·spread` stroke are separate masks, the stroke composited source-over.
 * - [Route.Skia]: `Outline.Rectangle`, a rect path, nested rects (except the frames below), the paths [buildMask]
 *   leaves to Skia, simple `Outline.Rounded` where Skia's decision is not Obvid's, and any outline at radius ≤ 0
 *   (Compose then sets no mask filter): Compose's own draw calls with its `Paint` configuration.
 * - A canonical frame ([ShadowBitmaps.isFrame]: two `addRect`-shaped contours) with radius > 0 and spread ≤ 0, by
 *   Skia's nested-rects test on its points moved by the translate ([nestedFrame]) and then Android 17's
 *   `filterRectsToNine` (`ObvidNative.builderPlanRects`):
 *   - [Route.NineRects]: Skia's frame nine-patch. The small frame is drawn into the mask bitmap, blurred by Obvid's
 *     kernel and stretched into the bitmap without its centre ([buildNineRects]).
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
 *     places ([buildNine]): published in [nineMasks] by a target's first build (shared by every target of the cache,
 *     drop and inner, never rewritten), and in the target's private buffer ([ShadowBitmaps.keptNineMask]) by a
 *     rebuild that finds neither (an animation: rewritten in place by its next miss, never published).
 *   - [Route.MaskRoundRect]: nothing to stretch, or an oval (`CircleShape` without spread); Skia blurs the whole mask
 *     of `SkPath::RRect(rrect)` or `SkPath::Oval(rect)`. The mask route with the shape drawn by the mask canvas's
 *     `drawRoundRect`, which builds that same path from the same floats.
 *   - Otherwise (a rect after `setRectXY`, or over Skia's limits): the Skia route.
 *   Spread adds no stroke here: Compose's round-rect overload draws the rect grown by spread.
 *
 * The build context (native handle with the kernel scratch, mask bitmap, canvases, paints, paths) is shared by every
 * shadow built with this builder; a [build] that fits allocates nothing (except a `BlurMaskFilter` when the Skia
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
 *   inverse fill (Skia's mask is the whole clip, [buildInnerPath]). Also a
 *   path, nested rects included, with spread > 0 and radius > 0: Compose's intermediate (the path eroded
 *   by a `Clear` stroke) is rasterized by Compose's own calls, and the mask Skia blurs for `drawImage` of it is formed
 *   by the newest Skia's placement rule ([buildInnerPathSpread]), unless Skia's rect-to-rect matrix for that
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

  private var handle: Long = ObvidNative.builderCreate()

  /** Test hook: called when [close] frees the native handle. */
  var onCloseForTest: (() -> Unit)? = null

  /** The route of the last [build]. */
  var route: Route = Route.Skia
    private set

  private val shadowCanvas = android.graphics.Canvas()
  private var canvasBitmap: Bitmap? = null
  private var canvasWidth = 0
  private var canvasHeight = 0
  private val composeCanvas = androidx.compose.ui.graphics.Canvas(shadowCanvas)

  private var mask: Bitmap? = null
  private val maskCanvas = android.graphics.Canvas()

  // Skia route: Compose's paint (`configureShadow`'s color and blend mode never change) and filter.
  private val paint =
    Paint().apply {
      color = Color.Black
      blendMode = BlendMode.SrcOver
    }
  private var blurFilter: BlurMaskFilter? = null
  private var blurFilterRadius = Float.NaN

  // Inner Skia route: Compose's renderer `Paint`, reset per build, and the path + spread intermediate.
  private val innerPaint = Paint()
  private var intermediate: Bitmap? = null
  private var intermediateImage: ImageBitmap? = null
  private val intermediateCanvas = android.graphics.Canvas()
  private val intermediateComposeCanvas = androidx.compose.ui.graphics.Canvas(intermediateCanvas)

  // Inner path + spread on Obvid's route: Skia's paint for the filtered `drawImage` of the intermediate, and the buffer
  // of exactly the cut rect's size it is drawn into (Skia's temporary mask).
  private val imagePaint =
    android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG or android.graphics.Paint.FILTER_BITMAP_FLAG)
  private var placed: Bitmap? = null
  private val placedCanvas = android.graphics.Canvas()

  // Mask route.
  private val maskPaint =
    android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
      color = android.graphics.Color.BLACK
      style = android.graphics.Paint.Style.FILL
    }
  private val strokePaint =
    android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
      style = android.graphics.Paint.Style.STROKE
    }
  private val devPath = android.graphics.Path()
  // Frame route: the canonical frame's points moved by the build's translate, the nested rects Skia finds in them
  // (outer, inner; LTRB) and the small frame's path.
  private val movedFrame = FloatArray(16)
  private val frameRects = FloatArray(8)
  private val framePath = android.graphics.Path()
  private val strokeFill = android.graphics.Path()
  // [strokeFill] as a Compose path, for [ShadowBitmaps.strokeMayBeRects]' iterator.
  private val strokeFillCompose = strokeFill.asComposePath()
  private val bounds = RectF()
  private var border = 0
  private var borderRadius = Float.NaN
  // [Route.MaskRoundRect]: the corner radii of the round rect [blurMask] draws when it has no path.
  // [buildNine]: whether this build's first build of a target may publish its mask (the current build's argument).
  private var mayPublish = true
  private var maskCornerX = 0f
  private var maskCornerY = 0f
  // [buildNine] with `inverted`: the packed hole of the bitmap it wrote (0: none).
  private var nineHole = 0L

  // The drop build's window ([setComposeWindow], [setFullWindow]): the translate before Compose's draw calls (whole px;
  // 0 for Compose's window, the canvas translate of a larger canvas for the full extent) and the bitmap's size, which
  // [prepareWindow] prepares in [windowTarget].
  private var fullBuild = false
  private var winX = 0f
  private var winY = 0f
  private var winW = 0
  private var winH = 0
  private var composeW = 0
  private var composeH = 0
  private var windowTarget: ShadowBitmaps? = null
  private var windowPreserve = false
  private val strokeBounds = RectF()
  // [strokeFill] holds this build's local stroke of the path (its bounds were needed before the route was decided).
  private var strokeReady = false
  private var fullMargin = 0
  private var fullMarginRadius = Float.NaN
  private var skiaMargin = 0
  private var skiaMarginRadius = Float.NaN

  /**
   * Rebuilds [target]'s shadow of [outline] (created for the layout [size]) with [radius] and [spread] in px, as
   * Compose computes them (`shadow.radius.toPx()`, `shadow.spread.toPx()`). With [preserveCurrent] the current bitmap
   * is left untouched and the shadow is written into the other buffer, which becomes current. A build off the
   * nine-patch route frees [target]'s private small round rect. Without [mayPublish] a first build into [target]
   * publishes no nine-patch mask ([buildNine]).
   *
   * With [full] ([ShadowExtent.Full]) the bitmap is the full-extent window instead of Compose's: Compose's draw calls
   * on a larger canvas, translated by whole pixels ([ShadowBitmaps.originX], [ShadowBitmaps.originY]: where the
   * bitmap starts in Compose's), cropped to the shape drawn (the outline grown by the spread, as Compose draws it)
   * grown by the margin past which the blur is 0: [ObvidNative.builderFullMargin] on Obvid's routes, the device Skia's
   * reach ([ObvidNative.builderSkiaReachMargin]) on [Route.Skia]. Every route decides and draws on that canvas's device
   * coordinates, as Skia would. A shape reaching past its layout bounds is kept up to that margin past Compose's window
   * (the bitmap is at most Compose's grown by the margin on each side). An inverse fill keeps Compose's window: it
   * covers the whole canvas, so no window holds all of its shadow.
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
    check(handle != 0L) { "closed" }
    this.mayPublish = mayPublish
    target.clearBands()
    try {
      buildRoutes(target, outline, size, radius, spread, preserveCurrent, full)
    } finally {
      windowTarget = null
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
    // An inverse fill covers the whole canvas Compose draws on, however large: no window holds all of its shadow, so it
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
    fullBuild = full
    composeW = cw
    composeH = ch
    windowTarget = target
    windowPreserve = preserveCurrent
    strokeReady = false

    // createOuterShadowBitmap(path): translated by radius + spread only for a positive spread.
    val t = if (spread > 0f) radius + spread else radius
    // Each sets the route when it takes the build, and prepares the bitmap ([prepareWindow]) once it has. A canonical
    // frame without spread (one fill draw) is decided by Skia's nested-rects test on the moved points; any other path
    // that may be nested rects is left to Skia, which blurs the fill draw with its rect nine-patch.
    if (path != null && radius > 0f) {
      val local = path.asAndroidPath()
      if (full) {
        pathDrawBounds(local, spread)
        setFullWindow(bounds, t, fullMargin(radius))
      } else {
        setComposeWindow()
      }
      val tx = moved(t, winX)
      val ty = moved(t, winY)
      val frame = if (spread <= 0f && target.isFrame) nestedFrame(target, tx, ty) else FRAME_UNKNOWN
      val native =
        when (frame) {
          FRAME_NESTED ->
            when (val plan = planFrame(radius)) {
              ObvidNative.PLAN_SKIA -> false
              ObvidNative.PLAN_RECTS_MASK -> buildMask(local, radius, spread, tx, ty, fromFrame = true)
              else -> buildNineRects(prepareWindow(), winW, winH, radius, plan, inverted = false)
            }
          FRAME_NOT_NESTED -> buildMask(local, radius, spread, tx, ty, fromFrame = true)
          else -> !target.nestedRects && buildMask(local, radius, spread, tx, ty)
        }
      if (native) return
    }
    if (path == null && radius > 0f && buildRoundRect(target, shadowWidth, shadowHeight, radius)) {
      return
    }
    route = Route.Skia
    if (full) {
      if (path != null) {
        pathDrawBounds(path.asAndroidPath(), spread)
        setFullWindow(bounds, t, skiaMargin(radius))
      } else {
        roundRectBounds(radius, shadowWidth, shadowHeight)
        setFullWindow(bounds, 0f, skiaMargin(radius))
      }
    } else {
      setComposeWindow()
    }
    buildSkia(prepareWindow(), path, target.cornerRadius, shadowWidth, shadowHeight, radius, spread, t)
  }

  // ---------------------------------------------------------------------------------------------------------------
  // The drop build's window: Compose's, or the full extent
  // ---------------------------------------------------------------------------------------------------------------

  /** Compose's window: no translate, Compose's bitmap size. */
  private fun setComposeWindow() {
    winX = 0f
    winY = 0f
    winW = composeW
    winH = composeH
  }

  /** [c] moved by the window's translate [p]: Skia's CTM `translate(p)` then Compose's translate by [c]. */
  private fun moved(c: Float, p: Float): Float = if (fullBuild) p + c else c

  /**
   * The full-extent window of a draw whose local bounds are [local], drawn under Compose's translate [t] (0 for the
   * round-rect draw), with [margin] px around it: per axis the whole-px translate and the size ([fullAxis]).
   */
  private fun setFullWindow(local: RectF, t: Float, margin: Int) {
    if (!(local.left.isFinite() && local.top.isFinite() && local.right.isFinite() && local.bottom.isFinite())) {
      setComposeWindow()
      return
    }
    winX = fullAxis(local.left, local.right, t, margin, composeW)
    winW = fullAxisSize
    winY = fullAxis(local.top, local.bottom, t, margin, composeH)
    winH = fullAxisSize
  }

  /** [fullAxis]' second result. */
  private var fullAxisSize = 0

  /**
   * One axis of [setFullWindow]: the translate p (whole px) such that the draw's first pixel, `floor(lo + (p + t))` on
   * the device (the coordinate moved as Skia moves it), lies [margin] px past the bitmap's start, and the bitmap's
   * size ([fullAxisSize]) so that its last pixel, `ceil(hi + (p + t)) − 1`, lies [margin] px before its end. In
   * Compose's bitmap (size [c]) the window stays within `[−margin, c + margin)`.
   */
  private fun fullAxis(lo: Float, hi: Float, t: Float, margin: Int, c: Int): Float {
    // The window's start in Compose's bitmap (p = −start).
    val start = (floor(lo + t).toInt() - margin).coerceIn(-margin, c + margin - 1)
    var p = -start
    // The device coordinate is rounded once more than lo + t: its floor can fall one short.
    while (p < margin && floor(lo + (p + t)).toInt() < margin) p++
    val end = min(ceil(hi + (p + t)).toInt() + margin, p + c + margin)
    fullAxisSize = max(end, 1)
    return p.toFloat()
  }

  /**
   * The local bounds of Compose's path draw into [bounds]: the path's, united with its `2·spread` stroke's for a
   * positive spread (the stroke is kept in [strokeFill] for [buildMask]). A stroke Skia draws as a hairline
   * ([hairlineSpread]) reaches pixels up to 1 px from the path instead (Skia draws it into a mask of the path's bounds
   * outset by ½, rounded out): the path's bounds outset by 1.
   */
  private fun pathDrawBounds(local: android.graphics.Path, spread: Float) {
    @Suppress("DEPRECATION") local.computeBounds(bounds, true)
    if (hairlineSpread(spread)) {
      bounds.inset(-1f, -1f)
    } else if (spread > 0f) {
      localStroke(local, spread)
      @Suppress("DEPRECATION") strokeFill.computeBounds(strokeBounds, true)
      bounds.union(strokeBounds)
    }
  }

  /** The bounds of Compose's round-rect draw (`createOuterShadowBitmap`'s rect, sorted) into [bounds]. */
  private fun roundRectBounds(radius: Float, shadowWidth: Float, shadowHeight: Float) {
    val r = shadowWidth - radius
    val b = shadowHeight - radius
    bounds.set(min(radius, r), min(radius, b), max(radius, r), max(radius, b))
  }

  /** [strokeFill] := Compose's stroke of [local] (`2·spread` wide) in local space, once per build. */
  private fun localStroke(local: android.graphics.Path, spread: Float) {
    if (strokeReady) return
    strokePaint.strokeWidth = spread * 2f
    strokeFill.rewind()
    @Suppress("DEPRECATION") strokePaint.getFillPath(local, strokeFill)
    strokeReady = true
  }

  /** The current build's bitmap, of the window's size, with the window recorded in it ([ShadowBitmaps.setWindow]). */
  private fun prepareWindow(): Bitmap {
    val target = checkNotNull(windowTarget)
    target.setWindow(-winX.toInt(), -winY.toInt(), composeW, composeH)
    return target.prepare(winW, winH, windowPreserve)
  }

  private fun fullMargin(radius: Float): Int {
    if (radius != fullMarginRadius) {
      fullMargin = ObvidNative.builderFullMargin(radius)
      fullMarginRadius = radius
    }
    return fullMargin
  }

  private fun skiaMargin(radius: Float): Int {
    if (radius != skiaMarginRadius) {
      skiaMargin = ObvidNative.builderSkiaReachMargin(radius)
      skiaMarginRadius = radius
    }
    return skiaMargin
  }

  /**
   * Rebuilds [target]'s inner shadow of [outline] (created for the layout [size]) with [radius], [spread] and the
   * offset ([offsetX], [offsetY]) in px, as Compose computes them (`shadow.radius.toPx()`, …), into the bitmap
   * Compose's `InnerShadowRenderer` allocates: `ceil(size)` per axis, grown by `ceil(radius)` on each side for a path
   * (the content is not inset by it). With [preserveCurrent] the current bitmap is left untouched and the shadow is
   * written into the other buffer, which becomes current. A build off the nine-patch route frees [target]'s private
   * small round rect. Without [mayPublish] a first build into [target] publishes no nine-patch mask ([buildNine]).
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
    check(handle != 0L) { "closed" }
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
    val image = if (path != null && spread > 0f) buildIntermediate(path, spread) else null
    val pad = if (path != null) ceil(radius).toInt() else 0
    val w = ceil(size.width).toInt() + pad * 2
    val h = ceil(size.height).toInt() + pad * 2
    require(w > 0 && h > 0) { "empty shadow bitmap ${w}x$h" }
    target.setWindow(0, 0, w, h)
    val bitmap = target.prepare(w, h, preserveCurrent)
    if (radius > 0f) {
      // Sets the route when it takes the build.
      val native =
        when {
          path == null -> buildInnerRoundRect(target, bitmap, size, w, h, radius, spread, offsetX, offsetY)
          image != null -> buildInnerPathSpread(bitmap, w, h, radius, offsetX, offsetY)
          // A canonical frame is decided by Skia's nested-rects test on the moved points; any other path that may be
          // nested rects is left to Skia, which blurs the path draw with its rect nine-patch.
          else ->
            when (if (target.isFrame) nestedFrame(target, offsetX, offsetY) else FRAME_UNKNOWN) {
              FRAME_NESTED ->
                when (val plan = planFrame(radius)) {
                  ObvidNative.PLAN_SKIA -> false
                  ObvidNative.PLAN_RECTS_MASK -> buildInnerPath(path, bitmap, w, h, radius, offsetX, offsetY, fromFrame = true)
                  else -> buildNineRects(bitmap, w, h, radius, plan, inverted = true)
                }
              FRAME_NOT_NESTED -> buildInnerPath(path, bitmap, w, h, radius, offsetX, offsetY, fromFrame = true)
              else -> !target.nestedRects && buildInnerPath(path, bitmap, w, h, radius, offsetX, offsetY)
            }
        }
      if (native) {
        // The nine-patch routes have written the inverted bitmap themselves; the round rect's also returned its hole.
        // The others' hole is grown from the component's centre moved by half the offset (the middle of the overlap of
        // the shape and the shape moved by the offset, for a symmetric shape). The frame's nine-patch gives none: its
        // 0s lie in the frame's band, not around a point known in advance.
        val hole =
          when (route) {
            Route.NinePatch -> nineHole
            Route.NineRects -> 0L
            else -> invertWithHole(bitmap, w, h, floor((size.width + offsetX) / 2f).toInt(), floor((size.height + offsetY) / 2f).toInt())
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
    buildInnerSkia(bitmap, path, image, target.cornerRadius, size, w, h, radius, spread, offsetX, offsetY)
  }

  override fun close() {
    if (handle != 0L) {
      ObvidNative.builderDestroy(handle)
      handle = 0L
      onCloseForTest?.invoke()
    }
    shadowCanvas.setBitmap(null)
    canvasBitmap = null
    maskCanvas.setBitmap(null)
    // The mask and the intermediate never leave the builder.
    mask?.recycle()
    mask = null
    intermediateCanvas.setBitmap(null)
    intermediate?.recycle()
    intermediate = null
    intermediateImage = null
    placedCanvas.setBitmap(null)
    placed?.recycle()
    placed = null
  }

  // ---------------------------------------------------------------------------------------------------------------
  // Mask route
  // ---------------------------------------------------------------------------------------------------------------

  /**
   * `createOuterShadowBitmap(path)` on Obvid's pipeline: Compose translates by its t and draws [local] blurred (then
   * stroked with spread > 0); Skia fills the path moved by the CTM's translate ([tx], [ty]: t, or the window's
   * translate plus t) in device space, through `filterPath`, unless the moved path is a rect (`countNestedRects`:
   * Skia's `BlurRect`, not Obvid's; nested rects reach this only where Skia's `filterRectsToNine` returns
   * unimplemented). Prepares and writes the bitmap and sets the route; false leaves the build to the Skia route.
   *
   * Also left to the Skia route, decided before anything is written, the paths whose masks Skia forms otherwise:
   * - an inverse fill: `DrawToMask` bounds it by the clip grown by the blur's margin, not by the path (its stroke is an
   *   inverse fill too);
   * - spread > 0 with a stroke Skia draws as a hairline ([hairlineSpread]): an anti-aliased hairline with the paint's
   *   alpha scaled by the width, not a fill of the stroke's outline;
   * - spread > 0 with a stroke that may be one rect or two nested rects for `countNestedRects`, which Skia blurs with
   *   `BlurRect` or its rect nine-patch: decided on the stroke itself ([localStroke]: the platform's stroker is Skia's,
   *   so the same degenerate curves reduce to lines), by [ShadowBitmaps.strokeMayBeRects], kept per outline and
   *   spread. A superset of Skia's test, which also takes the rects Skia blurs whole. A build with its outline and
   *   spread kept costs a fill-type read, a float compare and the stroke it draws anyway.
   *
   * The moved path is `local` copied and offset; with [fromFrame] (a canonical frame, spread ≤ 0) it is rebuilt from
   * the frame's moved points instead ([setMovedFrame]: the same verbs and points). On API 23–33 `android.graphics.Path`
   * keeps a shortcut for a path made only of same-direction `addRect` calls (`isSimplePath`, which `set` copies and a
   * whole-pixel `offset` keeps): `Canvas.drawPath` then fills the union of the int-truncated rects as a region, without
   * anti-aliasing. Other such paths still take it here; a rebuilt frame does not (`moveTo` clears it).
   */
  private fun buildMask(
    local: android.graphics.Path,
    radius: Float,
    spread: Float,
    tx: Float,
    ty: Float,
    fromFrame: Boolean = false,
  ): Boolean {
    if (local.isInverseFillType) return false
    if (spread > 0f && hairlineSpread(spread)) return false
    if (fromFrame) {
      setMovedFrame(local.fillType)
    } else {
      devPath.set(local)
      devPath.offset(tx, ty)
    }
    if (devPath.isRect(null)) return false
    if (spread > 0f) {
      // Second Compose draw: the same path stroked 2·spread wide. Skia strokes in local space (resScale 1 for a
      // translate), then translates by the CTM. Tested in local space: the translate keeps an axis-aligned edge so.
      localStroke(local, spread)
      if (checkNotNull(windowTarget).strokeMayBeRects(spread, strokeFillCompose)) return false
    }
    route = Route.Mask
    updateBorder(radius)
    val target = prepareWindow()
    blurPath(devPath, target, winW, winH, radius, MODE_REPLACE)
    if (spread > 0f) {
      strokeFill.offset(tx, ty)
      blurPath(strokeFill, target, winW, winH, radius, MODE_SRC_OVER)
    }
    return true
  }

  private fun updateBorder(radius: Float) {
    if (radius != borderRadius) {
      border = ObvidNative.builderBorder(radius)
      borderRadius = radius
    }
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
   * [bounds] with corner radii ([maskCornerX], [maskCornerY]).
   */
  private fun blurMask(devicePath: android.graphics.Path?, target: Bitmap, w: Int, h: Int, radius: Float, mode: Int) {
    val m = min(border, 128)
    val l = max(floor(bounds.left - 0.5f).toInt(), -m)
    val t = max(floor(bounds.top - 0.5f).toInt(), -m)
    val r = min(ceil(bounds.right + 0.5f).toInt(), w + m)
    val b = min(ceil(bounds.bottom + 0.5f).toInt(), h + m)
    val empty = l >= r || t >= b
    if (empty && mode == MODE_SRC_OVER) return
    val mw = if (empty) 0 else r - l
    val mh = if (empty) 0 else b - t
    val maskBitmap = ensureMask(max(mw, 1), max(mh, 1))
    if (!empty) {
      traceSection(TRACE_OUTLINE) {
        maskCanvas.save()
        maskCanvas.clipRect(0, 0, mw, mh)
        maskCanvas.drawColor(0, PorterDuff.Mode.CLEAR)
        maskCanvas.translate(-l.toFloat(), -t.toFloat())
        if (devicePath != null) {
          maskCanvas.drawPath(devicePath, maskPaint)
        } else {
          maskCanvas.drawRoundRect(bounds, maskCornerX, maskCornerY, maskPaint)
        }
        maskCanvas.restore()
      }
    }
    // The bitmap is exactly the used size: the clear rect is the used rect.
    val status =
      traceSection(TRACE_BLUR) {
        ObvidNative.builderBlur(handle, maskBitmap, mw, mh, l, t, radius, target, w, h, w, h, mode)
      }
    check(status == 0) { "builderBlur failed: $status" }
  }

  /** The mask is read as a window with its own stride: it grows per axis, never reconfigured. */
  private fun ensureMask(w: Int, h: Int): Bitmap {
    val current = mask
    if (current != null && current.width >= w && current.height >= h) return current
    val next = newA8(max(w, current?.width ?: 0), max(h, current?.height ?: 0))
    maskCanvas.setBitmap(next)
    // setBitmap takes the bitmap's density; with none, drawBitmap never scales by density.
    maskCanvas.density = Bitmap.DENSITY_NONE
    current?.recycle()
    mask = next
    return next
  }

  // ---------------------------------------------------------------------------------------------------------------
  // Round rect on Obvid's pipeline: Skia's nine-patch or its full-mask fallback
  // ---------------------------------------------------------------------------------------------------------------

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
    if (fullBuild) {
      roundRectBounds(radius, shadowWidth, shadowHeight)
      setFullWindow(bounds, 0f, fullMargin(radius))
    } else {
      setComposeWindow()
    }
    val l = moved(radius, winX)
    val t = moved(radius, winY)
    val r = moved(shadowWidth - radius, winX)
    val b = moved(shadowHeight - radius, winY)
    val w = winW
    val h = winH
    val plan = ObvidNative.builderPlanRoundRect(ObvidNative.SKIA_CLASS_B, l, t, r, b, rx, ry, radius)
    when (plan) {
      ObvidNative.PLAN_SKIA -> return false
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
        updateBorder(radius)
        bounds.set(l, t, r, b)
        maskCornerX = rx
        maskCornerY = ry
        blurMask(null, prepareWindow(), w, h, radius, MODE_REPLACE)
      }
      else -> {
        route = Route.NinePatch
        buildNine(bitmaps, prepareWindow(), w, h, radius, l, t, r, b, rx, ry, plan, inverted = false)
      }
    }
    return true
  }

  /**
   * The nine-patch of [plan]. A kept blurred small round rect of this plan, corner radii and radius ([NineMaskKey]) is
   * only stretched into [target] again: on a rebuild ([ShadowBitmaps.isRebuild]) [bitmaps]' private one first, then on
   * any build one published in [nineMasks] (whose last use moves to now). Otherwise the small round rect
   * `(0, 0, rw, rh)` of the plan with the device radii (the given ones: a nine-patch's are never scaled),
   * anti-aliased, no mask filter, is drawn at the mask's origin as Skia's `draw_rrect_into_mask` draws it into its
   * `roundOut()` (`ceil(rw) × ceil(rh)`), then blurred and stretched into [target] natively:
   * - a rebuild blurs into [bitmaps]' private buffer (`builderNineKeep`; rewritten in place, grown only when too small,
   *   never published), so an animation allocates nothing per frame;
   * - a first build blurs into a new mask published in [nineMasks] (`builderNineKeep`) when the caller allows it
   *   ([mayPublish]: a node's first appearance) and the cache keeps masks ([NineMaskCache.canPublish]), else through
   *   the native builder's own buffer (`builderNine`, nothing kept, nothing allocated): a new entry forked by a node
   *   that animates away from a shared entry is forked again on its next frame, so neither publishing nor a private
   *   buffer would be used twice.
   * With [inverted] (an inner shadow) every value v of [target] is written as `255 − v` by the `…InvertedHole` twin
   * of each call, which also returns the bitmap's hole ([nineHole]); a kept mask holds the same blurred small round
   * rect either way, so drop and inner shadows share it.
   */
  private fun buildNine(
    bitmaps: ShadowBitmaps,
    target: Bitmap,
    w: Int,
    h: Int,
    radius: Float,
    l: Float,
    t: Float,
    r: Float,
    b: Float,
    rx: Float,
    ry: Float,
    plan: Long,
    inverted: Boolean,
  ) {
    traceSection(TRACE_NINE_PATCH) {
      val masks = nineMasks
      val rebuild = bitmaps.isRebuild
      val own = if (rebuild) bitmaps.keptNineMask(plan, rx, ry, radius) else null
      val published = if (own == null) masks.find(plan, rx, ry, radius) else null
      val kept = own ?: published?.bitmap
      nineHole = 0L
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
          nineHole =
            ObvidNative.builderNinePlaceInvertedHole(
              kept, maskHole, ObvidNative.SKIA_CLASS_B, l, t, r, b, rx, ry, radius, target, w, h, w, h,
            )
          check(nineHole >= 0L) { "builderNinePlaceInvertedHole failed: $nineHole" }
        } else {
          val status = ObvidNative.builderNinePlace(kept, ObvidNative.SKIA_CLASS_B, l, t, r, b, rx, ry, radius, target, w, h, w, h)
          check(status == 0) { "builderNinePlace failed: $status" }
        }
        masks.countReuse(bitmaps, shared = published != null)
        return
      }
      val rw = ObvidNative.planRectWidth(plan)
      val rh = ObvidNative.planRectHeight(plan)
      val sw = ceil(rw).toInt()
      val sh = ceil(rh).toInt()
      val maskBitmap = ensureMask(sw, sh)
      maskCanvas.save()
      maskCanvas.clipRect(0, 0, sw, sh)
      maskCanvas.drawColor(0, PorterDuff.Mode.CLEAR)
      maskCanvas.drawRoundRect(0f, 0f, rw, rh, rx, ry, maskPaint)
      maskCanvas.restore()
      // The plan's border is the radius' border.
      updateBorder(radius)
      val kw = sw + 2 * border
      val kh = sh + 2 * border
      if (rebuild) {
        val allocations = bitmaps.nineAllocationCount
        val keep = bitmaps.nineMaskFor(kw, kh)
        if (bitmaps.nineAllocationCount != allocations) masks.countAllocation()
        nineKeep(maskBitmap, keep, l, t, r, b, rx, ry, radius, target, w, h, inverted)
        bitmaps.keepNine(plan, rx, ry, radius)
      } else if (mayPublish && masks.canPublish(kw, kh)) {
        val mask = masks.allocate(kw, kh)
        try {
          nineKeep(maskBitmap, mask.bitmap, l, t, r, b, rx, ry, radius, target, w, h, inverted)
        } catch (e: Throwable) {
          mask.bitmap.recycle()
          throw e
        }
        masks.publish(mask, plan, rx, ry, radius)
      } else if (inverted) {
        // The bitmap is exactly the used size: the clear rect is the used rect.
        nineHole =
          ObvidNative.builderNineInvertedHole(handle, maskBitmap, ObvidNative.SKIA_CLASS_B, l, t, r, b, rx, ry, radius, target, w, h, w, h)
        check(nineHole >= 0L) { "builderNineInvertedHole failed: $nineHole" }
      } else {
        val status = ObvidNative.builderNine(handle, maskBitmap, ObvidNative.SKIA_CLASS_B, l, t, r, b, rx, ry, radius, target, w, h, w, h)
        check(status == 0) { "builderNine failed: $status" }
      }
      masks.countBlur(bitmaps)
    }
  }

  /**
   * `builderNineKeep` (or with [inverted] `builderNineKeepInvertedHole`, which sets [nineHole]) of the small round
   * rect in [maskBitmap], kept in [keep].
   */
  private fun nineKeep(
    maskBitmap: Bitmap,
    keep: Bitmap,
    l: Float,
    t: Float,
    r: Float,
    b: Float,
    rx: Float,
    ry: Float,
    radius: Float,
    target: Bitmap,
    w: Int,
    h: Int,
    inverted: Boolean,
  ) {
    // The bitmap is exactly the used size: the clear rect is the used rect.
    if (inverted) {
      nineHole =
        ObvidNative.builderNineKeepInvertedHole(
          handle, maskBitmap, keep, ObvidNative.SKIA_CLASS_B, l, t, r, b, rx, ry, radius, target, w, h, w, h,
        )
      check(nineHole >= 0L) { "builderNineKeepInvertedHole failed: $nineHole" }
    } else {
      val status =
        ObvidNative.builderNineKeep(handle, maskBitmap, keep, ObvidNative.SKIA_CLASS_B, l, t, r, b, rx, ry, radius, target, w, h, w, h)
      check(status == 0) { "builderNineKeep failed: $status" }
    }
  }

  // ---------------------------------------------------------------------------------------------------------------
  // Frames on Obvid's pipeline: Skia's rect nine-patch of two nested rects
  // ---------------------------------------------------------------------------------------------------------------

  /**
   * Skia's nested-rects test (`SkPathPriv::IsNestedFillRects`) on [bitmaps]' canonical frame ([ShadowBitmaps.isFrame])
   * moved by Compose's translate ([tx], [ty]): each point `p + t` in float, as the CTM maps it.
   *
   * Skia runs `IsRectContour` on the whole path for the first contour (`trivial_rect` needs exactly one contour, so its
   * general loop runs) and on the rest for the second (`trivial_rect` first). For a contour of move, three lines and a
   * close whose side vectors pass `trivial_rect`'s test (finite, the first with exactly one zero component, each next
   * one orthogonal to it in the other dimension), both give the same answer: four non-zero sides alternating
   * horizontal and vertical close a rect with opposite sides in opposite directions, which the general loop accepts
   * with its corners at the second and fourth point (`bounds(firstCorner, thirdCorner)`), the same rect as
   * `trivial_rect`'s sorted first and third point. Then `SkRect::contains` either way, the first contour first.
   * - [FRAME_NESTED]: both contours pass and one contains the other; [frameRects] holds the outer and the inner rect.
   * - [FRAME_NOT_NESTED]: both pass, neither contains the other: Skia's test fails, and `isRect` too (a second contour
   *   after a closed rect is rejected), so Skia blurs the path's whole mask (the mask route).
   * - [FRAME_UNKNOWN]: a point is not finite or a contour fails `trivial_rect`'s test (a zero-length side, collinear
   *   points, an edge off axis). Skia's general loop may still take such a contour for a rect, so the build decides as
   *   for any other path ([ShadowBitmaps.nestedRects]).
   */
  private fun nestedFrame(bitmaps: ShadowBitmaps, tx: Float, ty: Float): Int {
    val p = bitmaps.framePoints
    val m = movedFrame
    for (i in 0 until 8) {
      m[2 * i] = p[2 * i] + tx
      m[2 * i + 1] = p[2 * i + 1] + ty
      if (!m[2 * i].isFinite() || !m[2 * i + 1].isFinite()) return FRAME_UNKNOWN
    }
    if (!trivialRect(m, 0) || !trivialRect(m, 8)) return FRAME_UNKNOWN
    // First contour: the general loop's bounds of its second and fourth point (SkRect::set: std::min / std::max).
    val al = skMin(m[2], m[6])
    val at = skMin(m[3], m[7])
    val ar = skMax(m[2], m[6])
    val ab = skMax(m[3], m[7])
    // Second contour: trivial_rect's MakeLTRB(first point, third point).makeSorted().
    val bl = skMin(m[8], m[12])
    val bt = skMin(m[9], m[13])
    val br = skMax(m[8], m[12])
    val bb = skMax(m[9], m[13])
    val r = frameRects
    when {
      contains(al, at, ar, ab, bl, bt, br, bb) -> r.setRects(al, at, ar, ab, bl, bt, br, bb)
      contains(bl, bt, br, bb, al, at, ar, ab) -> r.setRects(bl, bt, br, bb, al, at, ar, ab)
      else -> return FRAME_NOT_NESTED
    }
    return FRAME_NESTED
  }

  /**
   * [devPath] := the canonical frame moved by the last [nestedFrame] call ([movedFrame]: each point `p + t` in float),
   * as move, three lines and close per contour, with [fillType]: the verbs and points `set` and `offset` give the
   * source path, built without `addRect` or `set` so that the platform's same-direction-rects shortcut (API 23–33,
   * [buildMask]) cannot apply.
   */
  private fun setMovedFrame(fillType: android.graphics.Path.FillType) {
    val m = movedFrame
    devPath.rewind()
    devPath.addContour(m, 0)
    devPath.addContour(m, 8)
    devPath.fillType = fillType
  }

  /** `ObvidNative.builderPlanRects` for [frameRects] blurred with [radius]. */
  private fun planFrame(radius: Float): Long {
    val r = frameRects
    return ObvidNative.builderPlanRects(r[0], r[1], r[2], r[3], r[4], r[5], r[6], r[7], radius)
  }

  /**
   * Skia's frame nine-patch (`filterRectsToNine` with two rects, then `draw_nine` without the centre) of [frameRects]
   * for a nine-patch [plan]: the small frame `smallR0 = (o.l, o.t, o.r − dx, o.b − dy)` minus `smallR1` (the inner rect
   * likewise), even-odd, anti-aliased, no mask filter, is drawn into the mask bitmap's `roundOut(smallR0)` window under
   * the translate `(−floor(o.l), −floor(o.t))`, as `draw_rects_into_mask` draws it (two contours of `addRect`'s
   * clockwise points from the top-left, `(l, t) (r, t) (r, b) (l, b)`, written as move, three lines and close: an
   * `addRect` path would take the platform's rect-region shortcut on API 23–33, see [buildMask]; the same float
   * translate, the mask rect as clip), then blurred and stretched into [target] natively
   * (`ObvidNative.builderNineRects`). With [inverted] (an inner shadow) every value v is written as `255 − v`.
   * Sets the route; always true.
   */
  private fun buildNineRects(target: Bitmap, w: Int, h: Int, radius: Float, plan: Long, inverted: Boolean): Boolean {
    route = Route.NineRects
    traceSection(TRACE_NINE_PATCH) {
      val r = frameRects
      val dx = ObvidNative.planRectsDx(plan).toFloat()
      val dy = ObvidNative.planRectsDy(plan).toFloat()
      val right0 = r[2] - dx
      val bottom0 = r[3] - dy
      val maskLeft = floor(r[0]).toInt()
      val maskTop = floor(r[1]).toInt()
      val sw = ceil(right0).toInt() - maskLeft
      val sh = ceil(bottom0).toInt() - maskTop
      val maskBitmap = ensureMask(sw, sh)
      framePath.rewind()
      framePath.addClockwiseRect(r[0], r[1], right0, bottom0)
      framePath.addClockwiseRect(r[4], r[5], r[6] - dx, r[7] - dy)
      framePath.fillType = android.graphics.Path.FillType.EVEN_ODD
      maskCanvas.save()
      maskCanvas.clipRect(0, 0, sw, sh)
      maskCanvas.drawColor(0, PorterDuff.Mode.CLEAR)
      maskCanvas.translate(-maskLeft.toFloat(), -maskTop.toFloat())
      maskCanvas.drawPath(framePath, maskPaint)
      maskCanvas.restore()
      // The bitmap is exactly the used size: the clear rect is the used rect.
      val status =
        ObvidNative.builderNineRects(handle, maskBitmap, r[0], r[1], r[2], r[3], r[4], r[5], r[6], r[7], radius, target, w, h, w, h, inverted)
      check(status == 0) { "builderNineRects failed: $status" }
      return true
    }
  }

  // ---------------------------------------------------------------------------------------------------------------
  // Inner shadow on Obvid's pipeline: B, then the inversion
  // ---------------------------------------------------------------------------------------------------------------

  /**
   * `createInnerPathShadowBrush` without spread: Compose translates by the offset and draws the path blurred; Skia
   * fills the path moved by that translate in device space, through `filterPath` (as the drop shadow's mask route),
   * unless the moved path is a rect (`countNestedRects`: Skia's `BlurRect`, not Obvid's; nested rects reach this only
   * where Skia's `filterRectsToNine` returns unimplemented), or the path is an inverse fill (`DrawToMask` bounds its mask
   * by the clip grown by the blur's margin, not by the path).
   * Writes B and sets the route; false leaves the build to the Skia route. With [fromFrame] (a canonical frame) the
   * moved path is rebuilt from the frame's moved points, as in [buildMask] (which explains the platform shortcut this
   * avoids on API 23–33).
   */
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
    if (path.asAndroidPath().isInverseFillType) return false
    if (fromFrame) {
      setMovedFrame(path.asAndroidPath().fillType)
    } else {
      devPath.set(path.asAndroidPath())
      devPath.offset(offsetX, offsetY)
    }
    if (devPath.isRect(null)) return false
    route = Route.Mask
    updateBorder(radius)
    blurPath(devPath, target, w, h, radius, MODE_REPLACE)
    return true
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
    when (plan) {
      ObvidNative.PLAN_SKIA -> return false
      ObvidNative.PLAN_FULL_MASK -> {
        // Skia's full mask of SkPath::RRect(rrect) or SkPath::Oval(rect), as in buildRoundRect; the mask bounds are
        // clipped to the bitmap grown by min(border, 128), so a rect moved partly or wholly off the bitmap is cut the
        // way Skia cuts it.
        route = Route.MaskRoundRect
        updateBorder(radius)
        bounds.set(l, t, r, b)
        maskCornerX = rx
        maskCornerY = ry
        blurMask(null, target, w, h, radius, MODE_REPLACE)
      }
      else -> {
        // draw_nine clips each piece to the device clip (the bitmap): write_nine's clip to the used rect. Written
        // inverted (255 − B), so buildInner skips its separate inversion for this route.
        route = Route.NinePatch
        buildNine(bitmaps, target, w, h, radius, l, t, r, b, rx, ry, plan, inverted = true)
      }
    }
    return true
  }

  /**
   * `createInnerPathShadowBrush` with spread > 0 (radius > 0) on Obvid's pipeline. Compose draws the intermediate
   * ([buildIntermediate], already rasterized) with `drawImage(…, offset, blur + Xor)` over the opaque bitmap; Skia's
   * `drawImage` of an A8 image with a mask filter is `drawBitmapAsMask` (the image's alpha is the blur source), so B is
   * the blur of the mask Skia forms from the intermediate at the offset, by the newest Skia's rule on every API level:
   * - clipped out: the intermediate's rounded-out device rect misses the bitmap; B is empty;
   * - sprite ([treatAsSprite]): the intermediate itself at (round(ox), round(oy)), not clipped before the blur;
   * - filtered: Skia draws the intermediate at the offset, bilinear, into a temporary mask of exactly the size of the
   *   rounded mapped bounds clipped to the bitmap (the cut rect), translated by minus its top-left. The platform's
   *   `drawBitmap` with that paint draws it here the same way: into a reused A8 buffer reconfigured to exactly the cut
   *   rect's size ([ensurePlaced]), cleared, under `translate(−l, −t)`, at the offset; the blur reads that buffer with
   *   its origin at the cut rect's top-left. Drawing at device coordinates into a larger bitmap clipped to the cut rect
   *   instead can differ by 1 (seen in a one-pixel-wide cut rect).
   * Skia's `drawImage` is a `drawImageRect` into `(ox, oy, ox + iw, oy + ih)`: when the float sum `ox + iw` minus `ox`
   * is not `iw` (or the same in y), its rect-to-rect matrix scales, and Skia fills that rect with the intermediate as
   * a shader under the mask filter (the blurred rect times the intermediate), not `drawBitmapAsMask`. That draw is left
   * to the Skia route (false).
   * Writes B (replace mode, so the rest of the bitmap is zeroed) and sets the route.
   */
  private fun buildInnerPathSpread(target: Bitmap, w: Int, h: Int, radius: Float, offsetX: Float, offsetY: Float): Boolean {
    val image = checkNotNull(intermediate)
    val iw = image.width
    val ih = image.height
    if ((offsetX + iw) - offsetX != iw.toFloat() || (offsetY + ih) - offsetY != ih.toFloat()) return false
    route = Route.Mask
    // The intermediate's device rect: SkMatrix::mapRect of the translate.
    val left = offsetX
    val top = offsetY
    val right = offsetX + iw
    val bottom = offsetY + ih
    val clippedOut = !(floor(left) < w && ceil(right) > 0 && floor(top) < h && ceil(bottom) > 0)
    if (clippedOut) {
      blurInto(image, 0, 0, 0, 0, 0, 0, target, w, h, radius)
      return true
    }
    if (treatAsSprite(left, top, right, bottom, iw, ih)) {
      blurInto(image, 0, 0, iw, ih, skRound(left), skRound(top), target, w, h, radius)
      return true
    }
    val l = max(skRound(left), 0)
    val t = max(skRound(top), 0)
    val r = min(skRound(right), w)
    val b = min(skRound(bottom), h)
    if (l >= r || t >= b) {
      blurInto(image, 0, 0, 0, 0, 0, 0, target, w, h, radius)
      return true
    }
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
    return true
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
        ObvidNative.builderBlurFrom(handle, source, srcX, srcY, mw, mh, x, y, radius, target, w, h, w, h, MODE_REPLACE)
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

  /**
   * `255 − v` over the whole bitmap (it is exactly the used size): the opaque `Xor` rect Compose draws last. Returns
   * the packed hole of the result grown from ([seedX], [seedY]) (0: none).
   */
  private fun invertWithHole(target: Bitmap, w: Int, h: Int, seedX: Int, seedY: Int): Long {
    val hole = ObvidNative.builderInvertHole(handle, target, w, h, seedX, seedY)
    check(hole >= 0L) { "builderInvertHole failed: $hole" }
    return hole
  }

  // ---------------------------------------------------------------------------------------------------------------
  // Inner Skia route: InnerShadowRenderer's draw calls
  // ---------------------------------------------------------------------------------------------------------------

  /**
   * `createInnerPathShadowBrush`'s intermediate for a positive [spread]: an A8 bitmap of `ceil(bounds)` of the path
   * (the path at its own coordinates, not moved to the origin), the path filled, then, clipped to the bounds' size,
   * stroked `2·spread` wide with `Clear` (eroded by the spread). Compose's calls with its renderer `Paint`, reset to a
   * fresh one's state first; the bitmap is reused (exactly this size, cleared), so it is read whole as Compose's.
   */
  private fun buildIntermediate(path: Path, spread: Float): ImageBitmap {
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
  private fun buildInnerSkia(
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

  // ---------------------------------------------------------------------------------------------------------------
  // Skia route: createOuterShadowBitmap's draw calls
  // ---------------------------------------------------------------------------------------------------------------

  /**
   * Compose's draw calls into [target] (the window's size), after the window's translate for the full extent (the
   * canvas translate of a larger canvas).
   */
  private fun buildSkia(
    target: Bitmap,
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
      shadowCanvas.clipRect(0, 0, winW, winH)
      shadowCanvas.drawColor(0, PorterDuff.Mode.CLEAR)
      if (fullBuild) shadowCanvas.translate(winX, winY)
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

  private companion object {
    const val MODE_REPLACE = 0
    const val MODE_SRC_OVER = 1

    /** [nestedFrame]'s answers. */
    const val FRAME_UNKNOWN = 0
    const val FRAME_NESTED = 1
    const val FRAME_NOT_NESTED = 2
  }
}
