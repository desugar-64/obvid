// Copyright 2026, Serhii Yaremych (desugar-64) and the Obvid project contributors
// SPDX-License-Identifier: Apache-2.0

package dev.serhiiyaremych.obvid

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * [ShadowStore.current] is per thread: the main thread and a second thread obtain the same geometry (40 × 30 px
 * rectangle, radius 6 px; a drop shadow, or an inner shadow with offset (3, −2) px) and each gets its own store and
 * its own built entry; each releases its entry on its own thread, and both stores are then empty.
 */
@RunWith(AndroidJUnit4::class)
class ShadowStorePerThreadTest {

  /** An entry and its state, read on the thread that owns [store]. */
  private class Held(val store: ShadowStore, val entry: ShadowEntry) {
    val refCount = entry.refCount
    val built = entry.built
    val inner = entry.key.inner
  }

  private val density = Density(InstrumentationRegistry.getInstrumentation().targetContext)

  @Test
  fun eachThreadObtainsFromItsOwnStore() = eachThreadObtains(inner = false)

  @Test
  fun eachThreadObtainsItsOwnInnerEntry() = eachThreadObtains(inner = true)

  private fun eachThreadObtains(inner: Boolean) {
    val main = mainStore { Held(it, obtain(it, inner)) }
    var second: Result<Pair<Held, Int>>? = null
    thread {
      second = runCatching {
        val store = ShadowStore.current()
        val held = Held(store, obtain(store, inner))
        store.release(held.entry)
        held to store.entriesForTest().size
      }
    }.join()
    val mainLeft = mainStore {
      it.release(main.entry)
      it.entriesForTest().size
    }
    val (other, otherLeft) = second!!.getOrThrow()
    assertNotSame("one store per thread", main.store, other.store)
    assertNotSame("one entry per store", main.entry, other.entry)
    assertEquals("main thread: refCount", 1, main.refCount)
    assertTrue("main thread: built", main.built)
    assertEquals("second thread: refCount", 1, other.refCount)
    assertTrue("second thread: built", other.built)
    assertEquals("main thread: store entries after release", 0, mainLeft)
    assertEquals("second thread: store entries after release", 0, otherLeft)
    assertEquals("main thread: inner key", inner, main.inner)
    assertEquals("second thread: inner key", inner, other.inner)
  }

  private fun obtain(store: ShadowStore, inner: Boolean): ShadowEntry =
    if (inner) store.obtain(null, density, RectangleShape, Size(40f, 30f), LayoutDirection.Ltr, 6f, 0f, true, 3f, -2f)
    else store.obtain(null, density, RectangleShape, Size(40f, 30f), LayoutDirection.Ltr, 6f, 0f)
}
