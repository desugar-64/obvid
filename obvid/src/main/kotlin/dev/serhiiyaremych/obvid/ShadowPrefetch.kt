// Copyright 2026, Serhii Yaremych (desugar-64) and the Obvid project contributors
// SPDX-License-Identifier: Apache-2.0

package dev.serhiiyaremych.obvid

import android.os.Process
import androidx.compose.ui.graphics.Outline
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The first build of a shadow ([ShadowBuilds.Parallel]), queued when its node is measured and taken by its draw
 * ([ShadowStore.adopt]).
 *
 * [key] (a copy owned by the job) and [outline] (with its own path) are made on the UI thread, which also calls
 * `createOutline`; a worker ([ShadowWorkers]) builds [entry] with its own builder into the job's own nine-patch cache
 * [masks]. Neither is in a store until a draw adopts the job, so a store and its published masks stay on the UI
 * thread. Exactly one side runs the build: a worker that takes the job first builds it; a draw that comes first
 * ([claimForDraw]) builds inline through [ShadowStore.obtain] instead, and the worker then skips it.
 *
 * Shared by the nodes of one store that measured the same key before any of them drew it ([holders]); only the first
 * of them takes the result ([taken]).
 */
internal class ShadowPrefetch(
  val key: ShadowGeometry,
  private val outline: Outline,
  private val maxIdleBytes: Long,
  private val keepsMasks: Boolean,
) : Runnable {
  /**
   * The built entry, made by the worker (not during layout); taken once ([takeEntry]) after [awaitWorker] returned
   * null. Its own key: once adopted, the store may re-key the entry in place, and [key] must stay the job's.
   */
  private var entry: ShadowEntry? = null

  /**
   * The nine-patch mask the build published, as a first build publishes in its store (by the store's rules: whether
   * the store keeps masks, its byte cap), until the store takes it ([NineMaskCache.adoptFrom]). Made and read as
   * [entry].
   */
  lateinit var masks: NineMaskCache
    private set

  /** Nodes that hold this job (UI thread). The last one to let go cancels it. */
  var holders = 1

  /** A draw adopted [entry] (UI thread): the other holders obtain theirs from the store. */
  var taken = false

  private val owner = AtomicInteger(FREE)
  private val done = CountDownLatch(1)

  @Volatile private var failure: Throwable? = null

  /**
   * For a draw: true when the build is not a worker's (no worker took it, or it was cancelled), so the draw builds
   * inline; false when a worker runs or ran it ([awaitWorker]).
   */
  fun claimForDraw(): Boolean = owner.compareAndSet(FREE, DRAW) || owner.get() == DRAW

  /**
   * The built entry, for the draw that adopts it (UI thread). The job lets go of it: the other holders may keep the
   * job until they draw or detach, and the store alone decides how long the bitmap stays.
   */
  fun takeEntry(): ShadowEntry = checkNotNull(entry) { "no built entry" }.also { entry = null }

  /** A job no holder wants any more: a worker that has not taken it skips it; a running build finishes unused. */
  fun cancel() {
    owner.compareAndSet(FREE, DRAW)
  }

  /** The worker's run; a job a draw claimed first does nothing. */
  override fun run() {
    if (owner.compareAndSet(FREE, WORKER)) {
      try {
        traceSection(TRACE_PREFETCH) {
          val built = ShadowEntry(key.copy())
          val published = NineMaskCache(maxIdleBytes = maxIdleBytes, keepsIdle = { keepsMasks })
          built.build(ShadowWorkers.builder(published), outline, mayPublish = true)
          entry = built
          masks = published
        }
      } catch (t: Throwable) {
        failure = t
      } finally {
        done.countDown()
      }
    }
  }

  /**
   * For a draw whose job a worker took ([claimForDraw] false): waits until the build ends; what it threw, or null
   * ([entry] and [masks] are then set).
   */
  fun awaitWorker(): Throwable? {
    if (done.count > 0) traceSection(TRACE_PREFETCH_WAIT) { done.await() }
    return failure
  }

  private companion object {
    const val FREE = 0
    const val WORKER = 1
    const val DRAW = 2
  }
}

/**
 * The worker threads of [ShadowPrefetch]: one per big core but one (the UI thread needs one), at most [MAX_THREADS],
 * each bound to its own big core. A thread stops after [KEEP_ALIVE_MS] without work.
 *
 * The UI thread starts at most the first thread ([submit]); that thread reads the cores' layout, and a worker that
 * takes a job while more are queued starts the next one. Each thread has its own [ShadowBitmapBuilder], closed when the
 * thread stops.
 */
internal object ShadowWorkers {
  private const val KEEP_ALIVE_MS = 3_000L
  private const val MAX_THREADS = 3

  private val tasks = LinkedBlockingQueue<Runnable>()
  private val alive = AtomicInteger()
  private val named = AtomicInteger()

  /** Bit n: core slot n is a live worker's ([Cores.big] index). */
  private val slots = AtomicInteger()

  private val builders = ThreadLocal<ShadowBitmapBuilder?>()

  /** Read by the first worker; until then at most one thread runs. */
  @Volatile private var cores: Cores? = null

