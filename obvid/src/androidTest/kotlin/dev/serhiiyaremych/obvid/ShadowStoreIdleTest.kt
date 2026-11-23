// Copyright 2026, Serhii Yaremych (desugar-64) and the Obvid project contributors
// SPDX-License-Identifier: Apache-2.0

package dev.serhiiyaremych.obvid

import android.content.ComponentCallbacks
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.toRect
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Idle entries of [ShadowStore]: an entry released by its last holder stays findable by its key until it is taken
 * again, evicted by the byte cap (least recently released first), expired, or dropped on memory pressure; the builder
 * is closed once unused for the idle timeout. Calls the store directly, as `ShadowDrawState` does (each holder keeps
 * the entry it passed in). Each test makes its own store on the main thread, with the main looper; the expiry tests
 * drive the store's clock ([FakeClock]) and run the sweep themselves ([ShadowStore.sweepForTest]), except
 * [expiryRunsOnTheStoresLooper], which waits for the posted sweep in real time. Geometries: rectangles of 30 px
 * height, radius 6 px, spread 0, differing by width (or radius); a drop and an inner shadow where both apply.
 */
@RunWith(AndroidJUnit4::class)
class ShadowStoreIdleTest {

  private val instrumentation = InstrumentationRegistry.getInstrumentation()
  private val density = Density(instrumentation.targetContext)

  /** The store's clock, set by the test. */
  private class FakeClock : ShadowClock {
    @Volatile var now = 1_000L

    override fun uptimeMillis() = now
  }

