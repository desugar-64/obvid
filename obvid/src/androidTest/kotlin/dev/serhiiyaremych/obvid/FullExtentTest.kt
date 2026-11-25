// Copyright 2026, Serhii Yaremych (desugar-64) and the Obvid project contributors
// SPDX-License-Identifier: Apache-2.0

package dev.serhiiyaremych.obvid

import android.graphics.Bitmap
import android.os.Build
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas as ComposeCanvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.LinearGradientShader
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.PaintingStyle
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.serhiiyaremych.obvid.ScreenCases.showOverLockScreen
import dev.serhiiyaremych.obvid.builder.ShadowBitmapBuilder
import dev.serhiiyaremych.obvid.builder.ShadowBitmaps
import dev.serhiiyaremych.obvid.fixtures.BlurFilter
import dev.serhiiyaremych.obvid.fixtures.ComposeShadowReference
import dev.serhiiyaremych.obvid.fixtures.FrameShape
import dev.serhiiyaremych.obvid.fixtures.ShapeCase
import dev.serhiiyaremych.obvid.fixtures.alphaOf
import dev.serhiiyaremych.obvid.fixtures.catalogShape
import dev.serhiiyaremych.obvid.fixtures.configureShadow
import dev.serhiiyaremych.obvid.fixtures.outlineParams
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.ceil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * [ShadowExtent.Full]: the drop shadow's bitmap against Android's own blur on a canvas large enough that nothing is
 * cut, and the switch of [LocalShadowExtent] at runtime. The default extent is covered by the other tests.
 *
 * [fullExtentMatchesAndroid]: per case (round rect `rrect16` (nine-patch), `mixed` corners (path), `circle` (oval
 * full mask, nine-patch with spread), `morph` (path), a frame (frame nine-patch; Skia's nested rects with spread) and
 * `rect` (the device Skia's route) × 48 / 200 dp × radius 4 / 24 / 36 dp × spread 0 / 8 dp, plus a 320 × 56 dp pill
 * at radius 24 / 36 dp × spread 0 / 4 dp), one fresh builder with `full = true`, then:
 * - ground truth A: Compose's draw calls (`createOuterShadowBitmap`, with `BlurMaskFilter(radius, NORMAL)`) on a
 *   software canvas the size of Obvid's bitmap plus [EXTRA] px on the right and bottom, after the canvas translate by
 *   Obvid's bitmap's origin (the canvas that the builder emulates). Its `[0, w) × [0, h)` must equal Obvid's bitmap
 *   exactly, and its extra columns and rows must be 0 (nothing cut on the right and bottom);
 * - ground truth B: the same on a canvas grown by [EXTRA] px on every side (translated by [EXTRA] more): everything
 *   outside Obvid's window must be 0 (nothing cut on the left and top either; compared for 0 only, as the extra
 *   translate can round a coordinate differently);
 * - Obvid's bitmap's outermost ring is all 0;
 * - recorded only: inside Compose's window, Obvid's pixels against Compose's own bitmap (reference A of the other
 *   tests), and the bitmap's pixels against Compose's (`w·h / (cw·ch)`).
 * Identity and the zeros of A and B are asserted on the target Skia ([TargetSkia.isDevice]); below it the summary
 * records the difference from the device's Skia (and its zeros), and only the outer ring of Obvid's own routes (not the
 * device Skia's) is asserted. Output (subdir [RESULTS_DIR]): `full-extent-builder.csv`,
 * `full-extent-builder-summary.txt`.
 *
 * [extentSwitchRebuilds]: a live node under `LocalShadowExtent` switched Compose → Full → Compose, for both overloads:
 * one build per switch, one store entry, the bitmap's size and origin of each extent; and a store keeps a Compose
 * and a Full entry of one geometry apart. Output: `full-extent-switch-summary.txt`.
 *
 * [smallBlurFullExtentMatchesAndroid]: [fullExtentMatchesAndroid]'s checks at blur radii under 2.6 px (σ < 2, Skia's
 * small blur), with the route asserted. Output: `full-extent-smallblur.csv`, `full-extent-smallblur-summary.txt`.
 *
 * [maskRouteGapsFullExtentMatchAndroid]: [fullExtentMatchesAndroid]'s checks for the spread strokes left to the Skia
 * route ([MaskRouteGapCases]: hairlines, stroke rects), with the route asserted. Output: `full-extent-maskgaps.csv`,
 * `full-extent-maskgaps-summary.txt`.
 */
