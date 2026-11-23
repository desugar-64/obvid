// Copyright 2026, Serhii Yaremych (desugar-64) and the Obvid project contributors
// SPDX-License-Identifier: Apache-2.0

package dev.serhiiyaremych.obvid.fixtures

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.toPath
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.Morph
import kotlin.math.floor

/** Cookie9Sided to Pill. `MaterialShapes` polygons are already normalized to the unit square. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
val CookiePillMorph: Morph by lazy { Morph(MaterialShapes.Cookie9Sided, MaterialShapes.Pill) }

/**
 * The [morph] at [progress], stretched so its bounds fill the layout box: an `Outline.Generic`, so
 * `dropShadow` takes its path branch. Identity equality on purpose: a new instance per progress value
 * is a new shadow cache key, like any animated custom shape. The outline is kept per size so repeated
 * draws measure the shadow, not the path conversion.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
class MorphShape(private val morph: Morph, private val progress: Float) : Shape {
  private var outlineSize = Size.Unspecified
  private var outline: Outline? = null

  override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
    outline?.let { if (size == outlineSize) return it }
    val path = morph.toPath(progress, Path())
    val bounds = path.getBounds()
    path.translate(Offset(-bounds.left, -bounds.top))
    path.transform(Matrix().apply { scale(size.width / bounds.width, size.height / bounds.height) })
    return Outline.Generic(path).also {
      outline = it
      outlineSize = size
    }
  }
}

/**
 * A frame: the box and a rect inside it inset by a quarter of each side plus 0.5 px, two rect contours of one path (an
 * `Outline.Generic`; not a rect for `Path.isRect`). [evenOdd] false: the inner contour runs counter-clockwise (a hole
 * under the default non-zero fill); true: both run clockwise and the path's fill type is even-odd. The outline is kept
 * per size, as [MorphShape]'s.
 */
class FrameShape(private val evenOdd: Boolean) : Shape {
  private var outlineSize = Size.Unspecified
  private var outline: Outline? = null

  override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
    outline?.let { if (size == outlineSize) return it }
    val dx = floor(size.width / 4f) + 0.5f
    val dy = floor(size.height / 4f) + 0.5f
    val path = android.graphics.Path()
    path.addRect(0f, 0f, size.width, size.height, android.graphics.Path.Direction.CW)
    val inner = if (evenOdd) android.graphics.Path.Direction.CW else android.graphics.Path.Direction.CCW
    path.addRect(dx, dy, size.width - dx, size.height - dy, inner)
    if (evenOdd) path.fillType = android.graphics.Path.FillType.EVEN_ODD
    return Outline.Generic(path.asComposePath()).also {
      outline = it
      outlineSize = size
    }
  }
}

/** A shape of the catalog and its name in case labels, result rows and benchmark case names. */
class ShapeCase(val name: String, val shape: Shape)

/** The catalog's names, in its order: one shape per `dropShadow` route (see [catalogShape]). */
val CatalogNames = listOf("rect", "rrect16", "mixed", "morph", "circle")

/**
 * A new instance of the catalog shape [name]: `rect` (Skia's rect route), `rrect16` (a simple round rect, Compose's
 * round-rect branch), `mixed` (non-uniform corners (4, 16, 24, 8) dp, drawn as a path), `morph` ([MorphShape] of
 * [CookiePillMorph] at 0.5, a path) and `circle` (a simple round rect with radius half the size).
 */
fun catalogShape(name: String): Shape =
  when (name) {
    "rect" -> RectangleShape
    "rrect16" -> RoundedCornerShape(16.dp)
    "mixed" -> RoundedCornerShape(4.dp, 16.dp, 24.dp, 8.dp)
    "morph" -> MorphShape(CookiePillMorph, 0.5f)
    "circle" -> CircleShape
    else -> error("Unknown shape $name")
  }

/** The whole catalog, new instances (a [MorphShape] keeps its last outline, so each caller owns its own). */
fun shapeCatalog(): List<ShapeCase> = CatalogNames.map { ShapeCase(it, catalogShape(it)) }

/**
 * Corner radii (dp) of the round-rect route cases (Skia's nine-patch and its full-mask fallback): 4 dp (10.5 px at
 * density 2.625), 16 and 40 dp (whole px there) and 6.3 dp (16.5375 px, a fraction that is not a half).
 */
val RoundCornerDps = listOf(4f, 16f, 40f, 6.3f)

/** `RoundedCornerShape(c.dp)` for each of [RoundCornerDps], named `rrect<c>` (`rrect4`, `rrect16`, `rrect40`, `rrect6.3`). */
fun roundRectShapes(): List<ShapeCase> =
  RoundCornerDps.map { c ->
    val name = if (c == c.toInt().toFloat()) "rrect${c.toInt()}" else "rrect$c"
    ShapeCase(name, RoundedCornerShape(c.dp))
  }
