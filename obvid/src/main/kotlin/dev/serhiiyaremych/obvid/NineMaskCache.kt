// Copyright 2026, Serhii Yaremych (desugar-64) and the Obvid project contributors
// SPDX-License-Identifier: Apache-2.0

package dev.serhiiyaremych.obvid

import android.graphics.Bitmap
import androidx.collection.MutableScatterMap
import dev.serhiiyaremych.obvid.builder.ShadowBitmapBuilder
import dev.serhiiyaremych.obvid.builder.ShadowBitmaps
import dev.serhiiyaremych.obvid.builder.a8Bytes
import dev.serhiiyaremych.obvid.builder.newA8

/**
 * The key of a blurred small round rect of the nine-patch route: the plan (`ObvidNative.builderPlanRoundRect`'s
 * value, which holds the small rect's width and height), the corner radii the small round rect is drawn with and the
 * blur radius (its sigma, and so the border and the size of the blurred mask, follow from it). Every input of the
 * blurred bytes is here; the round rect's position and size, the spread, the inner shadow's offset and the drop
 * shadow's extent (which only moves the device rect, and so the plan is computed for the moved rect) are not, so drop
 * and inner shadows of any size and either extent share it. The round-rect class is not in the key: the builder always passes
 * `ObvidNative.SKIA_CLASS_B`. Compared by the floats' bits.
 */
internal class NineMaskKey(
  var plan: Long = ObvidNative.PLAN_SKIA,
  var rx: Float = 0f,
  var ry: Float = 0f,
  var radius: Float = 0f,
) {
  fun set(plan: Long, rx: Float, ry: Float, radius: Float): NineMaskKey {
    this.plan = plan
    this.rx = rx
    this.ry = ry
    this.radius = radius
    return this
  }

  fun matches(plan: Long, rx: Float, ry: Float, radius: Float): Boolean =
    this.plan == plan && this.rx.toRawBits() == rx.toRawBits() && this.ry.toRawBits() == ry.toRawBits() &&
      this.radius.toRawBits() == radius.toRawBits()

  override fun equals(other: Any?): Boolean =
    other is NineMaskKey && matches(other.plan, other.rx, other.ry, other.radius)

  override fun hashCode(): Int {
    var h = plan.hashCode()
    h = 31 * h + rx.toRawBits()
    h = 31 * h + ry.toRawBits()
    return 31 * h + radius.toRawBits()
  }

  override fun toString() = "NineMaskKey(plan=$plan, rx=$rx, ry=$ry, radius=$radius)"
}

/**
 * The order of the releases and uses that make things idle in one store (idle entries, published masks): the byte cap
 * evicts the lowest first, so two at the same clock time are still ordered by when they happened.
 */
internal class IdleSequence {
  private var last = 0L

  fun next(): Long = ++last
}

/**
 * A published blurred small round rect: the top-left `(ceil(w) + 2·border) × (ceil(h) + 2·border)` window of
 * [bitmap] (`ObvidNative.builderNineKeep`; read with the bitmap's own stride) holds the bytes of [key]. Written
 * once, before it is published, and never again; held by nobody (a build only reads it while it places it). HWUI
 * never draws it, so it is recycled when dropped.
 */
internal class NineMask(val bitmap: Bitmap) {
  val key = NineMaskKey()

  /** Its last use (publish or placement), the cache's clock. */
  var lastUse = 0L

  /** Its last use in the store's [IdleSequence]. */
  var order = 0L

  val bytes: Long = bitmap.allocationByteCount.toLong()

  /**
   * The rect of the mask whose image is an inner nine-patch's hole at any placement (`ObvidNative.builderNineMaskHole`,
   * packed), found by the first inner build that places it; [ObvidNative.MASK_HOLE_UNKNOWN] until then. The mask's
   * bytes never change, so neither does it.
   */
  var maskHole: Long = ObvidNative.MASK_HOLE_UNKNOWN

  var prev: NineMask? = null
  var next: NineMask? = null
}

/**
 * The published blurred small round rects of the nine-patch route, a cache owned by one [ShadowStore] (or by one
 * standalone [ShadowBitmapBuilder]), used by every build of its builders: a build whose [NineMaskKey] is published
 * places that mask without drawing or blurring anything, as Skia's nine-patch cache does for Compose. Entries never
 * hold a published mask, so every published mask counts as idle: in last-use order ([oldest] first) for its store's
 * expiry and byte cap (shared with the idle entries), and dropped on memory pressure or when nothing can stay idle.
 * A cache whose [keepsIdle] is false (no looper, or a standalone builder) publishes nothing ([canPublish]).
 * Not thread-safe (used on its store's thread).
 */
