// Copyright 2026, Serhii Yaremych (desugar-64) and the Obvid project contributors
// SPDX-License-Identifier: Apache-2.0

package dev.serhiiyaremych.obvid.macrobenchmark

import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.filters.LargeTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * Frame cost of each sample scenario (see the sample's `Scenarios.kt`). A case named after the scenario
 * (`radius`) uses Compose `dropShadow`; `cpu_<scenario>` uses Obvid's (the sample's
 * `shadowImpl=cpu` extra). `demo:dropShadow` is the sample's trace section around each card's shadow
 * draw on the main thread, the same for both.
 *
 * Setup starts a fresh process on the scenario and waits 1 s, so startup and first-frame work stay
 * out of the trace: Macrobenchmark records Perfetto only around the measure block, and every metric
 * is read from that trace. `startupMode = null` because `StartupMode.COLD` kills the process
 * between setup and measure; setup kills it itself instead.
 * One case: `-e class 'dev.serhiiyaremych.obvid.macrobenchmark.ShadowScenarioBenchmark#animate[radius]'`
 * (or `animate[cpu_radius]`).
 */
@LargeTest
@RunWith(Parameterized::class)
class ShadowScenarioBenchmark(private val case: String) {
  private val scenario = case.removePrefix(CpuPrefix)
  private val impl = if (case.startsWith(CpuPrefix)) "cpu" else "compose"

  @get:Rule val benchmarkRule = MacrobenchmarkRule()

  @Test
  fun animate() =
    benchmarkRule.measureRepeated(
      packageName = TARGET_PACKAGE,
      metrics = shadowMetrics(),
      compilationMode = compilationMode(),
      startupMode = null,
      iterations = 5,
      setupBlock = {
        killProcess()
        startActivityAndWait {
          it.putExtra("scenario", scenario)
          it.putExtra("shadowImpl", impl)
          // Stop rebuilding (and growing Compose's cache) once the window is over; see the sample's MainActivity.
          it.putExtra("finishAfterMs", 12_000L)
        }
        Thread.sleep(1_000)
      },
    ) {
      // Let the animation run.
      Thread.sleep(5_000)
    }

  companion object {
    private const val CpuPrefix = "cpu_"

    @JvmStatic
    @Parameterized.Parameters(name = "{0}")
    fun scenarios(): List<String> {
      val scenarios = listOf(
        "radius", "size", "morph", "morph6", "morph6Sync", "offset12", "offset24", "offset24Lambda",
        "radius24", "radius24Sync", "color",
      )
      return scenarios + scenarios.map { CpuPrefix + it }
    }
  }
}
