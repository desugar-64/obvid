// Copyright 2026, Serhii Yaremych (desugar-64) and the Obvid project contributors
// SPDX-License-Identifier: Apache-2.0

package dev.serhiiyaremych.obvid

import android.graphics.Bitmap
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageBitmapConfig
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.shadow.InnerShadowPainter
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import dev.serhiiyaremych.obvid.fixtures.CaseGeometry
import dev.serhiiyaremych.obvid.fixtures.InnerShadowReference
import dev.serhiiyaremych.obvid.fixtures.OutlineParams
import dev.serhiiyaremych.obvid.fixtures.ShapeCase
import dev.serhiiyaremych.obvid.fixtures.alphaOf
import dev.serhiiyaremych.obvid.fixtures.catalogShape
import dev.serhiiyaremych.obvid.fixtures.configureShadow
import dev.serhiiyaremych.obvid.fixtures.maskBounds
import dev.serhiiyaremych.obvid.fixtures.maskPaint
import dev.serhiiyaremych.obvid.fixtures.outlineParams
import java.nio.ByteBuffer
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import androidx.compose.ui.graphics.Canvas as ComposeCanvas

/**
 * Measures which models of Compose 1.13.0's inner-shadow bitmap are identical to it: each model is built from
 * masks drawn by the platform Canvas and the JNI blur (`ObvidTesting.blurA8`, scalar port; NEON compared to it),
 * blitted by Android's own A8 `drawBitmap`. Correctness only (no timing). Asserted: Reference B on every API level,
 * the `Xor` identity on the target Skia ([TargetSkia.isDevice]; elsewhere only recorded, and the summary adds the
 * expected-exact models' difference from the device's Compose, PSNR). Every model is only recorded, with whether its
 * case is expected to be exact.
 *
 * The models follow one Skia, Android 16–17's (sprite rule, nine-patch size test, `SkScalarRoundToInt` in double),
 * whatever device runs the test: the target is that output everywhere, so on older Android the "expected exact" flags
 * describe the target, not the device's own Skia. Each case allocates its bitmaps and arrays afresh and frees them
 * when it ends; only the pixel-free row stays.
 *
 * - Reference A: [InnerShadowReference], the copy of `createInnerPathShadowBrush` / `createInnerShadowBrush`.
 * - Reference B: Compose's public `InnerShadowPainter(shape, Shadow(radius, Black, spread, offset))` (uncached
 *   provider) drawn into an ARGB_8888 bitmap of the component's size, against A drawn through the same final draw
 *   ([InnerShadowReference.draw]); every channel compared.
 * - `H1`: an A8 bitmap holding every value; an opaque `Xor` rect over it gives exactly 255 − d.
 *
 * Every model is "255 − B" over the whole bitmap, B being what the blurred draw leaves in an empty bitmap of Compose's
 * size:
 * - `H2` path, spread ≤ 0: the path moved by the offset, its mask bounds by `compute_mask_bounds` on the padded
 *   bitmap, blurred, blitted. A path that is a rect (`Path.isRect`: a round rect with corners (x, 0) arrives as one)
 *   goes to Skia's rect route instead: `H5`, exact only on the full-mask fallback.
 * - `H3` path, spread > 0: the intermediate built by the platform Canvas with Compose's calls (fill, non-AA clip,
 *   `Clear` stroke 2·spread wide), then Skia's `drawBitmapAsMask`. Nothing when the intermediate's rounded-out
 *   bounds miss the bitmap (`clipped_out`). A sprite (`SkTreatAsSprite` with Compose's linear filtering: an integer
 *   offset on both axes, then the 4-subpixel-bit test for an AA paint) blurs the intermediate itself at that offset,
 *   unclipped. Otherwise the pre-blur mask is the platform `drawBitmap(…, AA | FILTER)` of the intermediate into a
 *   zeroed bitmap of the shadow's size, cut to the rounded mapped bounds (Skia's temporary mask).
 * - `H4` round rect: the inset, moved round rect as a full mask; exact where Skia takes the full mask (`mask(oval)`,
 *   `mask(rrect)`), a diagnostic where it takes the nine-patch (`nine(rrect)`, Android 16+'s integer-piece size test).
 * - `H5`: an empty inset rect (spread ≥ half the size) leaves every pixel 255; and the rect paths above.
 * - `RECT` (`Outline.Rectangle`): the rect as a full mask; exact only on Skia's full-mask fallback (`mask(rect)`), a
 *   diagnostic on its `BlurRect` nine-patch.
 * - Radius 0 (no mask filter): the shape drawn directly (`direct`), or the unblurred pre-blur mask (`H3`).
 *
 * Output (subdir `inner0` of the app's files dir and AGP's additional test output dir): `inner0-exactness.csv`, one
 * row per case, and `inner0-exactness-summary.txt`, per hypothesis: cases, identical, max diff, first mismatching
 * case ids. Logcat tag `FS-Inner0`.
 */
