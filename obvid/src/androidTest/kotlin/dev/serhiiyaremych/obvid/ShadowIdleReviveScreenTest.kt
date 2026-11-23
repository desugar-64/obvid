// Copyright 2026, Serhii Yaremych (desugar-64) and the Obvid project contributors
// SPDX-License-Identifier: Apache-2.0

package dev.serhiiyaremych.obvid

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.DropShadowScope
import androidx.compose.ui.draw.InnerShadowScope
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import dev.serhiiyaremych.obvid.ScreenCases.showOverLockScreen
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Obvid's nodes on the main thread's store: a shadow whose node goes away and comes back (a new node, or a `LazyColumn`
 * item scrolled out and back in) takes its idle entry again without a build, and draws the same pixels. Counted with
 * the store's counters; the store is emptied of idle entries first ([clearIdleShadows]), and its idle timeout is
 * raised for the test ([ShadowStore.setIdleTimeoutForTest]) so that a slow device cannot expire the entry between two
 * steps. Kinds ([Kind]): a drop shadow through the `Shadow` overload and the block overload, and an inner shadow
 * through the block overload.
 */
// captureToImage needs API 26.
@SdkSuppress(minSdkVersion = 26)
@RunWith(AndroidJUnit4::class)
class ShadowIdleReviveScreenTest {

  @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

  private enum class Kind {
    DropShadow,
    DropBlock,
    InnerBlock,
  }

  private var shown by mutableStateOf(false)
  private var kind by mutableStateOf(Kind.DropShadow)
  private var lazy by mutableStateOf(false)
  private var token by mutableStateOf(0)
  private var lazyState: LazyListState? = null

  @Before
  fun setUp() {
    rule.runOnUiThread { rule.activity.showOverLockScreen() }
    NativeShadows.forceComposeFallback = false
    rule.setContent {
      if (shown) key(token) {
        if (lazy) {
          val state = rememberLazyListState()
          lazyState = state
          LazyColumn(Modifier.size(CELL), state = state, userScrollEnabled = false) {
            items(ITEMS, key = { it }, contentType = { if (it == 0) 0 else 1 }) { i ->
              if (i == 0) ShadowCell() else Box(Modifier.size(CELL))
            }
          }
        } else {
          ShadowCell()
        }
      }
    }
    mainStore { it.setIdleTimeoutForTest(LONG_TIMEOUT_MS) }
    clearIdleShadows()
  }

  @After
  fun tearDown() {
    shown = false
    rule.waitForIdle()
    mainStore { it.setIdleTimeoutForTest(null) }
    clearIdleShadows()
  }

  @Composable
  private fun ShadowCell() {
    val mod =
      when (kind) {
        Kind.DropShadow -> Modifier.dropShadow(SHAPE, Shadow(12.dp, Color.Black, 4.dp, DpOffset(2.dp, 3.dp)))
        Kind.DropBlock -> Modifier.dropShadow(SHAPE, dropBlock)
        Kind.InnerBlock -> Modifier.innerShadow(SHAPE, innerBlock)
      }
    ScreenCases.Cell(Modifier.testTag(TAG_CELL), CELL, CONTENT, mod)
  }

  private fun capture(): IntArray = ScreenCases.pixelsOf(rule.onNodeWithTag(TAG_CELL).captureToImage().asAndroidBitmap())

  /** (builds, live entries, idle entries) of the main thread's store. */
  private fun counters(): Triple<Long, Int, Int> =
    mainStore { Triple(it.buildCount, it.entriesForTest().size, it.idleEntriesForTest().size) }

  @Test fun newNodeTakesTheIdleEntry() = forEachKind { comeBack(lazyList = false) }

  @Test fun lazyItemScrolledBackTakesTheIdleEntry() = forEachKind { comeBack(lazyList = true) }

  private fun forEachKind(run: () -> Unit) {
    for (k in Kind.entries) {
      kind = k
      run()
      shown = false
      rule.waitForIdle()
      clearIdleShadows()
    }
  }

  private fun comeBack(lazyList: Boolean) {
    val kind = kind.name
    lazy = lazyList
    token++
    shown = true
    rule.waitForIdle()
    val (b0, live0, idle0) = counters()
    assertEquals("$kind: live entries shown", 1, live0)
    assertEquals("$kind: idle entries shown", 0, idle0)
    val first = capture()
    if (lazyList) {
      rule.runOnIdle { lazyState!!.requestScrollToItem(FAR) }
      rule.waitForIdle()
      rule.onNodeWithTag(TAG_CELL).assertDoesNotExist()
    } else {
      shown = false
      rule.waitForIdle()
    }
    val (b1, live1, idle1) = counters()
    assertEquals("$kind: live entries gone", 0, live1)
    assertEquals("$kind: idle entries gone", 1, idle1)
    if (lazyList) {
      rule.runOnIdle { lazyState!!.requestScrollToItem(0) }
    } else {
      token++
      shown = true
    }
    rule.waitForIdle()
    val (b2, live2, idle2) = counters()
    assertEquals("$kind: builds while gone", b0, b1)
    assertEquals("$kind: builds on the way back", b0, b2)
    assertEquals("$kind: live entries back", 1, live2)
    assertEquals("$kind: idle entries back", 0, idle2)
    assertTrue("$kind: same pixels back", ScreenCases.diff(first, capture()).identical)
  }

  private companion object {
    const val TAG_CELL = "cell"
    const val ITEMS = 100
    const val FAR = 60
    val CELL = 200.dp
    val CONTENT = 100.dp
    val SHAPE = RoundedCornerShape(16.dp)
    const val LONG_TIMEOUT_MS = 600_000L

    // Non-capturing: one instance, so the element is unchanged across the node's comings and goings.
    val dropBlock: DropShadowScope.() -> Unit = { radius = 12.dp.toPx(); spread = 4.dp.toPx(); color = Color.Black }
    val innerBlock: InnerShadowScope.() -> Unit = { radius = 12.dp.toPx(); spread = 2.dp.toPx(); color = Color.Black }
  }
}
