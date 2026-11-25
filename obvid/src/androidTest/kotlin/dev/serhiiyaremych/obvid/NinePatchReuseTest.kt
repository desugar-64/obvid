// Copyright 2026, Serhii Yaremych (desugar-64) and the Obvid project contributors
// SPDX-License-Identifier: Apache-2.0

package dev.serhiiyaremych.obvid

import android.graphics.Bitmap
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import dev.serhiiyaremych.obvid.builder.ShadowBitmapBuilder
import dev.serhiiyaremych.obvid.builder.ShadowBitmaps
import dev.serhiiyaremych.obvid.fixtures.ComposeShadowReference
import dev.serhiiyaremych.obvid.fixtures.InnerShadowReference
import dev.serhiiyaremych.obvid.fixtures.alphaOf
import dev.serhiiyaremych.obvid.fixtures.outlineParams
import java.util.Locale
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The nine-patch route's private blurred small round rect ([ShadowBitmaps.keptNineMask]), drop and inner shadow. The
 * builders here are standalone: their [NineMaskCache] publishes nothing (the store's published masks:
 * [SharedNineMaskTest]).
 *
 * - [randomSequencesMatchCompose]: seeded random sequences of single-parameter changes (offset (inner only), layout
 *   size, radius, corner radius, spread) on round rects, one builder and one [ShadowBitmaps] per sequence, every other
 *   build at random with `preserveCurrent`. Every step's bitmap must equal a fresh build's (a new builder and bitmaps:
 *   nothing kept) everywhere, and Compose's ([ComposeShadowReference], [InnerShadowReference]) on the target Skia
 *   ([TargetSkia.isDevice]). The reuse counter ([ShadowBitmaps.nineReuseCount]) must step exactly where [KeepModel]
 *   says: a nine-patch build whose corner radii and blur radius equal those of the mask kept by an earlier nine-patch
 *   rebuild (a build that is not the first into the bitmaps) that missed. Since the small round rect depends only on
 *   those (`builderPlanRoundRect`'s small rect is `2·(ceil(r) + border) + 1` per axis), a change of the offset, the
 *   layout size or the spread alone keeps it, and a hit needs the entry's third build at least. A build off the
 *   nine-patch frees the kept buffer, so a radius or corner change never hits; in the inputs, every hit differs from
 *   the build that kept the mask in the offset, size or spread only. The kept buffer must exist exactly while a kept
 *   key does. Counts per change kind go to the summary.
 * - [staticEntryKeepsNothing]: a target built once never allocates the kept buffer, the second build allocates it and
 *   the third reuses it; through a [ShadowStore] without a looper (no idle entries), releasing the entry frees it.
 *
 * Output (subdir [InnerScreenCases.RESULTS_DIR2]): `inner2-reuse.csv` and `inner2-reuse-summary.txt`. Logcat tag
 * `FS-NineReuse`.
 */
@RunWith(AndroidJUnit4::class)
class NinePatchReuseTest {

  private val context = InstrumentationRegistry.getInstrumentation().targetContext
  private val density = Density(context)

  private enum class Change {
    Offset,
    Size,
    Radius,
    Corner,
    Spread,
  }

  /** One step's inputs: the corner radius, radius and spread in dp, the layout size and the offset in px. */
  private data class State(
    val cornerDp: Float,
    val widthPx: Int,
    val heightPx: Int,
    val radiusDp: Float,
    val spreadDp: Float,
    val ox: Float,
    val oy: Float,
  )

  private class Step(
    val inner: Boolean,
    val seq: Int,
    val index: Int,
    val change: Change?,
    val build: Int,
    val s: State,
    val route: String,
    val reused: Int,
    val expected: Boolean,
    val identicalFresh: Boolean,
    val compose: ScreenCases.Diff,
    val keptAllocated: Boolean,
    val keptExpected: Boolean,
  ) {
    fun csv(): String =
      listOf<Any>(
          Build.MODEL.replace(',', ' '),
          Build.VERSION.SDK_INT,
          if (inner) "inner" else "drop",
          seq,
          index,
          change?.name ?: "initial",
          build,
          fmt(s.cornerDp),
          s.widthPx,
          s.heightPx,
          fmt(s.radiusDp),
          fmt(s.spreadDp),
          s.ox,
          s.oy,
          route,
          reused,
          bit(expected),
          bit(identicalFresh),
          bit(compose.identical),
          compose.max,
          compose.pixels,
          bit(keptAllocated),
          bit(keptExpected),
        )
        .joinToString(",")
  }

  /**
   * What the builder keeps, from the inputs alone: the (corner x, corner y, radius px) of the kept mask, set by a
   * nine-patch build that is not the target's first and did not hit, cleared by a build off the nine-patch; a
   * nine-patch build hits when its triple equals it.
   */
  private class KeepModel {
    private var kept: Triple<Float, Float, Float>? = null

    val allocated: Boolean
      get() = kept != null

    /** Whether build number [build] (1-based) with [route] and [key] hits; updates the kept key. */
    fun next(build: Int, route: String, key: Triple<Float, Float, Float>): Boolean {
      if (route != ShadowBitmapBuilder.Route.NinePatch.name) {
        kept = null
        return false
      }
      if (kept == key) return true
      if (build >= 2) kept = key
      return false
    }
  }

  @Test
  @LargeTest
  fun randomSequencesMatchCompose() {
    val start = SystemClock.elapsedRealtime()
    val steps = ArrayList<Step>()
    for (inner in listOf(false, true)) for (seq in 0 until SEQUENCES) steps += runSequence(inner, seq)
    val seconds = (SystemClock.elapsedRealtime() - start) / 1000.0

    val summary = ArrayList<String>()
    for ((inner, group) in steps.groupBy { it.inner }) {
      val label = if (inner) "inner" else "drop"
      summary +=
        "$label: ${group.size} steps, identical to a fresh build ${group.count { it.identicalFresh }}, to Compose " +
          "${group.count { it.compose.identical }} (max diff ${group.maxOf { it.compose.max }}); reuse counter steps " +
          "${group.sumOf { it.reused }}, model ${group.count { it.expected }}, disagreeing ${group.count { (it.reused == 1) != it.expected }}; " +
          "routes ${group.groupingBy { it.route }.eachCount()}"
      for (change in Change.entries) {
        val byChange = group.filter { it.change == change }
        if (byChange.isEmpty()) continue
        val nine = byChange.filter { it.route == ShadowBitmapBuilder.Route.NinePatch.name }
        summary +=
          "  $label ${change.name}: ${byChange.size} steps, nine-patch ${nine.size}, reused ${byChange.sumOf { it.reused }}" +
            " (model ${byChange.count { it.expected }}); identical to Compose ${byChange.count { it.compose.identical }}"
      }
      summary += "  $label: reused before the third build ${group.count { it.reused == 1 && it.build < 3 }}; reused " +
        "after a radius or corner change " +
        "${group.count { it.reused == 1 && (it.change == Change.Radius || it.change == Change.Corner) }}"
    }
    summary += "API ${Build.VERSION.SDK_INT}, ${Build.MODEL}, density ${density.density}, " +
      String.format(Locale.US, "%.1f s", seconds)
    if (!TargetSkia.isDevice) {
      summary += "${TargetSkia.offTargetLabel} (alpha): " + DiffTally().also { t -> steps.forEach { t.add(it.compose) } }
    }
    summary.forEach { Log.i(TAG, it) }
    steps.filter { !it.identicalFresh || (it.reused == 1) != it.expected }.take(50).forEach { Log.i(TAG, it.csv()) }
    writeResults(context, "inner2-reuse.csv", listOf(HEADER) + steps.map { it.csv() }, InnerScreenCases.RESULTS_DIR2)
    writeResults(context, "inner2-reuse-summary.txt", summary, InnerScreenCases.RESULTS_DIR2)

    for (st in steps) {
      assertTrue("not identical to a fresh build: ${st.csv()}", st.identicalFresh)
      if (TargetSkia.isDevice) assertTrue("not identical to Compose: ${st.csv()}", st.compose.identical)
      assertEquals("reuse counter: ${st.csv()}", if (st.expected) 1 else 0, st.reused)
      assertEquals("kept buffer allocated: ${st.csv()}", st.keptExpected, st.keptAllocated)
      if (st.reused == 1) {
        assertTrue("reused before the third build: ${st.csv()}", st.build >= 3)
        assertTrue("reused off the nine-patch: ${st.csv()}", st.route == ShadowBitmapBuilder.Route.NinePatch.name)
      }
    }
    // In the inputs: a reuse differs from the build that kept the mask in the offset, the size or the spread only.
    for (group in steps.groupBy { it.inner to it.seq }.values) {
      var keeper: Step? = null
      for (st in group) {
        val k = keeper
        if (st.reused == 1 && k != null) {
          assertTrue(
            "reused with another corner or radius than the keeping build ${k.csv()}: ${st.csv()}",
            st.s.cornerDp == k.s.cornerDp && st.s.radiusDp == k.s.radiusDp,
          )
        }
        if (st.reused == 0 && st.build >= 2 && st.route == ShadowBitmapBuilder.Route.NinePatch.name) keeper = st
      }
    }
    // Both kinds of shadow reuse on offset (inner) and size changes.
    for (inner in listOf(false, true)) {
      val group = steps.filter { it.inner == inner }
      assertTrue("no size reuse (inner $inner)", group.any { it.change == Change.Size && it.reused == 1 })
      assertTrue("no nine-patch miss after the first build (inner $inner)", group.any { it.build >= 3 && it.route == "NinePatch" && it.reused == 0 })
      if (inner) assertTrue("no offset reuse", group.any { it.change == Change.Offset && it.reused == 1 })
    }
  }

  @Test
  fun staticEntryKeepsNothing() {
    val shape = RoundedCornerShape(16.dp)
    val radius = with(density) { 24.dp.toPx() }
    fun size(dp: Int) = with(density) { Size(dp.dp.roundToPx().toFloat(), dp.dp.roundToPx().toFloat()) }
    for (inner in listOf(false, true)) {
      ShadowBitmapBuilder().use { b ->
        val t = ShadowBitmaps()
        fun build(sizeDp: Int, ox: Float) {
          val s = size(sizeDp)
          val outline = shape.createOutline(s, LayoutDirection.Ltr, density)
          if (inner) b.buildInner(t, outline, s, radius, 0f, ox, 0f, preserveCurrent = true) else b.build(t, outline, s, radius, 0f)
          assertEquals("route (inner $inner)", ShadowBitmapBuilder.Route.NinePatch, b.route)
        }
        build(200, 3f)
        assertNull("kept buffer after one build (inner $inner)", keptBuffer(t))
        assertEquals(0, t.nineReuseCount)
        // The second build changes only the size (drop) or the offset (inner): a miss that keeps the mask.
        if (inner) build(200, 5.5f) else build(201, 0f)
        assertNotNull("kept buffer after the second build (inner $inner)", keptBuffer(t))
        assertEquals(0, t.nineReuseCount)
        if (inner) build(200, -2f) else build(202, 0f)
        assertEquals("third build reuses (inner $inner)", 1, t.nineReuseCount)
      }
    }

    // No looper: release drops the entry at once (no idle retention).
    val store = ShadowStore(looper = null)
    val first = store.obtain(null, density, shape, size(200), LayoutDirection.Ltr, radius, 0f, inner = true, offsetXPx = 3f)
    assertNull("store: kept buffer of a static entry", keptBuffer(first.bitmaps))
    // The same node draws again with another offset, then another size: the entry is re-keyed in place.
    val second = store.obtain(first, density, shape, size(200), LayoutDirection.Ltr, radius, 0f, inner = true, offsetXPx = 4f)
    assertTrue("store: re-keyed in place", second === first)
    assertNotNull("store: kept buffer after a rebuild", keptBuffer(second.bitmaps))
    val third = store.obtain(second, density, shape, size(210), LayoutDirection.Ltr, radius, 0f, inner = true, offsetXPx = 4f)
    assertTrue("store: re-keyed in place", third === first)
    assertEquals("store: reuse count", 1L, store.nineReuseCount)
    assertEquals("store: build count", 3L, store.buildCount)
    store.release(third)
    assertNull("store: kept buffer after release", keptBuffer(third.bitmaps))
    assertEquals("store: entries after release", 0, store.entriesForTest().size)
    assertEquals("store: nothing published without a looper", 0L, store.ninePublishCount)
  }

  // ---------------------------------------------------------------------------------------------------------------

  private fun runSequence(inner: Boolean, seq: Int): List<Step> {
    val random = Random(SEED + 31 * seq + if (inner) 1000 else 0)
    val minPx = with(density) { 60.dp.roundToPx() }
    val maxPx = with(density) { 360.dp.roundToPx() }
    fun offset(): Float =
      when (random.nextInt(3)) {
        0 -> random.nextInt(-40, 41).toFloat()
        1 -> random.nextInt(-80, 81) / 2f
        else -> (random.nextFloat() * 2f - 1f) * 40f
      }
    var s =
      State(
        CORNERS_DP.random(random),
        random.nextInt(minPx, maxPx + 1),
        random.nextInt(minPx, maxPx + 1),
        RADII_DP.random(random),
        SPREADS_DP.random(random),
        if (inner) offset() else 0f,
        if (inner) offset() else 0f,
      )
    val changes = if (inner) CHANGE_WEIGHTS else CHANGE_WEIGHTS.filter { it.first != Change.Offset }
    val totalWeight = changes.sumOf { it.second }
    val model = KeepModel()
    val out = ArrayList<Step>()
    ShadowBitmapBuilder().use { b ->
      val target = ShadowBitmaps()
      for (i in 0 until STEPS) {
        var change: Change? = null
        if (i > 0) {
          var pick = random.nextInt(totalWeight)
          for ((c, weight) in changes) {
            if (pick < weight) {
              change = c
              break
            }
            pick -= weight
          }
          s =
            when (checkNotNull(change)) {
              Change.Offset ->
                if (random.nextBoolean()) s.copy(ox = other(s.ox) { offset() }) else s.copy(oy = other(s.oy) { offset() })
              Change.Size ->
                when (random.nextInt(3)) {
                  0 -> s.copy(widthPx = other(s.widthPx) { random.nextInt(minPx, maxPx + 1) })
                  1 -> s.copy(heightPx = other(s.heightPx) { random.nextInt(minPx, maxPx + 1) })
                  // A small step, as an animation makes.
                  else -> s.copy(widthPx = s.widthPx + random.nextInt(1, 4), heightPx = s.heightPx + 1)
                }
              Change.Radius -> s.copy(radiusDp = other(s.radiusDp) { RADII_DP.random(random) })
              Change.Corner -> s.copy(cornerDp = other(s.cornerDp) { CORNERS_DP.random(random) })
              Change.Spread -> s.copy(spreadDp = other(s.spreadDp) { SPREADS_DP.random(random) })
            }
        }
        out += step(b, target, inner, seq, i, change, s, preserve = random.nextBoolean(), model)
      }
    }
    return out
  }

  /** A value from [next] different from [current]. */
  private inline fun <T> other(current: T, next: () -> T): T {
    while (true) {
      val v = next()
      if (v != current) return v
    }
  }

  private fun step(
    b: ShadowBitmapBuilder,
    target: ShadowBitmaps,
    inner: Boolean,
    seq: Int,
    index: Int,
    change: Change?,
    s: State,
    preserve: Boolean,
    model: KeepModel,
  ): Step {
    val size = Size(s.widthPx.toFloat(), s.heightPx.toFloat())
    val shape = RoundedCornerShape(s.cornerDp.dp)
    val outline = shape.createOutline(size, LayoutDirection.Ltr, density)
    val radius = with(density) { s.radiusDp.dp.toPx() }
    val spread = with(density) { s.spreadDp.dp.toPx() }
    val before = target.nineReuseCount
    build(b, target, inner, outline, size, radius, spread, s.ox, s.oy, preserve)
    val route = b.route.name
    val reused = target.nineReuseCount - before
    val obvid = alphaOf(target.bitmap!!)
    val fresh = ShadowBitmapBuilder().use { fb ->
      val ft = ShadowBitmaps()
      build(fb, ft, inner, outline, size, radius, spread, s.ox, s.oy, false)
      alphaOf(ft.bitmap!!)
    }
    val ref =
      if (inner) InnerShadowReference.referenceA(outline, size, radius, spread, s.ox, s.oy).asAndroidBitmap()
      else ComposeShadowReference.referenceA(outline, size, radius, spread).asAndroidBitmap()
    val refPx = alphaOf(ref)
    ref.recycle()
    val build = index + 1
    val corner = outlineParams(outline).cornerRadius
    val expected = model.next(build, route, Triple(corner.x, corner.y, radius))
    return Step(
      inner,
      seq,
      index,
      change,
      build,
      s,
      route,
      reused,
      expected,
      obvid.contentEquals(fresh),
      if (refPx.size == obvid.size) ScreenCases.diffAlpha(refPx, obvid) else ScreenCases.Diff(obvid.size, 255),
      keptBuffer(target) != null,
      model.allocated,
    )
  }

  private fun build(
    b: ShadowBitmapBuilder,
    t: ShadowBitmaps,
    inner: Boolean,
    outline: Outline,
    size: Size,
    radius: Float,
    spread: Float,
    ox: Float,
    oy: Float,
    preserve: Boolean,
  ) {
    if (inner) b.buildInner(t, outline, size, radius, spread, ox, oy, preserve) else b.build(t, outline, size, radius, spread, preserve)
  }

  /** The kept small-mask buffer of [t] (a private field: read for the test only). */
  private fun keptBuffer(t: ShadowBitmaps): Bitmap? = NINE_MASK_FIELD.get(t) as Bitmap?

  private companion object {
    const val TAG = "FS-NineReuse"
    const val SEED = 20261121
    const val SEQUENCES = 6
    const val STEPS = 80
    const val HEADER =
      "model,api,shadow,seq,step,change,build,cornerDp,widthPx,heightPx,radiusDp,spreadDp,offsetX,offsetY,route," +
        "reused,expected,identicalFresh,identicalCompose,maxDiff,diffPixels,keptAllocated,keptExpected"

    val CORNERS_DP = listOf(4f, 6.3f, 16f, 40f)
    val RADII_DP = listOf(1f, 4f, 8f, 12f, 24f)
    val SPREADS_DP = listOf(0f, 4f, 8f, -8f)

    /** Relative weights of the change kinds (the offset only for the inner shadow). */
    val CHANGE_WEIGHTS =
      listOf(Change.Offset to 30, Change.Size to 30, Change.Radius to 15, Change.Corner to 10, Change.Spread to 15)

    val NINE_MASK_FIELD = ShadowBitmaps::class.java.getDeclaredField("nineMask").apply { isAccessible = true }

    fun bit(b: Boolean) = if (b) 1 else 0

    fun fmt(f: Float) = if (f == f.toInt().toFloat()) f.toInt().toString() else f.toString()
  }
}
