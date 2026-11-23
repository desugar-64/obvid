// Copyright 2026, Serhii Yaremych (desugar-64) and the Obvid project contributors
// SPDX-License-Identifier: Apache-2.0

package dev.serhiiyaremych.obvid.benchmark

import android.graphics.RecordingCanvas
import android.graphics.RenderNode
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import kotlin.math.ceil
import kotlin.math.floor

/**
 * Records a shadow draw as a frame would: into a [RenderNode] the size of the box plus [pad] on every side, translated
 * by [pad], through one reused [CanvasDrawScope]. `beginRecording` hands back a pooled canvas; it is wrapped once per
 * instance, so recording allocates nothing itself. The draw scope's size is [drawSize] (the box by default; a size walk
 * sets smaller ones, which the node still holds).
 */
internal class ShadowRecorder(
  @PublishedApi internal val density: Density,
  @PublishedApi internal val size: Size,
  private val pad: Float,
) {
  private val node = RenderNode("shadow").apply {
    val side = ceil(size.width + 2 * pad).toInt()
    setPosition(0, 0, side, side)
  }
  @PublishedApi internal val drawScope = CanvasDrawScope()

  /** The size [record] draws at: at most the constructor's size, which the node is sized for. */
  @PublishedApi internal var drawSize: Size = size
  private var recording: RecordingCanvas? = null
  private var canvas: Canvas? = null

  /** One recording: [draw] at the box's size. Inline (as `CanvasDrawScope.draw` is), so [draw] is not allocated. */
  inline fun record(draw: DrawScope.() -> Unit) {
    drawScope.draw(density, LayoutDirection.Ltr, begin(), drawSize, draw)
    end()
  }

  @PublishedApi
  internal fun begin(): Canvas {
    val target = node.beginRecording()
    if (target !== recording) {
      recording = target
      canvas = Canvas(target)
    }
    target.translate(pad, pad)
    return canvas!!
  }

  @PublishedApi
  internal fun end() {
    node.endRecording()
  }
}

/**
 * The benchmarks' geometries per shape, (size dp, radius dp, spread dp): sizes 48 / 100 / 200 / 360 dp at r24, radii
 * 4 / 8 / 64 dp at 200 dp, and 200 dp r24 with spread 8 dp.
 */
internal val BenchGeometries: List<Triple<Int, Int, Int>> =
  listOf(48, 100, 200, 360).map { Triple(it, 24, 0) } + listOf(4, 8, 64).map { Triple(200, it, 0) } + Triple(200, 24, 8)

/**
 * Square layout sizes for the `size` mode, a size animation: whole pixels from `floor(basePx)` down by one pixel per
 * iteration over [SizeSteps] values, then again. Every iteration changes the size (a rebuild); the corner radius, the
 * radius and the spread stay, so a round rect keeps its nine-patch's small round rect.
 */
internal class SizeWalk(basePx: Float) {
  private val base = floor(basePx)

  operator fun get(iteration: Int): Size {
    val side = base - iteration % SizeSteps
    return Size(side, side)
  }

  private companion object {
    const val SizeSteps = 64
  }
}