@RunWith(AndroidJUnit4::class)
class FullExtentTest {

  @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

  /** A shape on a box of [widthDp] × [heightDp] (whole px), or of [sizePx] square when it is > 0 (widths then 0). */
  private class Case(
    val shape: ShapeCase,
    val widthDp: Int,
    val heightDp: Int,
    val radiusDp: Float,
    val spreadDp: Float,
    val sizePx: Float = 0f,
  ) {
    val label
      get() = "${shape.name} ${if (sizePx > 0f) "${fmt(sizePx)}px" else "${widthDp}x$heightDp"} r${fmt(radiusDp)} s${fmt(spreadDp)}"

    fun size(density: Density): Size =
      if (sizePx > 0f) Size(sizePx, sizePx)
      else with(density) { Size(widthDp.dp.roundToPx().toFloat(), heightDp.dp.roundToPx().toFloat()) }
  }

  private val cases =
    buildList {
      val shapes =
        listOf("rrect16", "mixed", "circle", "morph").map { ShapeCase(it, catalogShape(it)) } +
          ShapeCase("frame", FrameShape(evenOdd = false)) +
          ShapeCase("rect", catalogShape("rect"))
      for (shape in shapes) for (sizeDp in listOf(48, 200)) for (radiusDp in listOf(4, 24, 36)) {
        for (spreadDp in listOf(0, 8)) add(Case(shape, sizeDp, sizeDp, radiusDp.toFloat(), spreadDp.toFloat()))
      }
      val pill = ShapeCase("pill", RoundedCornerShape(50))
      for (radiusDp in listOf(24, 36)) for (spreadDp in listOf(0, 4)) add(Case(pill, 320, 56, radiusDp.toFloat(), spreadDp.toFloat()))
    }

  /** The shapes of [cases] that take routes of Obvid's (not `rect`, which keeps the device Skia's). */
  private fun smallBlurShapes(): List<ShapeCase> =
    listOf("rrect16", "mixed", "circle", "morph").map { ShapeCase(it, catalogShape(it)) } +
      ShapeCase("frame", FrameShape(evenOdd = false))

  private class Outcome(
    val case: Case,
    val route: String,
    val w: Int,
    val h: Int,
    val originX: Int,
    val originY: Int,
    val composeW: Int,
    val composeH: Int,
    val truth: ScreenCases.Diff,
    val extraNonZeroA: Int,
    val outsideNonZeroB: Int,
    val ringNonZero: Int,
    val insideCompose: ScreenCases.Diff,
  ) {
    val pixelRatio
      get() = w.toDouble() * h / (composeW.toDouble() * composeH)

    fun csv(): String =
      listOf<Any>(
          Build.MODEL.replace(',', ' '),
          Build.VERSION.SDK_INT,
          case.shape.name,
          case.widthDp,
          case.heightDp,
          fmt(case.radiusDp),
          fmt(case.spreadDp),
          route,
          w,
          h,
          originX,
          originY,
          composeW,
          composeH,
          String.format(Locale.US, "%.3f", pixelRatio),
          if (truth.identical) 1 else 0,
          truth.max,
          truth.pixels,
          extraNonZeroA,
          outsideNonZeroB,
          ringNonZero,
          insideCompose.max,
          insideCompose.pixels,
        )
        .joinToString(",")
  }

  @Test
  fun fullExtentMatchesAndroid() {
    check(NativeShadows.isAvailable)
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val density = Density(context)
    val outcomes = cases.map { c -> ShadowBitmapBuilder().use { b -> runCase(density, b, c) } }
    report(density, outcomes, CSV_NAME, SUMMARY_NAME)
  }

