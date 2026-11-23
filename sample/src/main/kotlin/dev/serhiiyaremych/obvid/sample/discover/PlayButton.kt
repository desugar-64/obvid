// Copyright 2026, Serhii Yaremych (desugar-64) and the Obvid project contributors
// SPDX-License-Identifier: Apache-2.0

package dev.serhiiyaremych.obvid.sample.discover

import android.content.res.Configuration
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
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
import dev.serhiiyaremych.obvid.sample.measuredInnerShadow
import kotlinx.coroutines.flow.collectLatest
import kotlin.math.roundToInt

/** Press depth: in, then out, ms. */
private const val PressInMillis = 120
private const val PressOutMillis = 280

/**
 * Play button: [tint] gradient on an [outerGlow] and a drop shadow of the same tint, with an inner shadow and the rim
 * border. Pressing turns the inner shadow from the [DepthTokens.rim] light into [DepthTokens.press], fades the rim
 * border and tightens the drop shadow; release lets it back out. A tap
 * shorter than [PressInMillis] still presses fully before releasing, so every tap animates the same way. The depth is
 * read in the draw phase only.
 */
@Composable
fun PlayButton(tint: Color, modifier: Modifier = Modifier, width: Dp = 54.dp, height: Dp = 36.dp, onClick: () -> Unit = {}) {
  val palette = DiscoverPalette.current
  val impl = LocalShadowImpl.current
  val depth = remember { Animatable(0f) }
  val interactions = remember { MutableInteractionSource() }
  LaunchedEffect(interactions) {
    interactions.interactions.collectLatest {
      when (it) {
        is PressInteraction.Press -> depth.animateTo(1f, tween(PressInMillis))
        is PressInteraction.Release, is PressInteraction.Cancel -> {
          depth.animateTo(1f, tween(((1f - depth.value) * PressInMillis).roundToInt()))
          depth.animateTo(0f, tween(PressOutMillis))
        }
      }
    }
  }
  val brush = remember(tint) { palette.brushes.tint(tint) }
  val dropRest = remember(tint) { palette.poses.playDropRest.withColor(tint) }
  val dropPressed = remember(tint) { palette.poses.playDropPressed.withColor(tint) }
  Box(
    modifier
      .size(width, height)
      .outerGlow(impl, palette, DiscoverShapes.Button, tint)
      .measuredDropShadow(DiscoverShapes.Button, impl) { blend(dropRest, dropPressed, depth.value) }
      .background(brush, DiscoverShapes.Button)
      .measuredInnerShadow(DiscoverShapes.Button, impl) { blend(palette.depth.rim, palette.depth.press, depth.value) }
      .rimBorder(DiscoverShapes.Button) { 1f - depth.value }
      .clickable(interactions, indication = null, role = Role.Button, onClick = onClick)
      .semantics { contentDescription = "Play" },
    contentAlignment = Alignment.Center,
  ) {
    LineIcon(DiscoverIcon.Play, Modifier.size(18.dp), color = { palette.colors.onAction })
  }
}

@Preview(uiMode = Configuration.UI_MODE_NIGHT_NO)
@Preview(uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun PlayButtonPreview() = DiscoverPreview {
  val palette = DiscoverPalette.current
  Row {
    PlayButton(palette.tint(Topic.Travel))
    Spacer(Modifier.width(20.dp))
    PlayButton(palette.tint(Topic.Design))
    Spacer(Modifier.width(20.dp))
    PlayButton(palette.colors.action, width = 84.dp, height = 42.dp)
  }
}
