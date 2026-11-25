// Copyright 2026, Serhii Yaremych (desugar-64) and the Obvid project contributors
// SPDX-License-Identifier: Apache-2.0

package dev.serhiiyaremych.obvid

import android.graphics.Bitmap
import android.os.Build
import android.os.Debug
import android.util.Log
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.serhiiyaremych.obvid.builder.ShadowBitmapBuilder
import dev.serhiiyaremych.obvid.builder.ShadowBitmaps
import dev.serhiiyaremych.obvid.fixtures.CaseGeometry
import dev.serhiiyaremych.obvid.fixtures.ComposeShadowReference
import dev.serhiiyaremych.obvid.fixtures.FrameShape
import dev.serhiiyaremych.obvid.fixtures.ShapeCase
import dev.serhiiyaremych.obvid.fixtures.alphaOf
import dev.serhiiyaremych.obvid.fixtures.catalogShape
import dev.serhiiyaremych.obvid.fixtures.outlineParams
import dev.serhiiyaremych.obvid.fixtures.roundRectShapes
import dev.serhiiyaremych.obvid.fixtures.shapeCatalog
import kotlin.math.floor
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Checks that [ShadowBitmapBuilder]'s used region is identical to Compose 1.13.0's shadow bitmap (reference A,
 * [ComposeShadowReference]), on every route, and also when one builder is reused across sizes and radii.
 * Correctness only (no timing). Identity is asserted on the target Skia ([TargetSkia.isDevice]); elsewhere the
 * summaries add the difference from the device's Compose (PSNR) and only the other checks are asserted.
 *
 * - `fresh`: 120 cases (5 shapes × 48 / 200 dp × r 1 / 4 / 24 / 64 dp × spread 0 / 8 / −8 dp: the 80 cases of
 *   [DropShadowExactnessTest] plus negative spread), one new builder and bitmap pair each; plus 30 extra cases at
 *   radius 0 dp (Compose sets no mask filter), reported separately.
 * - `reuse`: one builder and one bitmap pair driven through the same 150 cases in a fixed shuffled order (seed
 *   [SEED]), each output against a freshly computed reference A.
 * Per build: used region vs reference A (identical, max diff, differing pixels), non-zero pixels outside the used
 * region (must be 0: stale pixels cleared), and whether the bitmap's generation id changed (HWUI keys its texture on
 * it).
 *
 * Output: `builder.csv` (rows) and `builder-summary.txt` in the additional test output dir (pulled by
 * AGP) and the app's files dir, rows in logcat (tag `FS-Builder`). The summary also counts the cases per route.
 *
 * [roundRectRoutesMatchCompose]: the round-rect routes (Skia's nine-patch on Obvid's pipeline,
 * [ShadowBitmapBuilder.Route.NinePatch], and its full-mask fallback on the mask route,
 * [ShadowBitmapBuilder.Route.MaskRoundRect], also for ovals) over their own matrix, with the same checks. Output:
 * `roundrect-builder.csv` and `roundrect-builder-summary.txt`.
 *
 * [rectPathsMatchCompose]: rect paths (`Outline.Generic`), which take the Skia route, with the same checks. Output:
 * `rectpath-builder.csv` and `rectpath-builder-summary.txt`.
 *
 * [framesMatchCompose]: frames (two nested rect contours, [FrameShape], [RectsFrameShape]), a sweep of them, holes
 * around Skia's nine-patch threshold, fractional and thin frames and a radius past the sigma cap, and the paths around
 * the builder's nested-rects test ([NestedRectsEdgeShape]), with the same checks and the route asserted by
 * [FrameRouteModel]; plus Java allocations per rebuild of a frame while its outline is kept. Output (subdir
 * [InnerScreenCases.RESULTS_DIR2]): `frames-drop-builder.csv` and `frames-drop-builder-summary.txt`.
 *
 * [smallBlurMatchesCompose]: blur radii under 2.6 px (σ < 2, Skia's small blur) on paths, round rects and frames
 * (holes around Skia's frame nine-patch threshold included), with the same checks and the route asserted. Output: `smallblur-drop-builder.csv` and `smallblur-drop-builder-summary.txt`.
 *
 * [maskRouteGapsMatchCompose]: paths whose mask Skia forms differently from a fill inside the path's bounds (inverse
 * fills, spread strokes up to 1 px wide, spread strokes that are rects; [MaskRouteGapCases]), which take the Skia
 * route, with Skia's decision per case and the counts per group; the inverse fills also with the full extent, which
 * keeps Compose's window for them. Output: `maskgaps-drop-builder.csv` and
 * `maskgaps-drop-builder-summary.txt`.
 */
@RunWith(AndroidJUnit4::class)
class ShadowBitmapBuilderTest {

  private class Case(val shape: ShapeCase, val sizeDp: Int, val radiusDp: Int, val spreadDp: Int) {
    val extra get() = radiusDp == 0
  }

  private val shapes = shapeCatalog()

  private val cases =
    buildList {
      for (shape in shapes) for (sizeDp in listOf(48, 200)) for (radiusDp in listOf(1, 4, 24, 64, 0)) {
        for (spreadDp in listOf(0, 8, -8)) add(Case(shape, sizeDp, radiusDp, spreadDp))
      }
    }

  @Test
  fun builderMatchesCompose() {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val context = instrumentation.targetContext
    val density = Density(context)
    val rows = ArrayList<String>()
    rows += HEADER
    val summary = ArrayList<String>()

    // fresh: one builder per case
    val fresh = cases.mapIndexed { i, c -> ShadowBitmapBuilder().use { b -> runCase(density, b, ShadowBitmaps(), c, "fresh", i) } }
    // reuse: one builder through all cases in a fixed shuffled order
    val order = cases.shuffled(Random(SEED))
    val reuse = ShadowBitmapBuilder().use { b ->
      val target = ShadowBitmaps()
      order.mapIndexed { i, c -> runCase(density, b, target, c, "reuse", i) }
    }
    for (r in fresh + reuse) {
      rows += r.csv()
      Log.i(TAG, r.csv())
    }
    for ((phase, list) in listOf("fresh" to fresh, "reuse" to reuse)) {
      val main = list.filter { !it.case.extra }
      val extra = list.filter { it.case.extra }
      summary +=
        "$phase: identical ${main.count { it.identical }} / ${main.size} (radius > 0; " +
          "mask route ${main.count { it.identical && it.route == "Mask" }} / ${main.count { it.route == "Mask" }}, " +
          "skia route ${main.count { it.identical && it.route == "Skia" }} / ${main.count { it.route == "Skia" }}), " +
          "radius 0 identical ${extra.count { it.identical }} / ${extra.size}, " +
          "outside used non-zero ${list.count { it.outsideNonZero > 0 }}, generation id unchanged ${list.count { !it.genChanged }}"
    }
    for ((phase, list) in listOf("fresh" to fresh, "reuse" to reuse)) {
      summary += "$phase routes (radius > 0, identical / cases): " + routeCounts(list.filter { !it.case.extra }.map { it.o })
    }
    summary += "reuse order (index: shape sizeDp radiusDp spreadDp): " +
      order.mapIndexed { i, c -> "$i:${c.shape.name} ${c.sizeDp} ${c.radiusDp} ${c.spreadDp}" }.joinToString("; ")
    summary += generationIdObservations()
    summary += allocationCheck(density)
    summary += "API ${Build.VERSION.SDK_INT}, ${Build.MODEL}, density ${density.density}"
    summary += offTargetLines(listOf("fresh" to fresh.map { it.o }, "reuse" to reuse.map { it.o }))
    summary.forEach { Log.i(TAG, it) }

    writeResults(context, CSV_NAME, rows)
    writeResults(context, SUMMARY_NAME, summary)

    for (r in fresh + reuse) {
      if (TargetSkia.isDevice) assertTrue("not identical: ${r.csv()}", r.identical)
      assertEquals("stale pixels outside used: ${r.csv()}", 0, r.outsideNonZero)
      assertTrue("generation id unchanged: ${r.csv()}", r.genChanged)
    }
  }

  // ---------------------------------------------------------------------------------------------------------------

  /**
   * Rect paths, `Outline.Generic` (what a `GenericShape { addRect(…) }` gives): the whole box (`rectPath`) and the box
   * inset with fractional edges (`rectPathInset`), × 48 / 200 dp × radius 1 / 4 / 24 / 64 / 0 dp × spread 0 / 8 / −8
   * dp: 60 cases. Skia blurs a rect path with its analytic `BlurRect`, so every case takes the Skia route.
   */
  private val rectPathCases =
    buildList {
      val shapes = listOf(ShapeCase("rectPath", RectPathShape()), ShapeCase("rectPathInset", RectPathShape(0.2f)))
      for (shape in shapes) for (sizeDp in listOf(48, 200)) for (radiusDp in listOf(1, 4, 24, 64, 0)) {
        for (spreadDp in listOf(0, 8, -8)) add(Case(shape, sizeDp, radiusDp, spreadDp))
      }
    }

  /**
   * [rectPathCases], fresh (one builder per case) and reused (one builder and bitmap pair in a fixed shuffled order),
   * with the checks of [builderMatchesCompose] and the route asserted. Output: `rectpath-builder.csv` (the columns of
   * `builder.csv`) and `rectpath-builder-summary.txt`.
   */
  @Test
  fun rectPathsMatchCompose() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val density = Density(context)
    val fresh =
      rectPathCases.mapIndexed { i, c -> ShadowBitmapBuilder().use { b -> runCase(density, b, ShadowBitmaps(), c, "fresh", i) } }
    val order = rectPathCases.shuffled(Random(SEED))
    val reuse = ShadowBitmapBuilder().use { b ->
      val target = ShadowBitmaps()
      order.mapIndexed { i, c -> runCase(density, b, target, c, "reuse", i) }
    }
    val rows = ArrayList<String>()
    rows += HEADER
    for (r in fresh + reuse) {
      rows += r.csv()
      Log.i(TAG, r.csv())
    }
    val summary = ArrayList<String>()
    for ((phase, list) in listOf("fresh" to fresh, "reuse" to reuse)) {
      summary +=
        "rect paths $phase: identical ${list.count { it.identical }} / ${list.size}; routes (identical / cases): " +
          "${routeCounts(list.map { it.o })}; outside used non-zero ${list.count { it.outsideNonZero > 0 }}, " +
          "generation id unchanged ${list.count { !it.genChanged }}"
    }
    summary += "reuse order (index: shape sizeDp radiusDp spreadDp): " +
      order.mapIndexed { i, c -> "$i:${c.shape.name} ${c.sizeDp} ${c.radiusDp} ${c.spreadDp}" }.joinToString("; ")
    summary += "API ${Build.VERSION.SDK_INT}, ${Build.MODEL}, density ${density.density}"
    summary += offTargetLines(listOf("fresh" to fresh.map { it.o }, "reuse" to reuse.map { it.o }))
    summary.forEach { Log.i(TAG, it) }
    writeResults(context, RECT_PATH_CSV_NAME, rows)
    writeResults(context, RECT_PATH_SUMMARY_NAME, summary)

    for (r in fresh + reuse) {
      assertEquals("route: ${r.csv()}", ShadowBitmapBuilder.Route.Skia.name, r.route)
      if (TargetSkia.isDevice) assertTrue("not identical: ${r.csv()}", r.identical)
      assertEquals("stale pixels outside used: ${r.csv()}", 0, r.outsideNonZero)
      assertTrue("generation id unchanged: ${r.csv()}", r.genChanged)
    }
  }

  // ---------------------------------------------------------------------------------------------------------------

  /** A frame or edge-path case: [shape] on a square layout box of [sizeDp] (whole px), radius and spread in dp. */
  private class PathCase(val shape: ShapeCase, val sizeDp: Int, val radiusDp: Float, val spreadDp: Float) {
    fun size(density: Density) = with(density) { sizeDp.dp.roundToPx().toFloat() }.let { Size(it, it) }

    fun radius(density: Density) = with(density) { radiusDp.dp.toPx() }

    fun spread(density: Density) = with(density) { spreadDp.dp.toPx() }
  }

  /** One [PathCase] build: its CSV row (the columns of `builder.csv`), outcome and the model's decision. */
  private class PathRow(val phase: String, val case: PathCase, val csv: String, val o: Outcome, val model: FrameRouteModel.Decision)

  /**
   * - `frames` / `framesReuse`: [FrameShape] (counter-clockwise hole, and even-odd) × 48 / 200 dp × radius 1 / 4 / 24
   *   / 64 / 0 dp × spread 0 / 8 / −8 dp, fresh and through one builder and bitmap pair in a fixed shuffled order.
   * - `frameSweep`: both frames × 48 / 64 / 100 / 200 dp × radius 2 / 4 / 8 / 16 dp × 32 seeded spreads in eighths of
   *   a dp from −(a quarter of the side) to 12 dp: a negative spread makes the bitmap smaller than the blurred frame,
   *   so Skia's rect nine-patch is clipped by it.
   * - `edgePaths` / `edgePathsReuse`: every [NestedRectsEdgeShape] kind × 48 / 200 dp × radius 1 / 4 / 24 / 64 / 0 dp
   *   × spread 0 / 8 / −8 dp, fresh and reused.
   * - `frameHoles`: frames on 100 / 200 dp boxes whose hole, moved by the translate, rounds in to `2m + 3 − 1`, `2m + 3`
   *   or `2m + 3 + 1` px on one axis ([FrameRouteModel.holeFrame], fractional edges), × radius 1 / 4 / 8 / 16 / 24 dp ×
   *   spread 0 / −4 dp, both axes, both fill kinds.
   * - `frameEdges`: frames with fractional outer and inner edges (seeded), thin bands (a hole margin under 3 px) and a
   *   360 dp frame at radius 100 dp (σ past the 128 cap) with a hole above and below the threshold.
   * Every build is asserted identical (on the target Skia) with its route: the Skia route for radius 0, and with spread
   * > 0 for a path the builder takes for nested rects (Compose's fill and stroke are two draws), else the mask route;
   * at spread ≤ 0 the route of [FrameRouteModel] (the frame nine-patch, the mask route or the Skia route).
   */
  @Test
  fun framesMatchCompose() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val density = Density(context)
    val frames = listOf(ShapeCase("frameCcw", FrameShape(evenOdd = false)), ShapeCase("frameEvenOdd", FrameShape(evenOdd = true)))
    val nested = NestedRectsEdgeShape.KINDS + frames.associate { it.name to true }
    val radii = listOf(1f, 4f, 24f, 64f, 0f)
    val spreads = listOf(0f, 8f, -8f)
    fun grid(shapes: List<ShapeCase>) = buildList {
      for (shape in shapes) for (sizeDp in listOf(48, 200)) for (r in radii) for (s in spreads) add(PathCase(shape, sizeDp, r, s))
    }
    val frameCases = grid(frames)
    val random = Random(SEED)
    val sweepCases = buildList {
      for (shape in frames) for (sizeDp in listOf(48, 64, 100, 200)) for (r in listOf(2f, 4f, 8f, 16f)) {
        repeat(32) { add(PathCase(shape, sizeDp, r, random.nextInt(-sizeDp * 2, 97) / 8f)) }
      }
    }
    val edgeCases = grid(NestedRectsEdgeShape.KINDS.keys.map { ShapeCase(it, NestedRectsEdgeShape(it)) })
    val holeCases = buildList {
      var n = 0
      for (sizeDp in listOf(100, 200)) for (r in listOf(1f, 4f, 8f, 16f, 24f)) for (s in listOf(0f, -4f)) {
        val side = with(density) { sizeDp.dp.roundToPx().toFloat() }
        val radius = with(density) { r.dp.toPx() }
        val threshold = 2 * ObvidNative.builderBorder(radius) + 3
        for (axis in 0..1) for (k in -1..1) {
          val frac = HOLE_FRACTIONS[n % HOLE_FRACTIONS.size]
          val shape = FrameRouteModel.holeFrame(side, threshold, axis, k, frac, radius, radius, evenOdd = n % 2 == 1) ?: continue
          add(PathCase(ShapeCase("hole${"xy"[axis]}${if (k < 0) "Below" else if (k > 0) "Above" else "At"}", shape), sizeDp, r, s))
          n++
        }
      }
    }
    val edgeFrameCases = buildList {
      val random = Random(SEED + 1)
      fun f() = FRACTIONS[random.nextInt(FRACTIONS.size)]
      for (sizeDp in listOf(48, 100, 200)) for (r in listOf(1f, 4f, 16f, 24f)) {
        val side = with(density) { sizeDp.dp.roundToPx().toFloat() }
        repeat(3) {
          val outer = Rect(f(), f(), side - f(), side - f())
          val q = { floor(side * (0.05f + 0.3f * random.nextFloat())) }
          val inner = Rect(q() + f(), q() + f(), side - q() - f(), side - q() - f())
          add(PathCase(ShapeCase("fractional", RectsFrameShape(outer, inner, evenOdd = it == 1)), sizeDp, r, if (it == 2) -4f else 0f))
        }
        for (band in listOf(0.37f, 1.37f, 2.5f)) {
          val outer = Rect(0.5f, 0.25f, side - 0.5f, side - 0.75f)
          val inner = Rect(outer.left + band, outer.top + band, outer.right - band, outer.bottom - band)
          add(PathCase(ShapeCase("thinBand", RectsFrameShape(outer, inner)), sizeDp, r, 0f))
        }
      }
      // σ = 0.57735·r + 0.5 is capped at 128 from r ≈ 221 px: 100 dp is 262.5 px at density 2.625.
      val side = with(density) { 360.dp.roundToPx().toFloat() }
      for (inset in listOf(0.08f, 0.2f)) for (s in listOf(0f, -8f)) {
        val d = floor(side * inset) + 0.37f
        add(PathCase(ShapeCase("sigmaCap${(inset * 100).toInt()}", RectsFrameShape(Rect(0f, 0f, side, side), Rect(d, d, side - d, side - d))), 360, 100f, s))
      }
    }
    fun run(builder: ShadowBitmapBuilder, target: ShadowBitmaps, c: PathCase, phase: String, index: Int): PathRow {
      val size = c.size(density)
      val outline = c.shape.shape.createOutline(size, LayoutDirection.Ltr, density)
      // Every RectsFrameShape is two axis-aligned rect contours: the builder's superset takes it.
      val takenForNested = nested[c.shape.name] ?: true
      val radius = c.radius(density)
      val model =
        when {
          c.radiusDp <= 0f -> FrameRouteModel.Decision("Skia", "radius0")
          c.spreadDp > 0f -> FrameRouteModel.Decision(if (takenForNested) "Skia" else "Mask", "spread")
          else -> FrameRouteModel.decide(FrameRouteModel.canonicalPoints(outline), takenForNested, radius, radius, radius)
        }
      val o = measure(builder, target, outline, size, radius, c.spread(density))
      val csv =
        (listOf<Any>(
            Build.MODEL.replace(',', ' '),
            Build.VERSION.SDK_INT,
            phase,
            index,
            c.shape.name,
            c.sizeDp,
            size.width,
            fmt(c.radiusDp),
            c.radius(density),
            fmt(c.spreadDp),
            c.spread(density),
          ) + o.columns())
          .joinToString(",")
      return PathRow(phase, c, csv, o, model)
    }
    fun fresh(cases: List<PathCase>, phase: String) =
      cases.mapIndexed { i, c -> ShadowBitmapBuilder().use { b -> run(b, ShadowBitmaps(), c, phase, i) } }
    fun reused(cases: List<PathCase>, phase: String) = ShadowBitmapBuilder().use { b ->
      val target = ShadowBitmaps()
      cases.shuffled(Random(SEED)).mapIndexed { i, c -> run(b, target, c, phase, i) }
    }
    val phases =
      listOf(
        "frames" to fresh(frameCases, "frames"),
        "framesReuse" to reused(frameCases, "framesReuse"),
        "frameSweep" to fresh(sweepCases, "frameSweep"),
        "edgePaths" to fresh(edgeCases, "edgePaths"),
        "edgePathsReuse" to reused(edgeCases, "edgePathsReuse"),
        "frameHoles" to fresh(holeCases, "frameHoles"),
        "frameEdges" to fresh(edgeFrameCases, "frameEdges"),
      )
    val all = phases.flatMap { it.second }

    val allocations = frameAllocationCheck(density)
    val summary = ArrayList<String>()
    for ((phase, list) in phases) {
      val o = list.map { it.o }
      if (o.isEmpty()) continue
      summary +=
        "$phase: identical ${o.count { it.identical }} / ${o.size}, max diff ${o.maxOf { it.diff.max }}; routes (identical / " +
          "cases): ${routeCounts(o)}; outside used non-zero ${o.count { it.outsideNonZero > 0 }}, " +
          "generation id unchanged ${o.count { !it.genChanged }}"
      for ((name, group) in list.groupBy { it.case.shape.name }) {
        for ((label, part) in group.groupBy { spreadLabel(it.case) }.toSortedMap()) {
          val po = part.map { it.o }
          summary += "  $phase $name (nested rects ${nested[name] ?: true}) $label: identical ${po.count { it.identical }} / " +
            "${po.size}, max diff ${po.maxOf { it.diff.max }}; routes ${routeCounts(po)}"
        }
      }
    }
    for ((phase, list) in phases.filter { it.first == "frameHoles" || it.first == "frameEdges" }) {
      for ((hole, group) in list.filter { it.model.holeX != FrameRouteModel.NO_HOLE }.groupBy { it.model.holeLabel }.toSortedMap()) {
        val o = group.map { it.o }
        summary += "  $phase hole − (2m + 3) $hole: identical ${o.count { it.identical }} / ${o.size}; routes ${routeCounts(o)}"
      }
    }
    for ((kind, group) in all.groupBy { it.model.kind }.toSortedMap()) {
      val o = group.map { it.o }
      summary += "  model $kind: identical ${o.count { it.identical }} / ${o.size}; routes ${routeCounts(o)}"
    }
    for ((sizeDp, group) in phases.first { it.first == "frameSweep" }.second.groupBy { it.case.sizeDp }) {
      val o = group.map { it.o }
      summary += "  frameSweep ${sizeDp}dp: identical ${o.count { it.identical }} / ${o.size}, max diff " +
        "${o.maxOf { it.diff.max }}, differing pixels ${o.sumOf { it.diff.pixels }}, spreads ${fmt(group.minOf { it.case.spreadDp })} " +
        "to ${fmt(group.maxOf { it.case.spreadDp })} dp"
    }
    summary += "routes as expected ${all.count { it.o.route == it.model.route }} / ${all.size}"
    summary += allocations.first
    summary += "API ${Build.VERSION.SDK_INT}, ${Build.MODEL}, density ${density.density}"
    summary += offTargetLines(phases.map { (phase, list) -> phase to list.map { it.o } })
    summary.forEach { Log.i(TAG, it) }
    val rows = listOf(HEADER) + all.map { it.csv }
    all.filter { !it.o.identical }.take(MAX_LOGGED_ROWS).forEach { Log.i(TAG, it.csv) }
    writeResults(context, FRAMES_CSV_NAME, rows, InnerScreenCases.RESULTS_DIR2)
    writeResults(context, FRAMES_SUMMARY_NAME, summary, InnerScreenCases.RESULTS_DIR2)

    for (r in all) {
      assertEquals("route (${r.model.kind}): ${r.csv}", r.model.route, r.o.route)
      if (TargetSkia.isDevice) assertTrue("not identical: ${r.csv}", r.o.identical)
      assertEquals("stale pixels outside used: ${r.csv}", 0, r.o.outsideNonZero)
      assertTrue("generation id unchanged: ${r.csv}", r.o.genChanged)
    }
    allocations.second()
  }

  /**
   * Blur radii under 2.6 px (σ < 2: Skia's small blur, Obvid's NEON port on the whole-mask routes): the radii of
   * [SmallBlurCases] (px on every density, covering filter radii 1–4, and 0.5 / 0.9 dp as the rebuild benchmarks) ×
   * `mixed`, `morph` (the mask route), `rrect16` (the nine-patch, or its full mask where the small rect does not fit)
   * and `circle` (an oval's full mask; a simple round rect at +8 dp) × 48 / 200 dp × spread 0 / 8 / −8 dp; the
   * frames of [SmallBlurCases.frames] (counter-clockwise hole, and even-odd) on the same grid; and frames on a 100 dp
   * box whose moved hole rounds in to `2m + 3 − 1`, `2m + 3` or `2m + 3 + 1` px on one axis
   * ([FrameRouteModel.holeFrame]) at every radius × spread 0 / −4 dp. Fresh and through one builder and bitmap pair
   * in a fixed shuffled order. The checks of [builderMatchesCompose], the route (the mask route for the paths, a
   * round-rect route of Obvid's for the round rects, [FrameRouteModel]'s for the frames: the frame nine-patch, the
   * mask route below the threshold, the Skia route with spread > 0), and that the px radii give σ < 2 with every
   * border 1–4. Output: `smallblur-drop-builder.csv` (the columns of `builder.csv`
   * plus σ and the border) and `smallblur-drop-builder-summary.txt`.
   */
  @Test
  fun smallBlurMatchesCompose() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val density = Density(context)
    val radii = SmallBlurCases.radii(density.density)
    // (radius label, case)
    val cases = buildList {
      for (name in SmallBlurCases.SHAPES) for (sizeDp in listOf(48, 200)) for ((label, r) in radii) {
        for (s in listOf(0f, 8f, -8f)) add(label to PathCase(ShapeCase(name, catalogShape(name)), sizeDp, r, s))
      }
      for (sizeDp in listOf(48, 200)) for ((label, r) in radii) for (s in listOf(0f, 8f, -8f)) {
        for (frame in SmallBlurCases.frames()) add(label to PathCase(frame, sizeDp, r, s))
      }
      var n = 0
      val side = with(density) { SmallBlurCases.HOLE_SIDE_DP.dp.roundToPx().toFloat() }
      for ((label, r) in radii) for (s in SmallBlurCases.HOLE_SPREADS_DP) {
        val radius = with(density) { r.dp.toPx() }
        val threshold = 2 * ObvidNative.builderBorder(radius) + 3
        for (axis in 0..1) for (k in -1..1) {
          val frac = HOLE_FRACTIONS[n % HOLE_FRACTIONS.size]
          val shape = FrameRouteModel.holeFrame(side, threshold, axis, k, frac, radius, radius, evenOdd = n % 2 == 1) ?: continue
          val name = "hole${"xy"[axis]}${if (k < 0) "Below" else if (k > 0) "Above" else "At"}"
          add(label to PathCase(ShapeCase(name, shape), SmallBlurCases.HOLE_SIDE_DP, r, s))
          n++
        }
      }
    }
    // The model's route: null for a round rect (either round-rect route of Obvid's).
    class SmallRow(
      val case: PathCase,
      val label: String,
      val sigma: Float,
      val border: Int,
      val model: FrameRouteModel.Decision?,
      val csv: String,
      val o: Outcome,
    )
    fun run(builder: ShadowBitmapBuilder, target: ShadowBitmaps, labelled: Pair<String, PathCase>, phase: String, index: Int): SmallRow {
      val (label, c) = labelled
      val size = c.size(density)
      val outline = c.shape.shape.createOutline(size, LayoutDirection.Ltr, density)
      val radius = c.radius(density)
      val sigma = SmallBlurCases.sigma(radius)
      val border = ObvidNative.builderBorder(radius)
      val model =
        when (c.shape.name) {
          in SmallBlurCases.PATH_SHAPES -> FrameRouteModel.Decision(ShadowBitmapBuilder.Route.Mask.name, "path")
          in SmallBlurCases.ROUND_SHAPES -> null
          // Frames: the builder's nested-rects superset takes them; with spread > 0 the fill and stroke are two draws.
          else ->
            if (c.spreadDp > 0f) FrameRouteModel.Decision("Skia", "spread")
            else FrameRouteModel.decide(FrameRouteModel.canonicalPoints(outline), true, radius, radius, radius)
        }
      val o = measure(builder, target, outline, size, radius, c.spread(density))
      val csv =
        (listOf<Any>(
            Build.MODEL.replace(',', ' '),
            Build.VERSION.SDK_INT,
            phase,
            index,
            c.shape.name,
            c.sizeDp,
            size.width,
            fmt(c.radiusDp),
            radius,
            fmt(c.spreadDp),
            c.spread(density),
          ) + o.columns() + listOf(sigma, border))
          .joinToString(",")
      return SmallRow(c, label, sigma, border, model, csv, o)
    }
    val fresh = cases.mapIndexed { i, c -> ShadowBitmapBuilder().use { b -> run(b, ShadowBitmaps(), c, "fresh", i) } }
    val reuse = ShadowBitmapBuilder().use { b ->
      val target = ShadowBitmaps()
      cases.shuffled(Random(SEED)).mapIndexed { i, c -> run(b, target, c, "reuse", i) }
    }
    val all = fresh + reuse

    val summary = ArrayList<String>()
    for ((phase, list) in listOf("fresh" to fresh, "reuse" to reuse)) {
      val o = list.map { it.o }
      summary +=
        "small blur $phase: identical ${o.count { it.identical }} / ${o.size}, max diff ${o.maxOf { it.diff.max }}; routes " +
          "(identical / cases): ${routeCounts(o)}; outside used non-zero ${o.count { it.outsideNonZero > 0 }}, " +
          "generation id unchanged ${o.count { !it.genChanged }}"
      for ((label, group) in list.groupBy { it.label }) {
        val first = group.first()
        val go = group.map { it.o }
        summary += "  $phase $label (${first.case.radius(density)} px, σ ${first.sigma}, border ${first.border}): identical " +
          "${go.count { it.identical }} / ${go.size}; routes ${routeCounts(go)}"
      }
    }
    fun expected(r: SmallRow) = r.model?.let { it.route == r.o.route } ?: (r.o.route in SmallBlurCases.ROUND_ROUTES)
    for ((kind, group) in all.groupBy { it.model?.kind ?: "roundRect" }.toSortedMap()) {
      val go = group.map { it.o }
      summary += "  model $kind: identical ${go.count { it.identical }} / ${go.size}; routes ${routeCounts(go)}"
    }
    val holes = all.filter { (it.model?.holeX ?: FrameRouteModel.NO_HOLE) != FrameRouteModel.NO_HOLE }
    for ((key, group) in holes.groupBy { checkNotNull(it.model).holeLabel to it.border }.toList().sortedBy { it.first.toString() }) {
      val go = group.map { it.o }
      summary += "  hole − (2m + 3) ${key.first}, border ${key.second}: identical ${go.count { it.identical }} / ${go.size}; " +
        "routes ${routeCounts(go)}"
    }
    summary += "routes as expected ${all.count { expected(it) }} / ${all.size}"
    summary += "σ < 2 in ${all.count { it.sigma < 2f }} / ${all.size} builds, borders ${all.filter { it.sigma < 2f }.map { it.border }.toSortedSet()}"
    summary += "API ${Build.VERSION.SDK_INT}, ${Build.MODEL}, density ${density.density}"
    summary += offTargetLines(listOf("fresh" to fresh.map { it.o }, "reuse" to reuse.map { it.o }))
    summary.forEach { Log.i(TAG, it) }
    all.filter { !it.o.identical }.take(MAX_LOGGED_ROWS).forEach { Log.i(TAG, it.csv) }
    writeResults(context, SMALL_CSV_NAME, listOf(SMALL_HEADER) + all.map { it.csv })
    writeResults(context, SMALL_SUMMARY_NAME, summary)

    val pxRows = all.filter { it.label.startsWith("px") }
    assertTrue("px radii with σ ≥ 2: ${pxRows.filter { it.sigma >= 2f }.map { it.csv }}", pxRows.all { it.sigma < 2f })
    assertEquals("borders of the px radii", setOf(1, 2, 3, 4), pxRows.map { it.border }.toSet())
    for (r in all) {
      val model = r.model
      if (model != null) {
        assertEquals("route (${model.kind}): ${r.csv}", model.route, r.o.route)
      } else {
        assertTrue("route: ${r.csv}", r.o.route in SmallBlurCases.ROUND_ROUTES)
      }
      if (TargetSkia.isDevice) assertTrue("not identical: ${r.csv}", r.o.identical)
      assertEquals("stale pixels outside used: ${r.csv}", 0, r.o.outsideNonZero)
      assertTrue("generation id unchanged: ${r.csv}", r.o.genChanged)
    }
  }

  /**
   * Paths whose mask Skia forms differently from a fill inside the path's bounds ([MaskRouteGapCases]): inverse fills,
   * spread strokes up to 1 px wide (Skia's hairline) and spread strokes that are rects (Skia's `BlurRect`), at radius 1
   * px, 4 dp and 24 dp, fresh builders (`fresh`); and the inverse fills again with the full extent (`full`), which keeps
   * Compose's window for them: Compose's bitmap, at origin (0, 0); and [MaskRouteGapCases.spreadChangeCases] through one
   * builder, bitmap pair and kept outline per shape (`spreadChange`: the stroke test is kept per outline and spread).
   * Per case the route, the difference from Compose and
   * Skia's decision by those rules. Every case is asserted identical on the target Skia and on its route
   * ([MaskRouteGapCases.expectedDropRoute]: the Skia route for those paths, the mask route for the rest), with one
   * failure listing the counts per group; stale pixels and the generation id as in [builderMatchesCompose], and the
   * `full` builds' origin. Output: `maskgaps-drop-builder.csv` and `maskgaps-drop-builder-summary.txt`.
   */
  @Test
  fun maskRouteGapsMatchCompose() {
    val start = android.os.SystemClock.elapsedRealtime()
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val density = Density(context)
    val cases = MaskRouteGapCases.dropCases(density)
    class GapRow(val csv: String, val o: Outcome, val result: MaskRouteGapCases.Result, val originX: Int, val originY: Int)
    fun run(
      c: MaskRouteGapCases.DropCase,
      phase: String,
      builder: ShadowBitmapBuilder? = null,
      target: ShadowBitmaps = ShadowBitmaps(),
      kept: Outline? = null,
    ): GapRow {
      val outline = kept ?: c.outline(density)
      val radius = c.radius(density)
      val spread = c.spread(density)
      val local = checkNotNull(outlineParams(outline).path).asAndroidPath()
      val prediction = MaskRouteGapCases.predictDrop(local, radius, spread)
      val expectedRoute = MaskRouteGapCases.expectedDropRoute(local, radius, spread)
      val full = phase == "full"
      val o =
        if (builder != null) measure(builder, target, outline, c.size, radius, spread, full)
        else ShadowBitmapBuilder().use { b -> measure(b, target, outline, c.size, radius, spread, full) }
      val csv =
        (listOf<Any>(
            Build.MODEL.replace(',', ' '),
            Build.VERSION.SDK_INT,
            phase,
            c.gap,
              c.shape.name,
              c.sizePx,
              c.radiusLabel,
              c.radiusDp,
              radius,
              c.spreadLabel,
              c.spreadDp,
              spread,
              prediction.gap ?: "",
              prediction.rule,
              expectedRoute,
            ) + o.columns() + listOf(target.originX, target.originY))
            .joinToString(",")
      val result =
        MaskRouteGapCases.Result(
          c.gap,
          c.radiusLabel,
          c.spreadLabel,
          prediction.gap != null,
          o.route,
          o.identical,
          o.diff.max,
          o.diff.pixels,
          expectedRoute,
        )
      return GapRow(csv, o, result, target.originX, target.originY)
    }
    val fresh = cases.map { run(it, "fresh") }
    val full = cases.filter { it.gap == MaskRouteGapCases.INVERSE }.map { run(it, "full") }
    val spreadChange = ShadowBitmapBuilder().use { b ->
      val target = ShadowBitmaps()
      MaskRouteGapCases.spreadChangeCases(density).groupBy { it.shape.name }.values.flatMap { group ->
        val outline = group.first().outline(density)
        group.map { run(it, "spreadChange", b, target, outline) }
      }
    }
    val rows = fresh + full + spreadChange
    val seconds = (android.os.SystemClock.elapsedRealtime() - start) / 1000.0
    val results = rows.map { it.result }
    val summary = ArrayList<String>()
    summary += MaskRouteGapCases.summary(fresh.map { it.result })
    summary += "full extent, inverse fills: identical to Compose's bitmap ${full.count { it.o.identical }} / ${full.size}, " +
      "routes as expected ${full.count { it.result.routeOk }} / ${full.size}, origin not (0, 0) " +
      "${full.count { it.originX != 0 || it.originY != 0 }}"
    summary += "spread changes on a kept outline: identical ${spreadChange.count { it.o.identical }} / ${spreadChange.size}, " +
      "routes as expected ${spreadChange.count { it.result.routeOk }} / ${spreadChange.size}"
    summary += "outside used non-zero ${rows.count { it.o.outsideNonZero > 0 }}, generation id unchanged ${rows.count { !it.o.genChanged }}"
    summary += "API ${Build.VERSION.SDK_INT}, ${Build.MODEL}, density ${density.density}, " +
      String.format(java.util.Locale.US, "%.1f s", seconds)
    summary += offTargetLines(MaskRouteGapCases.GAPS.map { g -> g to rows.filter { it.result.gap == g }.map { it.o } })
    summary.forEach { Log.i(TAG, it) }
    rows.filter { !it.o.identical }.take(MAX_LOGGED_ROWS).forEach { Log.i(TAG, it.csv) }
    writeResults(context, GAPS_CSV_NAME, listOf(GAPS_HEADER) + rows.map { it.csv })
    writeResults(context, GAPS_SUMMARY_NAME, summary)

    for (r in rows) {
      assertEquals("stale pixels outside used: ${r.csv}", 0, r.o.outsideNonZero)
      assertTrue("generation id unchanged: ${r.csv}", r.o.genChanged)
    }
    for (r in full) assertTrue("full extent of an inverse fill not at Compose's origin: ${r.csv}", r.originX == 0 && r.originY == 0)
    MaskRouteGapCases.failure(results, GAPS_CSV_NAME, TargetSkia.isDevice)?.let { throw AssertionError(it) }
  }

  /** `radius 0`, or the spread's sign with radius > 0. */
  private fun spreadLabel(c: PathCase): String =
    when {
      c.radiusDp <= 0f -> "radius 0"
      c.spreadDp > 0f -> "radius > 0, spread > 0"
      c.spreadDp < 0f -> "radius > 0, spread < 0"
      else -> "radius > 0, spread 0"
    }

  /**
   * Java allocations per 10 rebuilds once grown, outline kept, radius alternating 24 / 23 dp and fixed: 200 dp frames
   * with spread 0 (the frame nine-patch) and 8 dp (the Skia route), against a 200 dp rect path with the same spreads
   * (the Skia route). The summary line, and the check: a frame allocates nothing with the radius fixed and, with it
   * changing, nothing on the frame nine-patch and on the Skia route what the rect path does (the `BlurMaskFilter` of a
   * changed radius); nothing for the frame test.
   */
  private fun frameAllocationCheck(density: Density): Pair<String, () -> Unit> {
    class Counts(val label: String, val route: String, val changing: Int, val fixed: Int)
    fun counts(label: String, shape: androidx.compose.ui.graphics.Shape, spreadDp: Float): Counts =
      ShadowBitmapBuilder().use { b ->
        val t = ShadowBitmaps()
        val c = PathCase(ShapeCase(label, shape), 200, 24f, spreadDp)
        val size = c.size(density)
        val outline = shape.createOutline(size, LayoutDirection.Ltr, density)
        val r1 = c.radius(density)
        val r2 = with(density) { 23.dp.toPx() }
        val s = c.spread(density)
        b.build(t, outline, size, r1, s)
        b.build(t, outline, size, r2, s)
        b.build(t, outline, size, r1, s)
        val changing = count { repeat(10) { b.build(t, outline, size, if (it % 2 == 0) r1 else r2, s) } }
        // The radius loop ends at r2: back to r1 outside the count, so the fixed loop changes nothing.
        b.build(t, outline, size, r1, s)
        val fixed = count { repeat(10) { b.build(t, outline, size, r1, s) } }
        Counts(label, b.route.name, changing, fixed)
      }
    val pairs =
      listOf(0f, 8f).map { s ->
        counts("frameCcw 200dp r24 s${fmt(s)}", FrameShape(evenOdd = false), s) to counts("rectPath 200dp r24 s${fmt(s)}", RectPathShape(), s)
      }
    val line =
      "allocations per 10 rebuilds once grown, outline kept: " +
        pairs.flatMap { listOf(it.first, it.second) }.joinToString("; ") {
          "${it.label} (${it.route}) ${it.changing} with the radius changing, ${it.fixed} with it fixed"
        } + " (Skia route: one BlurMaskFilter per radius change; the frame nine-patch: none)"
    val check = {
      for ((frame, rect) in pairs) {
        assertEquals("${frame.label}: allocations with the radius fixed", 0, frame.fixed)
        val changing = if (frame.route == ShadowBitmapBuilder.Route.Skia.name) rect.changing else 0
        assertEquals("${frame.label} (${frame.route}): allocations with the radius changing", changing, frame.changing)
      }
    }
    return line to check
  }

  // ---------------------------------------------------------------------------------------------------------------

  /** A round-rect route case: a round-rect shape on a `widthDp × heightDp` layout box (integer px, `roundToPx`). */
  private class RoundCase(val shape: ShapeCase, val widthDp: Int, val heightDp: Int, val radiusDp: Int, val spreadDp: Int) {
    fun size(density: Density) = with(density) { Size(widthDp.dp.roundToPx().toFloat(), heightDp.dp.roundToPx().toFloat()) }

    fun radius(density: Density) = with(density) { radiusDp.dp.toPx() }

    fun spread(density: Density) = with(density) { spreadDp.dp.toPx() }
  }

  /**
   * 360 cases: corners 4 / 16 / 40 / 6.3 dp ([roundRectShapes]) and `CircleShape` (`circle`) × layouts 48², 120², 200²,
   * 360², 360 × 48 and 48 × 200 dp × radius 1 / 4 / 24 / 64 dp × spread 0 / 8 / −8 dp: 288 round rects and 72 circles.
   * At density 2.625 both sides of Skia's nine-patch test occur (`W = 2·(ceil(corner) + border) + 1`
   * against the rect's width), PlanGauss from its smallest windows (1 dp is σ ≈ 2.02, just above Skia's small blur;
   * σ < 2 is [smallBlurMatchesCompose]'s) up, and setRectXY's scaling (40 dp corners on 48 dp sides). Circles: at spread 0 and −8 dp on square layouts mostly ovals (float rounding can leave one simple),
   * the whole mask either way; simple round rects at +8 dp and on the oblong layouts.
   */
  private val roundCases =
    buildList {
      val shapes = roundRectShapes() + ShapeCase(CIRCLE, catalogShape("circle"))
      for (shape in shapes) for ((wDp, hDp) in ROUND_SIZES) for (radiusDp in listOf(1, 4, 24, 64)) {
        for (spreadDp in listOf(0, 8, -8)) add(RoundCase(shape, wDp, hDp, radiusDp, spreadDp))
      }
    }

  @Test
  fun roundRectRoutesMatchCompose() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val density = Density(context)

    fun run(builder: ShadowBitmapBuilder, target: ShadowBitmaps, c: RoundCase, phase: String, index: Int): Triple<String, Outcome, RoundCase> {
      val size = c.size(density)
      val outline = c.shape.shape.createOutline(size, LayoutDirection.Ltr, density)
      val o = measure(builder, target, outline, size, c.radius(density), c.spread(density))
      val csv =
        (listOf<Any>(
            Build.MODEL.replace(',', ' '),
            Build.VERSION.SDK_INT,
            phase,
            index,
            c.shape.name,
            c.widthDp,
            c.heightDp,
            size.width,
            size.height,
            c.radiusDp,
            c.radius(density),
            c.spreadDp,
            c.spread(density),
          ) + o.columns())
          .joinToString(",")
      return Triple(csv, o, c)
    }

    val fresh = roundCases.mapIndexed { i, c -> ShadowBitmapBuilder().use { b -> run(b, ShadowBitmaps(), c, "fresh", i) } }
    val order = roundCases.shuffled(Random(SEED))
    val reuse = ShadowBitmapBuilder().use { b ->
      val target = ShadowBitmaps()
      order.mapIndexed { i, c -> run(b, target, c, "reuse", i) }
    }
    val rows = ArrayList<String>()
    rows += ROUND_HEADER
    for ((csv, _, _) in fresh + reuse) {
      rows += csv
      Log.i(TAG, csv)
    }
    val summary = ArrayList<String>()
    for ((phase, results) in listOf("fresh" to fresh, "reuse" to reuse)) {
      val list = results.map { it.second }
      val (circles, rrects) = results.partition { it.third.shape.name == CIRCLE }
      summary +=
        "$phase: identical ${list.count { it.identical }} / ${list.size}; routes (identical / cases): ${routeCounts(list)}; " +
          "round rects: ${routeCounts(rrects.map { it.second })}; circles: ${routeCounts(circles.map { it.second })}; " +
          "outside used non-zero ${list.count { it.outsideNonZero > 0 }}, generation id unchanged ${list.count { !it.genChanged }}"
    }
    summary += "reuse order (index: shape widthDp heightDp radiusDp spreadDp): " +
      order.mapIndexed { i, c -> "$i:${c.shape.name} ${c.widthDp} ${c.heightDp} ${c.radiusDp} ${c.spreadDp}" }.joinToString("; ")
    summary += roundAllocationCheck(density)
    // The round-rect routes run on every API level with Android 16–17's nine-patch geometry (class B).
    summary +=
      "API ${Build.VERSION.SDK_INT}, Skia round-rect class B, ${Build.MODEL}, " +
        "density ${density.density}; round-rect routes enabled: nine-patch true, full mask true"
    summary += offTargetLines(listOf("fresh" to fresh.map { it.second }, "reuse" to reuse.map { it.second }))
    summary.forEach { Log.i(TAG, it) }
    writeResults(context, ROUND_CSV_NAME, rows)
    writeResults(context, ROUND_SUMMARY_NAME, summary)

    for ((csv, o, _) in fresh + reuse) {
      if (TargetSkia.isDevice) assertTrue("not identical: $csv", o.identical)
      assertEquals("stale pixels outside used: $csv", 0, o.outsideNonZero)
      assertTrue("generation id unchanged: $csv", o.genChanged)
    }
    // Both round-rect routes are exercised and no case is left to Skia (every case has radius > 0, corners > 0 and
    // fits Skia's limits).
    val freshRoutes = fresh.map { it.second.route }
    assertTrue("no nine-patch case", "NinePatch" in freshRoutes)
    assertTrue("no full-mask case", "MaskRoundRect" in freshRoutes)
    assertTrue("a Skia-route case", "Skia" !in freshRoutes)
  }

  /**
   * Java allocations per rebuild once grown on the round-rect routes: 200 dp `rrect16` r24 s8 (nine-patch) and 48 dp
   * r24 s0 (full mask), the radius alternating 24 / 23 dp, after a warm-up.
   */
  private fun roundAllocationCheck(density: Density): String {
    val shape = roundRectShapes().first { it.name == "rrect16" }
    val parts = ArrayList<String>()
    for ((sizeDp, spreadDp) in listOf(200 to 8, 48 to 0)) {
      ShadowBitmapBuilder().use { b ->
        val t = ShadowBitmaps()
        val c = RoundCase(shape, sizeDp, sizeDp, 24, spreadDp)
        val size = c.size(density)
        val outline = shape.shape.createOutline(size, LayoutDirection.Ltr, density)
        val r1 = c.radius(density)
        val r2 = with(density) { 23.dp.toPx() }
        val s = c.spread(density)
        b.build(t, outline, size, r1, s)
        b.build(t, outline, size, r2, s)
        // The nine-patch keeps its blurred small round rect from the second build on, sized for that
        // radius: a third build at the first radius grows it to both.
        b.build(t, outline, size, r1, s)
        val n = count { repeat(10) { b.build(t, outline, size, if (it % 2 == 0) r1 else r2, s) } }
        val same = count { repeat(10) { b.build(t, outline, size, r1, s) } }
        parts += "rrect16 ${sizeDp}dp r24 s$spreadDp (${b.route}) $n allocations / 10 rebuilds with the radius changing, $same with it fixed"
      }
    }
    return "allocations (round rect): " + parts.joinToString("; ")
  }

  private fun geometry(density: Density, c: Case) = CaseGeometry(density, c.sizeDp, c.radiusDp, c.spreadDp)

  private fun runCase(
    density: Density,
    builder: ShadowBitmapBuilder,
    target: ShadowBitmaps,
    c: Case,
    phase: String,
    index: Int,
  ): Row {
    val g = geometry(density, c)
    val outline = c.shape.shape.createOutline(g.size, LayoutDirection.Ltr, density)
    return Row(phase, index, c, g, measure(builder, target, outline, g.size, g.radius, g.spread))
  }

  /** One build of [outline] by [builder] into [target] ([full]: the full extent), against reference A. */
  private fun measure(
    builder: ShadowBitmapBuilder,
    target: ShadowBitmaps,
    outline: Outline,
    size: Size,
    radius: Float,
    spread: Float,
    full: Boolean = false,
  ): Outcome {
    val ref = ComposeShadowReference.referenceA(outline, size, radius, spread).asAndroidBitmap()
    val before = target.bitmap
    val genBefore = before?.generationId
    builder.build(target, outline, size, radius, spread, full = full)
    val bmp = target.bitmap!!
    val grew = before !== bmp
    val genChanged = grew || bmp.generationId != genBefore
    val w = bmp.width
    val h = bmp.height
    check(w == ref.width && h == ref.height) { "used ${w}x$h vs reference ${ref.width}x${ref.height}" }
    val a = alphaOf(ref, ref.width, ref.height)
    val obvid = alphaOf(bmp, w, h)
    val d = ScreenCases.diffAlpha(a, obvid)
    val all = alphaOf(bmp, bmp.width, bmp.height)
    var outside = 0
    for (y in 0 until bmp.height) for (x in 0 until bmp.width) {
      if ((x >= w || y >= h) && all[y * bmp.width + x] != 0) outside++
    }
    return Outcome(builder.route.name, w, h, bmp.width, bmp.height, d, outside, genChanged, grew)
  }

  /**
   * Below the target Skia, per phase: the difference from the device's Compose in all and per route; nothing on the
   * target.
   */
  private fun offTargetLines(phases: List<Pair<String, List<Outcome>>>): List<String> {
    if (TargetSkia.isDevice) return emptyList()
    val out = ArrayList<String>()
    for ((phase, list) in phases) {
      out += "$phase ${TargetSkia.offTargetLabel} (alpha): " + list.tally()
      for ((route, group) in list.groupBy { it.route }) out += "  $route: " + group.tally()
    }
    return out
  }

  private fun List<Outcome>.tally() = DiffTally().also { t -> forEach { t.add(it.diff) } }

  /** `Route identical / cases` for every route, in [ShadowBitmapBuilder.Route] order. */
  private fun routeCounts(list: List<Outcome>): String =
    ShadowBitmapBuilder.Route.entries.joinToString(", ") { r ->
      "${r.name} ${list.count { it.identical && it.route == r.name }} / ${list.count { it.route == r.name }}"
    }

  /** Does a software `Canvas` draw (no native call) bump the generation id? (Logged, not asserted.) */
  private fun generationIdObservations(): String {
    val b = Bitmap.createBitmap(64, 64, Bitmap.Config.ALPHA_8)
    val c = android.graphics.Canvas(b)
    val g0 = b.generationId
    c.drawRect(4f, 4f, 40f, 40f, android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG))
    val g1 = b.generationId
    c.drawRect(8f, 8f, 20f, 20f, android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG))
    val g2 = b.generationId
    b.recycle()
    return "generation id: software Canvas draws changed it ${g1 != g0 && g2 != g1} ($g0 -> $g1 -> $g2)"
  }

  /**
   * Java allocations per rebuild once grown (thread alloc counter), for a mask-route and a Skia-route case with the
   * radius alternating between two values, after a warm-up at the largest size. A positive control checks the
   * counter works.
   */
  private fun allocationCheck(density: Density): String {
    val sink = ArrayList<Any>(100)
    val control = count { repeat(100) { sink += IntArray(4) } }
    val parts = ArrayList<String>()
    for (shapeName in listOf("morph", "mixed", "rrect16")) {
      val sc = shapes.first { it.name == shapeName }
      ShadowBitmapBuilder().use { b ->
        val t = ShadowBitmaps()
        val g = geometry(density, Case(sc, 200, 24, 8))
        val outline = sc.shape.createOutline(g.size, LayoutDirection.Ltr, density)
        val r2 = with(density) { 23.dp.toPx() }
        b.build(t, outline, g.size, g.radius, g.spread)
        b.build(t, outline, g.size, r2, g.spread)
        // The nine-patch keeps its blurred small round rect from the second build on, sized for that
        // radius: a third build at the first radius grows it to both.
        b.build(t, outline, g.size, g.radius, g.spread)
        val n = count { repeat(10) { b.build(t, outline, g.size, if (it % 2 == 0) g.radius else r2, g.spread) } }
        val same = count { repeat(10) { b.build(t, outline, g.size, g.radius, g.spread) } }
        parts += "$shapeName (${b.route}) $n allocations / 10 rebuilds with the radius changing, $same with it fixed"
      }
    }
    return "allocations: control ${control} for 100 IntArrays; " + parts.joinToString("; ") +
      " (Skia route: one BlurMaskFilter per radius change)"
  }

  /** Java allocations on this thread while [block] runs (the thread alloc counter). */
  @Suppress("DEPRECATION")
  private fun count(block: () -> Unit): Int {
    Debug.resetThreadAllocCount()
    Debug.startAllocCounting()
    block()
    Debug.stopAllocCounting()
    return Debug.getThreadAllocCount()
  }

  /** One build against reference A. */
  private class Outcome(
    val route: String,
    val w: Int,
    val h: Int,
    val capW: Int,
    val capH: Int,
    val diff: ScreenCases.Diff,
    val outsideNonZero: Int,
    val genChanged: Boolean,
    val grew: Boolean,
  ) {
    val identical get() = diff.identical

    /** The CSV columns from `route` on. */
    fun columns(): List<Any> =
      listOf(
        route,
        w,
        h,
        capW,
        capH,
        if (identical) 1 else 0,
        diff.max,
        diff.pixels,
        outsideNonZero,
        if (genChanged) 1 else 0,
        if (grew) 1 else 0,
      )
  }

  private class Row(val phase: String, val index: Int, val case: Case, val g: CaseGeometry, val o: Outcome) {
    val route get() = o.route
    val identical get() = o.identical
    val outsideNonZero get() = o.outsideNonZero
    val genChanged get() = o.genChanged

    fun csv() =
      (listOf<Any>(
          Build.MODEL.replace(',', ' '),
          Build.VERSION.SDK_INT,
          phase,
          index,
          case.shape.name,
          case.sizeDp,
          g.size.width,
          case.radiusDp,
          g.radius,
          case.spreadDp,
          g.spread,
        ) + o.columns())
        .joinToString(",")
  }

  private companion object {
    const val TAG = "FS-Builder"
    const val CSV_NAME = "builder.csv"
    const val SUMMARY_NAME = "builder-summary.txt"
    const val SEED = 20261119
    const val HEADER =
      "model,api,phase,index,shape,sizeDp,sizePx,radiusDp,radiusPx,spreadDp,spreadPx,route,usedW,usedH,capW,capH," +
        "identical,maxDiff,diffPixels,outsideNonZero,genChanged,grew"
    const val RECT_PATH_CSV_NAME = "rectpath-builder.csv"
    const val RECT_PATH_SUMMARY_NAME = "rectpath-builder-summary.txt"
    const val FRAMES_CSV_NAME = "frames-drop-builder.csv"
    const val FRAMES_SUMMARY_NAME = "frames-drop-builder-summary.txt"
    const val MAX_LOGGED_ROWS = 50
    const val SMALL_CSV_NAME = "smallblur-drop-builder.csv"
    const val SMALL_SUMMARY_NAME = "smallblur-drop-builder-summary.txt"
    const val SMALL_HEADER = "$HEADER,sigma,border"
    const val GAPS_CSV_NAME = "maskgaps-drop-builder.csv"
    const val GAPS_SUMMARY_NAME = "maskgaps-drop-builder-summary.txt"
    const val GAPS_HEADER =
      "model,api,phase,gap,shape,sizePx,radiusLabel,radiusDp,radiusPx,spreadLabel,spreadDp,spreadPx,predictedGap,skiaRule," +
        "expectedRoute,route,usedW,usedH,capW,capH,identical,maxDiff,diffPixels,outsideNonZero,genChanged,grew,originX,originY"
    const val ROUND_CSV_NAME = "roundrect-builder.csv"
    const val ROUND_SUMMARY_NAME = "roundrect-builder-summary.txt"
    const val ROUND_HEADER =
      "model,api,phase,index,shape,widthDp,heightDp,widthPx,heightPx,radiusDp,radiusPx,spreadDp,spreadPx,route,usedW," +
        "usedH,capW,capH,identical,maxDiff,diffPixels,outsideNonZero,genChanged,grew"
    val ROUND_SIZES = listOf(48 to 48, 120 to 120, 200 to 200, 360 to 360, 360 to 48, 48 to 200)
    const val CIRCLE = "circle"

    /** [FrameRouteModel.holeFrame]'s fractions of the moved hole's left and top edges, cycled. */
    val HOLE_FRACTIONS = listOf(0f, 0.25f, 0.5f, 0.37f)

    /** Fractional frame edges in px. */
    val FRACTIONS = listOf(0.37f, 0.61f, 0.25f, 0.5f, 0.125f, 0.875f)

    fun fmt(f: Float) = if (f == f.toInt().toFloat()) f.toInt().toString() else f.toString()
  }
}
