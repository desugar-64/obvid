// Copyright 2026, Serhii Yaremych (desugar-64) and the Obvid project contributors
// SPDX-License-Identifier: Apache-2.0

package dev.serhiiyaremych.obvid

import android.graphics.RectF
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.PathIterator
import androidx.compose.ui.graphics.PathSegment
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.serhiiyaremych.obvid.builder.ShadowBitmapBuilder
import dev.serhiiyaremych.obvid.fixtures.ShapeCase
import dev.serhiiyaremych.obvid.fixtures.catalogShape
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * The cases of the mask-route tests (drop: `ShadowBitmapBuilderTest.maskRouteGapsMatchCompose`, inner:
 * `InnerShadowBuilderTest.maskRouteGapsMatchCompose`): paths whose mask Android 17's Skia forms differently from a
 * fill of the path (or of its `2·spread` stroke) inside the path's bounds. Three groups ([GAPS]):
 * - [INVERSE]: an inverse fill (`InverseWinding`, `InverseEvenOdd`). `DrawToMask` (SkDraw.cpp) takes infinite path
 *   bounds for it, so `compute_mask_bounds` makes the mask the whole clip grown by min(border, 128). Drop and inner
 *   (the inner path drawn at the offset, spread ≤ 0; with spread > 0 the blur source is Compose's intermediate).
 * - [HAIRLINE]: a drop shadow's spread stroke `2·spread` wide when that width is ≤ 1 px. `DrawTreatAsHairline`
 *   (SkDrawProcs.h, SkDraw.cpp `DrawTreatAAStrokeAsHairline`, `modifyPaintForHairlines`) draws it as an anti-aliased
 *   hairline (`SkScan::AntiHairPath` into the mask), with the paint's alpha scaled by the coverage (the width) below 1.
 * - [STROKE_RECT]: a drop shadow's spread stroke that is a rect (an open horizontal or vertical segment, butt caps).
 *   `filterPath` (SkMaskFilterBase.cpp) runs `countNestedRects` on the stroked device path, and `filterRectsToNine`
 *   (SkBlurMaskFilterImpl.cpp) blurs a rect big enough for its nine-patch with the analytic `BlurRect`.
 * Each case also has Skia's decision by those rules ([predictDrop], [predictInner]): whether it should differ from a
 * fill of the path's bounds, and why; and the builder's route for it ([expectedDropRoute], [expectedInnerRoute]): the
 * Skia route for those cases (and a few more stroke rects), the mask route for the rest. Radii: 1 px (σ < 2), 4 and
 * 24 dp (σ ≥ 2).
 */
internal object MaskRouteGapCases {
  const val INVERSE = "inverse"
  const val HAIRLINE = "hairline"
  const val STROKE_RECT = "strokeRect"
  val GAPS = listOf(INVERSE, HAIRLINE, STROKE_RECT)

  /** (label, radius in dp) at [density]: 1 px ([dpOf]), 4 dp and 24 dp. */
  fun radii(density: Float): List<Pair<String, Float>> = listOf("px1" to dpOf(1f, density), "dp4" to 4f, "dp24" to 24f)

  /** Spreads of the inverse fills, dp. */
  val INVERSE_SPREADS_DP = listOf(0f, 8f, -8f)

  /** Spreads of the thin-stroke cases, px: up to 0.5 (a stroke ≤ 1 px wide) and just above. */
  val THIN_SPREADS_PX = listOf(0.1f, 0.25f, 0.4f, 0.5f, 0.51f, 0.6f)

  /** The layout box of the inverse fills and of the closed paths of [HAIRLINE], dp. */
  const val BOX_DP = 48

  /** The layout box of the segments, px (the coordinates below are px in it). */
  const val SEGMENT_BOX_PX = 96f

  /** The box of the long segments of [STROKE_RECT], dp, and their spread (dp): a stroke rect over 225 px at 24 dp. */
  const val LONG_BOX_DP = 200
  const val LONG_SPREAD_DP = 48f

  /**
   * A dp value whose `Dp.toPx` (`value * density`) is exactly [px] when one lies within a few ulps of `px / density`,
   * else `px / density`.
   */
  fun dpOf(px: Float, density: Float): Float {
    val d = px / density
    if (d * density == px) return d
    var up = d
    var down = d
    repeat(8) {
      up = Math.nextUp(up)
      if (up * density == px) return up
      down = Math.nextDown(down)
      if (down * density == px) return down
    }
    return d
  }

  /** The inverse fills: a triangle and a closed, self-overlapping curved star, each under both inverse fill types. */
  fun inverseShapes(): List<ShapeCase> =
    listOf(android.graphics.Path.FillType.INVERSE_WINDING, android.graphics.Path.FillType.INVERSE_EVEN_ODD).flatMap { f ->
      val suffix = if (f == android.graphics.Path.FillType.INVERSE_WINDING) "InvWinding" else "InvEvenOdd"
      listOf(ShapeCase("triangle$suffix", TriangleStarShape(curved = false, f)), ShapeCase("star$suffix", TriangleStarShape(curved = true, f)))
    }

  /**
   * A rect of the box's width from ¾ of its height to 1¾, inverse winding: convex, filled above it. With spread 0.5 px
   * (a 1 px `Clear` stroke, which Skia draws as a hairline and so not inverse) the inner shadow's intermediate keeps the
   * box above the rect, whose blur leaves a large hole of 0s in the inverted bitmap.
   */
  fun inverseRectShape(): ShapeCase =
    ShapeCase(
      "rectInvWinding",
      PathShape { size ->
        addRect(0f, 0.75f * size.height, size.width, 1.75f * size.height, android.graphics.Path.Direction.CW)
        fillType = android.graphics.Path.FillType.INVERSE_WINDING
      },
    )

  /** One drop case: [shape] on a square box of [sizePx], radius and spread in dp, labelled for the summary. */
  class DropCase(
    val gap: String,
    val shape: ShapeCase,
    val sizePx: Float,
    val radiusLabel: String,
    val radiusDp: Float,
    val spreadLabel: String,
    val spreadDp: Float,
  ) {
    val size
      get() = Size(sizePx, sizePx)

    fun radius(density: Density) = with(density) { radiusDp.dp.toPx() }

    fun spread(density: Density) = with(density) { spreadDp.dp.toPx() }

    fun outline(density: Density): Outline = shape.shape.createOutline(size, LayoutDirection.Ltr, density)
  }

  /**
   * - [INVERSE]: [inverseShapes] on a [BOX_DP] box × radii × spread 0 / 8 / −8 dp (36).
   * - [HAIRLINE]: the open segment (12, 28)→(62, 54) on a [SEGMENT_BOX_PX] box and the closed `mixed` and `morph` on
   *   a [BOX_DP] box × radii × [THIN_SPREADS_PX] (54).
   * - [STROKE_RECT]: segments (12, 28)→(62, 28) and (28, 12)→(28, 62) on a [SEGMENT_BOX_PX] box, and the first one
   *   closed (move, line, close), × radii × spread 2 px / 8 dp (18); segments across 80 % of a [LONG_BOX_DP] box at
   *   its middle, horizontal and vertical, × radii × [LONG_SPREAD_DP] (6); [pathStrokeShapes] on a [PATH_BOX_PX]
   *   box × radii and 4 px × their spreads (24).
   */
  fun dropCases(density: Density): List<DropCase> = buildList {
    val d = density.density
    val radii = radii(d)
    val box = with(density) { BOX_DP.dp.roundToPx().toFloat() }
    for (shape in inverseShapes()) for ((rl, r) in radii) for (s in INVERSE_SPREADS_DP) {
      add(DropCase(INVERSE, shape, box, rl, r, "dp${fmt(s)}", s))
    }
    val thinShapes =
      listOf(ShapeCase("slantSeg", SegmentShape(12f, 28f, 62f, 54f)) to SEGMENT_BOX_PX) +
        listOf("mixed", "morph").map { ShapeCase(it, catalogShape(it)) to box }
    for ((shape, size) in thinShapes) for ((rl, r) in radii) for (s in THIN_SPREADS_PX) {
      add(DropCase(HAIRLINE, shape, size, rl, r, "px${fmt(s)}", dpOf(s, d)))
    }
    val segments =
      listOf(
        ShapeCase("hSeg", SegmentShape(12f, 28f, 62f, 28f)),
        ShapeCase("vSeg", SegmentShape(28f, 12f, 28f, 62f)),
        ShapeCase("hSegClosed", SegmentShape(12f, 28f, 62f, 28f, closed = true)),
      )
    for (shape in segments) for ((rl, r) in radii) for ((sl, s) in listOf("px2" to dpOf(2f, d), "dp8" to 8f)) {
      add(DropCase(STROKE_RECT, shape, SEGMENT_BOX_PX, rl, r, sl, s))
    }
    val long = with(density) { LONG_BOX_DP.dp.roundToPx().toFloat() }
    val a = floor(long * 0.1f)
    val b = floor(long * 0.9f)
    val m = floor(long * 0.5f)
    val longs = listOf(ShapeCase("hSegLong", SegmentShape(a, m, b, m)), ShapeCase("vSegLong", SegmentShape(m, a, m, b)))
    for (shape in longs) for ((rl, r) in radii) {
      add(DropCase(STROKE_RECT, shape, long, rl, r, "dp${fmt(LONG_SPREAD_DP)}", LONG_SPREAD_DP))
    }
    val pathRadii = radii + ("px4" to dpOf(4f, d))
    for ((shape, spreads) in pathStrokeShapes()) for ((rl, r) in pathRadii) for (spreadPx in spreads) {
      add(DropCase(STROKE_RECT, shape, PATH_BOX_PX, rl, r, "px${fmt(spreadPx)}", dpOf(spreadPx, d)))
    }
  }

  /**
   * Spreads (px) changing on one kept outline per shape, radius 4 px, in this order: `hSeg` 4, 0.25, 4, 10, −4, 2 (a
   * stroke rect, a hairline, the fill alone), `mixed` 21, 0.4, 21, 10.5, 0.6, −10 (the mask route between hairlines
   * and the fill alone) and `quadInLine` 10, 4, 0.5, 10. Each spread change takes the stroke test again. The groups are
   * the cases' [STROKE_RECT] and [HAIRLINE] rules, labelled by shape.
   */
  fun spreadChangeCases(density: Density): List<DropCase> = buildList {
    val d = density.density
    val box = with(density) { BOX_DP.dp.roundToPx().toFloat() }
    val quadInLine = pathStrokeShapes().first { it.first.name == "quadInLine" }.first
    val runs =
      listOf(
        Triple(ShapeCase("hSeg", SegmentShape(12f, 28f, 62f, 28f)), SEGMENT_BOX_PX, listOf(4f, 0.25f, 4f, 10f, -4f, 2f)),
        Triple(ShapeCase("mixed", catalogShape("mixed")), box, listOf(21f, 0.4f, 21f, 10.5f, 0.6f, -10f)),
        Triple(quadInLine, PATH_BOX_PX, listOf(10f, 4f, 0.5f, 10f)),
      )
    for ((shape, size, spreads) in runs) for (s in spreads) {
      val gap = if (s > 0f && s * 2f <= 1f) HAIRLINE else STROKE_RECT
      add(DropCase(gap, shape, size, "px4", dpOf(4f, d), "px${fmt(s)}", dpOf(s, d)))
    }
  }

  /** The box of [pathStrokeShapes], px. */
  const val PATH_BOX_PX = 128f

  /**
   * Paths in px with their spreads (px): `nestedQuad` (a horizontal segment and a straight vertical quad: a path with
   * width and height whose 20 px stroke is two nested rects of the same direction), `nearFlat` (a segment whose ends
   * are one float ulp apart in y, spread 8 px: Compose's translate rounds them to one y, a full-extent window's may
   * not), `pointQuad` (a horizontal segment and a zero-length quad, whose point gives the path a height but adds no
   * stroke, spread 20 px), `quadInLine` (a quad whose control point is 1/16 px off its chord: Skia's stroker takes it
   * for a line, so its 10 px stroke is one rect though the path has three x and two y values) and `polyline` (a
   * straight three-point polyline: its stroke is one rect with a collinear point on each long edge, spread 4 and 10 px).
   */
  fun pathStrokeShapes(): List<Pair<ShapeCase, List<Float>>> =
    listOf(
      ShapeCase(
        "nestedQuad",
        PathShape {
          moveTo(8f, 64f)
          lineTo(120f, 64f)
          moveTo(64f, 52f)
          quadTo(64f, 64f, 64f, 76f)
        },
      ) to listOf(20f),
      ShapeCase(
        "nearFlat",
        PathShape {
          moveTo(32f, 31.999998092651367f)
          lineTo(96f, 32f)
        },
      ) to listOf(8f),
      ShapeCase(
        "pointQuad",
        PathShape {
          moveTo(12f, 44f)
          lineTo(100f, 44f)
          moveTo(40f, 36f)
          quadTo(40f, 36f, 40f, 36f)
        },
      ) to listOf(20f),
      ShapeCase(
        "quadInLine",
        PathShape {
          moveTo(8f, 32f)
          quadTo(24f, 32.0625f, 72f, 32f)
        },
      ) to listOf(10f),
      ShapeCase(
        "polyline",
        PathShape {
          moveTo(8f, 16f)
          lineTo(40f, 16f)
          lineTo(72f, 16f)
        },
      ) to listOf(4f, 10f),
    )

  /** Skia's decision for a case: the group whose rule makes its mask differ from a fill of the path's bounds, or null. */
  class Prediction(val gap: String?, val rule: String)

  /**
   * Android 17's Skia for Compose's drop-shadow draws of [local] (`createOuterShadowBitmap(path)`: the fill, then with
   * [spread] > 0 the stroke `2·spread` wide, under the translate `radius + spread`), blurred with [radius] px.
   */
  fun predictDrop(local: android.graphics.Path, radius: Float, spread: Float): Prediction {
    if (radius <= 0f) return Prediction(null, "no mask filter")
    if (local.isInverseFillType) return Prediction(INVERSE, "inverse fill: mask over the clip")
    if (spread <= 0f) return Prediction(null, "fill")
    val width = spread * 2f
    // DrawTreatAAStrokeAsHairline: fast_len of (width, 0) and (0, width) under a translate is the width.
    if (width <= 1f) {
      val alpha = if (width == 1f) 255 else 255 * (width * 256f).toInt() shr 8
      return Prediction(HAIRLINE, "hairline width $width alpha $alpha")
    }
    val t = radius + spread
    val stroke = android.graphics.Path()
    val paint =
      android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        style = android.graphics.Paint.Style.STROKE
        strokeWidth = width
      }
    @Suppress("DEPRECATION") paint.getFillPath(local, stroke)
    stroke.offset(t, t)
    // countNestedRects: two rect contours, one containing the other (IsNestedFillRects, any direction), or one rect.
    val rects = rectContours(stroke)
    if (rects == null || rects.isEmpty() || rects.size > 2) return Prediction(null, "fill and stroke masks")
    if (rects.size == 1) {
      val r = rects[0]
      val rect = "stroke rect ${r.left} ${r.top} ${r.right} ${r.bottom}"
      return if (rectNine(r, radius)) Prediction(STROKE_RECT, "$rect: BlurRect nine-patch")
      else Prediction(null, "$rect: too small for the nine-patch, whole mask")
    }
    val (a, b) = rects
    val (outer, inner) =
      when {
        a.contains(b) -> a to b
        b.contains(a) -> b to a
        else -> return Prediction(null, "stroke: two rects, not nested")
      }
    val text = "stroke nested rects ${outer.left} ${outer.top} ${outer.right} ${outer.bottom} / ${inner.left} ${inner.top} " +
      "${inner.right} ${inner.bottom}"
    val plan =
      ObvidNative.builderPlanRects(outer.left, outer.top, outer.right, outer.bottom, inner.left, inner.top, inner.right, inner.bottom, radius)
    return if (plan == ObvidNative.PLAN_RECTS_MASK) Prediction(null, "$text: too small for the nine-patch, whole mask")
    else Prediction(STROKE_RECT, "$text: rect nine-patch or no blur (plan $plan)")
  }

  /**
   * The contours of [path] as rects when each is lines only, every line axis-aligned and every point on the contour's
   * bounds (a rect, collinear points allowed, as Skia's rect test allows them), lone moves skipped; null when a
   * contour is not.
   */
  private fun rectContours(path: android.graphics.Path): List<RectF>? {
    val out = ArrayList<RectF>()
    val pts = ArrayList<Float>()
    fun flush(): Boolean {
      if (pts.isEmpty()) return true
      val xs = pts.filterIndexed { i, _ -> i % 2 == 0 }
      val ys = pts.filterIndexed { i, _ -> i % 2 == 1 }
      val r = RectF(xs.min(), ys.min(), xs.max(), ys.max())
      pts.clear()
      if (r.width() <= 0f || r.height() <= 0f) return false
      if (xs.indices.any { xs[it] != r.left && xs[it] != r.right && ys[it] != r.top && ys[it] != r.bottom }) return false
      out += r
      return true
    }
    val iterator = path.asComposePath().iterator(PathIterator.ConicEvaluation.AsConic, 0.25f)
    val p = FloatArray(8)
    while (iterator.hasNext()) {
      when (iterator.next(p, 0)) {
        PathSegment.Type.Move -> if (!flush()) return null
        PathSegment.Type.Line -> {
          if (p[0] != p[2] && p[1] != p[3]) return null
          for (i in 0 until 4) pts += p[i]
        }
        PathSegment.Type.Close -> Unit
        PathSegment.Type.Done -> break
        else -> return null
      }
    }
    return if (flush()) out else null
  }

  /**
   * Android 17's Skia for Compose's inner-shadow draw of [local] (`createInnerPathShadowBrush`): with [spread] ≤ 0 the
   * path drawn blurred at the offset; with spread > 0 the intermediate (Compose's own draws) as the blur source.
   */
  fun predictInner(local: android.graphics.Path, radius: Float, spread: Float): Prediction =
    when {
      radius <= 0f -> Prediction(null, "no mask filter")
      !local.isInverseFillType -> Prediction(null, "fill")
      spread > 0f -> Prediction(null, "inverse fill in the intermediate, drawn by Compose's calls")
      else -> Prediction(INVERSE, "inverse fill: mask over the clip")
    }

  /**
   * The builder's drop route for a path [local] that is not a rect or nested rects, with [radius] > 0: the Skia route
   * for an inverse fill, a spread stroke ≤ 1 px wide, and with spread > 0 a path whose stroke may be rects
   * ([strokeMayBeRects] on Compose's stroke in local space: a superset of [predictDrop]'s stroke rects, the same under
   * any translate), else the mask route.
   */
  fun expectedDropRoute(local: android.graphics.Path, radius: Float, spread: Float): String {
    val skia = ShadowBitmapBuilder.Route.Skia.name
    if (radius <= 0f || local.isInverseFillType) return skia
    if (spread <= 0f) return ShadowBitmapBuilder.Route.Mask.name
    if (spread * 2f <= 1f || strokeMayBeRects(stroke(local, spread))) return skia
    return ShadowBitmapBuilder.Route.Mask.name
  }

  /** Compose's stroke of [local], `2·spread` wide (butt caps, miter joins), in local space. */
  fun stroke(local: android.graphics.Path, spread: Float): android.graphics.Path {
    val stroke = android.graphics.Path()
    val paint =
      android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        style = android.graphics.Paint.Style.STROKE
        strokeWidth = spread * 2f
      }
    @Suppress("DEPRECATION") paint.getFillPath(local, stroke)
    return stroke
  }

  /**
   * Whether [stroke] may be one rect or two nested rects for Skia's `countNestedRects`: no curve, every line
   * horizontal or vertical within 1/64 px, at most two contours with a line longer than 1/64 px on either axis (Skia
   * skips zero-length lines).
   */
  fun strokeMayBeRects(stroke: android.graphics.Path): Boolean {
    val iterator = stroke.asComposePath().iterator(PathIterator.ConicEvaluation.AsConic, 0.25f)
    val p = FloatArray(8)
    var contours = 0
    var counted = false
    while (iterator.hasNext()) {
      when (iterator.next(p, 0)) {
        PathSegment.Type.Move,
        PathSegment.Type.Close -> counted = false
        PathSegment.Type.Line -> {
          val dx = abs(p[2] - p[0])
          val dy = abs(p[3] - p[1])
          if (dx > 1f / 64f && dy > 1f / 64f) return false
          if (!counted && (dx > 1f / 64f || dy > 1f / 64f)) {
            counted = true
            contours++
          }
        }
        PathSegment.Type.Done -> break
        else -> return false
      }
    }
    return contours <= 2
  }

  /** The builder's inner route for a path [local] that is not a rect or nested rects: Skia for an inverse fill at spread ≤ 0. */
  fun expectedInnerRoute(local: android.graphics.Path, spread: Float): String =
    if (local.isInverseFillType && spread <= 0f) ShadowBitmapBuilder.Route.Skia.name else ShadowBitmapBuilder.Route.Mask.name

  /**
   * `filterRectsToNine` with one rect [r] and `BlurMaskFilter(radius)`: true when it returns a nine-patch (Skia's
   * `BlurRect`), false for unimplemented (Skia blurs the whole mask). `BlurRect` pads by `ceil(6σ) / 2`; the small rect
   * needs the rect's rounded-out size minus `(blurred size − rounded-out size + 3)` ≥ 0 per axis, and ≥ 2 px left.
   */
  fun rectNine(r: RectF, radius: Float): Boolean {
    val sigma = min(SmallBlurCases.sigma(radius), MAX_SIGMA)
    val profile = ceil(6f * sigma).toInt()
    if (profile <= 0) return false
    val pad = profile / 2
    val dstW = skRound(r.right + pad) - skRound(r.left - pad)
    val dstH = skRound(r.bottom + pad) - skRound(r.top - pad)
    val srcW = ceil(r.right).toInt() - floor(r.left).toInt()
    val srcH = ceil(r.bottom).toInt() - floor(r.top).toInt()
    val dx = srcW - (dstW - srcW + 3)
    val dy = srcH - (dstH - srcH + 3)
    if (dx < 0 || dy < 0) return false
    return r.width() - dx >= 2f && r.height() - dy >= 2f
  }

  /** One build's outcome, for [summary] and [failure]. */
  class Result(
    val gap: String,
    val radiusLabel: String,
    val spreadLabel: String,
    val predicted: Boolean,
    val route: String,
    val identical: Boolean,
    val maxDiff: Int,
    val pixels: Int,
    val expectedRoute: String,
  ) {
    val routeOk
      get() = route == expectedRoute
  }

  /**
   * Per group ([GAPS]): identical / cases, differing pixels, max diff, how many Skia's rules predict to differ and how
   * the prediction held, routes; then per radius and per spread.
   */
  fun summary(results: List<Result>): List<String> {
    val out = ArrayList<String>()
    out += "total: identical ${results.count { it.identical }} / ${results.size}; differing per gap: " +
      present(results).joinToString(", ") { g -> results.filter { it.gap == g }.let { "$g ${it.count { r -> !r.identical }} / ${it.size}" } } +
      "; routes as expected ${results.count { it.routeOk }} / ${results.size}"
    for (gap in GAPS) {
      val group = results.filter { it.gap == gap }
      if (group.isEmpty()) continue
      val predicted = group.filter { it.predicted }
      val other = group.filter { !it.predicted }
      out += "gap $gap: identical ${group.count { it.identical }} / ${group.size}, differing pixels ${group.sumOf { it.pixels }}, " +
        "max diff ${group.maxOf { it.maxDiff }}; predicted to differ ${predicted.size} (differ ${predicted.count { !it.identical }}), " +
        "predicted identical ${other.size} (differ ${other.count { !it.identical }}); routes ${routes(group)}; routes as " +
        "expected ${group.count { it.routeOk }} / ${group.size} (Skia expected ${group.count { it.expectedRoute == ShadowBitmapBuilder.Route.Skia.name }})"
      for ((label, part) in group.groupBy { it.radiusLabel }) out += "  $gap radius $label: ${line(part)}"
      for ((label, part) in group.groupBy { it.spreadLabel }) out += "  $gap spread $label: ${line(part)}"
    }
    return out
  }

  /**
   * The failure message when a case is not identical (checked only where [identity]) or not on its expected route: the
   * counts per gap, or null when every case passes.
   */
  fun failure(results: List<Result>, file: String, identity: Boolean): String? {
    val bad = results.filter { (identity && !it.identical) || !it.routeOk }
    if (bad.isEmpty()) return null
    val per =
      present(results).joinToString(", ") { g ->
        val group = results.filter { it.gap == g }
        "$g: not identical ${group.count { !it.identical }} / ${group.size} (predicted ${group.count { it.predicted }}, " +
          "differing unpredicted ${group.count { !it.predicted && !it.identical }}), route not as expected " +
          "${group.count { !it.routeOk }}"
      }
    return "mask route gaps per gap: $per${if (identity) "" else " (identity not asserted below the target Skia)"}; rows in $file"
  }

  /** The groups of [GAPS] that [results] has. */
  private fun present(results: List<Result>): List<String> = GAPS.filter { g -> results.any { it.gap == g } }

  private fun line(rows: List<Result>): String =
    "identical ${rows.count { it.identical }} / ${rows.size} (predicted to differ ${rows.count { it.predicted }}), " +
      "max diff ${rows.maxOf { it.maxDiff }}, differing pixels ${rows.sumOf { it.pixels }}; routes ${routes(rows)}"

  /** `Route identical / cases` for every route that occurs. */
  private fun routes(rows: List<Result>): String =
    ShadowBitmapBuilder.Route.entries.filter { r -> rows.any { it.route == r.name } }.joinToString(", ") { r ->
      "${r.name} ${rows.count { it.identical && it.route == r.name }} / ${rows.count { it.route == r.name }}"
    }

  /** `kMAX_BLUR_SIGMA` (SkBlurMask.h). */
  private const val MAX_SIGMA = 532f

  /** `SkScalarRoundToInt`: floor(x + 0.5). */
  private fun skRound(x: Float): Int = floor(x.toDouble() + 0.5).toInt()

  fun fmt(f: Float) = if (f == f.toInt().toFloat()) f.toInt().toString() else f.toString()
}

