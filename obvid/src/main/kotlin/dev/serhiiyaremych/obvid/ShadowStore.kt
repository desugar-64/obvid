// Copyright 2026, Serhii Yaremych (desugar-64) and the Obvid project contributors
// SPDX-License-Identifier: Apache-2.0

package dev.serhiiyaremych.obvid

import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.collection.MutableScatterMap
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import java.lang.ref.WeakReference
import kotlin.math.max
import kotlin.math.min

/**
 * One shadow bitmap shared by every node of one [ShadowStore] whose [ShadowGeometry] equals [key]. Built lazily by
 * the first node that draws it; reference-counted by its store. It holds only its own output ([bitmaps], with the
 * nine-patch route's private small mask written by a rebuild that missed, freed when the entry goes idle or is
 * dropped); the build context and the published nine-patch masks are the store's.
 *
 * Released by its last node, a built entry stays in its store as idle ([idle]) until it is taken again, evicted or
 * expired; an idle entry is never rebuilt or re-keyed.
 */
internal class ShadowEntry(val key: ShadowGeometry) {
  var refCount = 0

  /** No node holds it; it is in its store's idle list ([idlePrev], [idleNext]), oldest release first. */
  var idle = false

  /** When it became idle (the store's clock). */
  var idleSince = 0L

  /** [ShadowBitmaps.byteCount] when it became idle (an idle entry's bitmaps do not change). */
  var idleBytes = 0L

  /** When it became idle in the store's [IdleSequence]. */
  var idleOrder = 0L

  var idlePrev: ShadowEntry? = null
  var idleNext: ShadowEntry? = null

  val bitmaps = ShadowBitmaps()

  /** False after a re-key: the next draw rebuilds. */
  var built = false

  /** Builds of this entry: a cached shader or image of an older build is stale (also a test counter). */
  var buildCount = 0
    private set

  /** Inner draws of this entry that went in bands around the bitmap's hole (a test counter). */
  var bandDrawCount = 0

  /** A frame recorded since the last build sampled the bitmap through a `BitmapShader` (not pinned at sync). */
  var sampledByShader = false

  /** Another node drew this bitmap since the last build (its display list may outlive its share). */
  var sharedSinceBuild = false

  private var image: ImageBitmap? = null
  private var imageSource: android.graphics.Bitmap? = null

  /**
   * Builds the shadow of [key] with [builder] (the drop or the inner build, by [ShadowGeometry.inner]). The outline is
   * kept from the previous build while its inputs are unchanged (`bitmaps.outline`, cleared by a re-key that changes
   * them); otherwise Compose creates it the same way, with the draw scope as the density (the renderer providers /
   * `AndroidShadowContext.obtainDropShadowRenderer`, `obtainInnerShadowRenderer`).
   *
   * The bitmap a recorded frame may still read is never rewritten: if it was drawn through a shader or by another
   * node since the last build, the builder writes into the second buffer (`preserveCurrent`). [mayPublish]: a first
   * build may publish its nine-patch mask in the store.
   */
  fun build(builder: ShadowBitmapBuilder, density: Density, mayPublish: Boolean) {
    val k = key
    val outline = bitmaps.outline ?: k.shape.createOutline(k.size, k.layoutDirection, density)
    val preserve = sampledByShader || sharedSinceBuild
    if (k.inner) {
      builder.buildInner(
        bitmaps, outline, k.size, k.radiusPx, k.spreadPx, k.offsetXPx, k.offsetYPx, preserve, mayPublish,
      )
    } else {
      builder.build(bitmaps, outline, k.size, k.radiusPx, k.spreadPx, preserve, mayPublish, k.extent == ShadowExtent.Full)
    }
    built = true
    buildCount++
    sampledByShader = false
    sharedSinceBuild = refCount > 1
  }

  /** The current bitmap as a Compose [ImageBitmap] (one wrapper per bitmap instance). */
  fun imageBitmap(): ImageBitmap {
    val bmp = checkNotNull(bitmaps.bitmap) { "not built" }
    val cached = image
    if (cached != null && imageSource === bmp) return cached
    return bmp.asImageBitmap().also {
      image = it
      imageSource = bmp
    }
  }

