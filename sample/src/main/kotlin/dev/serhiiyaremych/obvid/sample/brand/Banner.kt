// Copyright 2026, Serhii Yaremych (desugar-64) and the Obvid project contributors
// SPDX-License-Identifier: Apache-2.0

package dev.serhiiyaremych.obvid.sample.brand

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontSynthesis
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.serhiiyaremych.obvid.sample.LocalDemoPalette
import dev.serhiiyaremych.obvid.sample.PolygonShape
import dev.serhiiyaremych.obvid.sample.ShadowImpl
import dev.serhiiyaremych.obvid.sample.discover.DiscoverPalette
import dev.serhiiyaremych.obvid.sample.discover.cardSurface
import dev.serhiiyaremych.obvid.sample.measuredDropShadow
import dev.serhiiyaremych.obvid.sample.measuredInnerShadow

/** The banner's logical size: GitHub's social-preview aspect, 2:1. */
val BannerWidth = 1280.dp
val BannerHeight = 640.dp

/** Horizontal margin of both halves: well inside the crops GitHub applies on some surfaces (about 40 dp). */
private val BannerMargin = 104.dp

/** Specimen tiles: side, gap (room for each tile's shadows), corner radius. */
private val TileSide = 124.dp
private val TileGap = 52.dp
private val TileShape = RoundedCornerShape(26.dp)

/**
 * README header and GitHub social preview, [BannerWidth] × [BannerHeight] on the light Discover page. Left: the
 * wordmark (the split ring as its O) and one line under it. Right: a 3 × 2 grid of shadow specimens without text,
 * each one capability, all drawn by Obvid: lift, well, a tinted shadow, a Material shape, a gradient rim, the page
 * extruded. Text is Roboto Flex (bundled), so the render does not depend on the device's fonts.
 */
@Composable
fun ObvidBanner(modifier: Modifier = Modifier) {
  val palette = DiscoverPalette.current
  Box(modifier.size(BannerWidth, BannerHeight).background(palette.colors.background)) {
    Column(Modifier.align(Alignment.CenterStart).padding(start = BannerMargin)) {
      ObvidWordmark(150.sp)
      Spacer(Modifier.height(10.dp))
      BasicText(
        "Faster shadows for Jetpack Compose",
        style = TextStyle(
          color = palette.colors.muted,
          fontFamily = ObvidTextFamily,
          fontWeight = FontWeight.Normal,
          fontSynthesis = FontSynthesis.None,
          fontSize = 30.sp,
          lineHeight = 38.sp,
        ),
        maxLines = 1,
      )
    }
    SpecimenGrid(Modifier.align(Alignment.CenterEnd).padding(end = BannerMargin))
  }
}

/** The six specimens, three per row, in reading order: lift, well, tinted; shape, rim, extruded. */
@Composable
private fun SpecimenGrid(modifier: Modifier) {
  Column(modifier, verticalArrangement = Arrangement.spacedBy(TileGap)) {
    Row(horizontalArrangement = Arrangement.spacedBy(TileGap)) {
      LiftTile()
      WellTile()
      TintedTile()
    }
    Row(horizontalArrangement = Arrangement.spacedBy(TileGap)) {
      ShapeTile()
      RimTile()
      ExtrudedTile()
    }
  }
}

private val Tile = Modifier.size(TileSide)

/** Lift: the white Discover card on its ambient and key drop shadows, with the top sheen. */
@Composable
private fun LiftTile() {
  Box(Tile.cardSurface(ShadowImpl.Cpu, DiscoverPalette.current, TileShape, rim = false))
}

/**
 * Well: the well tone carved into the page, a press-style inner shadow from the top (deeper than the app's, to read at
 * this size) and a light lower lip.
 */
@Composable
private fun WellTile() {
  val palette = DiscoverPalette.current
  val press = remember { Shadow(radius = 14.dp, color = Color.Black, offset = DpOffset(0.dp, 6.dp), alpha = 0.42f) }
  val lip = remember { Shadow(radius = 3.dp, color = Color.White, offset = DpOffset(0.dp, (-2).dp), alpha = 0.8f) }
  Box(
    Tile
      .background(palette.colors.well, TileShape)
      .measuredInnerShadow(TileShape, press, ShadowImpl.Cpu)
      .measuredInnerShadow(TileShape, lip, ShadowImpl.Cpu),
  )
}

/**
 * Coral #FF8A70 harmonized to the seed #4285F4 (Material Color Utilities' Blend.harmonize: #FE888E, HCT hue 16.3), at
 * HCT chroma 48 as the scene hues: tone 92 for the [TintedTile]'s fill (tone 30 on the dark palette) and tone 60 for
 * its shadow. Computed once with materialyoucolor (the Python port of Material Color Utilities), which reproduces the
 * palette's own coral scene hue (#FF6E6E to #FB6C94, tones 90 and 45) exactly.
 */
