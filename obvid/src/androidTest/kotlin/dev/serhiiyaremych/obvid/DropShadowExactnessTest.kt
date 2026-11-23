// Copyright 2026, Serhii Yaremych (desugar-64) and the Obvid project contributors
// SPDX-License-Identifier: Apache-2.0

package dev.serhiiyaremych.obvid

import android.graphics.Bitmap
import android.graphics.RectF
import android.os.Build
import android.util.Log
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageBitmapConfig
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.shadow.DropShadowPainter
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.serhiiyaremych.obvid.fixtures.CaseGeometry
import dev.serhiiyaremych.obvid.fixtures.ComposeShadowReference
import dev.serhiiyaremych.obvid.fixtures.ShapeCase
import dev.serhiiyaremych.obvid.fixtures.alphaOf
import dev.serhiiyaremych.obvid.fixtures.maskPaint
import dev.serhiiyaremych.obvid.fixtures.outlineParams
import dev.serhiiyaremych.obvid.fixtures.shapeCatalog
import dev.serhiiyaremych.obvid.fixtures.strokeFillPath
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import org.junit.Test
import org.junit.runner.RunWith
import androidx.compose.ui.graphics.Canvas as ComposeCanvas

/**
 * Checks whether "Canvas draws the outline into an A8 mask without blur, then the scalar port `skia::blur_a8`
 * blurs it" is identical to Compose 1.13.0's shadow bitmap. Correctness only (no timing).
 *
 * Per case:
 * - Reference A: [ComposeShadowReference], a line-for-line copy of `DropShadowRenderer.createOuterShadowBitmap` (both
 *   overloads), same public calls and the same `Paint` configuration (`configureShadow`).
 * - Reference B: Compose's public `DropShadowPainter(shape, Shadow(radius, Black, spread))` drawn into an ARGB_8888
 *   bitmap at the padding offset; its alpha is compared with A (copy fidelity).
 * - Obvid: the same geometry drawn with a plain anti-aliased paint into an A8 mask whose origin and size follow
 *   Skia's `compute_mask_bounds` (path bounds outset by ½, rounded out, intersected with the clip grown by
 *   min(margin, 128)), blurred by the JNI port, and blitted into a bitmap of Compose's size at
 *   (mask origin − border) by Android's own `drawBitmap` of an A8 bitmap (the same `blitMask` Skia's
 *   `filterPath` uses, clipped to the device). Path + spread: fill and stroke blurred separately, the stroke
 *   blitted over the fill the same way. A second composite applies Skia's A8 srcover-mask arithmetic by hand,
 *   `d = div255((255 − m)·d + 255·m)` (SkBlitter_A8.cpp `SkA8_Blitter::blitMask`), to check that it is what the
 *   device does.
 * - Diagnostics for the round-rect nine-patch route: the full-mask pipeline on the round rect moved to where the
 *   nine-patch effectively puts it (`nine17`: `roundOut(rect)`, Skia main / Android 16–17; `nine17t`: the same in a
 *   tight mask).
 *
 * - Obvid (NEON): the same masks blurred by `blurA8(…, neon = true)` (`blur::blur_a8_with`) instead of the scalar
 *   port, composited the same way (`obvidNeon` columns, appended at the end of each row), plus whether every NEON mask
 *   of the case is identical to its scalar mask (`neonEqScalar`).
 *
 * Output: `exactness.csv` in the additional test output dir (pulled by AGP) and the app's files dir, plus
 * the rows in logcat (tag `FS-Exactness`). Below the target Skia ([TargetSkia.isDevice]) also
 * `exactness-summary.txt`: the summary line and the difference of `obvid` from the device's Compose (PSNR),
 * in all and per Skia route.
 */
@RunWith(AndroidJUnit4::class)
class DropShadowExactnessTest {

  private val shapes = shapeCatalog()

