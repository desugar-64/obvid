// Copyright 2026, Serhii Yaremych (desugar-64) and the Obvid project contributors
// SPDX-License-Identifier: Apache-2.0

package dev.serhiiyaremych.obvid

import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * How much of a drop shadow's blur [dropShadow] keeps: the size of the bitmap it draws, and so where the shadow can
 * end.
 * Provided for a whole app or a subtree with [LocalShadowExtent]; the default is [Compose].
 */
public enum class ShadowExtent {
  /**
   * Compose's window: the shape grown by `radius + spread` on each side. Identical to Compose's `dropShadow`
   * (Android 16–17's Skia). The blur still holds a few levels at that distance (about 8–12 of 255 behind a solid
   * shape), so a strong, coloured shadow on a dark background can end in a straight cut along the bitmap's edge.
   */
  Compose,

  /**
   * Until the blur reaches zero: the shape grown by the spread, then by the smallest margin past which the blurred
   * output is guaranteed to be 0 (about 1.4–1.6 × radius for the blur's own arithmetic; the device's Skia's reach for
   * the shapes it draws itself, such as rects), so the shadow fades out without a cut.
   *
   * Costs and differences:
   * - a larger bitmap to build, blur and draw: typically 15–36 % more pixels than [Compose] (more for a small shape
   *   with a large radius);
   * - the output is no longer identical to Compose's: the tail Compose cuts off is kept. Inside Compose's window it is
   *   Compose's shadow drawn on a larger canvas, moved by whole pixels (a coordinate moved that way can round
   *   differently in its last bit, rarely changing a pixel by a level); a gradient brush stays where Compose puts it;
   * - only drop shadows: [innerShadow] is unaffected (its blur lies inside the shape, in a bitmap that covers it).
   *
   * No effect where [dropShadow] falls back to Compose's own modifier (the native library cannot be used).
   */
  Full,
}

/**
 * The [ShadowExtent] of every [dropShadow] below. Changing it rebuilds the shadows that read it (their bitmap's size
 * changes); a shadow whose extent stays the same keeps its bitmap.
 *
 * ```
 * CompositionLocalProvider(LocalShadowExtent provides ShadowExtent.Full) { App() }
 * ```
 */
public val LocalShadowExtent: ProvidableCompositionLocal<ShadowExtent> =
  staticCompositionLocalOf { ShadowExtent.Compose }
