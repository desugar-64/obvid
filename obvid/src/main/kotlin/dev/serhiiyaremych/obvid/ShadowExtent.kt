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
   * Compose's window: the shape grown by `radius + spread`. The same pixels as Compose; a strong shadow can end in a
   * visible cut at the window's edge.
   */
  Compose,

  /**
   * Until the blur reaches zero, so the shadow fades out without a cut. Costs a larger bitmap (typically 15–36 % more
   * pixels than [Compose]), and the output no longer matches Compose's. Applies to [dropShadow] only; no effect when it
   * falls back to Compose's modifier.
   */
  Full,
}

/**
 * The [ShadowExtent] of every [dropShadow] below. A change rebuilds the shadows that read it.
 *
 * ```
 * CompositionLocalProvider(LocalShadowExtent provides ShadowExtent.Full) { App() }
 * ```
 */
public val LocalShadowExtent: ProvidableCompositionLocal<ShadowExtent> =
  staticCompositionLocalOf { ShadowExtent.Compose }
