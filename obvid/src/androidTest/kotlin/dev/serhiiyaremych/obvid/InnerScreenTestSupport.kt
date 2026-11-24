// Copyright 2026, Serhii Yaremych (desugar-64) and the Obvid project contributors
// SPDX-License-Identifier: Apache-2.0

package dev.serhiiyaremych.obvid

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.InnerShadowScope
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import dev.serhiiyaremych.obvid.ScreenCases.Impl
import dev.serhiiyaremych.obvid.ScreenCases.Paint
import dev.serhiiyaremych.obvid.ScreenCases.shadowOf
import dev.serhiiyaremych.obvid.fixtures.FrameShape
import dev.serhiiyaremych.obvid.fixtures.roundRectShapes
import dev.serhiiyaremych.obvid.fixtures.shapeCatalog
import androidx.compose.ui.draw.innerShadow as composeInnerShadow

/** The inner-shadow screen tests' cases and cell, on [ScreenCases]' paints, diffs and helpers. */
internal object InnerScreenCases {

  /** The subdirectory of the app's files dir the inner-shadow device tests write their results to ([outputDirs]). */
  const val RESULTS_DIR = "inner"

  /**
   * The subdirectory for the outputs of the path + spread route and nine-patch small-mask reuse tests (drop and inner),
   * kept apart from [RESULTS_DIR] so its files stay comparable across runs.
   */
  const val RESULTS_DIR2 = "spread-and-reuse"

  val shapes = shapeCatalog()

  /** The geometry cases' paint: black, with an offset (an inner shadow's offset is drawn into its bitmap). */
  val basePaint = Paint("black-offset", offset = DpOffset(3.dp, 5.dp))

  /** [ScreenCases.paintVariants] plus an offset larger than the blur and a source-atop blend. */
  val paintVariants =
    ScreenCases.paintVariants +
      listOf(
        Paint("offset-large", offset = DpOffset(20.dp, (-30).dp)),
        Paint("srcatop", color = Color(0xC0208040), blendMode = BlendMode.SrcAtop),
      )

  /**
   * 120 geometries (spread 0 / 8 / −8 dp) × [basePaint] × both overloads, then 12 paint variants on 10 geometries × both
   * overloads.
   */
  val cases: List<ScreenCases.Case> =
    buildList {
      for (block in listOf(false, true)) {
        for (s in shapes) for (size in listOf(48, 200)) for (r in listOf(1, 4, 24, 64)) for (sp in listOf(0, 8, -8)) {
          add(ScreenCases.Case(s, size, r, sp, basePaint, block))
        }
      }
      for (block in listOf(false, true)) for (p in paintVariants) for (s in shapes) {
        add(ScreenCases.Case(s, 200, 4, 8, p, block))
        add(ScreenCases.Case(s, 48, 24, 0, p, block))
      }
    }

  /**
   * The round-rect routes (nine-patch and full mask): corners 4 / 40 / 6.3 dp × 48 / 120 / 360 dp × radius 1 / 24 / 64
   * dp × spread 0 / 8 / −8 dp, [basePaint], `Shadow` overload: 81 cases.
   */
  val roundCases: List<ScreenCases.Case> =
    buildList {
      for (s in roundRectShapes().filter { it.name != "rrect16" }) {
        for (size in listOf(48, 120, 360)) for (r in listOf(1, 24, 64)) for (sp in listOf(0, 8, -8)) {
          add(ScreenCases.Case(s, size, r, sp, basePaint, false))
        }
      }
    }

  /**
   * A case of the banded-draw test: a shape, the geometry, the paint, the overload, whether it is drawn under a layer
   * that scales by 0.73 and rotates by 17° ([transformed]), and whether Obvid's draw must go in bands ([bands]; null:
   * not asserted, the hole's size decides).
   */
  class BandCase(
    val name: String,
    val shape: Shape,
    val sizeDp: Int,
    val radiusDp: Float,
    val spreadDp: Float,
    val paint: Paint,
    val block: Boolean,
    val bands: Boolean?,
    val transformed: Boolean,
  ) {
    val label
      get() =
        "$name ${sizeDp}dp r${MaskRouteGapCases.fmt(radiusDp)} s${MaskRouteGapCases.fmt(spreadDp)} ${paint.name} " +
          (if (block) "block" else "shadow") +
        if (transformed) " layer" else ""
  }

