// Copyright 2026, Serhii Yaremych (desugar-64) and the Obvid project contributors
// SPDX-License-Identifier: Apache-2.0

package dev.serhiiyaremych.obvid

import android.os.Build
import android.util.Log
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.DropShadowScope
import androidx.compose.ui.draw.InnerShadowScope
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import dev.serhiiyaremych.obvid.ScreenCases.Impl
import dev.serhiiyaremych.obvid.ScreenCases.showOverLockScreen
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import androidx.compose.ui.draw.dropShadow as composeDropShadow
import androidx.compose.ui.draw.innerShadow as composeInnerShadow

/**
 * The block overloads (`dropShadow(shape) { … }`, `innerShadow(shape) { … }`) when their node is detached and
 * reattached with an unchanged element: does a later change to a State the block reads still reach the shadow?
 *
 * The block is one non-capturing lambda that reads [radiusDp], so the element stays equal across the reattach. Each
 * scenario shows the shadow at radius [A], takes the node through a [Mechanism], sets the radius to [B], waits for
 * idle and captures the cell. The capture is compared exactly with the same implementation in the same host,
 * freshly composed at [B] (expected) and at [A] (stale). Every host puts the cell at the same window position, so a
 * same-implementation image is deterministic.
 *
 * Mechanisms:
 * - `control`: no detach; the State change only (proves the capture sees the change).
 * - `movable`: `movableContentOf` content moved between two overlapping parents (the LayoutNode is removed and
 *   inserted in one apply, so its modifier nodes detach and reattach; the composition is kept).
 * - `lazy-scroll`: a `LazyColumn` item scrolled out of the viewport (its slot deactivated: modifier nodes detached)
 *   and back (the same slot reactivated).
 * - `lazy-scroll-removal`: as `lazy-scroll`, plus an unrelated LayoutNode removed while the item is out. A removed
 *   LayoutNode makes the owner drop the read observations of every detached modifier node at the end of the apply.
 * - `view-reattach`: the ComposeView removed from and re-added to its parent (composition kept, as with
 *   `DisposeOnLifecycleDestroyed`, e.g. in a RecyclerView). The owner stops and restarts its snapshot observer,
 *   which clears all read observations; the modifier nodes stay attached.
 *
 * The host is a ComposeView created by the test in a FrameLayout (the `view-reattach` mechanism needs one it can
 * remove). Output (subdir `reattach` of the files dir, and the additional test output dir): `block-reattach.csv`,
 * `block-reattach-summary.txt`, PNGs of the captures that are not the expected image. Asserted: Obvid's equals the
 * expected image in every mechanism, and both implementations' control does; Compose's other results are recorded.
 */
// captureToImage needs API 26.
@SdkSuppress(minSdkVersion = 26)
@RunWith(AndroidJUnit4::class)
class BlockReattachTest {

  @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

  private enum class Kind {
    Drop,
    Inner,
  }

  private enum class Host {
    Plain,
    Movable,
    Lazy,
  }

  private enum class Mechanism(val label: String, val host: Host) {
    Control("control", Host.Plain),
    Movable("movable", Host.Movable),
    LazyScroll("lazy-scroll", Host.Lazy),
    LazyScrollRemoval("lazy-scroll-removal", Host.Lazy),
    ViewReattach("view-reattach", Host.Plain),
  }

  private class Shown(val impl: Impl, val kind: Kind, val host: Host, val token: Int)

  private class Image(val w: Int, val h: Int, val px: IntArray)

  private var shown by mutableStateOf<Shown?>(null)
  private var moved by mutableStateOf(false)
  private var extraNode by mutableStateOf(true)
  private var token = 0
  private var lazyState: LazyListState? = null
  private lateinit var container: FrameLayout
  private lateinit var composeView: ComposeView

