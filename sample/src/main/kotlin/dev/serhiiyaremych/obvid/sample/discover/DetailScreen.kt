// Copyright 2026, Serhii Yaremych (desugar-64) and the Obvid project contributors
// SPDX-License-Identifier: Apache-2.0

package dev.serhiiyaremych.obvid.sample.discover

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.serhiiyaremych.obvid.sample.LocalDemoLayout

/** Resource id of the detail screen's back button. */
const val DiscoverBackTag = "discoverBack"

/**
 * The detail of [card] over a [BottomScrim], inset by [LocalDemoLayout]'s margin: back button and category, its photo on a large
 * stacked shadow under the [DepthTokens.focus] rim, its text and actions, then the [DesignSystemPanel]. Shadows above
 * the panel: back 1 + 1, photo 2 drop + 1 inner, bookmark 1 + 1, play 2 + 1. The content starts below the status bar,
 * or with [chromePadding] (a host's chrome over the screen) in its place.
 */
@Composable
fun DetailScreen(
  card: FeedCard,
  bookmarked: () -> Boolean,
  onBookmarkChange: (Boolean) -> Unit,
  onBack: () -> Unit,
  chromePadding: PaddingValues? = null,
) {
  Box(Modifier.fillMaxSize()) {
    DetailContent(card, bookmarked, onBookmarkChange, onBack, chromePadding)
    BottomScrim(fadeHeight = ScrimFade, opaqueHeight = 0.dp, modifier = Modifier.align(Alignment.BottomCenter))
  }
}

@Composable
private fun DetailContent(
  card: FeedCard,
  bookmarked: () -> Boolean,
  onBookmarkChange: (Boolean) -> Unit,
  onBack: () -> Unit,
  chromePadding: PaddingValues?,
) {
  val palette = DiscoverPalette.current
  Column(
    Modifier
      .fillMaxSize()
      .verticalScroll(rememberScrollState())
      .then(if (chromePadding == null) Modifier.statusBarsPadding() else Modifier.padding(chromePadding))
      .navigationBarsPadding()
      .padding(horizontal = LocalDemoLayout.current.margin, vertical = 12.dp),
  ) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      RoundButton(DiscoverIcon.Back, "Back", onClick = onBack, modifier = Modifier.testTag(DiscoverBackTag))
      Spacer(Modifier.width(14.dp))
      BasicText(CategoryLabels[card.category], Modifier.weight(1f), style = palette.text.subtitle)
    }
    Spacer(Modifier.height(22.dp))
    Photo(card.photo, PhotoSize.Wide, DiscoverShapes.Photo, Modifier.padding(horizontal = 10.dp).fillMaxWidth().height(PhotoSize.Wide.heightDp.dp), hero = true)
    Spacer(Modifier.height(24.dp))
    Row(Modifier.padding(horizontal = 4.dp)) {
      Column(Modifier.weight(1f)) {
        BasicText(card.title, style = palette.text.detailTitle)
        Spacer(Modifier.height(6.dp))
        BasicText(card.body, style = palette.text.subtitle)
        Spacer(Modifier.height(8.dp))
        BasicText(card.meta, style = palette.text.meta)
      }
      Spacer(Modifier.width(12.dp))
      Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        BookmarkToggle(bookmarked, onBookmarkChange)
        PlayButton(DiscoverPalette.current.tint(card.topic))
      }
    }
    SectionDivider()
    DesignSystemPanel()
    Spacer(Modifier.height(24.dp + ScrimFade))
  }
}

@Preview(widthDp = 411, heightDp = 1400, uiMode = Configuration.UI_MODE_NIGHT_NO)
@Preview(widthDp = 411, heightDp = 1400, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun DetailScreenPreview() = DiscoverPreview {
  val card = FeedCategories[0].cards[0]
  var bookmarked by remember { mutableStateOf(false) }
  DetailScreen(card, { bookmarked }, { bookmarked = it }, onBack = {})
}