  /**
   * The bitmap size of the last build, the bitmap's own size: Compose's (drop: `ceil(size + 2·radius + 2·spread)` per
   * axis; inner: `ceil(size)`, plus `2·ceil(radius)` for a path), or a drop shadow's full-extent window
   * ([ShadowBitmaps.originX]).
   */
  val usedWidth: Int
    get() = bitmaps.bitmap?.width ?: 0

  val usedHeight: Int
    get() = bitmaps.bitmap?.height ?: 0
}

/** The store's time source, in `SystemClock.uptimeMillis` units (the time base of [Handler.postDelayed]). */
internal fun interface ShadowClock {
  fun uptimeMillis(): Long
}

/**
 * Reference-counted store of shadow bitmaps keyed by [ShadowGeometry]. Identical shadows share one entry. An entry
 * whose last node releases it becomes idle: it stays findable by its key, so a node that draws that geometry again
 * (a list item scrolled back in) takes it without a build. Idle entries are bounded: their bitmaps' bytes by
 * [maxIdleBytes] (least recently released evicted first), their age by [idleTimeoutMs], and all of them are dropped on
 * memory pressure ([ShadowMemoryCallbacks]). An animation re-keys its entry in place, so it leaves nothing idle behind.
 *
 * One store per thread ([current]), not thread-safe: a store and its entries are used only on the thread that owns
 * them. A node uses the store of the thread it draws on; Compose draws and detaches a node on its owner's UI thread,
 * so the windows of one UI thread (dialogs, popups) share one store. Expiry and memory pressure run as messages on
 * [looper], the owning thread's, at most one pending sweep at a time and none while nothing is idle and no builder is
 * held.
 *
 * The nine-patch route's blurred small round rects published by the first build of a node's first appearance
 * ([obtain] without a previous entry: a list item scrolled in) are the store's ([NineMaskCache]), placed by any build,
 * drop or inner, whose nine-patch has the same key; no entry holds one. They are idle under the same
 * rules as idle entries, from their last use: the same expiry, the same byte cap (idle entries and published masks
 * together, the least recently released or used first, [IdleSequence]) and dropped on memory pressure or when
 * nothing can stay idle. An entry's private small mask (written by its rebuilds) is its own, freed when it goes idle
 * (an idle entry is never rebuilt), so idle bytes never include it.
 *
 * A store without a looper cannot be reached later on its thread, so it keeps nothing idle: an entry is dropped when
 * its last node releases it, nothing is published, and the builder lives until the store empties. A store whose looper
 * has quit (a post refused) behaves the same from then on, and drops its idle entries and published masks when it
 * finds out on its own thread. An entry larger than the whole idle budget is dropped at release, and such a mask is
 * never published, rather than flushing the others.
 *
 * Builds use one shared [ShadowBitmapBuilder] per store (native scratch, mask bitmap, canvases, paints, paths);
 * entries keep only their bitmaps. The builder is created on the next build after it was closed, and closed when the
 * store holds no entries (live or idle) or, with a looper, [idleTimeoutMs] after its last build; failing both (a
 * looper that quit with a sweep pending), when the store itself is collected.
 */
