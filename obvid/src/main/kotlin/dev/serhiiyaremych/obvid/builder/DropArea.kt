// Copyright 2026, Serhii Yaremych (desugar-64) and the Obvid project contributors
// SPDX-License-Identifier: Apache-2.0

package dev.serhiiyaremych.obvid.builder

import android.graphics.Bitmap
import android.graphics.RectF
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** The drop build's area: the [translate] before Compose's draw calls and the bitmap's [size]. Set per build. */
internal class DropArea {
  /** Whether this build uses the full extent. */
  var full = false
    private set

  /** The canvas translate before Compose's draw calls, in whole px. */
  var translate: IntOffset = IntOffset.Zero
    private set

  /** The bitmap's size. */
  var size: IntSize = IntSize.Zero
    private set

  /** The bitmap this build writes; null between builds, so the builder keeps no target alive. */
  var target: ShadowBitmaps? = null
    private set

  private var composeSize = IntSize.Zero
  private var preserve = false

  /** Starts a build into [target]: Compose's bitmap is [composeSize]. */
  fun begin(target: ShadowBitmaps, full: Boolean, composeSize: IntSize, preserve: Boolean) {
    this.full = full
    this.composeSize = composeSize
    this.target = target
    this.preserve = preserve
  }

  fun end() {
    target = null
  }

  /** Compose's area: no translate, Compose's bitmap size. */
  fun setCompose() {
    translate = IntOffset.Zero
    size = composeSize
  }

  /** [c] moved by the area's translate [p] on one axis: Skia's CTM `translate(p)` then Compose's translate by [c]. */
  fun moved(c: Float, p: Int): Float = if (full) p.toFloat() + c else c

  /** The full-extent area of a draw with local bounds [local], under Compose's translate [t], with [margin] px around. */
  fun setFull(local: RectF, t: Float, margin: Int) {
    if (local.left.isFinite() && local.top.isFinite() && local.right.isFinite() && local.bottom.isFinite()) {
      val px = fullAxisStart(local.left, t, margin, composeSize.width)
      val py = fullAxisStart(local.top, t, margin, composeSize.height)
      translate = IntOffset(px, py)
      size =
        IntSize(
          fullAxisSize(local.right, px, t, margin, composeSize.width),
          fullAxisSize(local.bottom, py, t, margin, composeSize.height),
        )
    } else {
      setCompose()
    }
  }

  /** One axis of [setFull]: the whole-px translate that puts the draw's first pixel [margin] px past the start. */
  private fun fullAxisStart(lo: Float, t: Float, margin: Int, c: Int): Int {
    // The area's start in Compose's bitmap (p = −start).
    val start = (floor(lo + t).toInt() - margin).coerceIn(-margin, c + margin - 1)
    var p = -start
    // The device coordinate is rounded once more than lo + t: its floor can fall one short.
    while (p < margin && floor(lo + (p + t)).toInt() < margin) p++
    return p
  }

  /** One axis of [setFull]: the size that puts the draw's last pixel [margin] px before the end. */
  private fun fullAxisSize(hi: Float, p: Int, t: Float, margin: Int, c: Int): Int =
    max(min(ceil(hi + (p + t)).toInt() + margin, p + c + margin), 1)

  /** The current build's bitmap, of the area's size, with the area recorded in it ([ShadowBitmaps.setWindow]). */
  fun prepare(): Bitmap {
    val bitmaps = checkNotNull(target)
    bitmaps.setWindow(-translate, composeSize)
    return bitmaps.prepare(size.width, size.height, preserve)
  }
}
