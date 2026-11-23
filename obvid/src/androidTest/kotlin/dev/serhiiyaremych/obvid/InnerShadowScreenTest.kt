// Copyright 2026, Serhii Yaremych (desugar-64) and the Obvid project contributors
// SPDX-License-Identifier: Apache-2.0

package dev.serhiiyaremych.obvid

import android.os.Build
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import dev.serhiiyaremych.obvid.ScreenCases.Case
import dev.serhiiyaremych.obvid.ScreenCases.Impl
import dev.serhiiyaremych.obvid.InnerScreenCases.Cell
import dev.serhiiyaremych.obvid.InnerScreenCases.RESULTS_DIR
import dev.serhiiyaremych.obvid.InnerScreenCases.caseInnerShadow
import dev.serhiiyaremych.obvid.ScreenCases.showOverLockScreen
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Obvid's `innerShadow` against Compose's on the screen, the twin of [DropShadowScreenTest]: each case is drawn by
 * Compose's modifier, then by Obvid's in the same cell at the same position ([InnerScreenCases.Cell]: the shadow over a
 * translucent content box), captured with `captureToImage` and compared exactly.
 *
 * - `fresh`: a new node (new store entry, fresh bitmaps) per case.
 * - `grown`: the same node first draws a larger case (200 dp morph r64 s0, same paint and overload), then the case:
 *   the entry is re-keyed in place and its bitmaps reconfigured down to the used size.
 * Cases: [InnerScreenCases.cases] (groups `geometry` and `paint`: colour, translucent, brush, alpha, blend modes,
 * offsets, both overloads), then [InnerScreenCases.roundCases] (group `round`).
 *
 * Output (subdir [InnerScreenCases.RESULTS_DIR]): `inner1-screen.csv` and `inner1-screen-summary.txt`, PNGs of the
 * first mismatches in `mismatch/`; below the target Skia also the worst case of each mode, phase and paint. The
 * fallback test writes `inner1-fallback*`, the animation test `inner1-anim*`; [reuseAnimationPerFrame] writes
 * `inner2-anim*` to [InnerScreenCases.RESULTS_DIR2].
 *
 * Obvid against Compose is asserted identical on the target Skia ([TargetSkia.isDevice]); elsewhere the summaries add
 * the difference from the device's Compose (PSNR) and it does not fail. The fallback test (Compose against Compose)
 * asserts everywhere.
 */
// captureToImage needs API 26.
@SdkSuppress(minSdkVersion = 26)
@RunWith(AndroidJUnit4::class)
class InnerShadowScreenTest {

  @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

  private class Shown(val case: Case, val impl: Impl, val token: Int)

  private var shown by mutableStateOf<Shown?>(null)
  private var token = 0

  @Before
  fun setUp() {
    rule.runOnUiThread { rule.activity.showOverLockScreen() }
    NativeShadows.forceComposeFallback = false
    NativeShadows.forceSingleDraw = false
    clearIdleShadows()
  }

  @After
  fun tearDown() {
    NativeShadows.forceComposeFallback = false
    NativeShadows.forceSingleDraw = false
  }

  private fun setCellContent() {
    rule.setContent {
      val s = shown
      if (s != null) {
        key(s.token) {
          val c = s.case
          Cell(
            Modifier.testTag(TAG_CELL),
            ScreenCases.CELL,
            c.sizeDp.dp,
            Modifier.caseInnerShadow(s.impl, c.shape.shape, c.radiusDp.dp, c.spreadDp.dp, c.paint, c.block),
          )
        }
      }
    }
  }

  private fun show(case: Case, impl: Impl, newNode: Boolean) {
    if (newNode) token++
    shown = Shown(case, impl, token)
    rule.waitForIdle()
  }

  private fun capture(): IntArray = ScreenCases.pixelsOf(rule.onNodeWithTag(TAG_CELL).captureToImage().asAndroidBitmap())

  private fun clear() {
    shown = null
    rule.waitForIdle()
    val refCounts = mainStore { s -> s.entriesForTest().map { it.refCount } }
    check(refCounts.isEmpty()) { "store not empty: $refCounts" }
  }

