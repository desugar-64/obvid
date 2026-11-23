// Copyright 2026, Serhii Yaremych (desugar-64) and the Obvid project contributors
// SPDX-License-Identifier: Apache-2.0

package dev.serhiiyaremych.obvid.sample.discover

import android.content.res.Configuration
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.serhiiyaremych.obvid.sample.LocalDemoLayout

/** Space under the chip row, inside the gap to the first card ([FeedList] adds the rest). */
internal val ChipsBottom = 4.dp

/** Chip labels, in category order. */
val CategoryLabels: List<String> = FeedCategories.map { it.label }

/**
 * Header, category chips, the feed, and the floating bottom bar over a [BottomScrim], inset by [LocalDemoLayout]'s
 * margin (the header 4 dp more), with its gap above and below the chips. Reads no state itself: the chips, feed and
 * bar read theirs through lambdas. Shadows besides the cards: header 4 drop + 3 inner, chips 8 + 8, bar 2 + 1, bar
 * items 8 + 4.
 */
@Composable
fun HomeScreen(state: DiscoverState, modifier: Modifier = Modifier) {
  val layout = LocalDemoLayout.current
  Box(modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize()) {
      DiscoverHeader(Modifier.statusBarsPadding().padding(start = layout.margin + 4.dp, end = layout.margin + 4.dp, top = 12.dp))
      CategoryChipRow(
        CategoryLabels,
        selected = { state.category },
        onSelect = state::selectCategory,
        modifier = Modifier.fillMaxWidth().padding(start = layout.margin, end = layout.margin, top = layout.gap, bottom = ChipsBottom),
      )
      FeedList(
        category = { state.category },
        listState = state.listState,
        bookmarks = state.bookmarks,
        onOpen = state::open,
        onShown = state::categoryShown,
        modifier = Modifier.weight(1f),
      )
    }
    BottomScrim(
      fadeHeight = ScrimFade + NavBarHeight / 2,
      opaqueHeight = NavBarHeight / 2 + NavBarMargin,
      modifier = Modifier.align(Alignment.BottomCenter),
    )
    BottomNavBar(
      NavTabs,
      selected = { state.tab },
      onSelect = { state.tab = it },
      modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(horizontal = layout.margin, vertical = NavBarMargin),
    )
  }
}

@Preview(widthDp = 411, heightDp = 860, uiMode = Configuration.UI_MODE_NIGHT_NO)
@Preview(widthDp = 411, heightDp = 860, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun HomeScreenPreview() = DiscoverPreview { HomeScreen(remember { DiscoverState() }) }
