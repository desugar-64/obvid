// Copyright 2026, Serhii Yaremych (desugar-64) and the Obvid project contributors
// SPDX-License-Identifier: Apache-2.0

package dev.serhiiyaremych.obvid

import android.graphics.Bitmap
import android.os.Build
import android.os.Looper
import android.util.Log
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.toRect
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.serhiiyaremych.obvid.fixtures.ComposeShadowReference
import dev.serhiiyaremych.obvid.fixtures.InnerShadowReference
import dev.serhiiyaremych.obvid.fixtures.alphaOf
import java.nio.ByteBuffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The store's published nine-patch masks ([NineMaskCache]) and the entries' private ones
 * ([ShadowBitmaps.keptNineMask]). An entry's first build places a published mask of its [NineMaskKey] or blurs, and
 * publishes only for a node's first appearance (`obtain` without a previous entry); a rebuild places its private mask,
 * else a published one, else blurs into its private buffer (never published). No entry holds a published mask: it is idle from its last use (expiry, the byte cap shared with idle
 * entries, trim).
 *
 * Calls the store directly, each test with its own store on the main thread (main looper, the store's clock driven by
 * the test, sweeps run by the test). Every entry's bitmap is compared with a fresh build (a new builder and bitmaps:
 * nothing kept) and, on the target Skia ([TargetSkia.isDevice]), with Compose's ([ComposeShadowReference],
 * [InnerShadowReference]); the fresh build's route is checked, so each case exercises what it means to. Each step
 * checks the store's invariants ([assertMasksConsistent]). Logcat tag `FS-SharedNine`.
 */
@RunWith(AndroidJUnit4::class)
class SharedNineMaskTest {

  private val density = Density(InstrumentationRegistry.getInstrumentation().targetContext)

  private class FakeClock : ShadowClock {
    @Volatile var now = 1_000L

    override fun uptimeMillis() = now
  }

  /** A round rect with the corner radii ([rx], [ry]) in px on all four corners. One instance is one key (identity). */
  private class CornerShape(val rx: Float, val ry: Float = rx) : Shape {
    var failing = false

    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
      if (failing) throw IllegalStateException("CornerShape.createOutline")
      return Outline.Rounded(RoundRect(size.toRect(), CornerRadius(rx, ry)))
    }

