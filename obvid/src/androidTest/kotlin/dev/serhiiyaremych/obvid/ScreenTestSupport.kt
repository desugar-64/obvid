// Copyright 2026, Serhii Yaremych (desugar-64) and the Obvid project contributors
// SPDX-License-Identifier: Apache-2.0

package dev.serhiiyaremych.obvid

import android.app.Activity
import android.graphics.Bitmap
import android.os.Build
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.DropShadowScope
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import dev.serhiiyaremych.obvid.fixtures.ShapeCase
import dev.serhiiyaremych.obvid.fixtures.roundRectShapes
import dev.serhiiyaremych.obvid.fixtures.shapeCatalog
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import androidx.compose.ui.draw.dropShadow as composeDropShadow

/** On-screen tests: the shared case definitions and the cell both implementations draw into. */
internal object ScreenCases {

  /**
   * The subdirectory of the app's files dir the screen, race and rebuild tests write their results to (see
   * [outputDirs]).
   */
  const val RESULTS_DIR = "screen-tests"

  val shapes = shapeCatalog()

  /** The paint side of a case: everything that must not rebuild. */
  class Paint(
    val name: String,
    val color: Color = Color.Black,
    val brush: Brush? = null,
    val alpha: Float = 1f,
    val blendMode: BlendMode = BlendMode.SrcOver,
    val offset: DpOffset = DpOffset.Zero,
  )

  val basePaint = Paint("black")

  val paintVariants =
    listOf(
      Paint("opaque-color", color = Color(0xFF2050C0)),
      Paint("translucent-color", color = Color(0x80FF2050)),
      Paint("brush-linear", brush = Brush.linearGradient(listOf(Color.Red, Color.Blue))),
      Paint("alpha-0.5", alpha = 0.5f),
      Paint("multiply", color = Color(0xFF8040C0), blendMode = BlendMode.Multiply),
      Paint("screen", color = Color(0xFF8040C0), blendMode = BlendMode.Screen),
      Paint("dstout", blendMode = BlendMode.DstOut),
      Paint("offset-whole-dp", offset = DpOffset(4.dp, 6.dp)),
      Paint("offset-fractional", offset = DpOffset(3.3.dp, (-2.7).dp)),
      Paint(
        "brush-alpha-multiply-offset",
        brush = Brush.linearGradient(listOf(Color(0xFF00C080), Color(0xFFC000C0))),
        alpha = 0.5f,
        blendMode = BlendMode.Multiply,
        offset = DpOffset(2.5.dp, 1.25.dp),
      ),
    )

  class Case(
    val shape: ShapeCase,
    val sizeDp: Int,
    val radiusDp: Int,
    val spreadDp: Int,
    val paint: Paint,
    val block: Boolean,
  ) {
    val label
      get() = "${shape.name} ${sizeDp}dp r$radiusDp s$spreadDp ${paint.name} ${if (block) "block" else "shadow"}"
  }

  /**
   * 120 geometries (spread 0 / 8 / −8 dp) × the base paint × both overloads, then 10 paint variants on 10 geometries ×
   * both overloads.
   */
  val cases: List<Case> =
    buildList {
      for (block in listOf(false, true)) {
        for (s in shapes) for (size in listOf(48, 200)) for (r in listOf(1, 4, 24, 64)) for (sp in listOf(0, 8, -8)) {
          add(Case(s, size, r, sp, basePaint, block))
        }
      }
      for (block in listOf(false, true)) for (p in paintVariants) for (s in shapes) {
        add(Case(s, 200, 4, 8, p, block))
        add(Case(s, 48, 24, 0, p, block))
      }
    }

