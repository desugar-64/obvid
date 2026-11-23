// Copyright 2026, Serhii Yaremych (desugar-64) and the Obvid project contributors
// SPDX-License-Identifier: Apache-2.0

package dev.serhiiyaremych.obvid.sample.app

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import dev.serhiiyaremych.obvid.LocalShadowExtent
import dev.serhiiyaremych.obvid.sample.DemoLayout
import dev.serhiiyaremych.obvid.sample.FrameHud
import dev.serhiiyaremych.obvid.sample.LocalDemoLayout
import dev.serhiiyaremych.obvid.sample.LocalShadowImpl
import dev.serhiiyaremych.obvid.sample.ShadowImpl
import dev.serhiiyaremych.obvid.sample.discover.CategoryChipRow
import dev.serhiiyaremych.obvid.sample.discover.CategoryLabels
import dev.serhiiyaremych.obvid.sample.discover.ChipsBottom
import dev.serhiiyaremych.obvid.sample.discover.DiscoverChrome
import dev.serhiiyaremych.obvid.sample.discover.DiscoverHeader
import dev.serhiiyaremych.obvid.sample.discover.DiscoverPalette
import dev.serhiiyaremych.obvid.sample.discover.DiscoverState
import dev.serhiiyaremych.obvid.sample.readFullExtentSetting
import dev.serhiiyaremych.obvid.sample.readNoInnerShadowsSetting

/**
 * The demo app: four tabs ([DemoTab]) under one app bar holding the Compose / Obvid switch and the frame HUD switch.
 * Every shadow on screen, the app's own chrome included, draws with the switched implementation ([LocalShadowImpl]).
 *
 * Edge to edge: each tab's scene fills the window, behind the system bars, and the chrome lies over it ([ChromeScaffold]):
 * on top the app bar, and on the Feed's home screen the Discover header, which scrolls away with the feed, and the
 * category chips, which then pin under the bar ([TopChrome]); at the bottom the floating bar. Each sits on a scrim of
 * the page color fading out past its edge. The scenes are padded clear of the chrome by [DemoPadding], measured as the
 * chrome lays out.
 *
 * A switch rebuilds the tab's scene and the Feed's header and chips ([key]): every shadow modifier node is created anew
 * with empty caches, as on the first frame an app using that implementation draws, so the comparison starts cold each
 * time instead of carrying one implementation's warm state into the other. What the user did in a tab (feed position,
 * bookmarks, open card, camera) lives above the rebuild and survives it, as do the tab, the implementation and the HUD
 * across configuration changes. The Feed's header, chips and the bottom bar step aside while a feed card is open.
 * Margins and gaps follow the window width ([DemoLayout.forWindowWidth]).
 */
@Composable
fun DemoApp(initialImpl: ShadowImpl, initialHud: Boolean) {
  readNoInnerShadowsSetting(LocalContext.current)
  val extent = readFullExtentSetting(LocalContext.current)
  var tab by rememberSaveable { mutableStateOf(DemoTab.Feed) }
  var impl by rememberSaveable { mutableStateOf(initialImpl) }
  var hud by rememberSaveable { mutableStateOf(initialHud) }
  val feed = rememberSaveable(saver = DiscoverState.Saver) { DiscoverState() }
  val camera = rememberSaveable(saver = PannableCamera.Saver) { PannableCamera() }
  BackHandler(enabled = tab != DemoTab.Feed) { tab = DemoTab.Feed }
  val density = LocalDensity.current
  val windowWidth = with(density) { LocalWindowInfo.current.containerSize.width.toDp() }
  val layout = remember(windowWidth) { DemoLayout.forWindowWidth(windowWidth) }
  val metrics = remember { ChromeMetrics() }
  val padding = remember(metrics, density, layout) { DemoPadding(metrics, density, layout) }
  DemoTheme {
    CompositionLocalProvider(
      LocalShadowImpl provides impl,
      LocalDemoLayout provides layout,
      LocalShadowExtent provides extent,
    ) {
      Box(Modifier.fillMaxSize().background(DiscoverPalette.current.brushes.background)) {
        ChromeScaffold(
          metrics,
          topChrome = {
            DemoTopChrome(
              metrics,
              impl,
              { impl = it },
              hud,
              { hud = it },
              feed,
              feedHome = { tab == DemoTab.Feed && feed.detail == null },
            )
          },
          bottomChrome = { BottomBar(tab, { tab = it }, visible = { tab != DemoTab.Feed || feed.detail == null }) },
        ) {
          key(impl) { TabContent(tab, feed, camera, padding) }
        }
        if (hud) FrameHud(Modifier.align(Alignment.TopCenter).offset { IntOffset(0, metrics.bar + HudGap.roundToPx()) })
      }
    }
  }
}

