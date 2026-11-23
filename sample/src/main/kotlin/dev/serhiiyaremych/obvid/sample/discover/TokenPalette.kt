// Copyright 2026, Serhii Yaremych (desugar-64) and the Obvid project contributors
// SPDX-License-Identifier: Apache-2.0

package dev.serhiiyaremych.obvid.sample.discover

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.serhiiyaremych.obvid.sample.LocalShadowImpl
import dev.serhiiyaremych.obvid.sample.measuredDropShadow
import dev.serhiiyaremych.obvid.sample.measuredInnerShadow

private val SwatchShape = DiscoverShapes.Swatch

/** The five [DepthTokens], one swatch each: three drop shadows and two inner shadows. */
@Composable
fun TokenPalette(modifier: Modifier = Modifier) {
  val palette = DiscoverPalette.current
  val impl = LocalShadowImpl.current
  Row(modifier, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
    Swatch("Surface", "(ambient)", Modifier.measuredDropShadow(SwatchShape, palette.depth.surface, impl).background(palette.colors.raised, SwatchShape))
    Swatch("Lift", "(8 dp)", Modifier.measuredDropShadow(SwatchShape, palette.depth.lift, impl).background(palette.colors.raised, SwatchShape))
    Swatch("Press", "(inner)", Modifier.background(palette.colors.raised, SwatchShape).measuredInnerShadow(SwatchShape, palette.depth.press, impl))
    Swatch("Accent", "(colored)", Modifier.measuredDropShadow(SwatchShape, palette.depth.accent, impl).background(palette.brushes.accent, SwatchShape))
    Swatch("Focus", "(rim)", Modifier.background(palette.colors.raised, SwatchShape).measuredInnerShadow(SwatchShape, palette.depth.focus, impl))
  }
}

/** A square drawn with [surface], its name and note under it. */
@Composable
private fun RowScope.Swatch(name: String, note: String, surface: Modifier) {
  val palette = DiscoverPalette.current
  Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
    Box(Modifier.fillMaxWidth().aspectRatio(1f).then(surface))
    Spacer(Modifier.height(10.dp))
    BasicText(name, style = palette.text.captionStrong)
    BasicText(note, style = palette.text.caption)
  }
}

@Preview(widthDp = 420, uiMode = Configuration.UI_MODE_NIGHT_NO)
@Preview(widthDp = 420, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun TokenPalettePreview() = DiscoverPreview { TokenPalette(Modifier.width(360.dp)) }
