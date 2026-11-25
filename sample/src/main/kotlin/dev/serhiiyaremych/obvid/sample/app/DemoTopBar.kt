// Copyright 2026, Serhii Yaremych (desugar-64) and the Obvid project contributors
// SPDX-License-Identifier: Apache-2.0

package dev.serhiiyaremych.obvid.sample.app

import android.content.res.Configuration
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.serhiiyaremych.obvid.ShadowBuilds
import dev.serhiiyaremych.obvid.sample.LocalDemoLayout
import dev.serhiiyaremych.obvid.sample.ShadowImpl
import dev.serhiiyaremych.obvid.sample.discover.DiscoverIcon
import dev.serhiiyaremych.obvid.sample.discover.LineIcon

/**
 * How far an icon button's glyph sits inside its 48 dp touch target (24 dp glyph, centred): the actions' end padding
 * is the margin less this, so the last glyph ends at the margin.
 */
private val IconButtonGlyphInset = 12.dp

/**
 * The app bar: title, the [ImplSwitch] (switching [impl]), the [BuildsToggle] and the frame HUD switch, in a top app
 * bar's metrics (64 dp row below the status bar, title in `titleLarge`). No background of its own: it lies over the
 * content on the top chrome's scrim ([TopChrome]).
 *
 * A plain row rather than Material's `TopAppBar`, which clips its content to the bar: nothing here clips, so the
 * selected segment's shadow spills over whatever lies below. Its title starts at [LocalDemoLayout]'s margin and the
 * last action's glyph ends there.
 */
@Composable
fun DemoTopBar(
  impl: ShadowImpl,
  onImplChange: (ShadowImpl) -> Unit,
  builds: ShadowBuilds,
  onBuildsChange: (ShadowBuilds) -> Unit,
  hud: Boolean,
  onHudChange: (Boolean) -> Unit,
  modifier: Modifier = Modifier,
) {
  val margin = LocalDemoLayout.current.margin
  val colors = MaterialTheme.colorScheme
  Row(
    modifier
      .fillMaxWidth()
      .windowInsetsPadding(TopAppBarDefaults.windowInsets)
      .height(TopAppBarDefaults.TopAppBarExpandedHeight)
      .padding(start = margin, end = margin - IconButtonGlyphInset)
      .semantics { isTraversalGroup = true },
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(
      "Obvid",
      Modifier.weight(1f),
      color = colors.onSurface,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
      style = MaterialTheme.typography.titleLarge,
    )
    CompositionLocalProvider(LocalContentColor provides colors.onSurfaceVariant) {
      ImplSwitch(impl, onImplChange)
      Spacer(Modifier.width(4.dp))
      BuildsToggle(builds, onBuildsChange, enabled = impl == ShadowImpl.Cpu)
      HudToggle(hud, onHudChange)
    }
  }
}

/**
 * Obvid's first builds on worker threads ([ShadowBuilds.Parallel]) or in the draw; announced as a switch. Tertiary (the
 * action green) while parallel. Only Obvid's shadows build this way: disabled while Compose's are shown.
 */
@Composable
fun BuildsToggle(
  builds: ShadowBuilds,
  onChange: (ShadowBuilds) -> Unit,
  enabled: Boolean,
  modifier: Modifier = Modifier,
) {
  IconToggleButton(
    checked = builds == ShadowBuilds.Parallel,
    onCheckedChange = { onChange(if (it) ShadowBuilds.Parallel else ShadowBuilds.Inline) },
    enabled = enabled,
    modifier = modifier.semantics {
      role = Role.Switch
      contentDescription = "Parallel shadow builds"
    },
    colors = IconButtonDefaults.iconToggleButtonColors(checkedContentColor = MaterialTheme.colorScheme.tertiary),
  ) {
    val tint = LocalContentColor.current
    LineIcon(DiscoverIcon.Layers, Modifier.size(24.dp), color = { tint })
  }
}

/** Shows or hides the frame HUD; announced as a switch. Tertiary (the action green) while on. */
@Composable
fun HudToggle(checked: Boolean, onCheckedChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
  IconToggleButton(
    checked = checked,
    onCheckedChange = onCheckedChange,
    modifier = modifier.semantics {
      role = Role.Switch
      contentDescription = "Frame stats"
    },
    colors = IconButtonDefaults.iconToggleButtonColors(checkedContentColor = MaterialTheme.colorScheme.tertiary),
  ) {
    val tint = LocalContentColor.current
    LineIcon(DiscoverIcon.Pulse, Modifier.size(24.dp), color = { tint })
  }
}

@Preview(widthDp = 411, uiMode = Configuration.UI_MODE_NIGHT_NO)
@Preview(widthDp = 411, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun DemoTopBarPreview() = DemoPreview {
  var impl by remember { mutableStateOf(ShadowImpl.Cpu) }
  var builds by remember { mutableStateOf(ShadowBuilds.Inline) }
  var hud by remember { mutableStateOf(false) }
  DemoTopBar(impl, { impl = it }, builds, { builds = it }, hud, { hud = it })
}