  /**
   * The round-rect routes (Skia's nine-patch on Obvid's pipeline and its full-mask fallback): 81 cases, corners 4 / 40
   * / 6.3 dp ([roundRectShapes]; 16 dp is `rrect16` above) × 48 / 120 / 360 dp × radius 1 / 24 / 64 dp × spread 0 / 8 /
   * −8 dp, base paint, `Shadow` overload. Separate from [cases], so the main cases' summary lines (groups `geometry`
   * and `paint`) do not include them.
   */
  val roundCases: List<Case> =
    buildList {
      for (s in roundRectShapes().filter { it.name != "rrect16" }) {
        for (size in listOf(48, 120, 360)) for (r in listOf(1, 24, 64)) for (sp in listOf(0, 8, -8)) {
          add(Case(s, size, r, sp, basePaint, false))
        }
      }
    }

  /** The case that grows a node's bitmap past every case before the node draws the case again (`grown` phase). */
  fun bigCase(c: Case) = Case(shapes.first { it.name == "morph" }, 200, 64, 8, c.paint, c.block)

  val CELL: Dp = 380.dp
  val CONTENT = Color(0x803060FF)

  fun shadowOf(radius: Dp, spread: Dp, p: Paint): Shadow =
    if (p.brush != null) Shadow(radius, p.brush, spread, p.offset, p.alpha, p.blendMode)
    else Shadow(radius, p.color, spread, p.offset, p.alpha, p.blendMode)

  enum class Impl {
    Compose,
    Obvid,
  }

  /** The shadow modifier for [impl], with the case's overload. Dp parameters converted in the block, as a caller would. */
  fun Modifier.caseShadow(impl: Impl, shape: Shape, radiusDp: Dp, spreadDp: Dp, p: Paint, block: Boolean): Modifier =
    if (!block) {
      val shadow = shadowOf(radiusDp, spreadDp, p)
      if (impl == Impl.Compose) composeDropShadow(shape, shadow) else dropShadow(shape, shadow)
    } else {
      val lambda: DropShadowScope.() -> Unit = {
        radius = radiusDp.toPx()
        spread = spreadDp.toPx()
        this.offset = Offset(p.offset.x.toPx(), p.offset.y.toPx())
        if (p.brush != null) this.brush = p.brush else this.color = p.color
        this.alpha = p.alpha
        this.blendMode = p.blendMode
      }
      if (impl == Impl.Compose) composeDropShadow(shape, lambda) else dropShadow(shape, lambda)
    }

  /** One run of the animation tests (stepped and free-running): a catalog shape, the overload, a brush or a color. */
  class AnimRun(val name: String, val shape: Shape, val block: Boolean, val brush: Brush?)

  /**
   * The animation tests' runs, by name and in the caller's order: `morph-color-block`, `mixed-color-block`,
   * `rrect16-color-shadow`, `morph-brush-block`, `rrect16-brush-shadow`. New brushes per call.
   */
  fun animRuns(vararg names: String): List<AnimRun> {
    val byName = shapes.associateBy { it.name }
    val all =
      listOf(
        AnimRun("morph-color-block", byName.getValue("morph").shape, true, null),
        AnimRun("mixed-color-block", byName.getValue("mixed").shape, true, null),
        AnimRun("rrect16-color-shadow", byName.getValue("rrect16").shape, false, null),
        AnimRun("morph-brush-block", byName.getValue("morph").shape, true, Brush.linearGradient(listOf(Color.Red, Color.Blue))),
        AnimRun("rrect16-brush-shadow", byName.getValue("rrect16").shape, false, Brush.linearGradient(listOf(Color.Green, Color.Magenta))),
      )
    return names.map { n -> all.first { it.name == n } }
  }