  @Test
  fun screenEquality() {
    check(NativeShadows.isAvailable) { "native library not available" }
    setCellContent()
    val context = rule.activity
    val rows = ArrayList<String>()
    rows += HEADER
    val mismatchDir = File(outputDirs(context, RESULTS_DIR).last(), "mismatch")
    var saved = 0
    // Below the target: (mode, phase, paint) -> (max diff, file base) of the worst case saved so far.
    val worst = HashMap<String, Pair<Int, String>>()
    // phase key -> (identical, total)
    val counts = LinkedHashMap<String, IntArray>()
    val tallies = LinkedHashMap<String, DiffTally>()
    var w = 0
    var h = 0
    // The main cases (groups geometry / paint), then the round-rect cases (group round), indices continuing.
    val all = InnerScreenCases.cases.map { it to if (it.paint === InnerScreenCases.basePaint) "geometry" else "paint" } +
      InnerScreenCases.roundCases.map { it to "round" }
    for ((i, cg) in all.withIndex()) {
      val (c, group) = cg
      clear()
      show(c, Impl.Compose, newNode = true)
      val ref = capture()
      if (w == 0) {
        val b = rule.onNodeWithTag(TAG_CELL).captureToImage()
        w = b.width
        h = b.height
      }
      clear()
      show(c, Impl.Obvid, newNode = true)
      val fresh = capture()
      show(InnerScreenCases.bigCase(c), Impl.Obvid, newNode = true)
      show(c, Impl.Obvid, newNode = false)
      val grown = capture()
      for ((phase, px) in listOf("fresh" to fresh, "grown" to grown)) {
        val d = ScreenCases.diff(ref, px)
        val key = "$MODE/$phase/$group"
        counts.getOrPut(key) { IntArray(2) }.let {
          if (d.identical) it[0]++
          it[1]++
        }
        // Below the target: the premultiplied difference, for the summary and the worst-case PNGs.
        val dp = if (TargetSkia.isDevice) null else ScreenCases.diffPremul(ref, px)
        if (dp != null) tallies.getOrPut(key) { DiffTally() }.add(dp)
        rows += row(i, c, MODE, phase, d)
        if (!d.identical) {
          Log.w(TAG, "mismatch $phase ${c.label}: ${d.pixels} px, max ${d.max}")
          if (saved++ < 6) {
            val base = "${MODE}_${phase}_$i"
            ScreenCases.savePng(mismatchDir, "${base}_compose.png", w, h, ref)
            ScreenCases.savePng(mismatchDir, "${base}_obvid.png", w, h, px)
          }
          if (dp != null) {
            val group = "${MODE}_${phase}_${c.paint.name}"
            val previous = worst[group]
            if (previous == null || dp.max > previous.first) {
              if (previous != null) for (s in listOf("compose", "obvid")) File(mismatchDir, "${previous.second}_$s.png").delete()
              val base = "worst_${group}_$i"
              ScreenCases.savePng(mismatchDir, "${base}_compose.png", w, h, ref)
              ScreenCases.savePng(mismatchDir, "${base}_obvid.png", w, h, px)
              worst[group] = dp.max to base
            }
          }
        }
      }
    }
    clear()
    val summary = ArrayList<String>()
    for ((k, v) in counts) summary += "$k: identical ${v[0]} / ${v[1]}"
    summary += "cell ${w}x$h px, API ${Build.VERSION.SDK_INT}, ${Build.MODEL}, density ${context.resources.displayMetrics.density}"
    if (!TargetSkia.isDevice) {
      summary += "${TargetSkia.offTargetLabel} (premultiplied ARGB):"
      for ((k, t) in tallies) summary += "  $k: $t"
    }
    summary.forEach { Log.i(TAG, it) }
    writeResults(context, "inner1-screen.csv", rows, RESULTS_DIR)
    writeResults(context, "inner1-screen-summary.txt", summary, RESULTS_DIR)
    if (TargetSkia.isDevice) for ((k, v) in counts) assertEquals("$k identical", v[1], v[0])
  }

