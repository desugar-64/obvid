// Copyright 2026, Serhii Yaremych (desugar-64) and the Obvid project contributors
// SPDX-License-Identifier: Apache-2.0

package dev.serhiiyaremych.obvid.fixtures

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isSimple
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageBitmapConfig
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.PaintingStyle
import androidx.compose.ui.graphics.Path
import kotlin.math.ceil
import androidx.compose.ui.graphics.Canvas as ComposeCanvas

/**
 * What Compose draws for an outline (`ShadowRenderer.updateParamsFromOutline`): [path] for a generic outline or a
 * non-simple round rect (`Outline.Rounded.roundRectPath`), otherwise null and a round rect with [cornerRadius]
 * (zero for a rectangle).
 */
class OutlineParams(val path: Path?, val cornerRadius: CornerRadius)

fun outlineParams(outline: Outline): OutlineParams =
  when (outline) {
    is Outline.Generic -> OutlineParams(outline.path, CornerRadius.Zero)
    is Outline.Rounded ->
      if (outline.roundRect.isSimple) OutlineParams(null, outline.roundRect.topLeftCornerRadius)
      else OutlineParams(Path().apply { addRoundRect(outline.roundRect) }, CornerRadius.Zero)
    is Outline.Rectangle -> OutlineParams(null, CornerRadius.Zero)
  }

/**
 * Reference A of the exactness tests (`DropShadowExactnessTest`, `ShadowBitmapBuilderTest`):
 * `DropShadowRenderer.createOuterShadowBitmap` (Compose ui-graphics 1.13.0, both overloads), copied line for line,
 * with the same public calls and `Paint` configuration (`configureShadow`). A fresh exact-size bitmap per call, as
 * Compose allocates it.
 */
object ComposeShadowReference {

  /** [outlineParams], then the matching `createOuterShadowBitmap` overload. */
  fun referenceA(outline: Outline, size: Size, radius: Float, spread: Float): ImageBitmap {
    val params = outlineParams(outline)
    val path = params.path
    return if (path != null) refAPath(size, path, radius, spread) else refARoundRect(size, radius, spread, params.cornerRadius)
  }

  fun refAPath(size: Size, path: Path, radius: Float, spread: Float): ImageBitmap {
    val paint = Paint()
    val outset = radius * 2 + spread * 2
    val shadowWidth = size.width + outset
    val shadowHeight = size.height + outset
    val shadowBitmap =
      ImageBitmap(ceil(shadowWidth).toInt(), ceil(shadowHeight).toInt(), ImageBitmapConfig.Alpha8)
    val shadowCanvas = ComposeCanvas(shadowBitmap)
    with(shadowCanvas) {
      if (spread > 0f) {
        translate(radius + spread, radius + spread)
        drawPath(path, paint.configureShadow(blurFilter = if (radius > 0) BlurFilter(radius) else null))
        drawPath(
          path,
          paint
            .configureShadow(
              style = PaintingStyle.Stroke,
              blurFilter = if (radius > 0) BlurFilter(radius) else null,
            )
            .apply { strokeWidth = spread * 2f },
        )
      } else {
        paint.configureShadow(blurFilter = if (radius > 0) BlurFilter(radius) else null)
        translate(radius, radius)
        drawPath(path, paint)
      }
    }
    return shadowBitmap
  }

  fun refARoundRect(size: Size, shadowRadius: Float, spread: Float, cornerRadius: CornerRadius): ImageBitmap {
    val paint = Paint()
    val outset = shadowRadius * 2 + spread * 2
    val shadowWidth = size.width + outset
    val shadowHeight = size.height + outset
    val shadowBitmap =
      ImageBitmap(ceil(shadowWidth).toInt(), ceil(shadowHeight).toInt(), ImageBitmapConfig.Alpha8)
    val shadowCanvas = ComposeCanvas(shadowBitmap)
    with(shadowCanvas) {
      drawRoundRect(
        shadowRadius,
        shadowRadius,
        shadowWidth - shadowRadius,
        shadowHeight - shadowRadius,
        cornerRadius.x,
        cornerRadius.y,
        paint.configureShadow(blurFilter = if (shadowRadius > 0) BlurFilter(shadowRadius) else null),
      )
    }
    return shadowBitmap
  }
}
