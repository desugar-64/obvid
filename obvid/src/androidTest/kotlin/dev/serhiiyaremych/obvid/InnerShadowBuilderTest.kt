// Copyright 2026, Serhii Yaremych (desugar-64) and the Obvid project contributors
// SPDX-License-Identifier: Apache-2.0

package dev.serhiiyaremych.obvid

import android.os.Build
import android.os.Debug
import android.os.SystemClock
import android.util.Log
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import dev.serhiiyaremych.obvid.builder.ShadowBitmapBuilder
import dev.serhiiyaremych.obvid.builder.ShadowBitmaps
import dev.serhiiyaremych.obvid.fixtures.ComposeShadowReference
import dev.serhiiyaremych.obvid.fixtures.FrameShape
import dev.serhiiyaremych.obvid.fixtures.InnerShadowReference
import dev.serhiiyaremych.obvid.fixtures.ShapeCase
import dev.serhiiyaremych.obvid.fixtures.alphaOf
import dev.serhiiyaremych.obvid.fixtures.catalogShape
import dev.serhiiyaremych.obvid.fixtures.outlineParams
import dev.serhiiyaremych.obvid.fixtures.roundRectShapes
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Checks that [ShadowBitmapBuilder.buildInner] gives the bitmap of Compose 1.13.0's `InnerShadowRenderer`
 * ([InnerShadowReference], the line-for-line copy of its builds) exactly, on every route. Correctness only (no
 * timing). Identity is asserted on the target Skia ([TargetSkia.isDevice]); elsewhere the summaries add the difference
 * from the device's Compose (PSNR) and only the other checks are asserted. Radius, spread and offset are passed in px
 * to both, as the renderer computes them; layout sizes are whole px (`roundToPx`).
 *
 * Per build: the bitmap size against the reference's, the pixels (identical, max diff, differing pixels), whether the
 * generation id changed (HWUI keys its texture on it) and, for a build with `preserveCurrent`, whether the previous
 * bitmap kept its pixels. The route ([ShadowBitmapBuilder.route]) is recorded per case and counted per shape.
 *
 * - [builderMatchesCompose]: round rects (corners 4 / 16 / 40 / 6.3 dp, 49 %), `CircleShape`, paths (non-uniform
 *   corners, the morph, the morph placed off the origin with fractional bounds, partly outside the box) × layouts 48²,
 *   200², 200 × 48, 48 × 200 dp × radius 0 / 1 / 4 / 24 / 64 dp × spread 0 / 8 / −8 dp × [MAIN_OFFSETS]. `fresh`: a
 *   new builder per case; `reuse`: one builder and one bitmap pair through the same cases in a shuffled order (seed
 *   [SEED]), every other build with `preserveCurrent`, and every [DROP_EVERY]th case also a drop-shadow build of the
 *   same geometry with the same builder into its own bitmaps (against [ComposeShadowReference]). Plus Java allocations
 *   per rebuild once grown (logged).
 * - [edgeOffsetsMatchCompose]: the moved shape at distances around each bitmap edge (and two corners): overlapping by
 *   2.5 and 0.5 px, touching, 0.5 px off, then around the blur border b (`ObvidNative.builderBorder`), Skia's
 *   quick-reject reach 3σ (the blur's fast bounds, σ = 0.57735·r + 0.5) and the mask clip's 128 px cap. Round rects
 *   (nine-patch and full mask) with spread 0 / 8 / −8 dp, the circle, and paths with spread 0.
 * - [oneAxisEmptyInsetMatchesCompose]: oblong round rects whose spread empties the inset rect on the short axis only
 *   (or leaves under a pixel).
 * - [rectPathsAndFrames]: `Outline.Rectangle`, rects as paths (whole box and inset with fractional edges), corners
 *   (x, 0), and frames (two nested rect contours of one path, [FrameShape]), plus a sweep of frames at more sizes,
 *   small radii and seeded fractional offsets (`frameSweep`), which reach the bitmap's edges; frames whose moved hole
 *   is just below, at or above Skia's nine-patch threshold `2m + 3` on either axis (`frameHoles`,
 *   [FrameRouteModel.holeFrame]); frames with fractional edges, thin bands and a radius past the sigma cap
 *   (`frameEdges`). The rects take the Skia route (Skia blurs a rect with its `BlurRect`); the frames at radius > 0 and
 *   spread ≤ 0 the route of [FrameRouteModel] (Skia's frame nine-patch on Obvid's pipeline, or the mask route where
 *   Skia blurs the whole path); paths with spread > 0 and radius > 0 the mask route (their eroded intermediate is
 *   placed and blurred).
 * - [nestedRectEdgeCasesMatchCompose]: paths around the builder's nested-rects test ([NestedRectsEdgeShape]: same
 *   direction under non-zero fill, a collinear point, an edge one ulp off axis, disjoint rects, three contours, curves,
 *   no axis-aligned edge, a diagonal closing edge), fresh and reused, each against Compose with its route asserted
 *   ([FrameRouteModel] at spread ≤ 0); plus Java allocations per rebuild of a frame while its outline is kept.
 * - [smallBlurMatchesCompose]: blur radii under 2.6 px (σ < 2, Skia's small blur) on paths, round rects and frames
 *   (holes around Skia's frame nine-patch threshold included), the route asserted.
 * - [maskRouteGapsMatchCompose]: paths with an inverse fill ([MaskRouteGapCases]), whose mask Skia forms over the
 *   whole clip (the Skia route at spread ≤ 0), with Skia's decision per case, the route and no bands asserted.
 * - [pathSpreadOffsetsMatchCompose]: paths with spread > 0 and radius > 0 (the mask route: Compose's intermediate placed
 *   by the newest Skia's rule, then Obvid's blur) at offsets for every placement ([placement]: clipped out, sprite,
 *   filtered, filtered with an empty cut rect): whole pixels, around the 1/32 px sprite tolerance, halves, quarters,
 *   seeded random fractions, partly off each edge, at an edge, fully off; radius up to 64 dp (border > 128); plus
 *   one-axis-thin layouts and spreads that clear the whole path. Fresh and reused (as in [builderMatchesCompose]);
 *   counts per placement in the summary.
 *
 * Output (subdir [RESULTS_DIR] of the files dir and the additional test output dir): `inner1-builder*.csv` and
 * `inner1-builder*-summary.txt` per test; [pathSpreadOffsetsMatchCompose] writes `inner2-builder-spread.csv` (with a
 * placement column) and its summary, [nestedRectEdgeCasesMatchCompose] `frames-inner-edges.csv` and its summary, to
 * [InnerScreenCases.RESULTS_DIR2]. Logcat tag `FS-InnerBuilder`.
 */
@RunWith(AndroidJUnit4::class)
class InnerShadowBuilderTest {

  private val context = InstrumentationRegistry.getInstrumentation().targetContext
  private val density = Density(context)

  /** A layout box (whole px) with radius and spread in px, as `InnerShadowRenderer` computes them. */
  private inner class Geometry(val widthDp: Int, val heightDp: Int, val radiusDp: Float, val spreadDp: Float) {
    val size = with(density) { Size(widthDp.dp.roundToPx().toFloat(), heightDp.dp.roundToPx().toFloat()) }
    val radius = with(density) { radiusDp.dp.toPx() }
    val spread = with(density) { spreadDp.dp.toPx() }
    val border = if (radius > 0f) ObvidNative.builderBorder(radius) else 0

    /** Skia's blur fast-bounds outset (3σ), the reach of its quick reject. */
    val reach = if (radius > 0f) 3f * (SIGMA_SCALE * radius + 0.5f) else 0f

    /** [v] dp in px (`Dp.toPx`). */
    fun dpPx(v: Float): Float = with(density) { v.dp.toPx() }
  }

  private class Case(val shape: ShapeCase, val g: Geometry, val offset: String, val ox: Float, val oy: Float) {
    val key
      get() = "${shape.name}/${g.widthDp}x${g.heightDp}dp/r${fmt(g.radiusDp)}/s${fmt(g.spreadDp)}/$offset"
  }

  /** One build against the reference. */
  private class Outcome(
    val route: String,
    val w: Int,
    val h: Int,
    val diff: ScreenCases.Diff,
    val genChanged: Boolean,
    val previousUntouched: Boolean,
  ) {
    val identical
      get() = diff.identical
  }

  private class Row(val phase: String, val index: Int, val case: Case, val o: Outcome) {
    fun csv(): String =
      listOf<Any>(
          Build.MODEL.replace(',', ' '),
          Build.VERSION.SDK_INT,
          phase,
          index,
          case.shape.name,
          case.g.widthDp,
          case.g.heightDp,
          case.g.size.width,
          case.g.size.height,
          fmt(case.g.radiusDp),
          case.g.radius,
          case.g.border,
          fmt(case.g.spreadDp),
          case.g.spread,
          case.offset,
          case.ox,
          case.oy,
          o.route,
          o.w,
          o.h,
          bit(o.identical),
          o.diff.max,
          o.diff.pixels,
          bit(o.genChanged),
          bit(o.previousUntouched),
        )
        .joinToString(",")
  }

  // ---------------------------------------------------------------------------------------------------------------
  // Tests
  // ---------------------------------------------------------------------------------------------------------------

  @Test
  fun builderMatchesCompose() {
    val start = SystemClock.elapsedRealtime()
    val shapes =
      roundRectShapes() +
        ShapeCase("rrect49pct", RoundedCornerShape(percent = 49)) +
        listOf("circle", "mixed", "morph").map { ShapeCase(it, catalogShape(it)) } +
        ShapeCase("morph03", PlacedMorphShape(0.3f, 0.3f)) +
        ShapeCase("morphNeg", PlacedMorphShape(0.55f, 0.45f, negative = true))
    val cases = buildList {
      for (shape in shapes) for ((wDp, hDp) in MAIN_SIZES) for (r in RADII_DP) for (s in SPREADS_DP) {
        val g = Geometry(wDp, hDp, r, s)
        for ((name, at) in MAIN_OFFSETS) {
          val (ox, oy) = at(g)
          add(Case(shape, g, name, ox, oy))
        }
      }
    }
    val fresh = cases.mapIndexed { i, c -> ShadowBitmapBuilder().use { b -> Row("fresh", i, c, measure(b, ShadowBitmaps(), c, false)) } }
    val order = cases.shuffled(Random(SEED))
    var dropCases = 0
    var dropIdentical = 0
    var dropMax = 0
    val reuse = ShadowBitmapBuilder().use { b ->
      val target = ShadowBitmaps()
      val dropTarget = ShadowBitmaps()
      order.mapIndexed { i, c ->
        val row = Row("reuse", i, c, measure(b, target, c, preserve = i % 2 == 1))
        if (i % DROP_EVERY == 0) {
          val d = dropBuild(b, dropTarget, c)
          dropCases++
          if (d.identical) dropIdentical++
          dropMax = max(dropMax, d.max)
        }
        row
      }
    }
    val seconds = (SystemClock.elapsedRealtime() - start) / 1000.0
    val summary = ArrayList<String>()
    summary += summaryLines(listOf("fresh" to fresh, "reuse" to reuse))
    summary += "reuse: interleaved drop-shadow builds identical $dropIdentical / $dropCases, max diff $dropMax"
    summary += allocationCheck()
    summary += footer(seconds)
    summary += offTargetLines(listOf("fresh" to fresh, "reuse" to reuse))
    finish("inner1-builder", fresh + reuse, summary)

    val all = fresh + reuse
    assertChecks(all)
    if (TargetSkia.isDevice) assertEquals("interleaved drop builds", dropCases, dropIdentical)
    val routes = fresh.map { it.o.route }.toSet()
    for (r in listOf("NinePatch", "MaskRoundRect", "Mask", "Skia")) assertTrue("no $r case", r in routes)
    // Paths take the mask route whenever radius > 0, with or without spread, except a spread whose drawImageRect
    // matrix scales ([inexactRight]).
    for (row in all) {
      val c = row.case
      if (c.shape.name in PATH_SHAPES && c.g.radius > 0f) {
        val expected = if (c.g.spread > 0f && inexactRight(c)) "Skia" else "Mask"
        assertEquals("route of ${c.key}", expected, row.o.route)
      }
    }
  }

  @Test
  @LargeTest
  fun edgeOffsetsMatchCompose() {
    val start = SystemClock.elapsedRealtime()
    val roundShapes = listOf("rrect16", "rrect40").map { ShapeCase(it, if (it == "rrect16") catalogShape(it) else RoundedCornerShape(40.dp)) }
    val shapes =
      roundShapes.map { it to SPREADS_DP } +
        (ShapeCase("circle", catalogShape("circle")) to listOf(0f, 8f)) +
        listOf(
          ShapeCase("mixed", catalogShape("mixed")) to listOf(0f),
          ShapeCase("morph", catalogShape("morph")) to listOf(0f),
          ShapeCase("morph03", PlacedMorphShape(0.3f, 0.3f)) to listOf(0f),
        )
    val cases = buildList {
      for ((shape, spreads) in shapes) for (side in listOf(48, 200)) for (r in listOf(1f, 4f, 24f, 64f)) for (s in spreads) {
        val g = Geometry(side, side, r, s)
        for ((name, ox, oy) in edgeOffsets(shape, g)) add(Case(shape, g, name, ox, oy))
      }
    }
    val rows = cases.mapIndexed { i, c -> ShadowBitmapBuilder().use { b -> Row("edge", i, c, measure(b, ShadowBitmaps(), c, false)) } }
    val seconds = (SystemClock.elapsedRealtime() - start) / 1000.0
    val summary = ArrayList<String>()
    summary += summaryLines(listOf("edge" to rows))
    // Per distance band: which side of b and 3σ the shape is on.
    for ((band, group) in rows.groupBy { band(it.case.offset) }.toSortedMap()) {
      summary += "  band $band: identical ${group.count { it.o.identical }} / ${group.size}, max diff ${group.maxOf { it.o.diff.max }}; " +
        "routes ${routeCounts(group)}"
    }
    summary += footer(seconds)
    summary += offTargetLines(listOf("edge" to rows))
    finish("inner1-builder-edges", rows, summary)
    assertChecks(rows)
  }

  /**
   * Blur radii under 2.6 px (σ < 2: Skia's small blur, Obvid's NEON port on the whole-mask routes): the radii of
   * [SmallBlurCases] (px on every density, covering filter radii 1–4, and 0.5 / 0.9 dp as the rebuild benchmarks) ×
   * `mixed`, `morph` (the mask route, with spread through the placed intermediate), `rrect16` (the nine-patch, or its
   * full mask) and `circle` (an oval's full mask) × 48 / 200 dp × spread 0 / 8 / −8 dp × offsets zero / 4 dp / 3.25 px;
   * the frames of [SmallBlurCases.frames] × 48 / 200 dp × spread 0 / 8 / −8 dp, one of those offsets each in turn; and
   * frames on a 100 dp box whose hole, moved by the offset, rounds in to `2m + 3 − 1`, `2m + 3` or `2m + 3 + 1` px on
   * one axis ([FrameRouteModel.holeFrame]) at every radius × spread 0 / −4 dp. Fresh and reused as in
   * [builderMatchesCompose]. The checks of [builderMatchesCompose], the route (the mask route for the paths and the
   * frames with spread unless [inexactRight], a round-rect route of Obvid's for the round rects, [FrameRouteModel]'s
   * for the frames at spread ≤ 0), and that the px radii give σ < 2 with every border 1–4.
   */
  @Test
  fun smallBlurMatchesCompose() {
    val start = SystemClock.elapsedRealtime()
    val labels = HashMap<Case, String>()
    val radii = SmallBlurCases.radii(density.density)
    val offsets = MAIN_OFFSETS.filter { it.first in setOf("zero", "dp4", "px3.25") }
    val cases = buildList {
      for (name in SmallBlurCases.SHAPES) for (side in listOf(48, 200)) for ((label, r) in radii) {
        for (s in SPREADS_DP) {
          val g = Geometry(side, side, r, s)
          for ((offset, at) in offsets) {
            val (ox, oy) = at(g)
            add(Case(ShapeCase(name, catalogShape(name)), g, offset, ox, oy).also { labels[it] = label })
          }
        }
      }
      var n = 0
      for (side in listOf(48, 200)) for ((label, r) in radii) for (s in SPREADS_DP) for (frame in SmallBlurCases.frames()) {
        val g = Geometry(side, side, r, s)
        val (offset, at) = offsets[n++ % offsets.size]
        val (ox, oy) = at(g)
        add(Case(frame, g, offset, ox, oy).also { labels[it] = label })
      }
      n = 0
      for ((label, r) in radii) for (s in SmallBlurCases.HOLE_SPREADS_DP) {
        val g = Geometry(SmallBlurCases.HOLE_SIDE_DP, SmallBlurCases.HOLE_SIDE_DP, r, s)
        val threshold = 2 * g.border + 3
        for (axis in 0..1) for (k in -1..1) {
          val (offset, at) = offsets[n % offsets.size]
          val (ox, oy) = at(g)
          val frac = HOLE_FRACTIONS[n % HOLE_FRACTIONS.size]
          val shape = FrameRouteModel.holeFrame(g.size.width, threshold, axis, k, frac, ox, oy, evenOdd = n % 2 == 1) ?: continue
          val name = "hole${"xy"[axis]}${if (k < 0) "Below" else if (k > 0) "Above" else "At"}"
          add(Case(ShapeCase(name, shape), g, offset, ox, oy).also { labels[it] = label })
          n++
        }
      }
    }
    // The expected route: null for a round rect (either round-rect route of Obvid's).
    fun expected(c: Case): FrameRouteModel.Decision? =
      when (c.shape.name) {
        in SmallBlurCases.PATH_SHAPES ->
          FrameRouteModel.Decision(if (c.g.spread > 0f && inexactRight(c)) "Skia" else "Mask", "path")
        in SmallBlurCases.ROUND_SHAPES -> null
        else -> model(c, superset = true)
      }
    val fresh = cases.mapIndexed { i, c -> ShadowBitmapBuilder().use { b -> Row("fresh", i, c, measure(b, ShadowBitmaps(), c, false)) } }
    val reuse = ShadowBitmapBuilder().use { b ->
      val target = ShadowBitmaps()
      cases.shuffled(Random(SEED)).mapIndexed { i, c -> Row("reuse", i, c, measure(b, target, c, preserve = i % 2 == 1)) }
    }
    val all = fresh + reuse
    val seconds = (SystemClock.elapsedRealtime() - start) / 1000.0
    fun sigma(c: Case) = SmallBlurCases.sigma(c.g.radius)
    val summary = ArrayList<String>()
    summary += summaryLines(listOf("fresh" to fresh, "reuse" to reuse))
    for ((label, group) in all.groupBy { labels.getValue(it.case) }) {
      val g = group.first().case.g
      summary += "  radius $label (${g.radius} px, σ ${sigma(group.first().case)}, border ${g.border}): identical " +
        "${group.count { it.o.identical }} / ${group.size}, max diff ${group.maxOf { it.o.diff.max }}; routes ${routeCounts(group)}"
    }
    val decisions = all.associateWith { expected(it.case) }
    fun asExpected(row: Row) = decisions.getValue(row)?.let { it.route == row.o.route } ?: (row.o.route in SmallBlurCases.ROUND_ROUTES)
    for ((kind, group) in all.groupBy { decisions.getValue(it)?.kind ?: "roundRect" }.toSortedMap()) {
      summary += "  model $kind: identical ${group.count { it.o.identical }} / ${group.size}; routes ${routeCounts(group)}"
    }
    val holes = all.filter { (decisions.getValue(it)?.holeX ?: FrameRouteModel.NO_HOLE) != FrameRouteModel.NO_HOLE }
    for ((key, group) in holes.groupBy { checkNotNull(decisions.getValue(it)).holeLabel to it.case.g.border }.toList().sortedBy { it.first.toString() }) {
      summary += "  hole − (2m + 3) ${key.first}, border ${key.second}: identical ${group.count { it.o.identical }} / " +
        "${group.size}; routes ${routeCounts(group)}"
    }
    summary += "routes as expected ${all.count { asExpected(it) }} / ${all.size}"
    summary += "σ < 2 in ${all.count { sigma(it.case) < 2f }} / ${all.size} builds, borders " +
      "${all.filter { sigma(it.case) < 2f }.map { it.case.g.border }.toSortedSet()}"
    summary += footer(seconds)
    summary += offTargetLines(listOf("fresh" to fresh, "reuse" to reuse))
    finish("inner1-builder-smallblur", all, summary)

    assertChecks(all)
    val px = cases.filter { labels.getValue(it).startsWith("px") }
    assertTrue("px radii with σ ≥ 2: ${px.filter { sigma(it) >= 2f }.map { it.key }}", px.all { sigma(it) < 2f })
    assertEquals("borders of the px radii", setOf(1, 2, 3, 4), px.map { it.g.border }.toSet())
    for (row in all) {
      val d = decisions.getValue(row)
      if (d != null) {
        assertEquals("route of ${row.case.key} (${d.kind})", d.route, row.o.route)
      } else {
        assertTrue("route of ${row.case.key}: ${row.o.route}", row.o.route in SmallBlurCases.ROUND_ROUTES)
      }
    }
  }

  /**
   * Inverse fills ([MaskRouteGapCases.inverseShapes]: a triangle and a curved star under `InverseWinding` and
   * `InverseEvenOdd`) on a [MaskRouteGapCases.BOX_DP] box × radius 1 px / 4 dp / 24 dp × spread 0 / 8 / −8 dp ×
   * offsets zero / 4 dp, plus [MaskRouteGapCases.inverseRectShape] at radius 1 px, spread 0.5 px, offset zero (a hole
   * large enough for bands), fresh builders. Skia forms an inverse fill's mask over the whole clip; with spread > 0 the blur
   * source is Compose's intermediate instead ([MaskRouteGapCases.predictInner]). Every case is asserted identical on the
   * target Skia and on its route ([MaskRouteGapCases.expectedInnerRoute]: the Skia route at spread ≤ 0, the mask route
   * with spread), with one failure listing the counts; the generation id as in [builderMatchesCompose]; and no bands
   * ([ShadowBitmaps.hasBands]: an inverse fill covers the whole clip, past any band). Output (subdir [RESULTS_DIR]):
   * `inner1-builder-maskgaps.csv` (the columns of `inner1-builder.csv` after the group, Skia's decision, its rule and
   * the expected route, then whether the build set bands) and `inner1-builder-maskgaps-summary.txt`.
   */
  @Test
  fun maskRouteGapsMatchCompose() {
    val start = SystemClock.elapsedRealtime()
    val offsets = MAIN_OFFSETS.filter { it.first in setOf("zero", "dp4") }
    class GapCase(val case: Case, val radiusLabel: String, val spreadLabel: String)
    val cases = buildList {
      for (shape in MaskRouteGapCases.inverseShapes()) for ((rl, r) in MaskRouteGapCases.radii(density.density)) {
        for (s in MaskRouteGapCases.INVERSE_SPREADS_DP) {
          val g = Geometry(MaskRouteGapCases.BOX_DP, MaskRouteGapCases.BOX_DP, r, s)
          for ((offset, at) in offsets) {
            val (ox, oy) = at(g)
            add(GapCase(Case(shape, g, offset, ox, oy), rl, "dp${fmt(s)}"))
          }
        }
      }
      // A convex inverse fill whose spread intermediate leaves a large hole: no bands all the same.
      val d = density.density
      val g = Geometry(MaskRouteGapCases.BOX_DP, MaskRouteGapCases.BOX_DP, MaskRouteGapCases.dpOf(1f, d), MaskRouteGapCases.dpOf(0.5f, d))
      add(GapCase(Case(MaskRouteGapCases.inverseRectShape(), g, "zero", 0f, 0f), "px1", "px0.5"))
    }
    class GapRow(val csv: String, val row: Row, val result: MaskRouteGapCases.Result, val bands: Boolean)
    val rows =
      cases.mapIndexed { i, gc ->
        val c = gc.case
        val path = (outline(c) as Outline.Generic).path.asAndroidPath()
        val prediction = MaskRouteGapCases.predictInner(path, c.g.radius, c.g.spread)
        val expectedRoute = MaskRouteGapCases.expectedInnerRoute(path, c.g.spread)
        val target = ShadowBitmaps()
        val row = ShadowBitmapBuilder().use { b -> Row("maskgaps", i, c, measure(b, target, c, false)) }
        val result =
          MaskRouteGapCases.Result(
            MaskRouteGapCases.INVERSE,
            gc.radiusLabel,
            gc.spreadLabel,
            prediction.gap != null,
            row.o.route,
            row.o.identical,
            row.o.diff.max,
            row.o.diff.pixels,
            expectedRoute,
          )
        val prefix = "${MaskRouteGapCases.INVERSE},${prediction.gap ?: ""},${prediction.rule},$expectedRoute"
        GapRow("$prefix,${row.csv()},${bit(target.hasBands)}", row, result, target.hasBands)
      }
    val seconds = (SystemClock.elapsedRealtime() - start) / 1000.0
    val results = rows.map { it.result }
    val summary = ArrayList<String>()
    summary += MaskRouteGapCases.summary(results)
    for ((offset, group) in rows.groupBy { it.row.case.offset }) {
      summary += "  offset $offset: identical ${group.count { it.row.o.identical }} / ${group.size}; routes ${routeCounts(group.map { it.row })}"
    }
    summary += "generation id unchanged ${rows.count { !it.row.o.genChanged }}, bands set ${rows.count { it.bands }}"
    summary += footer(seconds)
    summary += offTargetLines(listOf("maskgaps" to rows.map { it.row }))
    summary.forEach { Log.i(TAG, it) }
    rows.filter { !it.row.o.identical }.take(MAX_LOGGED_ROWS).forEach { Log.i(TAG, it.csv) }
    writeResults(context, "$GAPS_NAME.csv", listOf("gap,predictedGap,skiaRule,expectedRoute,$HEADER,hasBands") + rows.map { it.csv }, RESULTS_DIR)
    writeResults(context, "$GAPS_NAME-summary.txt", summary, RESULTS_DIR)

    for (r in rows) {
      assertTrue("generation id unchanged: ${r.csv}", r.row.o.genChanged)
      assertTrue("bands for an inverse fill: ${r.csv}", !r.bands)
    }
    MaskRouteGapCases.failure(results, "$GAPS_NAME.csv", TargetSkia.isDevice)?.let { throw AssertionError(it) }
  }

  @Test
  fun oneAxisEmptyInsetMatchesCompose() {
    val start = SystemClock.elapsedRealtime()
    val shapes =
      listOf("rrect16", "circle").map { ShapeCase(it, catalogShape(it)) } +
        ShapeCase("rrect4", RoundedCornerShape(4.dp)) +
        ShapeCase("rrect49pct", RoundedCornerShape(percent = 49))
    val cases = buildList {
      for (shape in shapes) for ((wDp, hDp) in listOf(200 to 48, 48 to 200, 360 to 48)) for (r in listOf(1f, 4f, 24f, 64f)) {
        for (s in listOf(20f, 23f, 23.5f, 23.9f, 24f, 26f, 30f)) {
          val g = Geometry(wDp, hDp, r, s)
          for ((name, at) in MAIN_OFFSETS.filter { it.first in setOf("zero", "dp4", "px3.25") }) {
            val (ox, oy) = at(g)
            add(Case(shape, g, name, ox, oy))
          }
        }
      }
    }
    val rows = cases.mapIndexed { i, c -> ShadowBitmapBuilder().use { b -> Row("oneAxis", i, c, measure(b, ShadowBitmaps(), c, false)) } }
    val seconds = (SystemClock.elapsedRealtime() - start) / 1000.0
    val summary = ArrayList<String>()
    summary += summaryLines(listOf("oneAxis" to rows))
    for ((s, group) in rows.groupBy { it.case.g.spreadDp }.toSortedMap()) {
      summary += "  spread ${fmt(s)} dp: identical ${group.count { it.o.identical }} / ${group.size}; routes ${routeCounts(group)}"
    }
    summary += footer(seconds)
    summary += offTargetLines(listOf("oneAxis" to rows))
    finish("inner1-builder-oneaxis", rows, summary)
    assertChecks(rows)
  }

  @Test
  fun rectPathsAndFrames() {
    val start = SystemClock.elapsedRealtime()
    val shapes =
      listOf(
        ShapeCase("rect", catalogShape("rect")),
        ShapeCase("rectPath", RectPathShape()),
        ShapeCase("rectPathInset", RectPathShape(0.2f)),
        ShapeCase("rrectX0", CornerX0Shape(16.dp)),
        ShapeCase("frameCcw", FrameShape(evenOdd = false)),
        ShapeCase("frameEvenOdd", FrameShape(evenOdd = true)),
      )
    val cases = buildList {
      for (shape in shapes) for (side in listOf(48, 200)) for (r in RADII_DP) for (s in SPREADS_DP) {
        val g = Geometry(side, side, r, s)
        for ((name, at) in MAIN_OFFSETS.filter { it.first in setOf("zero", "dp8", "dp4", "px3.25", "f0.5", "partOff") }) {
          val (ox, oy) = at(g)
          add(Case(shape, g, name, ox, oy))
        }
      }
    }
    // Frames only: more sizes and small radii at seeded fractional offsets, some reaching past the bitmap's edges.
    val random = Random(SEED)
    val sweep = buildList {
      for (shape in shapes.filter { it.name in FRAME_SHAPES }) for (side in listOf(48, 64, 100, 200)) {
        for (r in listOf(2f, 4f, 8f, 16f)) {
          val g = Geometry(side, side, r, 0f)
          // Within 20 px, then up to 90 % of the side (partly off the bitmap); eighths of a pixel.
          repeat(32) { k ->
            val reach = if (k < 16) 160 else (7.2f * g.size.width).toInt()
            val ox = random.nextInt(-reach, reach) / 8f
            val oy = random.nextInt(-reach, reach) / 8f
            add(Case(shape, g, "rnd$k", ox, oy))
          }
        }
      }
    }
    val holeCases = frameHoleCases()
    val edgeFrameCases = frameEdgeCases()
    val rows = cases.mapIndexed { i, c -> ShadowBitmapBuilder().use { b -> Row("rects", i, c, measure(b, ShadowBitmaps(), c, false)) } }
    val sweepRows =
      sweep.mapIndexed { i, c -> ShadowBitmapBuilder().use { b -> Row("frameSweep", i, c, measure(b, ShadowBitmaps(), c, false)) } }
    val holeRows =
      holeCases.mapIndexed { i, c -> ShadowBitmapBuilder().use { b -> Row("frameHoles", i, c, measure(b, ShadowBitmaps(), c, false)) } }
    val edgeFrameRows =
      edgeFrameCases.mapIndexed { i, c -> ShadowBitmapBuilder().use { b -> Row("frameEdges", i, c, measure(b, ShadowBitmaps(), c, false)) } }
    val frameRows = holeRows + edgeFrameRows
    val seconds = (SystemClock.elapsedRealtime() - start) / 1000.0
    val summary = ArrayList<String>()
    summary += summaryLines(listOf("rects" to rows, "frameSweep" to sweepRows, "frameHoles" to holeRows, "frameEdges" to edgeFrameRows))
    for ((side, group) in sweepRows.groupBy { it.case.g.widthDp }) {
      summary += "  frameSweep ${side}dp: identical ${group.count { it.o.identical }} / ${group.size}, " +
        "max diff ${group.maxOf { it.o.diff.max }}, differing pixels ${group.sumOf { it.o.diff.pixels }}"
    }
    for ((name, group) in rows.groupBy { it.case.shape.name }) {
      for ((r, byRadius) in group.groupBy { it.case.g.radiusDp > 0f }) {
        val bad = byRadius.filter { !it.o.identical }
        summary += "  $name ${if (r) "radius > 0" else "radius 0"}: identical ${byRadius.size - bad.size} / ${byRadius.size}, " +
          "max diff ${byRadius.maxOf { it.o.diff.max }}; routes ${routeCounts(byRadius)}" +
          if (bad.isEmpty()) "" else "; first mismatches: " + bad.take(FIRST_MISMATCHES).joinToString("; ") { it.case.key }
      }
    }
    for ((phase, group) in listOf("frameHoles" to holeRows, "frameEdges" to edgeFrameRows)) {
      val decided = group.map { it to model(it.case, superset = true) }
      for ((hole, part) in decided.filter { it.second.holeX != FrameRouteModel.NO_HOLE }.groupBy { it.second.holeLabel }.toSortedMap()) {
        summary += "  $phase hole − (2m + 3) $hole: identical ${part.count { it.first.o.identical }} / ${part.size}; " +
          "routes ${routeCounts(part.map { it.first })}"
      }
    }
    val all = rows + sweepRows + frameRows
    val decisions = all.associateWith { expectedRectsRoute(it.case) }
    for ((kind, group) in all.groupBy { decisions.getValue(it).kind }.toSortedMap()) {
      summary += "  model $kind: identical ${group.count { it.o.identical }} / ${group.size}; routes ${routeCounts(group)}"
    }
    summary += "routes as expected ${all.count { it.o.route == decisions.getValue(it).route }} / ${all.size}"
    summary += footer(seconds)
    summary += offTargetLines(listOf("rects" to rows, "frameSweep" to sweepRows, "frameHoles" to holeRows, "frameEdges" to edgeFrameRows))
    finish("inner1-builder-rects", all, summary)
    assertChecks(all)
    // Rects reach Skia's rect blur only through the Skia route; frames take [FrameRouteModel]'s route. A path with
    // spread is not blurred as a path: its intermediate is placed and blurred on the mask route (unless the
    // drawImageRect matrix scales, [inexactRight]).
    for (row in all) {
      val d = decisions.getValue(row)
      assertEquals("route of ${row.case.key} (${d.kind})", d.route, row.o.route)
    }
  }

  /**
   * The route [rectPathsAndFrames] expects: rects (`rect`, the rect paths, corners (x, 0)) the Skia route, or the mask
   * route for a path with spread > 0 ([inexactRight] aside); frames (every other shape there) [model]'s.
   */
  private fun expectedRectsRoute(c: Case): FrameRouteModel.Decision =
    if (c.shape.name in RECT_SHAPES) {
      val placed = c.shape.name != "rect" && c.g.radius > 0f && c.g.spread > 0f && !inexactRight(c)
      FrameRouteModel.Decision(if (placed) "Mask" else "Skia", "rect")
    } else {
      model(c, superset = true)
    }

  /**
   * [FrameRouteModel]'s route for an inner build of [c]'s path (Compose draws it moved by the offset) at radius > 0 and
   * spread ≤ 0; the Skia route at radius 0; with spread > 0 the mask route ([inexactRight] aside). [superset]: whether
   * the builder's nested-rects superset takes the path.
   */
  private fun model(c: Case, superset: Boolean): FrameRouteModel.Decision =
    when {
      c.g.radius <= 0f -> FrameRouteModel.Decision("Skia", "radius0")
      c.g.spread > 0f -> FrameRouteModel.Decision(if (inexactRight(c)) "Skia" else "Mask", "spread")
      else -> FrameRouteModel.decide(FrameRouteModel.canonicalPoints(outline(c)), superset, c.ox, c.oy, c.g.radius)
    }

  /**
   * Frames on 100 / 200 dp boxes whose hole, moved by the offset, rounds in to `2m + 3 − 1`, `2m + 3` or `2m + 3 + 1` px
   * on one axis ([FrameRouteModel.holeFrame]), × radius 1 / 4 / 8 / 16 / 24 dp × spread 0 / −4 dp (ignored for a path),
   * both axes; offsets zero, fractional and whole dp in turn, both fill kinds.
   */
  private fun frameHoleCases(): List<Case> = buildList {
    val offsets = MAIN_OFFSETS.filter { it.first in setOf("zero", "px3.25", "dp4") }
    var n = 0
    for (side in listOf(100, 200)) for (r in listOf(1f, 4f, 8f, 16f, 24f)) for (s in listOf(0f, -4f)) {
      val g = Geometry(side, side, r, s)
      val threshold = 2 * g.border + 3
      for (axis in 0..1) for (k in -1..1) {
        val (name, at) = offsets[n % offsets.size]
        val (ox, oy) = at(g)
        val frac = HOLE_FRACTIONS[n % HOLE_FRACTIONS.size]
        val shape = FrameRouteModel.holeFrame(g.size.width, threshold, axis, k, frac, ox, oy, evenOdd = n % 2 == 1) ?: continue
        add(Case(ShapeCase("hole${"xy"[axis]}${if (k < 0) "Below" else if (k > 0) "Above" else "At"}", shape), g, name, ox, oy))
        n++
      }
    }
  }

  /**
   * Frames with fractional outer and inner edges (seeded) and thin bands (a hole margin of 0.37 / 1.37 / 2.5 px) on 48 /
   * 100 / 200 dp boxes × radius 1 / 4 / 16 / 24 dp, and a 360 dp frame at radius 100 dp (σ past the 128 cap, 262.5 px
   * at density 2.625) with its hole above and below the threshold; offsets zero, fractional and partly off in turn.
   */
  private fun frameEdgeCases(): List<Case> = buildList {
    val random = Random(SEED + 1)
    val offsets = MAIN_OFFSETS.filter { it.first in setOf("zero", "px3.25", "partOff") }
    var n = 0
    fun f() = FRACTIONS[random.nextInt(FRACTIONS.size)]
    fun add(name: String, shape: RectsFrameShape, g: Geometry) {
      val (offset, at) = offsets[n++ % offsets.size]
      val (ox, oy) = at(g)
      add(Case(ShapeCase(name, shape), g, offset, ox, oy))
    }
    for (side in listOf(48, 100, 200)) for (r in listOf(1f, 4f, 16f, 24f)) {
      val g = Geometry(side, side, r, 0f)
      val px = g.size.width
      repeat(3) {
        val outer = Rect(f(), f(), px - f(), px - f())
        val q = { floor(px * (0.05f + 0.3f * random.nextFloat())) }
        val inner = Rect(q() + f(), q() + f(), px - q() - f(), px - q() - f())
        add("fractional", RectsFrameShape(outer, inner, evenOdd = it == 1), g)
      }
      for (band in listOf(0.37f, 1.37f, 2.5f)) {
        val outer = Rect(0.5f, 0.25f, px - 0.5f, px - 0.75f)
        add("thinBand", RectsFrameShape(outer, Rect(outer.left + band, outer.top + band, outer.right - band, outer.bottom - band)), g)
      }
    }
    for (inset in listOf(0.08f, 0.2f)) {
      val g = Geometry(360, 360, 100f, 0f)
      val px = g.size.width
      val d = floor(px * inset) + 0.37f
      add("sigmaCap${(inset * 100).toInt()}", RectsFrameShape(Rect(0f, 0f, px, px), Rect(d, d, px - d, px - d)), g)
    }
  }

  @Test
  @LargeTest
  fun nestedRectEdgeCasesMatchCompose() {
    val start = SystemClock.elapsedRealtime()
    val nested = NestedRectsEdgeShape.KINDS
    val shapes = nested.keys.map { ShapeCase(it, NestedRectsEdgeShape(it)) }
    val cases = buildList {
      for (shape in shapes) for (side in listOf(48, 200)) for (r in RADII_DP) for (s in SPREADS_DP) {
        val g = Geometry(side, side, r, s)
        for ((name, at) in MAIN_OFFSETS.filter { it.first in setOf("zero", "px3.25", "partOff") }) {
          val (ox, oy) = at(g)
          add(Case(shape, g, name, ox, oy))
        }
      }
    }
    val fresh = cases.mapIndexed { i, c -> ShadowBitmapBuilder().use { b -> Row("edgePaths", i, c, measure(b, ShadowBitmaps(), c, false)) } }
    val order = cases.shuffled(Random(SEED))
    val reuse = ShadowBitmapBuilder().use { b ->
      val target = ShadowBitmaps()
      order.mapIndexed { i, c -> Row("edgePathsReuse", i, c, measure(b, target, c, preserve = i % 2 == 1)) }
    }
    val all = fresh + reuse
    val decisions = all.associateWith { model(it.case, nested.getValue(it.case.shape.name)) }
    fun expected(row: Row): String = decisions.getValue(row).route
    val allocations = frameAllocations()
    val seconds = (SystemClock.elapsedRealtime() - start) / 1000.0
    val summary = ArrayList<String>()
    summary += summaryLines(listOf("edgePaths" to fresh, "edgePathsReuse" to reuse))
    for ((name, group) in all.groupBy { it.case.shape.name }) {
      for ((label, part) in group.groupBy { spreadLabel(it.case.g) }.toSortedMap()) {
        summary += "  $name (nested rects ${nested.getValue(name)}) $label: identical ${part.count { it.o.identical }} / " +
          "${part.size}, max diff ${part.maxOf { it.o.diff.max }}; routes ${routeCounts(part)}"
      }
    }
    for ((kind, group) in all.groupBy { decisions.getValue(it).kind }.toSortedMap()) {
      summary += "  model $kind: identical ${group.count { it.o.identical }} / ${group.size}; routes ${routeCounts(group)}"
    }
    summary += "routes as expected ${all.count { it.o.route == expected(it) }} / ${all.size}"
    summary += allocations.line
    summary += footer(seconds)
    summary += offTargetLines(listOf("edgePaths" to fresh, "edgePathsReuse" to reuse))
    finish("frames-inner-edges", all, summary, InnerScreenCases.RESULTS_DIR2)

    assertChecks(all)
    for (row in all) assertEquals("route of ${row.case.key} (${decisions.getValue(row).kind})", expected(row), row.o.route)
    allocations.assertNone()
  }

  /** `radius 0`, or the spread's sign with radius > 0 (the inner routes split on both). */
  private fun spreadLabel(g: Geometry): String =
    when {
      g.radius <= 0f -> "radius 0"
      g.spread > 0f -> "radius > 0, spread > 0"
      else -> "radius > 0, spread <= 0"
    }

  @Test
  @LargeTest
  fun pathSpreadOffsetsMatchCompose() {
    val start = SystemClock.elapsedRealtime()
    val random = Random(SEED)
    val shapes =
      listOf("mixed", "morph").map { ShapeCase(it, catalogShape(it)) } +
        ShapeCase("morph03", PlacedMorphShape(0.3f, 0.3f)) +
        ShapeCase("morphNeg", PlacedMorphShape(0.55f, 0.45f, negative = true))
    val cases = buildList {
      for (shape in shapes) for (side in listOf(48, 200)) for (r in listOf(1f, 4f, 24f, 64f)) for (s in listOf(1f, 2.3f, 8f)) {
        val g = Geometry(side, side, r, s)
        for ((name, ox, oy) in spreadOffsets(shape, g, random)) add(Case(shape, g, name, ox, oy))
      }
      // One-axis-thin layouts, and spreads whose stroke clears the whole path.
      for (shape in shapes.take(2)) for ((wDp, hDp, s) in THIN_AND_ERODED) for (r in listOf(4f, 24f)) {
        val g = Geometry(wDp, hDp, r, s)
        for ((name, ox, oy) in spreadOffsets(shape, g, random)) add(Case(shape, g, name, ox, oy))
      }
    }
    val fresh =
      cases.mapIndexed { i, c -> ShadowBitmapBuilder().use { b -> Row("spread", i, c, measure(b, ShadowBitmaps(), c, false)) } }
    val order = cases.shuffled(Random(SEED))
    val reuse = ShadowBitmapBuilder().use { b ->
      val target = ShadowBitmaps()
      order.mapIndexed { i, c -> Row("spreadReuse", i, c, measure(b, target, c, preserve = i % 2 == 1)) }
    }
    val all = fresh + reuse
    val placements = all.associateWith { if (inexactRight(it.case)) "skiaRoute" else placement(it.case) }
    val seconds = (SystemClock.elapsedRealtime() - start) / 1000.0
    val summary = ArrayList<String>()
    summary += summaryLines(listOf("spread" to fresh, "spreadReuse" to reuse))
    fun line(label: String, rows: List<Row>) =
      "  $label: identical ${rows.count { it.o.identical }} / ${rows.size}, max diff ${rows.maxOfOrNull { it.o.diff.max } ?: 0}"
    for (kind in PLACEMENTS) summary += line("placement $kind", all.filter { placements[it] == kind })
    val large = all.filter { it.case.g.border > 128 }
    for (kind in PLACEMENTS) summary += line("border > 128, placement $kind", large.filter { placements[it] == kind })
    for (kind in PLACEMENTS) {
      summary += line("border > 128, partly off, placement $kind", large.filter { placements[it] == kind && isPartlyOff(it.case.offset) })
    }
    summary += line("thin or eroded", all.filter { it.case.g.widthDp != it.case.g.heightDp || it.case.g.spreadDp > 8f })
    val inexact = all.filter { inexactRight(it.case) }
    summary += line("fl(o + intermediate size) - o != intermediate size on an axis", inexact)
    summary += footer(seconds)
    summary += offTargetLines(listOf("spread" to fresh, "spreadReuse" to reuse))
    summary.forEach { Log.i(TAG, it) }
    all.filter { !it.o.identical }.take(MAX_LOGGED_ROWS).forEach { Log.i(TAG, it.csv() + "," + placements[it]) }
    writeResults(
      context,
      "inner2-builder-spread.csv",
      listOf("$HEADER,placement,inexactRight") + all.map { "${it.csv()},${placements[it]},${bit(inexactRight(it.case))}" },
      InnerScreenCases.RESULTS_DIR2,
    )
    writeResults(context, "inner2-builder-spread-summary.txt", summary, InnerScreenCases.RESULTS_DIR2)

    assertChecks(all)
    for (row in all) assertEquals("route of ${row.case.key}", if (inexactRight(row.case)) "Skia" else "Mask", row.o.route)
    for (kind in PLACEMENTS) assertTrue("no $kind case", all.any { placements[it] == kind })
    for (kind in listOf("sprite", "filtered")) {
      assertTrue("no partly-off $kind case with border > 128", large.any { placements[it] == kind && isPartlyOff(it.case.offset) })
    }
    assertTrue("no clipped-out case with border > 128", large.any { placements[it] == "clippedOut" })
    assertTrue("no case with an inexact right or bottom edge", inexact.isNotEmpty())
  }

  // ---------------------------------------------------------------------------------------------------------------
  // Path + spread placement
  // ---------------------------------------------------------------------------------------------------------------

  /** Compose's intermediate for a path with spread: `ceil` of the path bounds' size. */
  private fun intermediateSize(c: Case): Pair<Int, Int> = intermediateSize(c.shape, c.g)

  private fun intermediateSize(shape: ShapeCase, g: Geometry): Pair<Int, Int> {
    val b = checkNotNull(outlineParams(shape.shape.createOutline(g.size, LayoutDirection.Ltr, density)).path).getBounds()
    return ceil(b.width).toInt() to ceil(b.height).toInt()
  }

  /** Compose's bitmap for a path: `ceil(size)` grown by `ceil(radius)` on each side. */
  private fun bitmapSize(g: Geometry): Pair<Int, Int> {
    val pad = ceil(g.radius).toInt()
    return ceil(g.size.width).toInt() + 2 * pad to ceil(g.size.height).toInt() + 2 * pad
  }

  /**
   * How the newest Skia places the intermediate drawn at the offset (`drawBitmapAsMask`, for an offset that is not
   * [inexactRight]; such an offset is `skiaRoute`): `clippedOut` (its rounded-out
   * device rect misses the bitmap), `sprite` (an integer translate whose mapped bounds at 4 subpixel bits are the
   * integer bounds), `filtered` (a bilinear draw into the rounded device rect clipped to the bitmap) or `filteredEmpty`
   * (that rect is empty).
   */
  private fun placement(c: Case): String {
    val (iw, ih) = intermediateSize(c)
    val (w, h) = bitmapSize(c.g)
    val left = c.ox
    val top = c.oy
    val right = c.ox + iw
    val bottom = c.oy + ih
    if (!(floor(left) < w && ceil(right) > 0 && floor(top) < h && ceil(bottom) > 0)) return "clippedOut"
    val integer = left == left.toInt().toFloat() && top == top.toInt().toFloat()
    val ix = skRound(left)
    val iy = skRound(top)
    val sprite =
      integer &&
        (ix shl 4) == skRound(left * 16f) &&
        (iy shl 4) == skRound(top * 16f) &&
        ((ix + iw) shl 4) == skRound(right * 16f) &&
        ((iy + ih) shl 4) == skRound(bottom * 16f)
    if (sprite) return "sprite"
    val empty = max(skRound(left), 0) >= min(skRound(right), w) || max(skRound(top), 0) >= min(skRound(bottom), h)
    return if (empty) "filteredEmpty" else "filtered"
  }

  /**
   * The float sum `offset + intermediate size` minus the offset is not the size on an axis: Skia's `drawImageRect`
   * matrix for Compose's `drawImage` scales, and the builder leaves the build to the Skia route.
   */
  private fun inexactRight(c: Case): Boolean {
    val (iw, ih) = intermediateSize(c)
    return (c.ox + iw) - c.ox != iw.toFloat() || (c.oy + ih) - c.oy != ih.toFloat()
  }

  private fun isPartlyOff(offset: String): Boolean = offset.length > 2 && offset[1] == ':' && offset.first() in "LRTB"

  /**
   * Offsets (px) for the path + spread route: whole pixels (sprite), around Skia's 1/32 px sprite tolerance (inside,
   * at and just outside it, on one or both axes), halves and quarters, negative, seeded random fractions, the
   * intermediate half off each bitmap edge (whole and fractional), at an edge so that the cut rect rounds to nothing or
   * one pixel, and fully off the bitmap (clipped out).
   */
  private fun spreadOffsets(shape: ShapeCase, g: Geometry, random: Random): List<Triple<String, Float, Float>> {
    val (iw, ih) = intermediateSize(shape, g)
    val (w, h) = bitmapSize(g)
    val out = ArrayList<Triple<String, Float, Float>>()
    fun add(name: String, x: Float, y: Float) = out.add(Triple(name, x, y))
    add("int0", 0f, 0f)
    add("int", 21f, -13f)
    add("intNeg", -34f, -8f)
    add("int1", 1f, 2f)
    add("x+1/64", 5f + 1f / 64, 7f)
    add("y+1/64", 5f, 7f + 1f / 64)
    add("xy-1/64", 5f - 1f / 64, 7f - 1f / 64)
    add("xy+1/32", 5f + 1f / 32, -3f + 1f / 32)
    add("x+1/32+", 5f + 1f / 32 + 1f / 256, 7f)
    add("x-1/32-", 5f - 1f / 32 - 1f / 256, -7f)
    add("half", 0.5f, -0.5f)
    add("halfX", 10.5f, 3f)
    add("quarter", 0.25f, 0.75f)
    add("quarterNeg", -3.25f, -0.75f)
    repeat(6) { k -> add("rnd$k", (random.nextFloat() * 2f - 1f) * 60f, (random.nextFloat() * 2f - 1f) * 60f) }
    add("L:int", (-iw / 2).toFloat(), 3f)
    add("L:frac", -iw / 2f + 0.3f, 3.6f)
    add("R:int", (w - iw / 2).toFloat(), 3f)
    add("R:frac", w - iw / 2f + 0.3f, 3.6f)
    add("T:int", 4f, (-ih / 2).toFloat())
    add("T:frac", 4.4f, -ih / 2f - 0.3f)
    add("B:int", 4f, (h - ih / 2).toFloat())
    add("B:frac", 4.4f, h - ih / 2f + 0.7f)
    add("L:edge0.4", -iw + 0.4f, 2f)
    add("L:edge0.5", -iw + 0.5f, 2f)
    add("R:edge0.5", w - 0.5f, 2f)
    add("R:edge0.6", w - 0.6f, 2f)
    add("T:edge0.4", 2f, -ih + 0.4f)
    add("B:edge0.5", 2f, h - 0.5f)
    add("offL", (-iw - 1).toFloat(), 0f)
    add("offR", w.toFloat(), 0f)
    add("offT", 0.25f, -ih - 0.25f)
    add("offB", 0.25f, h + 0.5f)
    add("offRB", (w + 40).toFloat(), (h + 40).toFloat())
    return out
  }

  /** `SkScalarRoundToInt`: floor(x + 0.5) in double. */
  private fun skRound(x: Float): Int = floor(x.toDouble() + 0.5).toInt()

  // ---------------------------------------------------------------------------------------------------------------
  // One build
  // ---------------------------------------------------------------------------------------------------------------

  private fun outline(c: Case): Outline = c.shape.shape.createOutline(c.g.size, LayoutDirection.Ltr, density)

  /** One inner build of [c] by [builder] into [target], against the reference. */
  private fun measure(builder: ShadowBitmapBuilder, target: ShadowBitmaps, c: Case, preserve: Boolean): Outcome {
    val g = c.g
    val outline = outline(c)
    val refImage = InnerShadowReference.referenceA(outline, g.size, g.radius, g.spread, c.ox, c.oy)
    val ref = refImage.asAndroidBitmap()
    val a = alphaOf(ref)
    val before = target.bitmap
    val genBefore = before?.generationId
    val beforePixels = if (preserve && before != null) alphaOf(before) else null
    builder.buildInner(target, outline, g.size, g.radius, g.spread, Offset(c.ox, c.oy), preserve)
    val bmp = target.bitmap!!
    check(bmp.width == ref.width && bmp.height == ref.height) {
      "${c.key}: bitmap ${bmp.width}x${bmp.height} vs reference ${ref.width}x${ref.height}"
    }
    val genChanged = bmp !== before || bmp.generationId != genBefore
    val untouched = beforePixels == null || (before !== bmp && alphaOf(before!!).contentEquals(beforePixels))
    val d = ScreenCases.diffAlpha(a, alphaOf(bmp))
    ref.recycle()
    return Outcome(builder.route.name, bmp.width, bmp.height, d, genChanged, untouched)
  }

  /** A drop-shadow build of [c]'s shape, size, radius and |spread| with [builder], against Compose's drop bitmap. */
  private fun dropBuild(builder: ShadowBitmapBuilder, target: ShadowBitmaps, c: Case): ScreenCases.Diff {
    val g = c.g
    val outline = outline(c)
    val spread = abs(g.spread)
    val ref = ComposeShadowReference.referenceA(outline, g.size, g.radius, spread).asAndroidBitmap()
    builder.build(target, outline, g.size, g.radius, spread)
    val bmp = target.bitmap!!
    check(bmp.width == ref.width && bmp.height == ref.height) { "drop ${c.key}: size" }
    val d = ScreenCases.diffAlpha(alphaOf(ref), alphaOf(bmp))
    ref.recycle()
    return d
  }

  // ---------------------------------------------------------------------------------------------------------------
  // Edge offsets
  // ---------------------------------------------------------------------------------------------------------------

  /**
   * The offsets that put the moved shape's bounds at the distances of [edgeDistances] past each bitmap edge (`L`, `R`,
   * `T`, `B`) and past two corners (`LT`, `RB`), the other axis at offset 0. The shape's bounds: the inset rect for a
   * round rect (Compose's `drawRoundRect` rect at offset 0), the path's bounds for a path; the bitmap: Compose's.
   */
  private fun edgeOffsets(shape: ShapeCase, g: Geometry): List<Triple<String, Float, Float>> {
    val outline = shape.shape.createOutline(g.size, LayoutDirection.Ltr, density)
    val path = outlineParams(outline).path
    val bounds: Rect
    val bw: Float
    val bh: Float
    if (path != null) {
      bounds = path.getBounds()
      val pad = 2 * ceil(g.radius)
      bw = ceil(g.size.width) + pad
      bh = ceil(g.size.height) + pad
    } else {
      val s = g.spread
      bounds = Rect(s, s, max(s, g.size.width - s), max(s, g.size.height - s))
      bw = ceil(g.size.width)
      bh = ceil(g.size.height)
    }
    val out = ArrayList<Triple<String, Float, Float>>()
    for ((label, e) in edgeDistances(g)) {
      val l = -e - bounds.right
      val r = bw + e - bounds.left
      val t = -e - bounds.bottom
      val b = bh + e - bounds.top
      out += Triple("L:$label", l, 0f)
      out += Triple("R:$label", r, 0f)
      out += Triple("T:$label", 0f, t)
      out += Triple("B:$label", 0f, b)
      out += Triple("LT:$label", l, t)
      out += Triple("RB:$label", r, b)
    }
    return out
  }

  /** Distances (px) past an edge: overlaps, touching, around the border b, around 3σ, around the 128 px cap. */
  private fun edgeDistances(g: Geometry): List<Pair<String, Float>> {
    val out = LinkedHashMap<Float, String>()
    fun add(label: String, e: Float) {
      if (e !in out) out[e] = label
    }
    for (e in listOf(-2.5f, -0.5f, 0f, 0.5f)) add(fmt(e), e)
    if (g.radius > 0f) {
      val b = g.border.toFloat()
      for (d in listOf(-1f, -0.5f, 0f, 0.5f, 1f)) add("b${signed(d)}", b + d)
      for (d in listOf(-0.5f, 0f, 0.5f)) add("3s${signed(d)}", g.reach + d)
      if (g.border > 128) for (d in listOf(-0.5f, 0f, 0.5f, 1f)) add("cap${signed(d)}", 128f + d)
    }
    return out.entries.map { it.value to it.key }
  }

  /** The band of an edge offset name: the label after the side. */
  private fun band(offset: String): String = offset.substringAfter(':').let { if (it.first().isDigit() || it.first() == '-') "near:$it" else it }

  // ---------------------------------------------------------------------------------------------------------------
  // Allocations
  // ---------------------------------------------------------------------------------------------------------------

  /**
   * Java allocations per inner rebuild once grown, per native route and the Skia route: 10 rebuilds with the radius
   * alternating 24 / 23 dp, 10 with the offset changing every build, and 10 with nothing changed, after a warm-up.
   */
  private fun allocationCheck(): String {
    val parts = ArrayList<String>()
    val setups =
      listOf(
        Triple("rrect16 200dp s0", ShapeCase("rrect16", catalogShape("rrect16")), 200 to 0f),
        Triple("rrect16 48dp s0", ShapeCase("rrect16", catalogShape("rrect16")), 48 to 0f),
        Triple("circle 200dp s0", ShapeCase("circle", catalogShape("circle")), 200 to 0f),
        Triple("morph 200dp s0", ShapeCase("morph", catalogShape("morph")), 200 to 0f),
        Triple("morph 200dp s8", ShapeCase("morph", catalogShape("morph")), 200 to 8f),
        Triple("rect 200dp s0", ShapeCase("rect", catalogShape("rect")), 200 to 0f),
      )
    for ((label, shape, geometry) in setups) {
      val (side, spreadDp) = geometry
      ShadowBitmapBuilder().use { b ->
        val t = ShadowBitmaps()
        val g1 = Geometry(side, side, 24f, spreadDp)
        val r2 = with(density) { 23.dp.toPx() }
        val outline = shape.shape.createOutline(g1.size, LayoutDirection.Ltr, density)
        val ox = with(density) { 2.dp.toPx() }
        val oy = with(density) { 4.dp.toPx() }
        b.buildInner(t, outline, g1.size, g1.radius, g1.spread, Offset(ox, oy))
        b.buildInner(t, outline, g1.size, r2, g1.spread, Offset(ox, oy), preserveCurrent = true)
        // The nine-patch keeps its blurred small round rect from the second build on, sized for that
        // radius: a third build at the first radius grows it to both.
        b.buildInner(t, outline, g1.size, g1.radius, g1.spread, Offset(ox, oy), preserveCurrent = true)
        val radius = count { repeat(10) { b.buildInner(t, outline, g1.size, if (it % 2 == 0) g1.radius else r2, g1.spread, Offset(ox, oy), true) } }
        val offset = count { repeat(10) { b.buildInner(t, outline, g1.size, g1.radius, g1.spread, Offset(ox + it * 0.37f, oy - it * 0.25f), true) } }
        val same = count { repeat(10) { b.buildInner(t, outline, g1.size, g1.radius, g1.spread, Offset(ox, oy), true) } }
        parts += "$label (${b.route}): radius changing $radius, offset changing $offset, nothing changed $same"
      }
    }
    return "allocations per 10 inner rebuilds once grown (both buffers in use): " + parts.joinToString("; ")
  }

  /** Java allocations per 10 inner rebuilds once grown, per setup: radius changing, offset changing, nothing changed. */
  private class Allocations(val label: String, val route: String, val radius: Int, val offset: Int, val same: Int)

  /** [frameAllocations]' result: the summary line and the check. */
  private class FrameAllocations(val frames: List<Allocations>, val rectPath: Allocations) {
    val line: String
      get() =
        "allocations per 10 inner rebuilds once grown, outline kept (both buffers in use): " +
          (frames + rectPath).joinToString("; ") {
            "${it.label} (${it.route}): radius changing ${it.radius}, offset changing ${it.offset}, nothing changed ${it.same}"
          } + " (Skia route: one BlurMaskFilter per radius change; the frame nine-patch and the mask route: none)"

    /**
     * A frame allocates nothing per rebuild with the radius kept; with it changing, nothing on the frame nine-patch and
     * the mask route, and on the Skia route what a rect path there does (the `BlurMaskFilter` of a changed radius):
     * nothing for the frame test.
     */
    fun assertNone() {
      for (a in frames) {
        assertEquals("${a.label}: allocations with nothing changed", 0, a.same)
        assertEquals("${a.label}: allocations with the offset changing", 0, a.offset)
        val radius = if (a.route == "Skia") rectPath.radius else 0
        assertEquals("${a.label}: allocations with the radius changing", radius, a.radius)
      }
    }
  }

  /**
   * [allocationsOf] a 200 dp frame with spread 0 (the frame nine-patch) and 8 dp (mask route), and of a 200 dp rect
   * path with spread 0 (Skia route) for comparison.
   */
  private fun frameAllocations(): FrameAllocations =
    FrameAllocations(
      listOf(
        allocationsOf("frameCcw 200dp s0", FrameShape(evenOdd = false), 200, 0f),
        allocationsOf("frameCcw 200dp s8", FrameShape(evenOdd = false), 200, 8f),
        allocationsOf("frameEvenOdd 200dp s0", FrameShape(evenOdd = true), 200, 0f),
      ),
      allocationsOf("rectPath 200dp s0", RectPathShape(), 200, 0f),
    )

  /**
   * [allocationCheck]'s measurement for one shape, the outline created once and kept; the offset and unchanged loops
   * start at the radius they use.
   */
  private fun allocationsOf(label: String, shape: androidx.compose.ui.graphics.Shape, side: Int, spreadDp: Float): Allocations =
    ShadowBitmapBuilder().use { b ->
      val t = ShadowBitmaps()
      val g1 = Geometry(side, side, 24f, spreadDp)
      val r2 = with(density) { 23.dp.toPx() }
      val outline = shape.createOutline(g1.size, LayoutDirection.Ltr, density)
      val ox = with(density) { 2.dp.toPx() }
      val oy = with(density) { 4.dp.toPx() }
      b.buildInner(t, outline, g1.size, g1.radius, g1.spread, Offset(ox, oy))
      b.buildInner(t, outline, g1.size, r2, g1.spread, Offset(ox, oy), preserveCurrent = true)
      b.buildInner(t, outline, g1.size, g1.radius, g1.spread, Offset(ox, oy), preserveCurrent = true)
      val radius = count { repeat(10) { b.buildInner(t, outline, g1.size, if (it % 2 == 0) g1.radius else r2, g1.spread, Offset(ox, oy), true) } }
      // The radius loop ends at r2: back to the first radius outside the count, so the next loops keep the radius.
      b.buildInner(t, outline, g1.size, g1.radius, g1.spread, Offset(ox, oy), preserveCurrent = true)
      val offset = count { repeat(10) { b.buildInner(t, outline, g1.size, g1.radius, g1.spread, Offset(ox + it * 0.37f, oy - it * 0.25f), true) } }
      val same = count { repeat(10) { b.buildInner(t, outline, g1.size, g1.radius, g1.spread, Offset(ox, oy), true) } }
      Allocations(label, b.route.name, radius, offset, same)
    }

  @Suppress("DEPRECATION")
  private fun count(block: () -> Unit): Int {
    Debug.resetThreadAllocCount()
    Debug.startAllocCounting()
    block()
    Debug.stopAllocCounting()
    return Debug.getThreadAllocCount()
  }

  // ---------------------------------------------------------------------------------------------------------------
  // Summary and checks
  // ---------------------------------------------------------------------------------------------------------------

  private fun summaryLines(phases: List<Pair<String, List<Row>>>): List<String> {
    val out = ArrayList<String>()
    for ((phase, rows) in phases) {
      val bad = rows.filter { !it.o.identical }
      out += "$phase: identical ${rows.size - bad.size} / ${rows.size}, max diff ${rows.maxOfOrNull { it.o.diff.max } ?: 0}; " +
        "routes (identical / cases): ${routeCounts(rows)}; generation id unchanged ${rows.count { !it.o.genChanged }}, " +
        "previous bitmap changed ${rows.count { !it.o.previousUntouched }}" +
        if (bad.isEmpty()) "" else "; first mismatches: " + bad.take(FIRST_MISMATCHES).joinToString("; ") { it.case.key }
      for ((shape, group) in rows.groupBy { it.case.shape.name }) {
        out += "  $phase ${shape}: identical ${group.count { it.o.identical }} / ${group.size}; ${routeCounts(group)}"
      }
    }
    return out
  }

  /** `Route identical / cases` for every route that occurs, in [ShadowBitmapBuilder.Route] order. */
  private fun routeCounts(rows: List<Row>): String =
    ShadowBitmapBuilder.Route.entries.filter { r -> rows.any { it.o.route == r.name } }.joinToString(", ") { r ->
      "${r.name} ${rows.count { it.o.identical && it.o.route == r.name }} / ${rows.count { it.o.route == r.name }}"
    }

  private fun footer(seconds: Double): String =
    "API ${Build.VERSION.SDK_INT}, ${Build.MODEL}, density ${density.density}, " + String.format(Locale.US, "%.1f s", seconds)

  /** Below the target Skia, per phase: the difference from the device's Compose in all and per route. */
  private fun offTargetLines(phases: List<Pair<String, List<Row>>>): List<String> {
    if (TargetSkia.isDevice) return emptyList()
    val out = ArrayList<String>()
    for ((phase, rows) in phases) {
      out += "$phase ${TargetSkia.offTargetLabel} (alpha): " + DiffTally().also { t -> rows.forEach { t.add(it.o.diff) } }
      for ((route, group) in rows.groupBy { it.o.route }) {
        out += "  $route: " + DiffTally().also { t -> group.forEach { t.add(it.o.diff) } }
      }
    }
    return out
  }

  private fun finish(name: String, rows: List<Row>, summary: List<String>, dir: String = RESULTS_DIR) {
    summary.forEach { Log.i(TAG, it) }
    rows.filter { !it.o.identical }.take(MAX_LOGGED_ROWS).forEach { Log.i(TAG, it.csv()) }
    writeResults(context, "$name.csv", listOf(HEADER) + rows.map { it.csv() }, dir)
    writeResults(context, "$name-summary.txt", summary, dir)
  }

  private fun assertChecks(rows: List<Row>) {
    for (r in rows) {
      if (TargetSkia.isDevice) assertTrue("not identical: ${r.csv()}", r.o.identical)
      assertTrue("generation id unchanged: ${r.csv()}", r.o.genChanged)
      assertTrue("previous bitmap changed: ${r.csv()}", r.o.previousUntouched)
    }
  }

  private companion object {
    const val TAG = "FS-InnerBuilder"
    const val RESULTS_DIR = InnerScreenCases.RESULTS_DIR
    const val SEED = 20261121
    const val DROP_EVERY = 5
    const val FIRST_MISMATCHES = 5
    const val MAX_LOGGED_ROWS = 50
    const val SIGMA_SCALE = 0.57735f
    const val GAPS_NAME = "inner1-builder-maskgaps"
    const val HEADER =
      "model,api,phase,index,shape,widthDp,heightDp,widthPx,heightPx,radiusDp,radiusPx,border,spreadDp,spreadPx," +
        "offset,offsetX,offsetY,route,bitmapW,bitmapH,identical,maxDiff,diffPixels,genChanged,previousUntouched"

    val MAIN_SIZES = listOf(48 to 48, 200 to 200, 200 to 48, 48 to 200)
    val RADII_DP = listOf(0f, 1f, 4f, 24f, 64f)
    val SPREADS_DP = listOf(0f, 8f, -8f)
    val PATH_SHAPES = setOf("mixed", "morph", "morph03", "morphNeg")
    val PLACEMENTS = listOf("clippedOut", "sprite", "filtered", "filteredEmpty", "skiaRoute")

    /** (width dp, height dp, spread dp): thin on one axis, and spreads whose `Clear` stroke covers the whole path. */
    val THIN_AND_ERODED =
      listOf(
        Triple(200, 6, 1f),
        Triple(200, 6, 8f),
        Triple(6, 200, 1f),
        Triple(6, 200, 8f),
        Triple(200, 2, 1f),
        Triple(48, 48, 30f),
        Triple(200, 200, 120f),
      )
    val FRAME_SHAPES = setOf("frameCcw", "frameEvenOdd")
    val RECT_SHAPES = setOf("rect", "rectPath", "rectPathInset", "rrectX0")

    /** [FrameRouteModel.holeFrame]'s fractions of the moved hole's left and top edges, cycled. */
    val HOLE_FRACTIONS = listOf(0f, 0.25f, 0.5f, 0.37f)

    /** Fractional frame edges in px. */
    val FRACTIONS = listOf(0.37f, 0.61f, 0.25f, 0.5f, 0.125f, 0.875f)

    /** Offsets in px: zero, whole dp, negative dp, fractional px, a half, partly off, and past the bitmap and blur. */
    val MAIN_OFFSETS: List<Pair<String, InnerShadowBuilderTest.Geometry.() -> Pair<Float, Float>>> =
      listOf(
        "zero" to { 0f to 0f },
        "dp8" to { dpPx(8f) to dpPx(16f) },
        "dpNeg8" to { dpPx(-8f) to dpPx(-24f) },
        "dp4" to { dpPx(4f) to dpPx(-2f) },
        "px3.25" to { 3.25f to -7.5f },
        "f0.5" to { 0.5f to -0.5f },
        "partOff" to { (0.6f * size.width + 0.3f) to (-0.35f * size.height) },
        "far" to { (size.width + 2 * ceil(radius) + border + 8f) to 0f },
        "farY" to { 0f to -(size.height + 2 * ceil(radius) + border + 8f) },
      )

    fun bit(b: Boolean) = if (b) 1 else 0

    fun fmt(f: Float) = if (f == f.toInt().toFloat()) f.toInt().toString() else f.toString()

    fun signed(f: Float) = if (f == 0f) "" else if (f > 0f) "+${fmt(f)}" else fmt(f)
  }
}
