// Copyright 2026, Serhii Yaremych (desugar-64) and the Obvid project contributors
// SPDX-License-Identifier: Apache-2.0

package dev.serhiiyaremych.obvid

import android.os.Build
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.neverEqualPolicy
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.InnerShadowScope
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import dev.serhiiyaremych.obvid.InnerScreenCases.RESULTS_DIR
import dev.serhiiyaremych.obvid.ScreenCases.Impl
import dev.serhiiyaremych.obvid.ScreenCases.showOverLockScreen
import dev.serhiiyaremych.obvid.fixtures.CookiePillMorph
import dev.serhiiyaremych.obvid.fixtures.MorphShape
import dev.serhiiyaremych.obvid.fixtures.catalogShape
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import androidx.compose.ui.draw.innerShadow as composeInnerShadow

/**
 * The inner shadow's rebuild rules and the store's memory behaviour, the twin of [DropShadowRebuildTest]: the store's
 * debug counters while one node animates one parameter for [FRAMES] drawn frames.
 *
 * - color, alpha, brush, blend mode (both overloads): 0 rebuilds after the first frame;
 * - offset, radius, spread, layout size, shape, radius with a brush (both overloads): one rebuild per frame; one store
 *   entry throughout; bitmap allocations grow by at most one (the second buffer: every inner draw samples its bitmap
 *   through a shader, so every rebuild writes the other buffer) while the geometry shrinks or keeps its size;
 * - two nodes with the same inner geometry (offset included) share one entry; another offset is another entry; a
 *   drop shadow with the same shape, size, radius and spread is another entry;
 * - after the content is removed the store is empty.
 * The animated shape is `mixed` (a path, spread 0: the mask route), `rrect16` for the spread run (the round-rect
 * routes), the morph for the shape run. Output: `inner1-rebuild-summary.txt`.
 *
 * [layoutDirectionAndDensityChanges]: layout direction and density changes on a live node, one rebuild each, with the
 * pixels against Compose's. Output: `inner1-rebuild-env-summary.txt`.
 */
@RunWith(AndroidJUnit4::class)
class InnerShadowRebuildTest {

  @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

  private var step by mutableIntStateOf(0)
  private val drawn = AtomicInteger(-1)
  private var content by mutableStateOf<(@Composable () -> Unit)?>(null)

  @Before
  fun setUp() {
    rule.runOnUiThread { rule.activity.showOverLockScreen() }
    NativeShadows.forceComposeFallback = false
    rule.setContent { content?.invoke() }
    clearIdleShadows()
  }

  private fun awaitDrawn(s: Int) {
    rule.waitUntil(5_000) { drawn.get() == s }
  }

  private fun frames(apply: (Int) -> Unit) {
    for (f in 1..FRAMES) {
      apply(f)
      step = f
      rule.waitForIdle()
      awaitDrawn(f)
    }
  }

  /** The marker: records which step was drawn (same layer as the shadow). */
  private fun Modifier.marked(): Modifier = drawBehind { drawn.set(step) }

  private class Params(
    var radiusDp: Float = 24f,
    var spreadDp: Float = 0f,
    var sizeDp: Float = 120f,
    var shapeProgress: Float = 0.5f,
    var color: Color = Color.Black,
    var alpha: Float = 1f,
    var offsetDp: Float = 2f,
    var brushT: Float = -1f,
    var blendMode: BlendMode = BlendMode.SrcOver,
  )

  private enum class ShapeKind {
    Mixed,
    RoundRect,
    Morph,
  }

  private fun shapeOf(p: Params, kind: ShapeKind): Shape =
    when (kind) {
      ShapeKind.Mixed -> MIXED
      ShapeKind.RoundRect -> RRECT
      ShapeKind.Morph -> MorphShape(CookiePillMorph, p.shapeProgress)
    }

