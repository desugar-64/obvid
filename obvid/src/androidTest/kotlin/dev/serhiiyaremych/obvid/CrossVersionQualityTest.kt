// Copyright 2026, Serhii Yaremych (desugar-64) and the Obvid project contributors
// SPDX-License-Identifier: Apache-2.0

package dev.serhiiyaremych.obvid

import android.content.Context
import android.content.pm.ActivityInfo
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.filters.SdkSuppress
import dev.serhiiyaremych.obvid.ScreenCases.Impl
import dev.serhiiyaremych.obvid.ScreenCases.showOverLockScreen
import dev.serhiiyaremych.obvid.fixtures.FrameShape
import dev.serhiiyaremych.obvid.fixtures.catalogShape
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import androidx.compose.ui.draw.dropShadow as composeDropShadow
import androidx.compose.ui.draw.innerShadow as composeInnerShadow

/**
 * How far Obvid's shadows are from the device's own Compose, per device: a measurement, not a check. Obvid's output is
 * Android 16–17 Skia's on every version; below that, Compose draws with the device's older Skia, so the two may differ.
 *
 * Each case ([cases]: a black shadow, alpha 1, radius 24 dp, on a 200 dp box centred in a white [CELL] dp cell, no
 * content drawn) is drawn by Compose's modifier, then by Obvid's, in the same cell (new modifier nodes, same position),
 * and each is captured with `captureToImage`. Per case, in `cross-version/` under the files dir and the additional
 * test output dir:
 * - `cross-version.csv`: one row per case: API, model, density, the case, the capture size, differing pixels (count
 *   and %), the largest absolute difference per channel (0–255), the mean absolute difference over R, G and B, and the
 *   PSNR over R, G and B (`inf` when identical).
 * - `<case>/compose.png`, `<case>/obvid.png` and `<case>/diff_x16.png`: the largest channel difference of each pixel
 *   times 16 (clamped) as white (identical) to red (16 levels or more).
 *
 * Fails on errors only (a capture whose size or position does not match its node or the other capture), except on
 * API 36–37, the Skia that Obvid reproduces, where every case must be identical. Captures are immune to the window
 * changing between them: the orientation is locked, each capture is checked against its node's laid-out size (so a cell
 * clipped by the window or caught mid-layout is captured again after the next idle), and a case whose two captures
 * differ in size or window position is drawn and captured again.
 */
// captureToImage needs API 26.
@SdkSuppress(minSdkVersion = 26)
@LargeTest
@RunWith(AndroidJUnit4::class)
class CrossVersionQualityTest {

  @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

  private class QualityCase(val name: String, val inner: Boolean, val shape: Shape, val spreadDp: Int) {
    fun modifier(impl: Impl): Modifier {
      val shadow = Shadow(RADIUS, Color.Black, spreadDp.dp)
      return when {
        inner && impl == Impl.Compose -> Modifier.composeInnerShadow(shape, shadow)
        inner -> Modifier.innerShadow(shape, shadow)
        impl == Impl.Compose -> Modifier.composeDropShadow(shape, shadow)
        else -> Modifier.dropShadow(shape, shadow)
      }
    }
  }

  private class Shown(val case: QualityCase, val impl: Impl, val token: Int)

  private var shown by mutableStateOf<Shown?>(null)
  private var token = 0

  /** A capture and where its node was: its pixels, its size and its bounds in the window. */
  private class Capture(val px: IntArray, val width: Int, val height: Int, val bounds: Rect)

  @Before
  fun setUp() {
    rule.runOnUiThread {
      rule.activity.showOverLockScreen()
      // Keeps the current rotation: a rotation between two captures would change the cell's size or position.
      rule.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LOCKED
    }
    NativeShadows.forceComposeFallback = false
    clearIdleShadows()
  }

  @After
  fun tearDown() {
    NativeShadows.forceComposeFallback = false
  }

