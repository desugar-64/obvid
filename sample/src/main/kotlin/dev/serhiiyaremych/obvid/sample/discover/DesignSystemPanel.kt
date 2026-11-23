// Copyright 2026, Serhii Yaremych (desugar-64) and the Obvid project contributors
// SPDX-License-Identifier: Apache-2.0

package dev.serhiiyaremych.obvid.sample.discover

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.serhiiyaremych.obvid.sample.LocalShadowImpl
import dev.serhiiyaremych.obvid.sample.measuredInnerShadow

/**
 * The design-system half of the design: component examples (action button, bookmark toggle, chip, nav item, default
 * and active), the [TokenPalette] and a closing note. Shadows: action well 1 inner + play 2 + 1, toggles 2 + 2, chips
 * 4 + 4, nav sample 5 + 3, palette 3 + 2, note 2 + 1.
 */
@Composable
fun DesignSystemPanel(modifier: Modifier = Modifier) {
  val palette = DiscoverPalette.current
  Column(modifier) {
    BasicText("Component Examples", style = palette.text.section)
    Spacer(Modifier.height(8.dp))
    Example("Action Button", "Inner shadow for a pressed surface, outer shadow for elevation.") { ActionWell() }
    Example("Toggle / Bookmark", "Subtle depth and clear state (default / active).") {
      StatefulBookmarkToggle(initial = false, Modifier.size(44.dp))
      Spacer(Modifier.width(20.dp))
      StatefulBookmarkToggle(initial = true, Modifier.size(44.dp))
    }
    Example("Category Chip", "Inner shadow shapes the surface, outer shadow highlights selection.") { ChipPair() }
    Example("Navigation Item", "A tactile, elevated feel with consistent depth.") { NavItemPair() }
    SectionDivider()
    BasicText("Shadow Palette (Design Tokens)", style = palette.text.section)
    Spacer(Modifier.height(16.dp))
    TokenPalette()
    SectionDivider()
    ResultNote()
  }
}

/** A recessed well ([DepthTokens.press]) holding a play button. */
@Composable
private fun ActionWell() {
  val palette = DiscoverPalette.current
  Box(
    Modifier
      .size(128.dp, 68.dp)
      .background(palette.colors.well, DiscoverShapes.Well)
      .measuredInnerShadow(DiscoverShapes.Well, palette.depth.press, LocalShadowImpl.current),
    contentAlignment = Alignment.Center,
  ) {
    PlayButton(palette.colors.action, width = 84.dp, height = 42.dp)
  }
}

@Composable
private fun ChipPair() {
  var selected by rememberSaveable { mutableIntStateOf(1) }
  Row(Modifier.selectableGroup()) {
    repeat(2) { index ->
      if (index > 0) Spacer(Modifier.width(12.dp))
      CategoryChip("Tech", selected == index, { selected = index }, Modifier.width(66.dp))
    }
  }
}

@Composable
private fun NavItemPair() {
  val palette = DiscoverPalette.current
  var selected by rememberSaveable { mutableIntStateOf(0) }
  Row(Modifier.size(144.dp, 66.dp).selectableGroup().raised(LocalShadowImpl.current, palette, DiscoverShapes.NavItem, palette.colors.card).padding(5.dp)) {
    repeat(2) { index ->
      NavItem(NavTabs[0], selected == index, { selected = index }, Modifier.weight(1f).fillMaxHeight())
    }
  }
}

/** The closing note on a [DepthTokens.surface] card. */
@Composable
private fun ResultNote() {
  val palette = DiscoverPalette.current
  Row(
    Modifier.fillMaxWidth().cardSurface(LocalShadowImpl.current, palette, rim = false).padding(18.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    LineIcon(DiscoverIcon.Bulb, Modifier.size(34.dp), color = { palette.colors.warm })
    Spacer(Modifier.width(16.dp))
    BasicText(
      "Result: a clean, usable feed with depth, hierarchy, and rich, tactile interactions, powered by inner and outer shadows.",
      style = palette.text.note,
    )
  }
}

/** A component example: [sample] on the left, its name and what its shadows do on the right. */
@Composable
private fun Example(title: String, body: String, sample: @Composable RowScope.() -> Unit) {
  val palette = DiscoverPalette.current
  Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
    Row(Modifier.width(156.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically, content = sample)
    Spacer(Modifier.width(14.dp))
    Column(Modifier.weight(1f)) {
      BasicText(title, style = palette.text.smallTitle)
      Spacer(Modifier.height(3.dp))
      BasicText(body, style = palette.text.body)
    }
  }
}

/** A thin rule with space around it. */
@Composable
fun SectionDivider() {
  val palette = DiscoverPalette.current
  Spacer(Modifier.height(24.dp))
  Box(Modifier.fillMaxWidth().height(1.dp).background(palette.colors.divider))
  Spacer(Modifier.height(20.dp))
}

@Preview(widthDp = 411, heightDp = 900)
@Composable
private fun DesignSystemPanelPreview() = DiscoverPreview { DesignSystemPanel() }
