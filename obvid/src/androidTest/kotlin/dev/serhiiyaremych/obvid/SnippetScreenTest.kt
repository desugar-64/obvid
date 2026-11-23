// Copyright 2026, Serhii Yaremych (desugar-64) and the Obvid project contributors
// SPDX-License-Identifier: Apache-2.0

package dev.serhiiyaremych.obvid

import android.os.Build
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.TouchInjectionScope
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import dev.serhiiyaremych.obvid.ScreenCases.showOverLockScreen
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import dev.serhiiyaremych.obvid.snippets.compose.AnimatedColoredShadows as ComposeAnimatedColoredShadows
import dev.serhiiyaremych.obvid.snippets.compose.GradientBasedShadowAnimation as ComposeGradientBasedShadowAnimation
import dev.serhiiyaremych.obvid.snippets.compose.NeoBrutalShadows as ComposeNeoBrutalShadows
import dev.serhiiyaremych.obvid.snippets.compose.NeumorphicRaisedButton as ComposeNeumorphicRaisedButton
import dev.serhiiyaremych.obvid.snippets.compose.RealisticShadows as ComposeRealisticShadows
import dev.serhiiyaremych.obvid.snippets.compose.SimpleDropShadowUsage as ComposeSimpleDropShadowUsage
import dev.serhiiyaremych.obvid.snippets.compose.SimpleInnerShadowUsage as ComposeSimpleInnerShadowUsage
import dev.serhiiyaremych.obvid.snippets.obvid.AnimatedColoredShadows as ObvidAnimatedColoredShadows
import dev.serhiiyaremych.obvid.snippets.obvid.GradientBasedShadowAnimation as ObvidGradientBasedShadowAnimation
import dev.serhiiyaremych.obvid.snippets.obvid.NeoBrutalShadows as ObvidNeoBrutalShadows
import dev.serhiiyaremych.obvid.snippets.obvid.NeumorphicRaisedButton as ObvidNeumorphicRaisedButton
import dev.serhiiyaremych.obvid.snippets.obvid.RealisticShadows as ObvidRealisticShadows
import dev.serhiiyaremych.obvid.snippets.obvid.SimpleDropShadowUsage as ObvidSimpleDropShadowUsage
import dev.serhiiyaremych.obvid.snippets.obvid.SimpleInnerShadowUsage as ObvidSimpleInnerShadowUsage

/**
 * Google's Compose shadow snippets (android/snippets `graphics/Shadows.kt`) on the screen, in two copies that differ
 * only in the `dropShadow` and `innerShadow` imports: `snippets.compose` (Compose's) and `snippets.obvid`. Each snippet fills the same
 * [CELL] at the same position three times: Compose's copy, Obvid's, Compose's again (control). The test clock is paused
 * and advanced to fixed points, so all three runs see the same animation states; every point is a case, captured with
 * `captureToImage` and compared exactly, Obvid vs Compose and control vs Compose.
 *
 * A case passes when Obvid's is identical. When Obvid's differs and the control differs too, the case is noisy
 * (recorded, not failed); Obvid's differing while the control is identical fails. That is asserted on the target Skia
 * ([TargetSkia.isDevice]); elsewhere the summary adds the difference from the device's Compose (PSNR) and it does
 * not fail.
 *
 * Output (subdir `snippets` of the files dir, and the additional test output dir): `snippets-screen.csv`,
 * `snippets-screen-summary.txt`, and the Compose and Obvid PNG of every case (the control's too when it differs).
 */
// captureToImage needs API 26.
@SdkSuppress(minSdkVersion = 26)
@RunWith(AndroidJUnit4::class)
class SnippetScreenTest {

  @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

  private enum class Run {
    Compose,
    Obvid,
    Control,
  }

  /** A snippet: both copies, its preview's backdrop, and the script that takes it through its frame points. */
  private class Snippet(
    val name: String,
    val backdrop: Color,
    val compose: @Composable () -> Unit,
    val obvid: @Composable () -> Unit,
    val script: Driver.() -> Unit,
  )

  private class Shown(val content: @Composable () -> Unit, val backdrop: Color, val token: Int)

  /** One captured frame point. [note] is the snippet's visible state where it has one (the breathing text). */
  private class Frame(val point: String, val clockMs: Long, val note: String, val px: IntArray, val storeEntries: Int)

  private var shown by mutableStateOf<Shown?>(null)
  private var token = 0
  private var width = 0
  private var height = 0