  /**
   * [fullExtentMatchesAndroid]'s checks at blur radii under 2.6 px (σ < 2, Skia's small blur): the radii of
   * [SmallBlurCases] (px on every density, covering filter radii 1–4, and 0.5 / 0.9 dp) × `rrect16`, `mixed`,
   * `circle`, `morph` and the frame × 48 / 200 dp × spread 0 / 8 dp. Also asserted: the route (the mask route for the
   * paths, a round-rect route of Obvid's for the round rects, [FrameRouteModel]'s for the frame, moved by the full
   * extent's translate, and the Skia route for it with spread), and that the px radii give σ < 2 with every border
   * 1–4. Output (subdir [RESULTS_DIR]): `full-extent-smallblur.csv`, `full-extent-smallblur-summary.txt`.
   */
  @Test
  fun smallBlurFullExtentMatchesAndroid() {
    check(NativeShadows.isAvailable)
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val density = Density(context)
    val radii = SmallBlurCases.radii(density.density)
    val smallCases = buildList {
      for (shape in smallBlurShapes()) for (sizeDp in listOf(48, 200)) for ((_, r) in radii) {
        for (spreadDp in listOf(0, 8)) add(Case(shape, sizeDp, sizeDp, r, spreadDp.toFloat()))
      }
    }
    val pxRadii = radii.filter { it.first.startsWith("px") }.map { it.second }.toSet()
    val outcomes = smallCases.map { c -> ShadowBitmapBuilder().use { b -> runCase(density, b, c) } }
    val expected =
      outcomes.associateWith { o ->
        val c = o.case
        val radius = with(density) { c.radiusDp.dp.toPx() }
        when (c.shape.name) {
          in SmallBlurCases.PATH_SHAPES -> ShadowBitmapBuilder.Route.Mask.name
          in SmallBlurCases.ROUND_SHAPES -> null
          else ->
            if (c.spreadDp > 0f) {
              ShadowBitmapBuilder.Route.Skia.name
            } else {
              // The frame drawn under the window's translate (−origin) and Compose's translate by the radius.
              val size = c.size(density)
              val outline = c.shape.shape.createOutline(size, LayoutDirection.Ltr, density)
              val tx = (-o.originX).toFloat() + radius
              val ty = (-o.originY).toFloat() + radius
              FrameRouteModel.decide(FrameRouteModel.canonicalPoints(outline), true, tx, ty, radius).route
            }
        }
      }
    fun sigma(c: Case) = SmallBlurCases.sigma(with(density) { c.radiusDp.dp.toPx() })
    fun border(c: Case) = ObvidNative.builderBorder(with(density) { c.radiusDp.dp.toPx() })
    val extra =
      listOf(
        "routes as expected ${outcomes.count { o -> expected.getValue(o)?.let { it == o.route } ?: (o.route in SmallBlurCases.ROUND_ROUTES) }} / " +
          "${outcomes.size}",
        "σ < 2 in ${outcomes.count { sigma(it.case) < 2f }} / ${outcomes.size} builds, borders " +
          "${outcomes.filter { sigma(it.case) < 2f }.map { border(it.case) }.toSortedSet()}",
      )
    report(density, outcomes, SMALL_CSV_NAME, SMALL_SUMMARY_NAME, extra)

    val px = smallCases.filter { it.radiusDp in pxRadii }
    assertTrue("px radii with σ ≥ 2: ${px.filter { sigma(it) >= 2f }.map { it.label }}", px.all { sigma(it) < 2f })
    assertEquals("borders of the px radii", setOf(1, 2, 3, 4), px.map { border(it) }.toSet())
    for (o in outcomes) {
      val route = expected.getValue(o)
      if (route != null) {
        assertEquals("route: ${o.csv()}", route, o.route)
      } else {
        assertTrue("route: ${o.csv()}", o.route in SmallBlurCases.ROUND_ROUTES)
      }
    }
  }

