// Copyright 2026, Serhii Yaremych (desugar-64) and the Obvid project contributors
// SPDX-License-Identifier: Apache-2.0

package dev.serhiiyaremych.obvid.builder

import android.graphics.Bitmap
import android.graphics.RectF
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.isSimple
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import dev.serhiiyaremych.obvid.NineMaskCache
import dev.serhiiyaremych.obvid.NineMaskKey
import dev.serhiiyaremych.obvid.ObvidNative
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * One shadow's output, written by [ShadowBitmapBuilder]: up to two A8 bitmaps, each kept at exactly the used size
 * with `Bitmap.reconfigure` inside its grow-only allocation (so Compose's own `drawImage(image, topLeft)` /
 * `ImageShader` calls see Compose's exact-size bitmap: same edge clamping and filtering), the outline's mask path,
 * whether it may be nested rects ([nestedRects]), whether it is a canonical frame ([isFrame], [framePoints]) and
 * whether its stroke may be rects ([strokeMayBeRects]), kept while the outline is, and the nine-patch route's private blurred small round rect with its key ([keptNineMask]).
 *
 * Two buffers: `build(…, preserveCurrent = true)` writes into the second bitmap and leaves the current one untouched,
 * for a bitmap a recorded frame may still read on the RenderThread: HWUI pins (uploads) the images of `drawBitmap`
 * calls during the frame sync, while the UI thread waits (`SkiaDisplayList::prepareListAndChildren`), but not the
 * image inside a `BitmapShader` composite, which the RenderThread uploads when it draws. The second bitmap is
 * allocated on first use only. Replaced bitmaps are never `recycle()`d: a recorded display list may still hold them;
 * the GC frees them.
 */
internal class ShadowBitmaps {
  private val slots = arrayOfNulls<Bitmap>(2)
  private var current = 0

  /** The shadow bitmap of the last build (exactly Compose's bitmap size, [prepare]), or null before the first build. */
  val bitmap: Bitmap?
    get() = slots[current]

  /**
   * Whether the current build is not the first one into these bitmaps (set by [prepare]): only a rebuild keeps the
   * nine-patch's blurred small round rect ([keptNineMask]).
   */
  var isRebuild: Boolean = false
    private set

  /** Number of shadow bitmaps allocated (growth or first use of a buffer). */
  var allocationCount: Int = 0
    private set

  /** The outline [path] / [cornerRadius] were taken from ([setOutline]); null after [clearOutline]. */
  var outline: Outline? = null
    private set

  /** `ShadowRenderer.updateParamsFromOutline`: the path to draw, or null for a rect / simple round rect. */
  var path: Path? = null
    private set

  var cornerRadius: CornerRadius = CornerRadius.Zero
    private set

  /**
   * Whether Skia may blur [path] as nested rects ([mayBeNestedRects]), with its own rect nine-patch
   * (`filterRectsToNine`), which the mask route does not model: the builds leave such a path to the Skia route as they
   * do a rect path, unless it is a canonical frame they decide exactly ([isFrame]). Taken once per outline; false for
   * an outline without a path.
   */
  var nestedRects: Boolean = false
    private set

  /**
   * Whether [path] is a canonical frame: its verbs are exactly move, line, line, line, close, twice (two `addRect`
   * contours), and its fill is not inverse. Its 8 points are in [framePoints]. The builds test the points moved by
   * their translate with Skia's own rect test to decide whether Skia blurs it as nested rects (the frame route).
   * Taken once per outline; false for an outline without a path.
   */
  var isFrame: Boolean = false
    private set

  /**
   * The canonical frame's points as (x, y) pairs, the first contour's four, then the second's; valid when [isFrame].
   * Read once per `Outline` instance ([setOutline] skips the outline it already holds), as the outline itself is kept:
   * a path changed in place under the same outline is not read again.
   */
  val framePoints = FloatArray(16)

  /** [mayBeNestedRects]', [readFrame]'s and [mayBeStrokeRects]' scratch for one segment's points. */
  private val segmentPoints = FloatArray(8)

  /** The spread [strokeRects] was taken for under the current outline; NaN: none yet. */
  private var strokeRectsSpread = Float.NaN
  private var strokeRects = false

  /**
   * Whether [stroke], Compose's `2·spread` stroke of [path] for this [spread], may be one rect or two nested rects for
   * Skia's `countNestedRects` ([mayBeStrokeRects]). Taken when the spread differs from the last one asked under the
   * current outline (it allocates an iterator), then kept: a kept outline at a kept spread costs a float compare. As
   * for [isFrame], a path changed in place under the same outline (and spread) is not read again; Compose's own cache
   * of an outline's path assumes the same.
   */
  fun strokeMayBeRects(spread: Float, stroke: Path): Boolean {
    if (spread != strokeRectsSpread) {
      strokeRects = mayBeStrokeRects(stroke, segmentPoints)
      strokeRectsSpread = spread
    }
    return strokeRects
  }

  /** A copy of Compose's internal `Outline.Rounded.roundRectPath` (the same path), reused across outlines. */
  private var roundRectPath: Path? = null

  /**
   * The nine-patch route's private blurred small round rect, kept in the top-left window of this bitmap
   * (`ObvidNative.builderNineKeep`), and its key: the plan (`builderPlanRoundRect`'s value: the small round
   * rect), the corner radii and the blur radius ([NineMaskKey] has why these are every input of the bytes). Never
   * drawn by HWUI, so one buffer serves both shadow buffers, and it is never shared: it is rewritten in place by the
   * next rebuild that misses. [nineKey]'s plan is `ObvidNative.PLAN_SKIA` while nothing valid is kept.
   *
   * Written only by a rebuild ([isRebuild]) that finds the key neither here nor in the builder's [NineMaskCache]: a
   * shadow built once (a static one) never allocates it. Freed by a build off the nine-patch route and by the owner
   * ([releaseNineMask]).
   */
  var nineMask: Bitmap? = null
    private set
  private val nineKey = NineMaskKey()

  /**
   * [nineMask]'s rect whose image is an inner nine-patch's hole at any placement (`ObvidNative.builderNineMaskHole`,
   * packed), found by the first inner build that places it; `ObvidNative.MASK_HOLE_UNKNOWN` until then and whenever
   * the buffer is rewritten.
   */
  var nineMaskHole: Long = ObvidNative.MASK_HOLE_UNKNOWN

  /**
   * Nine-patch builds that placed a blurred small round rect instead of drawing and blurring it, the private one or a
   * published one (a test counter).
   */
  var nineReuseCount: Int = 0
    private set

  /** The [nineReuseCount] builds that placed a published small round rect ([NineMaskCache]) (a test counter). */
  var nineSharedHitCount: Int = 0
    private set

  /** Nine-patch builds that drew and blurred their small round rect (a test counter). */
  var nineBlurCount: Int = 0
    private set

  /** Allocations of the private buffer [nineMask] (first use or growth) (a test counter). */
  var nineAllocationCount: Int = 0
    private set

  /**
   * Whether the current build's final draw goes in bands (an inner shadow's, `ShadowDrawState`): the bitmap is 0
   * inside the hole `[holeLeft, holeRight) × [holeTop, holeBottom)` (whole pixels of the bitmap, which is the
   * component's local space), so the outline is drawn up to four times, each clipped to one band around it, the bands
   * spanning `[bandLeft, bandRight) × [bandTop, bandBottom)`. Set by [setBands] for a hole large enough
   * ([BAND_MIN_HOLE_SHARE], [BAND_MIN_HOLE_SIDE]); every build starts without ([clearBands]).
   */
  var hasBands: Boolean = false
    private set
  var holeLeft = 0
    private set
  var holeTop = 0
    private set
  var holeRight = 0
    private set
  var holeBottom = 0
    private set
  var bandLeft = 0
    private set
  var bandTop = 0
    private set
  var bandRight = 0
    private set
  var bandBottom = 0
    private set

  fun clearBands() {
    hasBands = false
  }

  /**
   * The bands of the current build from its packed [hole] (`ObvidNative.holeLeft`, …; ≤ 0: none) for a component of
   * [width] × [height] px whose outline has the [bounds] (null: the component's rect). The hole is shrunk by
   * [BAND_FILTER_INSET] on each side, then kept only if large enough; the bands span the component's rect, the
   * outline's bounds and the hole, rounded out and grown by [BAND_MARGIN].
   */
  fun setBands(hole: Long, width: Float, height: Float, bounds: RectF?) {
    hasBands = false
    if (hole <= 0L) return
    val l = ObvidNative.holeLeft(hole) + BAND_FILTER_INSET
    val t = ObvidNative.holeTop(hole) + BAND_FILTER_INSET
    val r = ObvidNative.holeRight(hole) - BAND_FILTER_INSET
    val b = ObvidNative.holeBottom(hole) - BAND_FILTER_INSET
    val w = r - l
    val h = b - t
    if (w < BAND_MIN_HOLE_SIDE || h < BAND_MIN_HOLE_SIDE) return
    if (w.toFloat() * h < BAND_MIN_HOLE_SHARE * width * height) return
    var outerL = min(0, l)
    var outerT = min(0, t)
    var outerR = max(ceil(width).toInt(), r)
    var outerB = max(ceil(height).toInt(), b)
    if (bounds != null) {
      outerL = min(outerL, floor(bounds.left).toInt())
      outerT = min(outerT, floor(bounds.top).toInt())
      outerR = max(outerR, ceil(bounds.right).toInt())
      outerB = max(outerB, ceil(bounds.bottom).toInt())
    }
    holeLeft = l
    holeTop = t
    holeRight = r
    holeBottom = b
    bandLeft = outerL - BAND_MARGIN
    bandTop = outerT - BAND_MARGIN
    bandRight = outerR + BAND_MARGIN
    bandBottom = outerB + BAND_MARGIN
    hasBands = true
  }

  /** Drops the outline: its inputs changed, the owner creates a new one. */
  fun clearOutline() {
    outline = null
  }

  fun setOutline(outline: Outline) {
    if (outline === this.outline) return
    this.outline = outline
    path = null
    cornerRadius = CornerRadius.Zero
    nestedRects = false
    isFrame = false
    strokeRectsSpread = Float.NaN
    when (outline) {
      is Outline.Generic -> {
        path = outline.path
        nestedRects = mayBeNestedRects(outline.path, segmentPoints)
        isFrame = readFrame(outline.path, segmentPoints, framePoints)
      }
      is Outline.Rounded ->
        if (outline.roundRect.isSimple) cornerRadius = outline.roundRect.topLeftCornerRadius
        else path = (roundRectPath ?: Path().also { roundRectPath = it }).apply {
          rewind()
          addRoundRect(outline.roundRect)
        }
      is Outline.Rectangle -> Unit
    }
  }

  /** The kept blurred small round rect if it was made for this [plan], corner radii and blur [radius], else null. */
  fun keptNineMask(plan: Long, rx: Float, ry: Float, radius: Float): Bitmap? {
    val kept = nineMask ?: return null
    return if (plan != ObvidNative.PLAN_SKIA && nineKey.matches(plan, rx, ry, radius)) kept else null
  }

  /** Counts a build that placed a kept small round rect; [shared] if it was a published one. */
  fun countNineReuse(shared: Boolean) {
    nineReuseCount++
    if (shared) nineSharedHitCount++
  }

  /** Counts a build that drew and blurred its small round rect. */
  fun countNineBlur() {
    nineBlurCount++
  }

  /**
   * The buffer to keep a blurred small round rect of [w] × [h] in, grown per axis (read as a top-left window with its
   * own stride, never reconfigured). Drops the key: it is set again by [keepNine] once the buffer holds the new bytes.
   */
  fun nineMaskFor(w: Int, h: Int): Bitmap {
    nineKey.plan = ObvidNative.PLAN_SKIA
    nineMaskHole = ObvidNative.MASK_HOLE_UNKNOWN
    val current = nineMask
    if (current != null && current.width >= w && current.height >= h) return current
    val next = newA8(max(w, current?.width ?: 0), max(h, current?.height ?: 0))
    nineAllocationCount++
    current?.recycle()
    nineMask = next
    return next
  }

  /** The buffer of [nineMaskFor] now holds the blurred small round rect of this [plan], corner radii and [radius]. */
  fun keepNine(plan: Long, rx: Float, ry: Float, radius: Float) {
    nineKey.set(plan, rx, ry, radius)
  }

  /**
   * Frees the private small round rect (the owner no longer builds, or the build left the nine-patch route); a later
   * nine-patch rebuild looks in the cache or draws and blurs again.
   */
  fun releaseNineMask() {
    nineKey.plan = ObvidNative.PLAN_SKIA
    nineMaskHole = ObvidNative.MASK_HOLE_UNKNOWN
    nineMask?.recycle()
    nineMask = null
  }

  /** Bytes held by these bitmaps: both buffers and the kept small round rect (`Bitmap.allocationByteCount`). */
  val byteCount: Long
    get() {
      var bytes = 0L
      for (b in slots) if (b != null) bytes += b.allocationByteCount
      nineMask?.let { bytes += it.allocationByteCount }
      return bytes
    }

  /**
   * Where the last build's bitmap lies in Compose's own bitmap, in whole px: its top-left pixel is Compose's pixel
   * ([originX], [originY]). 0 for Compose's window (every inner shadow); for a drop shadow's full extent the full
   * window's start, negative where it reaches past Compose's. The draw places the bitmap at Compose's position plus
   * this.
   */
  var originX: Int = 0
    private set
  var originY: Int = 0
    private set

  /** Compose's own bitmap size for the last build: the bitmap's size for Compose's window; a drop brush spans it. */
  var composeWidth: Int = 0
    private set
  var composeHeight: Int = 0
    private set

  /** Sets [originX], [originY], [composeWidth] and [composeHeight] for the current build. */
  fun setWindow(originX: Int, originY: Int, composeWidth: Int, composeHeight: Int) {
    this.originX = originX
    this.originY = originY
    this.composeWidth = composeWidth
    this.composeHeight = composeHeight
  }

  /**
   * The bitmap for a [w] × [h] build, made current; with [preserveCurrent] the other buffer. A bitmap whose allocation
   * holds w·h bytes is reconfigured (its old bytes are stale: the build rewrites all of it); otherwise a new one is
   * allocated (zeroed).
   */
  fun prepare(w: Int, h: Int, preserveCurrent: Boolean): Bitmap {
    isRebuild = slots[0] != null || slots[1] != null
    if (preserveCurrent && slots[current] != null) current = 1 - current
    val old = slots[current]
    if (old != null) {
      if (old.width == w && old.height == h) return old
      if (old.allocationByteCount >= w * h) {
        old.reconfigure(w, h, Bitmap.Config.ALPHA_8)
        return old
      }
    }
    allocationCount++
    return newA8(w, h).also {
      // A new bitmap is zeroed; the reconfigure keeps its bytes.
      if (it.width != w || it.height != h) it.reconfigure(w, h, Bitmap.Config.ALPHA_8)
      slots[current] = it
    }
  }

  companion object {
    /**
     * The share of the component's area the hole must cover for the draw to go in bands: below it the one draw over
     * the whole outline costs less than four draws of the bands.
     */
    const val BAND_MIN_HOLE_SHARE = 0.3f

    /** The hole's smallest width and height in px for the draw to go in bands. */
    const val BAND_MIN_HOLE_SIDE = 16

    /**
     * Pixels each side of the hole gives up before the bands are cut: the bitmap is sampled with bilinear filtering, so
     * a pixel whose centre maps within 1 px of the hole's edge (under a scale or rotation) may read a non-zero texel
     * just outside it.
     */
    const val BAND_FILTER_INSET = 1

    /**
     * How far the bands reach past the outline's bounds, in px: a pixel whose centre lies outside the bounds can still
     * be partly covered by the outline's anti-aliased edge (more than 1 px out when a layer scales the component down),
     * and a band that does not contain that centre would drop it.
     */
    const val BAND_MARGIN = 256
  }
}
