// Copyright 2026, Serhii Yaremych (desugar-64) and the Obvid project contributors
// SPDX-License-Identifier: Apache-2.0

package dev.serhiiyaremych.obvid

import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.PixelCopy
import android.view.View
import android.view.Window
import androidx.activity.ComponentActivity
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import dev.serhiiyaremych.obvid.InnerScreenCases.Animated
import dev.serhiiyaremych.obvid.InnerScreenCases.Cell
import dev.serhiiyaremych.obvid.ScreenCases.Impl
import dev.serhiiyaremych.obvid.InnerScreenCases.RESULTS_DIR
import dev.serhiiyaremych.obvid.ScreenCases.showOverLockScreen
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The inner shadow's free-running animation test, the twin of [DropShadowRaceTest]: a real-time infinite animation
 * (real Choreographer frames, no test clock) of the radius or the offset ([InnerScreenCases.animRuns]), Compose's cell
 * above Obvid's, the window copied with `PixelCopy` [CAPTURES] times per run while the animation runs. Both cells read
 * the same animated value in the same frame, so every copy must show them identical. Every inner draw samples its
 * bitmap through a shader, which HWUI does not pin at sync, so every rebuild must write the other buffer; a
 * RenderThread race on a rewritten bitmap would show as a mismatch.
 *
 * Below the target Skia ([TargetSkia.isDevice]) the Obvid cell is compared with its twin (a second Obvid cell under its
 * own store entry, [TwinShape]) plus a liveness check, without Compose cells ([againstTwin]).
 * Output: `inner1-race.csv`, `inner1-race-summary.txt`.
 */
// PixelCopy.request(Window, …) needs API 26.
@SdkSuppress(minSdkVersion = 26)
@LargeTest
@RunWith(AndroidJUnit4::class)
class InnerShadowRaceTest {

  @Test
  fun freeRunningAnimation() = runAll(InnerScreenCases.animRuns(), "inner1-race", RESULTS_DIR, withReuse = false)

  /**
   * [freeRunningAnimation] for the offset and size runs of [InnerScreenCases.reuseAnimRuns] (a nine-patch round rect,
   * both overloads): from its second build on Obvid's entry places a kept blurred small round rect (the one its first
   * build published, or its private one), so placed rebuilds go through the second buffer while HWUI may still sample
   * the first. Each run must reuse during the captures. Output (subdir [InnerScreenCases.RESULTS_DIR2]): `inner2-race.csv`, `inner2-race-summary.txt`.
   */
  @Test
  fun freeRunningReuse() =
    runAll(
      InnerScreenCases.reuseAnimRuns().filter { it.animated == Animated.Offset || it.animated == Animated.Size },
      "inner2-race",
      InnerScreenCases.RESULTS_DIR2,
      withReuse = true,
    )

  private fun runAll(runs: List<InnerScreenCases.AnimRun>, fileBase: String, dir: String, withReuse: Boolean) {
    check(NativeShadows.isAvailable)
    NativeShadows.forceComposeFallback = false
    if (TargetSkia.isDevice) againstCompose(runs, fileBase, dir, withReuse) else againstTwin(runs, fileBase, dir, withReuse)
  }

  /** The store's nine-patch reuse count on the main thread. */
  private fun reuseCount(): Long = mainStore { it.nineReuseCount }

