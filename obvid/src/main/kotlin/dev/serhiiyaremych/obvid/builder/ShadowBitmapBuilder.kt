// Copyright 2026, Serhii Yaremych (desugar-64) and the Obvid project contributors
// SPDX-License-Identifier: Apache-2.0

package dev.serhiiyaremych.obvid.builder

import android.graphics.Bitmap
import android.graphics.RectF
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import dev.serhiiyaremych.obvid.NineMaskCache
import dev.serhiiyaremych.obvid.ObvidNative
import dev.serhiiyaremych.obvid.ShadowExtent
import dev.serhiiyaremych.obvid.ShadowPrefetch
import dev.serhiiyaremych.obvid.ShadowStore
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Builds the A8 bitmaps of Compose's drop shadow ([build]) and inner shadow ([buildInner]) into a [ShadowBitmaps], with
 * the same pixels as Compose on Android 16–17. Each build picks a [Route]; a build that fits allocates nothing. An inner
 * build writes B, the blurred shape, then inverts it to `255 − B`.
 *
 * Not thread-safe: one builder per [ShadowStore]. [close] frees the native handle and the reused bitmaps.
 */
internal class ShadowBitmapBuilder(nineMasks: NineMaskCache = NineMaskCache()) : AutoCloseable {
  /** The published masks the next builds use; a worker's builder takes each job's own ([ShadowPrefetch.masks]). */
  var nineMasks: NineMaskCache = nineMasks

  enum class Route {
    /** A path drawn into a mask and blurred natively ([MaskRoute]). */
    Mask,

    /** Compose's own draw calls ([SkiaRoute]). */
    Skia,

    /** A simple round rect blurred small and stretched ([NinePatchRoute]). */
    NinePatch,

    /** A simple round rect drawn whole into a mask and blurred natively ([MaskRoute]). */
    MaskRoundRect,

    /** A frame of two nested rects blurred small and stretched ([NineRectsRoute]). */
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

  // A simple round rect's device rect on Obvid's routes, as Compose's `drawRoundRect` gives it (not sorted).
  private val roundRect = RectF()

  // Whether the current build may publish its nine-patch mask.
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
   * Rebuilds [target]'s drop shadow of [outline] for the layout [size], with [radius] and [spread] in px. With
   * [preserveCurrent] the shadow goes into the other buffer, which becomes current. Without [mayPublish] the build
   * publishes no nine-patch mask. With [full] ([ShadowExtent.Full]) the bitmap holds the whole shadow, not only
   * Compose's area.
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
    // An inverse fill keeps Compose's area.
    val full = fullExtent && !(path != null && path.asAndroidPath().isInverseFillType)

    // Compose's bitmap size; an empty bitmap throws, as in Compose.
    val outset = radius * 2 + spread * 2
    val shadowWidth = size.width + outset
    val shadowHeight = size.height + outset
    val cw = ceil(shadowWidth).toInt()
    val ch = ceil(shadowHeight).toInt()
    require(cw > 0 && ch > 0) { "empty shadow bitmap ${cw}x$ch" }
    area.begin(target, full, IntSize(cw, ch), preserveCurrent)
    stroke.reset()

    // createOuterShadowBitmap(path): translated by radius + spread only for a positive spread.
    val t = if (spread > 0f) radius + spread else radius
    // Each sets the route when it takes the build, and prepares the bitmap ([DropArea.prepare]) once it has.
    val taken =
      when {
        !(radius > 0f) -> false
        path != null -> buildPath(target, path.asAndroidPath(), radius, spread, t, full)
        else -> buildRoundRect(target, shadowWidth, shadowHeight, radius)
      }
    if (!taken) {
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
  }

