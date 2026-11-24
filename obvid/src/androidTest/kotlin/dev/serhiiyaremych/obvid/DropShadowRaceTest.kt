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
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import dev.serhiiyaremych.obvid.ScreenCases.Cell
import dev.serhiiyaremych.obvid.ScreenCases.Impl
import dev.serhiiyaremych.obvid.ScreenCases.RESULTS_DIR
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
 * The free-running animation test: a real-time infinite radius animation (real Choreographer frames, no test clock),
 * Compose's cell above Obvid's, the window copied with `PixelCopy` [CAPTURES] times per run while the
 * animation runs. Both cells read the same animated value in the same frame, so every copy must show them identical;
 * a RenderThread race on Obvid's in-place rebuilt bitmap would show as a mismatch.
 *
 * Below the target Skia ([TargetSkia.isDevice]) Obvid's output differs from the device's Compose by design, and
 * Compose's shadow cache there keeps a new bitmap per animated radius (the process runs out of memory within minutes),
 * so no Compose cell is drawn: the Obvid cell is compared with its twin, a second Obvid cell with the same animated
 * values under its own store entry ([TwinShape]), each rebuilding its own bitmap in place ([againstTwin]).
 * Output: `race.csv`, `race-summary.txt`.
 */
// PixelCopy.request(Window, …) needs API 26.
@SdkSuppress(minSdkVersion = 26)
@LargeTest
@RunWith(AndroidJUnit4::class)
class DropShadowRaceTest {

  @Test
  fun freeRunningAnimation() {
    check(NativeShadows.isAvailable)
    NativeShadows.forceComposeFallback = false
    if (TargetSkia.isDevice) againstCompose() else againstTwin()
  }

