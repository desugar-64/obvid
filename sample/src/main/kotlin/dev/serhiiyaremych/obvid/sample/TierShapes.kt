// Copyright 2026, Serhii Yaremych (desugar-64) and the Obvid project contributors
// SPDX-License-Identifier: Apache-2.0

package dev.serhiiyaremych.obvid.sample

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection

/**
 * A ten-tooth gear in a 100 × 100 viewport, as SVG path data: tooth tips and roots are arcs, and six circles cut
 * holes (a 13-unit hub and five 6-unit holes on a 26-unit ring). The holes need the even-odd fill: the circles run in
 * the same direction as the outline.
 */
const val GearPathData =
  "M43.23,11.59 L44.98,2.26 A48,48 0 0,1 55.02,2.26 L56.77,11.59 A39,39 0 0,1 67.1,14.95 L74,8.43 " +
    "A48,48 0 0,1 82.12,14.33 L78.05,22.91 A39,39 0 0,1 84.43,31.69 L93.85,30.48 A48,48 0 0,1 " +
    "96.95,40.02 L88.62,44.57 A39,39 0 0,1 88.62,55.43 L96.95,59.98 A48,48 0 0,1 93.85,69.52 " +
    "L84.43,68.31 A39,39 0 0,1 78.05,77.09 L82.12,85.67 A48,48 0 0,1 74,91.57 L67.1,85.05 A39,39 0 " +
    "0,1 56.77,88.41 L55.02,97.74 A48,48 0 0,1 44.98,97.74 L43.23,88.41 A39,39 0 0,1 32.9,85.05 " +
    "L26,91.57 A48,48 0 0,1 17.88,85.67 L21.95,77.09 A39,39 0 0,1 15.57,68.31 L6.15,69.52 A48,48 0 " +
    "0,1 3.05,59.98 L11.38,55.43 A39,39 0 0,1 11.38,44.57 L3.05,40.02 A48,48 0 0,1 6.15,30.48 " +
    "L15.57,31.69 A39,39 0 0,1 21.95,22.91 L17.88,14.33 A48,48 0 0,1 26,8.43 L32.9,14.95 A39,39 0 0,1 " +
    "43.23,11.59 Z M37,50 A13,13 0 1,0 63,50 A13,13 0 1,0 37,50 Z M59.28,28.97 A6,6 0 1,0 71.28,28.97 " +
    "A6,6 0 1,0 59.28,28.97 Z M68.73,58.03 A6,6 0 1,0 80.73,58.03 A6,6 0 1,0 68.73,58.03 Z M44,76 " +
    "A6,6 0 1,0 56,76 A6,6 0 1,0 44,76 Z M19.27,58.03 A6,6 0 1,0 31.27,58.03 A6,6 0 1,0 19.27,58.03 Z " +
    "M28.72,28.97 A6,6 0 1,0 40.72,28.97 A6,6 0 1,0 28.72,28.97 Z"

/** [GearPathData] parsed once, in viewport units. */
private val GearUnitPath: Path by lazy { PathParser().parsePathString(GearPathData).toPath() }

/**
 * SVG path data [GearPathData] stretched from its 100 × 100 viewport to the layout box, filled even-odd: an
 * `Outline.Generic` with holes, so `dropShadow` takes its path branch. Identity equality: each instance is its own
 * shadow cache key. The outline is kept for the last size, so a static shape converts its path once.
 */
class GearShape : Shape {
  private var outlineSize = Size.Unspecified
  private var outline: Outline? = null

  override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
    outline?.let { if (size == outlineSize) return it }
    val path = Path().apply {
      addPath(GearUnitPath)
      transform(Matrix().apply { scale(size.width / GearViewport, size.height / GearViewport) })
      fillType = PathFillType.EvenOdd
    }
    return Outline.Generic(path).also {
      outline = it
      outlineSize = size
    }
  }

  private companion object {
    const val GearViewport = 100f
  }
}
