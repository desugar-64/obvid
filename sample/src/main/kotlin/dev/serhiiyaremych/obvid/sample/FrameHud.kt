// Copyright 2026, Serhii Yaremych (desugar-64) and the Obvid project contributors
// SPDX-License-Identifier: Apache-2.0

package dev.serhiiyaremych.obvid.sample

import android.os.Handler
import android.os.Looper
import android.view.FrameMetrics
import android.view.Window
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.util.Locale

/** How often the HUD text is refreshed, ms. */
private const val PublishMs = 250L

/**
 * Live frame readout for demos (never in benchmarks), from the window's [FrameMetrics] over the last second: frames
 * drawn, the median and 90th percentile of each frame's CPU work (UI thread and RenderThread, input to swap), and the
 * janky frames, those that finished after their deadline. Listening starts with this composable and stops when it
 * leaves, so a hidden HUD costs nothing. The text updates [PublishMs] apart, in its own graphics layer; on an
 * otherwise idle screen the frames counted are the HUD's own updates.
 */
@Composable
fun FrameHud(modifier: Modifier = Modifier) {
  val window = LocalActivity.current?.window
  val stats = remember { FrameStats() }
  var text by remember { mutableStateOf("…") }
  if (window != null) {
    DisposableEffect(window) {
      val listener = Window.OnFrameMetricsAvailableListener { _, metrics, _ -> stats.add(metrics) }
      window.addOnFrameMetricsAvailableListener(listener, Handler(Looper.getMainLooper()))
      onDispose { window.removeOnFrameMetricsAvailableListener(listener) }
    }
    LaunchedEffect(stats) {
      while (true) {
        text = stats.summary(System.nanoTime())
        delay(PublishMs)
      }
    }
  }
  Text(
    text,
    modifier
      .graphicsLayer()
      .background(Color(0xCC0B1F2A), RoundedCornerShape(12.dp))
      .padding(horizontal = 12.dp, vertical = 8.dp),
    style = TextStyle(color = Color.White, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium, fontSize = 12.sp),
  )
}

/** A ring of the latest frames' vsync time, CPU work and jank, filled and read on the main thread. */
private class FrameStats {
  private val vsync = LongArray(Capacity)
  private val work = LongArray(Capacity)
  private val janky = BooleanArray(Capacity)
  private var count = 0

  fun add(metrics: FrameMetrics) {
    if (metrics.getMetric(FrameMetrics.FIRST_DRAW_FRAME) == 1L) return
    val i = count % Capacity
    vsync[i] = metrics.getMetric(FrameMetrics.INTENDED_VSYNC_TIMESTAMP)
    work[i] = WorkMetrics.sumOf { metrics.getMetric(it) }
    janky[i] = metrics.getMetric(FrameMetrics.TOTAL_DURATION) > metrics.getMetric(FrameMetrics.DEADLINE)
    count++
  }

  /** The frames whose vsync falls in the second before [now] ([System.nanoTime], the vsync timestamps' clock). */
  fun summary(now: Long): String {
    val times = ArrayList<Float>(128)
    var jank = 0
    var i = count - 1
    while (i >= 0 && count - i <= Capacity && now - vsync[i % Capacity] <= 1_000_000_000L) {
      times += work[i % Capacity] / 1e6f
      if (janky[i % Capacity]) jank++
      i--
    }
    if (times.isEmpty()) return "idle"
    times.sort()
    fun pct(p: Float) = times[((times.size - 1) * p).toInt()]
    return String.format(Locale.US, "%3d fps  work %4.1f / %4.1f ms  janky %d", times.size, pct(0.5f), pct(0.9f), jank)
  }

  private companion object {
    const val Capacity = 256

    /** The UI thread's and RenderThread's stages of a frame, from input handling to the buffer swap. */
    val WorkMetrics = intArrayOf(
      FrameMetrics.INPUT_HANDLING_DURATION,
      FrameMetrics.ANIMATION_DURATION,
      FrameMetrics.LAYOUT_MEASURE_DURATION,
      FrameMetrics.DRAW_DURATION,
      FrameMetrics.SYNC_DURATION,
      FrameMetrics.COMMAND_ISSUE_DURATION,
      FrameMetrics.SWAP_BUFFERS_DURATION,
    )
  }
}

@Preview
@Composable
private fun FrameHudPreview() = FrameHud()