  @Test
  fun exactness() {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val context = instrumentation.targetContext
    val density = Density(context)
    val rows = ArrayList<String>()
    rows += HEADER
    var identical = 0
    var identicalNeon = 0
    var neonEqScalar = 0
    var total = 0
    val tallies = LinkedHashMap<String, DiffTally>()
    for (shape in shapes) for (sizeDp in listOf(48, 200)) for (radiusDp in listOf(1, 4, 24, 64)) {
      for (spreadDp in listOf(0, 8)) {
        val row = runCase(density, shape, sizeDp, radiusDp, spreadDp)
        rows += row.csv()
        total++
        if (row.obvid.identical) identical++
        if (row.obvidNeon.identical) identicalNeon++
        if (row.neonEqScalar) neonEqScalar++
        for (k in listOf("all", row.skiaRoute)) tallies.getOrPut(k) { DiffTally() }.add(row.obvid)
        Log.i(TAG, row.csv())
      }
    }
    val summary =
      "identical $identical / $total, NEON identical $identicalNeon / $total, NEON == scalar masks $neonEqScalar / $total " +
        "(API ${Build.VERSION.SDK_INT}, density ${density.density})"
    Log.i(TAG, summary)
    writeResults(context, FILE_NAME, rows)
    if (!TargetSkia.isDevice) {
      val lines = listOf(summary, "${TargetSkia.offTargetLabel}, Obvid (alpha):") + tallies.map { (k, t) -> "  $k: $t" }
      lines.forEach { Log.i(TAG, it) }
      writeResults(context, SUMMARY_NAME, lines)
    }
  }

  // ---------------------------------------------------------------------------------------------------------------
  // One case
  // ---------------------------------------------------------------------------------------------------------------

  private fun runCase(density: Density, sc: ShapeCase, sizeDp: Int, radiusDp: Int, spreadDp: Int): Row {
    val g = CaseGeometry(density, sizeDp, radiusDp, spreadDp)
    val sizePx = g.sizePx
    val size = g.size
    val radius = g.radius
    val spread = g.spread
    val outline = sc.shape.createOutline(size, LayoutDirection.Ltr, density)

    val params = outlineParams(outline)
    val path = params.path
    val cornerRadius = params.cornerRadius

    val refA =
      if (path != null) ComposeShadowReference.refAPath(size, path, radius, spread)
      else ComposeShadowReference.refARoundRect(size, radius, spread, cornerRadius)
    val w = refA.width
    val h = refA.height
    val a = alphaOf(refA.asAndroidBitmap())

    val b = alphaOf(refB(density, sc.shape, size, radiusDp, spreadDp, w, h))
    val border = ObvidNative.builderBorder(radius)

    // Obvid
    val obvidAndroid: IntArray
    val obvidFormula: IntArray
    val obvidNeon: IntArray
    val neonEqScalar: Boolean
    var route: String
    var nine17: Diff? = null
    var nine17t: Diff? = null
    var smallCoverage = ",,"
    val zone: Zone
    if (path != null) {
      route = "mask(path)"
      val local = path.asAndroidPath()
      val t = radius + spread
      val dev = android.graphics.Path(local).apply { offset(t, t) }
      val masks = ArrayList<Blurred>()
      masks += blurPath(dev, w, h, radius, border)
      if (spread > 0f) {
        // Second Compose draw: the same path stroked 2·spread wide.
        masks += blurPath(strokeFillPath(local, spread, t), w, h, radius, border)
      }
      obvidAndroid = compositeAndroid(masks, w, h)
      obvidFormula = compositeFormula(masks, w, h)
      obvidNeon = compositeAndroid(masks, w, h, neon = true)
      neonEqScalar = masks.all { it.neonPixels.contentEquals(it.pixels) }
      val bounds = RectF().also { dev.computeBounds(it, true) }
      zone = Zone(bounds.left, bounds.top, bounds.right, bounds.bottom, maxCorner(outline), border)
    } else {
      val outset = radius * 2 + spread * 2
      val shadowWidth = size.width + outset
      val shadowHeight = size.height + outset
      val rect = RectF(radius, radius, shadowWidth - radius, shadowHeight - radius)
      val rx = cornerRadius.x
      val ry = cornerRadius.y
      route = predictRoundRectRoute(rect, rx, ry, radius, border)
      val masks = listOf(blurRoundRect(rect, rx, ry, w, h, radius, border))
      obvidAndroid = compositeAndroid(masks, w, h)
      obvidFormula = compositeFormula(masks, w, h)
      obvidNeon = compositeAndroid(masks, w, h, neon = true)
      neonEqScalar = masks.all { it.neonPixels.contentEquals(it.pixels) }
      zone = Zone(rect.left, rect.top, rect.right, rect.bottom, max(rx, ry), border)
      if (route == "nine(rrect)") {
        val r17 = RectF(floor(rect.left), floor(rect.top), ceil(rect.right), ceil(rect.bottom))
        nine17 = diff(a, compositeAndroid(listOf(blurRoundRect(r17, rx, ry, w, h, radius, border)), w, h), w, h, zone)
        nine17t =
          diff(a, compositeAndroid(listOf(blurRoundRect(r17, rx, ry, w, h, radius, border, tight = true)), w, h), w, h, zone)
        val tw = (ceil(rx).toInt() + border) * 2 + 1
        val th = (ceil(ry).toInt() + border) * 2 + 1
        smallCoverage = coverageVsSmall(tw.toFloat(), th.toFloat(), r17, rx, ry)
      }
    }

    return Row(
      shape = sc.name,
      composeRoute = if (path != null) "path" else "roundRect",
      skiaRoute = route,
      sizeDp = sizeDp,
      sizePx = sizePx,
      radiusDp = radiusDp,
      radiusPx = radius,
      spreadDp = spreadDp,
      spreadPx = spread,
      border = border,
      w = w,
      h = h,
      bVsA = diff(a, b, w, h, zone),
      obvid = diff(a, obvidAndroid, w, h, zone),
      formulaVsAndroid = diff(obvidAndroid, obvidFormula, w, h, zone),
      nine17 = nine17,
      nine17t = nine17t,
      smallCoverage = smallCoverage,
      obvidNeon = diff(a, obvidNeon, w, h, zone),
      neonEqScalar = neonEqScalar,
    )
  }

