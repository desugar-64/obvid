// Copyright 2026, Serhii Yaremych (desugar-64) and the Obvid project contributors
// SPDX-License-Identifier: Apache-2.0

package dev.serhiiyaremych.obvid

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.toShape
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.toRect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * [ShadowBuilds.Parallel]: first builds queued when a node is measured, built by a worker ([ShadowWorkers]) and adopted
 * by the draw ([ShadowStore.adopt]).
 *
 * The store-level tests run on the main thread with its store and drive [ShadowDrawState] as the nodes do: `prefetch`
 * at measure, the draw through a [CanvasDrawScope]. [ShadowDrawState.awaitPrefetchForTest] waits for the worker, so a
 * draw takes the worker's build (without it, a draw that comes first builds inline: the same pixels, but not this
 * path). Every worker build is compared with an inline build of the same key by a store without a looper (bitmap
 * bytes, window and bands). The Compose tests check the nodes: a whole screen drawn the same with both modes, no
 * remeasure on an update, and jobs released by nodes that were measured but never drawn.
 */
@RunWith(AndroidJUnit4::class)
class ShadowPrefetchTest {

  @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

  private val density = Density(InstrumentationRegistry.getInstrumentation().targetContext)

  /** One shadow as a node would measure and draw it (px). */
  private class Case(
    val name: String,
    val shape: Shape,
    val w: Float,
    val h: Float,
    val radius: Float,
    val spread: Float,
    val inner: Boolean = false,
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
    val extent: ShadowExtent = ShadowExtent.Compose,
  ) {
    fun sized(w: Float, h: Float) = Case("$name ${w}x$h", shape, w, h, radius, spread, inner, offsetX, offsetY, extent)
  }

  @Before
  fun setUp() {
    rule.runOnUiThread { with(ScreenCases) { rule.activity.showOverLockScreen() } }
    clearIdleShadows()
  }

  // -----------------------------------------------------------------------------------------------------------------
  // Store level
  // -----------------------------------------------------------------------------------------------------------------

  @Test
  fun workerBuildsMatchInlineBuilds() = onMain {
    val store = ShadowStore.current()
    val adopted = store.adoptCount
    for (c in cases()) {
      val state = ShadowDrawState()
      try {
        state.measure(c)
        state.awaitPrefetchForTest()
        state.draw(c)
        assertMatchesInline(c, store.heldEntry(c))
      } finally {
        state.release()
      }
    }
    assertEquals("worker builds adopted", cases().size.toLong(), store.adoptCount - adopted)
    assertEquals("jobs left", 0, store.prefetchCountForTest())
  }

  @Test
  fun adoptPublishesTheWorkersNineMask() = onMain {
    val store = ShadowStore.current()
    // Large enough for the nine-patch route (a small card's corners and blur cover it, and it blurs the whole mask).
    val first = Case("round", RoundedCornerShape(16.dp), 520f, 400f, 48f, 0f)
    val a = ShadowDrawState()
    val b = ShadowDrawState()
    try {
      val published = store.ninePublishCount
      a.measure(first)
      a.awaitPrefetchForTest()
      a.draw(first)
      assertEquals("masks published at adopt", published + 1, store.ninePublishCount)
      assertEquals("published masks", 1, store.nineMasksForTest().size)
      // Same corners and radius, another size: the inline build places the adopted mask.
      val hits = store.nineSharedHitCount
      b.draw(first.sized(600f, 430f))
      assertEquals("inline build placed the adopted mask", hits + 1, store.nineSharedHitCount)
    } finally {
      a.release()
      b.release()
    }
  }

  /**
   * Two nodes share one job. The first adopts it and then animates (its entry, held by it alone, is re-keyed in
   * place); the second draws the job's key after that and must get its own entry, not the re-keyed one.
   */
  @Test
  fun secondHolderAfterReKeyGetsItsOwnEntry() = onMain {
    val store = ShadowStore.current()
    val k = Case("round", RoundedCornerShape(10.dp), 90f, 60f, 12f, 0f)
    val k2 = k.sized(100f, 60f)
    val a = ShadowDrawState()
    val b = ShadowDrawState()
    try {
      a.measure(k)
      b.measure(k)
      assertEquals("one job for both", 1, store.prefetchCountForTest())
      a.awaitPrefetchForTest()
      a.draw(k)
      val adopted = store.heldEntry(k)
      a.draw(k2)
      assertEquals("first holder re-keyed in place", k2.key(), adopted.key)
      b.draw(k)
      val second = store.heldEntry(k)
      assertTrue("second holder has its own entry", second !== adopted)
      assertEquals("first holder's refCount", 1, adopted.refCount)
      assertEquals("second holder's refCount", 1, second.refCount)
      assertMatchesInline(k, second)
      assertMatchesInline(k2, adopted)
      assertEquals("jobs left", 0, store.prefetchCountForTest())
    } finally {
      a.release()
      b.release()
    }
    assertEquals("held entries after release", 0, store.entriesForTest().size)
  }

