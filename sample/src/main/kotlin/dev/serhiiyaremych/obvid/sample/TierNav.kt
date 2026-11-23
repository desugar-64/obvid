// Copyright 2026, Serhii Yaremych (desugar-64) and the Obvid project contributors
// SPDX-License-Identifier: Apache-2.0

package dev.serhiiyaremych.obvid.sample

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
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
import kotlinx.coroutines.flow.first

/** Resource id of `t6_nav`'s full-screen tap target: each tap switches screens. */
const val TierNavTag = "nav"

/** Async trace sections from a navigation tap until its transition has settled and one more frame has run. */
const val NavForwardSection = "demo:nav toDetail"
const val NavBackSection = "demo:nav toHome"

/** Transition length, ms (M3 shared-axis X: slide by a fifth of the width plus fade). */
private const val NavMillis = 350

private val HomeColor = Color(0xFFFAF7F2)
private val DetailColor = Color(0xFFF2F2F2)
private val Ink = Color(0xFF1D1B20)
private val Muted = Color(0xFF5E5A66)
private val Accent = Color(0xFF5B3FA8)
private val AccentSoft = Color(0xFFE9DDFF)

/**
 * `t6_nav`: a light home screen (five plain rows and one small pill with a shadow) and a shadow-heavy detail screen
 * (23 shadowed elements, all shown at once: a hero card, 12 cards in a 4 × 3 grid, 4 chips, 3 pill buttons, a search
 * field with an inner shadow, a MaterialShapes cookie and a holed gear). A tap anywhere on the [TierNavTag] target
 * switches screens with an [AnimatedContent] transition of [NavMillis] ms (slide by a fifth of the width plus fade,
 * reversed going back). Each switch is an async trace section ([NavForwardSection] / [NavBackSection]) from the tap to
 * one frame after the transition has settled. Leaving the detail screen releases its shadows; coming back reuses
 * whatever the implementation still keeps.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun TierNav() {
  var detail by remember { mutableStateOf(false) }
  var pending by remember { mutableStateOf<Pair<String, Int>?>(null) }
  val transition = updateTransition(detail, "nav")
  LaunchedEffect(detail) {
    val event = pending ?: return@LaunchedEffect
    snapshotFlow { transition.isRunning || transition.currentState != transition.targetState }.first { !it }
    withFrameNanos {}
    endEvent(event.first, event.second)
    pending = null
  }
  Box(Modifier.fillMaxSize()) {
    transition.AnimatedContent(
      Modifier.fillMaxSize(),
      transitionSpec = {
        val forward = targetState
        val enter = slideInHorizontally(tween(NavMillis, easing = FastOutSlowInEasing)) { if (forward) it / 5 else -it / 5 } +
          fadeIn(tween(NavMillis, easing = FastOutSlowInEasing))
        val exit = slideOutHorizontally(tween(NavMillis, easing = FastOutSlowInEasing)) { if (forward) -it / 5 else it / 5 } +
          fadeOut(tween(NavMillis / 2))
        enter togetherWith exit
      },
    ) { showDetail ->
      if (showDetail) DetailScreen() else HomeScreen()
    }
    Box(
      Modifier
        .fillMaxSize()
        .semantics { testTagsAsResourceId = true }
        .testTag(TierNavTag)
        .clickable(interactionSource = null, indication = null) {
          if (pending == null) {
            val name = if (detail) NavBackSection else NavForwardSection
            pending = name to beginEvent(name)
            detail = !detail
          }
        },
    )
  }
}

private val PillShape = RoundedCornerShape(percent = 50)
private val SmallShadow = Shadow(radius = 6.dp, color = Accent, offset = DpOffset(0.dp, 2.dp), alpha = 0.35f)

@Composable
private fun HomeScreen() {
  Column(Modifier.fillMaxSize().background(HomeColor).padding(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
    Text("Home", style = MaterialTheme.typography.headlineMedium, color = Ink)
    listOf("Recent", "Starred", "Shared with me", "Downloads", "Trash").forEach {
      Column {
        Text(it, style = MaterialTheme.typography.titleMedium, color = Ink)
        Text("Tap to open the overview", style = MaterialTheme.typography.bodySmall, color = Muted)
      }
    }
    Box(
      Modifier.size(140.dp, 48.dp).measuredDropShadow(PillShape, SmallShadow).background(Accent, PillShape),
      contentAlignment = Alignment.Center,
    ) {
      Text("Overview", color = Color.White, style = MaterialTheme.typography.labelLarge)
    }
  }
}

private val HeroShape = RoundedCornerShape(28.dp)
private val HeroShadow = Shadow(radius = 32.dp, color = Ink, offset = DpOffset(0.dp, 12.dp), alpha = 0.3f)
private val TileShape = RoundedCornerShape(12.dp)
private val TileShadow = Shadow(radius = 10.dp, color = Ink, offset = DpOffset(0.dp, 4.dp), alpha = 0.25f)
private val ChipShadow = Shadow(radius = 4.dp, color = Ink, offset = DpOffset(0.dp, 1.dp), alpha = 0.25f)
private val BadgeShadow = Shadow(radius = 16.dp, color = Accent, offset = DpOffset(0.dp, 6.dp), alpha = 0.35f)

@Composable
private fun DetailScreen() {
  val cookie = remember { PolygonShape("Cookie9Sided") }
  val gear = remember { GearShape() }
  Column(Modifier.fillMaxSize().background(DetailColor).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
    Box(
      Modifier.fillMaxWidth().height(160.dp).measuredDropShadow(HeroShape, HeroShadow).background(Color.White, HeroShape).padding(20.dp),
    ) {
      Text("Overview", style = MaterialTheme.typography.headlineSmall, color = Ink)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      listOf("All", "Docs", "Photos", "Audio").forEach {
        Box(
          Modifier.height(32.dp).measuredDropShadow(PillShape, ChipShadow).background(Color.White, PillShape).padding(horizontal = 14.dp),
          contentAlignment = Alignment.Center,
        ) {
          Text(it, style = MaterialTheme.typography.labelMedium, color = Ink)
        }
      }
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
      repeat(3) { row ->
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
          repeat(4) { column ->
            Box(
              Modifier.weight(1f).height(84.dp).measuredDropShadow(TileShape, TileShadow).background(Color.White, TileShape).padding(10.dp),
            ) {
              Text("${row * 4 + column + 1}", style = MaterialTheme.typography.titleMedium, color = Muted)
            }
          }
        }
      }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
      listOf("Share", "Move", "Pin").forEach { PillButton(it, Modifier.weight(1f)) }
    }
    SearchField("Filter items", Modifier.fillMaxWidth())
    Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
      Box(Modifier.size(80.dp).measuredDropShadow(cookie, BadgeShadow).background(AccentSoft, cookie))
      Box(Modifier.size(96.dp).measuredDropShadow(gear, BadgeShadow).background(Color(0xFFB7C9E8), gear))
    }
  }
}