  /**
   * [fullExtentMatchesAndroid]'s checks for the drop cases of [MaskRouteGapCases] other than the inverse fills: spread
   * strokes up to 1 px wide, which Skia draws as hairlines (the window takes the path's bounds outset by 1 px for them),
   * and spread strokes that may be rects, all on the Skia route; and the thin strokes just over 1 px on the mask route
   * ([MaskRouteGapCases.expectedDropRoute], asserted). The inverse fills keep Compose's window with the full extent (no
   * window holds their shadow), checked by `ShadowBitmapBuilderTest.maskRouteGapsMatchCompose`. Then two builds through
   * one builder and one bitmap pair (shapes `reuse:…`): `pointQuad` (radius 1 px, spread 20 px: the Skia route), then a
   * filled triangle (48 dp, radius 4 dp, spread 8 dp: the mask route), so the second build follows one that left the
   * mask route. Output (subdir [RESULTS_DIR]): `full-extent-maskgaps.csv`, `full-extent-maskgaps-summary.txt`.
   */
  @Test
  fun maskRouteGapsFullExtentMatchAndroid() {
    check(NativeShadows.isAvailable)
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val density = Density(context)
    val gapCases = MaskRouteGapCases.dropCases(density).filter { it.gap != MaskRouteGapCases.INVERSE }
    val cases = gapCases.map { g -> Case(g.shape, 0, 0, g.radiusDp, g.spreadDp, g.sizePx) }
    val d = density.density
    val pointQuad = MaskRouteGapCases.pathStrokeShapes().first { it.first.name == "pointQuad" }
    val reuseCases =
      listOf(
        Case(ShapeCase("reuse:pointQuad", pointQuad.first.shape), 0, 0, MaskRouteGapCases.dpOf(1f, d), MaskRouteGapCases.dpOf(pointQuad.second.first(), d), MaskRouteGapCases.PATH_BOX_PX),
        Case(ShapeCase("reuse:triangle", TriangleStarShape(curved = false, android.graphics.Path.FillType.WINDING)), 48, 48, 4f, 8f),
      )
    val fresh = cases.map { c -> ShadowBitmapBuilder().use { b -> runCase(density, b, c) } }
    val reused = ShadowBitmapBuilder().use { b ->
      val target = ShadowBitmaps()
      reuseCases.map { c -> runCase(density, b, c, target) }
    }
    val outcomes = fresh + reused
    val expected =
      (cases + reuseCases).map { c ->
        val outline = c.shape.shape.createOutline(c.size(density), LayoutDirection.Ltr, density)
        val local = checkNotNull(outlineParams(outline).path).asAndroidPath()
        MaskRouteGapCases.expectedDropRoute(local, with(density) { c.radiusDp.dp.toPx() }, with(density) { c.spreadDp.dp.toPx() })
      }
    val ok = outcomes.indices.filter { outcomes[it].route == expected[it] }
    val groups = gapCases.map { it.gap } + reuseCases.map { "reuse" }
    val extra =
      listOf("routes as expected ${ok.size} / ${outcomes.size}") +
        outcomes.indices.groupBy { groups[it] }.map { (gap, idx) ->
          "  $gap: identical to Android's blur ${idx.count { outcomes[it].truth.identical }} / ${idx.size}, routes as expected " +
            "${idx.count { it in ok }} / ${idx.size}"
        }
    report(density, outcomes, MASKGAPS_CSV_NAME, MASKGAPS_SUMMARY_NAME, extra)
    for (i in outcomes.indices) assertEquals("route: ${outcomes[i].csv()}", expected[i], outcomes[i].route)
  }