/** Space between the app bar and the frame HUD. */
private val HudGap = 12.dp

/**
 * Content padding of the scenes, from the chrome's measured sizes ([ChromeMetrics]): [scene] clear of the app bar, the
 * bottom chrome and their scrims; [discover], the Feed's: its list below the pinned chips by the layout's gap, within
 * the margins and clear of the bottom chrome, and its detail screen below the app bar.
 */
@Stable
class DemoPadding(metrics: ChromeMetrics, density: Density, layout: DemoLayout) {
  private val fade = with(density) { ChromeFade.roundToPx() }
  private val feedGap = with(density) { (layout.gap - ChipsBottom).roundToPx() }

  val scene: PaddingValues = ChromePadding(density, 0.dp, top = { metrics.bar + fade }, bottom = { metrics.bottom })

  val discover = DiscoverChrome(
    feed = ChromePadding(
      density,
      layout.margin,
      top = { metrics.bar + metrics.header + metrics.pinned + feedGap },
      bottom = { metrics.bottom },
    ),
    detail = ChromePadding(density, 0.dp, top = { metrics.bar }, bottom = { 0 }),
  )
}

/**
 * The top chrome: [DemoTopBar], and while [feedHome] holds, the Discover header and category chips (rebuilt with
 * [impl]), collapsing as [feed]'s list scrolls. [feedHome] is read here, so opening a card or switching tabs recomposes
 * the chrome alone; their fade and the scroll are read in the layout and draw phases.
 */
@Composable
private fun DemoTopChrome(
  metrics: ChromeMetrics,
  impl: ShadowImpl,
  onImplChange: (ShadowImpl) -> Unit,
  hud: Boolean,
  onHudChange: (Boolean) -> Unit,
  feed: DiscoverState,
  feedHome: () -> Boolean,
) {
  val shown = feedHome()
  val expanded = animateFloatAsState(if (shown) 1f else 0f, MaterialTheme.motionScheme.defaultEffectsSpec(), label = "feedChrome")
  val fading by remember { derivedStateOf { expanded.value > 0f } }
  val present = shown || fading
  val layout = LocalDemoLayout.current
  TopChrome(
    metrics,
    scrim = DiscoverPalette.current.colors.background,
    expanded = expanded,
    scrolled = { feed.listState.scrolledFromTop() },
    headerGap = layout.gap,
    bar = { DemoTopBar(impl, onImplChange, hud, onHudChange) },
    header = if (present) {
      { key(impl) { DiscoverHeader(Modifier.padding(start = layout.margin + 4.dp, end = layout.margin + 4.dp, top = 12.dp)) } }
    } else {
      null
    },
    pinned = if (present) {
      {
        key(impl) {
          CategoryChipRow(
            CategoryLabels,
            selected = { feed.category },
            onSelect = feed::selectCategory,
            modifier = Modifier.fillMaxWidth().padding(start = layout.margin, end = layout.margin, bottom = ChipsBottom),
          )
        }
      }
    } else {
      null
    },
  )
}

/** How far the list has scrolled from its top, px; past the first item, more than any header collapses. */
private fun LazyListState.scrolledFromTop(): Int = if (firstVisibleItemIndex == 0) firstVisibleItemScrollOffset else Int.MAX_VALUE

/** [TabScene]s, crossfaded on a tab change with the theme's effects motion. */
@Composable
private fun TabContent(tab: DemoTab, feed: DiscoverState, camera: PannableCamera, padding: DemoPadding) {
  Crossfade(tab, animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(), label = "tab") {
    TabScene(it, feed, camera, padding)
  }
}

/**
 * [DemoNavBar] over its [bottomScrim] ([ChromeFade] of fade above the bar's margin), sliding out below the screen edge
 * while [visible] is false; [visible] is read here only.
 */
@Composable
private fun BottomBar(selected: DemoTab, onSelect: (DemoTab) -> Unit, visible: () -> Boolean, modifier: Modifier = Modifier) {
  val motion = MaterialTheme.motionScheme
  AnimatedVisibility(
    visible(),
    modifier,
    enter = slideInVertically(motion.defaultSpatialSpec()) { it } + fadeIn(motion.defaultEffectsSpec()),
    exit = slideOutVertically(motion.fastSpatialSpec()) { it } + fadeOut(motion.fastEffectsSpec()),
  ) {
    Box(Modifier.fillMaxWidth().bottomScrim(DiscoverPalette.current.colors.background)) {
      DemoNavBar(selected, onSelect, Modifier.padding(top = ChromeFade))
    }
  }
}