  @Test
  fun fallbackMatchesCompose() {
    setCellContent()
    val context = rule.activity
    val rows = ArrayList<String>()
    rows += HEADER
    var identical = 0
    var total = 0
    val buildsBefore = mainStore { it.buildCount }
    for ((i, c) in InnerScreenCases.cases.withIndex()) {
      NativeShadows.forceComposeFallback = false
      show(c, Impl.Compose, newNode = true)
      val ref = capture()
      // The switch is read when the modifier is created (composition).
      NativeShadows.forceComposeFallback = true
      show(c, Impl.Obvid, newNode = true)
      val px = capture()
      val d = ScreenCases.diff(ref, px)
      if (d.identical) identical++
      total++
      rows += row(i, c, "fallback", "fresh", d)
    }
    NativeShadows.forceComposeFallback = false
    val (builds, storeSize) = mainStore { (it.buildCount - buildsBefore) to it.entriesForTest().size }
    val summary =
      listOf(
        "fallback: identical $identical / $total, store builds during fallback $builds, " +
          "store size $storeSize",
        "API ${Build.VERSION.SDK_INT}, ${Build.MODEL}",
      )
    summary.forEach { Log.i(TAG, it) }
    writeResults(context, "inner1-fallback.csv", rows, RESULTS_DIR)
    writeResults(context, "inner1-fallback-summary.txt", summary, RESULTS_DIR)
    assertEquals(total, identical)
    assertEquals(0L, builds)
  }