  /** Writes [outcomes]' rows and summary (plus [extra] lines) and asserts [fullExtentMatchesAndroid]'s checks. */
  private fun report(density: Density, outcomes: List<Outcome>, csvName: String, summaryName: String, extra: List<String> = emptyList()) {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val rows = ArrayList<String>()
    rows += HEADER
    for (o in outcomes) {
      rows += o.csv()
      Log.i(TAG, o.csv())
    }
    val summary = ArrayList<String>()
    summary +=
      "full extent vs Android's own blur (ground truth A): identical ${outcomes.count { it.truth.identical }} / " +
        "${outcomes.size}; A non-zero past Obvid's right / bottom edge in " +
        "${outcomes.count { it.extraNonZeroA > 0 }} cases; " +
        "B non-zero outside Obvid's window in ${outcomes.count { it.outsideNonZeroB > 0 }} cases; non-zero outer ring in " +
        "${outcomes.count { it.ringNonZero > 0 }} cases"
    for ((route, group) in outcomes.groupBy { it.route }) {
      val ratios = group.map { it.pixelRatio }
      summary +=
        "  $route: identical ${group.count { it.truth.identical }} / ${group.size}, pixels vs Compose's bitmap " +
          String.format(Locale.US, "%.2f–%.2f×", ratios.min(), ratios.max())
    }
    summary +=
      "inside Compose's window, Obvid vs Compose's bitmap: differ in " +
        "${outcomes.count { !it.insideCompose.identical }} / " +
        "${outcomes.size} cases, max diff ${outcomes.maxOf { it.insideCompose.max }} (recorded, not asserted)"
    summary += extra
    summary += "API ${Build.VERSION.SDK_INT}, ${Build.MODEL}, density ${density.density}"
    if (!TargetSkia.isDevice) {
      summary += "${TargetSkia.offTargetLabel.replace("this device's Compose", "this device's Skia")} (alpha): " +
        DiffTally().also { t -> outcomes.forEach { t.add(it.truth) } }
    }
    summary.forEach { Log.i(TAG, it) }
    writeResults(context, csvName, rows, RESULTS_DIR)
    writeResults(context, summaryName, summary, RESULTS_DIR)

    for (o in outcomes) {
      val ctx = o.csv()
      if (TargetSkia.isDevice) {
        assertTrue("not identical to Android's blur: $ctx", o.truth.identical)
        assertEquals("cut on the right or bottom: $ctx", 0, o.extraNonZeroA)
        assertEquals("cut on some side: $ctx", 0, o.outsideNonZeroB)
      }
      if (TargetSkia.isDevice || o.route != ShadowBitmapBuilder.Route.Skia.name) {
        assertEquals("outer ring not 0: $ctx", 0, o.ringNonZero)
      }
    }
  }

  private fun runCase(density: Density, builder: ShadowBitmapBuilder, c: Case, target: ShadowBitmaps = ShadowBitmaps()): Outcome {
    val size = c.size(density)
    val radius = with(density) { c.radiusDp.dp.toPx() }
    val spread = with(density) { c.spreadDp.dp.toPx() }
    val outline = c.shape.shape.createOutline(size, LayoutDirection.Ltr, density)
    builder.build(target, outline, size, radius, spread, full = true)
    val bmp = target.bitmap!!
    val w = bmp.width
    val h = bmp.height
    val obvid = alphaOf(bmp, w, h)
    val ox = target.originX
    val oy = target.originY

    // A: Obvid's canvas, wider and taller by EXTRA.
    val a = Bitmap.createBitmap(w + EXTRA, h + EXTRA, Bitmap.Config.ALPHA_8)
    drawCompose(a, -ox.toFloat(), -oy.toFloat(), outline, size, radius, spread)
    val aw = a.width
    val aAll = alphaOf(a)
    val aWindow = IntArray(w * h) { aAll[(it / w) * aw + it % w] }
    var extraA = 0
    for (y in 0 until a.height) for (x in 0 until aw) if ((x >= w || y >= h) && aAll[y * aw + x] != 0) extraA++

    // B: grown by EXTRA on every side.
    val b = Bitmap.createBitmap(w + 2 * EXTRA, h + 2 * EXTRA, Bitmap.Config.ALPHA_8)
    drawCompose(b, (EXTRA - ox).toFloat(), (EXTRA - oy).toFloat(), outline, size, radius, spread)
    val bAll = alphaOf(b)
    var outsideB = 0
    for (y in 0 until b.height) for (x in 0 until b.width) {
      val inside = x >= EXTRA && x < EXTRA + w && y >= EXTRA && y < EXTRA + h
      if (!inside && bAll[y * b.width + x] != 0) outsideB++
    }

    var ring = 0
    for (y in 0 until h) for (x in 0 until w) {
      if ((x == 0 || y == 0 || x == w - 1 || y == h - 1) && obvid[y * w + x] != 0) ring++
    }

    // Compose's own bitmap, over the part of its window that Obvid's covers.
    val compose = ComposeShadowReference.referenceA(outline, size, radius, spread).asAndroidBitmap()
    val cAll = alphaOf(compose)
    val cw = compose.width
    var n = 0
    var max = 0
    for (y in 0 until compose.height) for (x in 0 until cw) {
      val ux = x - ox
      val uy = y - oy
      if (ux < 0 || uy < 0 || ux >= w || uy >= h) continue
      val d = kotlin.math.abs(cAll[y * cw + x] - obvid[uy * w + ux])
      if (d != 0) n++
      if (d > max) max = d
    }

    return Outcome(
      c,
      builder.route.name,
      w,
      h,
      ox,
      oy,
      target.composeWidth,
      target.composeHeight,
      ScreenCases.diffAlpha(aWindow, obvid),
      extraA,
      outsideB,
      ring,
      ScreenCases.Diff(n, max),
    )
  }