  // ---------------------------------------------------------------------------------------------------------------
  // Reference B: Compose's public DropShadowPainter (uncached provider), drawn at the padding offset
  // ---------------------------------------------------------------------------------------------------------------

  private fun refB(density: Density, shape: Shape, size: Size, radiusDp: Int, spreadDp: Int, w: Int, h: Int): Bitmap {
    val painter = DropShadowPainter(shape, Shadow(radius = radiusDp.dp, color = Color.Black, spread = spreadDp.dp))
    val pad = with(density) { radiusDp.dp.toPx() + spreadDp.dp.toPx() }
    val target = ImageBitmap(w, h, ImageBitmapConfig.Argb8888)
    CanvasDrawScope().draw(density, LayoutDirection.Ltr, ComposeCanvas(target), size) {
      translate(pad, pad) { with(painter) { draw(size) } }
    }
    return target.asAndroidBitmap()
  }

  // ---------------------------------------------------------------------------------------------------------------
  // Obvid: Canvas mask (no mask filter) + the JNI blur port, placed like Skia's filterPath blit (SkiaMaskModel.kt);
  // the nine-patch diagnostics and the blit by hand
  // ---------------------------------------------------------------------------------------------------------------

  private val plainPaint = maskPaint()

  /**
   * Diagnostic for the nine-patch residue: the unblurred coverage of the small rrect Skia blurs for the nine-patch
   * (`draw_rrect_into_mask`: rect (0, 0, [sw], [sh]) at the device origin, the same radii) against the coverage of
   * the full-size rrect [big] in a tight mask (origin roundOut(big)), each quadrant of the small mask aligned
   * to the matching corner of [big] by an integer offset. Returns "pixels,maxDiff,corners" (corners: TL TR BL BR
   * counts).
   */
  private fun coverageVsSmall(sw: Float, sh: Float, big: RectF, rx: Float, ry: Float): String {
    val smw = ceil(sw).toInt()
    val smh = ceil(sh).toInt()
    val small = Bitmap.createBitmap(smw, smh, Bitmap.Config.ALPHA_8)
    android.graphics.Canvas(small).drawRoundRect(RectF(0f, 0f, sw, sh), rx, ry, plainPaint)
    val s = alphaOf(small)
    // The big rrect in the tight mask (origin roundOut(big)), as in the nine17t variant.
    val mb = intArrayOf(floor(big.left).toInt(), floor(big.top).toInt(), ceil(big.right).toInt(), ceil(big.bottom).toInt())
    val bw = mb[2] - mb[0]
    val bh = mb[3] - mb[1]
    val bigMask = Bitmap.createBitmap(bw, bh, Bitmap.Config.ALPHA_8)
    android.graphics.Canvas(bigMask).apply {
      translate(-mb[0].toFloat(), -mb[1].toFloat())
      drawRoundRect(big, rx, ry, plainPaint)
    }
    val b = alphaOf(bigMask)
    // Integer offsets from small-mask coordinates to big-mask coordinates per side.
    val offL = big.left.toInt() - mb[0]
    val offT = big.top.toInt() - mb[1]
    val offR = (big.right - sw).toInt() - mb[0]
    val offB = (big.bottom - sh).toInt() - mb[1]
    var count = 0
    var maxD = 0
    val corners = IntArray(4)
    for (y in 0 until smh) for (x in 0 until smw) {
      val right = x * 2 >= smw
      val bottom = y * 2 >= smh
      val bx = x + if (right) offR else offL
      val by = y + if (bottom) offB else offT
      if (bx !in 0 until bw || by !in 0 until bh) continue
      val d = abs(s[y * smw + x] - b[by * bw + bx])
      if (d != 0) {
        count++
        maxD = max(maxD, d)
        corners[(if (bottom) 2 else 0) + (if (right) 1 else 0)]++
      }
    }
    // Translation check: the same small rrect drawn 1 px right and down (CTM translate(1, 1), integer). If Skia's
    // AA rasterizer were exactly invariant under integer translation this would be 0.
    val shifted = Bitmap.createBitmap(smw + 1, smh + 1, Bitmap.Config.ALPHA_8)
    android.graphics.Canvas(shifted).apply {
      translate(1f, 1f)
      drawRoundRect(RectF(0f, 0f, sw, sh), rx, ry, plainPaint)
    }
    val t = alphaOf(shifted)
    var shiftCount = 0
    for (y in 0 until smh) for (x in 0 until smw) if (s[y * smw + x] != t[(y + 1) * (smw + 1) + x + 1]) shiftCount++
    return "$count,$maxD,${corners.joinToString(" ")} shift1px=$shiftCount"
  }

