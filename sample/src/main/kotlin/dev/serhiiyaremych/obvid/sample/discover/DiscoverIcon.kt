// Copyright 2026, Serhii Yaremych (desugar-64) and the Obvid project contributors
// SPDX-License-Identifier: Apache-2.0

package dev.serhiiyaremych.obvid.sample.discover

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

/** Line icons in a 24 × 24 viewport, as SVG path data; [filled] ones are filled, the others stroked. */
enum class DiscoverIcon(private val data: String, val filled: Boolean = false) {
  Search("M4 10.5 A6.5 6.5 0 1 1 17 10.5 A6.5 6.5 0 1 1 4 10.5 Z M15.6 15.6 L20 20"),
  Bell("M6 16.5 V11 A6 6 0 0 1 18 11 V16.5 L19.5 18 H4.5 Z M10 20.5 A2 2 0 0 0 14 20.5"),
  Bookmark("M7 3.5 H17 A1 1 0 0 1 18 4.5 V20.5 L12 16.5 L6 20.5 V4.5 A1 1 0 0 1 7 3.5 Z"),
  BookmarkFilled("M7 3.5 H17 A1 1 0 0 1 18 4.5 V20.5 L12 16.5 L6 20.5 V4.5 A1 1 0 0 1 7 3.5 Z", filled = true),
  Play("M8.5 5.5 L19 12 L8.5 18.5 Z", filled = true),
  Home("M3.5 11 L12 3.8 L20.5 11 V20.5 H14.6 V14.8 H9.4 V20.5 H3.5 Z", filled = true),
  HomeOutline("M4 11 L12 4.2 L20 11 V20 H14.5 V14.5 H9.5 V20 H4 Z"),
  Compass("M3 12 A9 9 0 1 1 21 12 A9 9 0 1 1 3 12 Z M15.5 8.5 L13.5 13.5 L8.5 15.5 L10.5 10.5 Z"),
  Profile("M8 7.8 A4 4 0 1 1 16 7.8 A4 4 0 1 1 8 7.8 Z M4.5 20.5 A7.5 7 0 0 1 19.5 20.5"),
  Mountain("M2 19.5 L9 8.5 L13 14.5 L15.6 11.2 L22 19.5 Z", filled = true),
  MountainOutline("M2.5 19 L9 9 L13 15 L15.5 12 L21.5 19 Z"),
  Back("M15 5 L8 12 L15 19"),
  Leaf("M5 19 C5 10 11 5 20 4 C19 13 14 19 5 19 Z M5 19 L13 11"),
  Layers("M12 4 L21 8.5 L12 13 L3 8.5 Z M3 12.5 L12 17 L21 12.5 M3 16.5 L12 21 L21 16.5"),
  Spark("M12 3 L14 10 L21 12 L14 14 L12 21 L10 14 L3 12 L10 10 Z"),
  Bulb("M9 17.5 H15 M10 20.5 H14 M12 3 A6 6 0 0 0 8.2 13.6 C9 14.4 9 15.3 9 16 H15 C15 15.3 15 14.4 15.8 13.6 A6 6 0 0 0 12 3 Z"),
  Pulse("M2.5 12.5 H7 L9.5 6 L14 19 L16.5 12.5 H21.5"),
  Shapes("M12 3.5 L16.6 10.5 H7.4 Z M4 13.8 H10.2 V20 H4 Z M13.8 16.9 A3.2 3.2 0 1 1 20.2 16.9 A3.2 3.2 0 1 1 13.8 16.9 Z"),
  Gear(
    "M10.3 5.42 L10.51 3.12 A9 9 0 0 1 13.49 3.12 L13.7 5.42 A6.8 6.8 0 0 1 15.45 6.14 L17.23 4.67 A9 9 0 " +
      "0 1 19.33 6.77 L17.86 8.55 A6.8 6.8 0 0 1 18.58 10.3 L20.88 10.51 A9 9 0 0 1 20.88 13.49 L18.58 13.7 " +
      "A6.8 6.8 0 0 1 17.86 15.45 L19.33 17.23 A9 9 0 0 1 17.23 19.33 L15.45 17.86 A6.8 6.8 0 0 1 13.7 " +
      "18.58 L13.49 20.88 A9 9 0 0 1 10.51 20.88 L10.3 18.58 A6.8 6.8 0 0 1 8.55 17.86 L6.77 19.33 A9 9 0 0 " +
      "1 4.67 17.23 L6.14 15.45 A6.8 6.8 0 0 1 5.42 13.7 L3.12 13.49 A9 9 0 0 1 3.12 10.51 L5.42 10.3 A6.8 " +
      "6.8 0 0 1 6.14 8.55 L4.67 6.77 A9 9 0 0 1 6.77 4.67 L8.55 6.14 A6.8 6.8 0 0 1 10.3 5.42 Z M9.2 12 " +
      "A2.8 2.8 0 1 1 14.8 12 A2.8 2.8 0 1 1 9.2 12 Z",
  ),
  Sun(
    "M8 12 A4 4 0 1 1 16 12 A4 4 0 1 1 8 12 Z " +
      "M19 12 H21 M16.95 16.95 L18.36 18.36 M12 19 V21 M7.05 16.95 L5.64 18.36 M5 12 H3 M7.05 7.05 L5.64 5.64 M12 5 V3 M16.95 7.05 L18.36 5.64",
  );

  /** The parsed path, in viewport units. */
  val path: Path by lazy { PathParser().parsePathString(data).toPath() }
}

/** [icon] scaled to this element's size. [color] and [alpha] are read in the draw phase; the path is cached per size. */
@Composable
fun LineIcon(icon: DiscoverIcon, modifier: Modifier, color: () -> Color, alpha: () -> Float = { 1f }) {
  Spacer(
    modifier.drawWithCache {
      val scale = size.minDimension / 24f
      val path = Path().apply {
        addPath(icon.path)
        transform(Matrix().apply { scale(scale, scale) })
      }
      val style = if (icon.filled) Fill else Stroke(1.8f * scale, cap = StrokeCap.Round, join = StrokeJoin.Round)
      onDrawBehind {
        val a = alpha()
        if (a > 0f) drawPath(path, color(), alpha = a, style = style)
      }
    },
  )
}

@Preview
@Composable
private fun IconsPreview() = DiscoverPreview {
  FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
  val palette = DiscoverPalette.current
    DiscoverIcon.entries.forEach { LineIcon(it, Modifier.size(24.dp), color = { palette.colors.text }) }
  }
}