private val CoralFill = Color(0xFFFFE1E1)
private val CoralFillDark = Color(0xFF7E2931)
private val CoralShadow = Color(0xFFDE6F75)

/**
 * Tinted: Material 3 Expressive `Clover4Leaf` (the path route) in a pale coral, on a soft shadow of the same hue
 * rather than a dark one, under the faint top sheen. Kept light: the wordmark's ring is the banner's only strong
 * colour.
 */
@Composable
private fun TintedTile() {
  val palette = DiscoverPalette.current
  val impl = ShadowImpl.Cpu
  val shape = remember { PolygonShape("Clover4Leaf") }
  val tinted = remember { Shadow(radius = 24.dp, color = CoralShadow, offset = DpOffset(0.dp, 6.dp), alpha = 0.32f) }
  Box(
    Tile
      .measuredDropShadow(shape, tinted, impl)
      .background(if (LocalDemoPalette.current.dark) CoralFillDark else CoralFill, shape)
      .measuredInnerShadow(shape, palette.depth.sheen, impl),
  )
}

/** Shape: Material 3 Expressive `Cookie9Sided` in the card colour on the card's drop shadows (the path route). */
@Composable
private fun ShapeTile() {
  val shape = remember { PolygonShape("Cookie9Sided") }
  Box(Tile.cardSurface(ShadowImpl.Cpu, DiscoverPalette.current, shape, rim = false))
}

/**
 * Rim: a card lit all round its edge by a brush inner shadow sweeping through the seed's blue, the Android green and
 * the warm amber. These are the palette's vivid scene hues (the same harmonized sources as primary, tertiary and the
 * warm rim): the scheme's own tones of them are too dark to glow on white.
 */
@Composable
private fun RimTile() {
  val palette = DiscoverPalette.current
  val hues = LocalDemoPalette.current.sceneHues
  val glow = remember(hues) {
    val blue = hues[0].vivid
    Shadow(radius = 14.dp, brush = Brush.sweepGradient(listOf(blue, hues[2].vivid, hues[3].vivid, blue)), alpha = 0.5f)
  }
  Box(
    Tile
      .measuredDropShadow(TileShape, palette.depth.surface, ShadowImpl.Cpu)
      .measuredDropShadow(TileShape, palette.depth.lift, ShadowImpl.Cpu)
      .background(palette.colors.card, TileShape)
      .measuredInnerShadow(TileShape, glow, ShadowImpl.Cpu),
  )
}

/**
 * Extruded: the page itself pushed up, the raised counterpart of the [WellTile]. The tile takes the page color, so
 * only its two drop shadows draw it: a dark one (the shade) toward the bottom right and a white one toward the top
 * left, as if lit from there, under the faint top sheen. The dark one draws first, so the white one softens their
 * overlap at the two other corners. On the dark palette the white one is faint and the dark one deeper: a strong white
 * shadow on a dark page reads as a glow, not as light.
 */
@Composable
private fun ExtrudedTile() {
  val palette = DiscoverPalette.current
  val impl = ShadowImpl.Cpu
  val night = LocalDemoPalette.current.dark
  val dark = remember(palette, night) {
    Shadow(radius = 20.dp, color = palette.colors.shade, offset = DpOffset(8.dp, 8.dp), alpha = if (night) 0.6f else 0.25f)
  }
  val light = remember(night) {
    Shadow(radius = 20.dp, color = Color.White, offset = DpOffset((-8).dp, (-8).dp), alpha = if (night) 0.1f else 0.9f)
  }
  Box(
    Tile
      .measuredDropShadow(TileShape, dark, impl)
      .measuredDropShadow(TileShape, light, impl)
      .background(palette.colors.background, TileShape)
      .measuredInnerShadow(TileShape, palette.depth.sheen, impl),
  )
}

@Preview(name = "Banner", widthDp = 1280, heightDp = 640)
@Composable
private fun BannerPreview() = BrandTheme { ObvidBanner() }

/** Marks the social-preview crop (40 dp in from every edge) over the banner, for layout checks only. */
@Preview(name = "Banner, safe area", widthDp = 1280, heightDp = 640)
@Composable
private fun BannerSafeAreaPreview() = BrandTheme {
  Box {
    ObvidBanner()
    Box(
      Modifier
        .padding(40.dp)
        .size(BannerWidth - 80.dp, BannerHeight - 80.dp)
        .background(Color.Red.copy(alpha = 0.08f)),
    )
  }
}
