// Copyright 2026, Serhii Yaremych (desugar-64) and the Obvid project contributors
// SPDX-License-Identifier: Apache-2.0

package dev.serhiiyaremych.obvid.macrobenchmark

import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * Frame cost of the sample's stress grid (`scenario=grid`, see the sample's `StressGrid.kt`): ~600
 * index-seeded tiles, ~60 % static shadows, ~25 % animated, ~15 % none. `demo:dropShadow` is the
 * trace section around each tile's shadow draw on the main thread (in `shadows=none` it wraps the
 * same tiles without a shadow, so the trace overhead is the same in every variant).
 *
 * Parameters `<mode>_<shadows>`:
 * - shadows: `none` (control: identical tiles and tile motion, no shadows), `static` (all shadows,
 *   clock frozen, nothing animates), `mix` (animated shadows run).
 * - mode `fling`: gesture margin 1/5 of the display width, fling DOWN 3 times then UP once, each a
 *   full-length swipe of the grid at a fixed [FlingSpeedDp] dp/s that lifts at speed (so the grid
 *   flings), followed by a fixed 1.2 s sleep. The grid animates forever, so the test never waits for
 *   idle. `UiObject2.swipe` instead of `fling`: same gesture, but `fling` then blocks until a
 *   scroll-finished event or its timeout (~5 s per fling in a dry run, a nondeterministic wait);
 *   `UiDevice.swipe` is not used either, it injects a step every ~22 ms, far below the set speed.
 *   `idle`: no input, 5 s, starting at item [IdleFirstItem] (`first` extra): items 12-19 are none,
 *   morph, still, morph, still, still, still, lift, so the viewport holds per-frame rebuilds, static
 *   shadows and tile motion. At item 0 only one cached orbit tile animates, and `idle_none` drew no
 *   frames at all.
 *
 * Setup as in [ShadowScenarioBenchmark]: kill the process, start the grid, find it by the `grid`
 * resource id, settle 1 s. No `idle_static`: nothing changes, so it draws no frames and
 * `FrameTimingMetric` fails ("at least one result is necessary"); static shadows cost nothing at rest.
 * One case: `-e class 'dev.serhiiyaremych.obvid.macrobenchmark.StressGridBenchmark#stress[fling_mix]'`.
 */
@LargeTest
@RunWith(Parameterized::class)
class StressGridBenchmark(private val mode: String, private val shadows: String) {
  @get:Rule val benchmarkRule = MacrobenchmarkRule()

  /** Found in setup: a lookup waits for accessibility idle, which must stay out of the measured block. */
  private lateinit var grid: UiObject2

  @Test
  fun stress() =
    benchmarkRule.measureRepeated(
      packageName = TARGET_PACKAGE,
      metrics = shadowMetrics(),
      compilationMode = compilationMode(),
      startupMode = null,
      iterations = 5,
      setupBlock = {
        killProcess()
        startActivityAndWait {
          it.putExtra("scenario", "grid")
          it.putExtra("shadows", shadows)
          if (mode == "idle") it.putExtra("first", IdleFirstItem.toString())
          // Stop rebuilding (and growing Compose's cache) once the window is over; see the sample's MainActivity.
          it.putExtra("finishAfterMs", 20_000L)
        }
        grid = checkNotNull(device.wait(Until.findObject(By.res(GridId)), 10_000)) { "grid not found" }
        grid.setGestureMargin(device.displayWidth / 5)
        Thread.sleep(1_000)
      },
    ) {
      when (mode) {
        "fling" -> {
          val speed = (FlingSpeedDp * InstrumentationRegistry.getInstrumentation().context.resources.displayMetrics.density).toInt()
          repeat(3) { // fling DOWN: the finger moves up
            grid.swipe(Direction.UP, 1f, speed)
            Thread.sleep(1_200)
          }
          grid.swipe(Direction.DOWN, 1f, speed)
          Thread.sleep(1_200)
        }
        "idle" -> Thread.sleep(5_000)
        else -> error("Unknown mode '$mode'")
      }
    }

  companion object {
    private const val GridId = "grid"
    private const val IdleFirstItem = 12

    /** Fixed swipe speed, dp/s (UiAutomator's default fling is 7500). */
    private const val FlingSpeedDp = 5_000

    @JvmStatic
    @Parameterized.Parameters(name = "{0}_{1}")
    fun cases() =
      listOf("fling", "idle").flatMap { mode -> listOf("none", "static", "mix").map { arrayOf(mode, it) } }
        .filterNot { (mode, shadows) -> mode == "idle" && shadows == "static" }
  }
}
