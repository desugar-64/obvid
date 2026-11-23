// Copyright 2026, Serhii Yaremych (desugar-64) and the Obvid project contributors
// SPDX-License-Identifier: Apache-2.0

package dev.serhiiyaremych.obvid.sample.discover

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.serhiiyaremych.obvid.sample.LocalShadowImpl
import dev.serhiiyaremych.obvid.sample.measuredDropShadow
import dev.serhiiyaremych.obvid.sample.measuredInnerShadow
import kotlin.math.min
import kotlin.math.roundToInt

/** Size of a [PhotoSize] with a fixed width ([PhotoSize.Thumb], [PhotoSize.Small]). */
fun Modifier.photoSize(size: PhotoSize): Modifier = size(size.widthDp.dp, size.heightDp.dp)

/**
 * Photo [photo], pre-decoded at [photoSize], clipped to [shape] on a [DepthTokens.photo] shadow. A [hero] photo also
 * stacks [DepthTokens.surface] under it and lays the [DepthTokens.focus] rim over it. Without a decoded image
 * (previews) it shows a faint accent fill.
 */
@Composable
fun Photo(photo: Int, photoSize: PhotoSize, shape: Shape, modifier: Modifier, hero: Boolean = false) {
  val palette = DiscoverPalette.current
  val impl = LocalShadowImpl.current
  val image = DiscoverPhotos.image(photo, photoSize)
  Spacer(
    modifier
      .then(if (hero) Modifier.measuredDropShadow(shape, palette.depth.surface, impl) else Modifier)
      .measuredDropShadow(shape, palette.depth.photo, impl)
      .clip(shape)
      .drawBehind {
        if (image != null) drawCentreCropped(image) else drawRect(palette.brushes.accent, alpha = 0.35f)
      }
      .then(if (hero) Modifier.measuredInnerShadow(shape, palette.depth.focus, impl) else Modifier),
  )
}

/**
 * [image] filling this element, centre-cropped to its aspect ratio: unscaled when it was decoded at this size, as
 * every photo is for the layout it was decoded for; never stretched when the layout changed after decoding.
 */
private fun DrawScope.drawCentreCropped(image: ImageBitmap) {
  val dst = IntSize(size.width.roundToInt(), size.height.roundToInt())
  if (dst.width <= 0 || dst.height <= 0) return
  val scale = min(image.width / dst.width.toFloat(), image.height / dst.height.toFloat())
  val src = IntSize(min(image.width, (dst.width * scale).roundToInt()), min(image.height, (dst.height * scale).roundToInt()))
  drawImage(image, IntOffset((image.width - src.width) / 2, (image.height - src.height) / 2), src, dstSize = dst)
}

@Preview
@Composable
private fun PhotoPreview() = DiscoverPreview {
  Photo(0, PhotoSize.Thumb, DiscoverShapes.Photo, Modifier.photoSize(PhotoSize.Thumb), hero = true)
}
