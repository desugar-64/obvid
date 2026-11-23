// Copyright 2026, Serhii Yaremych (desugar-64) and the Obvid project contributors
// SPDX-License-Identifier: Apache-2.0

package dev.serhiiyaremych.obvid.macrobenchmark

import android.app.UiAutomation
import android.graphics.Rect
import android.os.SystemClock
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
 * The sample's tier scenarios (see the sample's `Tiers.kt`), each with Compose's shadows (case `<scenario>`), Obvid's
 * (`cpu_<scenario>`) and none (`none_<scenario>`: the same screen without shadow modifiers, the baseline of Compose's
 * own frame cost). Every shadow draw sits in a `demo:dropShadow` or `demo:innerShadow` section that holds the shadow
 * only; `none_` keeps the empty sections.
 *
 * Every iteration starts a fresh process on the scenario and settles 1 s in setup, outside the trace (Macrobenchmark
 * records Perfetto around the measure block only). Then, by scenario:
 * - reveal (`t0_single`, `t1_static`): one tap on the `tier` target shows the content (a warm-up row with a drop
 *   and an inner shadow of another geometry is already drawn, so class and native-code loading stay in setup), then
 *   [RevealIdleMs] idle. The frames of the trace are the reveal frames; each iteration repeats the first reveal.
 * - animate (`t2_menu`, `t3_gears`, `t4_field`, `t8_glow_spin`, `t8_glow_pulse`): [AnimateMs] of the running
 *   animation.
 * - scroll (`t5_list`): [ScrollCycles] times { 3 drags down, 2 drags up } over the feed, each drag [DragFraction] of
 *   its height at [DragSpeedDp] dp/s with a still finger before lifting (no fling; see [drag]); every cycle moves on
 *   by one drag, so new items keep coming in and seen ones return. About 5.6 s on a phone screen.
 * - nav (`t6_nav`): tap (home → detail), [NavDwellMs], tap (back), the gap, tap (detail again), [NavDwellMs]. The
 *   gap is the instrumentation argument `navGapMs` (default [DefaultNavGapMs]): the detail screen's shadows are released
 *   about 0.36 s after the back tap (the 350 ms transition and one frame), so the default comes back after ~1.1 s,
 *   inside the ~3 s idle window of Obvid's store; pass `-e navGapMs 4000` to come back after it.
 * - Discover (`t7_*`, the sample's `t7_discover` screen, see its `Discover.kt`; every target is read in setup, and the
 *   chip, play, bookmark and card targets belong to card 0, which every category draws at the same place):
 *   - `t7_reveal`: as the other reveals, on `t7_discover_reveal` (the whole feed appears at the tap).
 *   - `t7_scroll`: [DiscoverScrollCycles] times { 3 drags down, 2 drags up } as in `t5_list`, inside the part of the
 *     feed above the floating bottom bar. About 7 s on a phone screen.
 *   - `t7_interact`: [InteractRounds] rounds, one action every [InteractStepMs]: tap the next category chip (every
 *     card changes, async section `demo:chip`), press card 0's play button, toggle its bookmark. 6 s.
 *   - `t7_nav`: as `t6_nav`, with a tap on card 0 (home → detail) and the back key (detail → home).
 *
 * Taps and drags are injected on a fixed schedule ([tap], [drag]), so the pace never depends on the implementation.
 * Metrics: [tierMetrics]. Compilation: [compilationMode] (`-e compilation ignore` skips the reinstall). One case:
 * `-e class 'dev.serhiiyaremych.obvid.macrobenchmark.TierBenchmark#measure[cpu_t4_field]'`.
 */
@LargeTest
@RunWith(Parameterized::class)
class TierBenchmark(private val case: String) {
  private val impl = Impls.firstOrNull { case.startsWith(it + "_") } ?: "compose"
  private val scenario = if (impl == "compose") case else case.removePrefix(impl + "_")

  /** The sample's `scenario` extra. */
  private val sampleScenario = when {
    scenario == "t7_reveal" -> "t7_discover_reveal"
    scenario.startsWith("t7_") -> "t7_discover"
    else -> scenario
  }

  @get:Rule val benchmarkRule = MacrobenchmarkRule()

  /** Bounds of the scenario's tap targets and list, by resource id, read in setup: a lookup waits for accessibility idle. */
  private val bounds = mutableMapOf<String, Rect>()

  private val navGapMs = InstrumentationRegistry.getArguments().getString("navGapMs")?.toLong() ?: DefaultNavGapMs

