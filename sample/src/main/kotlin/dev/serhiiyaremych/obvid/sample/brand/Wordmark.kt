// Copyright 2026, Serhii Yaremych (desugar-64) and the Obvid project contributors
// SPDX-License-Identifier: Apache-2.0

package dev.serhiiyaremych.obvid.sample.brand

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontSynthesis
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.serhiiyaremych.obvid.sample.R
import dev.serhiiyaremych.obvid.sample.discover.DiscoverPalette
import kotlin.math.roundToInt

// Roboto Flex (res/font/roboto_flex.ttf, SIL OFL 1.1, see LICENSES/OFL-1.1-RobotoFlex.txt), a variable font, so the
// brand text looks the same on every device. The metrics below were read from the font at opsz 144 with fontTools.

/** Weight of the wordmark: its stem (about 0.185 em at opsz 144) matches the ring's thickness (see [RingEm]). */
private const val DisplayWeight = 740

/** Roboto Flex at [DisplayWeight] and the display optical size, for the wordmark. */
val ObvidDisplayFamily = FontFamily(
  Font(
    R.font.roboto_flex,
    weight = FontWeight(DisplayWeight),
    variationSettings = FontVariation.Settings(FontVariation.weight(DisplayWeight), FontVariation.Setting("opsz", 144f)),
  ),
)

/** Roboto Flex at regular weight and a 30 pt optical size, for the banner's line of text. */
val ObvidTextFamily = FontFamily(
  Font(
    R.font.roboto_flex,
    weight = FontWeight.Normal,
    variationSettings = FontVariation.Settings(FontVariation.weight(400), FontVariation.Setting("opsz", 30f)),
  ),
)

/**
 * The ring's diameter, in em: the baseline-to-ascender span of "b" and "d" (0.7109 em) plus the O's overshoot below,
 * less a hair, so the ring is no taller than the letters' ascenders. A full O (0.7305 em) read heavier than the
 * letters. Its thickness, 0.25 × 0.715 = 0.179 em, sits just under the 0.185 em stem.
 */
private const val RingEm = 0.715f

/** How far the O dips below the baseline, in em: the ring's bottom sits there, as a round letter's would. */
private const val OvershootEm = 0.0098f

/**
 * Space between the ring and the text box, in em. The "b"'s left side bearing (0.0374 em) follows inside the text box,
 * so the ring's edge is 0.075 em from the "b"'s stem: the font's stem-to-stem spacing (an "l" has 0.0374 em on each
 * side), a little more than its O-to-b spacing (0.062 em), which with the full circle's weight read as touching.
 */
private const val RingGapEm = 0.0376f

/**
 * The wordmark "◯bvid": the split ring ([RingMark]) in place of the O, sized to the letters of Roboto Flex at
 * [fontSize] and sitting on the baseline, then "bvid" in [ObvidDisplayFamily].
 */
@Composable
fun ObvidWordmark(fontSize: TextUnit, modifier: Modifier = Modifier) {
  val palette = DiscoverPalette.current
  val density = LocalDensity.current
  val em = with(density) { fontSize.toDp() }
  val diameter = em * RingEm
  val overshoot = with(density) { (em * OvershootEm).toPx() }.roundToInt()
  Row(modifier) {
    RingMark(Modifier.alignBy { it.measuredHeight - overshoot }.size(diameter), u = ringUnit(diameter))
    Spacer(Modifier.width(em * RingGapEm))
    BasicText(
      "bvid",
      Modifier.alignBy(FirstBaseline),
      style = TextStyle(
        color = palette.colors.text,
        fontFamily = ObvidDisplayFamily,
        fontWeight = FontWeight(DisplayWeight),
        fontSynthesis = FontSynthesis.None,
        fontSize = fontSize,
      ),
      maxLines = 1,
    )
  }
}

/** The wordmark centred in its bounds at [fontSize] (300 sp for the 1200 × 400 render), on the page color with [page]. */
@Composable
fun ObvidWordmarkCard(page: Boolean, modifier: Modifier = Modifier, fontSize: TextUnit = 300.sp) {
  val palette = DiscoverPalette.current
  Box(
    modifier.fillMaxSize().then(if (page) Modifier.background(palette.colors.background) else Modifier),
    contentAlignment = Alignment.Center,
  ) {
    ObvidWordmark(fontSize)
  }
}

@Preview(name = "Wordmark", widthDp = 600, heightDp = 200)
@Composable
private fun WordmarkPreview() = BrandTheme {
  ObvidWordmarkCard(page = true, Modifier.size(600.dp, 200.dp), fontSize = 150.sp)
}
