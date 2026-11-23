// Copyright 2026, Serhii Yaremych (desugar-64) and the Obvid project contributors
// SPDX-License-Identifier: Apache-2.0

package dev.serhiiyaremych.obvid.sample.discover

/** Copy for one photo: a general story and a tech-angled one. */
internal class PhotoStory(val topic: Topic, val title: String, val body: String, val techTitle: String, val techBody: String)

/** One story per [PhotoResources] entry, same order. */
internal val Stories = listOf(
  PhotoStory(Topic.Travel, "Hidden Trails in the Alps", "A guide to the most beautiful, less-known routes.",
    "Mapping Glaciers with Lidar", "How laser scans track a shrinking icefield."),
  PhotoStory(Topic.Science, "Why Auroras Are Getting Brighter", "What's changing in Earth's atmosphere?",
    "Forecasting Auroras from Orbit", "Satellites that warn photographers an hour ahead."),
  PhotoStory(Topic.Design, "Biophilic Design in the City", "How nature-inspired spaces make urban life healthier.",
    "Surfaces That Shed Water", "What engineers learn from a single leaf."),
  PhotoStory(Topic.Nature, "The Quiet Physics of Dew", "Why drops form on cool, still mornings.",
    "Sensors That Drink the Air", "Harvesting water from the morning fog."),
  PhotoStory(Topic.Travel, "Sunset in the Sand Sea", "Three nights camping between the dunes.",
    "How Dunes Learn to Walk", "Simulating sand that moves a meter a day."),
  PhotoStory(Topic.Science, "The Red Dunes of Namib", "The oldest desert on Earth, at first light.",
    "Solar Farms in the Desert", "Keeping panels clean where the sand never stops."),
  PhotoStory(Topic.Travel, "First Light Above the Clouds", "An early start for the best view in the range.",
    "Weather Models for Climbers", "Reading the forecast before a summit push."),
  PhotoStory(Topic.Travel, "Cliffs at the Edge of Europe", "A coastal walk you can do in a weekend.",
    "Listening to Erosion", "Seismometers that hear the cliffs crumble."),
  PhotoStory(Topic.Travel, "Coastal Towns to Visit This Year", "Charming places, great food, and sea views.",
    "Ports Going Electric", "Shore power for ferries and fishing boats."),
  PhotoStory(Topic.Nature, "Into the Misty Creek", "A slow morning walk through an old forest.",
    "Counting Trees from Space", "Machine learning on satellite images."),
  PhotoStory(Topic.Science, "Forests That Make Rain", "How trees seed the clouds above them.",
    "Drones That Plant Forests", "Seed pods dropped by the thousand."),
)
