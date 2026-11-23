// Copyright 2026, Serhii Yaremych (desugar-64) and the Obvid project contributors
// SPDX-License-Identifier: Apache-2.0

package dev.serhiiyaremych.obvid.sample.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.serhiiyaremych.obvid.sample.ShadowImpl
import dev.serhiiyaremych.obvid.sample.discover.DepthPoses
import dev.serhiiyaremych.obvid.sample.discover.DepthTokens
import dev.serhiiyaremych.obvid.sample.discover.DiscoverPalette
import dev.serhiiyaremych.obvid.sample.discover.blend
import dev.serhiiyaremych.obvid.sample.discover.selectionFraction
import dev.serhiiyaremych.obvid.sample.measuredDropShadow

private val Options = listOf(ShadowImpl.Compose to "Compose", ShadowImpl.Cpu to "Obvid")

/** Segment height: the minimum touch target, so the shadow's bounds are the button's. */
private val SegmentHeight = 48.dp

/**
 * The global Compose / Obvid switch: a connected button group of two [ToggleButton]s, single choice, announced as radio
 * buttons. Checked, Material's primary toggle colors on the primary [DepthTokens.accent] shadow; unchecked, an unselected
 * category chip's raised fill on its faint dark drop ([DepthPoses.chipGlowRest]). The shadow blends between the two in
 * the draw phase and is drawn by the implementation the switch shows, so the control itself changes with it.
 */
@Composable
fun ImplSwitch(impl: ShadowImpl, onChange: (ShadowImpl) -> Unit, modifier: Modifier = Modifier) {
  val palette = DiscoverPalette.current
  Row(modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
    Options.forEachIndexed { index, (value, label) ->
      val checked = impl == value
      val fraction = selectionFraction(checked, "implSegment")
      ToggleButton(
        checked = checked,
        onCheckedChange = { onChange(value) },
        modifier = Modifier
          .semantics { role = Role.RadioButton }
          .height(SegmentHeight)
          .measuredDropShadow(ButtonGroupDefaults.connectedButtonCheckedShape, impl) {
            blend(palette.poses.chipGlowRest, palette.depth.accent, fraction.value)
          },
        colors = ToggleButtonDefaults.colors(containerColor = palette.colors.raised),
        shapes = if (index == 0) ButtonGroupDefaults.connectedLeadingButtonShapes() else ButtonGroupDefaults.connectedTrailingButtonShapes(),
      ) {
        Text(label, maxLines = 1)
      }
    }
  }
}

@LightDarkPreviews
@Composable
private fun ImplSwitchPreview() = DemoPreview {
  var impl by remember { mutableStateOf(ShadowImpl.Cpu) }
  ImplSwitch(impl, { impl = it })
}