  /** Drives one run: advances the paused clock relative to the last [mark] and captures the cell. */
  private inner class Driver(private val onFrame: (Frame) -> Unit) {
    private var t0 = rule.mainClock.currentTime

    fun mark() {
      t0 = rule.mainClock.currentTime
    }

    /** Advances the clock to [ms] after the mark (rounded up to whole frames, never back) and captures. */
    fun at(point: String, ms: Long, note: () -> String = { "" }) {
      val d = t0 + ms - rule.mainClock.currentTime
      if (d > 0) rule.mainClock.advanceTimeBy(d)
      rule.waitForIdle()
      val image = rule.onNodeWithTag(TAG_CELL).captureToImage()
      width = image.width
      height = image.height
      val px = ScreenCases.pixelsOf(image.asAndroidBitmap())
      val entries = mainStore { it.entriesForTest().size }
      onFrame(Frame(point, rule.mainClock.currentTime - t0, note(), px, entries))
    }

    /** Touch input on the snippet's clickable node, then [mark]. */
    fun touch(block: TouchInjectionScope.() -> Unit) {
      rule.onNode(hasClickAction()).performTouchInput(block)
      rule.waitForIdle()
      mark()
    }
  }

  private fun breathingText(): String {
    fun shows(text: String) = rule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    return when {
      shows("Inhale") -> "Inhale"
      shows("Exhale") -> "Exhale"
      else -> "none"
    }
  }

  private val snippets =
    listOf(
      Snippet("SimpleDropShadowUsage", Color.White, { ComposeSimpleDropShadowUsage() }, { ObvidSimpleDropShadowUsage() }) {
        at("static", 0)
      },
      Snippet("SimpleInnerShadowUsage", Color.White, { ComposeSimpleInnerShadowUsage() }, { ObvidSimpleInnerShadowUsage() }) {
        at("static", 0)
      },
      Snippet("RealisticShadows", Color(0xFF232323), { ComposeRealisticShadows() }, { ObvidRealisticShadows() }) {
        at("static", 0)
      },
      // The transition starts at rest on Inhaling (spread 10 dp); the effect flips to Exhaling after 5 s (10 → 2 dp
      // over 5 s), then back to Inhaling 5 s later (2 → 10 dp).
      Snippet(
        "GradientBasedShadowAnimation",
        Color.White,
        { ComposeGradientBasedShadowAnimation() },
        { ObvidGradientBasedShadowAnimation() },
      ) {
        for (ms in GRADIENT_POINTS_MS) at("t${ms}ms", ms) { breathingText() }
      },
      Snippet("NeumorphicRaisedButton", Color.White, { ComposeNeumorphicRaisedButton() }, { ObvidNeumorphicRaisedButton() }) {
        at("static", 0)
      },
      Snippet("AnimatedColoredShadows", Color.White, { ComposeAnimatedColoredShadows() }, { ObvidAnimatedColoredShadows() }) {
        at("released", 0)
        touch { down(center) }
        for (ms in longArrayOf(0, 100, 200, 400)) at("press${ms}ms", ms)
        touch { up() }
        for (ms in longArrayOf(0, 200, 400)) at("release${ms}ms", ms)
      },
      Snippet("NeoBrutalShadows", Color(0xFFFFCC00), { ComposeNeoBrutalShadows() }, { ObvidNeoBrutalShadows() }) {
        at("static", 0)
      },
    )

  @Before
  fun setUp() {
    rule.runOnUiThread { rule.activity.showOverLockScreen() }
    NativeShadows.forceComposeFallback = false
  }

  @After
  fun tearDown() {
    NativeShadows.forceComposeFallback = false
  }

  /** Recomposition on the paused clock: deliver the state change, then one frame. */
  private fun commitFrame() {
    rule.waitForIdle()
    rule.mainClock.advanceTimeByFrame()
    rule.waitForIdle()
  }

  private fun show(s: Snippet, run: Run) {
    shown = Shown(if (run == Run.Obvid) s.obvid else s.compose, s.backdrop, ++token)
    commitFrame()
    rule.onNodeWithTag(TAG_CELL).assertExists()
  }

  private fun hide() {
    shown = null
    commitFrame()
    rule.onNodeWithTag(TAG_CELL).assertDoesNotExist()
    val refCounts = mainStore { s -> s.entriesForTest().map { it.refCount } }
    check(refCounts.isEmpty()) { "store not empty: $refCounts" }
  }

  private fun runOnce(s: Snippet, run: Run, onFrame: (Frame) -> Unit) {
    show(s, run)
    Driver(onFrame).apply(s.script)
    hide()
  }

