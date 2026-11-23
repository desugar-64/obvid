// Copyright 2026, Serhii Yaremych (desugar-64) and the Obvid project contributors
// SPDX-License-Identifier: Apache-2.0

package dev.serhiiyaremych.obvid.sample.discover

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.serhiiyaremych.obvid.sample.LocalShadowImpl
import dev.serhiiyaremych.obvid.sample.measuredDropShadow
import dev.serhiiyaremych.obvid.sample.measuredInnerShadow

/** Resource id prefix of the chips (`discoverChip0` …), the benchmark's tap targets. */
const val DiscoverChipTag = "discoverChip"

/**
 * A category chip (a tab). Two drop and two inner shadows, animated in color and alpha only, in the draw phase:
 * unselected, a faint dark drop and a dark inner edge; selected, the primary [outerGlow] under the [DepthTokens.accent]
 * shadow, the accent gradient and its [rim].
 */
@Composable
fun CategoryChip(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
  val palette = DiscoverPalette.current
  val impl = LocalShadowImpl.current
  val fraction = selectionFraction(selected, "chip")
  Box(
    modifier
      .height(40.dp)
      .outerGlow(impl, palette, DiscoverShapes.Pill, palette.colors.primary) { fraction.value }
      .measuredDropShadow(DiscoverShapes.Pill, impl) { blend(palette.poses.chipGlowRest, palette.depth.accent, fraction.value) }
      .accentFill(DiscoverShapes.Pill, rest = palette.colors.raised, accent = palette.brushes.accent) { fraction.value }
      .measuredInnerShadow(DiscoverShapes.Pill, impl) { blend(palette.poses.chipEdgeRest, palette.poses.chipEdgeOff, fraction.value) }
      .rim(impl, palette, DiscoverShapes.Pill) { fraction.value }
      .selectable(selected, interactionSource = null, indication = null, role = Role.Tab, onClick = onClick)
      .padding(horizontal = 12.dp),
    contentAlignment = Alignment.Center,
  ) {
    BasicText(label, style = palette.text.label, maxLines = 1, color = { mix(palette.colors.muted, palette.colors.onPrimary, fraction.value) })
  }
}

/** The category chips, equal widths; [selected] is read here, so a switch recomposes this row and no further up. */
@Composable
fun CategoryChipRow(labels: List<String>, selected: () -> Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
  val current = selected()
  Row(modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
    labels.forEachIndexed { index, label ->
      CategoryChip(label, current == index, { onSelect(index) }, Modifier.weight(1f).testTag(DiscoverChipTag + index))
    }
  }
}

@Preview(uiMode = Configuration.UI_MODE_NIGHT_NO)
@Preview(uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun CategoryChipRowPreview() = DiscoverPreview {
  var selected by remember { mutableIntStateOf(0) }
  CategoryChipRow(CategoryLabels, { selected }, { selected = it }, Modifier.width(380.dp))
}