  /** On the target Skia: Compose, Obvid, and Compose again (the control), stacked. */
  private fun againstCompose() {
    val runs =
      ScreenCases.animRuns(
        "morph-color-block", "mixed-color-block", "rrect16-color-shadow", "morph-brush-block", "rrect16-brush-shadow",
      )
    val boundsCompose = AtomicReference<Rect>()
    val boundsObvid = AtomicReference<Rect>()
    val boundsControl = AtomicReference<Rect>()
    val drawnFrames = AtomicInteger()
    var runIndex by mutableIntStateOf(0)
    val rows = ArrayList<String>()
    rows += "model,api,run,capture,identical,diffPixels,maxDiff,controlIdentical,controlDiffPixels,controlMaxDiff"
    val summary = ArrayList<String>()
    val thread = HandlerThread("pixelcopy").apply { start() }
    val handler = Handler(thread.looper)
    ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
      lateinit var window: Window
      scenario.onActivity { a ->
        a.showOverLockScreen()
        window = a.window
        a.setContentView(ComposeView(a).apply { setContent {
          val r = runs[runIndex]
          val transition = rememberInfiniteTransition(label = "radius")
          val radius = transition.animateFloat(
            2f,
            40f,
            infiniteRepeatable(tween(1300, easing = LinearEasing), RepeatMode.Reverse),
            label = "r",
          )
          val cellDp = ScreenCases.animCell()
          // New nodes per run, so the per-run counters start at 0.
          key(runIndex) { ScreenCases.CellStack {
            // Compose, Obvid, and Compose again (control: Compose against itself in the same frame).
            for ((index, impl) in listOf(Impl.Compose, Impl.Obvid, Impl.Compose).withIndex()) {
              val mod =
                ScreenCases.animShadow(impl, r, { radius.value }) { if (index == 1) drawnFrames.incrementAndGet() }
              val target = when (index) { 0 -> boundsCompose; 1 -> boundsObvid; else -> boundsControl }
              Cell(Modifier.onGloballyPositioned { target.set(it.boundsInWindow()) }, cellDp, 120.dp, mod)
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
            val dir = File(outputDirs(InstrumentationRegistry.getInstrumentation().targetContext, RESULTS_DIR).last(), "race")
            ScreenCases.savePng(dir, "${r.name}_${i}_compose.png", w, h, pa)
            ScreenCases.savePng(dir, "${r.name}_${i}_obvid.png", w, h, pb)
            ScreenCases.savePng(dir, "${r.name}_${i}_control.png", w, h, pc)
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
        val (entries, allocations, builds) =
          mainStore { s ->
            val e = s.entriesForTest()
            Triple(e.size, e.map { it.bitmaps.allocationCount }, e.map { it.buildCount })
          }
        summary +=
          "${r.name}: identical $identical / $copies copies (of $CAPTURES), control (Compose vs Compose) identical " +
            "$controlIdentical / $copies, block reads during capture $frames, " +
            "store entries $entries, bitmap allocations $allocations, " +
            "builds $builds"
      }
    }
    thread.quitSafely()
    summary += "API ${Build.VERSION.SDK_INT}, ${Build.MODEL}"
    summary.forEach { Log.i(TAG, it) }
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    writeResults(context, "race.csv", rows, RESULTS_DIR)
    writeResults(context, "race-summary.txt", summary, RESULTS_DIR)
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
    writeResults(context, "race-summary.txt", summary + verdict, RESULTS_DIR)
    assertTrue(verdict, parsed.size > runs.size * CAPTURES / 2)
    assertTrue(verdict, obvidMax <= maxOf(1, controlMax))
    assertTrue(verdict, obvidMismatches <= 2 * controlMismatches + 5)
  }

  /**
   * Below the target Skia: the Obvid cell above its twin. Checked per copy: Obvid vs twin within the capture noise
   * ([NOISE_MAX_DIFF]). Checked per cell (liveness, [Liveness]): a bitmap stale the same way in both cells (a texture
   * cache missing the in-place rewrite) would still compare equal, so each cell's pixels must change whenever the
   * radius it drew moved by [LIVENESS_STEP_DP]. The radii are read on the main thread right before each copy request.
   */
  private fun againstTwin() {
    val runs =
      ScreenCases.animRuns(
        "morph-color-block", "mixed-color-block", "rrect16-color-shadow", "morph-brush-block", "rrect16-brush-shadow",
      )
    val twins = runs.map { ScreenCases.AnimRun(it.name, TwinShape(it.shape), it.block, it.brush) }
    val boundsObvid = AtomicReference<Rect>()
    val boundsTwin = AtomicReference<Rect>()
    // The radius (dp, float bits) each cell last read for drawing: in the draw block, or in composition for the
    // `Shadow` overload.
    val radiusObvid = AtomicInteger()
    val radiusTwin = AtomicInteger()
    val drawnFrames = AtomicInteger()
    var runIndex by mutableIntStateOf(0)
    val rows = ArrayList<String>()
    rows += "model,api,run,capture,identical,diffPixels,maxDiff,obvidRadiusDp,twinRadiusDp"
    val summary = ArrayList<String>()
    var copiesTotal = 0
    var mismatches = 0
    var maxDiff = 0
    var stale = 0
    val thread = HandlerThread("pixelcopy").apply { start() }
    val handler = Handler(thread.looper)
    ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
      lateinit var window: Window
      scenario.onActivity { a ->
        a.showOverLockScreen()
        window = a.window
        a.setContentView(ComposeView(a).apply { setContent {
          val transition = rememberInfiniteTransition(label = "radius")
          val radius = transition.animateFloat(
            2f,
            40f,
            infiniteRepeatable(tween(1300, easing = LinearEasing), RepeatMode.Reverse),
            label = "r",
          )
          val cellDp = ScreenCases.animCell()
          // New nodes per run, so the per-run counters start at 0.
          key(runIndex) { Column {
            val obvid =
              ScreenCases.animShadow(Impl.Obvid, runs[runIndex], { radius.value.also { radiusObvid.set(it.toBits()) } }) {
                drawnFrames.incrementAndGet()
              }
            Cell(Modifier.onGloballyPositioned { boundsObvid.set(it.boundsInWindow()) }, cellDp, 120.dp, obvid)
            val twin =
              ScreenCases.animShadow(Impl.Obvid, twins[runIndex], { radius.value.also { radiusTwin.set(it.toBits()) } })
            Cell(Modifier.onGloballyPositioned { boundsTwin.set(it.boundsInWindow()) }, cellDp, 120.dp, twin)
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
        repeat(CAPTURES) { i ->
          val full = Bitmap.createBitmap(window.decorView.width, window.decorView.height, Bitmap.Config.ARGB_8888)
          val latch = CountDownLatch(1)
          var result = -1
          var ro = 0f
          var rt = 0f
          InstrumentationRegistry.getInstrumentation().runOnMainSync {
            ro = Float.fromBits(radiusObvid.get())
            rt = Float.fromBits(radiusTwin.get())
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
            val dir = File(outputDirs(InstrumentationRegistry.getInstrumentation().targetContext, RESULTS_DIR).last(), "race")
            ScreenCases.savePng(dir, "${r.name}_${i}_obvid.png", w, h, pb)
            ScreenCases.savePng(dir, "${r.name}_${i}_twin.png", w, h, pt)
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
            "builds $builds; liveness (radius moved >= $LIVENESS_STEP_DP dp): Obvid ${livenessObvid.stale} unchanged " +
            "of ${livenessObvid.checks}, twin ${livenessTwin.stale} unchanged of ${livenessTwin.checks}"
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
    writeResults(context, "race.csv", rows, RESULTS_DIR)
    writeResults(context, "race-summary.txt", summary, RESULTS_DIR)
    assertTrue(verdict, copiesTotal > runs.size * CAPTURES / 2)
    assertTrue(verdict, maxDiff <= NOISE_MAX_DIFF)
    assertEquals(verdict, 0, stale)
  }

  /**
   * One cell's liveness: the reference copy and the radius read at its request. When a copy's radius differs from the
   * reference's by at least [LIVENESS_STEP_DP], its pixels must differ ([stale] counts those that do not) and it
   * becomes the reference.
   */
  private class Liveness {
    private var radius = 0f
    private var pixels: IntArray? = null
    var checks = 0
      private set
    var stale = 0
      private set

    fun next(r: Float, px: IntArray) {
      val ref = pixels
      if (ref != null && abs(r - radius) < LIVENESS_STEP_DP) return
      if (ref != null) {
        checks++
        if (ref.contentEquals(px)) stale++
      }
      radius = r
      pixels = px
    }
  }

  /** [shape]'s outline under a shape equal only to itself: a node drawing it gets its own store entry. */
  private class TwinShape(private val shape: Shape) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline =
      shape.createOutline(size, layoutDirection, density)
  }

  private companion object {
    const val TAG = "FS-Race"
    const val CAPTURES = 150

    /**
     * Below the target: the largest channel difference allowed between the Obvid cell and its twin. Two identical
     * renders in one emulator capture can still differ by 1–2 in a channel (capture noise), so this is the noise, not a
     * tolerance for a different shadow.
     */
    const val NOISE_MAX_DIFF = 2

    /**
     * Below the target: the radius step (dp) after which a cell's pixels must have changed. A copy can show a frame
     * older or newer than the radius read at its request; the animation moves ≈ 29 dp/s (38 dp in 1.3 s), so with
     * copies within 100 ms of their request two copies 8 dp apart in the read radius still show radii ≥ 2 dp apart,
     * a visible change.
     */
    const val LIVENESS_STEP_DP = 8f
  }
}
