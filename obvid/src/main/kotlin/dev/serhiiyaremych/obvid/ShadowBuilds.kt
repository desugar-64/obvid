// Copyright 2026, Serhii Yaremych (desugar-64) and the Obvid project contributors
// SPDX-License-Identifier: Apache-2.0

package dev.serhiiyaremych.obvid

import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Where [dropShadow] and [innerShadow] build a shadow's bitmap the first time it is drawn. Provided for a whole app or
 * a subtree with [LocalShadowBuilds]; the default is [Inline]. Either way the shadow is drawn in the same frame, with
 * the same pixels.
 */
public enum class ShadowBuilds {
  /** In the draw, on the UI thread, one shadow after the other. */
  Inline,

  /**
   * On worker threads (one per big core but one, at most 3), started when the shadow's node is measured. The draw
   * then takes the finished bitmap, waits while a worker builds it, or builds it itself when no worker started it.
   * A screen that opens with many shadows draws its first frame faster: the builds run in parallel, and during the
   * layout that comes before the draw.
   *
   * Costs and limits:
   * - only first builds: a shadow that changes (an animation) rebuilds in the draw, as with [Inline];
   * - the parameters are read at layout too (a block shadow's block runs once more there); a shadow whose geometry
   *   (shape, size, layout direction, density, radius, spread, an inner shadow's offset) changes between layout and
   *   draw builds again in the draw; a change of only its color, brush, alpha or blend mode uses the worker's bitmap;
   * - worker threads compete with the app's other work for the big cores, and each worker holds its own build buffers
   *   while it runs (they stop after 3 s without work);
   * - a little more total CPU work than [Inline]: shadows built in parallel do not share the blurred corner masks that
   *   inline builds reuse from each other.
   *
   * No effect where the modifiers fall back to Compose's own (the native library cannot be used).
   */
  Parallel,
}

/**
 * The [ShadowBuilds] of every [dropShadow] and [innerShadow] below, read when a shadow's node is attached and again
 * when it is measured. A change applies to shadows queued or built after it: a shadow already built keeps its bitmap,
 * and a build already queued is still used by the draw. A node
 * attached with [ShadowBuilds.Inline] stays draw-only, as Compose's, and builds inline until it is attached again
 * (for example, under a new `key`).
 *
 * ```
 * CompositionLocalProvider(LocalShadowBuilds provides ShadowBuilds.Parallel) { FeedScreen() }
 * ```
 */
public val LocalShadowBuilds: ProvidableCompositionLocal<ShadowBuilds> =
  staticCompositionLocalOf { ShadowBuilds.Inline }