  @Test
  fun detachBeforeTheDrawReleasesTheJob() = onMain {
    val store = ShadowStore.current()
    val state = ShadowDrawState()
    state.measure(Case("round", RoundedCornerShape(8.dp), 70f, 50f, 10f, 0f))
    assertEquals("queued", 1, store.prefetchCountForTest())
    state.release()
    assertEquals("jobs left", 0, store.prefetchCountForTest())
    assertEquals("held entries", 0, store.entriesForTest().size)
  }

  /** A shape whose outline reads state queues nothing: the state can change before the draw, which builds it. */
  @Test
  fun stateReadingShapeIsLeftToTheDraw() = onMain {
    val store = ShadowStore.current()
    val corner = mutableStateOf(6f)
    val shape =
      object : Shape {
        override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline =
          Outline.Rounded(RoundRect(size.toRect(), CornerRadius(corner.value)))
      }
    val measured = Case("state", shape, 80f, 60f, 10f, 0f)
    val state = ShadowDrawState()
    try {
      state.measure(measured)
      assertEquals("queued", 0, store.prefetchCountForTest())
      corner.value = 20f
      state.draw(measured)
      val drawn = Case("drawn", RoundedCornerShape(20f), 80f, 60f, 10f, 0f)
      assertMatchesInline(drawn, store.heldEntry(measured))
    } finally {
      state.release()
    }
  }

  @Test
  fun newSizeAtTheDrawBuildsInline() = onMain {
    val store = ShadowStore.current()
    val measured = Case("cut", CutCornerShape(12.dp), 90f, 70f, 14f, 3f)
    val drawn = measured.sized(96f, 70f)
    val state = ShadowDrawState()
    try {
      state.measure(measured)
      state.awaitPrefetchForTest()
      val adopted = store.adoptCount
      state.draw(drawn)
      assertEquals("nothing adopted", adopted, store.adoptCount)
      assertMatchesInline(drawn, store.heldEntry(drawn))
      assertEquals("jobs left", 0, store.prefetchCountForTest())
    } finally {
      state.release()
    }
  }

  /** A spread that leaves no bitmap: the worker's build throws, and the draw throws the same as an inline build. */
  @Test
  fun workerFailureThrowsFromTheDraw() = onMain {
    val store = ShadowStore.current()
    val empty = Case("empty", RoundedCornerShape(4.dp), 20f, 20f, 2f, -30f)
    val inline = assertThrows(IllegalArgumentException::class.java) { reference(empty) {} }
    val state = ShadowDrawState()
    try {
      state.measure(empty)
      state.awaitPrefetchForTest()
      val thrown = assertThrows(IllegalArgumentException::class.java) { state.draw(empty) }
      assertEquals("message", inline.message, thrown.message)
      assertEquals("held entries", 0, store.entriesForTest().size)
      assertEquals("jobs left", 0, store.prefetchCountForTest())
    } finally {
      state.release()
    }
  }

  @Test
  fun exactlyOneSideBuilds() {
    val c = Case("round", RoundedCornerShape(8.dp), 60f, 40f, 8f, 0f)
    // A draw that comes first: the worker skips the job.
    val drawFirst = job(c)
    assertTrue("draw claims a free job", drawFirst.claimForDraw())
    drawFirst.run()
    assertTrue("still the draw's", drawFirst.claimForDraw())
    // A cancelled job: the worker skips it, and a draw builds inline.
    val cancelled = job(c)
    cancelled.cancel()
    cancelled.run()
    assertTrue("cancelled job is the draw's", cancelled.claimForDraw())
    // A worker that comes first: the draw waits for it.
    val workerFirst = job(c)
    assertTrue("queued", ShadowWorkers.submit(workerFirst))
    assertNull("worker build failed", workerFirst.awaitWorker())
    assertFalse("the worker's", workerFirst.claimForDraw())
    assertTrue("built", workerFirst.takeEntry().built)
  }