  /** The drop shadow of [local] on Obvid's routes, under Compose's translate [t]; true when one takes it. */
  private fun buildPath(
    target: ShadowBitmaps,
    local: android.graphics.Path,
    radius: Float,
    spread: Float,
    t: Float,
    full: Boolean,
  ): Boolean {
    if (full) {
      stroke.drawBounds(local, spread, bounds)
      area.setFull(bounds, t, native.fullMargin(radius))
    } else {
      area.setCompose()
    }
    val translate = Offset(area.moved(t, area.translate.x), area.moved(t, area.translate.y))
    val frame =
      if (spread <= 0f && target.isFrame) nested.test(target.framePoints, translate) else NestedRects.Answer.Unknown
    return when (frame) {
      NestedRects.Answer.Nested ->
        when (val plan = nested.plan(radius)) {
          ObvidNative.PLAN_SKIA -> false
          ObvidNative.PLAN_RECTS_MASK -> buildMask(local, radius, spread, translate, fromFrame = true)
          else -> buildNineRects(area.prepare(), area.size.width, area.size.height, radius, plan, inverted = false)
        }
      NestedRects.Answer.NotNested -> buildMask(local, radius, spread, translate, fromFrame = true)
      else -> !target.nestedRects && buildMask(local, radius, spread, translate)
    }
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
    translate: Offset,
    fromFrame: Boolean = false,
  ): Boolean {
    val taken = maskRoute.setDropPath(local, spread, translate, fromFrame)
    if (taken) {
      route = Route.Mask
      maskRoute.drawDrop(radius, spread, translate)
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
   * The drop shadow of a simple round rect on [Route.NinePatch] or [Route.MaskRoundRect], when Skia's plan picks one;
   * false otherwise.
   */
  private fun buildRoundRect(bitmaps: ShadowBitmaps, shadowWidth: Float, shadowHeight: Float, radius: Float): Boolean {
    val corner = bitmaps.cornerRadius
    if (!(corner.x > 0f && corner.y > 0f)) return false // drawRoundRect draws a rect
    // Compose's drawRoundRect rect, moved by the full extent's translate.
    if (area.full) {
      roundRectBounds(radius, shadowWidth, shadowHeight)
      area.setFull(bounds, 0f, native.fullMargin(radius))
    } else {
      area.setCompose()
    }
    val l = area.moved(radius, area.translate.x)
    val t = area.moved(radius, area.translate.y)
    val r = area.moved(shadowWidth - radius, area.translate.x)
    val b = area.moved(shadowHeight - radius, area.translate.y)
    val w = area.size.width
    val h = area.size.height
    val plan = ObvidNative.builderPlanRoundRect(ObvidNative.SKIA_CLASS_B, l, t, r, b, corner.x, corner.y, radius)
    roundRect.set(l, t, r, b)
    val taken = plan != ObvidNative.PLAN_SKIA
    when (plan) {
      ObvidNative.PLAN_SKIA -> Unit
      ObvidNative.PLAN_FULL_MASK -> {
        route = Route.MaskRoundRect
        // Skia blurs the whole round rect (or oval); the mask canvas draws the same shape.
        maskRoute.setRoundRect(roundRect, corner, radius)
        maskRoute.blurRoundRect(area.prepare(), w, h, radius)
      }
      else -> {
        route = Route.NinePatch
        val target = area.prepare()
        ninePatch.build(bitmaps, nineMasks, mayPublish, target, w, h, radius, roundRect, corner, plan, inverted = false)
      }
    }
    return taken
  }

  /**
   * Rebuilds [target]'s inner shadow of [outline] for the layout [size], with [radius], [spread] and [offset] in px.
   * [preserveCurrent] and [mayPublish] as in [build].
   */
  fun buildInner(
    target: ShadowBitmaps,
    outline: Outline,
    size: Size,
    radius: Float,
    spread: Float,
    offset: Offset,
    preserveCurrent: Boolean = false,
    mayPublish: Boolean = true,
  ) {
    check(native.isOpen) { "closed" }
    this.mayPublish = mayPublish
    target.clearBands()
    buildInnerRoutes(target, outline, size, radius, spread, offset, preserveCurrent)
    if (route != Route.NinePatch) target.releaseNineMask()
  }

  private fun buildInnerRoutes(
    target: ShadowBitmaps,
    outline: Outline,
    size: Size,
    radius: Float,
    spread: Float,
    offset: Offset,
    preserveCurrent: Boolean,
  ) {
    target.setOutline(outline)
    val path = target.path
    // The spread intermediate first, then the bitmap; an empty one throws, in Compose's order.
    val image = if (path != null && spread > 0f) skia.buildIntermediate(path, spread) else null
    val pad = if (path != null) ceil(radius).toInt() else 0
    val w = ceil(size.width).toInt() + pad * 2
    val h = ceil(size.height).toInt() + pad * 2
    require(w > 0 && h > 0) { "empty shadow bitmap ${w}x$h" }
    target.setWindow(IntOffset.Zero, IntSize(w, h))
    val bitmap = target.prepare(w, h, preserveCurrent)
    // Sets the route when it takes the build.
    val taken =
      radius > 0f && buildInnerNative(target, path, image != null, bitmap, size, w, h, radius, spread, offset)
    if (taken) {
      invertAndBand(target, path, bitmap, size, w, h, offset)
    } else {
      route = Route.Skia
      skia.buildInner(bitmap, path, image, target.cornerRadius, size, w, h, radius, spread, offset)
    }
  }

  /** The inner shadow on Obvid's routes, written into [bitmap]; true when one takes it. */
  private fun buildInnerNative(
    target: ShadowBitmaps,
    path: Path?,
    spreadImage: Boolean,
    bitmap: Bitmap,
    size: Size,
    w: Int,
    h: Int,
    radius: Float,
    spread: Float,
    offset: Offset,
  ): Boolean =
    when {
      path == null -> buildInnerRoundRect(target, bitmap, size, w, h, radius, spread, offset)
      spreadImage -> buildInnerPathSpread(bitmap, w, h, radius, offset)
      // A frame is decided on its moved points; another path that may be nested rects goes to Skia.
      else ->
        when (if (target.isFrame) nested.test(target.framePoints, offset) else NestedRects.Answer.Unknown) {
          NestedRects.Answer.Nested ->
            when (val plan = nested.plan(radius)) {
              ObvidNative.PLAN_SKIA -> false
              ObvidNative.PLAN_RECTS_MASK -> buildInnerPath(path, bitmap, w, h, radius, offset, fromFrame = true)
              else -> buildNineRects(bitmap, w, h, radius, plan, inverted = true)
            }
          NestedRects.Answer.NotNested -> buildInnerPath(path, bitmap, w, h, radius, offset, fromFrame = true)
          else -> !target.nestedRects && buildInnerPath(path, bitmap, w, h, radius, offset)
        }
    }

  /** Inverts [bitmap] to `255 − B` unless the route did, then sets [target]'s bands from the bitmap's hole. */
  private fun invertAndBand(
    target: ShadowBitmaps,
    path: Path?,
    bitmap: Bitmap,
    size: Size,
    w: Int,
    h: Int,
    offset: Offset,
  ) {
    // The nine-patch routes wrote the bitmap inverted; the frame's has no hole. The others search the hole from the
    // centre moved by half the offset.
    val hole =
      when (route) {
        Route.NinePatch -> ninePatch.hole
        Route.NineRects -> 0L
        else ->
          native.invertWithHole(
            bitmap, w, h, floor((size.width + offset.x) / 2f).toInt(), floor((size.height + offset.y) / 2f).toInt(),
          )
      }
    // Bands only for a round rect or a convex path that is not an inverse fill.
    if (hole > 0L && (path == null || (path.isConvex && !path.asAndroidPath().isInverseFillType))) {
      // A path may reach past the component: the bands must cover all of it.
      @Suppress("DEPRECATION") if (path != null) path.asAndroidPath().computeBounds(bounds, true)
      target.setBands(hole, size, if (path != null) bounds else null)
    }
  }

  /** The inner mask route of [path] ([MaskRoute.setInnerPath]); false leaves the build to the Skia route. */
  private fun buildInnerPath(
    path: Path,
    target: Bitmap,
    w: Int,
    h: Int,
    radius: Float,
    offset: Offset,
    fromFrame: Boolean = false,
  ): Boolean {
    val taken = maskRoute.setInnerPath(path, offset, fromFrame)
    if (taken) {
      route = Route.Mask
      maskRoute.drawInner(target, w, h, radius)
    }
    return taken
  }

  /**
   * The inner shadow of a simple round rect (inset by [spread], moved by [offset]) on [Route.NinePatch] or
   * [Route.MaskRoundRect], when Skia's plan picks one; false otherwise.
   */
  private fun buildInnerRoundRect(
    bitmaps: ShadowBitmaps,
    target: Bitmap,
    size: Size,
    w: Int,
    h: Int,
    radius: Float,
    spread: Float,
    offset: Offset,
  ): Boolean {
    val corner = bitmaps.cornerRadius
    if (!(corner.x > 0f && corner.y > 0f)) return false // drawRoundRect draws a rect
    val l = offset.x + spread
    val t = offset.y + spread
    val r = max(l, offset.x + size.width - spread)
    val b = max(t, offset.y + size.height - spread)
    val plan = ObvidNative.builderPlanRoundRect(ObvidNative.SKIA_CLASS_B, l, t, r, b, corner.x, corner.y, radius)
    roundRect.set(l, t, r, b)
    val taken = plan != ObvidNative.PLAN_SKIA
    when (plan) {
      ObvidNative.PLAN_SKIA -> Unit
      ObvidNative.PLAN_FULL_MASK -> {
        route = Route.MaskRoundRect
        maskRoute.setRoundRect(roundRect, corner, radius)
        maskRoute.blurRoundRect(target, w, h, radius)
      }
      else -> {
        // Written inverted (255 − B).
        route = Route.NinePatch
        ninePatch.build(bitmaps, nineMasks, mayPublish, target, w, h, radius, roundRect, corner, plan, inverted = true)
      }
    }
    return taken
  }

  /** The inner path + spread on Obvid's route ([InnerPathSpread]); false leaves the build to the Skia route. */
  private fun buildInnerPathSpread(target: Bitmap, w: Int, h: Int, radius: Float, offset: Offset): Boolean {
    val image = skia.intermediateBitmap
    val taken = innerSpread.fits(image, offset)
    if (taken) {
      route = Route.Mask
      innerSpread.build(image, target, w, h, radius, offset)
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
