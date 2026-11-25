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
   * On worker threads, started when the shadow's node is measured; the draw uses the finished bitmap or waits for it.
   * The first frame of a screen with many shadows draws faster.
   *
   * Limits:
   * - first builds only: a shadow that changes rebuilds in the draw;
   * - a block shadow's block also runs at layout; a geometry change between layout and draw builds again in the draw;
   * - workers compete with the app's other work for the big cores;
   * - a little more total CPU work than [Inline].
   *
   * No effect when the modifiers fall back to Compose's own.
   */
  Parallel,
}

/**
 * The [ShadowBuilds] of every [dropShadow] and [innerShadow] below. A change applies to shadows built after it. A node
 * attached with [ShadowBuilds.Inline] builds inline until it is attached again.
 *
 * ```
 * CompositionLocalProvider(LocalShadowBuilds provides ShadowBuilds.Parallel) { FeedScreen() }
 * ```
 */
public val LocalShadowBuilds: ProvidableCompositionLocal<ShadowBuilds> =
  staticCompositionLocalOf { ShadowBuilds.Inline }