  /**
   * Queues [job]; false when no worker could be started for it (nothing is queued then). A job that cannot be taken
   * back from the queue is a worker's already: a worker that was stopping started its replacement, which took it.
   */
  fun submit(job: ShadowPrefetch): Boolean {
    tasks.offer(job)
    return alive.get() > 0 || startThread() || !tasks.remove(job)
  }

  /** The calling worker's builder, set to publish into [masks]. */
  fun builder(masks: NineMaskCache): ShadowBitmapBuilder {
    val builder = builders.get() ?: ShadowBitmapBuilder().also { builders.set(it) }
    builder.nineMasks = masks
    return builder
  }

  private fun startThread(): Boolean {
    val cap = cores?.threads ?: 1
    var reserved = false
    while (!reserved) {
      val n = alive.get()
      if (n >= cap) return false
      reserved = alive.compareAndSet(n, n + 1)
    }
    val started =
      runCatching { Thread(::work, "Obvid build ${named.incrementAndGet()}").apply { isDaemon = true }.start() }
    if (started.isFailure) alive.decrementAndGet()
    return started.isSuccess
  }

  private fun work() {
    val slot = claimSlot()
    try {
      runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_DISPLAY) }
      val layout = cores ?: Cores.read().also { cores = it }
      layout.pin(slot)
      while (true) {
        val task = tasks.poll(KEEP_ALIVE_MS, TimeUnit.MILLISECONDS) ?: break
        if (tasks.isNotEmpty()) startThread()
        task.run()
      }
    } finally {
      runCatching { builders.get()?.close() }
      builders.remove()
      // The slot first: a replacement started after the decrement takes the lowest free slot, this one.
      slots.getAndUpdate { it and (1 shl slot).inv() }
      alive.decrementAndGet()
      // A job queued between the last poll and the decrement saw a live thread and started none.
      if (tasks.isNotEmpty()) startThread()
    }
  }

  /** The lowest core slot no live worker holds. */
  private fun claimSlot(): Int {
    var slot: Int
    do {
      val taken = slots.get()
      slot = Integer.numberOfTrailingZeros(taken.inv())
    } while (!slots.compareAndSet(taken, taken or (1 shl slot)))
    return slot
  }

  /** The big cores ([big], ascending) and the number of workers they allow. */
  private class Cores(val big: IntArray, cpus: Int) {
    val threads: Int = ((if (big.isEmpty()) cpus / 2 else big.size) - 1).coerceIn(1, MAX_THREADS)
    private val mask: Long = big.fold(0L) { m, c -> m or (1L shl c) }

    /**
     * Binds the calling worker to big core [slot]: new threads have no load history, and the energy-aware scheduler
     * packs them onto one core or onto the little cores. A core the vendor's core control isolates at low load
     * refuses the binding; then the worker may run on any big core.
     */
    fun pin(slot: Int) {
      val core = big.getOrNull(slot)
      val pinned = core != null && ObvidNative.pinCurrentThread(1L shl core) == 0
      if (!pinned && mask != 0L) ObvidNative.pinCurrentThread(mask)
    }

    companion object {
      private const val SYSFS = "/sys/devices/system/cpu"

      /**
       * The big cores by the scheduler's capacity (above 1.5 × the lowest: middle and big cores, not little ones);
       * every core when none stands out (no little cores), none when a core's capacity cannot be read (kernels before
       * energy-aware scheduling). The clock is no substitute: a little core can have a higher clock than a middle one.
       */
      fun read(): Cores {
        val cpus = possibleCpus()
        val capacity = cpus.map { sysfsLong("$SYSFS/cpu$it/cpu_capacity") }
        val big =
          if (capacity.any { it == null }) IntArray(0)
          else {
            val limit = capacity.minOf { it!! } * 3 / 2
            cpus.filterIndexed { i, _ -> capacity[i]!! > limit }.ifEmpty { cpus }.toIntArray()
          }
        return Cores(big, cpus.size)
      }

      /**
       * `possible` ("0-7", "0-3,4-7"); online CPUs only ([Runtime.availableProcessors]) if it cannot be read. CPUs 0–63
       * only: a pin mask has one bit per CPU in a `Long`.
       */
      private fun possibleCpus(): List<Int> {
        val text = runCatching { File("$SYSFS/possible").readText().trim() }.getOrNull()
        val ranges =
          text?.split(',')?.mapNotNull { part ->
            val bounds = part.split('-').map { it.toIntOrNull() }
            when {
              bounds.size == 1 && bounds[0] != null -> bounds[0]!!..bounds[0]!!
              bounds.size == 2 && bounds[0] != null && bounds[1] != null -> bounds[0]!!..bounds[1]!!
              else -> null
            }
          }
        return ranges?.flatten()?.takeIf { it.isNotEmpty() && it.max() < 64 }
          ?: (0 until Runtime.getRuntime().availableProcessors().coerceAtMost(64)).toList()
      }

      private fun sysfsLong(path: String): Long? = runCatching { File(path).readText().trim().toLong() }.getOrNull()
    }
  }
}
