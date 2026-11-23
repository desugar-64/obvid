// Copyright 2026, Serhii Yaremych (desugar-64) and the Obvid project contributors
// SPDX-License-Identifier: Apache-2.0

package dev.serhiiyaremych.obvid.fixtures

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageBitmapConfig
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.PaintingStyle
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.drawscope.DrawScope
import kotlin.math.ceil
import kotlin.math.max
import androidx.compose.ui.graphics.Canvas as ComposeCanvas

/**
 * Reference A of the inner-shadow exactness test (`InnerShadowExactnessTest`): `InnerShadowRenderer`'s
 * `createInnerPathShadowBrush` and `createInnerShadowBrush` (Compose ui-graphics 1.13.0, `InnerShadowPainter.kt`),
 * copied line for line, returning the A8 bitmap instead of the `ShaderBrush(ImageShader(bitmap))` around it. Same
 * public calls and `Paint` configuration (`configureShadow`). A fresh `Paint` per build: Compose's renderer owns one
 * `Paint` and builds once (any change of shape, size or `Shadow` is a new renderer). [draw] is the renderer's
 * `onDrawShadow` for a shadow without a brush.
 *
 * Radius, spread and offset are in px, as the renderer reads them (`shadow.radius.toPx()`, …).
 */
object InnerShadowReference {

  /** [outlineParams] (`ShadowRenderer.updateParamsFromOutline`), then the matching build. */
  fun referenceA(outline: Outline, size: Size, radius: Float, spread: Float, offsetX: Float, offsetY: Float): ImageBitmap =
    referenceA(outlineParams(outline), size, radius, spread, offsetX, offsetY)

  /** `InnerShadowRenderer.buildShadow`: the path build for a path, the round-rect build otherwise. */
  fun referenceA(
    params: OutlineParams,
    size: Size,
    radius: Float,
    spread: Float,
    offsetX: Float,
    offsetY: Float,
  ): ImageBitmap {
    val path = params.path
    return if (path != null) refAPath(size, path, radius, spread, offsetX, offsetY)
    else refARoundRect(size, radius, spread, offsetX, offsetY, params.cornerRadius)
  }

  /**
   * `createInnerPathShadowBrush`. The bitmap is `ceil(size)` grown by `ceil(radius)` on each side, but the content is
   * not inset by that padding. A positive spread rasterizes the path into an intermediate bitmap of its bounds' size
   * (the path at its own coordinates), erodes it with a `Clear` stroke `2·spread` wide, and draws it blurred with
   * `Xor` over an opaque bitmap; otherwise the path is drawn blurred at the offset and the whole bitmap is `Xor`ed.
   */
  fun refAPath(size: Size, path: Path, radius: Float, spread: Float, offsetX: Float, offsetY: Float): ImageBitmap {
    val paint = Paint()
    val pathBitmap: ImageBitmap?
    val widthPx = ceil(size.width).toInt()
    val heightPx = ceil(size.height).toInt()
    if (spread > 0f) {
      val pathBounds = path.getBounds()
      val pathWidth = pathBounds.width
      val pathHeight = pathBounds.height
      pathBitmap = ImageBitmap(ceil(pathWidth).toInt(), ceil(pathHeight).toInt(), ImageBitmapConfig.Alpha8)
      val pathCanvas = ComposeCanvas(pathBitmap)
      with(pathCanvas) {
        drawPath(path, paint)
        clipRect(0f, 0f, pathWidth, pathHeight)
        drawPath(
          path,
          paint.configureShadow(style = PaintingStyle.Stroke, blendMode = BlendMode.Clear).apply {
            strokeWidth = spread * 2f
          },
        )
      }
    } else {
      pathBitmap = null
    }

    val clampPadding = ceil(radius).toInt()
    val shadowBitmap = ImageBitmap(widthPx + clampPadding * 2, heightPx + clampPadding * 2, ImageBitmapConfig.Alpha8)

    val shadowCanvas = ComposeCanvas(shadowBitmap)
    with(shadowCanvas) {
      if (pathBitmap != null) {
        drawRect(0f, 0f, shadowBitmap.width.toFloat(), shadowBitmap.height.toFloat(), paint.configureShadow())
        drawImage(
          pathBitmap,
          Offset(offsetX, offsetY),
          paint.configureShadow(
            blurFilter = if (radius > 0f) BlurFilter(radius) else null,
            blendMode = BlendMode.Xor,
          ),
        )
        return shadowBitmap
      } else {
        save()
        translate(offsetX, offsetY)
        drawPath(path, paint.configureShadow(blurFilter = if (radius > 0f) BlurFilter(radius) else null))
        restore()
        drawRect(
          0f,
          0f,
          shadowBitmap.width.toFloat(),
          shadowBitmap.height.toFloat(),
          paint.configureShadow(blendMode = BlendMode.Xor),
        )
        return shadowBitmap
      }
    }
  }