/**
 * An `Outline.Generic` with the fill type [fillType] (inverse in the inverse-fill cases): a triangle with corners at (30, 30), (50, 30) and (40, 50)
 * of a 96-unit box, or ([curved]) a five-point star whose edges are quadratic curves, drawn through every second
 * point so that its centre is covered twice (the two inverse fill types differ there). Scaled to the layout box.
 */
internal class TriangleStarShape(private val curved: Boolean, private val fillType: android.graphics.Path.FillType) : Shape {
  override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
    val path = android.graphics.Path()
    val w = size.width
    val h = size.height
    if (!curved) {
      path.moveTo(w * 30f / 96f, h * 30f / 96f)
      path.lineTo(w * 50f / 96f, h * 30f / 96f)
      path.lineTo(w * 40f / 96f, h * 50f / 96f)
      path.close()
    } else {
      val cx = w / 2f
      val cy = h / 2f
      val r = 0.38f * min(w, h)
      val bend = 0.08f * max(w, h)
      fun x(k: Int) = cx + r * cos(Math.toRadians(-90.0 + 144.0 * k)).toFloat()
      fun y(k: Int) = cy + r * sin(Math.toRadians(-90.0 + 144.0 * k)).toFloat()
      path.moveTo(x(0), y(0))
      for (k in 1..5) {
        val mx = (x(k - 1) + x(k)) / 2f
        val my = (y(k - 1) + y(k)) / 2f
        // The control point is the chord's midpoint pushed off the chord by `bend`.
        val dx = x(k) - x(k - 1)
        val dy = y(k) - y(k - 1)
        val len = kotlin.math.sqrt(dx * dx + dy * dy)
        path.quadTo(mx - dy / len * bend, my + dx / len * bend, x(k), y(k))
      }
      path.close()
    }
    path.fillType = fillType
    return Outline.Generic(path.asComposePath())
  }
}

/** An `Outline.Generic` of the path [build] makes for the layout size. */
internal class PathShape(private val build: android.graphics.Path.(Size) -> Unit) : Shape {
  override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline =
    Outline.Generic(android.graphics.Path().apply { build(size) }.asComposePath())
}

/**
 * An `Outline.Generic` of one line segment from ([x0], [y0]) to ([x1], [y1]) in px, whatever the box: an open path
 * (move, line), or with [closed] a closed one (move, line, close). Its fill is empty; with spread its stroke is drawn.
 */
internal class SegmentShape(
  private val x0: Float,
  private val y0: Float,
  private val x1: Float,
  private val y1: Float,
  private val closed: Boolean = false,
) : Shape {
  override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
    val path = android.graphics.Path()
    path.moveTo(x0, y0)
    path.lineTo(x1, y1)
    if (closed) path.close()
    return Outline.Generic(path.asComposePath())
  }
}