  @Test
  fun measure() {
    check(NativeShadows.isAvailable) { "native library not available" }
    rule.setContent {
      // The cell stays one node for the whole test; only the shadowed box is new per draw. `size` takes the window's
      // constraints, so a window smaller than the cell gives a smaller (still fully visible) cell.
      Box(Modifier.testTag(TAG_CELL).size(CELL).background(Color.White)) {
        val s = shown
        if (s != null) {
          key(s.token) { Box(Modifier.align(Alignment.Center).size(CONTENT).then(s.case.modifier(s.impl))) }
        }
      }
    }
    val context = rule.activity
    val density = context.resources.displayMetrics.density
    val rows = arrayListOf(HEADER)
    val failures = ArrayList<String>()
    for (c in cases()) {
      val (ref, obvid) = captureBoth(c)
      val q = quality(ref.px, obvid.px)
      val row =
        listOf(
            Build.VERSION.SDK_INT,
            Build.MODEL.replace(',', ' '),
            density,
            c.name,
            if (c.inner) "inner" else "drop",
            CONTENT.value.toInt(),
            RADIUS.value.toInt(),
            c.spreadDp,
            ref.width,
            ref.height,
            q.pixels,
            q.differing,
            fmt(q.percent, 4),
            q.max[0],
            q.max[1],
            q.max[2],
            q.max[3],
            fmt(q.meanAbs, 5),
            formatPsnr(q.psnr),
          )
          .joinToString(",")
      rows += row
      Log.i(TAG, row)
      for (d in resultDirs(context)) {
        val dir = File(d, c.name)
        ScreenCases.savePng(dir, "compose.png", ref.width, ref.height, ref.px)
        ScreenCases.savePng(dir, "obvid.png", ref.width, ref.height, obvid.px)
        ScreenCases.savePng(dir, "diff_x16.png", ref.width, ref.height, heatmap(ref.px, obvid.px))
      }
      if (q.differing != 0) failures += "${c.name}: ${q.differing} px differ, max ${q.max.max()}, PSNR ${formatPsnr(q.psnr)} dB"
    }
    shown = null
    rule.waitForIdle()
    for (d in resultDirs(context)) {
      d.mkdirs()
      File(d, CSV).writeText(rows.joinToString("\n", postfix = "\n"))
    }
    // Android 16–17's Skia is the one that Obvid reproduces: there, Obvid's must equal the device's Compose. A newer
    // Skia is not assumed to be the same.
    if (Build.VERSION.SDK_INT in 36..37) {
      assertTrue(
        "API ${Build.VERSION.SDK_INT} has the Skia Obvid reproduces, so every case must equal this device's Compose; " +
          "differing: ${failures.joinToString("; ")}",
        failures.isEmpty(),
      )
    }
  }

  private fun cases(): List<QualityCase> =
    listOf(
      QualityCase("drop-rrect16", false, catalogShape("rrect16"), 0),
      QualityCase("drop-mixed", false, catalogShape("mixed"), 0),
      QualityCase("drop-morph", false, catalogShape("morph"), 0),
      QualityCase("drop-circle", false, catalogShape("circle"), 0),
      QualityCase("drop-rrect16-spread8", false, catalogShape("rrect16"), 8),
      QualityCase("drop-morph-spread8", false, catalogShape("morph"), 8),
      QualityCase("inner-rrect16", true, catalogShape("rrect16"), 0),
      QualityCase("inner-mixed", true, catalogShape("mixed"), 0),
      QualityCase("inner-frame", true, FrameShape(evenOdd = false), 0),
    )

  /**
   * Compose's and Obvid's capture of [c]: drawn and captured again (up to [CASE_TRIES] times) while the two differ in
   * size or window position, so that each pair is compared like for like.
   */
  private fun captureBoth(c: QualityCase): Pair<Capture, Capture> {
    var problem = ""
    repeat(CASE_TRIES) { attempt ->
      val ref = show(c, Impl.Compose)
      val obvid = show(c, Impl.Obvid)
      if (ref.width == obvid.width && ref.height == obvid.height && ref.bounds == obvid.bounds) return ref to obvid
      problem = "compose ${ref.width}x${ref.height} at ${ref.bounds}, obvid ${obvid.width}x${obvid.height} at ${obvid.bounds}"
      Log.w(TAG, "${c.name}, attempt ${attempt + 1}: captures differ in geometry ($problem); drawing again")
      SystemClock.sleep(RETRY_DELAY_MS)
    }
    error("${c.name}: captures still differ in geometry after $CASE_TRIES attempts: $problem; ${windowState()}")
  }

  /** Draws [c] with [impl] on new nodes, then captures the cell ([capture]). */
  private fun show(c: QualityCase, impl: Impl): Capture {
    shown = Shown(c, impl, ++token)
    rule.waitForIdle()
    return capture()
  }

