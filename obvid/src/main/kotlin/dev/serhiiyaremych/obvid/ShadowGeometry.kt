// Copyright 2026, Serhii Yaremych (desugar-64) and the Obvid project contributors
// SPDX-License-Identifier: Apache-2.0

package dev.serhiiyaremych.obvid

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.LayoutDirection

/**
 * Everything the shadow bitmap depends on: Compose's renderer key (`AndroidShadowContext.ShadowKey`: shape, size,
 * layout direction, density) plus the parameters of its bitmap: the kind ([inner]), radius and spread in px, for an
 * inner shadow the offset in px (Compose draws it into the bitmap), and for a drop shadow its [extent] (the bitmap's
 * window; an inner shadow stores [ShadowExtent.Compose]). Color, brush, alpha and blend mode are not part of
 * it, nor a drop shadow's offset (a drop shadow stores 0): they only change the final draw call. Only a key change
 * rebuilds; a drop and an inner shadow never share a key.
 *
 * Mutable, as Compose's `ShadowKey`: each [ShadowStore] fills its one lookup key and copies it only to insert a
 * new entry; a key that is in the store's map is changed only after it was taken out (an entry re-keyed in place).
 * Lookup and stored keys compare with the same data-class `equals` (`Float.compare` semantics for the floats).
 */
internal data class ShadowGeometry(
  var shape: Shape = RectangleShape,
  var size: Size = Size.Zero,
  var layoutDirection: LayoutDirection = LayoutDirection.Ltr,
  var density: Float = 1f,
  var radiusPx: Float = 0f,
  var spreadPx: Float = 0f,
  var inner: Boolean = false,
  var offsetXPx: Float = 0f,
  var offsetYPx: Float = 0f,
  var extent: ShadowExtent = ShadowExtent.Compose,
) {
  fun set(
    shape: Shape,
    size: Size,
    layoutDirection: LayoutDirection,
    density: Float,
    radiusPx: Float,
    spreadPx: Float,
    inner: Boolean,
    offsetXPx: Float,
    offsetYPx: Float,
    extent: ShadowExtent,
  ): ShadowGeometry {
    this.shape = shape
    this.size = size
    this.layoutDirection = layoutDirection
    this.density = density
    this.radiusPx = radiusPx
    this.spreadPx = spreadPx
    this.inner = inner
    this.offsetXPx = offsetXPx
    this.offsetYPx = offsetYPx
    this.extent = extent
    return this
  }

  fun set(other: ShadowGeometry): ShadowGeometry =
    set(
      other.shape,
      other.size,
      other.layoutDirection,
      other.density,
      other.radiusPx,
      other.spreadPx,
      other.inner,
      other.offsetXPx,
      other.offsetYPx,
      other.extent,
    )

  /**
   * Same outline inputs as [other] (shape, size, layout direction, density): `createOutline` gives the same outline,
   * so it is kept (Compose's own renderer cache makes the same assumption).
   */
  fun sameOutline(other: ShadowGeometry): Boolean =
    shape == other.shape &&
      size == other.size &&
      layoutDirection == other.layoutDirection &&
      density.compareTo(other.density) == 0
}
