// Copyright 2026, Serhii Yaremych (desugar-64) and the Obvid project contributors
// SPDX-License-Identifier: Apache-2.0

package dev.serhiiyaremych.obvid.sample.discover

import androidx.compose.runtime.Immutable
import kotlin.random.Random

/** Cards per category. */
const val FeedCount = 40

/** What a feed card looks like: photo left with text (Standard), photo on top (Hero), or a short row (Compact). */
enum class CardKind { Standard, Hero, Compact }

/** A topic label with its meta-row icon; its play button's tint comes from [DiscoverPalette.tint]. */
enum class Topic(val label: String, val icon: DiscoverIcon) {
  Travel("Travel", DiscoverIcon.MountainOutline),
  Science("Science", DiscoverIcon.Spark),
  Design("Design", DiscoverIcon.Leaf),
  Nature("Nature", DiscoverIcon.Leaf),
  Tech("Tech", DiscoverIcon.Layers),
}

/** One feed card; built by [feedCard] from its category and index only. */
@Immutable
data class FeedCard(
  val category: Int,
  val index: Int,
  val kind: CardKind,
  val photo: Int,
  val topic: Topic,
  val title: String,
  val body: String,
  val minutes: Int,
  val bookmarked: Boolean,
) {
  /** Lazy-list key: unique over all categories, so a category switch replaces every card. */
  val key: Int = category * 1000 + index
  val meta: String = "${topic.label}  ·  $minutes min"
}

/** A feed category: its chip label and cards. */
@Immutable
class FeedCategory(val label: String, val cards: List<FeedCard>)

/** Share of Standard and Hero cards per category (the rest are Compact), in chip order. */
private val Categories = listOf("For You" to (0.55f to 0.25f), "Tech" to (0.35f to 0.15f), "Travel" to (0.30f to 0.55f), "Design" to (0.60f to 0.30f))

/** Category 1 tells the tech-angled story of each photo. */
private const val TechCategory = 1

/**
 * Card [index] of [category]. Card 0 of every category is Standard (the benchmark taps its play button, bookmark and
 * body at fixed positions); the rest follow the category's kind mix. Photos run through a per-category shuffle of all
 * photos, so neighbours differ and each category has its own order.
 */
private fun feedCard(category: Int, index: Int, photoOrder: List<Int>): FeedCard {
  val random = Random(category * 7919 + index)
  val (standard, hero) = Categories[category].second
  val draw = random.nextFloat()
  val kind = when {
    index == 0 || draw < standard -> CardKind.Standard
    draw < standard + hero -> CardKind.Hero
    else -> CardKind.Compact
  }
  val photo = photoOrder[index % photoOrder.size]
  val story = Stories[photo]
  val tech = category == TechCategory
  return FeedCard(
    category = category,
    index = index,
    kind = kind,
    photo = photo,
    topic = if (tech) Topic.Tech else story.topic,
    title = if (tech) story.techTitle else story.title,
    body = if (tech) story.techBody else story.body,
    minutes = 3 + random.nextInt(10),
    bookmarked = index != 0 && random.nextFloat() < 0.2f,
  )
}

/** Every category with its [FeedCount] cards, built once. */
val FeedCategories: List<FeedCategory> = Categories.mapIndexed { category, (label, _) ->
  val photoOrder = PhotoResources.indices.shuffled(Random(category + 11))
  FeedCategory(label, List(FeedCount) { feedCard(category, it, photoOrder) })
}
