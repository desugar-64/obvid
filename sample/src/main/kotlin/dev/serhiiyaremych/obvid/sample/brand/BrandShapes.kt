// Copyright 2026, Serhii Yaremych (desugar-64) and the Obvid project contributors
// SPDX-License-Identifier: Apache-2.0

package dev.serhiiyaremych.obvid.sample.brand

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.center
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.LinearGradientShader
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.Shader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import kotlin.math.min

/**
 * A ring filling the layout box: a circle with a centred circular hole of [hole] times the box's smaller side, filled
 * even-odd. An `Outline.Generic`, so the shadows take their path route. Value equality, so equal rings share shadow
 * cache entries.
 */
class RingShape(private val hole: Float) : Shape {
  override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
    val path = Path().apply { addOval(Rect(Offset.Zero, size)) }
    path.addOval(Rect(size.center, hole * min(size.width, size.height) / 2f))
    path.fillType = PathFillType.EvenOdd
    return Outline.Generic(path)
  }

  override fun equals(other: Any?) = other is RingShape && other.hole == hole

  override fun hashCode() = hole.hashCode()
}

/** [from] at the top-right corner to [to] at the bottom-left, scaled to the element it paints (the ring's glow). */
class AntiDiagonalBrush(private val from: Color, private val to: Color) : ShaderBrush() {
  override fun createShader(size: Size): Shader =
    LinearGradientShader(from = Offset(size.width, 0f), to = Offset(0f, size.height), colors = listOf(from, to))

  override fun equals(other: Any?) = other is AntiDiagonalBrush && other.from == from && other.to == to

  override fun hashCode() = 31 * from.hashCode() + to.hashCode()
}
