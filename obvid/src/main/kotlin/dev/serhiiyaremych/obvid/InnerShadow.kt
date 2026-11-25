// Copyright 2026, Serhii Yaremych (desugar-64) and the Obvid project contributors
// SPDX-License-Identifier: Apache-2.0

package dev.serhiiyaremych.obvid

import androidx.compose.runtime.Stable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.InnerShadowScope
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.node.requireDensity
import androidx.compose.ui.node.requireLayoutDirection
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.draw.innerShadow as composeInnerShadow

/**
 * Drop-in replacement for Compose's `Modifier.innerShadow(shape, shadow)`. Draws the same pixels as Compose on
 * Android 16–17, on every Android version; on older versions rects, radius 0 and paths with spread are drawn by the
 * device's Skia. Rebuilds the bitmap only when the shape, size, layout direction, density, radius, spread or offset
 * changes. Uses Compose's `innerShadow` when the native library cannot be loaded.
 *
 * [LocalShadowExtent] does not apply. Builds show in traces as `Obvid innerShadow build`.
 */
@Stable
public fun Modifier.innerShadow(shape: Shape, shadow: Shadow): Modifier =
  if (NativeShadows.useComposeFallback) composeInnerShadow(shape, shadow)
  else this then CpuInnerShadowElement(shape, shadow)

/**
 * Drop-in replacement for Compose's `Modifier.innerShadow(shape) { … }`, on Compose's own [InnerShadowScope]: the block
 * is read in the draw phase, as in Compose. Same rebuild rules and fallback as the [Shadow] overload.
 */
@Stable
public fun Modifier.innerShadow(shape: Shape, block: InnerShadowScope.() -> Unit): Modifier =
  if (NativeShadows.useComposeFallback) composeInnerShadow(shape, block)
  else this then CpuBlockInnerShadowElement(shape, block)

// -------------------------------------------------------------------------------------------------------------------
// Elements (Compose's SimpleInnerShadowElement / BlockInnerShadowElement)
// -------------------------------------------------------------------------------------------------------------------

internal data class CpuInnerShadowElement(val shape: Shape, val shadow: Shadow) :
  ModifierNodeElement<CpuInnerShadowNode>() {

  override fun create() = CpuInnerShadowNode(shape, shadow)

  override fun update(node: CpuInnerShadowNode) {
    node.shape = shape
    node.shadow = shadow
  }

  override fun InspectorInfo.inspectableProperties() {
    name = "innerShadow"
    properties["shape"] = shape
    properties["innerShadow"] = shadow
  }
}

internal class CpuBlockInnerShadowElement(val shape: Shape, val block: InnerShadowScope.() -> Unit) :
  ModifierNodeElement<CpuBlockInnerShadowNode>() {

  override fun create() = CpuBlockInnerShadowNode(shape, block)

  override fun update(node: CpuBlockInnerShadowNode) {
    node.update(shape, block)
  }

  override fun InspectorInfo.inspectableProperties() {
    name = "innerShadow"
    properties["shape"] = shape
    properties["block"] = block
  }

  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (other !is CpuBlockInnerShadowElement) return false
    return shape == other.shape && block === other.block
  }

  override fun hashCode() = 31 * shape.hashCode() + block.hashCode()
}

// -------------------------------------------------------------------------------------------------------------------
// Nodes (Compose's SimpleInnerShadowNode / BlockInnerShadowNode)
// -------------------------------------------------------------------------------------------------------------------

/** Compose's `SimpleInnerShadowNode` (the shadow, then the content), rebuilding only on a geometry change. */
internal class CpuInnerShadowNode(var shape: Shape, var shadow: Shadow) : CpuShadowNode() {

  private val state = ShadowDrawState()

  override fun onMeasured(size: IntSize) {
    if (state.wantsPrefetch && currentValueOf(LocalShadowBuilds) == ShadowBuilds.Parallel) {
      val density = requireDensity()
      with(density) {
        state.prefetch(
          density,
          shape,
          size.toSize(),
          requireLayoutDirection(),
          shadow.radius.toPx(),
          shadow.spread.toPx(),
          true,
          shadow.offset.x.toPx(),
          shadow.offset.y.toPx(),
          ShadowExtent.Compose,
        )
      }
    }
  }

  override fun ContentDrawScope.draw() {
    with(state) { drawInnerShadow(shape, shadow) }
    drawContent()
  }

  override fun onDetach() {
    state.release()
  }
}

/** Compose's `BlockInnerShadowNode` ([CpuBlockShadowNode]), drawn as [CpuInnerShadowNode] draws a `Shadow`. */
internal class CpuBlockInnerShadowNode(shape: Shape, block: InnerShadowScope.() -> Unit) :
  CpuBlockShadowNode<InnerShadowScope>(shape, block), InnerShadowScope {

  override val scope: InnerShadowScope
    get() = this

  override val inner: Boolean
    get() = true

  override fun DrawScope.drawFinalShadow(
    shape: Shape,
    radiusPx: Float,
    spreadPx: Float,
    offsetXPx: Float,
    offsetYPx: Float,
    color: Color,
    brush: Brush?,
    alpha: Float,
    blendMode: BlendMode,
  ) {
    with(state) { drawInnerShadow(shape, radiusPx, spreadPx, offsetXPx, offsetYPx, color, brush, alpha, blendMode) }
  }
}
