// Copyright 2026, Serhii Yaremych (desugar-64) and the Obvid project contributors
// SPDX-License-Identifier: Apache-2.0

package dev.serhiiyaremych.obvid

import androidx.compose.runtime.Stable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.DropShadowScope
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.draw.dropShadow as composeDropShadow

/**
 * Drop-in replacement for Compose's `Modifier.dropShadow(shape, shadow)` (ui 1.13.0), built by the native pipeline:
 * the pixels Compose draws on Android 16–17's Skia, on every Android version (on older ones this keeps that output
 * rather than the device Skia's; the masks are still rasterized by the device's `Canvas`, and rects are drawn by the
 * device's Skia). The bitmap is rebuilt only when the shape, layout size, layout direction, density, radius or spread
 * changes; color, brush, alpha, blend mode and offset only change the final draw call. If the native library cannot
 * be used, this is Compose's own `dropShadow`.
 *
 * [LocalShadowExtent] can widen the bitmap past Compose's window so that a large blur fades out without a cut
 * ([ShadowExtent.Full]; no longer identical to Compose's then); a change of it rebuilds the bitmap.
 *
 * Trace sections (app tag, visible in Perfetto or a Macrobenchmark trace), emitted only when a bitmap is (re)built: a
 * draw of a cached bitmap adds none. `Obvid dropShadow build` / `Obvid innerShadow build` cover one whole build. Inside
 * one: `Obvid outline`, the platform `Canvas` drawing the shape into an alpha mask; `Obvid blur`, the native blur of
 * that mask into the shadow bitmap; `Obvid nine-patch`, a round rect or rect frame blurred small and stretched (or a
 * kept blurred piece only stretched again); `Obvid Skia route`, a shadow left to the device's Skia (Compose's own
 * draw calls). The rest of a build is the outline's creation, the bitmap's preparation and an inner shadow's inversion.
 * With [ShadowBuilds.Parallel]: `Obvid prefetch build`, a first build on a worker thread, with `Obvid outline`,
 * `Obvid blur`, `Obvid nine-patch` or `Obvid Skia route` directly inside (the shape's outline was made on the UI
 * thread before); `Obvid prefetch wait`, a draw on the UI thread that waits for that build to end.
 */
@Stable
public fun Modifier.dropShadow(shape: Shape, shadow: Shadow): Modifier =
  if (NativeShadows.useComposeFallback) composeDropShadow(shape, shadow)
  else this then CpuDropShadowElement(shape, shadow)

/**
 * Drop-in replacement for Compose's `Modifier.dropShadow(shape) { … }` (ui 1.13.0), on Compose's own
 * [DropShadowScope]: the block is read in the draw phase, as in Compose. Same rebuild rules and fallback as the
 * [Shadow] overload.
 */
@Stable
public fun Modifier.dropShadow(shape: Shape, block: DropShadowScope.() -> Unit): Modifier =
  if (NativeShadows.useComposeFallback) composeDropShadow(shape, block)
  else this then CpuBlockDropShadowElement(shape, block)

// -------------------------------------------------------------------------------------------------------------------
// Elements (Compose's SimpleDropShadowElement / BlockDropShadowElement)
// -------------------------------------------------------------------------------------------------------------------

internal data class CpuDropShadowElement(val shape: Shape, val shadow: Shadow) :
  ModifierNodeElement<CpuDropShadowNode>() {

  override fun create() = CpuDropShadowNode(shape, shadow)

  override fun update(node: CpuDropShadowNode) {
    node.shape = shape
    node.shadow = shadow
  }

  override fun InspectorInfo.inspectableProperties() {
    name = "dropShadow"
    properties["shape"] = shape
    properties["dropShadow"] = shadow
  }
}

internal class CpuBlockDropShadowElement(val shape: Shape, val block: DropShadowScope.() -> Unit) :
  ModifierNodeElement<CpuBlockDropShadowNode>() {

  override fun create() = CpuBlockDropShadowNode(shape, block)

  override fun update(node: CpuBlockDropShadowNode) {
    node.update(shape, block)
  }

  override fun InspectorInfo.inspectableProperties() {
    name = "dropShadow"
    properties["shape"] = shape
    properties["block"] = block
  }

  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (other !is CpuBlockDropShadowElement) return false
    return shape == other.shape && block === other.block
  }

  override fun hashCode() = 31 * shape.hashCode() + block.hashCode()
}