    override fun toString() = "CornerShape($rx, $ry)"
  }

  /** One shadow: [w] × [h] px, radius and spread in px, the inner shadow's offset in px. */
  private data class Geo(
    val shape: Shape,
    val w: Float,
    val h: Float,
    val radius: Float,
    val spread: Float = 0f,
    val inner: Boolean = false,
    val ox: Float = 0f,
    val oy: Float = 0f,
  ) {
    val size
      get() = Size(w, h)
  }

  private fun px(dp: Float) = with(density) { dp.dp.toPx() }

  private fun store(clock: FakeClock = FakeClock(), maxIdleBytes: Long = Long.MAX_VALUE / 4) =
    ShadowStore(Looper.getMainLooper(), TIMEOUT, maxIdleBytes, clock)

  private fun ShadowStore.obtainG(previous: ShadowEntry?, g: Geo): ShadowEntry =
    obtain(previous, density, g.shape, g.size, LayoutDirection.Ltr, g.radius, g.spread, g.inner, g.ox, g.oy)
      .also { assertMasksConsistent(this) }

  private fun ShadowStore.releaseChecked(entry: ShadowEntry) {
    release(entry)
    assertMasksConsistent(this)
  }

  private fun ShadowStore.sweepChecked() {
    sweepForTest()
    assertMasksConsistent(this)
  }

  private fun ShadowStore.trimChecked() {
    trimMemory()
    assertMasksConsistent(this)
    assertTrue("trim: no published masks", nineMasksForTest().isEmpty())
    assertEquals("trim: no published mask bytes", 0L, nineMaskBytesForTest())
  }

  /** [entry]'s bitmap equals a fresh build of [g], which takes [route], and Compose's on the target. */
  private fun assertExact(label: String, entry: ShadowEntry, g: Geo, route: ShadowBitmapBuilder.Route = ShadowBitmapBuilder.Route.NinePatch) {
    val obvid = alphaOf(checkNotNull(entry.bitmaps.bitmap))
    val outline = g.shape.createOutline(g.size, LayoutDirection.Ltr, density)
    val (freshRoute, fresh) =
      ShadowBitmapBuilder().use { b ->
        val t = ShadowBitmaps()
        if (g.inner) b.buildInner(t, outline, g.size, g.radius, g.spread, g.ox, g.oy) else b.build(t, outline, g.size, g.radius, g.spread)
        b.route to alphaOf(checkNotNull(t.bitmap))
      }
    assertEquals("$label: fresh route ($g)", route, freshRoute)
    assertTrue("$label: not identical to a fresh build ($g)", obvid.contentEquals(fresh))
    val ref =
      if (g.inner) InnerShadowReference.referenceA(outline, g.size, g.radius, g.spread, g.ox, g.oy).asAndroidBitmap()
      else ComposeShadowReference.referenceA(outline, g.size, g.radius, g.spread).asAndroidBitmap()
    val refPx = alphaOf(ref)
    ref.recycle()
    val d = if (refPx.size == obvid.size) ScreenCases.diffAlpha(refPx, obvid) else ScreenCases.Diff(obvid.size, 255, 0L, 0L)
    Log.i(TAG, "$label: identical to Compose ${d.identical} (max ${d.max}, pixels ${d.pixels}), API ${Build.VERSION.SDK_INT}")
    if (TargetSkia.isDevice) assertTrue("$label: not identical to Compose ($g): max ${d.max}", d.identical)
  }

  private fun bytesOf(b: Bitmap): ByteArray = ByteBuffer.allocate(b.byteCount).also { b.copyPixelsToBuffer(it) }.array()

  /**
   * First builds of entries of one corner radius and radius with different sizes (fractional too), spreads, offsets,
   * drop and inner: one blur, one published mask, every other build a published hit; no entry keeps a private mask.
   * Then a static screen: the mask expires with the timeout after its last use while the entries stay live.
   */
  @Test
  fun manyEntriesShareOneBlur() = onMain {
    val clock = FakeClock()
    val store = store(clock)
    val shape = RoundedCornerShape(16.dp)
    val r = px(24f)
    val base = px(200f)
    val geos =
      listOf(
        Geo(shape, base, base, r),
        Geo(shape, base + 10.5f, base + 3f, r),
        Geo(shape, base + 33.25f, base + 0.75f, r),
        Geo(shape, base + 100f, base + 57.125f, r),
        Geo(shape, base + 1f, base + 1f, r, spread = px(8f)),
        Geo(shape, base + 2f, base + 2f, r, spread = -px(4f)),
        Geo(shape, base, base, r, inner = true, ox = 3f, oy = -2f),
        Geo(shape, base + 7.5f, base + 11.25f, r, inner = true, ox = 3.5f, oy = -2.25f),
        Geo(shape, base + 40f, base + 20f, r, spread = px(4f), inner = true, ox = -0.375f, oy = 6.5f),
      )
    val entries = geos.map { store.obtainG(null, it) }
    assertEquals("distinct entries", geos.size, entries.toSet().size)
    assertEquals("builds", geos.size.toLong(), store.buildCount)
    assertEquals("one blur", 1L, store.nineBlurCount)
    assertEquals("one publish", 1L, store.ninePublishCount)
    assertEquals("one mask allocation", 1L, store.nineMaskAllocationCount)
    assertEquals("every other build a published hit", geos.size - 1L, store.nineSharedHitCount)
    assertEquals("reuses", geos.size - 1L, store.nineReuseCount)
    val mask = store.nineMasksForTest().single()
    for ((i, e) in entries.withIndex()) {
      assertNull("entry $i keeps no private mask", e.bitmaps.nineMask)
      assertExact("share $i", e, geos[i])
    }
    clock.now += TIMEOUT - 1
    store.sweepChecked()
    assertEquals("before the expiry", listOf(mask), store.nineMasksForTest())
    clock.now += 1
    store.sweepChecked()
    assertTrue("static screen: no masks after the expiry", store.nineMasksForTest().isEmpty())
    assertEquals("static screen: no mask bytes", 0L, store.nineMaskBytesForTest())
    assertTrue("expired mask recycled", mask.bitmap.isRecycled)
    for ((i, e) in entries.withIndex()) {
      assertTrue("entry $i stays built", e.built)
      assertExact("after the expiry $i", e, geos[i])
    }
    for (e in entries) store.releaseChecked(e)
    store.trimChecked()
  }

  /**
   * A change of the corner radius (by one ulp up or down, fractional) or of the radius (by one ulp, or a whole dp) is a
   * new mask; a change of size, spread, offset or drop / inner, or another shape instance of the same corners, is not.
   * Elliptic corners are not a simple round rect for Compose (`RoundRect.isSimple` needs x == y): the path route, so
   * never a nine-patch mask.
   */
  @Test
  fun keysThatDifferDoNotShare() = onMain {
    val store = store()
    val r = px(24f)
    val c = px(16f)
    val frac = px(6.3f)
    val side = px(200f)
    val base = Geo(CornerShape(c), side, side, r)
    val mask = ShadowBitmapBuilder.Route.Mask
    // (label, geometry, the label of the earlier case whose published mask it places, or null for a new one; an
    // elliptic case takes the path route and publishes nothing).
    val variants =
      listOf(
        Triple("size", base.copy(w = side + 13.5f, h = side + 0.25f), "base"),
        Triple("spread", base.copy(spread = px(8f)), "base"),
        Triple("negative spread", base.copy(spread = -px(4f)), "base"),
        Triple("inner", base.copy(inner = true, ox = 2f, oy = 1f), "base"),
        Triple("inner fractional offset", base.copy(inner = true, ox = 2.625f, oy = -1.125f), "base"),
        Triple("inner spread", base.copy(inner = true, spread = px(4f), ox = -3f), "base"),
        Triple("same corners, another shape instance", base.copy(shape = CornerShape(c), w = side + 1f), "base"),
        Triple("corner + 1 ulp", base.copy(shape = CornerShape(Math.nextUp(c))), null),
        Triple("corner - 1 ulp", base.copy(shape = CornerShape(Math.nextDown(c))), null),
        Triple("fractional corner", base.copy(shape = CornerShape(frac)), null),
        Triple("fractional corner + 1 ulp", base.copy(shape = CornerShape(Math.nextUp(frac))), null),
        Triple("radius + 1 ulp", base.copy(radius = Math.nextUp(r)), null),
        Triple("radius 23 dp", base.copy(radius = px(23f)), null),
        Triple("radius 23 dp, inner, larger", base.copy(radius = px(23f), inner = true, ox = 1f, w = side + 5f), "radius 23 dp"),
        Triple("elliptic corners", base.copy(shape = CornerShape(c, px(10f))), ELLIPTIC),
        Triple("elliptic corners, axes swapped", base.copy(shape = CornerShape(px(10f), c)), ELLIPTIC),
      )
    val first = store.obtainG(null, base)
    assertExact("base", first, base)
    assertEquals("base blurs", 1L, store.nineBlurCount)
    val held = arrayListOf(first)
    val maskOf = linkedMapOf("base" to store.nineMasksForTest().single())
    for ((label, g, sharesWith) in variants) {
      val blurs = store.nineBlurCount
      val hits = store.nineSharedHitCount
      val before = store.nineMasksForTest().toSet()
      val e = store.obtainG(null, g)
      held += e
      val after = store.nineMasksForTest()
      when (sharesWith) {
        ELLIPTIC -> {
          assertExact(label, e, g, mask)
          assertEquals("$label: no blur", blurs, store.nineBlurCount)
          assertEquals("$label: nothing published", before, after.toSet())
        }
        null -> {
          assertExact(label, e, g)
          assertEquals("$label: blurs", blurs + 1, store.nineBlurCount)
          val added = after.filter { it !in before }
          assertEquals("$label: one new mask", 1, added.size)
          maskOf[label] = added.single()
        }
        else -> {
          assertExact(label, e, g)
          assertEquals("$label: no blur", blurs, store.nineBlurCount)
          assertEquals("$label: a published hit", hits + 1, store.nineSharedHitCount)
          assertEquals("$label: nothing published", before, after.toSet())
          // The hit is the most recently used.
          assertSame("$label: places the mask of $sharesWith", maskOf.getValue(sharesWith), after.last())
        }
      }
      assertNull("$label: no private mask on a first build", e.bitmaps.nineMask)
    }
    assertEquals("blurs: the base and one per new key", 1L + variants.count { it.third == null }, store.nineBlurCount)
    for (e in held) store.releaseChecked(e)
    store.trimChecked()
  }

  /**
   * Entries of one style animate their radius in lockstep (different sizes, spread, drop and inner): after the first
   * rebuild each has its private buffer, and later frames allocate no mask, publish nothing and leave the published
   * bytes as they were; then a lockstep size animation places the private masks with no blur.
   */
  @Test
  fun lockstepAnimationAllocatesOnce() = onMain {
    val store = store()
    val shape = RoundedCornerShape(16.dp)
    val side = px(200f)
    fun geos(radius: Float, grow: Float = 0f) =
      listOf(
        Geo(shape, side + grow, side, radius),
        Geo(shape, side + 30.5f + grow, side + 2f, radius, spread = px(4f)),
        Geo(shape, side + 10f + grow, side + 10f, radius, inner = true, ox = 2.5f, oy = -1f),
      )
    var entries: List<ShadowEntry?> = listOf(null, null, null)
    fun frame(gs: List<Geo>, label: String) {
      entries = gs.mapIndexed { i, g -> store.obtainG(entries[i], g) }
      for ((i, e) in entries.withIndex()) assertExact("$label #$i", checkNotNull(e), gs[i])
    }
    val r0 = px(24f)
    frame(geos(r0), "frame 0")
    assertEquals("frame 0: one publish", 1L, store.ninePublishCount)
    val published = store.nineMasksForTest().single()
    val publishedBytes = store.nineMaskBytesForTest()
    frame(geos(r0 - 0.5f), "frame 1")
    val allocations = store.nineMaskAllocationCount
    assertEquals("frame 1: the published mask and one private buffer per entry", 1L + 3, allocations)
    for (e in entries) assertNotNull("frame 1: private buffer", e!!.bitmaps.nineMask)
    val blurs = store.nineBlurCount
    for (f in 2..FRAMES) {
      frame(geos(r0 - 0.5f * f), "frame $f")
      assertEquals("frame $f: no mask allocation", allocations, store.nineMaskAllocationCount)
      assertEquals("frame $f: nothing published", 1L, store.ninePublishCount)
      assertEquals("frame $f: the published mask only", listOf(published), store.nineMasksForTest())
      assertEquals("frame $f: published bytes", publishedBytes, store.nineMaskBytesForTest())
    }
    assertEquals("a blur per entry per frame", blurs + 3L * (FRAMES - 1), store.nineBlurCount)
    val rLast = r0 - 0.5f * FRAMES
    val blursBefore = store.nineBlurCount
    val reusesBefore = store.nineReuseCount
    for (f in 1..6) frame(geos(rLast, grow = f * 1.25f), "size frame $f")
    assertEquals("size frames: no blur", blursBefore, store.nineBlurCount)
    assertEquals("size frames: private reuses", reusesBefore + 3L * 6, store.nineReuseCount)
    assertEquals("size frames: no mask allocation", allocations, store.nineMaskAllocationCount)
    for (e in entries) store.releaseChecked(e!!)
    store.trimChecked()
  }

  /**
   * Three nodes on one entry animate their radius together: each frame the first node forks a new entry (the shared
   * one cannot be re-keyed) and the others join it. A fork is not a node's first appearance, so it publishes nothing
   * and blurs through the builder's scratch: no mask allocation, no published bytes after frame 0.
   */
  @Test
  fun sharedEntryLockstepPublishesOnce() = onMain {
    val store = store()
    val shape = RoundedCornerShape(16.dp)
    val side = px(200f)
    val r0 = px(24f)
    fun g(r: Float) = Geo(shape, side, side, r)
    val nodes = arrayOfNulls<ShadowEntry>(3)
    for (i in nodes.indices) nodes[i] = store.obtainG(null, g(r0))
    assertEquals("one entry", 1, nodes.toSet().size)
    assertEquals("frame 0: one publish", 1L, store.ninePublishCount)
    val publishedBytes = store.nineMaskBytesForTest()
    val allocations = store.nineMaskAllocationCount
    for (f in 1..FRAMES) {
      val gf = g(r0 - 0.5f * f)
      for (i in nodes.indices) nodes[i] = store.obtainG(nodes[i], gf)
      assertEquals("frame $f: one entry", 1, nodes.toSet().size)
      val e = checkNotNull(nodes[0])
      assertEquals("frame $f: shared by every node", nodes.size, e.refCount)
      assertExact("frame $f", e, gf)
      assertNull("frame $f: no private buffer on a fork", e.bitmaps.nineMask)
      assertEquals("frame $f: nothing published", 1L, store.ninePublishCount)
      assertEquals("frame $f: published bytes", publishedBytes, store.nineMaskBytesForTest())
      assertEquals("frame $f: no mask allocation", allocations, store.nineMaskAllocationCount)
    }
    for (e in nodes) store.releaseChecked(checkNotNull(e))
    store.trimChecked()
  }

  /**
   * Entries animate their radius upwards in lockstep: their private buffers are allocated only when they must grow
   * (each growth strictly larger on an axis), and every frame is exact (the key is dropped before a buffer is grown and
   * set again only once the new bytes are in it).
   */
  @Test
  fun growingRadiusAllocatesOnlyToGrow() = onMain {
    val store = store()
    val shape = RoundedCornerShape(16.dp)
    val side = px(200f)
    fun geos(radius: Float) =
      listOf(
        Geo(shape, side, side, radius),
        Geo(shape, side + 30.5f, side + 2f, radius, spread = px(4f)),
        Geo(shape, side + 10f, side + 10f, radius, inner = true, ox = 2.5f, oy = -1f),
      )
    val r0 = px(24f)
    var entries = geos(r0).map { store.obtainG(null, it) }
    var growths = 0
    for (f in 1..GROW_FRAMES) {
      val gs = geos(r0 + 1.5f * f)
      val before = entries.map { it.bitmaps.nineMask }
      val allocations = store.nineMaskAllocationCount
      entries = gs.mapIndexed { i, g -> store.obtainG(entries[i], g) }
      var changed = 0
      for ((i, e) in entries.withIndex()) {
        assertExact("frame $f #$i", e, gs[i])
        val now = checkNotNull(e.bitmaps.nineMask) { "frame $f #$i: private buffer" }
        val old = before[i]
        if (now !== old) {
          changed++
          if (old != null) {
            growths++
            assertTrue("frame $f #$i: grown", now.width > old.width || now.height > old.height)
            assertTrue("frame $f #$i: the old buffer recycled", old.isRecycled)
          }
        }
      }
      assertEquals("frame $f: an allocation per new or grown buffer", allocations + changed, store.nineMaskAllocationCount)
      assertEquals("frame $f: nothing published", 1L, store.ninePublishCount)
    }
    assertTrue("the grow path ran", growths > 0)
    assertTrue("allocations only at growth steps: $growths of ${3 * (GROW_FRAMES - 1)}", growths < 3 * (GROW_FRAMES - 1))
    for (e in entries) store.releaseChecked(e)
    store.trimChecked()
  }

  /**
   * A published mask is never rewritten: A and B place M (key K1), A re-keys to K2 (a private blur), B's size-only
   * rebuild and a new entry still place M identically, M's bytes unchanged; A returning to K1 places M; after M
   * expires, a new entry of K1 blurs and publishes again.
   */
  @Test
  fun publishedMaskNeverRewritten() = onMain {
    val clock = FakeClock()
    val store = store(clock)
    val shape = RoundedCornerShape(16.dp)
    val side = px(200f)
    val r1 = px(24f)
    val r2 = px(23f)
    val gA = Geo(shape, side, side, r1)
    val gB = Geo(shape, side + 20f, side + 3f, r1)
    var a = store.obtainG(null, gA)
    var b = store.obtainG(null, gB)
    val m = store.nineMasksForTest().single()
    val mBytes = bytesOf(m.bitmap)
    a = store.obtainG(a, gA.copy(radius = r2))
    assertExact("A at K2", a, gA.copy(radius = r2))
    assertNotNull("A's private buffer", a.bitmaps.nineMask)
    assertEquals("K2 not published", listOf(m), store.nineMasksForTest())
    assertArrayEquals("M unchanged after A's re-key", mBytes, bytesOf(m.bitmap))
    val gB2 = gB.copy(w = side + 27.5f)
    val hits = store.nineSharedHitCount
    b = store.obtainG(b, gB2)
    assertExact("B size-only rebuild", b, gB2)
    assertEquals("B places M", hits + 1, store.nineSharedHitCount)
    assertNull("B keeps no private buffer", b.bitmaps.nineMask)
    val gC = Geo(shape, side + 5f, side + 50f, r1, inner = true, ox = 1.5f)
    val c = store.obtainG(null, gC)
    assertExact("C asks K1", c, gC)
    val blurs = store.nineBlurCount
    a = store.obtainG(a, gA.copy(w = side + 1f))
    assertExact("A back at K1", a, gA.copy(w = side + 1f))
    assertEquals("A places M", blurs, store.nineBlurCount)
    assertArrayEquals("M unchanged", mBytes, bytesOf(m.bitmap))
    clock.now += TIMEOUT
    store.sweepChecked()
    assertTrue("M expired", m.bitmap.isRecycled)
    val gD = Geo(shape, side + 9f, side, r1)
    val d = store.obtainG(null, gD)
    assertExact("D asks K1 after the expiry", d, gD)
    assertEquals("D blurs", blurs + 1, store.nineBlurCount)
    assertEquals("D publishes", 1, store.nineMasksForTest().size)
    for (e in listOf(a, b, c, d)) store.releaseChecked(e)
    store.trimChecked()
  }

  /**
   * A use moves a published mask's expiry; the byte cap is one budget for idle entries and published masks, the
   * earliest release or use first, ordered at equal clock times too; a mask larger than the cap is not published, and
   * a store without a looper publishes nothing.
   */
  @Test
  fun maskLifetime() = onMain {
    val clock = FakeClock()
    val store = store(clock)
    val shape = RoundedCornerShape(16.dp)
    val side = px(200f)
    val r = px(24f)
    val g1 = Geo(shape, side, side, r)
    val g2 = g1.copy(w = side + 10f)
    val g3 = g1.copy(w = side + 20f)
    val e1 = store.obtainG(null, g1)
    val m = store.nineMasksForTest().single()
    clock.now += TIMEOUT - 1
    val e2 = store.obtainG(null, g2)
    assertEquals("touched by the hit", clock.now, m.lastUse)
    clock.now += TIMEOUT - 1
    store.sweepChecked()
    assertEquals("alive until the timeout after its last use", listOf(m), store.nineMasksForTest())
    clock.now += 1
    store.sweepChecked()
    assertTrue("expired", m.bitmap.isRecycled && store.nineMasksForTest().isEmpty())
    assertTrue("live entries stay", e1.built && e2.built && !e1.bitmaps.bitmap!!.isRecycled)
    val e3 = store.obtainG(null, g3)
    assertEquals("published again", 1, store.nineMasksForTest().size)
    for (e in listOf(e1, e2, e3)) store.releaseChecked(e)
    store.trimChecked()

    // The byte cap. Sizes from a probe store.
    val rectGeo = Geo(RectangleShape, 40f, 30f, 6f)
    val (maskBytes, rectBytes) =
      store().let { probe ->
        val n = probe.obtainG(null, g1)
        val rect = probe.obtainG(null, rectGeo)
        (probe.nineMaskBytesForTest() to rect.bitmaps.byteCount).also {
          probe.releaseChecked(n)
          probe.releaseChecked(rect)
          probe.trimChecked()
        }
      }
    // At one clock time: an entry released, then a mask published: the entry is the older, evicted first.
    val s1 = store(FakeClock(), maxIdleBytes = maskBytes + rectBytes - 1)
    val rect1 = s1.obtainG(null, rectGeo)
    s1.releaseChecked(rect1)
    val n1 = s1.obtainG(null, g1)
    assertTrue("entry released first: evicted", s1.idleEntriesForTest().isEmpty())
    assertEquals("the mask stays", 1, s1.nineMasksForTest().size)
    s1.releaseChecked(n1)
    s1.trimChecked()
    // At one clock time: a mask published, then an entry released: the mask is the older, evicted first.
    val s2 = store(FakeClock(), maxIdleBytes = maskBytes + rectBytes - 1)
    val n2 = s2.obtainG(null, g1)
    val m2 = s2.nineMasksForTest().single()
    val rect2 = s2.obtainG(null, rectGeo)
    s2.releaseChecked(rect2)
    assertTrue("mask published first: evicted", m2.bitmap.isRecycled && s2.nineMasksForTest().isEmpty())
    assertEquals("the entry stays", listOf(rect2), s2.idleEntriesForTest())
    s2.releaseChecked(n2)
    s2.trimChecked()

    // A mask larger than the whole cap is not published; the build is still exact.
    val small = store(FakeClock(), maxIdleBytes = maskBytes - 1)
    val sm = small.obtainG(null, g1)
    assertExact("oversized", sm, g1)
    assertEquals("oversized: blurred", 1L, small.nineBlurCount)
    assertEquals("oversized: not published", 0L, small.ninePublishCount)
    assertEquals("oversized: nothing allocated", 0L, small.nineMaskAllocationCount)
    small.releaseChecked(sm)
    small.trimChecked()

    // No looper: nothing published.
    val noLooper = ShadowStore(looper = null)
    val a = noLooper.obtainG(null, g1)
    val b = noLooper.obtainG(null, g2)
    assertExact("no looper", b, g2)
    assertEquals("no looper: two blurs", 2L, noLooper.nineBlurCount)
    assertEquals("no looper: nothing published", 0L, noLooper.ninePublishCount)
    noLooper.releaseChecked(a)
    noLooper.releaseChecked(b)
  }

  /**
   * An entry's private buffer is freed by a build off the nine-patch route and when the entry goes idle; a published
   * mask is untouched by either.
   */
  @Test
  fun privateBufferFreedOffRouteAndWhenIdle() = onMain {
    for (inner in listOf(false, true)) {
      val store = store()
      val shape = RoundedCornerShape(16.dp)
      val side = px(200f)
      val g = Geo(shape, side, side, px(24f), inner = inner, ox = if (inner) 2f else 0f)
      var e = store.obtainG(null, g)
      val published = store.nineMasksForTest().single()
      e = store.obtainG(e, g.copy(radius = px(23f)))
      val p1 = checkNotNull(e.bitmaps.nineMask) { "private buffer (inner $inner)" }
      // Radius 0: Compose sets no mask filter, the Skia route.
      e = store.obtainG(e, g.copy(radius = 0f))
      assertExact("off route (inner $inner)", e, g.copy(radius = 0f), ShadowBitmapBuilder.Route.Skia)
      assertNull("off route: no private buffer (inner $inner)", e.bitmaps.nineMask)
      assertTrue("off route: recycled (inner $inner)", p1.isRecycled)
      e = store.obtainG(e, g.copy(radius = px(22f)))
      val p2 = checkNotNull(e.bitmaps.nineMask) { "private buffer again (inner $inner)" }
      assertExact("back on route (inner $inner)", e, g.copy(radius = px(22f)))
      store.releaseChecked(e)
      assertNull("idle: no private buffer (inner $inner)", e.bitmaps.nineMask)
      assertTrue("idle: recycled (inner $inner)", p2.isRecycled)
      assertFalse("published mask untouched (inner $inner)", published.bitmap.isRecycled)
      store.trimChecked()
    }
  }

  /**
   * [ShadowStore.obtain]'s rollback when the build throws, with private buffers: a re-keyed unbuilt entry keeps its
   * buffer; a holder whose own entry went idle (freeing its buffer) while it joined an unbuilt entry gets it back
   * live without one; an unbuilt entry released by its last holder is dropped with its buffer.
   */
  @Test
  fun failedBuildRollback() = onMain {
    val store = store()
    val shape = CornerShape(px(16f))
    val side = px(200f)
    val r1 = px(24f)
    val r2 = px(23f)
    fun g(dw: Float, r: Float = r2) = Geo(shape, side + dw, side, r)
    var a = store.obtainG(null, g(0f, r1))
    a = store.obtainG(a, g(0f))
    val pa = checkNotNull(a.bitmaps.nineMask)
    shape.failing = true
    assertThrows(IllegalStateException::class.java) { store.obtainG(a, g(2f)) }
    assertMasksConsistent(store)
    assertFalse("A unbuilt", a.built)
    assertSame("A keeps its private buffer", pa, a.bitmaps.nineMask)
    shape.failing = false
    val reuses = store.nineReuseCount
    assertSame("A retried", a, store.obtainG(a, g(2f)))
    assertExact("A retried", a, g(2f))
    assertEquals("A places its private buffer", reuses + 1, store.nineReuseCount)
    // C re-keys in place and fails (unbuilt under g(5)); D, whose own entry goes idle (freeing its buffer) while it
    // joins C, fails too and gets its entry back live, without a buffer.
    var c = store.obtainG(null, g(3f, r1))
    c = store.obtainG(c, g(3f))
    var d = store.obtainG(null, g(4f, r1))
    d = store.obtainG(d, g(4f))
    val pd = checkNotNull(d.bitmaps.nineMask)
    shape.failing = true
    assertThrows(IllegalStateException::class.java) { store.obtainG(c, g(5f)) }
    assertMasksConsistent(store)
    val entryD = d
    assertThrows(IllegalStateException::class.java) { store.obtainG(entryD, g(5f)) }
    assertMasksConsistent(store)
    assertTrue("D's entry live", entryD.refCount == 1 && !entryD.idle && entryD.built)
    assertNull("D's entry freed its buffer when it went idle", entryD.bitmaps.nineMask)
    assertTrue("D's buffer recycled", pd.isRecycled)
    shape.failing = false
    val joined = store.obtainG(entryD, g(5f))
    assertSame("D joins C", c, joined)
    assertExact("C built", c, g(5f))
    // An unbuilt entry released by its last holder is dropped with its buffer.
    shape.failing = true
    assertThrows(IllegalStateException::class.java) { store.obtainG(a, g(6f)) }
    assertMasksConsistent(store)
    shape.failing = false
    store.releaseChecked(a)
    assertNull("dropped A freed its buffer", a.bitmaps.nineMask)
    assertTrue("A's buffer recycled", pa.isRecycled)
    store.releaseChecked(joined)
    store.releaseChecked(joined)
    store.trimChecked()
    assertEquals("trim: no entries", 0, store.mapSizeForTest())
  }

  private companion object {
    const val TAG = "FS-SharedNine"
    const val ELLIPTIC = "elliptic"
    const val FRAMES = 12
    const val GROW_FRAMES = 16

    /** The store's timeout; no posted sweep reaches it during a test (the store's clock is driven by the test). */
    const val TIMEOUT = 600_000L
  }
}

