// Copyright 2026, Serhii Yaremych (desugar-64) and the Obvid project contributors
// SPDX-License-Identifier: Apache-2.0

package dev.serhiiyaremych.obvid.sample.discover

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.setValue

/** One bookmark state per card of every category, each read only by its own toggle. */
@Stable
class Bookmarks(initial: List<Boolean> = FeedCategories.flatMap { category -> category.cards.map { it.bookmarked } }) {
  private val states = initial.map { mutableStateOf(it) }

  operator fun get(card: FeedCard): MutableState<Boolean> = states[card.category * FeedCount + card.index]

  fun snapshot(): BooleanArray = BooleanArray(states.size) { states[it].value }
}

/**
 * State of the Discover screens, saved across configuration changes ([Saver]) and kept across a live switch of the
 * shadow implementation. Each value is read by the smallest composable that needs it: [category] by the chip row and
 * the feed, [tab] by the bottom bar, [detail] by the navigation host.
 */
@Stable
class DiscoverState(
  category: Int = 0,
  tab: Int = 0,
  detail: FeedCard? = null,
  val listState: LazyListState = LazyListState(),
  val bookmarks: Bookmarks = Bookmarks(),
) {
  var category by mutableIntStateOf(category)
    private set
  var tab by mutableIntStateOf(tab)
  var detail by mutableStateOf(detail)
    private set

  private val tracing = DiscoverTracing()

  /** A navigation asked for while a trip runs ([hasQueued]): applied when it settles ([transitionSettled]). */
  private var queued: FeedCard? = null
  private var hasQueued = false

  fun selectCategory(index: Int) {
    if (index == category) return
    tracing.beginChip()
    category = index
  }

  /** Two frames after the feed shows a category. */
  fun categoryShown() = tracing.endChip()

  /** Opens [card], or goes home with null. Asked during a trip, it runs once that trip settles. */
  fun open(card: FeedCard?) {
    if (tracing.tripOpen) {
      queued = card
      hasQueued = true
      return
    }
    if (card == detail) return
    tracing.beginTrip(forward = card != null)
    detail = card
  }

  /** One frame after a navigation transition has settled. */
  fun transitionSettled() {
    tracing.endTrip()
    if (hasQueued) {
      hasQueued = false
      open(queued)
    }
  }

  companion object {
    val Saver: Saver<DiscoverState, Any> = listSaver(
      save = {
        listOf(
          it.category, it.tab, it.detail?.category ?: -1, it.detail?.index ?: -1,
          it.listState.firstVisibleItemIndex, it.listState.firstVisibleItemScrollOffset, it.bookmarks.snapshot(),
        )
      },
      restore = {
        val detailCategory = it[2] as Int
        DiscoverState(
          category = it[0] as Int,
          tab = it[1] as Int,
          detail = if (detailCategory < 0) null else FeedCategories[detailCategory].cards[it[3] as Int],
          listState = LazyListState(it[4] as Int, it[5] as Int),
          bookmarks = Bookmarks((it[6] as BooleanArray).toList()),
        )
      },
    )
  }
}