  private fun brushOf(t: Float): Brush? =
    if (t < 0f) null else Brush.linearGradient(listOf(Color(1f, t, 0f), Color(0f, 0f, 1f - t)))

  /** Runs one animated parameter; returns (builds, bitmap allocations added, the summary line). */
  private fun animate(name: String, block: Boolean, kind: ShapeKind, change: Params.(Int) -> Unit): Triple<Long, Int, String> {
    // Frame 0 is the largest geometry: the shrinking runs must not allocate after the second buffer.
    // From a cold store: the build counts must not take an entry an earlier run left idle.
    clearIdleShadows()
    val initial = Params().also { it.change(0) }
    val params = mutableStateOf(initial, neverEqualPolicy())
    // The block overload reads the animated parameters in the draw phase only (no recomposition), except the
    // layout size and the shape, which are composition inputs.
    val compositionReads = !block || name == "size" || name == "shape"
    content = {
      val p = if (compositionReads) params.value else initial
      val shape = shapeOf(p, kind)
      val mod =
        if (block) {
          Modifier.innerShadow(shape) {
            // Read in the draw phase (state read inside the block).
            val q = params.value
            radius = q.radiusDp.dp.toPx()
            spread = q.spreadDp.dp.toPx()
            offset = Offset(q.offsetDp.dp.toPx(), (q.offsetDp * 1.5f).dp.toPx())
            val b = brushOf(q.brushT)
            if (b != null) brush = b else color = q.color
            alpha = q.alpha
            blendMode = q.blendMode
          }
        } else {
          val b = brushOf(p.brushT)
          val off = DpOffset(p.offsetDp.dp, (p.offsetDp * 1.5f).dp)
          Modifier.innerShadow(
            shape,
            if (b != null) Shadow(p.radiusDp.dp, b, p.spreadDp.dp, off, p.alpha, p.blendMode)
            else Shadow(p.radiusDp.dp, p.color, p.spreadDp.dp, off, p.alpha, p.blendMode),
          )
        }
      Box(Modifier.padding(80.dp).size(p.sizeDp.dp).marked().background(Color.Gray).then(mod))
    }
    step = 0
    rule.waitForIdle()
    awaitDrawn(0)
    val (start, allocStart) = mainStore { it.buildCount to it.entriesForTest().single().bitmaps.allocationCount }
    frames { f -> params.value = Params().also { p -> p.change(f) } }
    val (builds, alloc, entries) =
      mainStore { s ->
        val entry = s.entriesForTest().single()
        Triple(s.buildCount - start, entry.bitmaps.allocationCount, s.entriesForTest().size)
      }
    val line =
      "$name (${if (block) "block" else "shadow"}): builds $builds / $FRAMES frames, " +
        "store entries $entries, bitmap allocations $allocStart -> $alloc"
    Log.i(TAG, line)
    content = null
    rule.waitForIdle()
    return Triple(builds, alloc - allocStart, line)
  }

