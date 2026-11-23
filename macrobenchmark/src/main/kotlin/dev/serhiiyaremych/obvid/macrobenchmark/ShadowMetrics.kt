// Copyright 2026, Serhii Yaremych (desugar-64) and the Obvid project contributors
// SPDX-License-Identifier: Apache-2.0

package dev.serhiiyaremych.obvid.macrobenchmark

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.ExperimentalMacrobenchmarkApi
import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.MemoryUsageMetric
import androidx.benchmark.macro.TraceSectionMetric
import androidx.test.platform.app.InstrumentationRegistry

internal const val TARGET_PACKAGE = "dev.serhiiyaremych.obvid.sample"
internal const val SHADOW_SECTION = "demo:dropShadow"

/** What both harnesses record: frame timing, the [SHADOW_SECTION] sections (sum and mean), anonymous RSS at the end. */
@OptIn(ExperimentalMetricApi::class)
internal fun shadowMetrics() =
  listOf(
    FrameTimingMetric(),
    TraceSectionMetric(SHADOW_SECTION, TraceSectionMetric.Mode.Sum),
    TraceSectionMetric(SHADOW_SECTION, TraceSectionMetric.Mode.Average),
    MemoryUsageMetric(MemoryUsageMetric.Mode.Last),
  )

internal const val INNER_SHADOW_SECTION = "demo:innerShadow"

/** The library's own sections around a bitmap build (none on a Compose run, nor on a cached draw). */
internal const val OBVID_DROP_BUILD_SECTION = "Obvid dropShadow build"
internal const val OBVID_INNER_BUILD_SECTION = "Obvid innerShadow build"

/**
 * What the tier benchmark records: frame timing, the drop, inner and combined shadow sections and Obvid's drop and
 * inner build sections (Sum: total ms and count per iteration; 0 on a Compose run) and anonymous RSS at the end.
 * RenderThread and main-thread time come from the kept traces (scripts/analyze_tiers.py).
 */
@OptIn(ExperimentalMetricApi::class)
internal fun tierMetrics() =
  listOf(
    FrameTimingMetric(),
    TraceSectionMetric(SHADOW_SECTION, TraceSectionMetric.Mode.Sum),
    TraceSectionMetric(INNER_SHADOW_SECTION, TraceSectionMetric.Mode.Sum),
    TraceSectionMetric(listOf(SHADOW_SECTION, INNER_SHADOW_SECTION), label = "demo:shadow", mode = TraceSectionMetric.Mode.Sum),
    TraceSectionMetric(OBVID_DROP_BUILD_SECTION, TraceSectionMetric.Mode.Sum),
    TraceSectionMetric(OBVID_INNER_BUILD_SECTION, TraceSectionMetric.Mode.Sum),
    MemoryUsageMetric(MemoryUsageMetric.Mode.Last),
  )

/**
 * The instrumentation argument `compilation`: `full` (the default) is [CompilationMode.Full], which on API < 34 resets
 * the target's compilation by reinstalling it; `ignore` is [CompilationMode.Ignore], which keeps the target's AOT state
 * as the caller left it (scripts/run_bench.sh compiles it `speed` before every case, the state `Full` produces).
 */
@OptIn(ExperimentalMacrobenchmarkApi::class)
internal fun compilationMode(): CompilationMode =
  when (val arg = InstrumentationRegistry.getArguments().getString("compilation") ?: "full") {
    "full" -> CompilationMode.Full()
    "ignore" -> CompilationMode.Ignore()
    else -> error("compilation=$arg: expected full or ignore")
  }
