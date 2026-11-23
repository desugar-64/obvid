// Copyright 2026, Serhii Yaremych (desugar-64) and the Obvid project contributors
// SPDX-License-Identifier: Apache-2.0

package dev.serhiiyaremych.obvid.sample.app

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.DecayAnimationSpec
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateDecay
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.unit.round
import androidx.compose.ui.unit.toOffset
import dev.serhiiyaremych.obvid.sample.FieldCamera
import dev.serhiiyaremych.obvid.sample.FigureEightCamera
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * The Shapes tab's camera: the scripted [FigureEightCamera] while [autoPan] is on, else a position the user drags and
 * flings ([panCamera]). Turning auto-pan on glides from where the camera is onto the scripted path; a drag takes the
 * camera from wherever it is shown and turns auto-pan off. Its state is read in the field's layout phase only, so
 * panning relayouts the field and never recomposes it.
 */
@Stable
class PannableCamera(autoPan: Boolean = true, pan: Offset = Offset.Zero) : FieldCamera {
  var autoPan by mutableStateOf(autoPan)
    private set

  /** The user's position, field px, within 0..[free]. */
  private var pan by mutableStateOf(pan)

  /** 0: at [pan]; 1: on the scripted path. */
  private var blend by mutableFloatStateOf(if (autoPan) 1f else 0f)

  // Written by the layout pass, read by the gesture and toggle handlers: not state.
  private var free = IntSize.Zero
  private var shown = pan.round()

  /** The running glide or fling. */
  private var motion: Job? = null

  override fun position(seconds: Float, free: IntSize): IntOffset {
    this.free = free
    val b = blend
    val user = clamp(pan).round()
    shown = if (b <= 0f) user else lerp(user, FigureEightCamera.position(seconds, free), b)
    return shown
  }

  /** A drag begins: stop any glide or fling, and hold the camera where it is shown. */
  fun startDrag() {
    holdShown()
    autoPan = false
  }

  /** The finger moved by [delta] px: the field follows it. */
  fun dragBy(delta: Offset) {
    pan = clamp(pan - delta)
  }

  /** The finger lifted at [velocity] px/s: the camera glides on with [decay], stopping at the field's edges. */
  fun fling(scope: CoroutineScope, velocity: Offset, decay: DecayAnimationSpec<Offset>) {
    motion?.cancel()
    motion = scope.launch {
      AnimationState(Offset.VectorConverter, pan, -velocity).animateDecay(decay) {
        val clamped = clamp(value)
        pan = clamped
        val stoppedX = clamped.x != value.x || velocity.x == 0f
        val stoppedY = clamped.y != value.y || velocity.y == 0f
        if (stoppedX && stoppedY) cancelAnimation()
      }
    }
  }

  /** Turns auto-pan on, gliding onto the scripted path with [glide], or off, holding the camera where it is. */
  fun setAutoPan(on: Boolean, scope: CoroutineScope, glide: AnimationSpec<Float>) {
    holdShown()
    autoPan = on
    if (on) motion = scope.launch { animate(0f, 1f, animationSpec = glide) { value, _ -> blend = value } }
  }

  private fun holdShown() {
    motion?.cancel()
    pan = shown.toOffset()
    blend = 0f
  }

  private fun clamp(offset: Offset) =
    Offset(offset.x.coerceIn(0f, free.width.toFloat()), offset.y.coerceIn(0f, free.height.toFloat()))

  companion object {
    /** Saves auto-pan and where the camera is shown. */
    val Saver: Saver<PannableCamera, Any> = listSaver(
      save = { listOf(it.autoPan, it.shown.x, it.shown.y) },
      restore = { PannableCamera(it[0] as Boolean, Offset((it[1] as Int).toFloat(), (it[2] as Int).toFloat())) },
    )
  }
}

/** Two-axis drag and fling of [camera], the fling decaying by [decay] in [scope]. */
fun Modifier.panCamera(camera: PannableCamera, scope: CoroutineScope, decay: DecayAnimationSpec<Offset>): Modifier =
  pointerInput(camera, scope, decay) {
    val tracker = VelocityTracker()
    detectDragGestures(
      onDragStart = {
        tracker.resetTracking()
        camera.startDrag()
      },
      onDragEnd = {
        val velocity = tracker.calculateVelocity()
        camera.fling(scope, Offset(velocity.x, velocity.y), decay)
      },
      onDragCancel = { tracker.resetTracking() },
    ) { change, delta ->
      tracker.addPointerInputChange(change)
      camera.dragBy(delta)
    }
  }