  /**
   * Compose's `createOuterShadowBitmap` draw calls (`ComposeShadowReference`, line for line, fresh `Paint`) into
   * [target] after the canvas translate ([tx], [ty]).
   */
  private fun drawCompose(
    target: Bitmap,
    tx: Float,
    ty: Float,
    outline: Outline,
    size: Size,
    radius: Float,
    spread: Float,
  ) {
    val params = outlineParams(outline)
    val path = params.path
    val paint = Paint()
    val outset = radius * 2 + spread * 2
    val shadowWidth = size.width + outset
    val shadowHeight = size.height + outset
    val canvas = android.graphics.Canvas(target)
    canvas.translate(tx, ty)
    val filter = if (radius > 0f) BlurFilter(radius) else null
    with(ComposeCanvas(canvas)) {
      if (path == null) {
        drawRoundRect(
          radius,
          radius,
          shadowWidth - radius,
          shadowHeight - radius,
          params.cornerRadius.x,
          params.cornerRadius.y,
          paint.configureShadow(blurFilter = filter),
        )
      } else if (spread > 0f) {
        translate(radius + spread, radius + spread)
        drawPath(path, paint.configureShadow(blurFilter = filter))
        drawPath(
          path,
          paint.configureShadow(style = PaintingStyle.Stroke, blurFilter = filter).apply { strokeWidth = spread * 2f },
        )
      } else {
        paint.configureShadow(blurFilter = filter)
        translate(radius, radius)
        drawPath(path, paint)
      }
    }
  }

  // ---------------------------------------------------------------------------------------------------------------

  private var step by mutableIntStateOf(0)
  private val drawn = AtomicInteger(-1)
  private var content by mutableStateOf<(@Composable () -> Unit)?>(null)

  @Before
  fun setUp() {
    rule.runOnUiThread { rule.activity.showOverLockScreen() }
    NativeShadows.forceComposeFallback = false
    rule.setContent { content?.invoke() }
    clearIdleShadows()
  }

  /** The marker: records which step was drawn (same layer as the shadow). */
  private fun Modifier.marked(): Modifier = drawBehind { drawn.set(step) }

  private fun advance(s: Int) {
    step = s
    rule.waitForIdle()
    rule.waitUntil(5_000) { drawn.get() == s }
  }

