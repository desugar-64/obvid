// Copyright 2026, Serhii Yaremych (desugar-64) and the Obvid project contributors
// SPDX-License-Identifier: Apache-2.0

package dev.serhiiyaremych.obvid.sample.discover

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.serhiiyaremych.obvid.sample.LocalShadowImpl
import dev.serhiiyaremych.obvid.sample.measuredDropShadow

/** The app icon: the brand gradient with its [rim], on a green [outerGlow] under the [DepthTokens.accent] shadow. */
@Composable
fun AppIcon(modifier: Modifier = Modifier, size: Dp = 56.dp) {
  val palette = DiscoverPalette.current
  val impl = LocalShadowImpl.current
  Box(
    modifier
      .size(size)
      .outerGlow(impl, palette, DiscoverShapes.IconTile, palette.colors.action)
      .measuredDropShadow(DiscoverShapes.IconTile, palette.depth.accent, impl)
      .background(palette.brushes.brand, DiscoverShapes.IconTile)
      .rim(impl, palette, DiscoverShapes.IconTile),
    contentAlignment = Alignment.Center,
  ) {
    LineIcon(DiscoverIcon.Mountain, Modifier.size(size * 0.5f), color = { Color.White })
  }
}

/** A round [raised] icon button, announced as [contentDescription]; [badge] adds a small accent dot. */
@Composable
fun RoundButton(icon: DiscoverIcon, contentDescription: String, onClick: () -> Unit, modifier: Modifier = Modifier, badge: Boolean = false) {
  val palette = DiscoverPalette.current
  Box(
    modifier
      .size(48.dp)
      .raised(LocalShadowImpl.current, palette, DiscoverShapes.Circle)
      .clickable(interactionSource = null, indication = null, role = Role.Button, onClick = onClick)
      .semantics { this.contentDescription = contentDescription },
    contentAlignment = Alignment.Center,
  ) {
    LineIcon(icon, Modifier.size(22.dp), color = { palette.colors.text })
    if (badge) {
      Box(Modifier.align(Alignment.TopEnd).offset((-9).dp, 9.dp).size(9.dp).background(palette.colors.action, DiscoverShapes.Circle))
    }
  }
}

/** App icon, title and subtitle, search and notifications. Static: 4 drop and 3 inner shadows. */
@Composable
fun DiscoverHeader(modifier: Modifier = Modifier) {
  val palette = DiscoverPalette.current
  Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
    AppIcon()
    Spacer(Modifier.width(14.dp))
    Column(Modifier.weight(1f)) {
      BasicText("Discover", style = palette.text.headline, maxLines = 1)
      BasicText("Stories, ideas, and places", style = palette.text.subtitle, maxLines = 1)
    }
    RoundButton(DiscoverIcon.Search, "Search", onClick = {})
    Spacer(Modifier.width(12.dp))
    RoundButton(DiscoverIcon.Bell, "Notifications", onClick = {}, badge = true)
  }
}

@Preview(widthDp = 420, uiMode = Configuration.UI_MODE_NIGHT_NO)
@Preview(widthDp = 420, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun DiscoverHeaderPreview() = DiscoverPreview { DiscoverHeader() }
