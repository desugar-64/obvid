// Copyright 2026, Serhii Yaremych (desugar-64) and the Obvid project contributors
// SPDX-License-Identifier: Apache-2.0

package dev.serhiiyaremych.obvid.sample.discover

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Space between the bottom bar and the navigation-bar inset. */
val NavBarMargin = 12.dp

/** Length of the scrim's fade above what it hides. */
val ScrimFade = 32.dp

/** Bottom chrome of the home screen above the navigation-bar inset: the bar, its margin and the scrim's fade. */
val BottomChromeHeight = ScrimFade + NavBarHeight + NavBarMargin

/**
 * The screen background closing over scrolled content at the bottom edge: a [fadeHeight] fade, then opaque for
 * [opaqueHeight] and the navigation-bar inset below it, so nothing shows behind the gesture area. Draw only: touches
 * pass through.
 */
@Composable
fun BottomScrim(fadeHeight: Dp, opaqueHeight: Dp, modifier: Modifier = Modifier) {
  val palette = DiscoverPalette.current
  Column(modifier.fillMaxWidth()) {
    Spacer(Modifier.fillMaxWidth().height(fadeHeight).background(palette.brushes.scrimFade))
    Spacer(Modifier.fillMaxWidth().background(palette.colors.background).navigationBarsPadding().height(opaqueHeight))
  }
}

@Preview(widthDp = 411, heightDp = 160)
@Composable
private fun BottomScrimPreview() = DiscoverPreview { BottomScrim(ScrimFade, NavBarHeight) }
