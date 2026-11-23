// Copyright 2026, Serhii Yaremych (desugar-64) and the Obvid project contributors
// SPDX-License-Identifier: Apache-2.0

package dev.serhiiyaremych.obvid

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.toRect
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * [ShadowStore.obtain] when the build throws: the exception reaches the caller unchanged, and the store's entries and
 * reference counts match what the callers hold (each caller keeps the entry it passed in, as `ShadowDrawState` does).
 * Calls the store directly, with a shape whose `createOutline` throws while [FailingShape.failing] is set; the other
 * builds are real (40–60 × 30 px, radius 6 px, spread 0). Geometries differ by width, or for the inner shadow
 * ([ShadowGeometry.inner]) by offset, which keeps the outline (no `createOutline` call). Each test has its own store
 * and ends with it empty.
 */
@RunWith(AndroidJUnit4::class)
class ShadowStoreFailedBuildTest {

  /** One instance is one key (shape equality is identity). Throws a new exception per call while [failing]. */
  private class FailingShape : Shape {
    var failing = false
    var thrown: Throwable? = null

    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
      if (failing) throw IllegalStateException("FailingShape.createOutline").also { thrown = it }
      return Outline.Rectangle(size.toRect())
    }
  }

  private val density = Density(InstrumentationRegistry.getInstrumentation().targetContext)
  private val shape = FailingShape()
  private val store = ShadowStore()

  @Test
  fun failedFirstDrawLeavesNothingInTheStore() {
    shape.failing = true
    repeat(2) { i ->
      assertThrowsShapeException { obtain(null, W1) }
      assertEquals("failed first draw #${i + 1}: store entries", 0, store.entriesForTest().size)
    }
    shape.failing = false
    var held: ShadowEntry? = null
    try {
      val entry = obtain(null, W1)
      held = entry
      assertStoreHoldsExactly("first draw after the failures", entry)
      assertEquals("refCount", 1, entry.refCount)
      assertTrue("built", entry.built)
    } finally {
      held?.let(store::release)
    }
    assertEquals("store not empty after release", 0, store.entriesForTest().size)
  }

  @Test
  fun sharedEntrySurvivesFailedBuildOfOneHolder() {
    var a: ShadowEntry? = null
    var b: ShadowEntry? = null
    try {
      val entry = obtain(null, W1)
      a = entry
      b = obtain(null, W1)
      assertSame("both holders share one entry", entry, b)
      assertStoreHoldsExactly("two holders", entry)
      assertEquals("refCount with two holders", 2, entry.refCount)
      shape.failing = true
      repeat(2) { i ->
        // Holder a draws another geometry; the build throws and a keeps its entry.
        assertThrowsShapeException { obtain(entry, W2) }
        val msg = "failed build #${i + 1} of one holder"
        assertStoreHoldsExactly(msg, entry)
        assertEquals("$msg: refCount", 2, entry.refCount)
        assertTrue("$msg: built", entry.built)
      }
      shape.failing = false
    } finally {
      a?.let(store::release)
      b?.let(store::release)
    }
    assertEquals("store not empty after release", 0, store.entriesForTest().size)
  }

  @Test
  fun soleHolderRekeyedInPlaceKeepsItsEntry() {
    var held: ShadowEntry? = null
    try {
      val entry = obtain(null, W1)
      held = entry
      shape.failing = true
      assertThrowsShapeException { obtain(entry, W2) }
      assertStoreHoldsExactly("failed build after a re-key in place", entry)
      assertEquals("refCount after the failed build", 1, entry.refCount)
      assertFalse("built after the failed build", entry.built)
      shape.failing = false
      held = obtain(entry, W2)
      assertSame("the retried call returns the same entry", entry, held)
      assertStoreHoldsExactly("retried call", entry)
      assertEquals("refCount after the retried call", 1, entry.refCount)
      assertTrue("built after the retried call", entry.built)
    } finally {
      held?.let(store::release)
    }
    assertEquals("store not empty after release", 0, store.entriesForTest().size)
  }

  @Test
  fun joiningUnbuiltEntryFailsCleanlyOnHoldersLastReference() {
    var a: ShadowEntry? = null
    var c: ShadowEntry? = null
    try {
      val entryA = obtain(null, WA)
      a = entryA
      val entryC = obtain(null, W1)
      c = entryC
      shape.failing = true
      // C re-keys its entry in place to W2; the build throws, so the store holds C's entry unbuilt under W2.
      assertThrowsShapeException { obtain(entryC, W2) }
      assertStoreHoldsExactly("C's failed build", entryA, entryC)
      assertEquals("C's refCount after its failed build", 1, entryC.refCount)
      assertFalse("C's entry built after its failed build", entryC.built)
      // A joins C's unbuilt entry (A's reference is the last one on its own entry); the build throws.
      assertThrowsShapeException { obtain(entryA, W2) }
      val msg = "A's failed build joining C's entry"
      assertStoreHoldsExactly(msg, entryA, entryC)
      assertEquals("$msg: A's refCount", 1, entryA.refCount)
      assertTrue("$msg: A's entry built", entryA.built)
      assertEquals("$msg: C's refCount", 1, entryC.refCount)
      shape.failing = false
      a = obtain(entryA, W2)
      assertSame("A's retried call returns C's entry", entryC, a)
      assertStoreHoldsExactly("A's retried call", entryC)
      assertEquals("refCount after A's retried call", 2, entryC.refCount)
      assertTrue("built after A's retried call", entryC.built)
    } finally {
      a?.let(store::release)
      c?.let(store::release)
    }
    assertEquals("store not empty after release", 0, store.entriesForTest().size)
  }

  @Test
  fun failedFirstInnerDrawLeavesNothingInTheStore() {
    shape.failing = true
    repeat(2) { i ->
      assertThrowsShapeException { obtainInner(null, OX1) }
      assertEquals("failed first inner draw #${i + 1}: store entries", 0, store.entriesForTest().size)
    }
    shape.failing = false
    var held: ShadowEntry? = null
    try {
      val entry = obtainInner(null, OX1)
      held = entry
      assertStoreHoldsExactly("first inner draw after the failures", entry)
      assertEquals("refCount", 1, entry.refCount)
      assertTrue("built", entry.built)
      assertTrue("inner key", entry.key.inner)
    } finally {
      held?.let(store::release)
    }
    assertEquals("store not empty after release", 0, store.entriesForTest().size)
  }

  @Test
  fun innerOffsetRekeyKeepsTheOutline() {
    var held: ShadowEntry? = null
    try {
      val entry = obtainInner(null, OX1)
      held = entry
      val builds = entry.buildCount
      // The offset is part of an inner key but not of the outline's inputs: the sole holder's entry is re-keyed in
      // place and rebuilt from the kept outline, so a shape that would throw is not asked again.
      shape.failing = true
      held = obtainInner(entry, OX2)
      shape.failing = false
      assertSame("the re-keyed entry", entry, held)
      assertStoreHoldsExactly("offset re-key", entry)
      assertEquals("refCount after the re-key", 1, entry.refCount)
      assertTrue("built after the re-key", entry.built)
      assertEquals("builds", builds + 1, entry.buildCount)
      assertEquals("offset after the re-key", OX2, entry.key.offsetXPx, 0f)
      // A width change does need a new outline: the build throws and the entry stays re-keyed and unbuilt.
      shape.failing = true
      assertThrowsShapeException { store.obtain(entry, density, shape, Size(W2, HEIGHT), LayoutDirection.Ltr, RADIUS, 0f, true, OX2, 2f) }
      shape.failing = false
      assertStoreHoldsExactly("failed inner build after a width re-key", entry)
      assertEquals("refCount after the failed build", 1, entry.refCount)
      assertFalse("built after the failed build", entry.built)
    } finally {
      held?.let(store::release)
    }
    assertEquals("store not empty after release", 0, store.entriesForTest().size)
  }

  @Test
  fun sharedInnerEntrySurvivesFailedBuildOfOneHolder() {
    var a: ShadowEntry? = null
    var b: ShadowEntry? = null
    var drop: ShadowEntry? = null
    try {
      val entry = obtainInner(null, OX1)
      a = entry
      b = obtainInner(null, OX1)
      assertSame("both inner holders share one entry", entry, b)
      // A drop shadow of the same shape, size, radius and spread is another entry.
      val dropEntry = obtain(null, W1)
      drop = dropEntry
      assertStoreHoldsExactly("two inner holders and a drop holder", entry, dropEntry)
      shape.failing = true
      assertThrowsShapeException { obtainInner(entry, OX2) }
      assertStoreHoldsExactly("failed build of one inner holder", entry, dropEntry)
      assertEquals("refCount after the failed build", 2, entry.refCount)
      assertTrue("built after the failed build", entry.built)
      shape.failing = false
    } finally {
      a?.let(store::release)
      b?.let(store::release)
      drop?.let(store::release)
    }
    assertEquals("store not empty after release", 0, store.entriesForTest().size)
  }

  private fun obtain(previous: ShadowEntry?, width: Float): ShadowEntry =
    store.obtain(previous, density, shape, Size(width, HEIGHT), LayoutDirection.Ltr, RADIUS, 0f)

  /** An inner shadow of the [W1]-wide geometry with the offset ([offsetX], 2 px). */
  private fun obtainInner(previous: ShadowEntry?, offsetX: Float): ShadowEntry =
    store.obtain(previous, density, shape, Size(W1, HEIGHT), LayoutDirection.Ltr, RADIUS, 0f, true, offsetX, 2f)

  /** [call] throws, and what reaches the caller is the exception instance the shape threw. */
  private fun assertThrowsShapeException(call: () -> Unit) {
    shape.thrown = null
    val caught = assertThrows(Throwable::class.java) { call() }
    assertNotNull("the shape did not throw", shape.thrown)
    assertSame("the caller gets the shape's exception", shape.thrown, caught)
  }

  /** The store's entries are exactly [expected] (by identity). */
  private fun assertStoreHoldsExactly(message: String, vararg expected: ShadowEntry) {
    val actual = store.entriesForTest()
    assertEquals("$message: store entries", expected.size, actual.size)
    for (e in expected) assertTrue("$message: entry ${e.key} not in the store", actual.any { it === e })
  }

  private companion object {
    const val W1 = 40f
    const val W2 = 50f
    const val WA = 60f
    const val HEIGHT = 30f
    const val RADIUS = 6f
    const val OX1 = 0f
    const val OX2 = 3.5f
  }
}
