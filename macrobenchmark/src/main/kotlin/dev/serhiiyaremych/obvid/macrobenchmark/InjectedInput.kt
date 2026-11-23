// Copyright 2026, Serhii Yaremych (desugar-64) and the Obvid project contributors
// SPDX-License-Identifier: Apache-2.0

package dev.serhiiyaremych.obvid.macrobenchmark

import android.app.UiAutomation
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import kotlin.math.ceil

/**
 * Touch input injected through `UiAutomation.injectInputEvent` asynchronously on a fixed schedule (event time =
 * scheduled time), not with `UiDevice.swipe` or `click`, which wait for the app to handle each step: their pace would
 * depend on the implementation being measured. Only a test thread that misses its schedule changes the pace.
 */

/** Interval between move events, ms (a 125 Hz touch report rate). */
internal const val StepMs = 8L

/** Still time before lifting the finger after a drag, ms: longer than the 100 ms velocity-tracker window. */
internal const val HoldMs = 160L

/** Idle time after a drag, ms. */
internal const val GapMs = 80L

/** Time between down and up of a tap, ms. */
private const val TapMs = 48L

/**
 * Down at [fromY], constant-speed moves to [toY] over [durationMs], [HoldMs] still, up; then [GapMs] idle. The lift
 * velocity is zero, so nothing flings: the content moves exactly with the finger.
 */
internal fun UiAutomation.drag(x: Float, fromY: Float, toY: Float, durationMs: Float) {
  val steps = ceil(durationMs / StepMs).toInt()
  val down = SystemClock.uptimeMillis()
  var time = down
  inject(down, time, MotionEvent.ACTION_DOWN, x, fromY)
  for (i in 1..steps) {
    time += StepMs
    waitUntil(time)
    inject(down, time, MotionEvent.ACTION_MOVE, x, fromY + (toY - fromY) * i / steps)
  }
  repeat((HoldMs / StepMs).toInt()) {
    time += StepMs
    waitUntil(time)
    inject(down, time, MotionEvent.ACTION_MOVE, x, toY)
  }
  inject(down, time, MotionEvent.ACTION_UP, x, toY)
  waitUntil(time + GapMs)
}

/** Down and up at ([x], [y]), [TapMs] apart: a click without movement. */
internal fun UiAutomation.tap(x: Float, y: Float) {
  val down = SystemClock.uptimeMillis()
  inject(down, down, MotionEvent.ACTION_DOWN, x, y)
  waitUntil(down + TapMs)
  inject(down, down + TapMs, MotionEvent.ACTION_UP, x, y)
}

/** Back key down and up, [TapMs] apart. */
internal fun UiAutomation.back() {
  val down = SystemClock.uptimeMillis()
  injectKey(down, down, KeyEvent.ACTION_DOWN)
  waitUntil(down + TapMs)
  injectKey(down, down + TapMs, KeyEvent.ACTION_UP)
}

private fun UiAutomation.injectKey(down: Long, time: Long, action: Int) {
  val event = KeyEvent(
    down, time, action, KeyEvent.KEYCODE_BACK, 0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD,
  )
  check(injectInputEvent(event, false)) { "injecting $event failed" }
}

private fun UiAutomation.inject(down: Long, time: Long, action: Int, x: Float, y: Float) {
  val properties = MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER }
  val coords = MotionEvent.PointerCoords().apply { this.x = x; this.y = y; pressure = 1f; size = 1f }
  val event = MotionEvent.obtain(
    down, time, action, 1, arrayOf(properties), arrayOf(coords), 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0,
  )
  try {
    check(injectInputEvent(event, false)) { "injecting $event failed" }
  } finally {
    event.recycle()
  }
}

internal fun waitUntil(uptimeMs: Long) {
  val left = uptimeMs - SystemClock.uptimeMillis()
  if (left > 0) SystemClock.sleep(left)
}
