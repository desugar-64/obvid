// Copyright 2026, Serhii Yaremych (desugar-64) and the Obvid project contributors
// SPDX-License-Identifier: Apache-2.0

package dev.serhiiyaremych.obvid.sample.app

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.serhiiyaremych.obvid.sample.DemoPalette
import dev.serhiiyaremych.obvid.sample.LocalDemoPalette
import dev.serhiiyaremych.obvid.sample.discover.DiscoverPalette

/**
 * Material 3 Expressive with the demo's generated scheme ([DemoPalette]) in the system's light or dark mode, and the
 * expressive motion scheme. No dynamic (wallpaper) color: the demo looks the same on every device. Provides
 * [LocalDemoPalette], so the Discover screens and the scenes follow the same mode.
 */
@Composable
fun DemoTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
  val palette = if (dark) DemoPalette.Dark else DemoPalette.Light
  CompositionLocalProvider(LocalDemoPalette provides palette) {
    MaterialExpressiveTheme(colorScheme = palette.scheme, motionScheme = MotionScheme.expressive(), content = content)
  }
}

/** Preview frame of the shell components: [DemoTheme] (the preview's night mode) on the page background, with room for shadows. */
@Composable
internal fun DemoPreview(content: @Composable () -> Unit) {
  DemoTheme {
    Box(Modifier.background(DiscoverPalette.current.brushes.background).padding(24.dp)) { content() }
  }
}

/** Light and dark preview variants for a component drawn in [DemoPreview]. */
@Preview(name = "Light", uiMode = Configuration.UI_MODE_NIGHT_NO)
@Preview(name = "Dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
internal annotation class LightDarkPreviews
