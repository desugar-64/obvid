// Copyright 2026, Serhii Yaremych (desugar-64) and the Obvid project contributors
// SPDX-License-Identifier: Apache-2.0

package dev.serhiiyaremych.obvid.builder

import android.graphics.Bitmap
import android.graphics.PorterDuff
import dev.serhiiyaremych.obvid.ObvidNative
import dev.serhiiyaremych.obvid.TRACE_NINE_PATCH
import dev.serhiiyaremych.obvid.traceSection
import kotlin.math.ceil
import kotlin.math.floor

/** [ShadowBitmapBuilder.Route.NineRects]: Skia's rect nine-patch of the two nested rects of a canonical frame. */
internal class NineRectsRoute(
  private val native: NativeBuilder,
  private val mask: MaskBitmap,
  private val nested: NestedRects,
) {
  // The small frame's path.
  private val framePath = android.graphics.Path()

  /**
   * Skia's frame nine-patch (`filterRectsToNine` with two rects, then `draw_nine` without the centre) of [nested]'s
   * rects for a nine-patch [plan]: the small frame `smallR0 = (o.l, o.t, o.r − dx, o.b − dy)` minus `smallR1` (the
   * inner rect likewise), even-odd, anti-aliased, no mask filter, is drawn into the mask bitmap's `roundOut(smallR0)`
   * window under the translate `(−floor(o.l), −floor(o.t))`, as `draw_rects_into_mask` draws it (two contours of
   * `addRect`'s clockwise points from the top-left, `(l, t) (r, t) (r, b) (l, b)`, written as move, three lines and
   * close: an `addRect` path would take the platform's rect-region shortcut on API 23–33, see
   * [MaskRoute.setDropPath]; the same float translate, the mask rect as clip), then blurred and stretched into [target]
   * natively (`ObvidNative.builderNineRects`). With [inverted] (an inner shadow) every value v is written as `255 − v`.
   */
  fun build(target: Bitmap, w: Int, h: Int, radius: Float, plan: Long, inverted: Boolean) {
    traceSection(TRACE_NINE_PATCH) {
      val r = nested.rects
      val dx = ObvidNative.planRectsDx(plan).toFloat()
      val dy = ObvidNative.planRectsDy(plan).toFloat()
      val right0 = r[2] - dx
      val bottom0 = r[3] - dy
      val maskLeft = floor(r[0]).toInt()
      val maskTop = floor(r[1]).toInt()
      val sw = ceil(right0).toInt() - maskLeft
      val sh = ceil(bottom0).toInt() - maskTop
      val maskBitmap = mask.ensure(sw, sh)
      framePath.rewind()
      framePath.addClockwiseRect(r[0], r[1], right0, bottom0)
      framePath.addClockwiseRect(r[4], r[5], r[6] - dx, r[7] - dy)
      framePath.fillType = android.graphics.Path.FillType.EVEN_ODD
      val maskCanvas = mask.canvas
      maskCanvas.save()
      maskCanvas.clipRect(0, 0, sw, sh)
      maskCanvas.drawColor(0, PorterDuff.Mode.CLEAR)
      maskCanvas.translate(-maskLeft.toFloat(), -maskTop.toFloat())
      maskCanvas.drawPath(framePath, mask.paint)
      maskCanvas.restore()
      // The bitmap is exactly the used size: the clear rect is the used rect.
      val status =
        ObvidNative.builderNineRects(
          native.handle, maskBitmap, r[0], r[1], r[2], r[3], r[4], r[5], r[6], r[7], radius, target, w, h, w, h, inverted,
        )
      check(status == 0) { "builderNineRects failed: $status" }
    }
  }
}