  @Test
  fun rebuildRules() {
    check(NativeShadows.isAvailable)
    val summary = ArrayList<String>()
    val failures = ArrayList<String>()
    for (block in listOf(false, true)) {
      val noRebuild =
        listOf<Pair<String, Params.(Int) -> Unit>>(
          "color" to { f -> color = Color(f / FRAMES.toFloat(), 0.2f, 0.5f, 0.3f + 0.7f * f / FRAMES) },
          "alpha" to { f -> alpha = 1f - 0.9f * f / FRAMES },
          "brush" to { f -> brushT = f / FRAMES.toFloat() },
          "blendMode" to { f -> blendMode = BLEND_MODES[f % BLEND_MODES.size] },
        )
      for ((name, change) in noRebuild) {
        val (builds, allocDelta, line) = animate(name, block, ShapeKind.Mixed, change)
        summary += line
        if (builds != 0L) failures += line
        if (allocDelta != 0) failures += "allocations: $line"
        assertEquals("store not empty after $name", 0, mainStore { it.entriesForTest().size })
      }
      val rebuild =
        listOf<Triple<String, ShapeKind, Params.(Int) -> Unit>>(
          Triple("offset", ShapeKind.Mixed) { f -> offsetDp = 2f + 0.37f * f },
          Triple("radius", ShapeKind.Mixed) { f -> radiusDp = 40f - 30f * f / FRAMES },
          Triple("spread", ShapeKind.RoundRect) { f -> spreadDp = 12f - 20f * f / FRAMES },
          Triple("size", ShapeKind.Mixed) { f -> sizeDp = 160f - 60f * f / FRAMES },
          Triple("shape", ShapeKind.Morph) { f -> shapeProgress = f / FRAMES.toFloat() },
          Triple("radius+brush", ShapeKind.Mixed) { f ->
            radiusDp = 40f - 30f * f / FRAMES
            brushT = 0.5f
          },
        )
      for ((name, kind, change) in rebuild) {
        val (builds, allocDelta, line) = animate(name, block, kind, change)
        summary += line
        if (builds != FRAMES.toLong()) failures += line
        // The second buffer, once; shrinking or same-size geometry never grows the bitmaps.
        if (allocDelta > 1) failures += "allocations: $line"
        assertEquals("store not empty after $name", 0, mainStore { it.entriesForTest().size })
      }
    }
    summary += sharing()
    summary += "API ${Build.VERSION.SDK_INT}, ${Build.MODEL}"
    summary.forEach { Log.i(TAG, it) }
    writeResults(rule.activity, "inner1-rebuild-summary.txt", summary + failures.map { "FAIL $it" }, RESULTS_DIR)
    assertTrue("failures: $failures", failures.isEmpty())
  }