  /**
   * A shape that reuses one path, rewritten for each size (as material3's `RoundedPolygon.toShape()` does): a node
   * measured right after another must not change the first one's outline under its worker.
   */
  @Test
  fun sharedShapePathIsCopiedForTheWorker() = onMain {
    val store = ShadowStore.current()
    val shape = ReusedPathShape()
    val small = Case("reused", shape, 70f, 50f, 9f, 0f)
    val large = small.sized(140f, 110f)
    val a = ShadowDrawState()
    val b = ShadowDrawState()
    try {
      a.measure(small)
      b.measure(large)
      a.awaitPrefetchForTest()
      b.awaitPrefetchForTest()
      a.draw(small)
      b.draw(large)
      assertMatchesInline(small.copyWith(ReusedPathShape()), store.heldEntry(small))
      assertMatchesInline(large.copyWith(ReusedPathShape()), store.heldEntry(large))
    } finally {
      a.release()
      b.release()
    }
  }

  // -----------------------------------------------------------------------------------------------------------------
  // Compose nodes
  // -----------------------------------------------------------------------------------------------------------------

  private var mode by mutableStateOf<ShadowBuilds?>(null)

  /** A screen of shadows: both modifiers and overloads, round, cut, circle and Material shapes, a brush, a shared key. */
  @SdkSuppress(minSdkVersion = 26)
  @Test
  fun parallelScreenMatchesInline() {
    rule.setContent {
      val m = mode
      if (m != null) {
        key(m) { CompositionLocalProvider(LocalShadowBuilds provides m) { ShadowScreen(Modifier.testTag(TAG)) } }
      }
    }
    val inline = show(ShadowBuilds.Inline)
    val adopted = mainStore { it.adoptCount }
    val parallel = show(ShadowBuilds.Parallel)
    val adoptedNow = mainStore { it.adoptCount }
    val d = ScreenCases.diff(inline, parallel)
    assertTrue("pixels differ: ${d.pixels}, max ${d.max}", d.identical)
    assertEquals("jobs left", 0, mainStore { it.prefetchCountForTest() })
    // A draw that claims every job before a worker does is a valid schedule; the pixels above hold either way.
    assumeTrue("no worker build adopted", adoptedNow > adopted)
  }

  /** An update that changes only what is drawn (the color) remeasures nothing, in either mode. */
  @Test
  fun colorUpdateDoesNotRemeasure() {
    var color by mutableStateOf(Color.Black)
    var measures = 0
    var builds by mutableStateOf(ShadowBuilds.Inline)
    // One instance for every composition: a new lambda would be a new element, which remeasures by itself.
    val counting =
      Modifier.layout { measurable, constraints ->
        measures++
        val p = measurable.measure(constraints)
        layout(p.width, p.height) { p.place(0, 0) }
      }
    rule.setContent {
      // New nodes for each mode: a node reads the mode when it is attached.
      key(builds) {
        CompositionLocalProvider(LocalShadowBuilds provides builds) {
          Box(
            counting
              .padding(24.dp)
              .size(80.dp)
              .dropShadow(RoundedCornerShape(12.dp), Shadow(radius = 10.dp, color = color))
              .innerShadow(RoundedCornerShape(12.dp), Shadow(radius = 6.dp, color = color))
          )
        }
      }
    }
    for (m in listOf(ShadowBuilds.Inline, ShadowBuilds.Parallel)) {
      builds = m
      rule.waitForIdle()
      val before = measures
      for (c in listOf(Color.Red, Color.Blue, Color.Green, Color.Black)) {
        color = c
        rule.waitForIdle()
      }
      assertEquals("$m: measures after 4 color updates", before, measures)
    }
  }

  /** With [ShadowBuilds.Inline], a shadow added to a measured layout only draws (as Compose's): no remeasure. */
  @Test
  fun inlineShadowAddedToAMeasuredLayoutDoesNotRemeasure() {
    var shadows by mutableStateOf(false)
    var measures = 0
    val counting =
      Modifier.layout { measurable, constraints ->
        measures++
        val p = measurable.measure(constraints)
        layout(p.width, p.height) { p.place(0, 0) }
      }
    val drop = Modifier.dropShadow(RoundedCornerShape(12.dp), Shadow(radius = 10.dp))
    val inner = Modifier.innerShadow(RoundedCornerShape(12.dp)) { radius = 6f }
    rule.setContent {
      CompositionLocalProvider(LocalShadowBuilds provides ShadowBuilds.Inline) {
        Box(counting.size(80.dp).then(if (shadows) drop.then(inner) else Modifier))
      }
    }
    rule.waitForIdle()
    val before = measures
    shadows = true
    rule.waitForIdle()
    assertEquals("measures after adding the shadows", before, measures)
  }