  @Test
  fun extentSwitchRebuilds() {
    check(NativeShadows.isAvailable)
    val summary = ArrayList<String>()
    val failures = ArrayList<String>()
    val density = Density(rule.activity)
    val sizePx = with(density) { SWITCH_SIZE.roundToPx() }
    val r = with(density) { SWITCH_RADIUS.toPx() }
    val s = with(density) { SWITCH_SPREAD.toPx() }
    val composeSide = ceil(sizePx + (r * 2 + s * 2)).toInt()
    val margin = ObvidNative.builderFullMargin(r)
    for (block in listOf(false, true)) {
      val overload = if (block) "block" else "shadow"
      var extent by mutableStateOf(ShadowExtent.Compose)
      clearIdleShadows()
      content = {
        CompositionLocalProvider(LocalShadowExtent provides extent) {
          val mod =
            if (block) {
              Modifier.dropShadow(SWITCH_SHAPE) {
                radius = SWITCH_RADIUS.toPx()
                spread = SWITCH_SPREAD.toPx()
                color = Color.Black
              }
            } else {
              Modifier.dropShadow(SWITCH_SHAPE, Shadow(SWITCH_RADIUS, Color.Black, SWITCH_SPREAD))
            }
          Box(Modifier.padding(120.dp).size(SWITCH_SIZE).marked().then(mod).background(Color.Gray))
        }
      }
      advance(0)
      for ((i, next) in listOf(ShadowExtent.Full, ShadowExtent.Compose).withIndex()) {
        val before = mainStore { it.buildCount }
        extent = next
        advance(i + 1)
        val st =
          mainStore { store ->
            val e = store.entriesForTest().single()
            SwitchState(
              store.buildCount - before,
              store.entriesForTest().size,
              e.key.extent,
              e.usedWidth,
              e.usedHeight,
              e.bitmaps.originX,
              e.bitmaps.originY,
            )
          }
        val line =
          "$overload → $next: builds ${st.builds}, store entries ${st.entries}, key extent ${st.extent}, bitmap " +
            "${st.w}x${st.h}, origin (${st.originX}, ${st.originY}), Compose's ${composeSide}x$composeSide, " +
            "margin $margin"
        summary += line
        Log.i(TAG, line)
        val expectedOk =
          if (next == ShadowExtent.Full) {
            // A round rect drawn at (r, r): the window starts `margin` px before floor(r).
            st.originX == r.toInt() - margin && st.originY == st.originX && st.w > composeSide && st.h > composeSide
          } else {
            st.originX == 0 && st.originY == 0 && st.w == composeSide && st.h == composeSide
          }
        if (st.builds != 1L || st.entries != 1 || st.extent != next || !expectedOk) failures += line
      }
      content = null
      rule.waitForIdle()
      assertEquals("store not empty after $overload", 0, mainStore { it.entriesForTest().size })
    }
    summary += storeKeepsExtentsApart(density)
    summary += "API ${Build.VERSION.SDK_INT}, ${Build.MODEL}, density ${density.density}"
    writeResults(rule.activity, SWITCH_SUMMARY_NAME, summary + failures.map { "FAIL $it" }, RESULTS_DIR)
    assertTrue("failures: $failures", failures.isEmpty())
  }

  /**
   * A drop shadow drawn with the caller's own shader (`ShaderBrush(shader)` returns that shader on every call, maybe
   * shared, with its own local matrix), in the Compose extent and then the full one: inside Compose's window both
   * draw the same pixels (the gradient lies where Compose's window puts it, the caller's matrix applied), and the
   * caller's local matrix stays as it was. Whole-pixel radius, spread and position, so every offset is exact.
   *
   * The same pixels are asserted on the target Skia ([TargetSkia.isDevice]); older versions draw a few pixels of the
   * window differently between the extents (as [fullExtentMatchesAndroid] records), so there they must differ in
   * under 1 % of the window. A misplaced gradient changes most of the shadow's pixels.
   */
  @Test
  fun fullExtentBrushKeepsCallersShader() {
    check(NativeShadows.isAvailable)
    val callers = android.graphics.Matrix().apply { setRotate(30f) }.apply { postScale(2f, 2f) }
    val shader =
      LinearGradientShader(Offset.Zero, Offset(60f, 0f), listOf(Color.Red, Color.Blue), tileMode = TileMode.Mirror)
    shader.setLocalMatrix(callers)
    val callersBrush = ShaderBrush(shader)
    val density = Density(rule.activity)
    val side = 300
    val pad = 240
    val radiusPx = 40
    val spreadPx = 8
    val cell = side + 2 * pad
    var extent by mutableStateOf(ShadowExtent.Compose)
    clearIdleShadows()
    content = {
      CompositionLocalProvider(LocalShadowExtent provides extent) {
        val mod =
          Modifier.dropShadow(SWITCH_SHAPE) {
            radius = radiusPx.toFloat()
            spread = spreadPx.toFloat()
            brush = callersBrush
          }
        with(density) {
          Box(Modifier.testTag(TAG_BRUSH_CELL).size(cell.toDp()).background(Color.White)) {
            Box(Modifier.offset { IntOffset(pad, pad) }.size(side.toDp()).marked().then(mod).background(Color.Gray))
          }
        }
      }
    }
    val captures = ArrayList<IntArray>()
    for ((i, next) in listOf(ShadowExtent.Compose, ShadowExtent.Full).withIndex()) {
      extent = next
      advance(i)
      captures += ScreenCases.pixelsOf(rule.onNodeWithTag(TAG_BRUSH_CELL).captureToImage().asAndroidBitmap())
    }
    val origin = mainStore { store -> store.entriesForTest().single().bitmaps.let { it.originX to it.originY } }
    content = null
    rule.waitForIdle()

    assertTrue("the full extent moves the bitmap's origin: $origin", origin != (0 to 0))
    assertEquals("capture size", cell * cell, captures[0].size)
    // Compose's window: the component grown by radius + spread on each side.
    val w0 = pad - radiusPx - spreadPx
    val w1 = pad + side + radiusPx + spreadPx
    val white = Color.White.toArgb()
    var diffs = 0
    var shaded = 0
    for (y in w0 until w1) {
      for (x in w0 until w1) {
        val i = y * cell + x
        if (captures[0][i] != captures[1][i]) diffs++
        if (captures[0][i] != white) shaded++
      }
    }
    assertTrue("the shadow is drawn", shaded > side * side)
    val differ = "pixels inside Compose's window that differ between the extents"
    Log.i(TAG, "$differ: $diffs of ${(w1 - w0) * (w1 - w0)}, API ${Build.VERSION.SDK_INT}")
    if (TargetSkia.isDevice) {
      assertEquals(differ, 0, diffs)
    } else {
      assertTrue("$differ: $diffs", diffs * 100 < (w1 - w0) * (w1 - w0))
    }
    val after = android.graphics.Matrix()
    shader.getLocalMatrix(after)
    assertEquals("the caller's local matrix", callers, after)
  }

