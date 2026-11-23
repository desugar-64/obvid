// Copyright 2026, Serhii Yaremych (desugar-64) and the Obvid project contributors
// SPDX-License-Identifier: Apache-2.0

package dev.serhiiyaremych.obvid

import dev.serhiiyaremych.obvid.fixtures.FrameShape
import dev.serhiiyaremych.obvid.fixtures.ShapeCase

/**
 * The cases of the small-blur tests (drop: `ShadowBitmapBuilderTest.smallBlurMatchesCompose`, inner:
 * `InnerShadowBuilderTest.smallBlurMatchesCompose`): blur radii under 2.6 px, where `BlurMaskFilter`'s
 * σ = 0.57735·r + 0.5 is below 2 and Skia blurs with its direct Gaussian (`small_blur`, border = the `SkGaussFilter`
 * radius 1–4).
 */
internal object SmallBlurCases {
  /**
   * Radii in px, the same on every density: every filter radius (σ ≈ 0.56, 0.85, 1.19, 1.65 and 1.94: borders 1, 2,
   * 3, 4 and 4, the last near the top of the range), each away from the radii where the filter radius changes (about
   * 0.13, 0.95 and 1.79 px).
   */
  val RADII_PX = listOf(0.1f, 0.6f, 1.2f, 2f, 2.5f)

  /** Radii in dp, as the rebuild benchmarks use (σ 1.26 and 1.86 at density 2.625; 0.9 dp is σ ≥ 2 from density 2.9). */
  val RADII_DP = listOf(0.5f, 0.9f)

  /** The paths (mask route) and the round rects (`rrect16`: nine-patch or its full mask; `circle`: an oval's full mask). */
  val PATH_SHAPES = listOf("mixed", "morph")
  val ROUND_SHAPES = listOf("rrect16", "circle")
  val SHAPES = PATH_SHAPES + ROUND_SHAPES

  /** Canonical frames (two nested rect contours, [FrameShape]): Skia's frame nine-patch, or the mask route. */
  fun frames(): List<ShapeCase> =
    listOf(ShapeCase("frameCcw", FrameShape(evenOdd = false)), ShapeCase("frameEvenOdd", FrameShape(evenOdd = true)))

  /**
   * The box (dp) and spreads (dp) of the frames whose moved hole rounds in to just under, at and just over Skia's
   * frame nine-patch threshold `2m + 3` ([FrameRouteModel.holeFrame]; m is the border, so 5 to 11 px here).
   */
  const val HOLE_SIDE_DP = 100
  val HOLE_SPREADS_DP = listOf(0f, -4f)

  /** The round-rect routes of Obvid's pipeline. */
  val ROUND_ROUTES = setOf(ShadowBitmapBuilder.Route.NinePatch.name, ShadowBitmapBuilder.Route.MaskRoundRect.name)

  /** (label, radius in dp) at [density]: `px<r>` for [RADII_PX] (dp = px / density), `dp<r>` for [RADII_DP]. */
  fun radii(density: Float): List<Pair<String, Float>> =
    RADII_PX.map { "px$it" to it / density } + RADII_DP.map { "dp$it" to it }

  /** `BlurMaskFilter`'s σ for [radiusPx] (float, as Android computes it). */
  fun sigma(radiusPx: Float): Float = 0.57735f * radiusPx + 0.5f
}
