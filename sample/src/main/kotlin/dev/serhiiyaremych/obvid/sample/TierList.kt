// Copyright 2026, Serhii Yaremych (desugar-64) and the Obvid project contributors
// SPDX-License-Identifier: Apache-2.0

package dev.serhiiyaremych.obvid.sample

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import kotlin.random.Random

/** Resource id of the `t5_list` feed (Macrobenchmark finds it with `By.res`). */
const val TierListTag = "tierList"

/** Number of items in the `t5_list` feed. */
const val TierListCount = 100

/** What one feed item is. */
private enum class FeedKind { Card, ButtonRow, Search, Avatar }

/** Names of the MaterialShapes avatars draw from (one shared [PolygonShape] instance each). */
private val AvatarShapes = listOf("Cookie9Sided", "Clover4Leaf", "Sunny", "Flower", "Gem", "Puffy").map(::PolygonShape)

/**
 * One feed item, from `Random(index)` only: a content card (45 %: 96–200 dp tall, corners 12, 14 or 16 dp, shadow
 * radius 8–16 dp), a row with a pill button (25 %: small shadow), a search field with an inner shadow (10 %), or a
 * row with a 56 dp MaterialShapes avatar (20 %: shadow radius 8 dp).
 */
private class FeedItem(val index: Int) {
  private val random = Random(index)
  val kind = random.nextFloat().let {
    when {
      it < 0.45f -> FeedKind.Card
      it < 0.70f -> FeedKind.ButtonRow
      it < 0.80f -> FeedKind.Search
      else -> FeedKind.Avatar
    }
  }
  val height = (96 + random.nextInt(105)).dp
  val corner = intArrayOf(12, 14, 16)[random.nextInt(3)].dp
  val radius = (8 + random.nextInt(9)).dp
  val dy = (2 + random.nextInt(5)).dp
  val alpha = 0.2f + 0.15f * random.nextFloat()
  val avatar = AvatarShapes[random.nextInt(AvatarShapes.size)]
  val tone = Tones[random.nextInt(Tones.size)]
}

private val SearchShape = RoundedCornerShape(26.dp)
private val ListButtonShadow = Shadow(radius = 4.dp, color = Color(0xFF5B3FA8), offset = DpOffset(0.dp, 2.dp), alpha = 0.35f)

/**
 * `t5_list`: a full-screen LazyColumn of [TierListCount] heterogeneous [FeedItem]s, each with its own shadows (see
 * there). Nothing animates; frames come from scrolling.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun TierList() {
  val items = remember { List(TierListCount, ::FeedItem) }
  LazyColumn(
    Modifier.fillMaxSize().semantics { testTagsAsResourceId = true }.testTag(TierListTag),
    contentPadding = PaddingValues(20.dp),
    verticalArrangement = Arrangement.spacedBy(20.dp),
  ) {
    items(items.size, key = { it }, contentType = { items[it].kind }) { Feed(items[it]) }
  }
}

@Composable
private fun Feed(item: FeedItem) {
  when (item.kind) {
    FeedKind.Card -> ContentCard(
      item.height, item.radius, item.dy, "Post ${item.index}", "A card of the feed with its own height and shadow.",
      shape = RoundedCornerShape(item.corner), alpha = item.alpha,
    )
    FeedKind.ButtonRow -> Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
      Column(Modifier.weight(1f)) {
        Text("Suggested ${item.index}", style = MaterialTheme.typography.titleSmall)
        Text("Follow to see updates", style = MaterialTheme.typography.bodySmall, color = Color(0xFF5E5A66))
      }
      PillButton("Follow", Modifier.width(96.dp), ListButtonShadow)
    }
    FeedKind.Search -> SearchField("Search the feed", Modifier.fillMaxWidth(), SearchShape)
    FeedKind.Avatar -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
      val shadow = remember(item.tone) { Shadow(radius = 8.dp, color = item.tone.shadow, offset = DpOffset(0.dp, 3.dp), alpha = 0.4f) }
      Box(Modifier.size(56.dp).measuredDropShadow(item.avatar, shadow).background(item.tone.tile, item.avatar))
      Column {
        Text(item.avatar.name, style = MaterialTheme.typography.titleSmall)
        Text("Shared ${item.index % 7 + 1} new items", style = MaterialTheme.typography.bodySmall, color = Color(0xFF5E5A66))
      }
    }
  }
}
