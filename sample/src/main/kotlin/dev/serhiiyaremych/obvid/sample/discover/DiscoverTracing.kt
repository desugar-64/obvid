// Copyright 2026, Serhii Yaremych (desugar-64) and the Obvid project contributors
// SPDX-License-Identifier: Apache-2.0

package dev.serhiiyaremych.obvid.sample.discover

import dev.serhiiyaremych.obvid.sample.NavBackSection
import dev.serhiiyaremych.obvid.sample.NavForwardSection
import dev.serhiiyaremych.obvid.sample.beginEvent
import dev.serhiiyaremych.obvid.sample.endEvent

/** The Discover feed, shown at once (`scenario` extra). */
const val DiscoverScenario = "t7_discover"

/** The Discover feed behind a tap ([DiscoverReveal]). */
const val DiscoverRevealScenario = "t7_discover_reveal"

val DiscoverScenarios = listOf(DiscoverScenario, DiscoverRevealScenario)

/** Async trace section from a chip tap to two frames after the new category's cards are composed. */
const val ChipTraceSection = "demo:chip"

/**
 * The async trace sections the benchmark reads as event windows: one category switch ([ChipTraceSection]) and one
 * navigation trip ([NavForwardSection] / [NavBackSection]) at a time. Main thread only.
 */
internal class DiscoverTracing {
  private var chipCookie = 0
  private var trip: Pair<String, Int>? = null

  val tripOpen: Boolean get() = trip != null

  fun beginChip() {
    endChip()
    chipCookie = beginEvent(ChipTraceSection)
  }

  fun endChip() {
    if (chipCookie != 0) endEvent(ChipTraceSection, chipCookie)
    chipCookie = 0
  }

  fun beginTrip(forward: Boolean) {
    val name = if (forward) NavForwardSection else NavBackSection
    trip = name to beginEvent(name)
  }

  fun endTrip() {
    trip?.let { (name, cookie) -> endEvent(name, cookie) }
    trip = null
  }
}
