// Copyright 2026, Serhii Yaremych (desugar-64) and the Obvid project contributors
// SPDX-License-Identifier: Apache-2.0

package dev.serhiiyaremych.obvid.sample.discover

import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag

/** Resource ids on card 0 of every category: the benchmark's fixed tap targets (card 0 is always Standard). */
const val DiscoverCardTag = "discoverCard"
const val DiscoverPlayTag = "discoverPlay"
const val DiscoverBookmarkTag = "discoverBookmark"

/** [tag] on card 0 of each category only. */
internal fun Modifier.firstCardTag(card: FeedCard, tag: String): Modifier = if (card.index == 0) testTag(tag) else this
