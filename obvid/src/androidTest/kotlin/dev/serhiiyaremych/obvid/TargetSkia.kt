// Copyright 2026, Serhii Yaremych (desugar-64) and the Obvid project contributors
// SPDX-License-Identifier: Apache-2.0

package dev.serhiiyaremych.obvid

import android.os.Build
import java.util.Locale
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min

/**
 * The Skia whose output Obvid reproduces on every Android version: Android 16–17's (API 36+). The tests
 * that compare with the device's own Compose assert identity only where [isDevice]; elsewhere they record the
 * difference ([DiffTally]) and do not fail on it.
 */
internal object TargetSkia {
  val isDevice: Boolean = Build.VERSION.SDK_INT >= 36

  /** The prefix of the summary lines written only off the target. */
  val offTargetLabel: String
    get() = "below the target Skia (API ${Build.VERSION.SDK_INT} < 36), difference from this device's Compose"
}

/** PSNR in dB of [sse] (the sum of squared 8-bit errors) over [samples] samples; +∞ when there is no error. */
internal fun psnr(sse: Long, samples: Long): Double =
  if (sse == 0L) Double.POSITIVE_INFINITY else 10.0 * log10(255.0 * 255.0 * samples / sse)

internal fun formatPsnr(p: Double): String = if (p.isInfinite()) "inf" else String.format(Locale.US, "%.2f", p)

/** Differences from a reference over a group of cases: how many differ, by how much, and the PSNR. */
internal class DiffTally {
  private var cases = 0
  private var differing = 0
  private var diffPixels = 0L
  private var maxDiff = 0
  private var sse = 0L
  private var samples = 0L
  private var worst = Double.POSITIVE_INFINITY

  /** One case: [pixels] differing pixels, the largest channel difference [max], [sse] over [samples] samples. */
  fun add(pixels: Int, max: Int, sse: Long, samples: Long) {
    cases++
    if (pixels != 0) differing++
    diffPixels += pixels
    maxDiff = max(maxDiff, max)
    this.sse += sse
    this.samples += samples
    worst = min(worst, psnr(sse, samples))
  }

  fun add(d: ScreenCases.Diff) = add(d.pixels, d.max, d.sse, d.samples)

  /** PSNR over every compared sample of the group, then that of the worst case. */
  override fun toString(): String =
    "differ $differing / $cases, diff pixels $diffPixels, max diff $maxDiff, " +
      "PSNR ${formatPsnr(psnr(sse, samples))} dB (all cases), ${formatPsnr(worst)} dB (worst case)"
}