  /**
   * The animation tests' shadow for [run] at [radiusDp]: spread 8 dp, offset (2, 3) dp, the run's brush or a
   * translucent color. The block overload calls [radiusDp] (and [onBlock] first) inside its block, so in the draw
   * phase; the `Shadow` overload calls it here, in composition. Composable so that the block lambda is remembered,
   * as a lambda written inline in the tests' content would be.
   */
  @Composable
  fun animShadow(impl: Impl, run: AnimRun, radiusDp: () -> Float, onBlock: () -> Unit = {}): Modifier =
    if (run.block) {
      val block: DropShadowScope.() -> Unit = {
        onBlock()
        radius = radiusDp().dp.toPx()
        spread = 8.dp.toPx()
        offset = Offset(2.dp.toPx(), 3.dp.toPx())
        if (run.brush != null) brush = run.brush else color = ANIM_COLOR
      }
      if (impl == Impl.Compose) Modifier.composeDropShadow(run.shape, block) else Modifier.dropShadow(run.shape, block)
    } else {
      val rv = radiusDp()
      val s =
        if (run.brush != null) Shadow(rv.dp, run.brush, 8.dp, DpOffset(2.dp, 3.dp))
        else Shadow(rv.dp, ANIM_COLOR, 8.dp, DpOffset(2.dp, 3.dp))
      if (impl == Impl.Compose) Modifier.composeDropShadow(run.shape, s) else Modifier.dropShadow(run.shape, s)
    }

  private val ANIM_COLOR = Color(0xC0302060)

  /**
   * The animation tests' cell side: 240 dp rounded to a multiple of 16 px, so that the stacked cells sit at the same
   * device position modulo GPU gradient dithering's 8 × 8 pattern.
   */
  @Composable
  fun animCell(): Dp = with(LocalDensity.current) { ((240.dp.toPx() / 16f).roundToInt() * 16).toDp() }

  /**
   * The animation tests' cells (equally sized, top-left aligned): one under another as a `Column` would place them,
   * or side by side where they do not fit the window's height. A landscape tablet's window can be shorter than three
   * [animCell]s (at density 2.25: 3 × 544 px against 1504 px), and a `Column` would give the last cell only the height
   * left (416 px there), so its capture would not match the others in size.
   */
  @Composable
  fun CellStack(content: @Composable () -> Unit) {
    Layout(content) { measurables, constraints ->
      val cells = measurables.map { it.measure(constraints.copy(minWidth = 0, minHeight = 0)) }
      val column = cells.sumOf { it.height } <= constraints.maxHeight || cells.sumOf { it.width } > constraints.maxWidth
      val w = if (column) cells.maxOfOrNull { it.width } ?: 0 else cells.sumOf { it.width }
      val h = if (column) cells.sumOf { it.height } else cells.maxOfOrNull { it.height } ?: 0
      layout(constraints.constrainWidth(w), constraints.constrainHeight(h)) {
        var at = 0
        for (p in cells) {
          if (column) p.place(0, at) else p.place(at, 0)
          at += if (column) p.height else p.width
        }
      }
    }
  }

  /** A cell with a two-tone backdrop (blend modes see different destinations) and a translucent content box. */
  @Composable
  fun Cell(modifier: Modifier, cell: Dp, contentSize: Dp, shadowModifier: Modifier) {
    Box(
      modifier.size(cell).drawBehind {
        drawRect(Color.White)
        drawRect(Color(0xFFFFD040), topLeft = Offset(size.width / 2f, 0f), size = Size(size.width / 2f, size.height))
        drawRect(Color(0xFF40A0FF), topLeft = Offset(0f, size.height * 0.7f), size = Size(size.width, size.height * 0.3f))
      }
    ) {
      Box(Modifier.align(Alignment.Center).size(contentSize).then(shadowModifier).background(CONTENT))
    }
  }

  fun Activity.showOverLockScreen() {
    if (Build.VERSION.SDK_INT >= 27) {
      setShowWhenLocked(true)
      setTurnScreenOn(true)
    }
    @Suppress("DEPRECATION")
    window.addFlags(
      WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
        WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
        WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
    )
  }

  /**
   * Pixels that differ, the largest channel difference, and, where the comparison computes it ([diffAlpha],
   * [diffPremul]), the squared error [sse] over [samples] samples (for the PSNR, [psnr]).
   */
  class Diff(val pixels: Int, val max: Int, val sse: Long = 0L, val samples: Long = 0L) {
    val identical
      get() = pixels == 0
  }