  /**
   * The banded-draw cases, each plain and under the scaling, rotating layer: at 200 dp, radius 4 dp, the base paint
   * (offset (3, 5) dp), a round rect on the nine-patch (spread 0 and 8 dp, both overloads), mixed corners on the mask
   * route (spread 0, both overloads; spread 8 dp, the path with spread), the morph (a concave path: no bands, its
   * renderer depends on the clip), the circle (the oval's full mask), a frame (its 0s lie in the frame's band: no
   * bands), a convex path with an inverse fill (it covers the whole clip: no bands; spread 0, the Skia route, and 8 dp,
   * the path with spread), and [MaskRouteGapCases.inverseRectShape] at 48 dp, radius 1 px, spread 0.5 px, no offset
   * (its intermediate leaves a large hole: without the inverse-fill check it would go in bands); then paints on the
   * round rect: a gradient brush, alpha 0.5,
   * a large offset, and blend modes with bands (`SrcAtop`, `DstOut`, `Screen`) and without (`Multiply`, which a
   * transparent source may change).
   */
  fun bandCases(): List<BandCase> {
    val byName = shapes.associateBy { it.name }
    fun shape(n: String) = byName.getValue(n).shape
    val paints = paintVariants.associateBy { it.name }
    val invTriangle = TriangleStarShape(curved = false, android.graphics.Path.FillType.INVERSE_WINDING)
    val invRect = MaskRouteGapCases.inverseRectShape().shape
    val density = InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density
    fun dpOf(px: Float) = MaskRouteGapCases.dpOf(px, density)
    val plain =
      listOf(
        BandCase("rrect16", shape("rrect16"), 200, 4f, 0f, basePaint, false, true, false),
        BandCase("rrect16", shape("rrect16"), 200, 4f, 0f, basePaint, true, true, false),
        BandCase("rrect16", shape("rrect16"), 200, 4f, 8f, basePaint, false, true, false),
        BandCase("mixed", shape("mixed"), 200, 4f, 0f, basePaint, false, true, false),
        BandCase("mixed", shape("mixed"), 200, 4f, 0f, basePaint, true, true, false),
        BandCase("mixed", shape("mixed"), 200, 4f, 8f, basePaint, false, null, false),
        BandCase("morph", shape("morph"), 200, 4f, 0f, basePaint, false, false, false),
        BandCase("morph", shape("morph"), 200, 4f, 0f, basePaint, true, false, false),
        BandCase("circle", shape("circle"), 200, 4f, 0f, basePaint, false, null, false),
        BandCase("frame", FrameShape(evenOdd = false), 200, 4f, 0f, basePaint, false, false, false),
        BandCase("triangleInvWinding", invTriangle, 200, 4f, 0f, basePaint, false, false, false),
        BandCase("triangleInvWinding", invTriangle, 200, 4f, 8f, basePaint, false, false, false),
        BandCase("rectInvWinding", invRect, 48, dpOf(1f), dpOf(0.5f), ScreenCases.basePaint, false, false, false),
        BandCase("rrect16", shape("rrect16"), 200, 4f, 0f, paints.getValue("brush-linear"), false, true, false),
        BandCase("mixed", shape("mixed"), 200, 4f, 0f, paints.getValue("brush-linear"), true, true, false),
        BandCase("rrect16", shape("rrect16"), 200, 4f, 0f, paints.getValue("alpha-0.5"), false, true, false),
        BandCase("rrect16", shape("rrect16"), 200, 4f, 0f, paints.getValue("offset-large"), false, null, false),
        BandCase("rrect16", shape("rrect16"), 200, 4f, 0f, paints.getValue("srcatop"), false, true, false),
        BandCase("rrect16", shape("rrect16"), 200, 4f, 0f, paints.getValue("dstout"), false, true, false),
        BandCase("rrect16", shape("rrect16"), 200, 4f, 0f, paints.getValue("screen"), false, true, false),
        BandCase("rrect16", shape("rrect16"), 200, 4f, 0f, paints.getValue("multiply"), false, false, false),
      )
    return plain +
      plain.map { BandCase(it.name, it.shape, it.sizeDp, it.radiusDp, it.spreadDp, it.paint, it.block, it.bands, true) }
  }

