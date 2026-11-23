// Copyright 2026, Serhii Yaremych (desugar-64) and the Obvid project contributors
// SPDX-License-Identifier: Apache-2.0

package dev.serhiiyaremych.obvid.sample.app

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ShortNavigationBar
import androidx.compose.material3.ShortNavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.serhiiyaremych.obvid.sample.DemoLayout
import dev.serhiiyaremych.obvid.sample.LocalDemoLayout
import dev.serhiiyaremych.obvid.sample.LocalShadowImpl
import dev.serhiiyaremych.obvid.sample.discover.DiscoverPalette
import dev.serhiiyaremych.obvid.sample.discover.DiscoverShapes
import dev.serhiiyaremych.obvid.sample.discover.NavBarHeight
import dev.serhiiyaremych.obvid.sample.discover.NavBarMargin
import dev.serhiiyaremych.obvid.sample.discover.cardSurface

/**
 * The floating bottom navigation: a Material [ShortNavigationBar] (its default item colors) on a Discover card surface (ambient and key drop
 * shadows, sheen, by [LocalShadowImpl]), the same size and place as the Discover home screen's own bar, inset by
 * [LocalDemoLayout]'s margin, with the items centred (equal space above and below) and touches between them kept from
 * the content underneath. Items are tabs in a selectable group (Material's semantics).
 */
@Composable
fun DemoNavBar(selected: DemoTab, onSelect: (DemoTab) -> Unit, modifier: Modifier = Modifier) {
  Box(
    modifier
      .navigationBarsPadding()
      .padding(horizontal = LocalDemoLayout.current.margin, vertical = NavBarMargin)
      .fillMaxWidth()
      .height(NavBarHeight)
      .cardSurface(LocalShadowImpl.current, DiscoverPalette.current, DiscoverShapes.NavBar, rim = false)
      .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } },
    contentAlignment = Alignment.Center,
  ) {
    // The Material bar at its own height, centred: its items sit at their top padding in any taller bar. Loose
    // constraints, too: a minimum width handed down makes every item as wide as the bar, all but the first off-screen.
    ShortNavigationBar(containerColor = Color.Transparent, windowInsets = WindowInsets(0)) {
      DemoTab.entries.forEach { tab ->
        ShortNavigationBarItem(
          selected = tab == selected,
          onClick = { onSelect(tab) },
          icon = { TabIcon(tab) },
          label = { Text(tab.label, maxLines = 1) },
        )
      }
    }
  }
}

@Composable
private fun NavBarAtWidth(width: Dp) {
  var tab by remember { mutableStateOf(DemoTab.Feed) }
  DemoTheme {
    CompositionLocalProvider(LocalDemoLayout provides DemoLayout.forWindowWidth(width)) {
      Box(Modifier.background(DiscoverPalette.current.brushes.background).padding(vertical = 24.dp)) { DemoNavBar(tab, { tab = it }) }
    }
  }
}

@Preview(widthDp = 360, uiMode = Configuration.UI_MODE_NIGHT_NO)
@Preview(widthDp = 360, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun DemoNavBarCompactPreview() = NavBarAtWidth(360.dp)

@Preview(widthDp = 762, uiMode = Configuration.UI_MODE_NIGHT_NO)
@Preview(widthDp = 762, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun DemoNavBarMediumPreview() = NavBarAtWidth(762.dp)
