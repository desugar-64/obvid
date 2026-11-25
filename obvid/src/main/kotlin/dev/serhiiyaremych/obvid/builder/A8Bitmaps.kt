// Copyright 2026, Serhii Yaremych (desugar-64) and the Obvid project contributors
// SPDX-License-Identifier: Apache-2.0

package dev.serhiiyaremych.obvid.builder

import android.graphics.Bitmap

private const val GROW_STEP = 32

/** The bytes of [newA8] of [w] × [h]. */
internal fun a8Bytes(w: Int, h: Int): Long =
  ((w + GROW_STEP - 1) / GROW_STEP * GROW_STEP).toLong() * ((h + GROW_STEP - 1) / GROW_STEP * GROW_STEP)

/** A new zeroed A8 bitmap of at least [w] × [h], each axis rounded up to [GROW_STEP] px (the shared growth rule). */
internal fun newA8(w: Int, h: Int): Bitmap =
  Bitmap.createBitmap(
    (w + GROW_STEP - 1) / GROW_STEP * GROW_STEP,
    (h + GROW_STEP - 1) / GROW_STEP * GROW_STEP,
    Bitmap.Config.ALPHA_8,
  )