  /** The case that grows a node's bitmaps past every case before the node draws the case again. */
  fun bigCase(c: ScreenCases.Case) = ScreenCases.Case(shapes.first { it.name == "morph" }, 200, 64, 0, c.paint, c.block)

  /** The inner-shadow modifier for [impl], with the case's overload; Dp parameters converted in the block. */
  fun Modifier.caseInnerShadow(impl: Impl, shape: Shape, radiusDp: Dp, spreadDp: Dp, p: Paint, block: Boolean): Modifier =
    if (!block) {
      val shadow = shadowOf(radiusDp, spreadDp, p)
      if (impl == Impl.Compose) composeInnerShadow(shape, shadow) else innerShadow(shape, shadow)
    } else {
      val lambda: InnerShadowScope.() -> Unit = {
        radius = radiusDp.toPx()
        spread = spreadDp.toPx()
        this.offset = Offset(p.offset.x.toPx(), p.offset.y.toPx())
        if (p.brush != null) this.brush = p.brush else this.color = p.color
        this.alpha = p.alpha
        this.blendMode = p.blendMode
      }
      if (impl == Impl.Compose) composeInnerShadow(shape, lambda) else innerShadow(shape, lambda)
    }

  /**
   * What an animation run animates with its value v (2 → 40): the radius (v dp), or at radius 12 dp the offset, the
   * spread (0.4·v dp) or the content size ([contentSizeOf]).
   */
  enum class Animated {
    Radius,
    Offset,
    Spread,
    Size,
  }

  /**
   * One run of the animation tests: a catalog shape, the overload, a brush or a color, what moves, the spread, and the
   * offset while it does not move (null: (2, 3) dp).
   */
  class AnimRun(
    val name: String,
    val shape: Shape,
    val block: Boolean,
    val brush: Brush?,
    val animated: Animated,
    val spreadDp: Float,
    val offset: DpOffset? = null,
  )

  /**
   * The animation runs: radius on the morph and mixed paths (spread 0: the mask route), on `rrect16` (spread 4 dp: the
   * round-rect routes), with a color or a brush; the offset on `rrect16` and the morph; the radius on `mixed` with
   * spread 8 dp (a path with spread: the intermediate placed and blurred). New brushes per call.
   */
  fun animRuns(): List<AnimRun> {
    val byName = shapes.associateBy { it.name }
    fun shape(n: String) = byName.getValue(n).shape
    return listOf(
      AnimRun("morph-color-block", shape("morph"), true, null, Animated.Radius, 0f),
      AnimRun("rrect16-color-shadow", shape("rrect16"), false, null, Animated.Radius, 4f),
      AnimRun("mixed-color-block", shape("mixed"), true, null, Animated.Radius, 0f),
      AnimRun("morph-brush-block", shape("morph"), true, Brush.linearGradient(listOf(Color.Red, Color.Blue)), Animated.Radius, 0f),
      AnimRun(
        "rrect16-brush-shadow",
        shape("rrect16"),
        false,
        Brush.linearGradient(listOf(Color.Green, Color.Magenta)),
        Animated.Radius,
        4f,
      ),
      AnimRun("rrect16-offset-block", shape("rrect16"), true, null, Animated.Offset, 4f),
      AnimRun("morph-offset-shadow", shape("morph"), false, null, Animated.Offset, 0f),
      AnimRun("mixed-spread-shadow", shape("mixed"), false, null, Animated.Radius, 8f),
    )
  }

