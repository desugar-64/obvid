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
 * Drop-in replacement for Compose's `Modifier.dropShadow(shape, shadow)`. Draws the same pixels as Compose on
 * Android 16–17, on every Android version; on older versions rects are drawn by the device's Skia. Rebuilds the bitmap
 * only when the shape, size, layout direction, density, radius or spread changes. Uses Compose's `dropShadow` when the
 * native library cannot be loaded.
 *
 * [LocalShadowExtent] sets how far the blur reaches. Builds show in traces as `Obvid dropShadow build`.
 */
@Stable
public fun Modifier.dropShadow(shape: Shape, shadow: Shadow): Modifier =
  if (NativeShadows.useComposeFallback) composeDropShadow(shape, shadow)
  else this then CpuDropShadowElement(shape, shadow)

/**
 * Drop-in replacement for Compose's `Modifier.dropShadow(shape) { … }`, on Compose's own [DropShadowScope]: the block
 * is read in the draw phase, as in Compose. Same rebuild rules and fallback as the [Shadow] overload.
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