  /**
   * A live node under a changing layout direction (Rtl, Ltr, Rtl) and then density (the activity's × 0.75, × 1.25,
   * then back), for both overloads on `mixed` (asymmetric corners, spread 0: the mask route): one rebuild per change,
   * one store entry; the block overload re-reads its block on every density change. Each step is drawn next to
   * Compose's modifier under the same direction and density, both captured and compared (identity asserted on the
   * target Skia). Output: `inner1-rebuild-env-summary.txt`.
   */
  // captureToImage needs API 26.
  @SdkSuppress(minSdkVersion = 26)
  @Test
  fun layoutDirectionAndDensityChanges() {
    check(NativeShadows.isAvailable)
    val base = Density(rule.activity)
    var direction by mutableStateOf(LayoutDirection.Ltr)
    var density by mutableStateOf(base)
    val blockReads = AtomicInteger(0)
    val summary = ArrayList<String>()
    val failures = ArrayList<String>()
    val tallies = LinkedHashMap<String, DiffTally>()
    val steps =
      listOf<Pair<String, () -> Unit>>(
        "rtl 1" to { direction = LayoutDirection.Rtl },
        "ltr" to { direction = LayoutDirection.Ltr },
        "rtl 2" to { direction = LayoutDirection.Rtl },
        "density x0.75" to { density = Density(base.density * 0.75f, base.fontScale) },
        "density x1.25" to { density = Density(base.density * 1.25f, base.fontScale) },
        "density x1" to { density = base },
      )
    for (block in listOf(false, true)) {
      val overload = if (block) "block" else "shadow"
      direction = LayoutDirection.Ltr
      density = base
      content = {
        // Remembered: a density change reaches the block only through the node (no new lambda).
        val composeBlock = remember { envBlock(null) }
        val obvidBlock = remember { envBlock(blockReads) }
        CompositionLocalProvider(LocalDensity provides density, LocalLayoutDirection provides direction) {
          val cell = envCell()
          Column {
            for ((tag, impl) in listOf(TAG_COMPOSE to Impl.Compose, TAG_OBVID to Impl.Obvid)) {
              val mod =
                when {
                  block && impl == Impl.Compose -> Modifier.composeInnerShadow(MIXED, composeBlock)
                  block -> Modifier.innerShadow(MIXED, obvidBlock)
                  impl == Impl.Compose -> Modifier.composeInnerShadow(MIXED, ENV_SHADOW)
                  else -> Modifier.innerShadow(MIXED, ENV_SHADOW)
                }
              InnerScreenCases.Cell(Modifier.testTag(tag), cell, ENV_CONTENT, mod)
            }
            Box(Modifier.size(1.dp).marked())
          }
        }
      }
      step = 0
      rule.waitForIdle()
      awaitDrawn(0)
      val initial = envCompare(overload, "initial", tallies)
      summary += envLine(overload, "initial (ltr, density ${base.density})", initial, "", failures)
      for ((i, s) in steps.withIndex()) {
        val (name, change) = s
        val (buildsBefore, readsBefore) = mainStore { it.buildCount } to blockReads.get()
        change()
        step = i + 1
        rule.waitForIdle()
        awaitDrawn(i + 1)
        // Captured first: the counters then include every draw up to the capture.
        val d = envCompare(overload, name, tallies)
        val (builds, entries) = mainStore { (it.buildCount - buildsBefore) to it.entriesForTest().size }
        val reads = if (block) blockReads.get() - readsBefore else null
        val counters = "builds $builds, store entries $entries, " + if (reads != null) "block reads $reads, " else ""
        val line = envLine(overload, name, d, counters, failures)
        summary += line
        if (builds != 1L || entries != 1) failures += line
        if (reads != null && name.startsWith("density") && reads < 1) failures += "block not re-read: $line"
      }
      content = null
      rule.waitForIdle()
      assertEquals("store not empty after $overload", 0, mainStore { it.entriesForTest().size })
      // The next overload starts cold: its steps would otherwise revive this one's idle entries.
      clearIdleShadows()
    }
    summary += "API ${Build.VERSION.SDK_INT}, ${Build.MODEL}, density ${base.density}"
    if (!TargetSkia.isDevice) {
      summary += "${TargetSkia.offTargetLabel} (premultiplied ARGB):"
      for ((k, t) in tallies) summary += "  $k: $t"
    }
    summary.forEach { Log.i(TAG, it) }
    writeResults(rule.activity, "inner1-rebuild-env-summary.txt", summary + failures.map { "FAIL $it" }, RESULTS_DIR)
    assertTrue("failures: $failures", failures.isEmpty())
  }

  /** The `Shadow` overload's [ENV_SHADOW] as a block; [reads] counts its runs. */
  private fun envBlock(reads: AtomicInteger?): InnerShadowScope.() -> Unit = {
    reads?.incrementAndGet()
    radius = 12.dp.toPx()
    spread = 0f
    offset = Offset(3.dp.toPx(), 5.dp.toPx())
    color = ENV_COLOR
  }

  /** The cell side: 200 dp rounded to a multiple of 16 px at the current density. */
  @Composable
  private fun envCell(): Dp = with(LocalDensity.current) { ((200.dp.toPx() / 16f).roundToInt() * 16).toDp() }

  /**
   * Captures both cells and compares them. Below the target Skia the premultiplied difference goes to [tallies] under
   * the step's key.
   */
  private fun envCompare(overload: String, name: String, tallies: MutableMap<String, DiffTally>): ScreenCases.Diff {
    val a = ScreenCases.pixelsOf(rule.onNodeWithTag(TAG_COMPOSE).captureToImage().asAndroidBitmap())
    val b = ScreenCases.pixelsOf(rule.onNodeWithTag(TAG_OBVID).captureToImage().asAndroidBitmap())
    if (!TargetSkia.isDevice) tallies.getOrPut("$overload $name") { DiffTally() }.add(ScreenCases.diffPremul(a, b))
    return ScreenCases.diff(a, b)
  }

