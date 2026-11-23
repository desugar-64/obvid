// Copyright 2026, Serhii Yaremych (desugar-64) and the Obvid project contributors
// SPDX-License-Identifier: Apache-2.0

package dev.serhiiyaremych.obvid.macrobenchmark

import android.graphics.Rect
import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.TraceSectionMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import kotlin.math.roundToInt

/**
 * Shadow redraws of cards that scroll out and back in: the sample's `reentry_unique` (every card its own height)
 * and `reentry_same` (one height) scenarios, a LazyColumn of 80 static cards (see the sample's `Reentry.kt`).
 * A case named after the scenario uses Compose `dropShadow`; `cpu_<scenario>` uses Obvid's (`shadowImpl=cpu`).
 *
 * Scroll script, the same for every case: starting at the top, [Cycles] times { 2 drags down, 2 drags up }. A
 * drag ([drag]) moves the finger [DragFraction] of the list's height (so a leg of 2 drags is 1.5 list heights, less
 * the touch slop of each drag) at a constant [DragSpeedDp] dp/s, one move every [StepMs] ms, then holds still for
 * [HoldMs] ms before lifting, so the lift velocity is zero and nothing flings: the list moves exactly with the
 * finger. Down and up legs are mirror images, so every cycle returns to the top and the cards of the first
 * screen leave and come back once per cycle, those of the next half screen come in and leave again.
 *
 * The events are injected through `UiAutomation.injectInputEvent` asynchronously on a fixed schedule (event
 * time = scheduled time), not with `UiDevice.swipe`, which waits for each step to be handled (~22 ms per step
 * in the stress grid, more when the main thread is busy): its pace would depend on the implementation being
 * measured. Distance and timing are therefore the same for both implementations; only a test thread that
 * misses its schedule changes the pace, never the distance. With a ~2220 px list at density 2.625 a drag
 * is ~634 dp in ~320 ms; with hold and [GapMs] the measured block lasts about 6.7 s.
 *
 * Metrics: frame timing and the `demo:dropShadow` sections (`Mode.Sum` also reports their count; a separate
 * `Mode.Count` metric would repeat the `demo:dropShadowCount` key). Hit and rebuild are told apart from each
 * section's duration in the traces (scripts/analyze_reentry.py).
 * One case: `-e class 'dev.serhiiyaremych.obvid.macrobenchmark.ReentryScrollBenchmark#scroll[cpu_reentry_unique]'`.
 */
@LargeTest
@RunWith(Parameterized::class)
class ReentryScrollBenchmark(private val case: String) {
  private val scenario = case.removePrefix(CpuPrefix)
  private val impl = if (case.startsWith(CpuPrefix)) "cpu" else "compose"

  @get:Rule val benchmarkRule = MacrobenchmarkRule()

  /** Read in setup: a lookup waits for accessibility idle, which must stay out of the measured block. */
  private lateinit var bounds: Rect

  @OptIn(ExperimentalMetricApi::class)
  @Test
  fun scroll() =
    benchmarkRule.measureRepeated(
      packageName = TARGET_PACKAGE,
      metrics = listOf(FrameTimingMetric(), TraceSectionMetric(SHADOW_SECTION, TraceSectionMetric.Mode.Sum)),
      compilationMode = compilationMode(),
      startupMode = null,
      iterations = 5,
      setupBlock = {
        killProcess()
        startActivityAndWait {
          it.putExtra("scenario", scenario)
          it.putExtra("shadowImpl", impl)
          // Ends the activity well after the measured block; see the sample's MainActivity.
          it.putExtra("finishAfterMs", 20_000L)
        }
        val list = checkNotNull(device.wait(Until.findObject(By.res(ListId)), 10_000)) { "list not found" }
        bounds = list.visibleBounds
        Thread.sleep(1_000)
      },
    ) {
      val density = InstrumentationRegistry.getInstrumentation().context.resources.displayMetrics.density
      val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
      val x = bounds.exactCenterX()
      val distance = (bounds.height() * DragFraction).roundToInt()
      val low = bounds.centerY() + distance / 2f
      val high = low - distance
      val durationMs = distance / (DragSpeedDp * density) * 1000f
      repeat(Cycles) {
        repeat(2) { automation.drag(x, low, high, durationMs) } // finger up: the list scrolls down
        repeat(2) { automation.drag(x, high, low, durationMs) }
      }
    }

  companion object {
    private const val CpuPrefix = "cpu_"
    private const val ListId = "reentry"

    /** Down-and-back cycles in the measured block. */
    private const val Cycles = 3

    /** Finger travel of one drag, as a fraction of the list's visible height. */
    private const val DragFraction = 0.75f

    /** Finger speed during a drag, dp/s. */
    private const val DragSpeedDp = 2_000f

    @JvmStatic
    @Parameterized.Parameters(name = "{0}")
    fun cases(): List<String> {
      val scenarios = listOf("reentry_unique", "reentry_same")
      return scenarios + scenarios.map { CpuPrefix + it }
    }
  }
}
