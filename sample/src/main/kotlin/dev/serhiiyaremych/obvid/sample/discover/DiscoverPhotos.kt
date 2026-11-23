// Copyright 2026, Serhii Yaremych (desugar-64) and the Obvid project contributors
// SPDX-License-Identifier: Apache-2.0

package dev.serhiiyaremych.obvid.sample.discover

import android.content.Context
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.Trace
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.Dp
import dev.serhiiyaremych.obvid.sample.R
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max
import kotlin.math.roundToInt

/** The sizes a Discover photo is drawn at; [Wide] spans the screen width less [wideInset]. */
enum class PhotoSize(val widthDp: Float, val heightDp: Float) {
  Thumb(120f, 148f),
  Small(76f, 76f),
  Wide(0f, 190f);

  companion object {
    /** Horizontal space around a [Wide] photo: the screen [margin] and [HeroCardPadding] on each side. */
    fun wideInset(margin: Dp): Dp = (margin + HeroCardPadding) * 2
  }
}

/** The CC0 photos of the Discover feed (sources in PHOTO_CREDITS.md), in [DiscoverPhotos] index order. */
val PhotoResources = intArrayOf(
  R.drawable.photo_matterhorn,
  R.drawable.photo_aurora,
  R.drawable.photo_leaf_drops,
  R.drawable.photo_leaf_dew,
  R.drawable.photo_dunes_dusk,
  R.drawable.photo_dunes_namib,
  R.drawable.photo_alps_dawn,
  R.drawable.photo_coast_rocks,
  R.drawable.photo_coast_bay,
  R.drawable.photo_forest_creek,
  R.drawable.photo_forest_slope,
)

/**
 * Every photo at every [PhotoSize], decoded once per process before the Discover screens show: each image is decoded
 * straight to its drawn pixel size (scaled and center-cropped by the decoder) into a hardware bitmap, so the texture is
 * uploaded here and scrolling only draws it. Decoding runs on a small pool off the main thread; [ready] turns true on
 * the main thread when all are done. Previews never load them ([image] is null there).
 */
object DiscoverPhotos {
  var ready by mutableStateOf(false)
    private set

  private var started = false
  private lateinit var images: Array<Array<ImageBitmap?>>

  /** Photo [photo] at [size], or null before [ready]. */
  fun image(photo: Int, size: PhotoSize): ImageBitmap? = if (ready) images[size.ordinal][photo] else null

  /**
   * Starts decoding, once per process; later calls do nothing. [margin] is the screen margin the [PhotoSize.Wide]
   * photos are laid out with, which sets their width.
   */
  fun load(context: Context, margin: Dp) {
    if (started) return
    started = true
    val resources = context.applicationContext.resources
    val metrics = resources.displayMetrics
    val density = metrics.density
    val screenWidth = metrics.widthPixels
    images = Array(PhotoSize.entries.size) { arrayOfNulls(PhotoResources.size) }
    val jobs = PhotoSize.entries.flatMap { size -> PhotoResources.indices.map { size to it } }
    val left = AtomicInteger(jobs.size)
    val pool = Executors.newFixedThreadPool(4)
    val main = Handler(Looper.getMainLooper())
    for ((size, photo) in jobs) {
      pool.execute {
        val width = if (size == PhotoSize.Wide) screenWidth - (PhotoSize.wideInset(margin).value * density).roundToInt()
        else (size.widthDp * density).roundToInt()
        val height = (size.heightDp * density).roundToInt()
        Trace.beginSection("demo:decodePhoto")
        try {
          images[size.ordinal][photo] = decode(resources, PhotoResources[photo], width, height).asImageBitmap()
        } finally {
          Trace.endSection()
        }
        if (left.decrementAndGet() == 0) {
          pool.shutdown()
          main.post { ready = true }
        }
      }
    }
  }

  /** The resource scaled to cover [width] × [height] and center-cropped to it. */
  private fun decode(resources: Resources, res: Int, width: Int, height: Int): Bitmap {
    val source = ImageDecoder.createSource(resources, res)
    return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
      val scale = max(width.toFloat() / info.size.width, height.toFloat() / info.size.height)
      val scaledWidth = max(width, (info.size.width * scale).roundToInt())
      val scaledHeight = max(height, (info.size.height * scale).roundToInt())
      decoder.setTargetSize(scaledWidth, scaledHeight)
      val x = (scaledWidth - width) / 2
      val y = (scaledHeight - height) / 2
      decoder.crop = Rect(x, y, x + width, y + height)
      decoder.allocator = ImageDecoder.ALLOCATOR_HARDWARE
    }
  }
}