  /** Skia's A8 srcover mask blit by hand (SkBlitter_A8.cpp): d = div255((255 − m)·d + 255·m), clipped. */
  private fun compositeFormula(masks: List<Blurred>, w: Int, h: Int): IntArray {
    val out = IntArray(w * h)
    for (m in masks) {
      for (y in max(0, m.y) until min(h, m.y + m.h)) for (x in max(0, m.x) until min(w, m.x + m.w)) {
        val c = m.pixels[(y - m.y) * m.w + (x - m.x)].toInt() and 0xFF
        val d = out[y * w + x]
        out[y * w + x] = div255((255 - c) * d + 255 * c)
      }
    }
    return out
  }

  private fun div255(prod: Int) = ((prod + 128) * 257) ushr 16

  // ---------------------------------------------------------------------------------------------------------------
  // Comparison
  // ---------------------------------------------------------------------------------------------------------------

  /**
   * Where a pixel sits relative to the outline's bounds [l, t, r, b]: within `corner + border` of two sides is
   * "corner", of one side "edge" (the band a nine-patch stretches), of none "center".
   */
  private class Zone(val l: Float, val t: Float, val r: Float, val b: Float, corner: Float, border: Int) {
    private val band = corner + border
    fun of(x: Int, y: Int): Int {
      val nx = x + 0.5f < l + band || x + 0.5f > r - band
      val ny = y + 0.5f < t + band || y + 0.5f > b - band
      return if (nx && ny) 0 else if (nx || ny) 1 else 2
    }
  }

