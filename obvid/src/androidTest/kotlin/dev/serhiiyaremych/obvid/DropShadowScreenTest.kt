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
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.filters.SdkSuppress
import dev.serhiiyaremych.obvid.ScreenCases.Case
import dev.serhiiyaremych.obvid.ScreenCases.Cell
import dev.serhiiyaremych.obvid.ScreenCases.Impl
import dev.serhiiyaremych.obvid.ScreenCases.RESULTS_DIR
import dev.serhiiyaremych.obvid.ScreenCases.caseShadow
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
 * Obvid's `dropShadow` against Compose's on the screen. Each case is drawn by Compose's modifier, then by Obvid's in
 * the same cell at the same position, captured with `captureToImage` (a PixelCopy of the window) and compared byte for
 * byte.
 *
 * - `fresh`: a new node (new store entry, fresh bitmap) per case.
 * - `grown`: the same node first draws a larger case (200 dp morph r64 s8, same paint and overload), then the case:
 *   the entry is re-keyed in place and its bitmap reconfigured down to the used size.
 *
 * Output: `screen.csv` and `screen-summary.txt` (additional test output and files dir), PNGs of the
 * first mismatches in `mismatch/`; below the target Skia also the worst case (largest max diff, the first on ties) of
 * each mode, phase and paint there, `worst_<mode>_<phase>_<paint>_<index>_{compose,obvid}.png` (by the premultiplied
 * difference, [ScreenCases.diffPremul], as the off-target summary lines). [ScreenCases.roundCases] run after the
 * main cases (summary keys `exact/<phase>/round`; they take the nine-patch and full-mask routes).
 *
 * Obvid against Compose is asserted identical on the target Skia ([TargetSkia.isDevice]); elsewhere the summaries add
 * the difference from the device's Compose (PSNR) and it does not fail. The fallback test (Compose against Compose)
 * asserts everywhere.
 */
// captureToImage needs API 26.
@SdkSuppress(minSdkVersion = 26)
@LargeTest
@RunWith(AndroidJUnit4::class)
class DropShadowScreenTest {

  @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

  private class Shown(val case: Case, val impl: Impl, val token: Int)

  private var shown by mutableStateOf<Shown?>(null)
  private var token = 0

  @Before
  fun setUp() {
    rule.runOnUiThread { rule.activity.showOverLockScreen() }
    NativeShadows.forceComposeFallback = false
    clearIdleShadows()
  }

