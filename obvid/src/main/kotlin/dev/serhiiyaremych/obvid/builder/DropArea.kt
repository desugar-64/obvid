// Copyright 2026, Serhii Yaremych (desugar-64) and the Obvid project contributors
// SPDX-License-Identifier: Apache-2.0

package dev.serhiiyaremych.obvid.builder

import android.graphics.Bitmap
import android.graphics.RectF
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * The drop build's area ([setCompose], [setFull]): the translate before Compose's draw calls ([x], [y]: whole px; 0 for
 * Compose's area, the canvas translate of a larger canvas for the full extent) and the bitmap's size ([width],
 * [height]), which [prepare] prepares in [target]. Set per build by [begin], released by [end].
 */
internal class DropArea {
  /** Whether this build uses the full extent. */
  var full = false
    private set

  var x = 0f
    private set

  var y = 0f
    private set

  var width = 0
    private set

  var height = 0
    private set

  /** The bitmap this build writes; null between builds, so the builder keeps no target alive. */
  var target: ShadowBitmaps? = null
    private set

  private var composeWidth = 0
  private var composeHeight = 0
  private var preserve = false

  /** [fullAxis]' second result. */
  private var fullAxisSize = 0

  /** Starts a build into [target]: Compose's bitmap is [composeWidth] × [composeHeight]. */
  fun begin(target: ShadowBitmaps, full: Boolean, composeWidth: Int, composeHeight: Int, preserve: Boolean) {
    this.full = full
    this.composeWidth = composeWidth
    this.composeHeight = composeHeight
    this.target = target
    this.preserve = preserve
  }

  fun end() {
    target = null
  }

  /** Compose's area: no translate, Compose's bitmap size. */
  fun setCompose() {
    x = 0f
    y = 0f
    width = composeWidth
    height = composeHeight
  }

  /** [c] moved by the area's translate [p]: Skia's CTM `translate(p)` then Compose's translate by [c]. */
  fun moved(c: Float, p: Float): Float = if (full) p + c else c

  /**
   * The full-extent area of a draw whose local bounds are [local], drawn under Compose's translate [t] (0 for the
   * round-rect draw), with [margin] px around it: per axis the whole-px translate and the size ([fullAxis]).
   */
  fun setFull(local: RectF, t: Float, margin: Int) {
    if (local.left.isFinite() && local.top.isFinite() && local.right.isFinite() && local.bottom.isFinite()) {
      x = fullAxis(local.left, local.right, t, margin, composeWidth)
      width = fullAxisSize
      y = fullAxis(local.top, local.bottom, t, margin, composeHeight)
      height = fullAxisSize
    } else {
      setCompose()
    }
  }

  /**
   * One axis of [setFull]: the translate p (whole px) such that the draw's first pixel, `floor(lo + (p + t))` on the
   * device (the coordinate moved as Skia moves it), lies [margin] px past the bitmap's start, and the bitmap's size
   * ([fullAxisSize]) so that its last pixel, `ceil(hi + (p + t)) − 1`, lies [margin] px before its end. In Compose's
   * bitmap (size [c]) the area stays within `[−margin, c + margin)`.
   */
  private fun fullAxis(lo: Float, hi: Float, t: Float, margin: Int, c: Int): Float {
    // The area's start in Compose's bitmap (p = −start).
    val start = (floor(lo + t).toInt() - margin).coerceIn(-margin, c + margin - 1)
    var p = -start
    // The device coordinate is rounded once more than lo + t: its floor can fall one short.
    while (p < margin && floor(lo + (p + t)).toInt() < margin) p++
    val end = min(ceil(hi + (p + t)).toInt() + margin, p + c + margin)
    fullAxisSize = max(end, 1)
    return p.toFloat()
  }

  /** The current build's bitmap, of the area's size, with the area recorded in it ([ShadowBitmaps.setWindow]). */
  fun prepare(): Bitmap {
    val bitmaps = checkNotNull(target)
    bitmaps.setWindow(-x.toInt(), -y.toInt(), composeWidth, composeHeight)
    return bitmaps.prepare(width, height, preserve)
  }
}
