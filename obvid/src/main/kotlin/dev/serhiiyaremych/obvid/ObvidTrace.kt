// Copyright 2026, Serhii Yaremych (desugar-64) and the Obvid project contributors
// SPDX-License-Identifier: Apache-2.0

package dev.serhiiyaremych.obvid

import android.os.Trace

// The library's trace sections (see `Modifier.dropShadow`'s KDoc). Constant names: with tracing off,
// `Trace.beginSection` only checks the app tag.
internal const val TRACE_DROP_BUILD = "Obvid dropShadow build"
internal const val TRACE_INNER_BUILD = "Obvid innerShadow build"
internal const val TRACE_OUTLINE = "Obvid outline"
internal const val TRACE_BLUR = "Obvid blur"
internal const val TRACE_NINE_PATCH = "Obvid nine-patch"
internal const val TRACE_SKIA = "Obvid Skia route"

/** [block] inside the trace section [name] (closed on a throw too). */
internal inline fun <T> traceSection(name: String, block: () -> T): T {
  Trace.beginSection(name)
  try {
    return block()
  } finally {
    Trace.endSection()
  }
}
