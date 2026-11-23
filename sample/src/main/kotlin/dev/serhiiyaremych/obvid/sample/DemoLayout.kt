// Copyright 2026, Serhii Yaremych (desugar-64) and the Obvid project contributors
// SPDX-License-Identifier: Apache-2.0

package dev.serhiiyaremych.obvid.sample

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max

/**
 * Spacing of the demo screens: [margin], the horizontal inset of content from the window edges, and [gap], the
 * vertical spacing unit between sections (header, chips, first card).
 */
@Immutable
data class DemoLayout(val margin: Dp, val gap: Dp) {
  companion object {
    /** Without a provider (the benchmark scenes, previews): the fixed phone spacing the benchmarks were recorded with. */
    val Phone = DemoLayout(margin = 16.dp, gap = 20.dp)

    /** Widest content of an expanded window; wider windows centre it. */
    private val MaxContentWidth = 800.dp

    /**
     * By window [width] (Material window size classes): compact (< 600 dp) a 16 dp margin, medium (< 840 dp) 48 dp,
     * expanded at least 48 dp and as much as keeps the content within [MaxContentWidth], centred. The vertical gap is
     * the margin, or 48 dp where the margin only centres.
     */
    fun forWindowWidth(width: Dp): DemoLayout {
      val gutter = if (width < 600.dp) 16.dp else 48.dp
      val margin = if (width < 840.dp) gutter else max(gutter, (width - MaxContentWidth) / 2)
      return DemoLayout(margin, gutter)
    }
  }
}

/** The [DemoLayout] in effect: [DemoLayout.Phone] unless the demo app provides one for its window. */
val LocalDemoLayout = staticCompositionLocalOf { DemoLayout.Phone }
