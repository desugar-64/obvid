// Copyright 2026, Serhii Yaremych (desugar-64) and the Obvid project contributors
// SPDX-License-Identifier: Apache-2.0

package dev.serhiiyaremych.obvid.sample.discover

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import dev.serhiiyaremych.obvid.sample.LocalShadowImpl
import dev.serhiiyaremych.obvid.sample.RevealTraceSection
import dev.serhiiyaremych.obvid.sample.TierTargetTag
import dev.serhiiyaremych.obvid.sample.beginEvent
import dev.serhiiyaremych.obvid.sample.endEvent
import dev.serhiiyaremych.obvid.sample.measuredDropShadow
import dev.serhiiyaremych.obvid.sample.measuredInnerShadow

/**
 * A [WarmUpRow] until a tap anywhere on [TierTargetTag], then [content]. The tap opens [RevealTraceSection], closed two
 * frames after.
 */
@Composable
fun DiscoverReveal(content: @Composable () -> Unit) {
  var revealed by remember { mutableStateOf(false) }
  var cookie by remember { mutableIntStateOf(0) }
  LaunchedEffect(revealed) {
    if (!revealed) return@LaunchedEffect
    withFrameNanos {}
    withFrameNanos {}
    endEvent(RevealTraceSection, cookie)
  }
  Box(
    Modifier
      .fillMaxSize()
      .testTag(TierTargetTag)
      .clickable(enabled = !revealed, interactionSource = null, indication = null) {
        cookie = beginEvent(RevealTraceSection)
        revealed = true
      },
  ) {
    if (revealed) content() else WarmUpRow(Modifier.statusBarsPadding().padding(20.dp))
  }
}

private val WarmUpShadow = Shadow(radius = 3.dp, color = Color.Black, offset = DpOffset(0.dp, 1.dp), alpha = 0.4f)

/**
 * A drop and an inner shadow of a geometry used nowhere else, and text: the implementation's classes, native code and
 * text rendering load before the reveal, not in its first frame.
 */
@Composable
private fun WarmUpRow(modifier: Modifier = Modifier) {
  val palette = DiscoverPalette.current
  Row(modifier, horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
    val impl = LocalShadowImpl.current
    val circle = DiscoverShapes.Circle
    Box(Modifier.size(30.dp).measuredDropShadow(circle, WarmUpShadow, impl).background(palette.colors.raised, circle))
    Box(Modifier.size(30.dp).background(palette.colors.raised, circle).measuredInnerShadow(circle, WarmUpShadow, impl))
    BasicText("Discover", style = palette.text.subtitle)
  }
}

@Preview
@Composable
private fun WarmUpRowPreview() = DiscoverPreview { WarmUpRow() }
