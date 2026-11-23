// Copyright 2026, Serhii Yaremych (desugar-64) and the Obvid project contributors
// SPDX-License-Identifier: Apache-2.0

package dev.serhiiyaremych.obvid.benchmark

import androidx.benchmark.junit4.BenchmarkRule
import androidx.benchmark.junit4.measureRepeated
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.shadow.DropShadowPainter
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.graphics.shadow.ShadowContext
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import dev.serhiiyaremych.obvid.DropShadowDrawHook
import dev.serhiiyaremych.obvid.fixtures.FrameShape
import dev.serhiiyaremych.obvid.fixtures.catalogShape
import kotlin.math.ceil
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * Cost of one Compose drop shadow draw, recorded into a [RenderNode] as a frame would record it.
 * - `rebuild`: one frame of an animated shadow. Each iteration builds a new `Shadow` and an uncached
 *   `DropShadowPainter` (as the app does per frame) with a radius not used in the last thousands of
 *   iterations (see [RadiusWalk]), so neither Compose nor Skia's blur mask cache can hit: every draw
 *   allocates an Alpha8 bitmap and blurs the outline. Reported allocations include the new `Shadow`
 *   and painter.
 * - `repeat`: the same uncached painter every iteration. Compose rebuilds its bitmap, but Skia's
 *   resource cache returns the blurred round rect nine-patch, so this is the Skia-cache reference.
 * - `cached`: a `ShadowContext` painter builds once, then only draws its bitmap (a static shadow).
 * - `size`: a size animation: a new `Shadow` and painter each iteration with the case's radius and spread, the square
 *   layout size walking down through [SizeWalk]'s whole pixels from the case's size. Skia's nine-patch cache can serve
 *   Compose's round rect; Obvid's places its kept blurred small round rect again.
 *
 * Shapes take each outline branch: `round` is a simple round rect (16 dp), `mixed` ([MixedRebuildBench],
 * non-uniform corners) and `morph` (Cookie9Sided↔Pill at 0.5) are drawn as paths, `circle` (`CircleShape`, rebuild
 * only: 48 / 200 dp r24 s0, 200 dp r24 s8) is a round rect too, an oval at spread 0 (Skia blurs its whole mask) and a
 * simple round rect at spread 8, and `frame` (rebuild only: 200 dp r24 s0) is two nested rect contours of one path
 * ([FrameShape]), which Skia blurs with its rect nine-patch. One case: `Class#draw[rebuild_round_200dp_r24_s0]`.
 *
 * Cases prefixed `cpu_` draw Obvid's `dropShadow` instead, through [DropShadowDrawHook], which does what Obvid's
 * modifier node does in a frame: `cpu_rebuild` gets a new `Shadow` with the same [RadiusWalk] radius each iteration, so
 * the node's sole store entry is rebuilt in place (paths through the mask and Obvid's NEON blur; round rects through
 * Skia's nine-patch or full-mask route on Obvid's pipeline; the frame through Skia's rect nine-patch on Obvid's
 * pipeline), then drawn with Compose's draw call; `cpu_cached` keeps one `Shadow`, so after the first build it only
 * draws. Allocations include the new `Shadow`.
 */
@RunWith(Parameterized::class)
class DropShadowRebuildBenchmark(private val case: Case) {
  @get:Rule val benchmarkRule = BenchmarkRule()