  @Test
  fun measure() =
    benchmarkRule.measureRepeated(
      packageName = TARGET_PACKAGE,
      metrics = tierMetrics(),
      compilationMode = compilationMode(),
      startupMode = null,
      iterations = 5,
      setupBlock = {
        killProcess()
        startActivityAndWait {
          it.putExtra("scenario", sampleScenario)
          it.putExtra("shadowImpl", impl)
          // Stops animations (and Compose's cache growth) well after the measured block; see the sample's MainActivity.
          it.putExtra("finishAfterMs", 20_000L + navGapMs)
        }
        for (id in targetsOf(scenario)) {
          bounds[id] = checkNotNull(device.wait(Until.findObject(By.res(id)), 10_000)) { "$id not found" }.visibleBounds
        }
        Thread.sleep(1_000)
      },
    ) {
      val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
      when (scenario) {
        in RevealScenarios -> {
          val target = bounds.getValue("tier")
          automation.tap(target.exactCenterX(), target.exactCenterY())
          Thread.sleep(RevealIdleMs)
        }
        in AnimateScenarios -> Thread.sleep(AnimateMs)
        "t5_list" -> automation.scroll(bounds.getValue("tierList"), ScrollCycles)
        "t6_nav" -> {
          val target = bounds.getValue("nav")
          val x = target.exactCenterX()
          val y = target.exactCenterY()
          automation.tap(x, y)
          Thread.sleep(NavDwellMs)
          automation.tap(x, y)
          Thread.sleep(navGapMs)
          automation.tap(x, y)
          Thread.sleep(NavDwellMs)
        }
        "t7_scroll" -> {
          val feed = bounds.getValue("discoverFeed")
          val area = Rect(feed.left, feed.top, feed.right, minOf(feed.bottom, bounds.getValue("discoverNav").top))
          automation.scroll(area, DiscoverScrollCycles)
        }
        "t7_interact" -> {
          val play = bounds.getValue("discoverPlay")
          val bookmark = bounds.getValue("discoverBookmark")
          var next = SystemClock.uptimeMillis()
          fun step(target: Rect) {
            waitUntil(next)
            automation.tap(target.exactCenterX(), target.exactCenterY())
            next += InteractStepMs
          }
          for (round in 1..InteractRounds) {
            step(bounds.getValue("discoverChip${round % DiscoverChips}"))
            step(play)
            step(bookmark)
          }
          waitUntil(next)
        }
        "t7_nav" -> {
          // Left quarter of card 0: its photo, away from the bookmark and play buttons.
          val card = bounds.getValue("discoverCard")
          val x = card.left + card.width() / 4f
          val y = card.exactCenterY()
          automation.tap(x, y)
          Thread.sleep(NavDwellMs)
          automation.back()
          Thread.sleep(navGapMs)
          automation.tap(x, y)
          Thread.sleep(NavDwellMs)
        }
        else -> error("Unknown scenario '$scenario'")
      }
    }

  /** [cycles] times { 3 drags down, 2 drags up }, each [DragFraction] of [area]'s height around its center. */
  private fun UiAutomation.scroll(area: Rect, cycles: Int) {
    val density = InstrumentationRegistry.getInstrumentation().context.resources.displayMetrics.density
    val x = area.exactCenterX()
    val distance = (area.height() * DragFraction).roundToInt()
    val low = area.centerY() + distance / 2f
    val high = low - distance
    val durationMs = distance / (DragSpeedDp * density) * 1000f
    repeat(cycles) {
      repeat(3) { drag(x, low, high, durationMs) } // finger up: the list scrolls down
      repeat(2) { drag(x, high, low, durationMs) }
    }
  }

  /** Resource ids the measure block needs the bounds of. */
  private fun targetsOf(scenario: String): List<String> =
    when (scenario) {
      in RevealScenarios -> listOf("tier")
      "t5_list" -> listOf("tierList")
      "t6_nav" -> listOf("nav")
      "t7_scroll" -> listOf("discoverFeed", "discoverNav")
      "t7_interact" -> List(DiscoverChips) { "discoverChip$it" } + listOf("discoverPlay", "discoverBookmark")
      "t7_nav" -> listOf("discoverCard")
      else -> emptyList()
    }

  companion object {
    private val Impls = listOf("cpu", "none")
    private val RevealScenarios = listOf("t0_single", "t1_static", "t7_reveal")
    private val AnimateScenarios = listOf("t2_menu", "t3_gears", "t4_field", "t8_glow_spin", "t8_glow_pulse")

    /** Idle after a reveal tap, ms. */
    private const val RevealIdleMs = 1_000L

    /** Animation time per iteration, ms: two to three cycles of every animation. */
    private const val AnimateMs = 6_000L

    /** Down-and-on cycles of the feed scroll. */
    private const val ScrollCycles = 2

    /** Finger travel of one drag, as a fraction of the list's visible height. */
    private const val DragFraction = 0.75f

    /** Finger speed during a drag, dp/s. */
    private const val DragSpeedDp = 2_000f

    /** Time on a screen after a navigation tap, ms: the 350 ms transition and some idle. */
    private const val NavDwellMs = 1_200L

    /** Default time from the back tap to the next forward tap, ms. */
    private const val DefaultNavGapMs = 1_500L

    /** Down-and-on cycles of the Discover feed scroll. */
    private const val DiscoverScrollCycles = 3

    /** Rounds of chip, play and bookmark taps in `t7_interact`. */
    private const val InteractRounds = 4

    /** Time from one `t7_interact` tap to the next, ms: longer than each animation (at most 400 ms). */
    private const val InteractStepMs = 500L

    /** Category chips of the Discover screen. */
    private const val DiscoverChips = 4

    @JvmStatic
    @Parameterized.Parameters(name = "{0}")
    fun cases(): List<String> {
      val scenarios = RevealScenarios + AnimateScenarios + listOf("t5_list", "t6_nav", "t7_scroll", "t7_interact", "t7_nav")
      return scenarios + scenarios.map { "cpu_$it" } + scenarios.map { "none_$it" }
    }
  }
}
