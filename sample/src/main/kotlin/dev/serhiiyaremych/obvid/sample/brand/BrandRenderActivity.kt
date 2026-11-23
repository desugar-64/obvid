// Copyright 2026, Serhii Yaremych (desugar-64) and the Obvid project contributors
// SPDX-License-Identifier: Apache-2.0

package dev.serhiiyaremych.obvid.sample.brand

import android.graphics.Bitmap
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import dev.serhiiyaremych.obvid.sample.DemoPalette
import dev.serhiiyaremych.obvid.sample.LocalDemoPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** One PNG to render: [content] laid out at exactly [width] × [height] pixels with [density] pixels per dp. */
private class BrandRender(val file: String, val width: Int, val height: Int, val density: Float, val content: @Composable () -> Unit)

/**
 * Every brand image. The logo and wordmark scale to any size (density 1 is arbitrary); the banner is 1280 × 640 dp at
 * 1× and 2×, on the light Discover palette and, for the README's dark theme, on the dark one.
 */
private val BrandRenders = listOf(
  BrandRender("logo_512.png", 512, 512, 1f) { ObvidLogo(page = true) },
  BrandRender("logo_transparent_512.png", 512, 512, 1f) { ObvidLogo() },
  BrandRender("logo_avatar_512.png", 512, 512, 1f) { ObvidLogo(page = true, avatar = true) },
  BrandRender("logo_32.png", 32, 32, 1f) { ObvidLogo() },
  BrandRender("wordmark_1200x400.png", 1200, 400, 1f) { ObvidWordmarkCard(page = true) },
  BrandRender("wordmark_transparent_1200x400.png", 1200, 400, 1f) { ObvidWordmarkCard(page = false) },
  BrandRender("banner_1280x640.png", 1280, 640, 1f) { ObvidBanner() },
  BrandRender("banner_2560x1280.png", 2560, 1280, 2f) { ObvidBanner() },
  BrandRender("banner_dark_1280x640.png", 1280, 640, 1f) { DarkPalette { ObvidBanner() } },
  BrandRender("banner_dark_2560x1280.png", 2560, 1280, 2f) { DarkPalette { ObvidBanner() } },
)

/** [content] on the dark demo palette; [BrandTheme] provides the light one. */
@Composable
private fun DarkPalette(content: @Composable () -> Unit) =
  CompositionLocalProvider(LocalDemoPalette provides DemoPalette.Dark, content = content)

/**
 * Renders the brand art ([ObvidLogo], [ObvidWordmarkCard], [ObvidBanner]) to PNGs at exact pixel sizes, one at a
 * time, then finishes. Each image is laid out at its own size (larger than the screen is fine), recorded into a
 * graphics layer and read back from it, so the window, the screen density and the system bars play no part; the density and font scale are fixed per image, and text is the bundled Roboto Flex, not the device's fonts.
 * Clears the PNGs of earlier runs, writes to the app's external files dir and logs one line per file (tag
 * `ObvidBrand`).
 *
 *     adb shell am start -W -n dev.serhiiyaremych.obvid.sample/.brand.BrandRenderActivity
 *     adb logcat -d -s ObvidBrand            # one line per file, then "done"
 *     adb pull /sdcard/Android/data/dev.serhiiyaremych.obvid.sample/files/brand/ .
 *
 * Exported but guarded by `android.permission.DUMP`, which the shell holds and apps cannot, so only adb starts it.
 */
class BrandRenderActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    val dir = File(getExternalFilesDir(null), "brand").apply { mkdirs() }
    dir.listFiles { file -> file.name.endsWith(".png") }?.forEach { it.delete() }
    setContent { BrandRenderer(dir, onDone = ::finish) }
  }
}

@Composable
private fun BrandRenderer(dir: File, onDone: () -> Unit) {
  var index by remember { mutableIntStateOf(0) }
  val layer = rememberGraphicsLayer()
  // Index of the render last recorded into the layer; written in the draw phase, so not state.
  val recorded = remember { intArrayOf(-1) }
  val render = BrandRenders[index]
  val current = index
  CompositionLocalProvider(LocalDensity provides Density(render.density, fontScale = 1f), LocalLayoutDirection provides LayoutDirection.Ltr) {
    BrandTheme {
      Box(
        Modifier
          .exactPixels(render.width, render.height)
          .drawWithContent {
            layer.record { this@drawWithContent.drawContent() }
            recorded[0] = current
            drawLayer(layer)
          },
      ) {
        key(current) { render.content() }
      }
    }
  }
  LaunchedEffect(current) {
    while (recorded[0] != current) withFrameNanos {}
    // The content is static, so one more frame settles nothing new; it only makes sure the record is complete.
    withFrameNanos {}
    val image = layer.toImageBitmap().asAndroidBitmap()
    val file = File(dir, render.file)
    val line = withContext(Dispatchers.IO) {
      val pixels = image.copy(Bitmap.Config.ARGB_8888, false)
      file.outputStream().use { pixels.compress(Bitmap.CompressFormat.PNG, 100, it) }
      val ok = pixels.width == render.width && pixels.height == render.height
      "${if (ok) "wrote" else "SIZE MISMATCH"} ${file.absolutePath} ${pixels.width}x${pixels.height}"
    }
    Log.i(Tag, line)
    if (current + 1 < BrandRenders.size) index = current + 1
    else {
      Log.i(Tag, "done: ${BrandRenders.size} files in ${dir.absolutePath}")
      onDone()
    }
  }
}

/** Measures the content at exactly [width] × [height] pixels, whatever the parent allows, and places it at the origin. */
private fun Modifier.exactPixels(width: Int, height: Int): Modifier = layout { measurable, _ ->
  val placeable = measurable.measure(Constraints.fixed(width, height))
  layout(width, height) { placeable.place(0, 0) }
}

private const val Tag = "ObvidBrand"
