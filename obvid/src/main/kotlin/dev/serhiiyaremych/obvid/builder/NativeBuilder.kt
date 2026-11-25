// Copyright 2026, Serhii Yaremych (desugar-64) and the Obvid project contributors
// SPDX-License-Identifier: Apache-2.0

package dev.serhiiyaremych.obvid.builder

import android.graphics.Bitmap
import dev.serhiiyaremych.obvid.ObvidNative

/** The native shadow builder handle and its per-radius values, kept for the last radius. */
internal class NativeBuilder {
  /** The native handle; 0 after [close]. */
  var handle: Long = ObvidNative.builderCreate()
    private set

  /** The blur's border for the radius of the last [updateBorder]. */
  var border = 0
    private set

  private var borderRadius = Float.NaN
  private var fullMargin = 0
  private var fullMarginRadius = Float.NaN
  private var skiaMargin = 0
  private var skiaMarginRadius = Float.NaN

  val isOpen: Boolean
    get() = handle != 0L

  fun updateBorder(radius: Float) {
    if (radius != borderRadius) {
      border = ObvidNative.builderBorder(radius)
      borderRadius = radius
    }
  }

  fun fullMargin(radius: Float): Int {
    if (radius != fullMarginRadius) {
      fullMargin = ObvidNative.builderFullMargin(radius)
      fullMarginRadius = radius
    }
    return fullMargin
  }

  fun skiaMargin(radius: Float): Int {
    if (radius != skiaMarginRadius) {
      skiaMargin = ObvidNative.builderSkiaReachMargin(radius)
      skiaMarginRadius = radius
    }
    return skiaMargin
  }

  /** Inverts [target] to `255 − v`; returns the packed hole found from ([seedX], [seedY]) (0: none). */
  fun invertWithHole(target: Bitmap, w: Int, h: Int, seedX: Int, seedY: Int): Long {
    val hole = ObvidNative.builderInvertHole(handle, target, w, h, seedX, seedY)
    check(hole >= 0L) { "builderInvertHole failed: $hole" }
    return hole
  }

  /** Frees the native handle; true when it was open. */
  fun close(): Boolean {
    val open = isOpen
    if (open) {
      ObvidNative.builderDestroy(handle)
      handle = 0L
    }
    return open
  }

  companion object {
    /** The `mode` of `ObvidNative.builderBlur` / `builderBlurFrom`: replace the bitmap, or composite source-over. */
    const val MODE_REPLACE = 0
    const val MODE_SRC_OVER = 1
  }
}
