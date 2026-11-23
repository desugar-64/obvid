// Copyright 2026, Serhii Yaremych (desugar-64) and the Obvid project contributors
// SPDX-License-Identifier: Apache-2.0

package dev.serhiiyaremych.obvid.sample.discover

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.serhiiyaremych.obvid.sample.DemoPalette
import dev.serhiiyaremych.obvid.sample.LocalDemoPalette
import dev.serhiiyaremych.obvid.sample.LocalShadowImpl

/**
 * Preview frame of the Discover components: the page background, light or dark by the preview's night mode, with room
 * for the shadows. Shadows draw with
 * [LocalShadowImpl]'s default, Obvid. Photos are not decoded in previews; [Photo] shows its placeholder.
 */
@Composable
internal fun DiscoverPreview(content: @Composable () -> Unit) {
  CompositionLocalProvider(LocalDemoPalette provides if (isSystemInDarkTheme()) DemoPalette.Dark else DemoPalette.Light) {
    Box(Modifier.background(DiscoverPalette.current.brushes.background).padding(28.dp)) { content() }
  }
}
