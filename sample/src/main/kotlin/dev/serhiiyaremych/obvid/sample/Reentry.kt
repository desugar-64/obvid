// Copyright 2026, Serhii Yaremych (desugar-64) and the Obvid project contributors
// SPDX-License-Identifier: Apache-2.0

package dev.serhiiyaremych.obvid.sample

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Resource id of the re-entry list (Macrobenchmark finds it with `By.res`). */
const val ReentryListTag = "reentry"

/** Number of cards in the re-entry list. */
const val ReentryCardCount = 80

private val ReentryShape = RoundedCornerShape(16.dp)
private val ReentryShadow = Shadow(radius = 24.dp, alpha = 0.4f)

/** Height of card [index] with [unique] geometry: 120..183 dp, 7 dp apart from its neighbours, repeating every 64 cards. */
private fun uniqueHeight(index: Int): Dp = (120 + index * 7 % 64).dp

/** Height of every card without [unique] geometry: the middle of the unique range. */
private val SameHeight = 152.dp

/**
 * A full-screen [LazyColumn] of [ReentryCardCount] full-width white cards with one static shadow each, the same
 * shape and [Shadow] on every card. With [unique], every card has its own height ([uniqueHeight]): 7 is coprime
 * with 64, so heights repeat only 64 cards apart (several screens), and no two cards in or near the viewport
 * share a shadow geometry. Without it, every card is [SameHeight] and all cards share one geometry.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun ReentryList(unique: Boolean) {
  LazyColumn(
    Modifier.fillMaxSize().semantics { testTagsAsResourceId = true }.testTag(ReentryListTag),
    contentPadding = PaddingValues(24.dp),
    verticalArrangement = Arrangement.spacedBy(24.dp),
  ) {
    items(ReentryCardCount, key = { it }) { index ->
      val height = if (unique) uniqueHeight(index) else SameHeight
      Box(Modifier.fillMaxWidth().height(height).demoShadow(ReentryShape, ReentryShadow).background(Color.White, ReentryShape))
    }
  }
}