  /**
   * The banded draw against Compose's single draw: each of [InnerScreenCases.bandCases] (plain and under a layer that
   * scales by 0.73 and rotates by 17°) drawn by Compose, then by Obvid's (a new node each), then by Obvid's again with
   * [NativeShadows.forceSingleDraw] set (a new node: the same bitmap drawn in one draw), captured and compared byte for
   * byte. Obvid against Compose is asserted identical on the target Skia. Asserted everywhere (neither depends on the
   * device's Skia): Obvid in bands equals Obvid in one draw, and a case that expects bands drew in bands
   * ([ShadowEntry.bandDrawCount] > 0, none with the switch set) while one that expects none did not. Writes
   * `band-screen.csv` and `band-screen-summary.txt` (subdir [InnerScreenCases.RESULTS_DIR2]) and PNGs of the first
   * mismatches in `mismatch-band/`.
   */
  @Test
  fun bandedDrawing() {
    check(NativeShadows.isAvailable) { "native library not available" }
    var band by mutableStateOf<Pair<InnerScreenCases.BandCase, Impl>?>(null)
    var bandToken by mutableStateOf(0)
    rule.setContent {
      val s = band
      if (s != null) {
        key(bandToken) {
          val (c, impl) = s
          val layer =
            if (c.transformed) {
              Modifier.graphicsLayer {
                scaleX = 0.73f
                scaleY = 0.73f
                rotationZ = 17f
              }
            } else {
              Modifier
            }
          Cell(
            Modifier.testTag(TAG_CELL),
            ScreenCases.CELL,
            c.sizeDp.dp,
            layer.caseInnerShadow(impl, c.shape, c.radiusDp.dp, c.spreadDp.dp, c.paint, c.block),
          )
        }
      }
    }
    val context = rule.activity
    val mismatchDir = File(outputDirs(context, InnerScreenCases.RESULTS_DIR2).last(), "mismatch-band")
    val rows = ArrayList<String>()
    rows += "model,api,index,case,identical,diffPixels,maxDiff,hasBands,bandDraws,expected,bandedVsSingleDiff,bandedVsSingleMax"
    val failures = ArrayList<String>()
    var identical = 0
    var banded = 0
    var lossless = 0
    var saved = 0
    val cases = InnerScreenCases.bandCases()
    for ((i, c) in cases.withIndex()) {
      band = null
      rule.waitForIdle()
      clearIdleShadows()
      bandToken++
      band = c to Impl.Compose
      rule.waitForIdle()
      val ref = capture()
      bandToken++
      band = c to Impl.Obvid
      rule.waitForIdle()
      val px = capture()
      val (hasBands, draws) =
        mainStore { s -> s.entriesForTest().singleOrNull()?.let { it.bitmaps.hasBands to it.bandDrawCount } ?: (false to -1) }
      // The same case in one draw: the switch is read by the draw, so it holds for the new node's draws only. The new
      // node may take the same entry back from the store: its counter restarts here.
      val single: IntArray
      val singleDraws: Int
      NativeShadows.forceSingleDraw = true
      try {
        mainStore { s -> s.entriesForTest().forEach { it.bandDrawCount = 0 } }
        bandToken++
        rule.waitForIdle()
        single = capture()
        singleDraws = mainStore { s -> s.entriesForTest().singleOrNull()?.bandDrawCount ?: -1 }
      } finally {
        NativeShadows.forceSingleDraw = false
      }
      val d = ScreenCases.diff(ref, px)
      val ds = ScreenCases.diff(single, px)
      if (d.identical) identical++
      if (ds.identical) lossless++
      if (draws > 0) banded++
      val expected = c.bands
      if (expected != null && (draws > 0) != expected) failures += "${c.label}: band draws $draws, expected bands $expected"
      if (singleDraws != 0) failures += "${c.label}: band draws $singleDraws with the single draw forced"
      if (!ds.identical) failures += "${c.label}: banded vs single draw: ${ds.pixels} px differ, max ${ds.max}"
      if (TargetSkia.isDevice && !d.identical) failures += "${c.label}: ${d.pixels} px differ, max ${d.max}"
      if (!d.identical) {
        Log.w(TAG, "band mismatch ${c.label}: ${d.pixels} px, max ${d.max}")
        if (saved++ < 6) {
          val b = rule.onNodeWithTag(TAG_CELL).captureToImage()
          ScreenCases.savePng(mismatchDir, "band_${i}_compose.png", b.width, b.height, ref)
          ScreenCases.savePng(mismatchDir, "band_${i}_obvid.png", b.width, b.height, px)
        }
      }
      if (!ds.identical && saved++ < 6) {
        val b = rule.onNodeWithTag(TAG_CELL).captureToImage()
        ScreenCases.savePng(mismatchDir, "band_${i}_single.png", b.width, b.height, single)
        ScreenCases.savePng(mismatchDir, "band_${i}_banded.png", b.width, b.height, px)
      }
      rows +=
        listOf(
            Build.MODEL.replace(',', ' '),
            Build.VERSION.SDK_INT,
            i,
            c.label,
            if (d.identical) 1 else 0,
            d.pixels,
            d.max,
            if (hasBands) 1 else 0,
            draws,
            c.bands?.toString() ?: "-",
            ds.pixels,
            ds.max,
          )
          .joinToString(",")
    }
    band = null
    rule.waitForIdle()
    val summary =
      listOf(
        "band: identical to Compose $identical / ${cases.size}, banded identical to the single draw $lossless / " +
          "${cases.size}, drawn in bands $banded / ${cases.size}",
        "API ${Build.VERSION.SDK_INT}, ${Build.MODEL}, density ${context.resources.displayMetrics.density}",
      ) + failures
    summary.forEach { Log.i(TAG, it) }
    writeResults(context, "band-screen.csv", rows, InnerScreenCases.RESULTS_DIR2)
    writeResults(context, "band-screen-summary.txt", summary, InnerScreenCases.RESULTS_DIR2)
    assertTrue(failures.joinToString("\n"), failures.isEmpty())
  }

  /**
   * The stepped animation test: over [FRAMES] frames the run's value moves 2 → 40 → 2 (radius or offset,
   * [InnerScreenCases.animInnerShadow]), Compose's cell above Obvid's and Compose's again (control) in the same frame,
   * all captured every frame. Obvid's node re-keys its entry in place every frame, alternating its two buffers (every
   * inner draw samples the bitmap through a shader).
   */
  @Test
  fun animationPerFrame() = animate(InnerScreenCases.animRuns(), "inner1-anim", RESULTS_DIR, withReuse = false)