  /** On the target Skia: Compose, Obvid, and Compose again (the control), stacked. */
  private fun againstCompose(runs: List<InnerScreenCases.AnimRun>, fileBase: String, dir: String, withReuse: Boolean) {
    val boundsCompose = AtomicReference<Rect>()
    val boundsObvid = AtomicReference<Rect>()
    val boundsControl = AtomicReference<Rect>()
    val drawnFrames = AtomicInteger()
    var runIndex by mutableIntStateOf(0)
    val rows = ArrayList<String>()
    rows += "model,api,run,capture,identical,diffPixels,maxDiff,controlIdentical,controlDiffPixels,controlMaxDiff"
    val summary = ArrayList<String>()
    val reusesPerRun = ArrayList<Long>()
    val thread = HandlerThread("pixelcopy").apply { start() }
    val handler = Handler(thread.looper)
    ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
      lateinit var window: Window
      scenario.onActivity { a ->
        a.showOverLockScreen()
        window = a.window
        a.setContentView(ComposeView(a).apply { setContent {
          val r = runs[runIndex]
          val transition = rememberInfiniteTransition(label = "value")
          val animated = transition.animateFloat(
            2f,
            40f,
            infiniteRepeatable(tween(1300, easing = LinearEasing), RepeatMode.Reverse),
            label = "v",
          )
          val cellDp = ScreenCases.animCell()
          // New nodes per run, so the per-run counters start at 0.
          key(runIndex) { ScreenCases.CellStack {
            // Compose, Obvid, and Compose again (control: Compose against itself in the same frame).
            for ((index, impl) in listOf(Impl.Compose, Impl.Obvid, Impl.Compose).withIndex()) {
              val mod =
                InnerScreenCases.animInnerShadow(impl, r, { animated.value }) { if (index == 1) drawnFrames.incrementAndGet() }
              val target = when (index) { 0 -> boundsCompose; 1 -> boundsObvid; else -> boundsControl }
              Cell(
                Modifier.onGloballyPositioned { target.set(it.boundsInWindow()) },
                cellDp,
                InnerScreenCases.contentSizeOf(r) { animated.value },
                mod,
              )
            }
          } }
        } })
      }
      for ((ri, r) in runs.withIndex()) {
        scenario.onActivity { runIndex = ri }
        Thread.sleep(800)
        val a = boundsCompose.get()
        val b = boundsObvid.get()
        val c = boundsControl.get()
        var controlIdentical = 0
        var saved = 0
        val w = a.width.roundToInt()
        val h = a.height.roundToInt()
        var identical = 0
        var copies = 0
        val frames0 = drawnFrames.get()
        val reuse0 = reuseCount()
        repeat(CAPTURES) { i ->
          val full = Bitmap.createBitmap(window.decorView.width, window.decorView.height, Bitmap.Config.ARGB_8888)
          val latch = CountDownLatch(1)
          var result = -1
          InstrumentationRegistry.getInstrumentation().runOnMainSync {
            PixelCopy.request(window, full, { res -> result = res; latch.countDown() }, handler)
          }
          latch.await(5, TimeUnit.SECONDS)
          if (result != PixelCopy.SUCCESS) {
            full.recycle()
            return@repeat
          }
          copies++
          val pa = IntArray(w * h)
          val pb = IntArray(w * h)
          val pc = IntArray(w * h)
          full.getPixels(pa, 0, w, a.left.roundToInt(), a.top.roundToInt(), w, h)
          full.getPixels(pb, 0, w, b.left.roundToInt(), b.top.roundToInt(), w, h)
          full.getPixels(pc, 0, w, c.left.roundToInt(), c.top.roundToInt(), w, h)
          val d = ScreenCases.diff(pa, pb)
          val dc = ScreenCases.diff(pa, pc)
          if (dc.identical) controlIdentical++
          if (d.identical) identical++
          else if (saved++ < 3) {
            val raceDir = File(outputDirs(InstrumentationRegistry.getInstrumentation().targetContext, dir).last(), "race")
            ScreenCases.savePng(raceDir, "${r.name}_${i}_compose.png", w, h, pa)
            ScreenCases.savePng(raceDir, "${r.name}_${i}_obvid.png", w, h, pb)
            ScreenCases.savePng(raceDir, "${r.name}_${i}_control.png", w, h, pc)
          }
          rows +=
            listOf(
                Build.MODEL.replace(',', ' '), Build.VERSION.SDK_INT, r.name, i, if (d.identical) 1 else 0, d.pixels, d.max,
                if (dc.identical) 1 else 0, dc.pixels, dc.max,
              )
              .joinToString(",")
          full.recycle()
          Thread.sleep(7)
        }
        val frames = drawnFrames.get() - frames0
        val reuses = reuseCount() - reuse0
        reusesPerRun += reuses
        val (entries, allocations, builds) =
          mainStore { s ->
            val e = s.entriesForTest()
            Triple(e.size, e.map { it.bitmaps.allocationCount }, e.map { it.buildCount })
          }
        summary +=
          "${r.name}: identical $identical / $copies copies (of $CAPTURES), control (Compose vs Compose) identical " +
            "$controlIdentical / $copies, block reads during capture $frames, " +
            "store entries $entries, bitmap allocations $allocations, " +
            "builds $builds" +
            if (withReuse) ", nine-patch reuses during capture $reuses" else ""
      }
    }
    thread.quitSafely()
    summary += "API ${Build.VERSION.SDK_INT}, ${Build.MODEL}"
    summary.forEach { Log.i(TAG, it) }
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    writeResults(context, "$fileBase.csv", rows, dir)
    writeResults(context, "$fileBase-summary.txt", summary, dir)
    // Compose against itself in the same copy also differs by 1 in a few copies (see the control column), so a race
    // is judged against the control: Obvid's differences must be no larger and no more frequent than the control's.
    val parsed = rows.drop(1).map { it.split(",") }
    val obvidMax = parsed.maxOf { it[6].toInt() }
    val controlMax = parsed.maxOf { it[9].toInt() }
    val obvidMismatches = parsed.count { it[4] == "0" }
    val controlMismatches = parsed.count { it[7] == "0" }
    val verdict = "Obvid vs Compose: $obvidMismatches mismatching copies, max diff $obvidMax; " +
      "control: $controlMismatches mismatching copies, max diff $controlMax; copies ${parsed.size}"
    Log.i(TAG, verdict)
    writeResults(context, "$fileBase-summary.txt", summary + verdict, dir)
    assertTrue(verdict, parsed.size > runs.size * CAPTURES / 2)
    assertTrue(verdict, obvidMax <= maxOf(1, controlMax))
    assertTrue(verdict, obvidMismatches <= 2 * controlMismatches + 5)
    if (withReuse) for ((i, r) in runs.withIndex()) assertTrue("${r.name}: no reuse during capture", reusesPerRun[i] > 0)
  }

  /**
   * Below the target Skia: the Obvid cell above its twin. Checked per copy: Obvid vs twin within the capture noise
   * ([NOISE_MAX_DIFF]). Checked per cell (liveness, [Liveness]): a bitmap stale the same way in both cells (a texture
   * cache missing the in-place rewrite) would still compare equal, so each cell's pixels must change whenever the
   * animated value it drew moved by [LIVENESS_STEP]. The values are read on the main thread right before each copy
   * request.
   */
  private fun againstTwin(runs: List<InnerScreenCases.AnimRun>, fileBase: String, dir: String, withReuse: Boolean) {
    val twins = runs.map { InnerScreenCases.AnimRun(it.name, TwinShape(it.shape), it.block, it.brush, it.animated, it.spreadDp, it.offset) }
    val boundsObvid = AtomicReference<Rect>()
    val boundsTwin = AtomicReference<Rect>()
    // The animated value (float bits) each cell last read for drawing: in the draw block, or in composition for the
    // `Shadow` overload.
    val valueObvid = AtomicInteger()
    val valueTwin = AtomicInteger()
    val drawnFrames = AtomicInteger()
    var runIndex by mutableIntStateOf(0)
    val rows = ArrayList<String>()
    rows += "model,api,run,capture,identical,diffPixels,maxDiff,obvidRadiusDp,twinRadiusDp"
    val summary = ArrayList<String>()
    var copiesTotal = 0
    var mismatches = 0
    var maxDiff = 0
    var stale = 0
    val reusesPerRun = ArrayList<Long>()
    val thread = HandlerThread("pixelcopy").apply { start() }
    val handler = Handler(thread.looper)
    ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
      lateinit var window: Window
      scenario.onActivity { a ->
        a.showOverLockScreen()
        window = a.window
        a.setContentView(ComposeView(a).apply { setContent {
          val transition = rememberInfiniteTransition(label = "value")
          val animated = transition.animateFloat(
            2f,
            40f,
            infiniteRepeatable(tween(1300, easing = LinearEasing), RepeatMode.Reverse),
            label = "v",
          )
          val cellDp = ScreenCases.animCell()
          // New nodes per run, so the per-run counters start at 0.
          key(runIndex) { Column {
            val obvid =
              InnerScreenCases.animInnerShadow(Impl.Obvid, runs[runIndex], { animated.value.also { valueObvid.set(it.toBits()) } }) {
                drawnFrames.incrementAndGet()
              }
            val contentSize = InnerScreenCases.contentSizeOf(runs[runIndex]) { animated.value }
            Cell(Modifier.onGloballyPositioned { boundsObvid.set(it.boundsInWindow()) }, cellDp, contentSize, obvid)
            val twin =
              InnerScreenCases.animInnerShadow(Impl.Obvid, twins[runIndex], { animated.value.also { valueTwin.set(it.toBits()) } })
            Cell(Modifier.onGloballyPositioned { boundsTwin.set(it.boundsInWindow()) }, cellDp, contentSize, twin)
          } }
        } })
      }
      for ((ri, r) in runs.withIndex()) {
        // Not onActivity: it waits for the main thread to go idle, which a running animation can prevent indefinitely.
        InstrumentationRegistry.getInstrumentation().runOnMainSync { runIndex = ri }
        Thread.sleep(800)
        val b = boundsObvid.get()
        val t = boundsTwin.get()
        var saved = 0
        val w = b.width.roundToInt()
        val h = b.height.roundToInt()
        var identical = 0
        var copies = 0
        var runMax = 0
        val livenessObvid = Liveness()
        val livenessTwin = Liveness()
        val frames0 = drawnFrames.get()
        val reuse0 = reuseCount()
        repeat(CAPTURES) { i ->
          val full = Bitmap.createBitmap(window.decorView.width, window.decorView.height, Bitmap.Config.ARGB_8888)
          val latch = CountDownLatch(1)
          var result = -1
          var ro = 0f
          var rt = 0f
          InstrumentationRegistry.getInstrumentation().runOnMainSync {
            ro = Float.fromBits(valueObvid.get())
            rt = Float.fromBits(valueTwin.get())
            PixelCopy.request(window, full, { res -> result = res; latch.countDown() }, handler)
          }
          latch.await(5, TimeUnit.SECONDS)
          if (result != PixelCopy.SUCCESS) {
            full.recycle()
            return@repeat
          }
          copies++
          val pb = IntArray(w * h)
          val pt = IntArray(w * h)
          full.getPixels(pb, 0, w, b.left.roundToInt(), b.top.roundToInt(), w, h)
          full.getPixels(pt, 0, w, t.left.roundToInt(), t.top.roundToInt(), w, h)
          val d = ScreenCases.diff(pt, pb)
          runMax = max(runMax, d.max)
          livenessObvid.next(ro, pb)
          livenessTwin.next(rt, pt)
          if (d.identical) identical++
          else if (saved++ < 3) {
            val raceDir = File(outputDirs(InstrumentationRegistry.getInstrumentation().targetContext, dir).last(), "race")
            ScreenCases.savePng(raceDir, "${r.name}_${i}_obvid.png", w, h, pb)
            ScreenCases.savePng(raceDir, "${r.name}_${i}_twin.png", w, h, pt)
          }
          rows +=
            listOf(
                Build.MODEL.replace(',', ' '), Build.VERSION.SDK_INT, r.name, i, if (d.identical) 1 else 0, d.pixels, d.max,
                ro, rt,
              )
              .joinToString(",")
          full.recycle()
          Thread.sleep(7)
        }
        val frames = drawnFrames.get() - frames0
        val reuses = reuseCount() - reuse0
        reusesPerRun += reuses
        val (entries, allocations, builds) =
          mainStore { s ->
            val e = s.entriesForTest()
            Triple(e.size, e.map { it.bitmaps.allocationCount }, e.map { it.buildCount })
          }
        copiesTotal += copies
        mismatches += copies - identical
        maxDiff = max(maxDiff, runMax)
        stale += livenessObvid.stale + livenessTwin.stale
        summary +=
          "${r.name}: Obvid vs twin identical $identical / $copies copies (of $CAPTURES), max diff $runMax, " +
            "block reads during capture $frames, store entries $entries, bitmap allocations $allocations, " +
            "builds $builds; liveness (value moved >= $LIVENESS_STEP): Obvid ${livenessObvid.stale} unchanged " +
            "of ${livenessObvid.checks}, twin ${livenessTwin.stale} unchanged of ${livenessTwin.checks}" +
            if (withReuse) ", nine-patch reuses during capture $reuses" else ""
      }
      // Stop the animation before close(): close() waits for the main thread to go idle.
      InstrumentationRegistry.getInstrumentation().runOnMainSync { window.setContentView(View(window.context)) }
    }
    thread.quitSafely()
    summary += "API ${Build.VERSION.SDK_INT}, ${Build.MODEL}; below the target Skia: Obvid vs its twin, no Compose cell"
    val verdict = "Obvid vs twin: ${mismatches} mismatching copies, max diff $maxDiff (allowed $NOISE_MAX_DIFF); " +
      "liveness: $stale stale copies; copies $copiesTotal"
    summary += verdict
    summary.forEach { Log.i(TAG, it) }
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    writeResults(context, "$fileBase.csv", rows, dir)
    writeResults(context, "$fileBase-summary.txt", summary, dir)
    assertTrue(verdict, copiesTotal > runs.size * CAPTURES / 2)
    assertTrue(verdict, maxDiff <= NOISE_MAX_DIFF)
    assertEquals(verdict, 0, stale)
    if (withReuse) for ((i, r) in runs.withIndex()) assertTrue("${r.name}: no reuse during capture", reusesPerRun[i] > 0)
  }

  /**
   * One cell's liveness: the reference copy and the animated value read at its request. When a copy's value differs from the
   * reference's by at least [LIVENESS_STEP], its pixels must differ ([stale] counts those that do not) and it
   * becomes the reference.
   */
  private class Liveness {
    private var value = 0f
    private var pixels: IntArray? = null
    var checks = 0
      private set
    var stale = 0
      private set

    fun next(r: Float, px: IntArray) {
      val ref = pixels
      if (ref != null && abs(r - value) < LIVENESS_STEP) return
      if (ref != null) {
        checks++
        if (ref.contentEquals(px)) stale++
      }
      value = r
      pixels = px
    }
  }

  /** [shape]'s outline under a shape equal only to itself: a node drawing it gets its own store entry. */
  private class TwinShape(private val shape: Shape) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline =
      shape.createOutline(size, layoutDirection, density)
  }

  private companion object {
    const val TAG = "FS-InnerRace"
    const val CAPTURES = 150

    /**
     * Below the target: the largest channel difference allowed between the Obvid cell and its twin. Two identical
     * renders in one emulator capture can still differ by 1–2 in a channel (capture noise), so this is the noise, not a
     * tolerance for a different shadow.
     */
    const val NOISE_MAX_DIFF = 2

    /**
     * Below the target: the step of the animated value after which a cell's pixels must have changed. A copy can show
     * a frame older or newer than the value read at its request; the value moves ≈ 29 per second (38 in 1.3 s), so
     * with copies within 100 ms of their request two copies 8 apart in the read value still show values ≥ 2 apart: a
     * radius ≥ 2 dp apart, or an offset ≥ 1 dp apart on x.
     */
    const val LIVENESS_STEP = 8f
  }
}
