// Copyright 2026, Serhii Yaremych (desugar-64) and the Obvid project contributors
// SPDX-License-Identifier: Apache-2.0

package dev.serhiiyaremych.obvid.sample.discover

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import dev.serhiiyaremych.obvid.LocalShadowBuilds
import dev.serhiiyaremych.obvid.sample.LocalShadowImpl
import dev.serhiiyaremych.obvid.sample.ShadowImpl
import dev.serhiiyaremych.obvid.sample.readParallelBuildsSetting
import kotlinx.coroutines.flow.first

/** Navigation transition, ms (as `t6_nav`: slide by a fifth of the width plus fade). */
private const val NavMillis = 350

/**
 * The Discover showcase alone ([DiscoverScenarios]): a dark feed in a soft-3D style whose depth is all drop and inner
 * shadows ([DepthTokens]), drawn by [impl], with its own bottom bar. Shows a plain splash until [DiscoverPhotos] are
 * decoded; [reveal] puts the screens behind a tap ([DiscoverReveal]).
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun DiscoverScreen(impl: ShadowImpl, reveal: Boolean) {
  val palette = DiscoverPalette.current
  // Around the reveal's warm-up row too: it loads the workers' code before the reveal, as it does the library's.
  val builds = readParallelBuildsSetting(LocalContext.current)
  Box(Modifier.fillMaxSize().background(palette.brushes.background).semantics { testTagsAsResourceId = true }) {
    CompositionLocalProvider(LocalShadowBuilds provides builds) {
      when {
        !DiscoverPhotos.ready -> Splash()
        reveal -> DiscoverReveal { DiscoverApp(impl) }
        else -> DiscoverApp(impl)
      }
    }
  }
}

/**
 * Where a host's chrome lies over the Discover screens, when the host shows the header, chips and bottom bar itself:
 * [feed] pads the feed list clear of them, and [detail] pads the detail screen in place of the status bar inset.
 */
@Stable
class DiscoverChrome(val feed: PaddingValues, val detail: PaddingValues)

/**
 * The Discover screens inside a host that brings its own chrome (the demo app): the feed list alone, padded by
 * [chrome], and the detail screen, drawn with [LocalShadowImpl], both filling the window. [state] comes from the host,
 * so the feed keeps its place across the host's rebuilds.
 */
@Composable
fun DiscoverFeed(state: DiscoverState, chrome: DiscoverChrome, modifier: Modifier = Modifier) {
  val palette = DiscoverPalette.current
  Box(modifier.fillMaxSize().background(palette.brushes.background)) {
    if (DiscoverPhotos.ready) DiscoverNavHost(state, chrome) else Splash()
  }
}

@Composable
private fun Splash() {
  val palette = DiscoverPalette.current
  Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
    BasicText("Loading photos…", style = palette.text.subtitle)
  }
}

/** The screens drawn with [impl], provided as [LocalShadowImpl]. */
@Composable
private fun DiscoverApp(impl: ShadowImpl) {
  val state = rememberSaveable(saver = DiscoverState.Saver) { DiscoverState() }
  CompositionLocalProvider(LocalShadowImpl provides impl) { DiscoverNavHost(state, chrome = null) }
}

/**
 * Home and detail, switched like `t6_nav`: an [AnimatedContent] slide plus fade of [NavMillis] ms, each trip traced
 * from the tap to one frame after the transition has settled. Back during a trip is queued, not dropped. Without a
 * host's [chrome], home is the whole [HomeScreen]; with it, the feed list alone.
 */
@Composable
private fun DiscoverNavHost(state: DiscoverState, chrome: DiscoverChrome?) {
  val transition = updateTransition(state.detail, "discover")
  LaunchedEffect(transition.targetState) {
    snapshotFlow { transition.isRunning || transition.currentState != transition.targetState }.first { !it }
    withFrameNanos {}
    state.transitionSettled()
  }
  BackHandler(enabled = transition.targetState != null || transition.currentState != null) { state.open(null) }
  transition.AnimatedContent(Modifier.fillMaxSize(), transitionSpec = { navTransition(forward = targetState != null) }, contentKey = { it?.key }) { card ->
    if (card == null) {
      if (chrome == null) HomeScreen(state) else HostedFeed(state, chrome.feed)
    } else {
      val bookmark = state.bookmarks[card]
      DetailScreen(card, { bookmark.value }, { bookmark.value = it }, onBack = { state.open(null) }, chromePadding = chrome?.detail)
    }
  }
}

/** [state]'s feed filling the window, padded by [padding]. */
@Composable
private fun HostedFeed(state: DiscoverState, padding: PaddingValues) {
  FeedList(
    category = { state.category },
    listState = state.listState,
    bookmarks = state.bookmarks,
    onOpen = state::open,
    onShown = state::categoryShown,
    contentPadding = padding,
    modifier = Modifier.fillMaxSize(),
  )
}

/** Slide by a fifth of the width plus fade, mirrored going back. */
private fun navTransition(forward: Boolean): ContentTransform {
  val enter = slideInHorizontally(tween(NavMillis, easing = FastOutSlowInEasing)) { if (forward) it / 5 else -it / 5 } +
    fadeIn(tween(NavMillis, easing = FastOutSlowInEasing))
  val exit = slideOutHorizontally(tween(NavMillis, easing = FastOutSlowInEasing)) { if (forward) -it / 5 else it / 5 } +
    fadeOut(tween(NavMillis / 2))
  return enter togetherWith exit
}