  /** One instance is one key (shape equality is identity). Throws a new exception per call while [failing]. */
  private class FailingShape : Shape {
    var failing = false

    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
      if (failing) throw IllegalStateException("FailingShape.createOutline")
      return Outline.Rectangle(size.toRect())
    }
  }

  /** A store on the main looper whose posted sweeps never come due during a test (the tests sweep themselves). */
  private fun mainLooperStore(
    clock: FakeClock = FakeClock(),
    maxIdleBytes: Long = Long.MAX_VALUE / 4,
    timeoutMs: Long = LONG_TIMEOUT,
  ) = ShadowStore(Looper.getMainLooper(), timeoutMs, maxIdleBytes, clock)

  private fun ShadowStore.obtainW(
    previous: ShadowEntry?,
    width: Float,
    radius: Float = RADIUS,
    shape: Shape = RectangleShape,
    inner: Boolean = false,
  ): ShadowEntry =
    (if (inner) obtain(previous, density, shape, Size(width, HEIGHT), LayoutDirection.Ltr, radius, 0f, true, 3f, -2f)
    else obtain(previous, density, shape, Size(width, HEIGHT), LayoutDirection.Ltr, radius, 0f))
      .also { assertConsistent(this) }

  private fun ShadowStore.releaseChecked(entry: ShadowEntry) {
    release(entry)
    assertConsistent(this)
  }

  private fun ShadowStore.trimChecked() {
    trimMemory()
    assertConsistent(this)
  }

  private fun ShadowStore.sweepChecked() {
    sweepForTest()
    assertConsistent(this)
  }

  /**
   * Every entry in the map is live or idle, never both, and the idle bytes are the idle entries' own; the nine-patch
   * masks agree ([assertMasksConsistent]).
   */
  private fun assertConsistent(store: ShadowStore) {
    val live = store.entriesForTest()
    val idle = store.idleEntriesForTest()
    assertEquals("map size == live + idle", live.size + idle.size, store.mapSizeForTest())
    assertTrue("live entries are not idle", live.none { it.idle })
    assertTrue("idle entries have no holder", idle.all { it.idle && it.refCount == 0 })
    assertEquals("idle bytes", idle.sumOf { it.idleBytes }, store.idleBytesForTest())
    assertMasksConsistent(store)
  }

  @Test fun reviveWithoutRebuild() = reviveWithoutRebuild(inner = false)

  @Test fun reviveInnerWithoutRebuild() = reviveWithoutRebuild(inner = true)

  /**
   * Released, the entry is idle (not live, its bytes counted); the same key taken again by another holder is the same
   * entry with the same bitmap and no build, marked shared. An idle entry is not re-keyed in place by a holder that
   * moves to another key.
   */
  private fun reviveWithoutRebuild(inner: Boolean) = onMain {
    val store = mainLooperStore()
    val e = store.obtainW(null, W1, inner = inner)
    val bitmap = e.bitmaps.bitmap
    val builds = store.buildCount
    store.releaseChecked(e)
    assertEquals("live entries after release", 0, store.entriesForTest().size)
    assertEquals("idle entries after release", listOf(e), store.idleEntriesForTest())
    assertTrue("idle entry stays built", e.built)
    assertEquals("idle bytes", e.bitmaps.byteCount, store.idleBytesForTest())
    assertTrue("idle bytes > 0", store.idleBytesForTest() > 0)
    // Another holder moves its own entry to other keys: the idle entry is never the one re-keyed.
    val other = store.obtainW(null, W2, inner = inner)
    val moved = store.obtainW(other, W3, inner = inner)
    assertSame("the other holder's entry is re-keyed in place", other, moved)
    assertEquals("idle entry keeps its key", W1, e.key.size.width, 0f)
    val revived = store.obtainW(null, W1, inner = inner)
    assertSame("revived entry", e, revived)
    assertSame("revived bitmap", bitmap, revived.bitmaps.bitmap)
    assertEquals("builds on revive", builds + 2, store.buildCount)
    assertEquals("refCount", 1, revived.refCount)
    assertTrue("revive marks the entry shared", revived.sharedSinceBuild)
    assertTrue("no idle entries after the revive", store.idleEntriesForTest().isEmpty())
    assertEquals("idle bytes after the revive", 0L, store.idleBytesForTest())
    store.releaseChecked(revived)
    store.releaseChecked(moved)
    store.trimChecked()
    assertStoreEmpty(store)
  }

  /**
   * The idle byte cap: releases beyond it evict the least recently released idle entry first; a revived and released
   * again entry counts as released last. Live entries are never evicted, even under a cap of 0.
   */
  @Test
  fun lruEvictionUnderByteCap() = onMain {
    val bytes = LongArray(3)
    ShadowStore(looper = null).let { probe ->
      for ((i, w) in WIDTHS.withIndex()) {
        val e = probe.obtainW(null, w)
        bytes[i] = e.bitmaps.byteCount
        probe.releaseChecked(e)
      }
    }
    val (b1, b2, b3) = bytes.toList()
    val store = mainLooperStore(maxIdleBytes = b1 + b2 + b3 - 1)
    val e1 = store.obtainW(null, W1)
    val e2 = store.obtainW(null, W2)
    val e3 = store.obtainW(null, W3)
    store.releaseChecked(e2)
    store.releaseChecked(e1)
    assertEquals("idle order", listOf(e2, e1), store.idleEntriesForTest())
    store.releaseChecked(e3)
    assertEquals("over the cap: the oldest release evicted", listOf(e1, e3), store.idleEntriesForTest())
    assertEquals("idle bytes", b1 + b3, store.idleBytesForTest())
    val builds = store.buildCount
    val e2b = store.obtainW(null, W2)
    assertNotSame("an evicted entry is not revived", e2, e2b)
    assertEquals("the evicted key builds again", builds + 1, store.buildCount)
    val e1b = store.obtainW(null, W1)
    assertSame("revived", e1, e1b)
    assertEquals("after the revive", listOf(e3), store.idleEntriesForTest())
    store.releaseChecked(e2b)
    store.releaseChecked(e1b)
    assertEquals("revived and released again counts as the newest", listOf(e2b, e1b), store.idleEntriesForTest())
    assertEquals("idle bytes", b2 + b1, store.idleBytesForTest())
    store.trimChecked()
    assertStoreEmpty(store)

    val zero = mainLooperStore(maxIdleBytes = 0)
    val live = zero.obtainW(null, W1)
    val gone = zero.obtainW(null, W2)
    zero.releaseChecked(gone)
    assertTrue("cap 0: nothing idle", zero.idleEntriesForTest().isEmpty())
    assertEquals("cap 0: the live entry stays", listOf(live), zero.entriesForTest())
    assertTrue("cap 0: the live entry stays built", live.built)
    val builds0 = zero.buildCount
    zero.releaseChecked(zero.obtainW(null, W2))
    assertEquals("cap 0: the released key builds again", builds0 + 1, zero.buildCount)
    zero.releaseChecked(live)
    assertStoreEmpty(zero)
  }

  /**
   * Expiry with the store's clock: an idle entry is dropped by the first sweep at least the timeout after its release;
   * the builder is closed by the first sweep at least the timeout after its last build, while live entries remain;
   * a sweep stays pending exactly while something is idle or the builder is held.
   */
  @Test
  fun idleEntriesExpire() = onMain {
    val clock = FakeClock()
    val store = mainLooperStore(clock)
    val e1 = store.obtainW(null, W1)
    val e2 = store.obtainW(null, W2)
    val live = store.obtainW(null, W3)
    assertTrue("pending after builds", store.sweepPendingForTest())
    store.releaseChecked(e1)
    clock.now += LONG_TIMEOUT / 2
    store.releaseChecked(e2)
    clock.now += LONG_TIMEOUT / 2 - 1
    store.sweepChecked()
    assertEquals("before the first expiry", listOf(e1, e2), store.idleEntriesForTest())
    assertTrue("builder before its expiry", store.hasBuilderForTest())
    clock.now += 1
    store.sweepChecked()
    assertEquals("first expiry", listOf(e2), store.idleEntriesForTest())
    assertFalse("builder closed after the timeout without a build", store.hasBuilderForTest())
    assertEquals("live entry stays", listOf(live), store.entriesForTest())
    assertTrue("live entry stays built", live.built)
    assertTrue("pending while an entry is idle", store.sweepPendingForTest())
    clock.now += LONG_TIMEOUT / 2
    store.sweepChecked()
    assertTrue("second expiry", store.idleEntriesForTest().isEmpty())
    assertEquals("idle bytes", 0L, store.idleBytesForTest())
    assertFalse("nothing pending: nothing idle, no builder", store.sweepPendingForTest())
    // The next build recreates the builder, and a sweep is pending again.
    val builds = store.buildCount
    val rekeyed = store.obtainW(live, W4)
    assertSame("re-keyed in place", live, rekeyed)
    assertEquals("one build", builds + 1, store.buildCount)
    assertTrue("builder recreated", store.hasBuilderForTest())
    assertTrue("pending after the build", store.sweepPendingForTest())
    store.releaseChecked(rekeyed)
    store.trimChecked()
    assertStoreEmpty(store)
    assertFalse("nothing pending after the trim", store.sweepPendingForTest())
  }

  /** A store without a looper (its thread's) keeps nothing idle: release drops the entry, and nothing is posted. */
  @Test
  fun storeWithoutLooperKeepsNothingIdle() {
    var result: Result<Triple<Int, Int, Boolean>>? = null
    thread {
      result = runCatching {
        check(Looper.myLooper() == null)
        val store = ShadowStore.current()
        val e = store.obtainW(null, W1)
        store.releaseChecked(e)
        Triple(store.entriesForTest().size, store.idleEntriesForTest().size, store.sweepPendingForTest())
      }
    }.join()
    val (live, idle, pending) = result!!.getOrThrow()
    assertEquals("live", 0, live)
    assertEquals("idle", 0, idle)
    assertFalse("pending", pending)
  }

  /** The posted sweep runs on the store's own looper and expires idle entries and the builder in real time. */
  @Test
  fun expiryRunsOnTheStoresLooper() {
    val store = onMain { ShadowStore(Looper.getMainLooper(), SHORT_TIMEOUT) }
    onMain {
      store.releaseChecked(store.obtainW(null, W1))
      assertEquals("idle", 1, store.idleEntriesForTest().size)
      assertTrue("pending", store.sweepPendingForTest())
    }
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
    var done = false
    while (!done && System.nanoTime() < deadline) {
      Thread.sleep(50)
      done = onMain { store.idleEntriesForTest().isEmpty() && !store.hasBuilderForTest() && !store.sweepPendingForTest() }
    }
    assertTrue("idle entry, builder and sweep gone within 5 s of a ${SHORT_TIMEOUT} ms timeout", done)
  }

  /**
   * Memory pressure through the process callbacks, delivered on each store's own thread (here the main thread and a
   * HandlerThread): at TRIM_MEMORY_BACKGROUND and above and on onLowMemory, idle entries, published nine-patch masks
   * and the builder go; live entries stay built; a lower level changes nothing. A released entry frees its private
   * nine-patch mask; the mask its first build published stays in the store until the trim recycles it.
   */
  @Suppress("DEPRECATION")
  @Test
  fun trimDropsIdleEntriesAndBuilder() {
    val shape = RoundedCornerShape(8.dp)
    lateinit var store: ShadowStore
    lateinit var live: ShadowEntry
    lateinit var published: NineMask
    onMain {
      store = mainLooperStore()
      live = store.obtainW(null, W1)
      // Three builds of one nine-patch round-rect entry: the first publishes its blurred small round rect, the second
      // (another radius) blurs into its private buffer, the third (another size) places that again.
      val r24 = with(density) { 24.dp.toPx() }
      val r23 = with(density) { 23.dp.toPx() }
      fun square(dp: Int) = with(density) { dp.dp.roundToPx().toFloat() }.let { Size(it, it) }
      var e = store.obtain(null, density, shape, square(200), LayoutDirection.Ltr, r24, 0f)
      published = store.nineMasksForTest().single()
      e = store.obtain(e, density, shape, square(201), LayoutDirection.Ltr, r23, 0f)
      e = store.obtain(e, density, shape, square(202), LayoutDirection.Ltr, r23, 0f)
      val private = checkNotNull(e.bitmaps.nineMask) { "private nine-patch mask" }
      assertEquals("one private reuse", 1, e.bitmaps.nineReuseCount)
      store.releaseChecked(e)
      assertEquals("idle", listOf(e), store.idleEntriesForTest())
      assertNull("an idle entry frees its private mask", e.bitmaps.nineMask)
      assertTrue("private mask recycled", private.isRecycled)
      assertEquals("the published mask stays", listOf(published), store.nineMasksForTest())
      assertEquals("idle bytes: the entry's own", e.bitmaps.byteCount, store.idleBytesForTest())
      assertEquals("published mask bytes", published.bitmap.allocationByteCount.toLong(), store.nineMaskBytesForTest())
    }
    ShadowMemoryCallbacks.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN)
    instrumentation.waitForIdleSync()
    onMain {
      assertEquals("below TRIM_MEMORY_BACKGROUND: idle kept", 1, store.idleEntriesForTest().size)
      assertEquals("below TRIM_MEMORY_BACKGROUND: published mask kept", 1, store.nineMasksForTest().size)
      assertTrue("below TRIM_MEMORY_BACKGROUND: builder kept", store.hasBuilderForTest())
    }

    val other = HandlerThread("shadow-store").apply { start() }
    try {
      val otherHandler = Handler(other.looper)
      fun <T> onOther(block: () -> T): T {
        var r: Result<T>? = null
        val latch = CountDownLatch(1)
        otherHandler.post {
          r = runCatching(block)
          latch.countDown()
        }
        check(latch.await(5, TimeUnit.SECONDS))
        return r!!.getOrThrow()
      }
      val otherStore = onOther {
        ShadowStore.current().also { s ->
          s.releaseChecked(s.obtainW(null, W2))
          assertEquals("other thread: idle", 1, s.idleEntriesForTest().size)
        }
      }

      ShadowMemoryCallbacks.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_BACKGROUND)
      instrumentation.waitForIdleSync()
      onMain {
        assertTrue("trim: no idle entries", store.idleEntriesForTest().isEmpty())
        assertEquals("trim: idle bytes", 0L, store.idleBytesForTest())
        assertFalse("trim: builder closed", store.hasBuilderForTest())
        assertTrue("trim: no published nine-patch masks", store.nineMasksForTest().isEmpty())
        assertTrue("trim: published mask recycled", published.bitmap.isRecycled)
        assertEquals("trim: live entry stays", listOf(live), store.entriesForTest())
        assertTrue("trim: live entry stays built", live.built)
        assertNotNull("trim: live bitmap stays", live.bitmaps.bitmap)
        assertFalse("trim: nothing pending", store.sweepPendingForTest())
      }
      // The other thread's trim message was posted before this one.
      onOther {
        assertTrue("other thread: trimmed", otherStore.idleEntriesForTest().isEmpty())
        assertFalse("other thread: builder closed", otherStore.hasBuilderForTest())
      }

      onMain { store.releaseChecked(store.obtainW(null, W2)) }
      ShadowMemoryCallbacks.onLowMemory()
      instrumentation.waitForIdleSync()
      onMain {
        assertTrue("low memory: no idle entries", store.idleEntriesForTest().isEmpty())
        assertFalse("low memory: builder closed", store.hasBuilderForTest())
        store.releaseChecked(live)
        store.trimChecked()
        assertStoreEmpty(store)
      }
    } finally {
      other.quitSafely()
    }
  }

  /**
   * [ShadowStore.obtain]'s failed-build rollback with idle entries: a holder whose own entry went idle while it joined
   * an unbuilt entry gets it back from the idle list, even under a byte cap of 0 (nothing is evicted before the
   * rollback); an idle entry is left as it was by a failed first draw; an unbuilt entry released by its last holder is
   * dropped, not kept idle.
   */
  @Test
  fun failedBuildRollbackWithIdleEntries() {
    for (cap in listOf(Long.MAX_VALUE / 4, 0L)) onMain {
      val shape = FailingShape()
      val store = mainLooperStore(maxIdleBytes = cap)
      var a = store.obtainW(null, WA, shape = shape)
      val c = store.obtainW(null, W1, shape = shape)
      shape.failing = true
      // C re-keys in place to W2 and fails: C's entry is unbuilt under W2.
      assertThrows(IllegalStateException::class.java) { store.obtainW(c, W2, shape = shape) }
      assertConsistent(store)
      assertFalse("cap $cap: C unbuilt", c.built)
      // A joins C's unbuilt entry; A's own entry (its last reference) goes idle, then the build fails.
      val entryA = a
      assertThrows(IllegalStateException::class.java) { store.obtainW(entryA, W2, shape = shape) }
      assertConsistent(store)
      assertTrue("cap $cap: no idle entries after the rollback", store.idleEntriesForTest().isEmpty())
      assertEquals("cap $cap: idle bytes after the rollback", 0L, store.idleBytesForTest())
      assertEquals("cap $cap: A's refCount", 1, entryA.refCount)
      assertTrue("cap $cap: A's entry built", entryA.built)
      assertFalse("cap $cap: A's entry not idle", entryA.idle)
      assertEquals("cap $cap: C's refCount", 1, c.refCount)
      assertEquals("cap $cap: live entries", setOf(entryA, c), store.entriesForTest().toSet())
      // A failed first draw leaves an idle entry as it was.
      shape.failing = false
      val idle = store.obtainW(null, W3, shape = shape)
      store.releaseChecked(idle)
      val idleExpected = if (cap == 0L) emptyList() else listOf(idle)
      assertEquals("cap $cap: idle", idleExpected, store.idleEntriesForTest())
      shape.failing = true
      assertThrows(IllegalStateException::class.java) { store.obtainW(null, W4, shape = shape) }
      assertConsistent(store)
      assertEquals("cap $cap: idle after a failed first draw", idleExpected, store.idleEntriesForTest())
      assertEquals("cap $cap: live after a failed first draw", setOf(entryA, c), store.entriesForTest().toSet())
      // C's unbuilt entry released by its last holder is dropped, not idle.
      store.releaseChecked(c)
      assertFalse("cap $cap: unbuilt entry not idle", store.idleEntriesForTest().any { it === c })
      assertEquals("cap $cap: live after C's release", listOf(entryA), store.entriesForTest())
      shape.failing = false
      a = store.obtainW(entryA, W2, shape = shape)
      assertSame("cap $cap: re-keyed in place", entryA, a)
      assertTrue("cap $cap: built", a.built)
      store.releaseChecked(a)
      store.trimChecked()
      assertStoreEmpty(store)
    }
  }

  /**
   * An entry whose own bytes exceed the idle cap is dropped at release and leaves the other idle entries as they were;
   * an entry that fits is kept.
   */
  @Test
  fun oversizedReleaseKeepsTheOtherIdleEntries() = onMain {
    val small = ShadowStore(looper = null).let { probe ->
      val e = probe.obtainW(null, W1)
      e.bitmaps.byteCount.also { probe.releaseChecked(e) }
    }
    val store = mainLooperStore(maxIdleBytes = small * 2)
    val e1 = store.obtainW(null, W1)
    val big = store.obtain(null, density, RectangleShape, Size(400f, 400f), LayoutDirection.Ltr, RADIUS, 0f)
    assertTrue("the big entry exceeds the cap", big.bitmaps.byteCount > small * 2)
    store.releaseChecked(e1)
    assertEquals("idle", listOf(e1), store.idleEntriesForTest())
    store.releaseChecked(big)
    assertEquals("the oversized entry is dropped, the other stays idle", listOf(e1), store.idleEntriesForTest())
    assertEquals("idle bytes", e1.idleBytes, store.idleBytesForTest())
    store.trimChecked()
    assertStoreEmpty(store)
  }

  /**
   * A store whose looper has quit keeps nothing idle: the refused post is noticed on the next build, idle entries are
   * dropped, release drops the entry, and a trim request is not posted again.
   */
  @Test
  fun quitLooperStoreDropsOnRelease() {
    val other = HandlerThread("shadow-store-quit").apply { start() }
    val looper = other.looper
    other.quitSafely()
    other.join(5_000)
    onMain {
      val store = ShadowStore(looper, LONG_TIMEOUT, Long.MAX_VALUE / 4, FakeClock())
      assertTrue("keeps idle before any post", store.keepsIdleForTest())
      val e = store.obtainW(null, W1)
      assertFalse("the refused post is noticed", store.keepsIdleForTest())
      assertFalse("nothing pending", store.sweepPendingForTest())
      store.releaseChecked(e)
      assertTrue("no idle entries", store.idleEntriesForTest().isEmpty())
      assertEquals("map", 0, store.mapSizeForTest())
      store.requestTrim()
      store.requestTrim()
      assertStoreEmpty(store)
    }
    onMain {
      val store = ShadowStore(looper, LONG_TIMEOUT, Long.MAX_VALUE / 4, FakeClock())
      store.requestTrim()
      assertFalse("a refused trim post is noticed", store.keepsIdleForTest())
      store.releaseChecked(store.obtainW(null, W1))
      assertTrue("no idle entries", store.idleEntriesForTest().isEmpty())
      assertStoreEmpty(store)
    }
  }

  /**
   * A looper that quits with a sweep pending (posted and accepted, then dropped by the quit): nothing runs on its
   * thread again, so the builder's native handle is freed when the store itself is collected. The test holds only a
   * weak reference to the store and the builder's close callback.
   */
  @Test
  fun quitWithPendingSweepClosesBuilderWhenCollected() {
    val other = HandlerThread("shadow-store-pending-quit").apply { start() }
    val closed = CountDownLatch(1)
    val setUp = CountDownLatch(1)
    var state: Result<java.lang.ref.WeakReference<ShadowStore>>? = null
    Handler(other.looper).post {
      state = runCatching {
        val store = ShadowStore(other.looper, LONG_TIMEOUT, Long.MAX_VALUE / 4, FakeClock())
        store.releaseChecked(store.obtainW(null, W1))
        assertTrue("the sweep is pending", store.sweepPendingForTest())
        val builder = checkNotNull(store.builderForTest()) { "no builder after a build" }
        builder.onCloseForTest = { closed.countDown() }
        java.lang.ref.WeakReference(store)
      }
      setUp.countDown()
    }
    assertTrue("set up", setUp.await(10, TimeUnit.SECONDS))
    val storeRef = state!!.getOrThrow()
    other.quit()
    other.join(5_000)
    assertEquals("not closed by the quit itself", 1L, closed.count)
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
    while (closed.count > 0 && System.nanoTime() < deadline) {
      Runtime.getRuntime().gc()
      System.runFinalization()
      closed.await(100, TimeUnit.MILLISECONDS)
    }
    assertNull("the store is collected", storeRef.get())
    assertEquals("the builder is closed", 0L, closed.count)
  }

  /** The callbacks are registered once, on the application context, however many times and from whatever context. */
  @Test
  fun callbacksRegisterOnceOnTheApplicationContext() {
    val base = instrumentation.targetContext
    class Counting(base: Context) : ContextWrapper(base) {
      var registrations = 0

      override fun registerComponentCallbacks(callback: ComponentCallbacks) {
        registrations++
      }
    }
    val app = Counting(base)
    val activityLike =
      object : ContextWrapper(base) {
        var registrations = 0

        override fun getApplicationContext(): Context = app

        override fun registerComponentCallbacks(callback: ComponentCallbacks) {
          registrations++
        }
      }
    val callbacks =
      object : ComponentCallbacks2 {
        override fun onTrimMemory(level: Int) = Unit

        override fun onConfigurationChanged(newConfig: Configuration) = Unit

        @Deprecated("Deprecated in Java") override fun onLowMemory() = Unit
      }
    val registration = CallbacksRegistration(callbacks)
    assertFalse("not done before install", registration.done)
    repeat(3) { registration.install(activityLike) }
    registration.install(app)
    assertTrue("done", registration.done)
    assertEquals("registered once on the application context", 1, app.registrations)
    assertEquals("never on the given context", 0, activityLike.registrations)
  }

  @Test fun reviveThenRekeyKeepsTheRevivedBitmap() = reviveThenRekey(inner = false)

  @Test fun reviveThenRekeyKeepsTheRevivedInnerBitmap() = reviveThenRekey(inner = true)

  /**
   * The bitmap a released holder last drew (its recorded frames may still read it) is never rewritten after another
   * holder revives the entry and re-keys it in place: the first re-key writes the second buffer, and the bytes of the
   * revived bitmap stay those of its own build through further re-keys.
   */
  private fun reviveThenRekey(inner: Boolean) = onMain {
    val store = mainLooperStore()
    val shape = RoundedCornerShape(8.dp)
    val e = store.obtainW(null, W1, radius = 6f, shape = shape, inner = inner)
    val revivedBitmap = checkNotNull(e.bitmaps.bitmap)
    val before = bytesOf(revivedBitmap)
    store.releaseChecked(e)
    val revived = store.obtainW(null, W1, radius = 6f, shape = shape, inner = inner)
    assertSame("revived", e, revived)
    val allocations = e.bitmaps.allocationCount
    val r1 = store.obtainW(revived, W1, radius = 9f, shape = shape, inner = inner)
    assertSame("re-keyed in place", e, r1)
    assertNotSame("the re-key writes the other buffer", revivedBitmap, r1.bitmaps.bitmap)
    assertEquals("the second buffer allocated once", allocations + 1, e.bitmaps.allocationCount)
    assertArrayEquals("revived bitmap unchanged after the first re-key", before, bytesOf(revivedBitmap))
    val r2 = store.obtainW(r1, W1, radius = 12f, shape = shape, inner = inner)
    assertSame("re-keyed in place again", e, r2)
    assertNotSame("still the other buffer", revivedBitmap, r2.bitmaps.bitmap)
    assertArrayEquals("revived bitmap unchanged after the second re-key", before, bytesOf(revivedBitmap))
    store.releaseChecked(r2)
    store.trimChecked()
    assertStoreEmpty(store)
  }

  private fun bytesOf(b: Bitmap): ByteArray {
    val buffer = ByteBuffer.allocate(b.byteCount)
    b.copyPixelsToBuffer(buffer)
    return buffer.array()
  }

  private fun assertStoreEmpty(store: ShadowStore) {
    assertEquals("map size", 0, store.mapSizeForTest())
    assertEquals("nine-patch masks", 0, store.nineMasksForTest().size)
    assertEquals("live entries", 0, store.entriesForTest().size)
    assertEquals("idle entries", 0, store.idleEntriesForTest().size)
    assertEquals("idle bytes", 0L, store.idleBytesForTest())
    assertFalse("builder", store.hasBuilderForTest())
    assertFalse("sweep pending", store.sweepPendingForTest())
  }

  private companion object {
    const val W1 = 40f
    const val W2 = 50f
    const val W3 = 60f
    const val W4 = 70f
    const val WA = 80f
    val WIDTHS = floatArrayOf(W1, W2, W3)
    const val HEIGHT = 30f
    const val RADIUS = 6f

    /** A timeout no posted sweep reaches during a test (the store's clock is driven by the test). */
    const val LONG_TIMEOUT = 600_000L
    const val SHORT_TIMEOUT = 200L
  }
}