  /** Nodes measured but never placed (so never drawn) hold jobs, and let them go when they leave. */
  @Test
  fun measuredButNeverDrawnNodesReleaseTheirJobs() {
    var shown by mutableStateOf(true)
    rule.setContent {
      CompositionLocalProvider(LocalShadowBuilds provides ShadowBuilds.Parallel) {
        if (shown) {
          Box(
            Modifier.layout { measurable, constraints ->
              measurable.measure(constraints)
              // Measured, not placed: the shadows below are never drawn.
              layout(0, 0) {}
            }
          ) {
            Row {
              for (i in 0 until 4) {
                Box(Modifier.size((40 + 10 * i).dp).dropShadow(RoundedCornerShape(8.dp), Shadow(radius = 8.dp)))
              }
            }
          }
        }
      }
    }
    rule.waitForIdle()
    assertEquals("jobs of measured nodes", 4, mainStore { it.prefetchCountForTest() })
    shown = false
    rule.waitForIdle()
    assertEquals("jobs left", 0, mainStore { it.prefetchCountForTest() })
  }

  // -----------------------------------------------------------------------------------------------------------------
  // Helpers
  // -----------------------------------------------------------------------------------------------------------------

  private fun cases(): List<Case> {
    val morph = PlacedMorphShape(0.3f, 0.6f)
    return listOf(
      Case("round drop", RoundedCornerShape(16.dp), 140f, 90f, 20f, 0f),
      Case("round drop, spread", RoundedCornerShape(16.dp), 141f, 90f, 20f, 6f),
      Case("mixed corners drop", RoundedCornerShape(4.dp, 20.dp, 8.dp, 28.dp), 150f, 100f, 18f, 0f),
      Case("circle drop", CircleShape, 96f, 96f, 14f, 0f),
      Case("cut drop", CutCornerShape(14.dp), 120f, 80f, 16f, 0f),
      Case("morph drop", morph, 110f, 110f, 22f, 4f),
      Case("small blur drop", RoundedCornerShape(10.dp), 80f, 60f, 1.5f, 0f),
      Case("round drop, full extent", RoundedCornerShape(16.dp), 142f, 90f, 20f, 0f, extent = ShadowExtent.Full),
      Case("round inner", RoundedCornerShape(24.dp), 160f, 120f, 18f, 0f, inner = true, offsetX = 6f, offsetY = 9f),
      Case("morph inner, spread", morph, 120f, 120f, 16f, 5f, inner = true, offsetX = -4f, offsetY = 7f),
    )
  }

  private fun Case.key() =
    ShadowGeometry(shape, Size(w, h), LayoutDirection.Ltr, density.density, radius, spread, inner, offsetX, offsetY, extent)

  private fun Case.copyWith(other: Shape) = Case(name, other, w, h, radius, spread, inner, offsetX, offsetY, extent)

  private fun ShadowDrawState.measure(c: Case) {
    prefetch(density, c.shape, Size(c.w, c.h), LayoutDirection.Ltr, c.radius, c.spread, c.inner, c.offsetX, c.offsetY, c.extent)
  }

  /** The node's draw of [c] into a scratch canvas (only the store's bitmaps are read). */
  private fun ShadowDrawState.draw(c: Case) {
    val canvas = Canvas(ImageBitmap(CANVAS, CANVAS))
    CanvasDrawScope().draw(density, LayoutDirection.Ltr, canvas, Size(c.w, c.h)) {
      with(this@draw) {
        if (c.inner) drawInnerShadow(c.shape, c.radius, c.spread, c.offsetX, c.offsetY, Color.Black, null, 1f, BlendMode.SrcOver)
        else drawShadow(c.shape, c.radius, c.spread, 0f, 0f, Color.Black, null, 1f, BlendMode.SrcOver, c.extent)
      }
    }
  }

  private fun ShadowStore.heldEntry(c: Case): ShadowEntry {
    val key = c.key()
    return entriesForTest().single { it.key == key }
  }