  /**
   * The cell's capture, taken again (up to [CAPTURE_TRIES] times, after the next idle) until its size is the node's
   * laid-out size: `captureToImage` crops to the node's bounds clipped to the window, so a cell partly outside the
   * window, or caught while the window is resized, captures smaller.
   */
  private fun capture(): Capture {
    val node = rule.onNodeWithTag(TAG_CELL)
    var problem = ""
    repeat(CAPTURE_TRIES) {
      rule.waitForIdle()
      val semantics = node.fetchSemanticsNode()
      val size = semantics.size
      val bounds = semantics.boundsInWindow
      val b = node.captureToImage().asAndroidBitmap()
      if (b.width == size.width && b.height == size.height) return Capture(ScreenCases.pixelsOf(b), b.width, b.height, bounds)
      problem = "capture ${b.width}x${b.height}, node ${size.width}x${size.height} at $bounds"
      Log.w(TAG, "capture does not match its node ($problem); capturing again")
      SystemClock.sleep(RETRY_DELAY_MS)
    }
    error("cell capture never matched its node after $CAPTURE_TRIES attempts: $problem; ${windowState()}")
  }

  private fun windowState(): String =
    onMain {
      val decor = rule.activity.window.decorView
      val rotation =
        when (decor.display?.rotation) {
          Surface.ROTATION_0 -> 0
          Surface.ROTATION_90 -> 90
          Surface.ROTATION_180 -> 180
          Surface.ROTATION_270 -> 270
          else -> -1
        }
      "window ${decor.width}x${decor.height}, rotation $rotation, density ${rule.activity.resources.displayMetrics.density}"
    }

  /** Differences of two equally sized ARGB captures: per channel maxima (R, G, B, A), and over R, G and B the sums. */
  private class Quality(val pixels: Int, val differing: Int, val max: IntArray, val sumAbs: Long, val sse: Long) {
    val percent
      get() = 100.0 * differing / pixels

    val meanAbs
      get() = sumAbs.toDouble() / (3L * pixels)

    val psnr
      get() = psnr(sse, 3L * pixels)
  }

  private fun quality(a: IntArray, b: IntArray): Quality {
    check(a.size == b.size) { "capture sizes ${a.size} vs ${b.size}" }
    var differing = 0
    val maxima = IntArray(4)
    var sumAbs = 0L
    var sse = 0L
    for (i in a.indices) {
      val x = a[i]
      val y = b[i]
      if (x == y) continue
      differing++
      for ((ch, sh) in SHIFTS.withIndex()) {
        val d = abs(((x ushr sh) and 0xFF) - ((y ushr sh) and 0xFF))
        maxima[ch] = max(maxima[ch], d)
        // R, G and B only (channels 0..2) for the mean and the PSNR.
        if (ch < 3) {
          sumAbs += d
          sse += d * d
        }
      }
    }
    return Quality(a.size, differing, maxima, sumAbs, sse)
  }

  /** White where identical, toward red by the pixel's largest channel difference × 16 (clamped). */
  private fun heatmap(a: IntArray, b: IntArray): IntArray =
    IntArray(a.size) { i ->
      var d = 0
      for (sh in SHIFTS) d = max(d, abs(((a[i] ushr sh) and 0xFF) - ((b[i] ushr sh) and 0xFF)))
      val gb = 255 - min(255, d * 16)
      (0xFF shl 24) or (0xFF shl 16) or (gb shl 8) or gb
    }

  /** `cross-version/` under each of [outputDirs] (the files dir and, when set, the additional test output dir). */
  private fun resultDirs(context: Context): List<File> =
    outputDirs(context, RESULTS_DIR).map { if (it.name == RESULTS_DIR) it else File(it, RESULTS_DIR) }

  private fun fmt(v: Double, decimals: Int) = String.format(Locale.US, "%.${decimals}f", v)

  private companion object {
    const val TAG = "FS-CrossVersion"
    const val TAG_CELL = "cell"
    const val RESULTS_DIR = "cross-version"
    const val CSV = "cross-version.csv"
    val CELL = 320.dp
    val CONTENT = 200.dp
    val RADIUS = 24.dp
    const val CASE_TRIES = 3
    const val CAPTURE_TRIES = 5
    const val RETRY_DELAY_MS = 200L

    // R, G, B, A in a getPixels ARGB int.
    val SHIFTS = intArrayOf(16, 8, 0, 24)
    const val HEADER =
      "api,model,density,case,kind,sizeDp,radiusDp,spreadDp,widthPx,heightPx,pixels,diffPixels,diffPercent," +
        "maxR,maxG,maxB,maxA,meanAbsRgb,psnrRgbDb"
  }
}