  @Test
  fun snippetsMatchCompose() {
    check(NativeShadows.isAvailable) { "native library not available" }
    rule.setContent {
      val s = shown
      if (s != null) {
        key(s.token) { Box(Modifier.testTag(TAG_CELL).size(CELL).background(s.backdrop)) { s.content() } }
      }
    }
    rule.waitForIdle()
    rule.mainClock.autoAdvance = false
    val context = rule.activity
    val dirs = outputDirs(context, SUBDIR)
    fun save(name: String, px: IntArray) {
      for (d in dirs) ScreenCases.savePng(d, name, width, height, px)
    }
    val rows = ArrayList<String>()
    rows += HEADER
    val summary = ArrayList<String>()
    val failing = ArrayList<String>()
    var allIdentical = 0
    var allNoisy = 0
    var allCases = 0
    val allTally = DiffTally()
    val tallies = ArrayList<String>()
    for (s in snippets) {
      val ref = ArrayList<Frame>()
      runOnce(s, Run.Compose) { f ->
        ref += f
        save("${s.name}_${f.point}_compose.png", f.px)
      }
      val obvid = ArrayList<Pair<Frame, ScreenCases.Diff>>()
      runOnce(s, Run.Obvid) { f ->
        val r = ref[obvid.size]
        check(f.point == r.point && f.clockMs == r.clockMs && f.note == r.note) {
          "${s.name}: Obvid run at ${f.point} ${f.clockMs} ms '${f.note}', Compose run at ${r.point} ${r.clockMs} ms '${r.note}'"
        }
        obvid += f to ScreenCases.diff(r.px, f.px)
        save("${s.name}_${f.point}_obvid.png", f.px)
      }
      val control = ArrayList<ScreenCases.Diff>()
      runOnce(s, Run.Control) { f ->
        val r = ref[control.size]
        check(f.point == r.point && f.clockMs == r.clockMs && f.note == r.note) {
          "${s.name}: control run at ${f.point} ${f.clockMs} ms '${f.note}', Compose run at ${r.point} ${r.clockMs} ms '${r.note}'"
        }
        val d = ScreenCases.diff(r.px, f.px)
        control += d
        if (!d.identical) save("${s.name}_${f.point}_control.png", f.px)
      }
      check(obvid.size == ref.size && control.size == ref.size) { "${s.name}: frame counts differ" }
      var identical = 0
      var noisy = 0
      val entries = obvid.map { it.first.storeEntries }
      val tally = DiffTally()
      for (i in ref.indices) {
        val (f, d) = obvid[i]
        val c = control[i]
        if (!TargetSkia.isDevice) {
          val dp = ScreenCases.diffPremul(ref[i].px, f.px)
          tally.add(dp)
          allTally.add(dp)
        }
        if (d.identical) {
          identical++
        } else {
          Log.w(TAG, "${s.name} ${f.point}: Obvid differs, ${d.pixels} px, max ${d.max}; control ${c.pixels} px, max ${c.max}")
          if (c.identical) failing += "${s.name} ${f.point}" else noisy++
        }
        rows +=
          listOf(
              Build.MODEL.replace(',', ' '),
              Build.VERSION.SDK_INT,
              s.name,
              f.point,
              f.clockMs,
              f.note,
              if (d.identical) 1 else 0,
              d.pixels,
              d.max,
              if (c.identical) 1 else 0,
              c.pixels,
              c.max,
              f.storeEntries,
            )
            .joinToString(",")
      }
      tallies += "  ${s.name}: $tally"
      allIdentical += identical
      allNoisy += noisy
      allCases += ref.size
      summary +=
        "${s.name}: Obvid identical $identical / ${ref.size}, noisy $noisy (control differs too), " +
          "control identical ${control.count { it.identical }} / ${ref.size}, " +
          "Obvid store entries ${entries.min()}..${entries.max()}"
    }
    summary +=
      "all: Obvid identical $allIdentical / $allCases, noisy $allNoisy, failing ${failing.size}" +
        if (failing.isEmpty()) "" else " (${failing.joinToString("; ")})"
    summary +=
      "cell ${width}x$height px, API ${Build.VERSION.SDK_INT}, ${Build.MODEL}, " +
        "density ${context.resources.displayMetrics.density}"
    if (!TargetSkia.isDevice) {
      summary += "${TargetSkia.offTargetLabel} (premultiplied ARGB):"
      summary += tallies
      summary += "  all: $allTally"
    }
    summary.forEach { Log.i(TAG, it) }
    writeResults(context, "snippets-screen.csv", rows, SUBDIR)
    writeResults(context, "snippets-screen-summary.txt", summary, SUBDIR)
    if (TargetSkia.isDevice) {
      assertTrue("Obvid differs while the control is identical: ${failing.joinToString("; ")}", failing.isEmpty())
    }
  }

  private companion object {
    const val TAG = "FS-Snippets"
    const val TAG_CELL = "snippet"
    const val SUBDIR = "snippets"

    /**
     * Square, so every snippet (all `fillMaxSize`) gets the same canvas; 400 dp fits a 411 dp phone width and
     * holds the snippets' 300 dp boxes with their shadows (RealisticShadows' 40 dp blur reaches past the cell edge).
     */
    val CELL = 400.dp

    /** At rest on 10 dp, then through the exhale (10 → 2 dp) and the following inhale (2 → 10 dp). */
    val GRADIENT_POINTS_MS = longArrayOf(0, 5250, 6250, 7500, 8750, 10000, 11250, 12500, 13750, 15000)

    const val HEADER =
      "model,api,snippet,point,clockMs,note,identical,diffPixels,maxDiff," +
        "controlIdentical,controlDiffPixels,controlMaxDiff,obvidStoreEntries"
  }
}
