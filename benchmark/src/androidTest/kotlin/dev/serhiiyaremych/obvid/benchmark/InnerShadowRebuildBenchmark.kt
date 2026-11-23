// Copyright 2026, Serhii Yaremych (desugar-64) and the Obvid project contributors
// SPDX-License-Identifier: Apache-2.0

package dev.serhiiyaremych.obvid.benchmark

import androidx.benchmark.junit4.BenchmarkRule
import androidx.benchmark.junit4.measureRepeated
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.shadow.InnerShadowPainter
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import dev.serhiiyaremych.obvid.InnerShadowDrawHook
import dev.serhiiyaremych.obvid.benchmark.DropShadowRebuildBenchmark.RadiusWalk
import dev.serhiiyaremych.obvid.fixtures.FrameShape
import dev.serhiiyaremych.obvid.fixtures.catalogShape
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * Cost of one Compose inner shadow draw, recorded into a [RenderNode] as a frame would record it. Every iteration
 * builds a new `Shadow` and an uncached `InnerShadowPainter` (as an animated shadow does per frame: Compose's renderer
 * cache is keyed by the whole `Shadow`), so every Compose draw allocates an Alpha8 bitmap and draws the blurred shape
 * into it. Reported allocations include the new `Shadow` and painter.
 * - `rebuild`: the radius walks through [RadiusWalk]'s cycle (offset (2, 4) dp), so neither Compose nor Skia's blur
 *   mask cache can hit.
 * - `offset`: the radius stays, the offset walks through [OffsetSteps] values 1/64 dp apart (a moving light).
 *   Skia's round-rect nine-patch cache is keyed by the small round rect and sigma, not the position, so Compose's
 *   blur can come from that cache here.
 * - `color`: the radius and offset stay, the color walks through [ColorSteps] values. Compose rebuilds (the color is
 *   in its key); Obvid's does not.
 * - `sprite`: `rebuild` at an offset that is a whole number of pixels on both axes at the device density
 *   ([wholePixelDp]): a path with spread is placed as a sprite (its intermediate is the blur's mask as is), where
 *   `rebuild`'s offset (2, 4) dp (5.25, 10.5 px at density 2.625) takes the filtered placement.
 * - `size`: the radius and offset stay, the square layout size walks down through [SizeWalk]'s whole pixels from the
 *   case's size (a size animation). Skia's nine-patch cache can serve Compose's round rect; Obvid's places its kept
 *   blurred small round rect again.
 *
 * Shapes: `round` is a simple round rect (16 dp; at 200 dp r24 Obvid's build takes Skia's nine-patch, at 48 dp r24 its
 * full mask), `circle` an oval (full mask), `mixed` (corners (16, 16, 4, 4) dp, as [DropShadowRebuildBenchmark]'s)
 * and `morph` (Cookie9Sided↔Pill at 0.5) are paths (Obvid's mask route, with or without spread), `rect` takes the Skia
 * route (Compose's own calls on Obvid's reused bitmap), and `frame` (two nested rect contours of one path,
 * [FrameShape]; Skia blurs it with its rect nine-patch) that nine-patch on Obvid's pipeline. One case:
 * `Class#draw[rebuild_round_200dp_r24_s0]`.
 *
 * Cases prefixed `cpu_` draw Obvid's `innerShadow` instead, through [InnerShadowDrawHook], which does what Obvid's
 * modifier node does in a frame: a new `Shadow` with the same walk each iteration rebuilds the node's sole store entry
 * in place (the offset is part of Obvid's key, the color is not), then Compose's draw call. Allocations include the new
 * `Shadow`.
 */
@RunWith(Parameterized::class)
class InnerShadowRebuildBenchmark(private val case: Case) {
  @get:Rule val benchmarkRule = BenchmarkRule()

  @Test
  fun draw() {
    val density = Density(InstrumentationRegistry.getInstrumentation().context.resources.displayMetrics.density)
    val sizePx = with(density) { case.size.dp.toPx() }
    val size = Size(sizePx, sizePx)
    val shape = createShape(case.shape)
    val radii = RadiusWalk(case.radius.toFloat(), density.density)
    val sizes = SizeWalk(sizePx)
    val spriteOffset =
      DpOffset(wholePixelDp(BaseOffset.x.value, density.density).dp, wholePixelDp(BaseOffset.y.value, density.density).dp)
    // An inner shadow draws inside the box.
    val recorder = ShadowRecorder(density, size, MarginPx)
    if (case.impl == Impl.Cpu) {
      val hook = InnerShadowDrawHook()
      var iteration = 0
      benchmarkRule.measureRepeated {
        if (case.mode == Mode.Size) recorder.drawSize = sizes[iteration]
        val shadow = shadowAt(iteration++, radii, spriteOffset)
        recorder.record { with(hook) { drawShadow(shape, shadow) } }
      }
      hook.release()
      return
    }
    var iteration = 0
    benchmarkRule.measureRepeated {
      val drawSize = if (case.mode == Mode.Size) sizes[iteration].also { recorder.drawSize = it } else size
      val painter = InnerShadowPainter(shape, shadowAt(iteration++, radii, spriteOffset))
      recorder.record { with(painter) { draw(drawSize) } }
    }
  }

  /** The `Shadow` of [iteration] for the case's mode ([spriteOffset]: `sprite`'s whole-pixel offset). */
  private fun shadowAt(iteration: Int, radii: RadiusWalk, spriteOffset: DpOffset): Shadow =
    when (case.mode) {
      Mode.Rebuild -> Shadow(radii[iteration].dp, Color.Black, case.spread.dp, BaseOffset, ShadowAlpha)
      Mode.Sprite -> Shadow(radii[iteration].dp, Color.Black, case.spread.dp, spriteOffset, ShadowAlpha)
      Mode.Size -> Shadow(case.radius.dp, Color.Black, case.spread.dp, BaseOffset, ShadowAlpha)
      Mode.Offset -> {
        val k = iteration % OffsetSteps
        Shadow(case.radius.dp, Color.Black, case.spread.dp, DpOffset((2f + k / 64f).dp, (4f - k / 128f).dp), ShadowAlpha)
      }
      Mode.Color -> {
        val k = iteration % ColorSteps
        Shadow(case.radius.dp, Color(k / (ColorSteps - 1f), 0.2f, 1f - k / (ColorSteps - 1f)), case.spread.dp, BaseOffset, ShadowAlpha)
      }
    }

  enum class Mode { Rebuild, Offset, Color, Sprite, Size }

  enum class Impl { Compose, Cpu }

  /**
   * One benchmark case; [toString] is the JUnit parameter name (`<mode>_<shape>_<size>dp_r<radius>_s<spread>`,
   * prefixed `cpu_` for Obvid's).
   */
  class Case(
    val mode: Mode,
    val shape: String,
    val size: Int,
    val radius: Int,
    val spread: Int,
    val impl: Impl = Impl.Compose,
  ) {
    override fun toString() =
      (if (impl == Impl.Cpu) "cpu_" else "") + "${mode.name.lowercase()}_${shape}_${size}dp_r${radius}_s$spread"
  }

  companion object {
    private const val MarginPx = 8f
    private const val ShadowAlpha = 0.4f
    private val BaseOffset = DpOffset(2.dp, 4.dp)
    private const val OffsetSteps = 256
    private const val ColorSteps = 256

    @JvmStatic
    @Parameterized.Parameters(name = "{0}")
    fun cases(): List<Case> {
      fun all(impl: Impl) =
        listOf(
          Case(Mode.Rebuild, "round", 200, 24, 0, impl),
          Case(Mode.Rebuild, "round", 48, 24, 0, impl),
          Case(Mode.Rebuild, "circle", 200, 24, 0, impl),
          Case(Mode.Rebuild, "mixed", 200, 24, 0, impl),
          Case(Mode.Rebuild, "morph", 200, 24, 0, impl),
          Case(Mode.Rebuild, "rect", 200, 24, 0, impl),
          Case(Mode.Rebuild, "frame", 200, 24, 0, impl),
          Case(Mode.Rebuild, "morph", 200, 24, 8, impl),
          Case(Mode.Offset, "round", 200, 24, 0, impl),
          Case(Mode.Color, "round", 200, 24, 0, impl),
          Case(Mode.Rebuild, "mixed", 200, 24, 8, impl),
          Case(Mode.Sprite, "morph", 200, 24, 8, impl),
          Case(Mode.Sprite, "mixed", 200, 24, 8, impl),
          Case(Mode.Size, "round", 200, 24, 0, impl),
        )
      return all(Impl.Compose) + all(Impl.Cpu)
    }

    /**
     * A dp value near [nearDp] that is a whole number of pixels at [density] exactly as Compose converts it
     * (`Dp.toPx`: dp × density in float), for `sprite`'s offset.
     */
    private fun wholePixelDp(nearDp: Float, density: Float): Float {
      val start = Math.round(nearDp * density)
      for (px in start until start + 64) {
        val dp = px / density
        if (dp * density == px.toFloat()) return dp
      }
      error("no whole-pixel offset near $nearDp dp at density $density")
    }

    /** [DropShadowRebuildBenchmark]'s `mixed`: non-uniform corners, drawn as a path. */
    private val MixedRebuildBench = RoundedCornerShape(16.dp, 16.dp, 4.dp, 4.dp)

    private fun createShape(name: String): Shape =
      when (name) {
        "round" -> catalogShape("rrect16")
        "mixed" -> MixedRebuildBench
        "morph" -> catalogShape("morph")
        "circle" -> catalogShape("circle")
        "rect" -> catalogShape("rect")
        "frame" -> FrameShape(evenOdd = false)
        else -> error("Unknown shape $name")
      }
  }
}
