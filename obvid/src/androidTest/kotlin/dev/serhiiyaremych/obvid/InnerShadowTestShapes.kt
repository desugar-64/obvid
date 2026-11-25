// Copyright 2026, Serhii Yaremych (desugar-64) and the Obvid project contributors
// SPDX-License-Identifier: Apache-2.0

package dev.serhiiyaremych.obvid

import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.toPath
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import dev.serhiiyaremych.obvid.builder.ShadowBitmaps
import dev.serhiiyaremych.obvid.fixtures.CookiePillMorph
import kotlin.math.floor

/** A uniform round rect whose corners are (x, 0): not "simple" for Compose, so its outline is drawn as a path. */
internal class CornerX0Shape(private val corner: Dp) : Shape {
  override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline =
    Outline.Rounded(
      RoundRect(0f, 0f, size.width, size.height, CornerRadius(with(density) { corner.toPx() }, 0f))
    )
}

/**
 * [CookiePillMorph] at 0.5 placed so its bounds have fractional edges and a width / height fraction of [fracW] /
 * [fracH] px (bounds not at the origin); [negative] puts it partly left of and below the box.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
internal class PlacedMorphShape(private val fracW: Float, private val fracH: Float, private val negative: Boolean = false) :
  Shape {
  override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
    val s = size.width
    val place =
      if (negative) Rect(Offset(-floor(0.2f * s) - 0.4f, floor(0.1f * s) + 0.3f), Size(floor(0.9f * s) + fracW, s - floor(0.1f * s) + fracH))
      else Rect(Offset(floor(0.1f * s) + 0.37f, floor(0.15f * s) + 0.61f), Size(floor(0.7f * s) + fracW, floor(0.6f * s) + fracH))
    val path = CookiePillMorph.toPath(0.5f, Path())
    val b = path.getBounds()
    path.translate(Offset(-b.left, -b.top))
    path.transform(Matrix().apply { scale(place.width / b.width, place.height / b.height) })
    path.translate(place.topLeft)
    return Outline.Generic(path)
  }
}

/**
 * A rect as an `Outline.Generic` (what a `GenericShape { addRect(…) }` gives): the whole box, or with [inset] > 0 the
 * box inset by that fraction of each side plus 0.37 px (fractional edges).
 */
internal class RectPathShape(private val inset: Float = 0f) : Shape {
  override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
    val dx = if (inset > 0f) floor(inset * size.width) + 0.37f else 0f
    val dy = if (inset > 0f) floor(inset * size.height) + 0.37f else 0f
    val path = android.graphics.Path()
    path.addRect(dx, dy, size.width - dx, size.height - dy, android.graphics.Path.Direction.CW)
    return Outline.Generic(path.asComposePath())
  }
}

/**
 * Paths around the builders' nested-rects test ([ShadowBitmaps.nestedRects]), each an `Outline.Generic` filling the
 * box, by [kind] ([KINDS]):
 * - `sameDirWinding`: the box and a rect inside it, both clockwise, non-zero fill (fills the whole box);
 * - `collinearFrame`: a frame whose outer contour has an extra point on its top edge;
 * - `ulpFrame`: a frame whose outer right edge is off axis by one float ulp (its bottom end at `Math.nextUp(width)`);
 *   a translate can round both ends to one x (at 48 dp and density 2.625, 126 px: by 2.625, 10.5, 63, 168 or 3.25 px);
 * - `disjoint`: two rects side by side, neither inside the other;
 * - `threeNested`: three nested rect contours, alternating direction;
 * - `framePlusCurve`: a frame and an oval inside its hole (three contours, one curved);
 * - `rectPlusOval`: the box with an oval hole (two contours, one curved);
 * - `quadPair`: a diamond with a smaller diamond hole (no edge axis-aligned);
 * - `diagonalClose` / `diagonalOpen`: two triangles (the second inside the first, even-odd fill) whose drawn edges are
 *   all axis-aligned and whose closing edge is diagonal, with a close verb / closed only by the fill.
 * Edges have fractional coordinates.
 */