  private data class SwitchState(
    val builds: Long,
    val entries: Int,
    val extent: ShadowExtent,
    val w: Int,
    val h: Int,
    val originX: Int,
    val originY: Int,
  )

  /** One geometry obtained with each extent from one store: two entries, two bitmaps of different sizes. */
  private fun storeKeepsExtentsApart(density: Density): String =
    onMain {
      val store = ShadowStore(looper = null)
      val size = Size(300f, 200f)
      val shape = catalogShape("mixed")
      val ltr = LayoutDirection.Ltr
      val compose = store.obtain(null, density, shape, size, ltr, 40f, 6f, extent = ShadowExtent.Compose)
      val full = store.obtain(null, density, shape, size, ltr, 40f, 6f, extent = ShadowExtent.Full)
      assertNotSame(compose, full)
      assertEquals(2, store.entriesForTest().size)
      val cw = compose.usedWidth
      val fw = full.usedWidth
      assertTrue("full ${fw} vs compose $cw", fw > cw && full.usedHeight > compose.usedHeight)
      store.release(compose)
      store.release(full)
      "one store, one geometry, both extents: 2 entries, ${cw}x${compose.usedHeight} and ${fw}x${full.usedHeight}"
    }

  private companion object {
    const val TAG = "FS-FullExtent"
    const val RESULTS_DIR = "full-extent"
    const val CSV_NAME = "full-extent-builder.csv"
    const val SUMMARY_NAME = "full-extent-builder-summary.txt"
    const val SWITCH_SUMMARY_NAME = "full-extent-switch-summary.txt"
    const val SMALL_CSV_NAME = "full-extent-smallblur.csv"
    const val SMALL_SUMMARY_NAME = "full-extent-smallblur-summary.txt"
    const val MASKGAPS_CSV_NAME = "full-extent-maskgaps.csv"
    const val MASKGAPS_SUMMARY_NAME = "full-extent-maskgaps-summary.txt"
    const val HEADER =
      "model,api,shape,widthDp,heightDp,radiusDp,spreadDp,route,w,h,originX,originY,composeW,composeH,pixelRatio," +
        "identical,maxDiff,diffPixels,extraNonZeroA,outsideNonZeroB,ringNonZero,insideComposeMax,insideComposePixels"

    /** The ground truths' extra pixels per side. */
    const val EXTRA = 160
    val SWITCH_SHAPE = RoundedCornerShape(16.dp)
    val SWITCH_SIZE = 120.dp
    val SWITCH_RADIUS = 36.dp
    val SWITCH_SPREAD = 4.dp
    const val TAG_BRUSH_CELL = "full-extent-brush-cell"

    fun fmt(f: Float) = if (f == f.toInt().toFloat()) f.toInt().toString() else f.toString()
  }
}