  @Before
  fun setUp() {
    NativeShadows.forceComposeFallback = false
    rule.runOnUiThread {
      val activity = rule.activity
      activity.showOverLockScreen()
      // A slow device must not expire Obvid's idle entry while the node is away: the no-rebuild check counts builds.
      ShadowStore.current().setIdleTimeoutForTest(LONG_TIMEOUT_MS)
      composeView =
        ComposeView(activity).apply {
          setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnLifecycleDestroyed(activity))
          setContent { TestContent() }
        }
      container = FrameLayout(activity)
      container.addView(composeView, LP_WRAP)
      activity.setContentView(container)
    }
    rule.waitForIdle()
  }

  @After
  fun tearDown() {
    NativeShadows.forceComposeFallback = false
    radiusDp = A
    mainStore { it.setIdleTimeoutForTest(null) }
    clearIdleShadows()
  }

  @Composable
  private fun TestContent() {
    Box {
      val s = shown
      if (s != null) {
        key(s.token) {
          when (s.host) {
            Host.Plain -> ShadowCell(s)
            Host.Movable -> {
              val content = remember { movableContentOf { ShadowCell(s) } }
              Box {
                Box(Modifier.size(CELL)) { if (!moved) content() }
                Box(Modifier.size(CELL)) { if (moved) content() }
              }
            }
            Host.Lazy -> {
              val state = rememberLazyListState()
              lazyState = state
              LazyColumn(Modifier.size(CELL), state = state, userScrollEnabled = false) {
                items(ITEMS, key = { it }, contentType = { if (it == 0) 0 else 1 }) { i ->
                  if (i == 0) ShadowCell(s) else Box(Modifier.size(CELL).background(Color.LightGray))
                }
              }
            }
          }
        }
      }
      // Draws nothing; removing it detaches a LayoutNode.
      if (extraNode) Box(Modifier.size(1.dp))
    }
  }

  @Composable
  private fun ShadowCell(s: Shown) {
    val tag = Modifier.testTag(TAG_CELL)
    when (s.kind) {
      Kind.Drop ->
        ScreenCases.Cell(
          tag,
          CELL,
          CONTENT,
          if (s.impl == Impl.Compose) Modifier.composeDropShadow(SHAPE, dropBlock) else Modifier.dropShadow(SHAPE, dropBlock),
        )
      Kind.Inner ->
        InnerScreenCases.Cell(
          tag,
          CELL,
          CONTENT,
          if (s.impl == Impl.Compose) Modifier.composeInnerShadow(SHAPE, innerBlock)
          else Modifier.innerShadow(SHAPE, innerBlock),
        )
    }
  }

  /** A fresh host (new key) at [radius]. */
  private fun show(impl: Impl, kind: Kind, host: Host, radius: Float) {
    shown = null
    rule.waitForIdle()
    radiusDp = radius
    moved = false
    extraNode = true
    lazyState = null
    shown = Shown(impl, kind, host, ++token)
    rule.waitForIdle()
  }

  private fun capture(): Image {
    val b = rule.onNodeWithTag(TAG_CELL).captureToImage().asAndroidBitmap()
    return Image(b.width, b.height, ScreenCases.pixelsOf(b))
  }

  /**
   * Takes the shown cell's node through [m] and back. Returns Obvid's store's live entry count while the item is out of
   * the list (a detached Obvid node releases its entry, which goes idle), or null where nothing is out between
   * frames.
   */
  private fun detachReattach(m: Mechanism): Int? {
    var entriesWhileOut: Int? = null
    when (m) {
      Mechanism.Control -> {}
      Mechanism.Movable -> {
        moved = true
        rule.waitForIdle()
      }
      Mechanism.LazyScroll,
      Mechanism.LazyScrollRemoval -> {
        rule.runOnIdle { lazyState!!.requestScrollToItem(FAR) }
        rule.waitForIdle()
        rule.onNodeWithTag(TAG_CELL).assertDoesNotExist()
        entriesWhileOut = mainStore { it.entriesForTest().size }
        if (m == Mechanism.LazyScrollRemoval) {
          extraNode = false
          rule.waitForIdle()
        }
        rule.runOnIdle { lazyState!!.requestScrollToItem(0) }
        rule.waitForIdle()
      }
      Mechanism.ViewReattach -> {
        rule.runOnUiThread {
          container.removeView(composeView)
          container.addView(composeView, LP_WRAP)
        }
        rule.waitForIdle()
      }
    }
    rule.onNodeWithTag(TAG_CELL).assertExists()
    return entriesWhileOut
  }

  @Test
  fun blockStateChangeAfterReattach() {
    check(NativeShadows.isAvailable) { "native library not available" }
    val context = rule.activity
    val dirs = outputDirs(context, SUBDIR)
    fun save(name: String, img: Image) {
      for (d in dirs) ScreenCases.savePng(d, name, img.w, img.h, img.px)
    }
    val rows = ArrayList<String>()
    rows += HEADER
    val summary = ArrayList<String>()
    val failing = ArrayList<String>()
    var cell: Image? = null
    for (impl in Impl.entries) for (kind in Kind.entries) for (host in Host.entries) {
      show(impl, kind, host, A)
      val refA = capture()
      show(impl, kind, host, B)
      val refB = capture()
      cell = refB
      val refsDiffer = ScreenCases.diff(refA.px, refB.px)
      val prefix = "${impl.name.lowercase()} ${kind.name.lowercase()}"
      if (refsDiffer.identical) failing += "$prefix ${host.name.lowercase()}: A and B images identical"
      for (m in Mechanism.entries.filter { it.host == host }) {
        show(impl, kind, host, A)
        val start = capture()
        val startIsA = ScreenCases.diff(refA.px, start.px).identical
        val buildsBefore = mainStore { it.buildCount }
        val entries = detachReattach(m)
        rule.waitForIdle()
        val reattachBuilds = mainStore { it.buildCount } - buildsBefore
        radiusDp = B
        rule.waitForIdle()
        val after = capture()
        val dB = ScreenCases.diff(refB.px, after.px)
        val dA = ScreenCases.diff(refA.px, after.px)
        val result =
          when {
            dB.identical -> "B"
            dA.identical -> "stale-A"
            else -> "other"
          }
        val label = "$prefix ${m.label}"
        Log.i(TAG, "$label: $result (vs B ${dB.pixels} px max ${dB.max}; vs A ${dA.pixels} px max ${dA.max}; start is A $startIsA)")
        if (!dB.identical) {
          val base = "${impl.name.lowercase()}_${kind.name.lowercase()}_${m.label}"
          save("${base}_after.png", after)
          save("${base}_expectedB.png", refB)
          save("${base}_staleA.png", refA)
        }
        if (!startIsA) failing += "$label: start image is not A"
        // The State did not change across the reattach: the Obvid node takes its idle entry (or keeps its live one)
        // unbuilt.
        if (impl == Impl.Obvid && reattachBuilds != 0L) failing += "$label: $reattachBuilds builds across the reattach"
        // A lazy item out of the list holds nothing (its entry is idle).
        if (impl == Impl.Obvid && entries != null && entries != 0) failing += "$label: $entries live entries while out"
        if (!dB.identical && (impl == Impl.Obvid || m == Mechanism.Control)) failing += "$label: $result"
        summary += "$label: $result"
        rows +=
          listOf(
              Build.MODEL.replace(',', ' '),
              Build.VERSION.SDK_INT,
              impl.name.lowercase(),
              kind.name.lowercase(),
              m.label,
              result,
              dB.pixels,
              dB.max,
              dA.pixels,
              dA.max,
              if (startIsA) 1 else 0,
              refsDiffer.pixels,
              if (impl == Impl.Obvid) entries?.toString() ?: "" else "",
            )
            .joinToString(",")
      }
    }
    shown = null
    rule.waitForIdle()
    summary +=
      "failing ${failing.size}" + if (failing.isEmpty()) "" else " (${failing.joinToString("; ")})"
    summary +=
      "radius $A dp -> $B dp, cell ${cell?.w}x${cell?.h} px, API ${Build.VERSION.SDK_INT}, ${Build.MODEL}, " +
        "density ${context.resources.displayMetrics.density}"
    summary.forEach { Log.i(TAG, it) }
    writeResults(context, "block-reattach.csv", rows, SUBDIR)
    writeResults(context, "block-reattach-summary.txt", summary, SUBDIR)
    assertTrue(failing.joinToString("; "), failing.isEmpty())
  }

  private companion object {
    const val TAG = "FS-Reattach"
    const val TAG_CELL = "cell"
    const val SUBDIR = "reattach"
    const val A = 8f
    const val B = 24f
    const val ITEMS = 100
    const val FAR = 60
    const val LONG_TIMEOUT_MS = 600_000L

    val CELL = 200.dp
    val CONTENT = 100.dp
    val SHAPE = RoundedCornerShape(16.dp)
    val LP_WRAP = ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    var radiusDp by mutableFloatStateOf(A)

    // Non-capturing: one instance for the whole run, so a block element never changes.
    val dropBlock: DropShadowScope.() -> Unit = { radius = radiusDp.dp.toPx() }
    val innerBlock: InnerShadowScope.() -> Unit = { radius = radiusDp.dp.toPx() }

    const val HEADER =
      "model,api,impl,kind,mechanism,result,diffBPixels,diffBMax,diffAPixels,diffAMax,startIsA,refsDiffPixels," +
        "obvidEntriesWhileOut"
  }
}