@LargeTest
@RunWith(AndroidJUnit4::class)
class InnerShadowExactnessTest {

  private val plainPaint = maskPaint()

  @Test
  fun exactness() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val density = Density(context)
    val start = SystemClock.elapsedRealtime()

    val xor = xorCheck()
    val cases = grid()
    Log.i(TAG, "${cases.size} cases (API ${Build.VERSION.SDK_INT}, ${Build.MODEL}, density ${density.density})")

    val rows = ArrayList<Row>(cases.size)
    var logged = 0
    for ((i, c) in cases.withIndex()) {
      val row = runCase(density, i, c)
      rows += row
      if ((!row.b.identical || (row.model.expectedExact && !row.obvid.identical)) && logged++ < MAX_LOGGED_ROWS) {
        Log.i(TAG, row.csv())
      }
    }
    val seconds = (SystemClock.elapsedRealtime() - start) / 1000.0

    val summary = summary(rows, xor, density, seconds)
    summary.forEach { Log.i(TAG, it) }
    writeResults(context, CSV_NAME, listOf(HEADER) + rows.map { it.csv() }, RESULTS_DIR)
    writeResults(context, SUMMARY_NAME, summary, RESULTS_DIR)

    if (TargetSkia.isDevice) {
      assertTrue("Xor over A8 is not 255 − d (${xor.pixels} pixels, max ${xor.max})", xor.identical)
    }
    val bMismatches = rows.filter { !it.b.identical }
    assertEquals(
      "Reference B != A in ${bMismatches.take(5).joinToString { it.key }}",
      0,
      bMismatches.size,
    )
  }

  // ---------------------------------------------------------------------------------------------------------------
  // Grid
  // ---------------------------------------------------------------------------------------------------------------

  private class OffsetContext(val density: Density, val sizePx: Float, val radiusPx: Float, val border: Int) {
    /** An offset given in px, as the Dp a caller would pass (it comes back as px through `toPx`). */
    fun px(x: Float, y: Float) = DpOffset((x / density.density).dp, (y / density.density).dp)

    /** Past the bitmap and the blur on any route. */
    val far
      get() = sizePx + 2 * ceil(radiusPx) + border + 8
  }

  private class OffsetCase(val name: String, val at: OffsetContext.() -> DpOffset)

  private class Case(
    val shape: ShapeCase,
    val pathRoute: Boolean,
    val sizeDp: Int,
    val radiusPx: Float,
    val spreadDp: Int,
    val offset: OffsetCase,
  ) {
    val key
      get() = "${shape.name}/${sizeDp}dp/r${fmt(radiusPx)}/s$spreadDp/${offset.name}"
  }

  /**
   * Round-rect route shapes × path shapes × 48 / 200 dp × radius 0 / 1 / 4 / 24 / 64 / 100 px × spread 0 / 8 / −8 dp
   * × [BASE_OFFSETS]; path shapes with spread 0 / 8 dp also × [FRACTION_OFFSETS], with −8 dp (ignored by Compose's
   * path build) only × [FEW_OFFSETS]; at 48 dp, spread 24 / 30 dp (an empty or eroded shape) × [FEW_OFFSETS].
   */
  private fun grid(): List<Case> {
    val shapes =
      listOf("rect", "rrect16", "circle").map { ShapeCase(it, catalogShape(it)) } +
        ShapeCase("rrect49pct", RoundedCornerShape(percent = 49)) +
        listOf("mixed", "morph").map { ShapeCase(it, catalogShape(it)) } +
        ShapeCase("rrectX0", CornerX0Shape(16.dp)) +
        ShapeCase("morph03", PlacedMorphShape(0.3f, 0.3f)) +
        ShapeCase("morph07", PlacedMorphShape(0.7f, 0.7f)) +
        ShapeCase("morphNeg", PlacedMorphShape(0.55f, 0.45f, negative = true))
    val pathShapes = setOf("mixed", "morph", "rrectX0", "morph03", "morph07", "morphNeg")
    val cases = ArrayList<Case>()
    for (shape in shapes) {
      val path = shape.name in pathShapes
      for (sizeDp in SIZES_DP) for (radiusPx in RADII_PX) {
        for (spreadDp in SPREADS_DP) {
          val offsets =
            when {
              !path -> BASE_OFFSETS
              spreadDp >= 0 -> BASE_OFFSETS + FRACTION_OFFSETS
              else -> FEW_OFFSETS
            }
          for (o in offsets) cases += Case(shape, path, sizeDp, radiusPx, spreadDp, o)
        }
        if (sizeDp == 48) for (spreadDp in DEGENERATE_SPREADS_DP) for (o in FEW_OFFSETS) {
          cases += Case(shape, path, sizeDp, radiusPx, spreadDp, o)
        }
      }
    }
    return cases
  }

  // ---------------------------------------------------------------------------------------------------------------
  // One case
  // ---------------------------------------------------------------------------------------------------------------

  private fun runCase(density: Density, id: Int, c: Case): Row {
    val g = CaseGeometry(density, c.sizeDp, 0, c.spreadDp)
    val size = g.size
    val spread = g.spread
    val radiusDp = (c.radiusPx / density.density).dp
    val radius = with(density) { radiusDp.toPx() }
    val border = if (radius > 0f) ObvidNative.builderBorder(radius) else 0
    val offsetDp = OffsetContext(density, g.sizePx, radius, border).let { c.offset.at(it) }
    val ox = with(density) { offsetDp.x.toPx() }
    val oy = with(density) { offsetDp.y.toPx() }

    val outline = c.shape.shape.createOutline(size, LayoutDirection.Ltr, density)
    val params = outlineParams(outline)
    val path = params.path
    val refA = InnerShadowReference.referenceA(params, size, radius, spread, ox, oy)
    val w = refA.width
    val h = refA.height
    val a = alphaOf(refA.asAndroidBitmap())

    // Reference B against A, both through the renderer's final draw into an ARGB bitmap of the component's size.
    val painter =
      InnerShadowPainter(c.shape.shape, Shadow(radius = radiusDp, color = Color.Black, spread = c.spreadDp.dp, offset = offsetDp))
    val bPixels = drawComponent(density, size) { with(painter) { draw(size) } }
    val aPixels = drawComponent(density, size) { InnerShadowReference.draw(this, refA, params) }
    refA.asAndroidBitmap().recycle()
    val b = ScreenCases.diff(aPixels, bPixels)

    val built =
      when {
        path != null && spread > 0f -> modelPathSpread(path, w, h, radius, spread, border, ox, oy)
        path != null -> modelPath(path, w, h, radius, border, ox, oy)
        else -> modelRoundRect(size, params, w, h, radius, spread, border, ox, oy)
      }
    val obvid = ScreenCases.diffAlpha(a, built.pixels)
    return Row(id, c, g.sizePx, radius, border, spread, ox, oy, w, h, built.model, b, obvid)
  }

  private fun drawComponent(density: Density, size: Size, block: DrawScope.() -> Unit): IntArray {
    val target = ImageBitmap(ceil(size.width).toInt(), ceil(size.height).toInt(), ImageBitmapConfig.Argb8888)
    CanvasDrawScope().draw(density, LayoutDirection.Ltr, ComposeCanvas(target), size, block)
    val bitmap = target.asAndroidBitmap()
    return ScreenCases.pixelsOf(bitmap).also { bitmap.recycle() }
  }

  // ---------------------------------------------------------------------------------------------------------------
  // Models
  // ---------------------------------------------------------------------------------------------------------------

  /** What a case's model is and which Skia route it stands for; kept per row (no pixels). */
  private class Model(
    val hypothesis: String,
    val route: String,
    val expectedExact: Boolean,
    val geom: RectF?,
    val rx: Float = 0f,
    val ry: Float = 0f,
    val interW: Int = -1,
    val interH: Int = -1,
    val sprite: Boolean? = null,
    val neonEqScalar: Boolean? = null,
  )

  /** A model and its bitmap (alpha per pixel), alive for one case only. */
  private class Built(val model: Model, val pixels: IntArray)

  /** Path, spread ≤ 0: the path moved by the offset, full mask (or Skia's rect route for a rect path). */
  private fun modelPath(path: Path, w: Int, h: Int, radius: Float, border: Int, ox: Float, oy: Float): Built {
    val dev = android.graphics.Path(path.asAndroidPath()).apply { offset(ox, oy) }
    val bounds = RectF().also { dev.computeBounds(it, true) }
    val asRect = RectF()
    val isRect = dev.isRect(asRect)
    if (radius <= 0f) {
      return Built(Model(if (isRect) "H5" else "H2", "direct", true, bounds), invert(direct(w, h) { it.drawPath(dev, plainPaint) }))
    }
    val masks = if (maskBounds(bounds, w, h, border) == null) emptyList() else listOf(blurPath(dev, w, h, radius, border))
    val pixels = invert(compositeAndroid(masks, w, h))
    val neonEq = masks.all { it.neonPixels.contentEquals(it.pixels) }
    if (isRect) {
      val route = predictRectRoute(asRect, radius)
      return Built(Model("H5", "path:$route", route == "mask(rect)", bounds, neonEqScalar = neonEq), pixels)
    }
    return Built(Model("H2", "mask(path)", true, bounds, neonEqScalar = neonEq), pixels)
  }

  /** Round rect or rect: Compose's inset, moved rect as a full mask, with the route Skia is predicted to take. */
  private fun modelRoundRect(
    size: Size,
    params: OutlineParams,
    w: Int,
    h: Int,
    radius: Float,
    spread: Float,
    border: Int,
    ox: Float,
    oy: Float,
  ): Built {
    val left = ox + spread
    val top = oy + spread
    val right = max(left, ox + size.width - spread)
    val bottom = max(top, oy + size.height - spread)
    val rect = RectF(left, top, right, bottom)
    val rx = params.cornerRadius.x
    val ry = params.cornerRadius.y
    if (rect.isEmpty) return Built(Model("H5", "empty", true, rect, rx, ry), invert(IntArray(w * h)))
    val hypothesis = if (rx > 0f && ry > 0f) "H4" else "RECT"
    if (radius <= 0f) {
      val pixels = direct(w, h) { it.drawRoundRect(left, top, right, bottom, rx, ry, plainPaint) }
      return Built(Model(hypothesis, "direct", true, rect, rx, ry), invert(pixels))
    }
    val route = predictRoundRectRoute(rect, rx, ry, radius, border)
    val masks =
      if (maskBounds(rect, w, h, border) == null) emptyList() else listOf(blurRoundRect(rect, rx, ry, w, h, radius, border))
    val model =
      Model(
        hypothesis,
        route,
        route.startsWith("mask("),
        rect,
        rx,
        ry,
        neonEqScalar = masks.all { it.neonPixels.contentEquals(it.pixels) },
      )
    return Built(model, invert(compositeAndroid(masks, w, h)))
  }

  /**
   * Path, spread > 0: the eroded intermediate drawn as a mask (`drawBitmapAsMask`) at the offset over the opaque
   * bitmap: sprite, filtered or clipped out.
   */
  private fun modelPathSpread(
    path: Path,
    w: Int,
    h: Int,
    radius: Float,
    spread: Float,
    border: Int,
    ox: Float,
    oy: Float,
  ): Built {
    val local = path.asAndroidPath()
    val pb = RectF().also { local.computeBounds(it, true) }
    val pw = pb.width()
    val ph = pb.height()
    val iw = ceil(pw).toInt()
    val ih = ceil(ph).toInt()
    val inter = Bitmap.createBitmap(iw, ih, Bitmap.Config.ALPHA_8)
    android.graphics.Canvas(inter).apply {
      drawPath(local, composePaint())
      clipRect(0f, 0f, pw, ph)
      drawPath(
        local,
        composePaint().apply {
          style = android.graphics.Paint.Style.STROKE
          strokeWidth = spread * 2f
          xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
        },
      )
    }

    // The intermediate's device rect (SkMatrix::mapRect of a translate).
    val dst = RectF(ox, oy, ox + iw, oy + ih)
    val clippedOut = !(floor(dst.left) < w && ceil(dst.right) > 0 && floor(dst.top) < h && ceil(dst.bottom) > 0)
    val sprite = treatAsSprite(dst, iw, ih)

    // Pre-blur mask: (x, y, w, h, bytes) on the device.
    var mx = 0
    var my = 0
    var mw = 0
    var mh = 0
    var bytes: ByteArray? = null
    if (!clippedOut) {
      if (sprite) {
        mx = skRound(ox)
        my = skRound(oy)
        mw = iw
        mh = ih
        bytes = bytesOf(alphaOf(inter))
      } else {
        val device = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8)
        inter.density = device.density
        android.graphics.Canvas(device)
          .drawBitmap(
            inter,
            ox,
            oy,
            android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG or android.graphics.Paint.FILTER_BITMAP_FLAG),
          )
        val l = max(skRound(dst.left), 0)
        val t = max(skRound(dst.top), 0)
        val r = min(skRound(dst.right), w)
        val b = min(skRound(dst.bottom), h)
        if (l < r && t < b) {
          mx = l
          my = t
          mw = r - l
          mh = b - t
          val px = IntArray(mw * mh)
          device.getPixels(px, 0, mw, l, t, mw, mh)
          bytes = ByteArray(px.size) { (px[it] ushr 24).toByte() }
        }
        device.recycle()
      }
    }
    inter.recycle()

    val masks =
      when {
        bytes == null -> emptyList()
        radius > 0f -> listOf(blurMask(bytes, mx, my, mw, mh, radius, border))
        else -> listOf(Blurred(mx, my, mw, mh, bytes, bytes))
      }
    val route =
      (if (clippedOut) "image(clippedOut)" else if (sprite) "image(sprite)" else "image(filter)") +
        if (radius > 0f) "" else "+noblur"
    val model =
      Model(
        "H3",
        route,
        true,
        pb,
        interW = iw,
        interH = ih,
        sprite = !clippedOut && sprite,
        neonEqScalar = masks.all { it.neonPixels.contentEquals(it.pixels) },
      )
    return Built(model, invert(compositeAndroid(masks, w, h)))
  }

  /**
   * `SkTreatAsSprite` for the translate to [dst] with Compose's paint (anti-aliased, linear filtering): no fractional
   * translate (linear filtering, b/41322892), and the mapped bounds at 4 subpixel bits round to the integer bounds
   * moved by the rounded translate.
   */
  private fun treatAsSprite(dst: RectF, iw: Int, ih: Int): Boolean {
    if (dst.left != dst.left.toInt().toFloat() || dst.top != dst.top.toInt().toFloat()) return false
    val ix = skRound(dst.left)
    val iy = skRound(dst.top)
    return (ix shl 4) == skRound(dst.left * 16f) &&
      (iy shl 4) == skRound(dst.top * 16f) &&
      ((ix + iw) shl 4) == skRound(dst.right * 16f) &&
      ((iy + ih) shl 4) == skRound(dst.bottom * 16f)
  }

  /** `SkScalarRoundToInt`: floor(x + 0.5) in double. */
  private fun skRound(x: Float): Int = floor(x.toDouble() + 0.5).toInt()

  /** Compose's `Paint()` as Android's: anti-aliased, dithered, bitmap filtering; black, src-over, fill. */
  private fun composePaint() =
    android.graphics.Paint(
        android.graphics.Paint.ANTI_ALIAS_FLAG or
          android.graphics.Paint.DITHER_FLAG or
          android.graphics.Paint.FILTER_BITMAP_FLAG
      )
      .apply { color = android.graphics.Color.BLACK }

  /** [draw] straight into a zeroed A8 bitmap of [w] × [h] (no mask filter). */
  private fun direct(w: Int, h: Int, draw: (android.graphics.Canvas) -> Unit): IntArray {
    val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8)
    draw(android.graphics.Canvas(bitmap))
    return alphaOf(bitmap).also { bitmap.recycle() }
  }

  /** 255 − v in place. */
  private fun invert(px: IntArray): IntArray {
    for (i in px.indices) px[i] = 255 - px[i]
    return px
  }

  private fun bytesOf(px: IntArray) = ByteArray(px.size) { px[it].toByte() }

  // ---------------------------------------------------------------------------------------------------------------
  // H1: Xor over A8
  // ---------------------------------------------------------------------------------------------------------------

  /** Every value 0..255 (rows ascending and descending), then Compose's opaque full-bitmap `Xor` rect. */
  private fun xorCheck(): ScreenCases.Diff {
    val w = 256
    val h = 2
    val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8)
    val rb = bitmap.rowBytes
    val buf = ByteArray(rb * h)
    for (x in 0 until w) {
      buf[x] = x.toByte()
      buf[rb + x] = (255 - x).toByte()
    }
    bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(buf))
    val before = alphaOf(bitmap)
    ComposeCanvas(bitmap.asImageBitmap())
      .drawRect(0f, 0f, w.toFloat(), h.toFloat(), Paint().configureShadow(blendMode = BlendMode.Xor))
    return ScreenCases.diff(invert(before), alphaOf(bitmap))
  }

  // ---------------------------------------------------------------------------------------------------------------
  // Output
  // ---------------------------------------------------------------------------------------------------------------

  private class Row(
    val id: Int,
    val case: Case,
    val sizePx: Float,
    val radius: Float,
    val border: Int,
    val spread: Float,
    val ox: Float,
    val oy: Float,
    val w: Int,
    val h: Int,
    val model: Model,
    val b: ScreenCases.Diff,
    val obvid: ScreenCases.Diff,
  ) {
    val key
      get() = "#$id ${case.key}"

    fun csv(): String {
      val g = model.geom
      return listOf(
          id,
          case.key,
          Build.MODEL.replace(',', ' '),
          Build.VERSION.SDK_INT,
          model.hypothesis,
          bit(model.expectedExact),
          case.shape.name,
          if (case.pathRoute) "path" else "roundRect",
          model.route,
          case.sizeDp,
          sizePx,
          radius,
          border,
          case.spreadDp,
          spread,
          case.offset.name,
          ox,
          oy,
          w,
          h,
          g?.left ?: "",
          g?.top ?: "",
          g?.right ?: "",
          g?.bottom ?: "",
          model.rx,
          model.ry,
          model.interW.takeIf { it >= 0 } ?: "",
          model.interH.takeIf { it >= 0 } ?: "",
          model.sprite?.let(::bit) ?: "",
          bit(b.identical),
          b.pixels,
          b.max,
          bit(obvid.identical),
          obvid.pixels,
          obvid.max,
          model.neonEqScalar?.let(::bit) ?: "",
        )
        .joinToString(",")
    }
  }

  private fun summary(rows: List<Row>, xor: ScreenCases.Diff, density: Density, seconds: Double): List<String> {
    val out = ArrayList<String>()
    out += "InnerShadowExactnessTest: ${rows.size} cases, ${Build.MODEL}, API ${Build.VERSION.SDK_INT}, " +
      "density ${density.density}, ${"%.1f".format(seconds)} s"
    out += "H1 Xor (A8, every value, opaque Xor rect == 255 − d): " +
      (if (xor.identical) "identical" else "DIFFERS (${xor.pixels} pixels, max ${xor.max})")
    out += line("Reference B == A (ARGB, all channels)", rows) { it.b }
    out += ""
    out += "Expected exact (should match), per hypothesis:"
    for ((hyp, group) in rows.filter { it.model.expectedExact }.groupBy { it.model.hypothesis }.toSortedMap()) {
      out += line("  $hyp", group) { it.obvid }
    }
    out += "Diagnostic (not expected exact), per hypothesis:"
    for ((hyp, group) in rows.filter { !it.model.expectedExact }.groupBy { it.model.hypothesis }.toSortedMap()) {
      out += line("  $hyp", group) { it.obvid }
    }
    out += ""
    out += "Per hypothesis and route (exact = expected exact):"
    val byRoute = rows.groupBy { Triple(it.model.hypothesis, it.model.route, it.model.expectedExact) }
    for ((k, group) in byRoute.entries.sortedWith(compareBy({ it.key.first }, { it.key.second }))) {
      out += line("  ${k.first} ${k.second}${if (k.third) " exact" else " diagnostic"}", group) { it.obvid }
    }
    out += ""
    val h3 = rows.filter { it.model.hypothesis == "H3" && !it.model.route.startsWith("image(clippedOut)") }
    out += "H3: sprite ${h3.count { it.model.sprite == true }}, filter ${h3.count { it.model.sprite == false }}"
    val blurred = rows.filter { it.model.neonEqScalar != null }
    out += "NEON blur == scalar on ${blurred.count { it.model.neonEqScalar == true }} / ${blurred.size} modelled cases"
    if (!TargetSkia.isDevice) {
      val expected = rows.filter { it.model.expectedExact }
      out += ""
      out += "${TargetSkia.offTargetLabel}, expected-exact models (alpha):"
      for ((hyp, group) in expected.groupBy { it.model.hypothesis }.toSortedMap()) {
        out += "  $hyp: " + DiffTally().apply { group.forEach { add(it.obvid) } }
      }
      out += "  all: " + DiffTally().apply { expected.forEach { add(it.obvid) } }
    }
    return out
  }

  private fun line(label: String, rows: List<Row>, diff: (Row) -> ScreenCases.Diff): String {
    val bad = rows.filter { !diff(it).identical }
    val maxDiff = rows.maxOfOrNull { diff(it).max } ?: 0
    val first = if (bad.isEmpty()) "" else "; first mismatches: " + bad.take(FIRST_MISMATCHES).joinToString("; ") { it.key }
    return "$label: cases ${rows.size}, identical ${rows.size - bad.size}, max diff $maxDiff$first"
  }

  private companion object {
    const val TAG = "FS-Inner0"
    const val RESULTS_DIR = "inner0"
    const val CSV_NAME = "inner0-exactness.csv"
    const val SUMMARY_NAME = "inner0-exactness-summary.txt"
    const val MAX_LOGGED_ROWS = 100
    const val FIRST_MISMATCHES = 5

    val SIZES_DP = listOf(48, 200)
    val RADII_PX = listOf(0f, 1f, 4f, 24f, 64f, 100f)
    val SPREADS_DP = listOf(0, 8, -8)
    val DEGENERATE_SPREADS_DP = listOf(24, 30)

    /** Zero, whole px at 8-dp multiples, fractional px, partly off, and past the bitmap and blur on each axis. */
    val BASE_OFFSETS =
      listOf(
        OffsetCase("zero") { DpOffset.Zero },
        OffsetCase("dp8") { DpOffset(8.dp, 16.dp) },
        OffsetCase("dpNeg8") { DpOffset((-8).dp, (-24).dp) },
        OffsetCase("dp4") { DpOffset(4.dp, (-2).dp) },
        OffsetCase("px3.25") { px(3.25f, -7.5f) },
        OffsetCase("partOff") { px(0.6f * sizePx + 0.3f, -0.35f * sizePx) },
        OffsetCase("offX") { px(far, 0f) },
        OffsetCase("offY") { px(0f, -far) },
      )

    /** Around the sprite test's 1/32 px threshold, quarters and halves, negative. */
    val FRACTION_OFFSETS =
      listOf(
        OffsetCase("f1/64") { px(1f / 64, 1f / 64) },
        OffsetCase("f1/32-e") { px(1f / 32 - 1f / 256, -(1f / 32 - 1f / 256)) },
        OffsetCase("f1/32+e") { px(1f / 32 + 1f / 256, 0f) },
        OffsetCase("f0.25") { px(0.25f, 0.25f) },
        OffsetCase("f0.5") { px(0.5f, -0.5f) },
        OffsetCase("fNeg1/64") { px(-5f - 1f / 64, -2f) },
        OffsetCase("fNeg") { px(-3.75f, -1.25f) },
      )

    val FEW_OFFSETS = BASE_OFFSETS.filter { it.name in setOf("zero", "px3.25", "partOff") }

    const val HEADER =
      "caseId,key,model,api,hypothesis,expectedExact,shape,composeRoute,skiaRoute,sizeDp,sizePx,radiusPx,border," +
        "spreadDp,spreadPx,offset,offsetX,offsetY,bitmapW,bitmapH,geomL,geomT,geomR,geomB,rx,ry,interW,interH," +
        "sprite,B_identical,B_diffPixels,B_maxDiff,identical,diffPixels,maxDiff,neonEqScalar"

    fun bit(b: Boolean) = if (b) 1 else 0

    fun fmt(f: Float) = if (f == f.toInt().toFloat()) f.toInt().toString() else f.toString()
  }
}
