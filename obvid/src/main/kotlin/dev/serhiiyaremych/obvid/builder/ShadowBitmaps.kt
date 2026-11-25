// Copyright 2026, Serhii Yaremych (desugar-64) and the Obvid project contributors
// SPDX-License-Identifier: Apache-2.0

package dev.serhiiyaremych.obvid.builder

import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.RectF
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isSimple
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import dev.serhiiyaremych.obvid.NineMaskCache
import dev.serhiiyaremych.obvid.NineMaskKey
import dev.serhiiyaremych.obvid.ObvidNative
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * One shadow's output, written by [ShadowBitmapBuilder]: up to two A8 bitmaps at exactly Compose's size, the outline's
 * path with its kept tests, the private nine-patch mask ([nineMask]) and the bands of an inner shadow's draw.
 * `build(…, preserveCurrent = true)` writes into the other bitmap and leaves the current one untouched.
 */
internal class ShadowBitmaps {
  private val slots = arrayOfNulls<Bitmap>(2)
  private var current = 0

  /** The shadow bitmap of the last build (exactly Compose's bitmap size, [prepare]), or null before the first build. */
  val bitmap: Bitmap?
    get() = slots[current]

  /** Whether the current build is not the first one into these bitmaps (set by [prepare]). */
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

  /** Whether Skia may blur [path] as nested rects; the builds then use the Skia route, except for a frame ([isFrame]). */
  var nestedRects: Boolean = false
    private set

  /** Whether [path] is a canonical frame: two `addRect`-shaped contours, not an inverse fill. */
  var isFrame: Boolean = false
    private set

  /** The frame's 8 points as (x, y) pairs, the first contour's four, then the second's; valid when [isFrame]. */
  val framePoints = FloatArray(16)

  /** [mayBeNestedRects]', [readFrame]'s and [mayBeStrokeRects]' scratch for one segment's points. */
  private val segmentPoints = FloatArray(8)

  /** The spread [strokeRects] was taken for under the current outline; NaN: none yet. */
  private var strokeRectsSpread = Float.NaN
  private var strokeRects = false

  /** Whether [stroke], the `2·spread` stroke of [path], may be one rect or two nested rects; kept per [spread]. */
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
   * The nine-patch route's private blurred small round rect, in this bitmap's top-left window, with its key [nineKey].
   * Written by a rebuild that finds no kept mask; freed by [releaseNineMask].
   */
  var nineMask: Bitmap? = null
    private set
  private val nineKey = NineMaskKey()

  /** [nineMask]'s hole for an inner nine-patch, packed; `ObvidNative.MASK_HOLE_UNKNOWN` until found. */
  var nineMaskHole: Long = ObvidNative.MASK_HOLE_UNKNOWN

  /** Nine-patch builds that placed a kept small round rect instead of blurring one (a test counter). */
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

  /** Whether the current build's final draw goes in up to four bands around [hole], where the bitmap is 0. */
  var hasBands: Boolean = false
    private set

  /** The bitmap's hole while [hasBands]: 0 inside, right and bottom exclusive. */
  val hole = Rect()

  /** The span of the four bands around [hole] while [hasBands]. */
  val bands = Rect()

  fun clearBands() {
    hasBands = false
  }

  /**
   * Sets the bands from [packedHole] (≤ 0: none) for a component of [size] px with the outline [bounds] (null: the
   * component's rect), when the hole is large enough.
   */
  fun setBands(packedHole: Long, size: Size, bounds: RectF?) {
    hasBands = false
    if (packedHole <= 0L) return
    val l = ObvidNative.holeLeft(packedHole) + BAND_FILTER_INSET
    val t = ObvidNative.holeTop(packedHole) + BAND_FILTER_INSET
    val r = ObvidNative.holeRight(packedHole) - BAND_FILTER_INSET
    val b = ObvidNative.holeBottom(packedHole) - BAND_FILTER_INSET
    val w = r - l
    val h = b - t
    val large =
      !(w < BAND_MIN_HOLE_SIDE || h < BAND_MIN_HOLE_SIDE) &&
        !(w.toFloat() * h < BAND_MIN_HOLE_SHARE * size.width * size.height)
    if (large) {
      var outerL = min(0, l)
      var outerT = min(0, t)
      var outerR = max(ceil(size.width).toInt(), r)
      var outerB = max(ceil(size.height).toInt(), b)
      if (bounds != null) {
        outerL = min(outerL, floor(bounds.left).toInt())
        outerT = min(outerT, floor(bounds.top).toInt())
        outerR = max(outerR, ceil(bounds.right).toInt())
        outerB = max(outerB, ceil(bounds.bottom).toInt())
      }
      hole.set(l, t, r, b)
      bands.set(outerL - BAND_MARGIN, outerT - BAND_MARGIN, outerR + BAND_MARGIN, outerB + BAND_MARGIN)
      hasBands = true
    }
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

  /** Frees the private small round rect. */
  fun releaseNineMask() {
    nineKey.plan = ObvidNative.PLAN_SKIA
    nineMaskHole = ObvidNative.MASK_HOLE_UNKNOWN
    nineMask?.recycle()
    nineMask = null
  }

  /** Bytes held by these bitmaps: both buffers and the kept small round rect (`Bitmap.allocationByteCount`). */
  val byteCount: Long
    get() = slots.sumOf { it?.allocationByteCount?.toLong() ?: 0L } + (nineMask?.allocationByteCount ?: 0)

  /** Where the last build's bitmap starts in Compose's own bitmap, in whole px; zero for Compose's window. */
  var origin: IntOffset = IntOffset.Zero
    private set

  /** Compose's own bitmap size for the last build: the bitmap's size for Compose's window; a drop brush spans it. */
  var composeSize: IntSize = IntSize.Zero
    private set

  /** Sets [origin] and [composeSize] for the current build. */
  fun setWindow(origin: IntOffset, composeSize: IntSize) {
    this.origin = origin
    this.composeSize = composeSize
  }

  /**
   * The bitmap for a [w] × [h] build, made current; with [preserveCurrent] the other buffer. Reconfigures a bitmap that
   * fits, else allocates a new one.
   */
  fun prepare(w: Int, h: Int, preserveCurrent: Boolean): Bitmap {
    isRebuild = slots[0] != null || slots[1] != null
    if (preserveCurrent && slots[current] != null) current = 1 - current
    val old = slots[current]
    return when {
      old != null && old.width == w && old.height == h -> old
      old != null && old.allocationByteCount >= w * h -> old.apply { reconfigure(w, h, Bitmap.Config.ALPHA_8) }
      else -> {
        allocationCount++
        newA8(w, h).also {
          // A new bitmap is zeroed; the reconfigure keeps its bytes.
          if (it.width != w || it.height != h) it.reconfigure(w, h, Bitmap.Config.ALPHA_8)
          slots[current] = it
        }
      }
    }
  }

  companion object {
    /** The share of the component's area the hole must cover for the draw to go in bands. */
    const val BAND_MIN_HOLE_SHARE = 0.3f

    /** The hole's smallest width and height in px for the draw to go in bands. */
    const val BAND_MIN_HOLE_SIDE = 16

    /** Pixels each side of the hole gives up before the bands are cut. */
    const val BAND_FILTER_INSET = 1

    /** How far the bands reach past the outline's bounds, in px. */
    const val BAND_MARGIN = 256
  }
}