internal class ShadowStore(
  looper: Looper? = Looper.myLooper(),
  private var idleTimeoutMs: Long = IDLE_TIMEOUT_MS,
  private val maxIdleBytes: Long = MAX_IDLE_BYTES,
  private val clock: ShadowClock = ShadowClock { SystemClock.uptimeMillis() },
) {
  private val entries = MutableScatterMap<ShadowGeometry, ShadowEntry>()
  private val lookup = ShadowGeometry()

  /** Volatile for [closesBuilderWhenCollected], which reads it on the finalizer thread. */
  @Volatile private var builder: ShadowBitmapBuilder? = null

  /**
   * Closes the builder when the store is collected with one still open. A looper that quits with a sweep pending
   * drops it, so nothing runs on that thread again and only the GC can still reach the native handle. The store is
   * unreachable by then (the memory callbacks hold it weakly), so nothing uses the builder any more. A store's own
   * object rather than a finalizer on the builder: a builder could be collected during its own native call.
   */
  @Suppress("unused")
  private val closesBuilderWhenCollected =
    object {
      protected fun finalize() {
        builder?.close()
      }
    }

  /** When [builder] was last used for a build. */
  private var lastBuildAt = 0L

  private val handler: Handler? = looper?.let { Handler(it) }

  /** The looper refused a post (it has quit): nothing posted runs any more. Set from any thread ([requestTrim]). */
  @Volatile private var looperQuit = false

  private val idleSequence = IdleSequence()
  private val nineMasks = NineMaskCache(clock, maxIdleBytes, idleSequence) { handler != null && !looperQuit }
  private var idleOldest: ShadowEntry? = null
  private var idleNewest: ShadowEntry? = null
  private var idleBytes = 0L
  private var sweepPending = false
  private val sweepRunnable = Runnable {
    sweepPending = false
    sweep()
  }
  private val trimRunnable = Runnable { trimMemory() }

  init {
    if (handler != null) ShadowMemoryCallbacks.register(this)
  }

  /** Debug counter: shadow builds of this store (rebuild-rule tests). */
  var buildCount: Long = 0
    private set

  /**
   * Debug counter: the builds of [buildCount] that placed a kept nine-patch small round rect instead of drawing and
   * blurring it, the entry's private one or a published one ([NineMaskCache.reuseCount]).
   */
  val nineReuseCount: Long
    get() = nineMasks.reuseCount

  /** Debug counter: the [nineReuseCount] builds that placed a published small round rect. */
  val nineSharedHitCount: Long
    get() = nineMasks.sharedHitCount

  /** Debug counter: nine-patch builds that drew and blurred their small round rect ([NineMaskCache.blurCount]). */
  val nineBlurCount: Long
    get() = nineMasks.blurCount

  /** Debug counter: the [nineBlurCount] builds that published their mask. */
  val ninePublishCount: Long
    get() = nineMasks.publishCount

  /** Debug counter: small round rect buffers allocated, published or private ([NineMaskCache.allocationCount]). */
  val nineMaskAllocationCount: Long
    get() = nineMasks.allocationCount

  /**
   * The built entry for this geometry, for a node that held [previous] (released here when it is not reused):
   * - [previous]'s own key: [previous] (the per-draw case: no map lookup);
   * - the key already in the store, held by other nodes or idle: shared (no build);
   * - [previous] used only by this node: re-keyed in place, rebuilt into the same bitmaps (no allocation);
   * - otherwise a new entry.
   * [density] is the draw scope, for `createOutline`. [inner] selects the inner shadow, whose offset ([offsetXPx],
   * [offsetYPx]) is part of the key; a drop shadow passes `false` and offset 0, and its [extent] (an inner shadow
   * passes [ShadowExtent.Compose]).
   *
   * If the build throws, the exception propagates as is and the caller holds what it held before: one reference to
   * [previous] (re-keyed and unbuilt if it was re-keyed in place), none to any other entry.
   */
  fun obtain(
    previous: ShadowEntry?,
    density: Density,
    shape: Shape,
    size: Size,
    layoutDirection: LayoutDirection,
    radiusPx: Float,
    spreadPx: Float,
    inner: Boolean = false,
    offsetXPx: Float = 0f,
    offsetYPx: Float = 0f,
    extent: ShadowExtent = ShadowExtent.Compose,
  ): ShadowEntry {
    val key =
      lookup.set(shape, size, layoutDirection, density.density, radiusPx, spreadPx, inner, offsetXPx, offsetYPx, extent)
    val entry = if (previous != null && previous.key == key) previous else acquire(key, previous)
    if (entry.built) {
      if (entry !== previous) settle()
      return entry
    }
    lastBuildAt = clock.uptimeMillis()
    try {
      // Only a node's first appearance publishes a nine-patch mask: a node that held an entry is animating or changing
      // (a re-key, a fork from a shared entry, another key), and its keys are not worth keeping for other nodes.
      traceSection(if (inner) TRACE_INNER_BUILD else TRACE_DROP_BUILD) {
        val b = builder ?: ShadowBitmapBuilder(nineMasks).also { builder = it }
        entry.build(b, density, mayPublish = previous == null)
      }
    } catch (t: Throwable) {
      if (entry !== previous) {
        // Back to the caller's references: previous first, so the store does not empty and close the builder.
        // Nothing is evicted before settle(), so an idle previous is still in the store.
        if (previous != null) retake(previous)
        releaseRef(entry)
      }
      settle()
      throw t
    }
    buildCount++
    settle()
    return entry
  }

  private fun acquire(key: ShadowGeometry, previous: ShadowEntry?): ShadowEntry {
    val existing = entries[key]
    if (existing != null) {
      if (previous != null) releaseRef(previous)
      if (existing.idle) unlinkIdle(existing)
      existing.refCount++
      // Another node drew it since its last build, also when it was idle: that node's recorded frames may still read
      // its bitmap, so its next build must not rewrite it.
      existing.sharedSinceBuild = true
      return existing
    }
    if (previous != null && previous.refCount == 1) {
      entries.remove(previous.key)
      if (!previous.key.sameOutline(key)) previous.bitmaps.clearOutline()
      previous.key.set(key)
      previous.built = false
      entries[previous.key] = previous
      return previous
    }
    if (previous != null) releaseRef(previous)
    return ShadowEntry(key.copy()).also {
      it.refCount = 1
      entries[it.key] = it
    }
  }

  /** Drops a node's reference to [entry]; the last one makes it idle (or drops it, [releaseRef]). */
  fun release(entry: ShadowEntry) {
    releaseRef(entry)
    settle()
  }

  /**
   * [release] without enforcing the idle bounds, so that inside one [obtain] an entry released by the caller stays in
   * the store until [settle] (a failed build takes it back, [retake]). An unbuilt entry, or any entry of a store
   * without a looper, is dropped instead of kept idle. Either way its private nine-patch mask is freed.
   */
  private fun releaseRef(entry: ShadowEntry) {
    check(entry.refCount > 0 && !entry.idle) { "released more often than obtained" }
    if (--entry.refCount > 0) return
    entry.bitmaps.releaseNineMask()
    if (handler == null || looperQuit || !entry.built) {
      remove(entry)
      return
    }
    val bytes = entry.bitmaps.byteCount
    if (bytes > maxIdleBytes) {
      remove(entry)
      return
    }
    entry.idle = true
    entry.idleSince = clock.uptimeMillis()
    entry.idleOrder = idleSequence.next()
    entry.idleBytes = bytes
    entry.idlePrev = idleNewest
    entry.idleNext = null
    val newest = idleNewest
    if (newest != null) newest.idleNext = entry else idleOldest = entry
    idleNewest = entry
    idleBytes += entry.idleBytes
  }

  /**
   * Takes back a reference to [entry] that [releaseRef] dropped in the same [obtain]; if it was the last one, [entry]
   * leaves the idle list, or returns to the map under its key if it was dropped (no other entry took that key in
   * between).
   */
  private fun retake(entry: ShadowEntry) {
    if (++entry.refCount != 1) return
    if (entry.idle) unlinkIdle(entry) else entries[entry.key] = entry
  }

  private fun unlinkIdle(entry: ShadowEntry) {
    val prev = entry.idlePrev
    val next = entry.idleNext
    if (prev != null) prev.idleNext = next else idleOldest = next
    if (next != null) next.idlePrev = prev else idleNewest = prev
    entry.idlePrev = null
    entry.idleNext = null
    entry.idle = false
    idleBytes -= entry.idleBytes
    entry.idleBytes = 0L
  }

  /**
   * Drops [entry] from the store. Its shadow bitmaps are left to the GC (a recorded display list may still hold them);
   * its private nine-patch small mask, if it still has one, is freed now: HWUI never draws it, so no recorded frame
   * reads it.
   */
  private fun remove(entry: ShadowEntry) {
    entries.remove(entry.key, entry)
    entry.bitmaps.releaseNineMask()
    if (entries.isEmpty()) closeBuilder()
  }

  private fun evict(entry: ShadowEntry) {
    unlinkIdle(entry)
    remove(entry)
  }

  private fun closeBuilder() {
    builder?.close()
    builder = null
  }

  /**
   * Enforces the idle byte cap over idle entries and published masks (the earliest release or last use first, by
   * [IdleSequence], so equal clock times are ordered too) and keeps one sweep pending while there is work for it.
   */
  private fun settle() {
    while (idleBytes + nineMasks.bytes > maxIdleBytes) {
      val entry = idleOldest
      val mask = nineMasks.oldest
      when {
        mask != null && (entry == null || mask.order < entry.idleOrder) -> nineMasks.evict(mask)
        entry != null -> evict(entry)
        else -> break
      }
    }
    scheduleSweep()
  }

  /**
   * Posts the sweep for the earliest deadline (the oldest idle entry's expiry, the least recently used mask's, the
   * builder's) unless one is pending, or cancels the pending one when there is nothing left to sweep. Deadlines only
   * move later while a sweep is pending (the idle entries are in release order, the masks in last-use order, builds
   * only renew the builder), so the pending sweep is never late; an early one reschedules.
   */
  private fun scheduleSweep() {
    val h = handler ?: return
    if (looperQuit) {
      looperGone()
      return
    }
    val oldest = idleOldest
    val oldestMask = nineMasks.oldest
    val hasBuilder = builder != null
    if (oldest == null && oldestMask == null && !hasBuilder) {
      if (sweepPending) {
        h.removeCallbacks(sweepRunnable)
        sweepPending = false
      }
      return
    }
    if (sweepPending) return
    var deadline = if (hasBuilder) lastBuildAt + idleTimeoutMs else Long.MAX_VALUE
    if (oldest != null) deadline = min(deadline, oldest.idleSince + idleTimeoutMs)
    if (oldestMask != null) deadline = min(deadline, oldestMask.lastUse + idleTimeoutMs)
    sweepPending = h.postDelayed(sweepRunnable, max(deadline - clock.uptimeMillis(), 0L))
    // Refused: the looper has quit, so no sweep will ever run here.
    if (!sweepPending) {
      looperQuit = true
      looperGone()
    }
  }

  /** The looper has quit: nothing can expire on this thread any more, so nothing stays idle. */
  private fun looperGone() {
    sweepPending = false
    while (true) evict(idleOldest ?: break)
    nineMasks.dropAll()
  }

  /**
   * Drops the idle entries released and the masks last used at least [idleTimeoutMs] ago, and closes the builder if
   * unused for as long.
   */
  private fun sweep() {
    val now = clock.uptimeMillis()
    while (true) {
      val oldest = idleOldest ?: break
      if (now - oldest.idleSince < idleTimeoutMs) break
      evict(oldest)
    }
    nineMasks.expire(now, idleTimeoutMs)
    if (builder != null && now - lastBuildAt >= idleTimeoutMs) closeBuilder()
    scheduleSweep()
  }

  /**
   * Memory pressure, on the owning thread: drops every idle entry and published mask and closes the builder (recreated
   * on demand).
   */
  fun trimMemory() {
    while (true) evict(idleOldest ?: break)
    nineMasks.dropAll()
    closeBuilder()
    scheduleSweep()
  }

  /** [trimMemory] on the owning thread, from any thread; nothing for a store without a looper. */
  fun requestTrim() {
    val h = handler ?: return
    if (looperQuit) return
    h.removeCallbacks(trimRunnable)
    if (!h.post(trimRunnable)) looperQuit = true
  }

  /** Test introspection: the live entries (held by at least one node). */
  fun entriesForTest(): List<ShadowEntry> = buildList { entries.forEachValue { if (it.refCount > 0) add(it) } }

  /** Test introspection: the idle entries, oldest release first. */
  fun idleEntriesForTest(): List<ShadowEntry> = buildList {
    var e = idleOldest
    while (e != null) {
      add(e)
      e = e.idleNext
    }
  }

  /** Test introspection: the bytes of the idle entries' bitmaps. */
  fun idleBytesForTest(): Long = idleBytes

  /** Test introspection: the published nine-patch masks, least recently used first. */
  fun nineMasksForTest(): List<NineMask> = nineMasks.publishedForTest()

  /** Test introspection: the number of keys in the published masks' map. */
  fun nineMaskMapSizeForTest(): Int = nineMasks.mapSizeForTest()

  /** Test introspection: the bytes of the published nine-patch masks' bitmaps. */
  fun nineMaskBytesForTest(): Long = nineMasks.bytes

  /** Test introspection: whether the builder (and its scratch) is held. */
  fun hasBuilderForTest(): Boolean = builder != null

  /** Test introspection: the builder, if held. */
  fun builderForTest(): ShadowBitmapBuilder? = builder

  /** Test introspection: whether a sweep is posted. */
  fun sweepPendingForTest(): Boolean = sweepPending

  /** Test introspection: runs the sweep now (with the store's clock), as the posted one does. */
  fun sweepForTest() = sweep()

  /** Test introspection: the map's size (live and idle entries). */
  fun mapSizeForTest(): Int = entries.size

  /** Test introspection: whether idle entries can be kept (a looper that has not quit). */
  fun keepsIdleForTest(): Boolean = handler != null && !looperQuit

  /**
   * Test override of the idle timeout (entries and builder), e.g. so that a slow device does not expire an entry
   * between two steps of a test; [ms] null restores the default. Reschedules a pending sweep.
   */
  fun setIdleTimeoutForTest(ms: Long?) {
    idleTimeoutMs = ms ?: IDLE_TIMEOUT_MS
    if (sweepPending) {
      handler?.removeCallbacks(sweepRunnable)
      sweepPending = false
    }
    scheduleSweep()
  }

  companion object {
    /**
     * Idle bitmaps kept at most (idle entries' and published nine-patch masks' together): a few screens of large
     * shadows, small next to a typical app heap.
     */
    private const val MAX_IDLE_BYTES = 10L * 1024 * 1024

    /** Idle entries and the unused builder are kept this long: enough for a scroll back, freed soon after. */
    private const val IDLE_TIMEOUT_MS = 3_000L

    private val perThread =
      object : ThreadLocal<ShadowStore>() {
        override fun initialValue() = ShadowStore()
      }

    /** The calling thread's store. */
    fun current(): ShadowStore = perThread.get()!!
  }
}