  @Test
  fun draw() {
    val density = Density(InstrumentationRegistry.getInstrumentation().context.resources.displayMetrics.density)
    val sizePx = with(density) { case.size.dp.toPx() }
    val size = Size(sizePx, sizePx)
    val shape = createShape(case.shape)
    val shadow = Shadow(radius = case.radius.dp, spread = case.spread.dp, alpha = 0.4f)
    val radii = RadiusWalk(case.radius, density.density)
    // The shadow bitmap reaches radius + spread past the box on every side.
    val pad = with(density) { (case.radius + case.spread).dp.toPx() } + MarginPx
    val recorder = ShadowRecorder(density, size, pad)
    val sizes = SizeWalk(sizePx)
    if (case.impl == Impl.Cpu) return drawCpu(recorder, radii, sizes, shape, shadow)
    if (case.mode == Mode.Size) {
      var iteration = 0
      benchmarkRule.measureRepeated {
        val drawSize = sizes[iteration++]
        recorder.drawSize = drawSize
        val painter = DropShadowPainter(shape, Shadow(radius = case.radius.dp, spread = case.spread.dp, alpha = 0.4f))
        recorder.record { with(painter) { draw(drawSize) } }
      }
      return
    }
    val fixedPainter: Painter? =
      when (case.mode) {
        Mode.Repeat -> DropShadowPainter(shape, shadow)
        Mode.Cached -> ShadowContext().createDropShadowPainter(shape, shadow)
        Mode.Rebuild, Mode.Size -> null
      }
    var iteration = 0
    benchmarkRule.measureRepeated {
      val painter =
        fixedPainter
          ?: DropShadowPainter(shape, Shadow(radius = radii[iteration++].dp, spread = case.spread.dp, alpha = 0.4f))
      recorder.record { with(painter) { draw(size) } }
    }
  }

  /** Obvid's `dropShadow`: same recording as Compose's cases, the node's draw through [DropShadowDrawHook]. */
  private fun drawCpu(recorder: ShadowRecorder, radii: RadiusWalk, sizes: SizeWalk, shape: Shape, fixedShadow: Shadow) {
    val hook = DropShadowDrawHook()
    var iteration = 0
    if (case.mode == Mode.Size) {
      benchmarkRule.measureRepeated {
        recorder.drawSize = sizes[iteration++]
        val shadow = Shadow(radius = case.radius.dp, spread = case.spread.dp, alpha = 0.4f)
        recorder.record { with(hook) { drawShadow(shape, shadow) } }
      }
      hook.release()
      return
    }
    benchmarkRule.measureRepeated {
      val shadow =
        if (case.mode == Mode.Cached) fixedShadow
        else Shadow(radius = radii[iteration++].dp, spread = case.spread.dp, alpha = 0.4f)
      recorder.record { with(hook) { drawShadow(shape, shadow) } }
    }
    hook.release()
  }

  enum class Mode { Rebuild, Repeat, Cached, Size }

  enum class Impl { Compose, Cpu }

  /**
   * One benchmark case; [toString] is the JUnit parameter name (`<mode>_<shape>_<size>dp_r<radius>_s<spread>`,
   * prefixed `cpu_` for Obvid's; a fractional radius is written with `p` for the point, `r0p5` for 0.5 dp).
   */
  class Case(
    val mode: Mode,
    val shape: String,
    val size: Int,
    val radius: Float,
    val spread: Int,
    val impl: Impl = Impl.Compose,
  ) {
    override fun toString(): String {
      val r = if (radius % 1f == 0f) radius.toInt().toString() else radius.toString().replace('.', 'p')
      return (if (impl == Impl.Cpu) "cpu_" else "") + "${mode.name.lowercase()}_${shape}_${size}dp_r${r}_s$spread"
    }
  }

  /**
   * Radii (dp) for the `rebuild` mode: a cycle of distinct values just below [baseDp].
   *
   * Skia keys its blur mask cache on the exact float sigma (`0.57735·radiusPx + 0.5`), so consecutive
   * radii must give distinct sigmas: the step is a whole number of dp ulps (so every value is exact)
   * worth at least 3 sigma ulps, which leaves room for Skia rounding the sigma differently (FMA).
   * The cycle spans at most [WindowPx] below the base: the radius drifts by under 0.2 px and, since
   * the base outsets here are whole or half pixels, the bitmap's ceil'd size never changes. A value
   * comes back only after the whole cycle (thousands of iterations, the fewest at r64), far more
   * masks than Skia's LRU resource cache holds at these sizes (tens to about a thousand), so it has
   * been evicted by then.
   */
  internal class RadiusWalk(private val baseDp: Float, density: Float) {
    private val stepDp: Double
    val count: Int

