// Copyright 2026, Serhii Yaremych (desugar-64) and the Obvid project contributors
// SPDX-License-Identifier: Apache-2.0

package dev.serhiiyaremych.obvid.sample.app

import androidx.compose.animation.rememberSplineBasedDecay
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.material3.ToggleButtonShapes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.serhiiyaremych.obvid.sample.FieldScreen
import dev.serhiiyaremych.obvid.sample.LocalDemoLayout
import dev.serhiiyaremych.obvid.sample.LocalShadowImpl
import dev.serhiiyaremych.obvid.sample.discover.DepthTokens
import dev.serhiiyaremych.obvid.sample.discover.DiscoverPalette
import dev.serhiiyaremych.obvid.sample.discover.DiscoverShapes
import dev.serhiiyaremych.obvid.sample.discover.blend
import dev.serhiiyaremych.obvid.sample.discover.selectionFraction
import dev.serhiiyaremych.obvid.sample.discover.withAlpha
import dev.serhiiyaremych.obvid.sample.discover.withColor
import dev.serhiiyaremych.obvid.sample.measuredDropShadow

/**
 * The morphing field (`t4_field`'s [FieldScreen]) filling the window under [camera]: dragged and flung on both axes, or
 * flown by the scripted camera while the [AutoPanToggle] is on; the toggle sits at the bottom of [padding], clear of the
 * chrome. Reads no camera state itself, so a toggle recomposes the toggle only.
 */
@Composable
fun ShapesTab(camera: PannableCamera, padding: PaddingValues, modifier: Modifier = Modifier) {
  val scope = rememberCoroutineScope()
  val decay = rememberSplineBasedDecay<Offset>()
  val glide = MaterialTheme.motionScheme.slowSpatialSpec<Float>()
  Box(modifier.fillMaxSize()) {
    FieldScreen(Modifier.panCamera(camera, scope, decay), camera)
    Box(Modifier.fillMaxSize().padding(padding).padding(horizontal = LocalDemoLayout.current.margin), contentAlignment = Alignment.BottomCenter) {
      AutoPanToggle(checked = { camera.autoPan }, onCheckedChange = { camera.setAutoPan(it, scope, glide) })
    }
  }
}

private val PillShapes = ToggleButtonShapes(DiscoverShapes.Pill, DiscoverShapes.Pill, DiscoverShapes.Pill)

/**
 * The auto-pan switch: a pill [ToggleButton] on a [DepthTokens.lift] shadow, checked in tertiary (the action green) with
 * a glow of it under the button while on (faded
 * by alpha, read in the draw phase); announced as a switch. [checked] is read here only.
 */
@Composable
fun AutoPanToggle(checked: () -> Boolean, onCheckedChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
  val on = checked()
  val impl = LocalShadowImpl.current
  val palette = DiscoverPalette.current
  val scheme = MaterialTheme.colorScheme
  val glow = remember(palette) { palette.depth.accent.withColor(palette.colors.action) }
  val glowOff = remember(glow) { glow.withAlpha(0f) }
  val fraction = selectionFraction(on, "autoPan")
  ToggleButton(
    checked = on,
    onCheckedChange = onCheckedChange,
    modifier = modifier
      .semantics { role = Role.Switch }
      .height(48.dp)
      .measuredDropShadow(DiscoverShapes.Pill, palette.depth.lift, impl)
      .measuredDropShadow(DiscoverShapes.Pill, impl) { blend(glowOff, glow, fraction.value) },
    shapes = PillShapes,
    colors = ToggleButtonDefaults.colors(
      containerColor = scheme.surfaceContainerHighest,
      contentColor = scheme.onSurface,
      checkedContainerColor = scheme.tertiary,
      checkedContentColor = scheme.onTertiary,
    ),
  ) {
    Text("Auto-pan", maxLines = 1)
  }
}

@LightDarkPreviews
@Composable
private fun AutoPanTogglePreview() = DemoPreview {
  var on by remember { mutableStateOf(true) }
  AutoPanToggle({ on }, { on = it })
}