/**
 * Memory pressure for every [ShadowStore] with a looper: on `onTrimMemory(≥ TRIM_MEMORY_BACKGROUND)` (the level at
 * which Compose clears its own shadow cache) and on `onLowMemory`, each store drops its idle entries and its builder.
 * The callbacks arrive on the main thread and a store is not thread-safe, so each store gets the request as a message
 * on its own thread ([ShadowStore.requestTrim]). Registered once per process, on the application context (never an
 * Activity), by the first node that attaches ([install]); stores are held weakly (a thread's store goes with it).
 */
internal object ShadowMemoryCallbacks : ComponentCallbacks2 {
  private val registration = CallbacksRegistration(this)
  private val stores = ArrayList<WeakReference<ShadowStore>>()

  /** Registered already: a node's attach checks this before reading anything. */
  val isInstalled: Boolean
    get() = registration.done

  fun install(context: Context) = registration.install(context)

  fun register(store: ShadowStore) {
    synchronized(this) {
      stores.removeAll { it.get() == null }
      stores.add(WeakReference(store))
    }
  }

  @Suppress("DEPRECATION")
  override fun onTrimMemory(level: Int) {
    if (level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) trimAll()
  }

  @Deprecated("Deprecated in Java")
  override fun onLowMemory() = trimAll()

  override fun onConfigurationChanged(newConfig: Configuration) = Unit

  private fun trimAll() {
    val live = synchronized(this) { stores.mapNotNull { it.get() } }
    for (store in live) store.requestTrim()
  }
}

/** Registers [callbacks] on the application context of the first [install]'s context, once. */
internal class CallbacksRegistration(private val callbacks: ComponentCallbacks2) {
  @Volatile
  var done = false
    private set

  fun install(context: Context) {
    if (done) return
    val app = context.applicationContext ?: return
    synchronized(this) {
      if (done) return
      app.registerComponentCallbacks(callbacks)
      done = true
    }
  }
}