  private class Diff(
    val maxDiff: Int,
    val count: Int,
    val bbox: String,
    val corner: Int,
    val edge: Int,
    val center: Int,
    val maxSigned: Int,
    val sse: Long,
    val samples: Long,
  ) {
    val identical get() = count == 0
    fun csv() = "${if (identical) 1 else 0},$maxDiff,$count,$bbox,$corner,$edge,$center,$maxSigned"
  }

  private fun DiffTally.add(d: Diff) = add(d.count, d.maxDiff, d.sse, d.samples)

  private fun diff(a: IntArray, b: IntArray, w: Int, h: Int, zone: Zone): Diff {
    var maxDiff = 0
    var maxSigned = 0
    var count = 0
    var sse = 0L
    var x0 = Int.MAX_VALUE
    var y0 = Int.MAX_VALUE
    var x1 = -1
    var y1 = -1
    val zones = IntArray(3)
    for (y in 0 until h) for (x in 0 until w) {
      val d = b[y * w + x] - a[y * w + x]
      if (d != 0) {
        count++
        sse += d * d
        if (abs(d) > maxDiff) {
          maxDiff = abs(d)
          maxSigned = d
        }
        x0 = min(x0, x)
        y0 = min(y0, y)
        x1 = max(x1, x)
        y1 = max(y1, y)
        zones[zone.of(x, y)]++
      }
    }
    val bbox = if (count == 0) "" else "$x0 $y0 $x1 $y1"
    return Diff(maxDiff, count, bbox, zones[0], zones[1], zones[2], maxSigned, sse, w.toLong() * h)
  }

  private fun maxCorner(outline: Outline): Float =
    when (outline) {
      is Outline.Rounded ->
        with(outline.roundRect) {
          maxOf(topLeftCornerRadius.x, topRightCornerRadius.x, bottomRightCornerRadius.x, bottomLeftCornerRadius.x)
        }
      else -> 0f
    }

  private class Row(
    val shape: String,
    val composeRoute: String,
    val skiaRoute: String,
    val sizeDp: Int,
    val sizePx: Float,
    val radiusDp: Int,
    val radiusPx: Float,
    val spreadDp: Int,
    val spreadPx: Float,
    val border: Int,
    val w: Int,
    val h: Int,
    val bVsA: Diff,
    val obvid: Diff,
    val formulaVsAndroid: Diff,
    val nine17: Diff?,
    val nine17t: Diff?,
    val smallCoverage: String,
    val obvidNeon: Diff,
    val neonEqScalar: Boolean,
  ) {
    fun csv() =
      listOf(
          Build.MODEL.replace(',', ' '),
          Build.VERSION.SDK_INT,
          shape,
          composeRoute,
          skiaRoute,
          sizeDp,
          sizePx,
          radiusDp,
          radiusPx,
          spreadDp,
          spreadPx,
          border,
          w,
          h,
          bVsA.csv(),
          obvid.csv(),
          formulaVsAndroid.csv(),
          nine17?.csv() ?: EMPTY_DIFF,
          nine17t?.csv() ?: EMPTY_DIFF,
          smallCoverage,
          obvidNeon.csv(),
          if (neonEqScalar) 1 else 0,
        )
        .joinToString(",")
  }

  private companion object {
    const val TAG = "FS-Exactness"
    const val FILE_NAME = "exactness.csv"
    const val SUMMARY_NAME = "exactness-summary.txt"
    const val EMPTY_DIFF = ",,,,,,,"
    private fun diffCols(p: String) =
      listOf("identical", "maxDiff", "diffPixels", "bbox", "corner", "edge", "center", "maxSigned").joinToString(",") {
        "${p}_$it"
      }
    val HEADER =
      "model,api,shape,composeRoute,skiaRoute,sizeDp,sizePx,radiusDp,radiusPx,spreadDp,spreadPx,border,bitmapW,bitmapH," +
        listOf("B", "obvid", "formulaVsAndroid", "nine17", "nine17t").joinToString(",") { diffCols(it) } +
        ",smallCov_diffPixels,smallCov_maxDiff,smallCov_where," +
        diffCols("obvidNeon") +
        ",neonEqScalar"
  }
}
