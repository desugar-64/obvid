// Copyright 2026, Serhii Yaremych (desugar-64) and the Obvid project contributors
// SPDX-License-Identifier: Apache-2.0

package dev.serhiiyaremych.obvid.sample.discover

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.serhiiyaremych.obvid.sample.LocalShadowImpl
import dev.serhiiyaremych.obvid.sample.measuredDropShadow
import dev.serhiiyaremych.obvid.sample.measuredInnerShadow

/**
 * Bookmark toggle (a checkbox). Off: a low dark drop shadow and a [DepthTokens.sheen]. On: the drop turns green and
 * spreads, the inner shadow deepens into a well, the icon fills. [checked] is read here only; the animation only in
 * the draw phase.
 */
@Composable
fun BookmarkToggle(checked: () -> Boolean, onCheckedChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
  val palette = DiscoverPalette.current
  val impl = LocalShadowImpl.current
  val on = checked()
  val fraction = selectionFraction(on, "bookmark")
  Box(
    modifier
      .size(36.dp)
      .measuredDropShadow(DiscoverShapes.Toggle, impl) { blend(palette.poses.bookmarkDropOff, palette.poses.bookmarkDropOn, fraction.value) }
      .background(palette.colors.raised, DiscoverShapes.Toggle)
      .measuredInnerShadow(DiscoverShapes.Toggle, impl) { blend(palette.depth.sheen, palette.poses.bookmarkWell, fraction.value) }
      .toggleable(on, interactionSource = null, indication = null, role = Role.Checkbox, onValueChange = onCheckedChange)
      .semantics { contentDescription = "Bookmark" },
    contentAlignment = Alignment.Center,
  ) {
    LineIcon(DiscoverIcon.Bookmark, Modifier.size(20.dp), color = { palette.colors.text }, alpha = { 1f - fraction.value })
    LineIcon(DiscoverIcon.BookmarkFilled, Modifier.size(20.dp), color = { palette.colors.action }, alpha = { fraction.value })
  }
}

/** A toggle that keeps its own state, starting at [initial]. */
@Composable
fun StatefulBookmarkToggle(initial: Boolean, modifier: Modifier = Modifier) {
  var checked by rememberSaveable { mutableStateOf(initial) }
  BookmarkToggle({ checked }, { checked = it }, modifier)
}

@Preview(uiMode = Configuration.UI_MODE_NIGHT_NO)
@Preview(uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun BookmarkTogglePreview() = DiscoverPreview {
  Row {
    StatefulBookmarkToggle(initial = false)
    Spacer(Modifier.width(24.dp))
    StatefulBookmarkToggle(initial = true)
  }
}
