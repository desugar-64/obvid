// Copyright 2026, Serhii Yaremych (desugar-64) and the Obvid project contributors
// SPDX-License-Identifier: Apache-2.0

package dev.serhiiyaremych.obvid.sample.discover

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.serhiiyaremych.obvid.sample.LocalDemoLayout

/** Resource id of the feed (the benchmark scrolls it). */
const val DiscoverFeedTag = "discoverFeed"

/**
 * The cards of category [category], padded by [contentPadding] (by default the home screen's: [LocalDemoLayout]'s
 * margin, the rest of its gap below the chips, and clear of the bottom bar and its scrim), keyed by category and index:
 * a switch replaces every visible card in place, and [onShown] runs two frames after. Items take only their card, their bookmark state (read by its toggle) and
 * [onOpen]; nothing an item composes reads the scroll position, so scrolling composes entering items only.
 */
@Composable
fun FeedList(
  category: () -> Int,
  listState: LazyListState,
  bookmarks: Bookmarks,
  onOpen: (FeedCard) -> Unit,
  onShown: () -> Unit,
  contentPadding: PaddingValues = homeFeedPadding(),
  modifier: Modifier = Modifier,
) {
  val current = category()
  LaunchedEffect(current) {
    withFrameNanos {}
    withFrameNanos {}
    onShown()
  }
  val cards = FeedCategories[current].cards
  LazyColumn(
    modifier.fillMaxWidth().testTag(DiscoverFeedTag),
    state = listState,
    contentPadding = contentPadding,
    verticalArrangement = Arrangement.spacedBy(18.dp),
  ) {
    items(cards.size, key = { cards[it].key }, contentType = { cards[it].kind }) {
      val card = cards[it]
      val bookmark = bookmarks[card]
      FeedCardView(card, { bookmark.value }, { checked -> bookmark.value = checked }, onOpen)
    }
  }
}

/** The home screen's feed padding: see [FeedList]. */
@Composable
private fun homeFeedPadding(): PaddingValues {
  val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + BottomChromeHeight + 8.dp
  val layout = LocalDemoLayout.current
  return PaddingValues(start = layout.margin, end = layout.margin, top = layout.gap - ChipsBottom, bottom = bottom)
}