  /**
   * Runs on the routes that skip work between frames or place an intermediate: the spread on the morph (a path with
   * spread; the `Shadow` overload at a whole-pixel offset at density 2.625 (21 px, the sprite placement), the block
   * overload at a fractional one (filtered)), and the offset and the content size on `rrect16` (spread 4 dp: the
   * nine-patch, whose blurred small round rect is kept and placed again), both overloads each.
   */
  fun reuseAnimRuns(): List<AnimRun> {
    val byName = shapes.associateBy { it.name }
    fun shape(n: String) = byName.getValue(n).shape
    return listOf(
      AnimRun("morph-spread-shadow", shape("morph"), false, null, Animated.Spread, 0f, DpOffset(8.dp, (-8).dp)),
      AnimRun("morph-spread-block", shape("morph"), true, null, Animated.Spread, 0f, DpOffset(3.3.dp, (-2.7).dp)),
      AnimRun("rrect16-offset-shadow", shape("rrect16"), false, null, Animated.Offset, 4f),
      AnimRun("rrect16-offset-block", shape("rrect16"), true, null, Animated.Offset, 4f),
      AnimRun("rrect16-size-shadow", shape("rrect16"), false, null, Animated.Size, 4f),
      AnimRun("rrect16-size-block", shape("rrect16"), true, null, Animated.Size, 4f),
    )
  }

  /**
   * The content box of [run]: 90 → 158.4 dp with the animated value v for a size run, else 120 dp. [value] is read only
   * for a size run, so the other runs' block overload still reads it in the draw phase only.
   */
  fun contentSizeOf(run: AnimRun, value: () -> Float): Dp =
    if (run.animated == Animated.Size) (90f + 1.8f * (value() - 2f)).dp else 120.dp

  private val ANIM_COLOR = Color(0xC0302060)

  /**
   * The animation tests' inner shadow for [run] at the animated value [value] (2..40): radius v dp with offset (2, 3)
   * dp, or radius 12 dp with offset ((v − 21) · 0.5, (21 − v) · 0.3) dp, or radius 12 dp with spread 0.4·v dp or a
   * still geometry (a size run); the run's spread and offset where they do not move, its brush or a translucent
   * color. The block overload calls [value] (and [onBlock] first) inside its block, in the draw phase; the `Shadow`
   * overload calls it here, in composition.
   */
  @Composable
  fun animInnerShadow(impl: Impl, run: AnimRun, value: () -> Float, onBlock: () -> Unit = {}): Modifier {
    fun radiusOf(v: Float) = if (run.animated == Animated.Radius) v else 12f
    fun spreadOf(v: Float) = if (run.animated == Animated.Spread) 0.4f * v else run.spreadDp
    fun offsetOf(v: Float) =
      if (run.animated == Animated.Offset) DpOffset(((v - 21f) * 0.5f).dp, ((21f - v) * 0.3f).dp)
      else run.offset ?: DpOffset(2.dp, 3.dp)
    return if (run.block) {
      val block: InnerShadowScope.() -> Unit = {
        onBlock()
        val v = value()
        radius = radiusOf(v).dp.toPx()
        spread = spreadOf(v).dp.toPx()
        val o = offsetOf(v)
        offset = Offset(o.x.toPx(), o.y.toPx())
        if (run.brush != null) brush = run.brush else color = ANIM_COLOR
      }
      if (impl == Impl.Compose) Modifier.composeInnerShadow(run.shape, block) else Modifier.innerShadow(run.shape, block)
    } else {
      val v = value()
      val r = radiusOf(v).dp
      val o = offsetOf(v)
      val s =
        if (run.brush != null) Shadow(r, run.brush, spreadOf(v).dp, o)
        else Shadow(r, ANIM_COLOR, spreadOf(v).dp, o)
      if (impl == Impl.Compose) Modifier.composeInnerShadow(run.shape, s) else Modifier.innerShadow(run.shape, s)
    }
  }

  /**
   * A cell with a two-tone backdrop and a translucent content box drawn under the inner shadow (the shadow modifier
   * follows the box's background, as the official snippets order them).
   */
  @Composable
  fun Cell(modifier: Modifier, cell: Dp, contentSize: Dp, shadowModifier: Modifier) {
    Box(
      modifier.size(cell).drawBehind {
        drawRect(Color.White)
        drawRect(Color(0xFFFFD040), topLeft = Offset(size.width / 2f, 0f), size = Size(size.width / 2f, size.height))
        drawRect(Color(0xFF40A0FF), topLeft = Offset(0f, size.height * 0.7f), size = Size(size.width, size.height * 0.3f))
      }
    ) {
      Box(Modifier.align(Alignment.Center).size(contentSize).background(ScreenCases.CONTENT).then(shadowModifier))
    }
  }
}