internal class NestedRectsEdgeShape(private val kind: String) : Shape {
  override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
    val w = size.width
    val h = size.height
    val cw = android.graphics.Path.Direction.CW
    val ccw = android.graphics.Path.Direction.CCW
    val p = android.graphics.Path()
    fun inset(f: Float, dir: android.graphics.Path.Direction) {
      val dx = floor(f * w) + 0.37f
      val dy = floor(f * h) + 0.37f
      p.addRect(dx, dy, w - dx, h - dy, dir)
    }
    when (kind) {
      "sameDirWinding" -> {
        p.addRect(0f, 0f, w, h, cw)
        inset(0.25f, cw)
      }
      "collinearFrame" -> {
        p.moveTo(0f, 0f)
        p.lineTo(floor(0.4f * w) + 0.25f, 0f)
        p.lineTo(w, 0f)
        p.lineTo(w, h)
        p.lineTo(0f, h)
        p.close()
        inset(0.25f, ccw)
      }
      "ulpFrame" -> {
        p.moveTo(0f, 0f)
        p.lineTo(w, 0f)
        p.lineTo(Math.nextUp(w), h)
        p.lineTo(0f, h)
        p.close()
        inset(0.25f, ccw)
      }
      "disjoint" -> {
        p.addRect(0.5f, 0.37f, floor(0.45f * w) + 0.25f, h - 0.37f, cw)
        p.addRect(floor(0.55f * w) + 0.75f, 0.37f, w - 0.5f, h - 0.37f, cw)
      }
      "threeNested" -> {
        p.addRect(0f, 0f, w, h, cw)
        inset(1f / 6f, ccw)
        inset(1f / 3f, cw)
      }
      "framePlusCurve" -> {
        p.addRect(0f, 0f, w, h, cw)
        inset(0.2f, ccw)
        p.addOval(0.4f * w, 0.4f * h, 0.6f * w, 0.6f * h, cw)
      }
      "rectPlusOval" -> {
        p.addRect(0f, 0f, w, h, cw)
        p.addOval(0.25f * w + 0.37f, 0.25f * h + 0.37f, 0.75f * w - 0.37f, 0.75f * h - 0.37f, ccw)
      }
      "quadPair" -> {
        p.moveTo(w / 2f, 0f)
        p.lineTo(w, h / 2f)
        p.lineTo(w / 2f, h)
        p.lineTo(0f, h / 2f)
        p.close()
        p.moveTo(w / 2f, 0.25f * h + 0.37f)
        p.lineTo(0.25f * w + 0.37f, h / 2f)
        p.lineTo(w / 2f, 0.75f * h - 0.37f)
        p.lineTo(0.75f * w - 0.37f, h / 2f)
        p.close()
      }
      "diagonalClose", "diagonalOpen" -> {
        val close = kind == "diagonalClose"
        p.moveTo(0.37f, 0.37f)
        p.lineTo(w - 0.37f, 0.37f)
        p.lineTo(w - 0.37f, h - 0.37f)
        if (close) p.close()
        p.moveTo(floor(0.5f * w) + 0.5f, floor(0.1f * h) + 0.25f)
        p.lineTo(floor(0.9f * w) + 0.5f, floor(0.1f * h) + 0.25f)
        p.lineTo(floor(0.9f * w) + 0.5f, floor(0.5f * h) + 0.25f)
        if (close) p.close()
        p.fillType = android.graphics.Path.FillType.EVEN_ODD
      }
      else -> error("unknown kind $kind")
    }
    return Outline.Generic(p.asComposePath())
  }

  companion object {
    /** Every kind, and whether the builders take it for nested rects (the Skia route where they check for them). */
    val KINDS: Map<String, Boolean> =
      linkedMapOf(
        "sameDirWinding" to true,
        "collinearFrame" to true,
        "ulpFrame" to true,
        "disjoint" to true,
        "threeNested" to false,
        "framePlusCurve" to false,
        "rectPlusOval" to false,
        "quadPair" to false,
        "diagonalClose" to false,
        "diagonalOpen" to false,
      )
  }
}
