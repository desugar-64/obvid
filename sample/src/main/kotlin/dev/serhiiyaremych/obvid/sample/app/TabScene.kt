// Copyright 2026, Serhii Yaremych (desugar-64) and the Obvid project contributors
// SPDX-License-Identifier: Apache-2.0

package dev.serhiiyaremych.obvid.sample.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import dev.serhiiyaremych.obvid.sample.GearsScreen
import dev.serhiiyaremych.obvid.sample.GlowColors
import dev.serhiiyaremych.obvid.sample.GlowScreen
import dev.serhiiyaremych.obvid.sample.LocalDemoLayout
import dev.serhiiyaremych.obvid.sample.SceneColors
import dev.serhiiyaremych.obvid.sample.discover.DiscoverFeed
import dev.serhiiyaremych.obvid.sample.discover.DiscoverState

/**
 * [tab]'s scene, full-window under the chrome, the same composable its benchmark measures: the Discover feed
 * (`t7_discover`, without its own header, chips and bar, which the host's chrome shows), the morph field (`t4_field`,
 * with a user camera), the gear train (`t3_gears`) and the turning glow (`t8_glow_spin`). [feed] and [camera] come from
 * the host, so they outlive a rebuild of the scene; [padding] keeps content clear of the chrome.
 */
@Composable
fun TabScene(tab: DemoTab, feed: DiscoverState, camera: PannableCamera, padding: DemoPadding) {
  when (tab) {
    DemoTab.Feed -> DiscoverFeed(feed, padding.discover)
    DemoTab.Shapes -> ShapesTab(camera, padding.scene)
    DemoTab.Gears -> ClearOfChrome(SceneColors.current.gears, padding.scene) { GearsScreen(it) }
    DemoTab.Glow -> ClearOfChrome(GlowColors.current.background, padding.scene) { GlowScreen(pulse = false, modifier = it) }
  }
}

/** [scene] laid out within [padding] and [LocalDemoLayout]'s margins, its [background] filling the window. */
@Composable
private fun ClearOfChrome(background: Color, padding: PaddingValues, scene: @Composable (Modifier) -> Unit) {
  Box(Modifier.fillMaxSize().background(background)) {
    scene(Modifier.padding(padding).padding(horizontal = LocalDemoLayout.current.margin))
  }
}
