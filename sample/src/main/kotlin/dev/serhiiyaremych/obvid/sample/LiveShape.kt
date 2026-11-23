// Copyright 2026, Serhii Yaremych (desugar-64) and the Obvid project contributors
// SPDX-License-Identifier: Apache-2.0

package dev.serhiiyaremych.obvid.sample

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.node.DelegatingNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.ModifierNodeElement

/**
 * A shadow modifier whose shape changes in the draw phase, without recomposition. Compose's shadow modifiers and
 * Obvid's key the outline on the [Shape] value (a shape that reads state inside `createOutline` keeps its first
 * outline), so a moving outline has to arrive as a new shape. On every draw this node reads [shape]; when the result
 * differs from the last one, it builds [shadowFor]`(shape)` (a single shadow element, e.g. `Modifier.dropShadow(shape)
 * { … }`) and updates its delegated shadow node with it, as a recomposition with the new shape would. The read is
 * observed by the draw phase, so a State behind [shape] invalidates the draw only.
 */
internal class LiveShapeShadowElement(
  private val shape: () -> Shape,
  private val shadowFor: (Shape) -> Modifier,
) : ModifierNodeElement<LiveShapeShadowNode>() {
  override fun create() = LiveShapeShadowNode(shape, shadowFor)

  override fun update(node: LiveShapeShadowNode) = node.update(shape, shadowFor)

  override fun equals(other: Any?) = other is LiveShapeShadowElement && other.shape === shape && other.shadowFor === shadowFor

  override fun hashCode() = 31 * shape.hashCode() + shadowFor.hashCode()
}

internal class LiveShapeShadowNode(
  private var shape: () -> Shape,
  private var shadowFor: (Shape) -> Modifier,
) : DelegatingNode(), DrawModifierNode {
  private var current: Shape = shape()
  private val shadow: Modifier.Node = delegate(shadowFor(current).asElement().create())

  fun update(shape: () -> Shape, shadowFor: (Shape) -> Modifier) {
    this.shape = shape
    this.shadowFor = shadowFor
    current = shape()
    shadowFor(current).asElement().update(shadow)
  }

  override fun ContentDrawScope.draw() {
    val next = shape()
    if (next != current) {
      current = next
      shadowFor(next).asElement().update(shadow)
    }
    // The shadow node's own draw, on this scope: its drawContent() goes on to the rest of the chain.
    with(shadow as DrawModifierNode) { draw() }
  }
}

@Suppress("UNCHECKED_CAST")
private fun Modifier.asElement(): ModifierNodeElement<Modifier.Node> =
  checkNotNull(this as? ModifierNodeElement<Modifier.Node>) { "expected one shadow element, got $this" }

/**
 * The latest pose's shape, made by [make] from two pose values and kept while they stay the same: every reader of one
 * pose in a frame (the shadow, the fill) gets the same instance, and so the outline that instance caches.
 */
class ShapeMemo(private val make: (Float, Float) -> Shape) {
  private var a = Float.NaN
  private var b = Float.NaN
  private var shape: Shape? = null

  fun at(a: Float, b: Float = 0f): Shape {
    val cached = shape
    if (cached != null && a == this.a && b == this.b) return cached
    this.a = a
    this.b = b
    return make(a, b).also { shape = it }
  }
}

/**
 * Creates [shape]'s outline before the rest of the chain draws: put ahead of a shadow section, it keeps the outline
 * building (cached by the shape instance) out of the section, the same for every implementation.
 */
fun Modifier.outlineAhead(shape: () -> Shape): Modifier = drawWithContent {
  shape().createOutline(size, layoutDirection, this)
  drawContent()
}

/** Fills [shape] (read in the draw phase) with [color], behind the content. */
fun Modifier.liveShapeFill(shape: () -> Shape, color: Color): Modifier = drawBehind {
  drawOutline(shape().createOutline(size, layoutDirection, this), color)
}