    init {
      val dpUlp = Math.ulp(baseDp).toDouble()
      val sigmaUlp = Math.ulp(SigmaScale * baseDp * density + 0.5f).toDouble()
      val ulpsPerStep = ceil(3 * sigmaUlp / (SigmaScale * density * dpUlp))
      stepDp = ulpsPerStep * dpUlp
      count = (WindowPx / density / stepDp).toInt()
      check(count >= MinCycle) { "Radius cycle of $count is too short to outlive Skia's cache" }
    }

    operator fun get(iteration: Int): Float = (baseDp - (iteration % count) * stepDp).toFloat()
  }

  companion object {
    private const val MarginPx = 8f
    private const val SigmaScale = 0.57735f // Skia's BlurMaskFilter radius-to-sigma factor.
    private const val WindowPx = 0.2
    private const val MinCycle = 2000

    @JvmStatic
    @Parameterized.Parameters(name = "{0}")
    fun cases(): List<Case> {
      val shapes = listOf("round", "mixed", "morph")
      fun rebuilds(impl: Impl) = shapes.flatMap { shape ->
        BenchGeometries.map { (size, radius, spread) -> Case(Mode.Rebuild, shape, size, radius.toFloat(), spread, impl) }
      } + CircleGeometries.map { (size, radius, spread) -> Case(Mode.Rebuild, "circle", size, radius.toFloat(), spread, impl) } +
        Case(Mode.Rebuild, "frame", size = 200, radius = 24f, spread = 0, impl) +
        smallBlurs(impl)
      fun cached(impl: Impl) = listOf("round", "morph").map { Case(Mode.Cached, it, size = 200, radius = 24f, spread = 0, impl) }
      fun sizes(impl: Impl) = listOf(Case(Mode.Size, "round", size = 200, radius = 24f, spread = 0, impl))
      return rebuilds(Impl.Compose) +
        shapes.map { Case(Mode.Repeat, it, size = 200, radius = 24f, spread = 0) } +
        cached(Impl.Compose) +
        sizes(Impl.Compose) +
        rebuilds(Impl.Cpu) +
        cached(Impl.Cpu) +
        sizes(Impl.Cpu)
    }

    /**
     * This benchmark's `mixed`: corners (16, 16, 4, 4) dp, not the catalog's (4, 16, 24, 8) dp that the device tests
     * use. Both are non-uniform round rects drawn as paths; this one is kept so that results stored under the same case
     * names stay comparable.
     */
    private val MixedRebuildBench = RoundedCornerShape(16.dp, 16.dp, 4.dp, 4.dp)

    /**
     * Rebuilds below σ = 2 (Skia's small blur, radius < 2.6 px): 0.5 and 0.9 dp, the low and high end of that range on
     * a 2.625 density (σ 1.26 and 1.86). `round` takes the nine-patch route (a small mask), the others blur the whole
     * mask.
     */
    private fun smallBlurs(impl: Impl) =
      listOf("round", "mixed", "morph", "circle").flatMap { shape ->
        listOf(0.5f, 0.9f).map { Case(Mode.Rebuild, shape, size = 200, radius = it, spread = 0, impl) }
      }

    /** The `circle` rebuild cases (size dp, radius dp, spread dp). */
    private val CircleGeometries = listOf(Triple(48, 24, 0), Triple(200, 24, 0), Triple(200, 24, 8))

    private fun createShape(name: String): Shape =
      when (name) {
        "round" -> catalogShape("rrect16")
        "mixed" -> MixedRebuildBench
        "morph" -> catalogShape("morph")
        "circle" -> catalogShape("circle")
        "frame" -> FrameShape(evenOdd = false)
        else -> error("Unknown shape $name")
      }
  }
}