  /** [block] with an inline build of [c] by a store without a looper (nothing shared, nothing kept). */
  private fun reference(c: Case, block: (ShadowEntry) -> Unit) {
    val store = ShadowStore(looper = null)
    val entry =
      store.obtain(null, density, c.shape, Size(c.w, c.h), LayoutDirection.Ltr, c.radius, c.spread, c.inner, c.offsetX, c.offsetY, c.extent)
    try {
      block(entry)
    } finally {
      store.release(entry)
    }
  }

  private fun assertMatchesInline(c: Case, entry: ShadowEntry) = reference(c) { ref ->
    val a = ref.bitmaps
    val b = entry.bitmaps
    assertTrue("${c.name}: built", entry.built)
    assertTrue("${c.name}: pixels", a.bitmap!!.sameAs(b.bitmap!!))
    assertEquals("${c.name}: window", listOf(a.origin, a.composeSize), listOf(b.origin, b.composeSize))
    assertEquals("${c.name}: bands", a.hasBands, b.hasBands)
    if (a.hasBands) {
      assertEquals("${c.name}: hole and bands", listOf(a.hole, a.bands), listOf(b.hole, b.bands))
    }
    assertEquals("${c.name}: corner radius", a.cornerRadius, b.cornerRadius)
  }

  private fun job(c: Case) =
    ShadowPrefetch(c.key(), c.shape.createOutline(Size(c.w, c.h), LayoutDirection.Ltr, density), Long.MAX_VALUE, keepsMasks = false)

  private fun show(m: ShadowBuilds): IntArray {
    mode = null
    rule.waitForIdle()
    clearIdleShadows()
    mode = m
    rule.waitForIdle()
    return ScreenCases.pixelsOf(rule.onNodeWithTag(TAG).captureToImage().asAndroidBitmap())
  }

  /** One path for every outline, rewritten per size: a round rect of the size. */
  private class ReusedPathShape : Shape {
    private val path = android.graphics.Path()

    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
      path.rewind()
      path.addRoundRect(0f, 0f, size.width, size.height, 14f, 14f, android.graphics.Path.Direction.CW)
      return Outline.Generic(path.asComposePath())
    }
  }

  private companion object {
    const val TAG = "prefetch-screen"
    const val CANVAS = 8
  }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@androidx.compose.runtime.Composable
private fun ShadowScreen(modifier: Modifier) {
  val card = RoundedCornerShape(18.dp)
  val cookie = MaterialShapes.Cookie9Sided.toShape()
  val gradient = Brush.linearGradient(listOf(Color(0xFF3F51B5), Color(0xFFE91E63)))
  Column(modifier.background(Color(0xFFF2F2F2)).padding(24.dp)) {
    Row {
      for (i in 0 until 2) {
        // The same key twice: one job, shared.
        Box(
          Modifier.padding(12.dp)
            .size(90.dp, 64.dp)
            .dropShadow(card, Shadow(radius = 14.dp, color = Color.Black, offset = DpOffset(0.dp, 4.dp), alpha = 0.5f))
            .background(Color.White, card)
        )
      }
      Box(
        Modifier.padding(12.dp)
          .size(70.dp)
          .dropShadow(CircleShape) {
            radius = 12.dp.toPx()
            color = Color(0xFF2196F3)
            offset = Offset(3.dp.toPx(), 5.dp.toPx())
          }
          .background(Color.White, CircleShape)
      )
    }
    Row {
      Box(
        Modifier.padding(12.dp)
          .size(100.dp, 70.dp)
          .dropShadow(CutCornerShape(14.dp), Shadow(radius = 10.dp, brush = gradient, spread = 3.dp))
          .background(Color.White, CutCornerShape(14.dp))
      )
      Box(
        Modifier.padding(12.dp)
          .size(84.dp)
          .background(Color.White, cookie)
          .innerShadow(cookie, Shadow(radius = 8.dp, color = Color.Black, spread = 2.dp, offset = DpOffset(2.dp, 3.dp)))
      )
      Box(
        Modifier.padding(12.dp)
          .size(110.dp, 80.dp)
          .background(Color.White, RoundedCornerShape(4.dp, 22.dp, 8.dp, 30.dp))
          .innerShadow(RoundedCornerShape(4.dp, 22.dp, 8.dp, 30.dp)) {
            radius = 10.dp.toPx()
            spread = 1.dp.toPx()
            color = Color(0xFF4CAF50)
            offset = Offset(-2.dp.toPx(), 4.dp.toPx())
          }
      )
    }
  }
}
