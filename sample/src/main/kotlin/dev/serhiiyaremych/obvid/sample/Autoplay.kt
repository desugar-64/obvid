// Copyright 2026, Serhii Yaremych (desugar-64) and the Obvid project contributors
// SPDX-License-Identifier: Apache-2.0

package dev.serhiiyaremych.obvid.sample

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.Trace
import android.util.Log
import android.view.Choreographer
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.graphics.toAndroidRect
import androidx.compose.ui.node.RootForTest
import androidx.compose.ui.platform.AndroidUiDispatcher
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import dev.serhiiyaremych.obvid.sample.discover.DiscoverBookmarkTag
import dev.serhiiyaremych.obvid.sample.discover.DiscoverCardTag
import dev.serhiiyaremych.obvid.sample.discover.DiscoverChipTag
import dev.serhiiyaremych.obvid.sample.discover.DiscoverFeedTag
import dev.serhiiyaremych.obvid.sample.discover.DiscoverNavBarTag
import dev.serhiiyaremych.obvid.sample.discover.DiscoverPlayTag
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.ceil
import kotlin.math.roundToInt

/** Intent string extra: run this TierBenchmark scenario's input script inside the app ([Autoplay]), e.g. `t7_scroll`. */
const val AutoplayExtra = "autoplay"

/** Intent string extra: a token [Autoplay]'s log lines carry, so the host matches them to its own launch. */
const val AutoplayTokenExtra = "autoplayToken"

/** Intent Long extra: time from the back tap to the next forward tap of the nav scripts, ms (TierBenchmark's `navGapMs`). */
const val NavGapExtra = "navGapMs"

/** Broadcast that lets a ready [Autoplay] start its script (sent by the host once it has started tracing). */
const val AutoplayGoAction = "dev.serhiiyaremych.obvid.sample.AUTOPLAY_GO"

/** Async trace section around the script: the measured window. */
const val MeasureSection = "demo:measure"

/** Logcat tag of [Autoplay]'s `ready`, `done` and `failed` lines. */
private const val LogTag = "FsAutoplay"

/**
 * TierBenchmark's measure block run by the app itself, for a device where Macrobenchmark cannot run
 * (scripts/run_perfetto_tiers.sh records the trace). Same scenarios, constants, targets and schedule as TierBenchmark.
 *
 * Setup, as the benchmark's: wait for the scenario's targets (test tags in the unmerged semantics tree, first match in
 * depth-first order, bounds in the window, read once), then [SettleMs]; log `ready <token>`. On [AutoplayGoAction],
 * wait until app tracing is on ([Trace.isEnabled]) and [TraceLeadMs] more, then run the script inside the async
 * section [MeasureSection] and log `done <token>` (`failed <token>: <reason>` on any error).
 *
 * The script is a timeline fixed at its start (each step at the time the benchmark's own pace gives it), posted to the
 * main thread as asynchronous messages, so a traversal's sync barrier does not hold them back, as it does not hold
 * input. Input goes to the window's decor view:
 * - down, up and back run at their time, outside a frame, as delivered input does;
 * - a drag's moves are delivered once per frame, as one batched event of every sample due by the frame time (the input
 *   system's batching, without its resampling), by the first animation callback of the frame ([feeder]). Compose
 *   applies the drag in a coroutine on its UI dispatcher; [armJob] keeps that dispatcher's frame callback queued for
 *   the next frame, so the coroutine runs later in the same frame, before layout, as after input consumed in the
 *   frame's input phase.
 */
