// Copyright 2026, Serhii Yaremych (desugar-64) and the Obvid project contributors
// SPDX-License-Identifier: Apache-2.0

package dev.serhiiyaremych.obvid.sample.discover

import android.content.res.Configuration
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.serhiiyaremych.obvid.sample.LocalShadowImpl
import dev.serhiiyaremych.obvid.sample.ShadowImpl

/**
 * One feed card of its [CardKind]; a tap on its body calls [onOpen]. [bookmarked] is read by the bookmark toggle only,
 * so a toggle recomposes the toggle, not the card. Shadows: Standard and Hero 6 drop + 4 inner (card 2 + 2, photo 1,
 * bookmark 1 + 1, play 2 + 1); Compact 4 drop + 2 inner (no rim, no play button).
 */
@Composable
fun FeedCardView(card: FeedCard, bookmarked: () -> Boolean, onBookmarkChange: (Boolean) -> Unit, onOpen: (FeedCard) -> Unit) {
  when (card.kind) {
    CardKind.Standard -> StandardCard(card, bookmarked, onBookmarkChange, onOpen)
    CardKind.Hero -> HeroCard(card, bookmarked, onBookmarkChange, onOpen)
    CardKind.Compact -> CompactCard(card, bookmarked, onBookmarkChange, onOpen)
  }
}

/** Inner padding of each card layout; the photos' shapes in [DiscoverShapes] are derived from it. */
val StandardCardPadding = 14.dp
val HeroCardPadding = 10.dp
val CompactCardPadding = 12.dp

/** The card frame: fixed [height], [cardSurface] drawn by [impl] in [palette], a tap that opens the card, inner [padding]. */
private fun Modifier.cardFrame(
  impl: ShadowImpl,
  palette: DiscoverPalette,
  card: FeedCard,
  height: Dp,
  padding: Dp,
  onOpen: (FeedCard) -> Unit,
  rim: Boolean = true,
): Modifier =
  fillMaxWidth()
    .height(height)
    .firstCardTag(card, DiscoverCardTag)
    .cardSurface(impl, palette, rim = rim)
    .clickable(interactionSource = null, indication = null, onClickLabel = "Open story") { onOpen(card) }
    .padding(padding)

@Composable
private fun StandardCard(card: FeedCard, bookmarked: () -> Boolean, onBookmarkChange: (Boolean) -> Unit, onOpen: (FeedCard) -> Unit) {
  val palette = DiscoverPalette.current
  Row(Modifier.cardFrame(LocalShadowImpl.current, DiscoverPalette.current, card, height = 176.dp, padding = StandardCardPadding, onOpen)) {
    Photo(card.photo, PhotoSize.Thumb, DiscoverShapes.ThumbPhoto, Modifier.photoSize(PhotoSize.Thumb))
    Spacer(Modifier.width(14.dp))
    Column(Modifier.weight(1f).fillMaxHeight()) {
      TitleRow(card, bookmarked, onBookmarkChange)
      Spacer(Modifier.height(6.dp))
      BasicText(card.body, style = palette.text.body, maxLines = 2, overflow = TextOverflow.Ellipsis)
      Spacer(Modifier.weight(1f))
      ActionRow(card)
    }
  }
}

@Composable
private fun HeroCard(card: FeedCard, bookmarked: () -> Boolean, onBookmarkChange: (Boolean) -> Unit, onOpen: (FeedCard) -> Unit) {
  val palette = DiscoverPalette.current
  Column(Modifier.cardFrame(LocalShadowImpl.current, DiscoverPalette.current, card, height = 336.dp, padding = HeroCardPadding, onOpen)) {
    Photo(card.photo, PhotoSize.Wide, DiscoverShapes.WidePhoto, Modifier.fillMaxWidth().height(PhotoSize.Wide.heightDp.dp))
    Spacer(Modifier.height(12.dp))
    Column(Modifier.weight(1f).padding(horizontal = 6.dp)) {
      TitleRow(card, bookmarked, onBookmarkChange)
      Spacer(Modifier.height(4.dp))
      BasicText(card.body, style = palette.text.body, maxLines = 1, overflow = TextOverflow.Ellipsis)
      Spacer(Modifier.weight(1f))
      ActionRow(card)
    }
  }
}

@Composable
private fun CompactCard(card: FeedCard, bookmarked: () -> Boolean, onBookmarkChange: (Boolean) -> Unit, onOpen: (FeedCard) -> Unit) {
  val palette = DiscoverPalette.current
  Row(Modifier.cardFrame(LocalShadowImpl.current, DiscoverPalette.current, card, height = 100.dp, padding = CompactCardPadding, onOpen, rim = false), verticalAlignment = Alignment.CenterVertically) {
    Photo(card.photo, PhotoSize.Small, DiscoverShapes.SmallPhoto, Modifier.photoSize(PhotoSize.Small))
    Spacer(Modifier.width(12.dp))
    Column(Modifier.weight(1f)) {
      BasicText(card.title, style = palette.text.smallTitle, maxLines = 2, overflow = TextOverflow.Ellipsis)
      Spacer(Modifier.height(4.dp))
      MetaRow(card)
    }
    Spacer(Modifier.width(8.dp))
    BookmarkToggle(bookmarked, onBookmarkChange)
  }
}

/** Title (two lines) and the bookmark toggle. */
@Composable
private fun TitleRow(card: FeedCard, bookmarked: () -> Boolean, onBookmarkChange: (Boolean) -> Unit) {
  val palette = DiscoverPalette.current
  Row {
    BasicText(card.title, Modifier.weight(1f), style = palette.text.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
    Spacer(Modifier.width(8.dp))
    BookmarkToggle(bookmarked, onBookmarkChange, Modifier.firstCardTag(card, DiscoverBookmarkTag))
  }
}

/** Meta row and the play button. */
@Composable
private fun ActionRow(card: FeedCard) {
  Row(verticalAlignment = Alignment.CenterVertically) {
    MetaRow(card, Modifier.weight(1f))
    PlayButton(DiscoverPalette.current.tint(card.topic), Modifier.firstCardTag(card, DiscoverPlayTag))
  }
}

/** Topic icon, topic and reading time. */
@Composable
fun MetaRow(card: FeedCard, modifier: Modifier = Modifier) {
  val palette = DiscoverPalette.current
  Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
    LineIcon(card.topic.icon, Modifier.size(16.dp), color = { palette.colors.muted })
    BasicText(card.meta, style = palette.text.meta, maxLines = 1)
  }
}

@Composable
private fun CardPreview(kind: CardKind) = DiscoverPreview {
  val card = FeedCategories.flatMap { it.cards }.first { it.kind == kind }
  var bookmarked by remember { mutableStateOf(card.bookmarked) }
  FeedCardView(card, { bookmarked }, { bookmarked = it }, onOpen = {})
}

@Preview(widthDp = 420, uiMode = Configuration.UI_MODE_NIGHT_NO)
@Preview(widthDp = 420, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun StandardCardPreview() = CardPreview(CardKind.Standard)

@Preview(widthDp = 420, uiMode = Configuration.UI_MODE_NIGHT_NO)
@Preview(widthDp = 420, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun HeroCardPreview() = CardPreview(CardKind.Hero)

@Preview(widthDp = 420, uiMode = Configuration.UI_MODE_NIGHT_NO)
@Preview(widthDp = 420, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun CompactCardPreview() = CardPreview(CardKind.Compact)