  /**
   * `createInnerShadowBrush`: a `ceil(size)` bitmap; the round rect inset by the spread (corner radii unchanged; a
   * negative spread grows it, a spread past the middle leaves it empty) and moved by the offset, drawn blurred, then
   * the whole bitmap `Xor`ed.
   */
  fun refARoundRect(
    size: Size,
    radius: Float,
    spread: Float,
    offsetX: Float,
    offsetY: Float,
    cornerRadius: CornerRadius,
  ): ImageBitmap {
    val paint = Paint()
    val shadowBitmap = ImageBitmap(ceil(size.width).toInt(), ceil(size.height).toInt(), ImageBitmapConfig.Alpha8)
    val shadowCanvas = ComposeCanvas(shadowBitmap)
    with(shadowCanvas) {
      val left = offsetX + spread
      val top = offsetY + spread
      val right = max(left, offsetX + size.width - spread)
      val bottom = max(top, offsetY + size.height - spread)

      drawRoundRect(
        left,
        top,
        right,
        bottom,
        cornerRadius.x,
        cornerRadius.y,
        paint.configureShadow(blurFilter = if (radius > 0) BlurFilter(radius) else null),
      )
      drawRect(
        0f,
        0f,
        shadowBitmap.width.toFloat(),
        shadowBitmap.height.toFloat(),
        paint.configureShadow(blendMode = BlendMode.Xor),
      )
    }
    return shadowBitmap
  }

  /**
   * `InnerShadowRenderer.onDrawShadow` for a shadow without a brush, as `InnerShadowPainter` calls it without a painter
   * color filter: the [mask] as an `ImageShader` (clamp, pixel (0, 0) at the component origin), tinted with [color],
   * fills the outline ([params]: `drawPath`, `drawRect` for a zero corner radius, `drawRoundRect` otherwise) with
   * [alpha] and [blendMode]. No translate: the offset is in the mask.
   */
  fun draw(
    scope: DrawScope,
    mask: ImageBitmap,
    params: OutlineParams,
    color: Color = Color.Black,
    alpha: Float = 1f,
    blendMode: BlendMode = BlendMode.SrcOver,
  ) =
    with(scope) {
      val brush = ShaderBrush(ImageShader(mask))
      val colorFilter = ColorFilter.tint(color)
      val path = params.path
      val cornerRadius = params.cornerRadius
      if (path != null) {
        drawPath(path, brush = brush, colorFilter = colorFilter, alpha = alpha, blendMode = blendMode)
      } else if (cornerRadius == CornerRadius.Zero) {
        drawRect(brush = brush, colorFilter = colorFilter, alpha = alpha, blendMode = blendMode)
      } else {
        drawRoundRect(
          brush = brush,
          cornerRadius = cornerRadius,
          colorFilter = colorFilter,
          alpha = alpha,
          blendMode = blendMode,
        )
      }
    }
}