  @After
  fun tearDown() {
    NativeShadows.forceComposeFallback = false
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
            Modifier.caseShadow(s.impl, c.shape.shape, c.radiusDp.dp, c.spreadDp.dp, c.paint, c.block),
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
    val all = ScreenCases.cases.map { it to if (it.paint === ScreenCases.basePaint) "geometry" else "paint" } +
      ScreenCases.roundCases.map { it to "round" }
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
      show(ScreenCases.bigCase(c), Impl.Obvid, newNode = true)
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
    writeResults(context, "screen.csv", rows, RESULTS_DIR)
    writeResults(context, "screen-summary.txt", summary, RESULTS_DIR)
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
    for ((i, c) in ScreenCases.cases.withIndex()) {
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
    writeResults(context, "fallback.csv", rows, RESULTS_DIR)
    writeResults(context, "fallback-summary.txt", summary, RESULTS_DIR)
    assertEquals(total, identical)
    assertEquals(0L, builds)
  }

  /**
   * The stepped animation test: a radius animation over [FRAMES] frames, Compose's cell above Obvid's in the same
   * frame, both captured every frame. Obvid's node re-keys its entry in place every frame (and alternates buffers on
   * the brush run).
   */
  @Test
  fun animationPerFrame() {
    check(NativeShadows.isAvailable) { "native library not available" }
    clearStore()
    val context = rule.activity
    var radiusDp by mutableFloatStateOf(2f)
    var run by mutableStateOf(0)
    val runs =
      ScreenCases.animRuns(
        "morph-color-block", "rrect16-color-shadow", "mixed-color-block", "morph-brush-block", "rrect16-brush-shadow",
      )
    rule.setContent {
      val r = runs[run]
      val cellDp = ScreenCases.animCell()
      // New nodes per run, so the per-run counters start at 0.
      key(run) { ScreenCases.CellStack {
        // Compose, Obvid, and Compose again (control: Compose against itself in the same frame).
        for ((tag, impl) in listOf(TAG_COMPOSE to Impl.Compose, TAG_OBVID to Impl.Obvid, TAG_CONTROL to Impl.Compose)) {
          Cell(Modifier.testTag(tag), cellDp, 120.dp, ScreenCases.animShadow(impl, r, { radiusDp }))
        }
      } }
    }
    val rows = ArrayList<String>()
    rows += "model,api,run,frame,radiusDp,identical,diffPixels,maxDiff,controlIdentical,allocations,builds"
    val summary = ArrayList<String>()
    val tallies = LinkedHashMap<String, DiffTally>()
    for ((ri, r) in runs.withIndex()) {
      run = ri
      val tally = DiffTally().also { tallies[r.name] = it }
      var identical = 0
      var controlIdentical = 0
      for (f in 0 until FRAMES) {
        // A triangle 2 → 40 → 2 dp with fractional steps.
        val t = f / (FRAMES / 2f)
        radiusDp = 2f + 38f * (if (t <= 1f) t else 2f - t)
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
        val (allocations, builds) =
          mainStore { s ->
            val entry = s.entriesForTest().singleOrNull()
            (entry?.bitmaps?.allocationCount ?: -1) to (entry?.buildCount ?: -1)
          }
        rows +=
          listOf(
              Build.MODEL.replace(',', ' '),
              Build.VERSION.SDK_INT,
              r.name,
              f,
              radiusDp,
              if (d.identical) 1 else 0,
              d.pixels,
              d.max,
              if (dc.identical) 1 else 0,
              allocations,
              builds,
            )
            .joinToString(",")
      }
      val (entries, builds, allocations) =
        mainStore { s ->
          val entry = s.entriesForTest().singleOrNull()
          Triple(s.entriesForTest().size, entry?.buildCount, entry?.bitmaps?.allocationCount)
        }
      summary +=
        "${r.name}: identical $identical / $FRAMES frames (control $controlIdentical / $FRAMES); " +
          "store entries $entries, builds $builds, " +
          "bitmap allocations $allocations"
    }
    summary += "API ${Build.VERSION.SDK_INT}, ${Build.MODEL}"
    if (!TargetSkia.isDevice) {
      summary += "${TargetSkia.offTargetLabel} (premultiplied ARGB):"
      for ((name, t) in tallies) summary += "  $name: $t"
    }
    summary.forEach { Log.i(TAG, it) }
    writeResults(context, "anim.csv", rows, RESULTS_DIR)
    writeResults(context, "anim-summary.txt", summary, RESULTS_DIR)
    if (TargetSkia.isDevice) {
      for (s in summary.dropLast(1)) assertTrue(s, s.contains("identical $FRAMES / $FRAMES frames"))
    }
  }

  /**
   * A content-size animation (90 → 158.4 → 90 dp over [FRAMES] frames) on `rrect16` at radius 12 dp, spread 8 dp (the
   * nine-patch route), both overloads, Compose's cell above Obvid's and Compose's again (control), captured every
   * frame. Only the size changes, so Obvid's entry blurs its small round rect exactly twice (its first build, which
   * publishes it, and, after the trim of frame 0 drops that, its first rebuild into its private buffer) and places it
   * again on every other build. Output (subdir [InnerScreenCases.RESULTS_DIR2]): `drop2-size-anim.csv` and
   * `drop2-size-anim-summary.txt`.
   */
  @Test
  fun sizeAnimationPerFrame() {
    check(NativeShadows.isAvailable) { "native library not available" }
    clearStore()
    val context = rule.activity
    var value by mutableFloatStateOf(2f)
    var run by mutableStateOf(0)
    val shape = ScreenCases.shapes.first { it.name == "rrect16" }.shape
    val runs = listOf(ScreenCases.AnimRun("rrect16-size-shadow", shape, false, null), ScreenCases.AnimRun("rrect16-size-block", shape, true, null))
    // No published mask before a run's first build (see the counters' check below).
    clearIdleShadows()
    rule.setContent {
      val r = runs[run]
      val cellDp = ScreenCases.animCell()
      key(run) { ScreenCases.CellStack {
        for ((tag, impl) in listOf(TAG_COMPOSE to Impl.Compose, TAG_OBVID to Impl.Obvid, TAG_CONTROL to Impl.Compose)) {
          Cell(Modifier.testTag(tag), cellDp, (90f + 1.8f * (value - 2f)).dp, ScreenCases.animShadow(impl, r, { 12f }))
        }
      } }
    }
    val rows = ArrayList<String>()
    rows += "model,api,run,frame,value,identical,diffPixels,maxDiff,controlIdentical,allocations,builds,reuses"
    val summary = ArrayList<String>()
    val results = ArrayList<IntArray>()
    for ((ri, r) in runs.withIndex()) {
      clearIdleShadows()
      run = ri
      var identical = 0
      var controlIdentical = 0
      val tally = DiffTally()
      for (f in 0 until FRAMES) {
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
        val counters =
          mainStore { s ->
            val entry = s.entriesForTest().singleOrNull()
            listOf(entry?.bitmaps?.allocationCount ?: -1, entry?.buildCount ?: -1, entry?.bitmaps?.nineReuseCount ?: -1)
          }
        rows +=
          (listOf<Any>(
              Build.MODEL.replace(',', ' '),
              Build.VERSION.SDK_INT,
              r.name,
              f,
              value,
              if (d.identical) 1 else 0,
              d.pixels,
              d.max,
              if (dc.identical) 1 else 0,
            ) + counters)
            .joinToString(",")
      }
      val (entries, builds, reuses) =
        mainStore { s ->
          val entry = s.entriesForTest().singleOrNull()
          Triple(s.entriesForTest().size, entry?.buildCount ?: -1, entry?.bitmaps?.nineReuseCount ?: -1)
        }
      val (blurs, shared) =
        mainStore { s -> s.entriesForTest().singleOrNull()?.bitmaps.let { (it?.nineBlurCount ?: -1) to (it?.nineSharedHitCount ?: -1) } }
      results += intArrayOf(identical, builds, reuses, blurs, shared)
      summary +=
        "${r.name}: identical $identical / $FRAMES frames (control $controlIdentical / $FRAMES); store entries $entries, " +
          "builds $builds, nine-patch reuses $reuses" +
          if (TargetSkia.isDevice) "" else "; ${TargetSkia.offTargetLabel} (premultiplied ARGB): $tally"
    }
    summary += "API ${Build.VERSION.SDK_INT}, ${Build.MODEL}"
    summary.forEach { Log.i(TAG, it) }
    writeResults(context, "drop2-size-anim.csv", rows, InnerScreenCases.RESULTS_DIR2)
    writeResults(context, "drop2-size-anim-summary.txt", summary, InnerScreenCases.RESULTS_DIR2)
    for ((i, r) in runs.withIndex()) {
      val (identical, builds, reuses, blurs, shared) = results[i].toList()
      if (TargetSkia.isDevice) assertEquals("${r.name}: identical frames", FRAMES, identical)
      assertTrue("${r.name}: builds $builds", builds >= FRAMES - 1)
      // Relies on the trims: before the run nothing is published, so the first build blurs and publishes; the trim after
      // frame 0 drops that mask, so the first rebuild blurs into the entry's private buffer, which every later build
      // places. Two blurs, no published hit.
      assertEquals("${r.name}: nine-patch blurs", 2, blurs)
      assertEquals("${r.name}: published hits", 0, shared)
      assertEquals("${r.name}: nine-patch reuses of $builds builds", builds - blurs, reuses)
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
    const val TAG = "FS-Screen"
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
