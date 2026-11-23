// Copyright 2026, Serhii Yaremych (desugar-64) and the Obvid project contributors
// SPDX-License-Identifier: Apache-2.0

package dev.serhiiyaremych.obvid.sample.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.serhiiyaremych.obvid.sample.discover.DiscoverIcon
import dev.serhiiyaremych.obvid.sample.discover.LineIcon

/** The demo's destinations, in bar order; the first is the start destination. */
enum class DemoTab(val label: String) {
  Feed("Feed"),
  Shapes("Shapes"),
  Gears("Gears"),
  Glow("Glow"),
}

private val IconSize = 24.dp

private val DemoTab.icon: DiscoverIcon
  get() = when (this) {
    DemoTab.Feed -> DiscoverIcon.HomeOutline
    DemoTab.Shapes -> DiscoverIcon.Shapes
    DemoTab.Gears -> DiscoverIcon.Gear
    DemoTab.Glow -> DiscoverIcon.Sun
  }

/** [tab]'s outline glyph in [LocalContentColor]. */
@Composable
fun TabIcon(tab: DemoTab, modifier: Modifier = Modifier) {
  val color = LocalContentColor.current
  LineIcon(tab.icon, modifier.size(IconSize), color = { color })
}

@LightDarkPreviews
@Composable
private fun TabIconsPreview() = DemoPreview {
  CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) { DemoTab.entries.forEach { TabIcon(it) } }
  }
}