/**
 * The store's nine-patch masks agree with its entries: an idle entry has no private buffer, a live entry's is not
 * recycled and is never a published mask; the published masks are each in the map once, not recycled, in last-use
 * order (clock time and [IdleSequence] order), and their bytes are the store's published mask bytes.
 */
internal fun assertMasksConsistent(store: ShadowStore) {
  for (e in store.idleEntriesForTest()) assertNull("idle entry ${e.key} has a private buffer", e.bitmaps.nineMask)
  val privates = store.entriesForTest().mapNotNull { it.bitmaps.nineMask }
  for (p in privates) assertFalse("a live private buffer recycled", p.isRecycled)
  val published = store.nineMasksForTest()
  assertEquals("published masks: list == map", published.size, store.nineMaskMapSizeForTest())
  assertEquals("published masks listed once", published.size, published.toSet().size)
  for (m in published) {
    assertFalse("published mask ${m.key} recycled", m.bitmap.isRecycled)
    assertTrue("published mask ${m.key} is a private buffer", privates.none { it === m.bitmap })
  }
  for (i in 1 until published.size) {
    assertTrue("last-use order (time)", published[i - 1].lastUse <= published[i].lastUse)
    assertTrue("last-use order (sequence)", published[i - 1].order < published[i].order)
  }
  assertEquals("published mask bytes", published.sumOf { it.bytes }, store.nineMaskBytesForTest())
}