internal class NineMaskCache(
  private val clock: ShadowClock = ShadowClock { 0L },
  private val maxIdleBytes: Long = Long.MAX_VALUE,
  private val sequence: IdleSequence = IdleSequence(),
  private val keepsIdle: () -> Boolean = { false },
) {
  private val masks = MutableScatterMap<NineMaskKey, NineMask>()
  private val lookup = NineMaskKey()

  /** The least recently used published mask. */
  var oldest: NineMask? = null
    private set

  private var newest: NineMask? = null

  /** The bytes of the published masks' bitmaps. */
  var bytes = 0L
    private set

  /**
   * Debug counter: nine-patch builds that placed a kept small round rect (a target's private one or a published one).
   */
  var reuseCount: Long = 0
    private set

  /** Debug counter: the [reuseCount] builds that placed a published one. */
  var sharedHitCount: Long = 0
    private set

  /** Debug counter: nine-patch builds that drew and blurred their small round rect. */
  var blurCount: Long = 0
    private set

  /** Debug counter: the [blurCount] builds that published their mask. */
  var publishCount: Long = 0
    private set

  /** Debug counter: small round rect buffers allocated and used (published masks, targets' private buffers grown). */
  var allocationCount: Long = 0
    private set

  /** The published mask of this key, or null. */
  fun find(plan: Long, rx: Float, ry: Float, radius: Float): NineMask? = masks[lookup.set(plan, rx, ry, radius)]

  /** [mask] is used now: it becomes the most recently used. */
  fun touch(mask: NineMask) {
    unlink(mask)
    link(mask)
  }

  /** Whether a mask of [w] × [h] would be published: this cache keeps masks and it fits the idle budget alone. */
  fun canPublish(w: Int, h: Int): Boolean = keepsIdle() && a8Bytes(w, h) <= maxIdleBytes

  /**
   * A new, unpublished mask of at least [w] × [h], for a build to blur into and then [publish] (counted there: a mask
   * whose blur fails is recycled uncounted).
   */
  fun allocate(w: Int, h: Int): NineMask = NineMask(newA8(w, h))

  /** [mask] (from [allocate]) now holds the blurred small round rect of this key: published, the most recently used. */
  fun publish(mask: NineMask, plan: Long, rx: Float, ry: Float, radius: Float) {
    mask.key.set(plan, rx, ry, radius)
    // The build that blurred it looked the key up first, and nothing publishes in between.
    check(masks.put(mask.key, mask) == null) { "nine-patch mask key published twice: ${mask.key}" }
    publishCount++
    allocationCount++
    link(mask)
  }

  fun countReuse(holder: ShadowBitmaps, shared: Boolean) {
    reuseCount++
    if (shared) sharedHitCount++
    holder.countNineReuse(shared)
  }

  fun countBlur(holder: ShadowBitmaps) {
    blurCount++
    holder.countNineBlur()
  }

  /** Counts a target's private buffer allocated. */
  fun countAllocation() {
    allocationCount++
  }

  /** Drops [mask] (eviction, expiry, memory pressure) and recycles its bitmap. */
  fun evict(mask: NineMask) {
    masks.remove(mask.key, mask)
    unlink(mask)
    mask.bitmap.recycle()
  }

  /** Drops every published mask. */
  fun dropAll() {
    while (true) evict(oldest ?: break)
  }

  /** Drops the masks last used at least [timeoutMs] before [now]. */
  fun expire(now: Long, timeoutMs: Long) {
    while (true) {
      val m = oldest ?: break
      if (now - m.lastUse < timeoutMs) break
      evict(m)
    }
  }

  private fun link(mask: NineMask) {
    mask.lastUse = clock.uptimeMillis()
    mask.order = sequence.next()
    mask.prev = newest
    mask.next = null
    val n = newest
    if (n != null) n.next = mask else oldest = mask
    newest = mask
    bytes += mask.bytes
  }

  private fun unlink(mask: NineMask) {
    val p = mask.prev
    val n = mask.next
    if (p != null) p.next = n else oldest = n
    if (n != null) n.prev = p else newest = p
    mask.prev = null
    mask.next = null
    bytes -= mask.bytes
  }

  /**
   * Takes what a job's own cache [from] published (its worker's build of a first appearance, [ShadowPrefetch]): each
   * mask whose key this cache does not hold yet is published here, as that build would have published it in this
   * cache; a mask whose key is already here is recycled (no recorded frame draws a nine-patch mask). Its build counters
   * are added to these ([publishCount] counts the masks published here). [from] is empty afterwards.
   */
  fun adoptFrom(from: NineMaskCache) {
    while (true) {
      val mask = from.oldest ?: break
      from.masks.remove(mask.key, mask)
      from.unlink(mask)
      if (masks[mask.key] == null && canPublish(mask.bitmap.width, mask.bitmap.height)) {
        masks[mask.key] = mask
        link(mask)
        publishCount++
      } else {
        mask.bitmap.recycle()
      }
    }
    reuseCount += from.reuseCount
    sharedHitCount += from.sharedHitCount
    blurCount += from.blurCount
    allocationCount += from.allocationCount
  }

  /** Test introspection: the published masks, least recently used first. */
  fun publishedForTest(): List<NineMask> = buildList {
    var m = oldest
    while (m != null) {
      add(m)
      m = m.next
    }
  }

  /** Test introspection: the number of keys in the map. */
  fun mapSizeForTest(): Int = masks.size
}