  fun pixelsOf(b: Bitmap): IntArray {
    val hardware = Build.VERSION.SDK_INT >= 26 && b.config == Bitmap.Config.HARDWARE
    val sw = if (hardware) b.copy(Bitmap.Config.ARGB_8888, false) else b
    val px = IntArray(sw.width * sw.height)
    sw.getPixels(px, 0, sw.width, 0, 0, sw.width, sw.height)
    if (sw !== b) sw.recycle()
    return px
  }

  /** Per-channel byte comparison of two equally sized ARGB images (no squared error). */
  fun diff(a: IntArray, b: IntArray): Diff {
    require(a.size == b.size) { "size ${a.size} vs ${b.size}" }
    var n = 0
    var m = 0
    for (i in a.indices) {
      val x = a[i]
      val y = b[i]
      if (x != y) {
        n++
        for (sh in intArrayOf(0, 8, 16, 24)) m = max(m, abs(((x ushr sh) and 0xFF) - ((y ushr sh) and 0xFF)))
      }
    }
    return Diff(n, m)
  }

  /**
   * Comparison of two equally sized ARGB images (as `getPixels` returns them, unpremultiplied) after premultiplying:
   * R, G and B each times alpha / 255 (rounded), and alpha itself; the squared error is over those four channels. A
   * colour under alpha 0 then counts as nothing, as it does once composited.
   */
  fun diffPremul(a: IntArray, b: IntArray): Diff {
    require(a.size == b.size) { "size ${a.size} vs ${b.size}" }
    var n = 0
    var m = 0
    var sse = 0L
    for (i in a.indices) {
      val x = a[i]
      val y = b[i]
      if (x == y) continue
      val ax = x ushr 24
      val ay = y ushr 24
      var differs = ax != ay
      var pixelMax = abs(ax - ay)
      var pixelSse = (ax - ay) * (ax - ay)
      for (sh in intArrayOf(0, 8, 16)) {
        val d = abs(premul((x ushr sh) and 0xFF, ax) - premul((y ushr sh) and 0xFF, ay))
        if (d != 0) differs = true
        pixelMax = max(pixelMax, d)
        pixelSse += d * d
      }
      if (differs) {
        n++
        m = max(m, pixelMax)
        sse += pixelSse
      }
    }
    return Diff(n, m, sse, 4L * a.size)
  }

  private fun premul(c: Int, alpha: Int) = (c * alpha + 127) / 255

  /** Comparison of two equally sized arrays of alpha values (0..255, one per pixel). */
  fun diffAlpha(a: IntArray, b: IntArray): Diff {
    require(a.size == b.size) { "size ${a.size} vs ${b.size}" }
    var n = 0
    var m = 0
    var sse = 0L
    for (i in a.indices) {
      val d = abs(a[i] - b[i])
      if (d != 0) {
        n++
        m = max(m, d)
        sse += d * d
      }
    }
    return Diff(n, m, sse, a.size.toLong())
  }

  fun savePng(dir: File, name: String, w: Int, h: Int, px: IntArray) {
    dir.mkdirs()
    val b = Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    try {
      File(dir, name).outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    } finally {
      b.recycle()
    }
  }
}

/**
 * Runs [read] on the main thread with the main thread's [ShadowStore] (the store the tests' nodes draw into) and
 * returns its result; an exception thrown by [read] is rethrown here. Everything read from the store or its entries
 * must be read inside [read].
 */
internal fun <T> mainStore(read: (ShadowStore) -> T): T {
  var result: Result<T>? = null
  InstrumentationRegistry.getInstrumentation().runOnMainSync { result = runCatching { read(ShadowStore.current()) } }
  return result!!.getOrThrow()
}

/** Runs [block] on the main thread and returns its result; an exception thrown by [block] is rethrown here. */
internal fun <T> onMain(block: () -> T): T {
  var result: Result<T>? = null
  InstrumentationRegistry.getInstrumentation().runOnMainSync { result = runCatching(block) }
  return result!!.getOrThrow()
}

/**
 * Empties the main thread's store of idle entries (and closes its builder), so that the next draws build from a cold
 * store: a test that counts builds must not take an entry another run or test left idle.
 */
internal fun clearIdleShadows() {
  mainStore { it.trimMemory() }
}