  /**
   * [animationPerFrame] for [InnerScreenCases.reuseAnimRuns]: the spread on a path (both placements), the offset and
   * the content size on a nine-patch round rect, both overloads. Also checked per run: the nine-patch runs blur exactly
   * twice (the first build, which publishes, and, after the trim of frame 0 drops that, the first rebuild into the
   * private buffer) and place a kept small round rect on every other build, the path runs never.
   */
  @Test
  fun reuseAnimationPerFrame() =
    animate(InnerScreenCases.reuseAnimRuns(), "inner2-anim", InnerScreenCases.RESULTS_DIR2, withReuse = true)

  private fun animate(runs: List<InnerScreenCases.AnimRun>, fileBase: String, dir: String, withReuse: Boolean) {
    check(NativeShadows.isAvailable) { "native library not available" }
    clearStore()
    val context = rule.activity
    var value by mutableFloatStateOf(2f)
    var run by mutableStateOf(0)
    // No published mask before a run's first build (see the counters' check below).
    clearIdleShadows()
    rule.setContent {
      val r = runs[run]
      val cellDp = ScreenCases.animCell()
      // New nodes per run, so the per-run counters start at 0.
      key(run) { ScreenCases.CellStack {
        for ((tag, impl) in listOf(TAG_COMPOSE to Impl.Compose, TAG_OBVID to Impl.Obvid, TAG_CONTROL to Impl.Compose)) {
          Cell(Modifier.testTag(tag), cellDp, InnerScreenCases.contentSizeOf(r) { value }, InnerScreenCases.animInnerShadow(impl, r, { value }))
        }
      } }
    }
    val rows = ArrayList<String>()
    rows += "model,api,run,frame,value,identical,diffPixels,maxDiff,controlIdentical,allocations,builds" +
      if (withReuse) ",reuses" else ""
    val summary = ArrayList<String>()
    val tallies = LinkedHashMap<String, DiffTally>()
    // Per run: (builds, nine-patch reuses, nine-patch blurs, published hits) of Obvid's entry at the end.
    val reuse = ArrayList<IntArray>()
    for ((ri, r) in runs.withIndex()) {
      clearIdleShadows()
      run = ri
      val tally = DiffTally().also { tallies[r.name] = it }
      var identical = 0
      var controlIdentical = 0
      for (f in 0 until FRAMES) {
        // A triangle 2 → 40 → 2 with fractional steps.
        val t = f / (FRAMES / 2f)
        value = 2f + 38f * (if (t <= 1f) t else 2f - t)
        rule.waitForIdle()
        // The previous run's entry is idle now; dropped so that this run's counters are its own node's entry's.
        if (f == 0) clearIdleShadows()
        val a = ScreenCases.pixelsOf(rule.onNodeWithTag(TAG_COMPOSE).captureToImage().asAndroidBitmap())
        val b = ScreenCases.pixelsOf(rule.onNodeWithTag(TAG_OBVID).captureToImage().asAndroidBitmap())
        val c = ScreenCases.pixelsOf(rule.onNodeWithTag(TAG_CONTROL).captureToImage().asAndroidBitmap())
        val d = ScreenCases.diff(a, b)
        if (d.identical) identical++
        if (!TargetSkia.isDevice) tally.add(ScreenCases.diffPremul(a, b))
        val dc = ScreenCases.diff(a, c)
        if (dc.identical) controlIdentical++
        val (allocations, builds, reuses) =
          mainStore { s ->
            val entry = s.entriesForTest().singleOrNull()
            Triple(entry?.bitmaps?.allocationCount ?: -1, entry?.buildCount ?: -1, entry?.bitmaps?.nineReuseCount ?: -1)
          }
        rows +=
          (listOf(
              Build.MODEL.replace(',', ' '),
              Build.VERSION.SDK_INT,
              r.name,
              f,
              value,
              if (d.identical) 1 else 0,
              d.pixels,
              d.max,
              if (dc.identical) 1 else 0,
              allocations,
              builds,
            ) + if (withReuse) listOf(reuses) else emptyList())
            .joinToString(",")
      }
      val (entries, builds, allocations) =
        mainStore { s ->
          val entry = s.entriesForTest().singleOrNull()
          Triple(s.entriesForTest().size, entry?.buildCount, entry?.bitmaps?.allocationCount)
        }
      val reuses = mainStore { s -> s.entriesForTest().singleOrNull()?.bitmaps?.nineReuseCount ?: -1 }
      val (blurs, shared) =
        mainStore { s -> s.entriesForTest().singleOrNull()?.bitmaps.let { (it?.nineBlurCount ?: -1) to (it?.nineSharedHitCount ?: -1) } }
      reuse += intArrayOf(builds ?: -1, reuses, blurs, shared)
      summary +=
        "${r.name}: identical $identical / $FRAMES frames (control $controlIdentical / $FRAMES); " +
          "store entries $entries, builds $builds, " +
          "bitmap allocations $allocations" +
          if (withReuse) ", nine-patch reuses $reuses" else ""
    }
    summary += "API ${Build.VERSION.SDK_INT}, ${Build.MODEL}"
    if (!TargetSkia.isDevice) {
      summary += "${TargetSkia.offTargetLabel} (premultiplied ARGB):"
      for ((name, t) in tallies) summary += "  $name: $t"
    }
    summary.forEach { Log.i(TAG, it) }
    writeResults(context, "$fileBase.csv", rows, dir)
    writeResults(context, "$fileBase-summary.txt", summary, dir)
    if (TargetSkia.isDevice) {
      for (s in summary.take(runs.size)) assertTrue(s, s.contains("identical $FRAMES / $FRAMES frames"))
    }
    if (withReuse) {
      for ((i, r) in runs.withIndex()) {
        val (builds, reuses, blurs, shared) = reuse[i]
        val nine = r.animated == InnerScreenCases.Animated.Offset || r.animated == InnerScreenCases.Animated.Size
        assertTrue("${r.name}: builds $builds", builds >= FRAMES - 1)
        // Relies on the trims: before the run nothing is published, so the first build blurs and publishes; the trim
        // after frame 0 drops that mask, so the first rebuild blurs into the entry's private buffer, which later builds
        // place. Two blurs, no published hit.
        if (nine) {
          assertEquals("${r.name}: nine-patch blurs", 2, blurs)
          assertEquals("${r.name}: published hits", 0, shared)
        }
        assertEquals("${r.name}: nine-patch reuses of $builds builds", if (nine) builds - blurs else 0, reuses)
      }
    }
  }

  private fun clearStore() {
    val refCounts = mainStore { s -> s.entriesForTest().map { it.refCount } }
    check(refCounts.isEmpty()) { "store not empty: $refCounts" }
  }

  private fun row(i: Int, c: Case, mode: String, phase: String, d: ScreenCases.Diff) =
    listOf(
        Build.MODEL.replace(',', ' '),
        Build.VERSION.SDK_INT,
        mode,
        phase,
        i,
        c.shape.name,
        c.sizeDp,
        c.radiusDp,
        c.spreadDp,
        c.paint.name,
        if (c.block) "block" else "shadow",
        if (d.identical) 1 else 0,
        d.pixels,
        d.max,
      )
      .joinToString(",")

  private companion object {
    const val TAG = "FS-InnerScreen"
    const val TAG_CELL = "cell"
    const val TAG_COMPOSE = "compose"
    const val TAG_OBVID = "obvid"
    const val TAG_CONTROL = "control"
    // The CSV's mode column (the only mode).
    const val MODE = "exact"
    const val FRAMES = 120
    const val HEADER = "model,api,mode,phase,index,shape,sizeDp,radiusDp,spreadDp,paint,overload,identical,diffPixels,maxDiff"
  }
}