class Autoplay private constructor(
  private val activity: ComponentActivity,
  private val scenario: String,
  private val token: String,
  private val navGapMs: Long,
) : DefaultLifecycleObserver {
  private val main = Handler.createAsync(Looper.getMainLooper())
  private val choreographer = Choreographer.getInstance()
  private val uiScope = CoroutineScope(AndroidUiDispatcher.Main)
  private val targets = targetsOf(scenario)
  private var bounds: Map<String, Rect> = emptyMap()
  private var ready = false
  private var goReceived = false
  private var begun = false
  private var finished = false
  private var measureCookie = 0

  /** Timeline cursor while the script is built, uptime ms. */
  private var t = 0L

  private val receiver = object : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
      goReceived = true
      maybeBegin()
    }
  }

  /** Moves of the running drag not delivered yet, in time order. */
  private val pending = ArrayDeque<Sample>()
  private var dragDown = 0L
  private var dragX = 0f
  private var armJob: Job? = null

  private val feeder = object : Choreographer.FrameCallback {
    override fun doFrame(frameTimeNanos: Long) {
      deliver(frameTimeNanos / 1_000_000)
      if (pending.isNotEmpty()) postFeeder()
    }
  }

  private class Sample(val time: Long, val y: Float)

  private fun start() {
    ContextCompat.registerReceiver(activity, receiver, IntentFilter(AutoplayGoAction), ContextCompat.RECEIVER_EXPORTED)
    activity.lifecycle.addObserver(this)
    // Posted: start runs in onCreate, before the content is set.
    main.post { findTargets(SystemClock.uptimeMillis() + TargetTimeoutMs) }
  }

  override fun onDestroy(owner: LifecycleOwner) {
    if (!finished) fail("activity destroyed")
  }

  private fun findTargets(deadline: Long) {
    if (finished) return
    val found = findTags(targets)
    when {
      found.size == targets.size -> {
        bounds = found
        main.postDelayed({
          ready = true
          Log.i(LogTag, "ready $token")
          maybeBegin()
        }, SettleMs)
      }
      SystemClock.uptimeMillis() > deadline -> fail("targets not found: ${targets - found.keys}")
      else -> main.postDelayed({ findTargets(deadline) }, PollMs)
    }
  }

  private fun maybeBegin() {
    if (!ready || !goReceived || begun || finished) return
    begun = true
    awaitTracing(SystemClock.uptimeMillis() + TraceTimeoutMs)
  }

  private fun awaitTracing(deadline: Long) {
    if (finished) return
    when {
      Trace.isEnabled() -> main.postDelayed(::runScript, TraceLeadMs)
      SystemClock.uptimeMillis() > deadline -> fail("app tracing did not turn on")
      else -> main.postDelayed({ awaitTracing(deadline) }, TracePollMs)
    }
  }

  private fun runScript() {
    if (finished) return
    measureCookie = beginEvent(MeasureSection)
    t = SystemClock.uptimeMillis()
    script()
    at(t) {
      endEvent(MeasureSection, measureCookie)
      Log.i(LogTag, "done $token")
      stop()
    }
  }

  /** TierBenchmark's measure block, by scenario. */
  private fun script() {
    when (scenario) {
      in RevealScenarios -> {
        val target = bounds.getValue(TierTargetTag)
        tap(target.exactCenterX(), target.exactCenterY())
        sleep(RevealIdleMs)
      }
      in AnimateScenarios -> sleep(AnimateMs)
      "t5_list" -> scroll(bounds.getValue(TierListTag), ScrollCycles)
      "t6_nav" -> {
        val target = bounds.getValue(TierNavTag)
        val x = target.exactCenterX()
        val y = target.exactCenterY()
        tap(x, y)
        sleep(NavDwellMs)
        tap(x, y)
        sleep(navGapMs)
        tap(x, y)
        sleep(NavDwellMs)
      }
      "t7_scroll" -> {
        val feed = bounds.getValue(DiscoverFeedTag)
        val area = Rect(feed.left, feed.top, feed.right, minOf(feed.bottom, bounds.getValue(DiscoverNavBarTag).top))
        scroll(area, DiscoverScrollCycles)
      }
      "t7_interact" -> {
        val play = bounds.getValue(DiscoverPlayTag)
        val bookmark = bounds.getValue(DiscoverBookmarkTag)
        var next = t
        fun step(target: Rect) {
          t = maxOf(t, next)
          tap(target.exactCenterX(), target.exactCenterY())
          next += InteractStepMs
        }
        for (round in 1..InteractRounds) {
          step(bounds.getValue(DiscoverChipTag + round % DiscoverChips))
          step(play)
          step(bookmark)
        }
        t = maxOf(t, next)
      }
      "t7_nav" -> {
        // Left quarter of card 0: its photo, away from the bookmark and play buttons.
        val card = bounds.getValue(DiscoverCardTag)
        val x = card.left + card.width() / 4f
        val y = card.exactCenterY()
        tap(x, y)
        sleep(NavDwellMs)
        back()
        sleep(navGapMs)
        tap(x, y)
        sleep(NavDwellMs)
      }
    }
  }

  private fun sleep(ms: Long) {
    t += ms
  }

  /** Down and up at ([x], [y]), [TapMs] apart. */
  private fun tap(x: Float, y: Float) {
    val down = t
    at(down) { touch(down, down, MotionEvent.ACTION_DOWN, x, y) }
    at(down + TapMs) { touch(down, down + TapMs, MotionEvent.ACTION_UP, x, y) }
    t += TapMs
  }

  /** Back released [TapMs] after its press: the back dispatch a back key's release makes. */
  private fun back() {
    at(t + TapMs) { activity.onBackPressedDispatcher.onBackPressed() }
    t += TapMs
  }

  /** [cycles] times { 3 drags down, 2 drags up }, each [DragFraction] of [area]'s height around its center. */
  private fun scroll(area: Rect, cycles: Int) {
    val density = activity.resources.displayMetrics.density
    val x = area.exactCenterX()
    val distance = (area.height() * DragFraction).roundToInt()
    val low = area.centerY() + distance / 2f
    val high = low - distance
    val durationMs = distance / (DragSpeedDp * density) * 1000f
    repeat(cycles) {
      repeat(3) { drag(x, low, high, durationMs) }
      repeat(2) { drag(x, high, low, durationMs) }
    }
  }

  /**
   * Down at [fromY], a sample every [StepMs] at constant speed to [toY] over [durationMs], [HoldMs] of still samples,
   * up at the last sample; then [GapMs] idle. The lift velocity is zero, so nothing flings.
   */
  private fun drag(x: Float, fromY: Float, toY: Float, durationMs: Float) {
    val steps = ceil(durationMs / StepMs).toInt()
    val down = t
    var time = down
    val samples = ArrayList<Sample>()
    for (i in 1..steps) {
      time += StepMs
      samples += Sample(time, fromY + (toY - fromY) * i / steps)
    }
    repeat((HoldMs / StepMs).toInt()) {
      time += StepMs
      samples += Sample(time, toY)
    }
    val up = time
    at(down) {
      touch(down, down, MotionEvent.ACTION_DOWN, x, fromY)
      dragDown = down
      dragX = x
      pending.addAll(samples)
      postFeeder()
      armJob = uiScope.launch { while (isActive) withFrameNanos {} }
    }
    at(up) {
      deliver(up)
      touch(down, up, MotionEvent.ACTION_UP, x, toY)
      choreographer.removeFrameCallback(feeder)
      armJob?.cancel()
      armJob = null
    }
    t = up + GapMs
  }

  /**
   * Queues [feeder] ahead of every other animation callback of the next frame: a due time in the past sorts it first.
   */
  private fun postFeeder() {
    choreographer.postFrameCallbackDelayed(feeder, -FeederLeadMs)
  }

  /** Dispatches the pending moves due by [upTo] (uptime ms) as one batched move event. */
  private fun deliver(upTo: Long) {
    var event: MotionEvent? = null
    while (pending.isNotEmpty() && pending.first().time <= upTo) {
      val sample = pending.removeFirst()
      if (event == null) {
        event = motionEvent(dragDown, sample.time, MotionEvent.ACTION_MOVE, dragX, sample.y)
      } else {
        event.addBatch(sample.time, arrayOf(coords(dragX, sample.y)), 0)
      }
    }
    event?.let(::dispatch)
  }

  private fun touch(down: Long, time: Long, action: Int, x: Float, y: Float) = dispatch(motionEvent(down, time, action, x, y))

  private fun dispatch(event: MotionEvent) {
    try {
      activity.window.decorView.dispatchTouchEvent(event)
    } finally {
      event.recycle()
    }
  }

  private fun at(time: Long, action: () -> Unit) {
    main.postAtTime({ if (!finished) action() }, time)
  }

  private fun fail(reason: String) {
    if (finished) return
    Log.e(LogTag, "failed $token: $reason")
    stop()
  }

  private fun stop() {
    finished = true
    main.removeCallbacksAndMessages(null)
    choreographer.removeFrameCallback(feeder)
    uiScope.cancel()
    activity.unregisterReceiver(receiver)
    activity.lifecycle.removeObserver(this)
  }

  /** Window bounds of the first node carrying each of [tags] that has a nonempty size. */
  private fun findTags(tags: List<String>): Map<String, Rect> {
    val root = composeRoot(activity.window.decorView) ?: return emptyMap()
    val out = mutableMapOf<String, Rect>()
    fun visit(node: SemanticsNode) {
      val tag = node.config.getOrNull(SemanticsProperties.TestTag)
      if (tag != null && tag in tags && tag !in out) {
        val box = node.boundsInWindow
        if (box.width > 0f && box.height > 0f) out[tag] = box.toAndroidRect()
      }
      node.children.forEach(::visit)
    }
    visit(root.semanticsOwner.unmergedRootSemanticsNode)
    return out
  }

  private fun composeRoot(view: View): RootForTest? {
    if (view is RootForTest) return view
    if (view is ViewGroup) {
      for (i in 0 until view.childCount) composeRoot(view.getChildAt(i))?.let { return it }
    }
    return null
  }

  companion object {
    /** Starts [scenario]'s autoplay on [activity]; an unknown scenario fails at once (logged). */
    fun start(activity: ComponentActivity, scenario: String, token: String, navGapMs: Long) {
      if (scenario !in Scenarios) {
        Log.e(LogTag, "failed $token: unknown autoplay scenario '$scenario', expected one of $Scenarios")
        return
      }
      Autoplay(activity, scenario, token, navGapMs).start()
    }

    private val RevealScenarios = listOf("t0_single", "t1_static", "t7_reveal")
    private val AnimateScenarios = listOf("t2_menu", "t3_gears", "t4_field", "t8_glow_spin", "t8_glow_pulse")
    private val Scenarios = RevealScenarios + AnimateScenarios + listOf("t5_list", "t6_nav", "t7_scroll", "t7_interact", "t7_nav")

    /** Test tags whose bounds the script needs. */
    private fun targetsOf(scenario: String): List<String> =
      when (scenario) {
        in RevealScenarios -> listOf(TierTargetTag)
        "t5_list" -> listOf(TierListTag)
        "t6_nav" -> listOf(TierNavTag)
        "t7_scroll" -> listOf(DiscoverFeedTag, DiscoverNavBarTag)
        "t7_interact" -> List(DiscoverChips) { DiscoverChipTag + it } + listOf(DiscoverPlayTag, DiscoverBookmarkTag)
        "t7_nav" -> listOf(DiscoverCardTag)
        else -> emptyList()
      }

    /** Time the targets may take to appear, ms (the benchmark's wait per target). */
    private const val TargetTimeoutMs = 10_000L
    private const val PollMs = 50L

    /** Idle after the targets are found, ms (the benchmark's setup sleep). */
    private const val SettleMs = 1_000L

    /** Time app tracing may take to turn on after the go broadcast, ms. */
    private const val TraceTimeoutMs = 10_000L
    private const val TracePollMs = 10L

    /** Idle between app tracing turning on and the script, ms: the other data sources start around the same time. */
    private const val TraceLeadMs = 500L

    /** How far in the past [postFeeder] dates the feeder, ms. */
    private const val FeederLeadMs = 60_000L

    // TierBenchmark's and InjectedInput's constants.
    private const val RevealIdleMs = 1_000L
    private const val AnimateMs = 6_000L
    private const val ScrollCycles = 2
    private const val DragFraction = 0.75f
    private const val DragSpeedDp = 2_000f
    private const val NavDwellMs = 1_200L
    private const val DiscoverScrollCycles = 3
    private const val InteractRounds = 4
    private const val InteractStepMs = 500L
    private const val DiscoverChips = 4
    private const val StepMs = 8L
    private const val HoldMs = 160L
    private const val GapMs = 80L
    private const val TapMs = 48L

    /** TierBenchmark's default `navGapMs`. */
    const val DefaultNavGapMs = 1_500L
  }
}

private fun coords(x: Float, y: Float) = MotionEvent.PointerCoords().apply {
  this.x = x
  this.y = y
  pressure = 1f
  size = 1f
}

private fun motionEvent(down: Long, time: Long, action: Int, x: Float, y: Float): MotionEvent {
  val properties = MotionEvent.PointerProperties().apply {
    id = 0
    toolType = MotionEvent.TOOL_TYPE_FINGER
  }
  return MotionEvent.obtain(
    down, time, action, 1, arrayOf(properties), arrayOf(coords(x, y)), 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0,
  )
}
