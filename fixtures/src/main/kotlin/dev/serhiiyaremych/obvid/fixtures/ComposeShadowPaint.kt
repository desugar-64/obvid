// Copyright 2026, Serhii Yaremych (desugar-64) and the Obvid project contributors
// SPDX-License-Identifier: Apache-2.0

package dev.serhiiyaremych.obvid.fixtures

import android.graphics.BlurMaskFilter
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.PaintingStyle
import androidx.compose.ui.graphics.nativePaint

/**
 * `Shadow.kt` `Paint.configureShadow` (Compose ui-graphics 1.13.0), with `Blur.android.kt`'s `setBlurFilter`
 * (`nativePaint.setMaskFilter`): every call overwrites color, blend mode, style and mask filter, nothing else (the
 * stroke width of an earlier call stays).
 */
internal fun Paint.configureShadow(
  color: Color = Color.Black,
  blendMode: BlendMode = BlendMode.SrcOver,
  blurFilter: BlurMaskFilter? = null,
  style: PaintingStyle = PaintingStyle.Fill,
): Paint {
  this.color = color
  this.blendMode = blendMode
  this.style = style
  nativePaint.setMaskFilter(blurFilter)
  return this
}

/** `Blur.android.kt` `BlurFilter(radius)`. */
internal fun BlurFilter(radius: Float) = BlurMaskFilter(radius, BlurMaskFilter.Blur.NORMAL)
