// Copyright 2026, Serhii Yaremych (desugar-64) and the Obvid project contributors
// SPDX-License-Identifier: Apache-2.0

package dev.serhiiyaremych.obvid.sample.discover

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State

/** Selection animations (chips, nav items, bookmarks), ms. */
const val SelectMillis = 300

/**
 * 0 → 1 as [selected] turns true, back as it turns false, over [SelectMillis]. Read the returned state in the draw
 * phase (shadow blocks, draw lambdas, color producers) so only drawing follows the animation.
 */
@Composable
fun selectionFraction(selected: Boolean, label: String): State<Float> =
  animateFloatAsState(if (selected) 1f else 0f, tween(SelectMillis), label = label)
