// Copyright 2026, Serhii Yaremych (desugar-64) and the Obvid project contributors
// SPDX-License-Identifier: Apache-2.0

package dev.serhiiyaremych.obvid.sample.discover

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.serhiiyaremych.obvid.sample.LocalShadowImpl
import dev.serhiiyaremych.obvid.sample.measuredDropShadow

/** Resource id of the bar (the benchmark keeps its drags above it). */
const val DiscoverNavBarTag = "discoverNav"

/** Height of the bar. */
val NavBarHeight = 76.dp

/** A bar destination. */
@Immutable
class NavTab(val icon: DiscoverIcon, val selectedIcon: DiscoverIcon, val label: String)

val NavTabs = listOf(
  NavTab(DiscoverIcon.HomeOutline, DiscoverIcon.Home, "Home"),
  NavTab(DiscoverIcon.Compass, DiscoverIcon.Compass, "Explore"),
  NavTab(DiscoverIcon.Bookmark, DiscoverIcon.BookmarkFilled, "Saved"),
  NavTab(DiscoverIcon.Profile, DiscoverIcon.Profile, "Profile"),
)

/**
 * A bar item (a tab): selected, the accent gradient with its [rim] over an [outerGlow] and the [DepthTokens.accent]
 * shadow; unselected, flat. The three shadows stay in place and fade by alpha, in the draw phase.
 */
@Composable
fun NavItem(tab: NavTab, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
  val palette = DiscoverPalette.current
  val impl = LocalShadowImpl.current
  val fraction = selectionFraction(selected, "navItem")
  val tint = { mix(palette.colors.muted, palette.colors.onPrimary, fraction.value) }
  Column(
    modifier
      .outerGlow(impl, palette, DiscoverShapes.NavItem, palette.colors.primary) { fraction.value }
      .measuredDropShadow(DiscoverShapes.NavItem, impl) { blend(palette.poses.navAccentOff, palette.depth.accent, fraction.value) }
      .accentFill(DiscoverShapes.NavItem, rest = null, accent = palette.brushes.accent) { fraction.value }
      .rim(impl, palette, DiscoverShapes.NavItem) { fraction.value }
      .selectable(selected, interactionSource = null, indication = null, role = Role.Tab, onClick = onClick),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.Center,
  ) {
    Box(Modifier.size(24.dp)) {
      LineIcon(tab.icon, Modifier.size(24.dp), color = tint, alpha = { 1f - fraction.value })
      LineIcon(tab.selectedIcon, Modifier.size(24.dp), color = tint, alpha = { fraction.value })
    }
    Spacer(Modifier.height(3.dp))
    BasicText(tab.label, style = palette.text.navLabel, maxLines = 1, color = tint)
  }
}

/**
 * The floating bar: a [cardSurface] without rim holding the [NavItem]s. [selected] is read here only. The bar's own
 * pointer input takes touches between the items, so a drag there never reaches the feed below.
 */
@Composable
fun BottomNavBar(tabs: List<NavTab>, selected: () -> Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
  val palette = DiscoverPalette.current
  val current = selected()
  Row(
    modifier
      .fillMaxWidth()
      .height(NavBarHeight)
      .testTag(DiscoverNavBarTag)
      .cardSurface(LocalShadowImpl.current, palette, DiscoverShapes.NavBar, rim = false)
      .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } }
      .selectableGroup()
      .padding(8.dp),
    horizontalArrangement = Arrangement.spacedBy(6.dp),
  ) {
    tabs.forEachIndexed { index, tab ->
      NavItem(tab, current == index, { onSelect(index) }, Modifier.weight(1f).fillMaxHeight())
    }
  }
}

@Preview(widthDp = 420, uiMode = Configuration.UI_MODE_NIGHT_NO)
@Preview(widthDp = 420, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun BottomNavBarPreview() = DiscoverPreview {
  var selected by remember { mutableIntStateOf(0) }
  BottomNavBar(NavTabs, { selected }, { selected = it })
}

@Preview(uiMode = Configuration.UI_MODE_NIGHT_NO)
@Preview(uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun NavItemPreview() = DiscoverPreview {
  Row(Modifier.width(144.dp).height(56.dp)) {
    NavItem(NavTabs[0], selected = true, onClick = {}, modifier = Modifier.weight(1f).fillMaxHeight())
    NavItem(NavTabs[0], selected = false, onClick = {}, modifier = Modifier.weight(1f).fillMaxHeight())
  }
}
