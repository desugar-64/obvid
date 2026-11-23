// Copyright 2026, Serhii Yaremych (desugar-64) and the Obvid project contributors
// SPDX-License-Identifier: Apache-2.0

package dev.serhiiyaremych.obvid

import androidx.annotation.RestrictTo
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.shadow.Shadow

/**
 * Benchmark hook, not API (debug and benchmark builds only): one [CpuInnerShadowNode] draw without the node.
 * [drawShadow] does exactly what the node does in a frame (rebuild through the shared store when the geometry,
 * offset included, changed since the last call, then the final draw call); [release] lets go of the store entry, as
 * the node does on detach.
 */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
public class InnerShadowDrawHook {
  private val state = ShadowDrawState()

  public fun DrawScope.drawShadow(shape: Shape, shadow: Shadow): Unit = with(state) { drawInnerShadow(shape, shadow) }

  public fun release(): Unit = state.release()
}