  /** The summary line of the step [name]; a difference on the target Skia is a failure. */
  private fun envLine(
    overload: String,
    name: String,
    d: ScreenCases.Diff,
    counters: String,
    failures: MutableList<String>,
  ): String {
    val line =
      "$overload $name: ${counters}Obvid vs Compose identical ${d.identical} (diff pixels ${d.pixels}, max ${d.max})"
    Log.i(TAG, line)
    if (TargetSkia.isDevice && !d.identical) failures += line
    return line
  }

  /**
   * Two inner nodes with the same geometry (offset included), then a third with another offset and a drop shadow
   * with the first two's shape, size, radius and spread; then everything removed.
   */
  private fun sharing(): List<String> {
    val out = ArrayList<String>()
    var more by mutableStateOf(false)
    clearIdleShadows()
    val start = mainStore { it.buildCount }
    val offset = DpOffset(3.dp, 3.dp)
    content = {
      Column {
        Box(Modifier.padding(40.dp).size(100.dp).marked().innerShadow(MIXED, Shadow(12.dp, Color.Black, 4.dp, offset)))
        Box(
          Modifier.padding(40.dp)
            .size(100.dp)
            .innerShadow(MIXED, Shadow(12.dp, Color.Red, 4.dp, offset, 0.5f, BlendMode.Multiply))
        )
        if (more) {
          Box(Modifier.padding(40.dp).size(100.dp).innerShadow(MIXED, Shadow(12.dp, Color.Black, 4.dp, DpOffset(3.dp, 4.dp))))
          Box(Modifier.padding(40.dp).size(100.dp).dropShadow(MIXED, Shadow(12.dp, Color.Black, 4.dp)))
        }
      }
    }
    step = 0
    rule.waitForIdle()
    awaitDrawn(0)
    val (twoEntries, twoRefCounts, twoBuilds) =
      mainStore { s -> Triple(s.entriesForTest().size, s.entriesForTest().map { it.refCount }, s.buildCount - start) }
    val two = "two inner nodes, same geometry (different color / alpha / blend mode): " +
      "store entries $twoEntries, refCounts $twoRefCounts, builds $twoBuilds"
    out += two
    assertEquals(two, 1, twoEntries)
    assertEquals(two, listOf(2), twoRefCounts)
    assertEquals(two, 1L, twoBuilds)
    more = true
    rule.waitForIdle()
    val (moreEntries, moreRefCounts, moreBuilds) =
      mainStore { s ->
        Triple(s.entriesForTest().size, s.entriesForTest().map { it.refCount }.sorted(), s.buildCount - start)
      }
    val three = "plus an inner node with another offset and a drop node with the same shape, size, radius and " +
      "spread: store entries $moreEntries, refCounts $moreRefCounts, builds $moreBuilds"
    out += three
    assertEquals(three, 3, moreEntries)
    assertEquals(three, listOf(1, 1, 2), moreRefCounts)
    assertEquals(three, 3L, moreBuilds)
    content = null
    rule.waitForIdle()
    val goneEntries = mainStore { it.entriesForTest().size }
    val gone = "after removing the content: store entries $goneEntries"
    out += gone
    assertEquals(gone, 0, goneEntries)
    return out
  }

  private companion object {
    const val TAG = "FS-InnerRebuild"
    const val FRAMES = 30
    const val TAG_COMPOSE = "compose"
    const val TAG_OBVID = "obvid"
    val ENV_COLOR = Color(0xC0302060)
    val ENV_SHADOW = Shadow(12.dp, ENV_COLOR, 0.dp, DpOffset(3.dp, 5.dp))
    val ENV_CONTENT = 120.dp
    val MIXED = catalogShape("mixed")
    val RRECT = catalogShape("rrect16")
    val BLEND_MODES =
      listOf(BlendMode.SrcOver, BlendMode.Multiply, BlendMode.Screen, BlendMode.SrcAtop, BlendMode.DstOut, BlendMode.Overlay)
  }
}
