// Copyright 2026, Serhii Yaremych (desugar-64) and the Obvid project contributors
// SPDX-License-Identifier: Apache-2.0

//! Host tests for `shadow` (declared in shadow.rs as `mod tests`). The references are independent: a pixel-by-pixel
//! model of the clipped write, and for whole rebuilds the scalar port `skia::blur_a8` with `blit_mask_a8` (Skia's A8
//! srcover blit, checked against the device by `DropShadowExactnessTest`) into a zeroed bitmap of Compose's size.

use super::*;
use crate::test_support::{Rng, blit_mask_a8, reference_src_over};

/// Byte the tests put in padding and outside every rect that must stay untouched.
const SENTINEL: u8 = 0xA5;

/// A random `w × h` source with stride `stride` (padding = SENTINEL).
fn random_src(w: usize, h: usize, stride: usize, rng: &mut Rng) -> Vec<u8> {
    let mut v = vec![SENTINEL; stride * h.max(1)];
    for y in 0..h {
        for x in 0..w {
            v[y * stride + x] = rng.next() as u8;
        }
    }
    v
}

/// Pixel-by-pixel model of [`write_window`].
#[allow(clippy::too_many_arguments)]
fn model(
    dst: &mut [u8],
    stride: usize,
    src: &[u8],
    sw: usize,
    sh: usize,
    ss: usize,
    x: isize,
    y: isize,
    at: Placement,
    mode: Store,
) {
    let (cw, ch) = match mode {
        Store::Replace => (at.clear_w.max(at.used_w), at.clear_h.max(at.used_h)),
        Store::SourceOver => (at.used_w, at.used_h),
    };
    for dy in 0..ch {
        for dx in 0..cw {
            let inside_used = dx < at.used_w && dy < at.used_h;
            let (sx, sy) = (dx as isize - x, dy as isize - y);
            let inside_src = sx >= 0 && sy >= 0 && (sx as usize) < sw && (sy as usize) < sh;
            let p = &mut dst[dy * stride + dx];
            match mode {
                Store::Replace => {
                    *p = if inside_used && inside_src { src[sy as usize * ss + sx as usize] } else { 0 };
                }
                Store::SourceOver => {
                    if inside_used && inside_src {
                        *p = reference_src_over(*p as u32, src[sy as usize * ss + sx as usize] as u32) as u8;
                    }
                }
            }
        }
    }
}

#[test]
fn window_clipping_every_edge_and_stride() {
    let mut rng = Rng(0x1234_5678);
    // Bitmap capacity 37 × 29 with row padding (stride 48); used and clear rects inside it.
    let (cap_w, cap_h, stride) = (37usize, 29usize, 48usize);
    let mut cases = 0;
    for &(sw, sh) in &[(1usize, 1usize), (5, 3), (20, 20), (37, 29), (60, 50), (90, 7)] {
        let ss = sw + rng.below(5) as usize; // padded source rows too
        let src = random_src(sw, sh, ss, &mut rng);
        // Positions: fully left / above / right / below, touching each edge, straddling each edge, inside, covering.
        let xs = [-(sw as isize) - 3, -(sw as isize), -(sw as isize) + 1, -2, 0, 3, 30, 36, 37, 40];
        let ys = [-(sh as isize) - 3, -(sh as isize), -(sh as isize) + 1, -2, 0, 3, 22, 28, 29, 33];
        for &x in &xs {
            for &y in &ys {
                for mode in [Store::Replace, Store::SourceOver] {
                    let used_w = rng.range(0, 37) as usize;
                    let used_h = rng.range(0, 29) as usize;
                    let at = Placement {
                        mask_x: 0,
                        mask_y: 0,
                        used_w,
                        used_h,
                        clear_w: rng.range(0, 37) as usize,
                        clear_h: rng.range(0, 29) as usize,
                    };
                    let mut base = vec![SENTINEL; stride * cap_h];
                    for yy in 0..cap_h {
                        for xx in 0..cap_w {
                            base[yy * stride + xx] = rng.next() as u8; // stale pixels
                        }
                    }
                    let mut want = base.clone();
                    model(&mut want, stride, &src, sw, sh, ss, x, y, at, mode);
                    let mut got = base.clone();
                    let mut dst = A8Mut { pixels: &mut got, width: cap_w, height: cap_h, stride };
                    write_window(&mut dst, A8 { pixels: &src, width: sw, height: sh, stride: ss }, x, y, at, mode);
                    assert_eq!(got, want, "src {sw}×{sh} at ({x}, {y}) {at:?} {mode:?}");
                    // Padding bytes are never touched.
                    for yy in 0..cap_h {
                        assert!(got[yy * stride + cap_w..(yy + 1) * stride].iter().all(|&b| b == SENTINEL));
                    }
                    cases += 1;
                }
            }
        }
    }
    assert_eq!(cases, 6 * 10 * 10 * 2);
}

#[test]
fn replace_clears_clear_rect_and_leaves_the_rest() {
    let (cap_w, cap_h, stride) = (40usize, 30usize, 44usize);
    let src = vec![200u8; 10 * 10];
    let mut px = vec![7u8; stride * cap_h];
    let at = Placement { mask_x: 0, mask_y: 0, used_w: 20, used_h: 15, clear_w: 30, clear_h: 25 };
    let mut dst = A8Mut { pixels: &mut px, width: cap_w, height: cap_h, stride };
    write_window(&mut dst, A8 { pixels: &src, width: 10, height: 10, stride: 10 }, 15, -3, at, Store::Replace);
    for y in 0..cap_h {
        for x in 0..stride {
            let v = px[y * stride + x];
            let want = if x < 30 && y < 25 { if (15..20).contains(&x) && y < 7 { 200 } else { 0 } } else { 7 };
            assert_eq!(v, want, "({x}, {y})");
        }
    }
}

/// A soft-edged disc in a `w × h` mask with stride `stride` (padding = SENTINEL).
fn disc(w: usize, h: usize, stride: usize) -> Vec<u8> {
    let (cx, cy, r) = (w as f64 / 2.0, h as f64 / 2.0, w.min(h) as f64 / 2.0 - 1.5);
    let mut v = vec![SENTINEL; stride * h];
    for y in 0..h {
        for x in 0..w {
            let d = ((x as f64 + 0.5 - cx).powi(2) + (y as f64 + 0.5 - cy).powi(2)).sqrt();
            v[y * stride + x] = ((r - d + 0.5).clamp(0.0, 1.0) * 255.0).round() as u8;
        }
    }
    v
}

/// Tightly packed copy of a strided mask.
fn tight(src: &[u8], w: usize, h: usize, stride: usize) -> Vec<u8> {
    (0..h).flat_map(|y| src[y * stride..y * stride + w].iter().copied()).collect()
}

/// One mask of a rebuild: `w × h`, stride, unblurred origin in the bitmap.
#[derive(Clone, Copy, Debug)]
struct MaskSpec {
    w: usize,
    h: usize,
    stride: usize,
    x: isize,
    y: isize,
}

/// Reference: Compose's bitmap (`used_w × used_h`, zeroed) with every mask blurred by the scalar port and blitted by
/// Skia's A8 srcover blit at (origin − border).
fn reference(masks: &[(MaskSpec, Vec<u8>)], radius: f32, used_w: usize, used_h: usize) -> Vec<u8> {
    let sigma = skia::mask_blur_sigma_from_radius(radius);
    let mut out = vec![0u8; used_w * used_h];
    for (m, px) in masks {
        let b = skia::blur_a8(&tight(px, m.w, m.h, m.stride), m.w, m.h, m.w, sigma);
        let (bx, by) = (m.x - b.border_x as isize, m.y - b.border_y as isize);
        blit_mask_a8(&mut out, used_w, used_h, &b.pixels, b.width, b.height, bx, by);
    }
    out
}

/// Runs a rebuild through `builder` into the capacity plane `px` (stride `stride`), clear rect `clear`.
#[allow(clippy::too_many_arguments)]
fn rebuild(
    builder: &mut ShadowBuilder,
    masks: &[(MaskSpec, Vec<u8>)],
    radius: f32,
    px: &mut [u8],
    cap: (usize, usize),
    stride: usize,
    used: (usize, usize),
    clear: (usize, usize),
) {
    for (i, (m, mpx)) in masks.iter().enumerate() {
        let mut dst = A8Mut { pixels: px, width: cap.0, height: cap.1, stride };
        let at =
            Placement { mask_x: m.x, mask_y: m.y, used_w: used.0, used_h: used.1, clear_w: clear.0, clear_h: clear.1 };
        let mode = if i == 0 { Store::Replace } else { Store::SourceOver };
        builder.blur_into(A8 { pixels: mpx, width: m.w, height: m.h, stride: m.stride }, radius, &mut dst, at, mode);
    }
}

fn used_region(px: &[u8], stride: usize, used: (usize, usize)) -> Vec<u8> {
    tight(px, used.0, used.1, stride)
}

#[test]
fn border_matches_scalar_port() {
    for radius in [0.5f32, 1.0, 2.0, 2.625, 3.0, 10.5, 42.0, 63.0, 168.0, 400.0] {
        let sigma = skia::mask_blur_sigma_from_radius(radius);
        assert_eq!(border_for_radius(radius), skia::blur_a8(&[0], 1, 1, 1, sigma).border_x, "radius {radius}");
    }
}

#[test]
fn rebuild_matches_scalar_blur_and_skia_blit() {
    // Fill (Replace) and stroke-like ring (SourceOver), stale bitmap, padded strides, masks straddling every edge.
    let mut rng = Rng(0xC0FF_EE11);
    for radius in [1.5f32, 2.625, 10.5, 42.0, 63.0] {
        let border = border_for_radius(radius) as isize;
        for &(uw, uh) in &[(60usize, 40usize), (131, 131), (17, 90)] {
            let fill_w = uw + 2 * border.min(128) as usize; // mask bounds may reach min(border, 128) past the bitmap
            let fill = MaskSpec { w: fill_w.min(uw + 20), h: uh, stride: fill_w.min(uw + 20) + 3, x: -10, y: 0 };
            let ring = MaskSpec {
                w: uw / 2 + 1,
                h: uh / 2 + 1,
                stride: uw / 2 + 9,
                x: rng.range(-5, uw as isize / 2),
                y: rng.range(-5, uh as isize / 2),
            };
            let masks = vec![(fill, disc(fill.w, fill.h, fill.stride)), (ring, disc(ring.w, ring.h, ring.stride))];
            let cap = (uw + 32, uh + 32);
            let stride = cap.0 + 16;
            let mut px: Vec<u8> = (0..stride * cap.1).map(|_| rng.next() as u8).collect();
            let mut builder = ShadowBuilder::new();
            rebuild(&mut builder, &masks, radius, &mut px, cap, stride, (uw, uh), cap);
            assert_eq!(used_region(&px, stride, (uw, uh)), reference(&masks, radius, uw, uh), "r {radius} {uw}×{uh}");
            // The rest of the cleared capacity is zero.
            for y in 0..cap.1 {
                for x in 0..cap.0 {
                    if x >= uw || y >= uh {
                        assert_eq!(px[y * stride + x], 0, "({x}, {y}) outside used");
                    }
                }
            }
        }
    }
}

#[test]
fn one_builder_reused_up_and_down() {
    // One builder and one capacity plane through sizes and radii going up and down; each clear rect is the previous
    // used rect, as the Kotlin builder passes it.
    let mut rng = Rng(0x0BAD_F00D);
    let cap = (420usize, 400usize);
    let stride = cap.0 + 12;
    let mut px: Vec<u8> = (0..stride * cap.1).map(|_| rng.next() as u8).collect();
    let mut builder = ShadowBuilder::new();
    let mut prev = cap; // first rebuild: everything may be stale
    let seq = [
        (400usize, 380usize, 63.0f32),
        (50, 50, 2.625),
        (300, 120, 168.0),
        (90, 300, 10.5),
        (12, 12, 42.0),
        (410, 400, 1.0),
        (200, 200, 63.0),
    ];
    for &(uw, uh, radius) in &seq {
        let border = border_for_radius(radius).min(128) as isize;
        let w = (uw as isize + border).max(1) as usize;
        let h = (uh as isize / 2).max(1) as usize;
        let m = MaskSpec { w, h, stride: w + rng.below(9) as usize, x: -border / 2, y: uh as isize / 4 };
        let masks = vec![(m, disc(m.w, m.h, m.stride))];
        rebuild(&mut builder, &masks, radius, &mut px, cap, stride, (uw, uh), prev);
        assert_eq!(used_region(&px, stride, (uw, uh)), reference(&masks, radius, uw, uh), "{uw}×{uh} r {radius}");
        for y in 0..prev.1.max(uh) {
            for x in 0..prev.0.max(uw) {
                if x >= uw || y >= uh {
                    assert_eq!(px[y * stride + x], 0, "{uw}×{uh}: stale ({x}, {y})");
                }
            }
        }
        prev = (uw, uh);
    }
}

#[test]
fn empty_mask_replace_clears_and_src_over_is_noop() {
    let (cap_w, cap_h, stride) = (20usize, 10usize, 24usize);
    let mut px = vec![9u8; stride * cap_h];
    let mut builder = ShadowBuilder::new();
    let at = Placement { mask_x: 3, mask_y: 3, used_w: 12, used_h: 8, clear_w: 0, clear_h: 0 };
    let empty = A8 { pixels: &[], width: 0, height: 0, stride: 0 };
    let mut dst = A8Mut { pixels: &mut px, width: cap_w, height: cap_h, stride };
    builder.blur_into(empty, 10.0, &mut dst, at, Store::SourceOver);
    assert!(px.iter().all(|&b| b == 9));
    let mut dst = A8Mut { pixels: &mut px, width: cap_w, height: cap_h, stride };
    builder.blur_into(empty, 10.0, &mut dst, at, Store::Replace);
    for y in 0..cap_h {
        for x in 0..stride {
            assert_eq!(px[y * stride + x], if x < 12 && y < 8 { 0 } else { 9 }, "({x}, {y})");
        }
    }
}

/// σ < 2 with the whole blurred mask inside the used rect: Replace runs `small_blur` straight into the bitmap at its
/// stride, then a second mask is srcover'd onto it. Every byte of the bitmap (stale pixels, a clear rect larger than
/// the used rect, row padding) against the scalar port with the pixel-by-pixel [`model`].
#[test]
fn small_sigma_whole_mask_inside_used_rect() {
    let mut rng = Rng(0x5A11_0001);
    let mut builder = ShadowBuilder::new();
    for radius in [0.6f32, 1.5, 2.5] {
        let sigma = skia::mask_blur_sigma_from_radius(radius);
        assert!((1.0 / 3.0..2.0).contains(&sigma), "radius {radius}: sigma {sigma}");
        let b = border_for_radius(radius);
        for &(w, h) in &[(1usize, 1usize), (7, 3), (20, 33), (45, 17)] {
            let (used_w, used_h) = (w + 2 * b + 6, h + 2 * b + 4);
            let (mask_x, mask_y) = ((b + 2) as isize, (b + 1) as isize);
            let at = Placement { mask_x, mask_y, used_w, used_h, clear_w: used_w + 9, clear_h: used_h + 5 };
            let (cap_w, cap_h, stride) = (at.clear_w + 3, at.clear_h + 2, at.clear_w + 10);
            let mut got: Vec<u8> = (0..stride * cap_h).map(|_| 1 + rng.below(255) as u8).collect();
            let mut want = got.clone();
            for mode in [Store::Replace, Store::SourceOver] {
                let ms = w + rng.below(5) as usize;
                let mask = random_src(w, h, ms, &mut rng);
                let blurred = skia::blur_a8(&mask, w, h, ms, sigma);
                let (bx, by) = (mask_x - b as isize, mask_y - b as isize);
                model(
                    &mut want,
                    stride,
                    &blurred.pixels,
                    blurred.width,
                    blurred.height,
                    blurred.width,
                    bx,
                    by,
                    at,
                    mode,
                );
                let mut dst = A8Mut { pixels: &mut got, width: cap_w, height: cap_h, stride };
                builder.blur_into(A8 { pixels: &mask, width: w, height: h, stride: ms }, radius, &mut dst, at, mode);
                assert!(got == want, "radius {radius} {w}×{h} {mode:?}");
            }
        }
    }
}

/// A random `w × h` mask with stride `stride` (padding = SENTINEL): noise, blobs (anti-aliased rects and discs of
/// random levels over a 0 or 255 background, sometimes with a noise patch), flat 0 or flat 255.
fn fuzz_mask(w: usize, h: usize, stride: usize, rng: &mut Rng) -> Vec<u8> {
    let mut v = vec![SENTINEL; stride * h.max(1)];
    let kind = rng.below(6);
    let background = if kind == 3 || rng.below(5) == 0 { 255 } else { 0 };
    for y in 0..h {
        v[y * stride..y * stride + w].fill(if kind == 0 { 0 } else { background });
    }
    let unit = |rng: &mut Rng| rng.below(1 << 16) as f64 / (1 << 16) as f64;
    match kind {
        0 | 3 => {}
        1 => {
            for y in 0..h {
                for x in 0..w {
                    v[y * stride + x] = rng.next() as u8;
                }
            }
        }
        _ => {
            for _ in 0..1 + rng.below(4) {
                let (cx, cy) = (unit(rng) * w as f64, unit(rng) * h as f64);
                let (rx, ry) = ((0.05 + unit(rng)) * w as f64 / 2.0, (0.05 + unit(rng)) * h as f64 / 2.0);
                let level = if rng.below(2) == 0 { 255 - background } else { rng.next() as u8 };
                let disc = rng.below(2) == 0;
                for y in 0..h {
                    for x in 0..w {
                        // 2 × 2 supersampled coverage
                        let mut n = 0u32;
                        for s in 0..4 {
                            let (px, py) =
                                (x as f64 + 0.25 + 0.5 * (s % 2) as f64, y as f64 + 0.25 + 0.5 * (s / 2) as f64);
                            let (dx, dy) = ((px - cx) / rx.max(0.5), (py - cy) / ry.max(0.5));
                            if if disc { dx * dx + dy * dy <= 1.0 } else { dx.abs() <= 1.0 && dy.abs() <= 1.0 } {
                                n += 1;
                            }
                        }
                        let p = &mut v[y * stride + x];
                        *p = ((*p as u32 * (4 - n) + level as u32 * n + 2) / 4) as u8;
                    }
                }
            }
            if kind == 5 && w > 0 && h > 0 {
                let (x0, y0) = (rng.below(w as u32) as usize, rng.below(h as u32) as usize);
                for y in y0..(y0 + 1 + rng.below(h as u32 / 3 + 1) as usize).min(h) {
                    for x in x0..(x0 + 1 + rng.below(w as u32 / 3 + 1) as usize).min(w) {
                        v[y * stride + x] = rng.next() as u8;
                    }
                }
            }
        }
    }
    v
}

/// The direct route of [`ShadowBuilder::blur_window`] (the window blurred straight into the bitmap) against the
/// buffered one (the whole mask blurred by `blur_a8_with` into a buffer, then [`write_window`], which shares the
/// direct route's window and clear: this checks the window blur), and every case against the scalar port with the
/// pixel-by-pixel [`model`] (which checks the window and clear). Random masks (noise, blobs, flat; sizes 0..300;
/// strides wider than the width), radii for no blur, σ < 2 and σ 2..~40 (odd and even windows, both sides of the
/// i16/i32 switch), placements with the mask origin negative, zero and positive, used rects that clip the blurred
/// mask on every side, contain it, are empty or narrower than 16 px, clear rects up to larger than the used rect, both
/// modes onto a random non-zero bitmap with row padding, every interior-skip mode, one builder per mode reused across
/// all cases. Every byte of the bitmap is compared (padding and pixels outside the clear rect must be untouched).
#[test]
fn direct_route_fuzz_matches_buffered_and_scalar() {
    let mut rng = Rng(0xD1EC_7001);
    let skips = [InteriorSkip::Off, InteriorSkip::Auto, InteriorSkip::Always];
    let mut builders: [ShadowBuilder; 3] = Default::default();
    let mut counts = [0usize; 6]; // empty window, narrow window, clipped left/top, clipped right/bottom, replace, src-over
    for case in 0..1500 {
        let size = |rng: &mut Rng| match rng.below(6) {
            0 => rng.below(3) as usize,
            1 => 1 + rng.below(24) as usize,
            _ => rng.below(301) as usize,
        };
        let (w, h) = (size(&mut rng), size(&mut rng));
        let stride = w + rng.below(9) as usize;
        let src = fuzz_mask(w, h, stride, &mut rng);
        let radius = match case % 6 {
            0 => [0.0f32, 0.4, 1.0, 1.7, 2.5][rng.below(5) as usize], // no blur, σ < 2
            1 => 2.6 + rng.below(1000) as f32 * 0.03,                 // σ 2..~19
            _ => 2.6 + rng.below(1000) as f32 * 0.065,                // σ 2..~40
        };
        let sigma = skia::mask_blur_sigma_from_radius(radius);
        let b = border_for_radius(radius) as isize;
        let (bw, bh) = (w as isize + 2 * b, h as isize + 2 * b);
        let used = |rng: &mut Rng, n: isize| match rng.below(16) {
            0 => 0,
            1..=3 => 1 + rng.below(15) as usize,
            4..=6 => (n + rng.below(40) as isize) as usize,
            _ => rng.below(n as u32 + 30) as usize,
        };
        let (used_w, used_h) = (used(&mut rng, bw), used(&mut rng, bh));
        // The mask origin; the blurred mask starts at origin − border.
        let origin = |rng: &mut Rng, n: isize, used: usize| match rng.below(6) {
            0 => 0,
            1 => -rng.range(1, n + 10),       // negative, the blurred mask clipped or gone
            2 => b - rng.range(0, 2 * b + 3), // blurred mask around the bitmap's start
            3 => used as isize - n + b + rng.range(-12, 12), // blurred mask around the used rect's end
            4 => rng.range(0, used as isize + 8),
            _ => rng.range(-n, used as isize + n),
        };
        let (mask_x, mask_y) = (origin(&mut rng, bw, used_w), origin(&mut rng, bh, used_h));
        let clear = |rng: &mut Rng, used: usize| {
            if rng.below(3) == 0 { rng.below(used as u32 + 1) as usize } else { used + rng.below(25) as usize }
        };
        let (clear_w, clear_h) = (clear(&mut rng, used_w), clear(&mut rng, used_h));
        let (cap_w, cap_h) = (used_w.max(clear_w) + rng.below(4) as usize, used_h.max(clear_h) + rng.below(4) as usize);
        let bitmap_stride = cap_w + rng.below(13) as usize;
        let mode = if rng.below(2) == 0 { Store::Replace } else { Store::SourceOver };
        let at = Placement { mask_x, mask_y, used_w, used_h, clear_w, clear_h };
        let base: Vec<u8> = (0..bitmap_stride * cap_h).map(|_| 1 + rng.below(255) as u8).collect();

        let (bx, by) = (mask_x - b, mask_y - b);
        let x0 = bx.clamp(0, used_w as isize);
        let x1 = (bx + bw).clamp(0, used_w as isize);
        let y0 = by.clamp(0, used_h as isize);
        let y1 = (by + bh).clamp(0, used_h as isize);
        let window = w > 0 && h > 0 && x0 < x1 && y0 < y1;
        counts[0] += !window as usize;
        counts[1] += (window && x1 - x0 < 16) as usize;
        counts[2] += (window && bx < 0 && by < 0) as usize;
        counts[3] += (window && bx + bw > used_w as isize && by + bh > used_h as isize) as usize;
        counts[4 + mode as usize] += 1;

        let what = format!(
            "case {case}: {w}x{h}/{stride} r {radius} (sigma {sigma}, border {b}) {at:?} {mode:?} bitmap {cap_w}x{cap_h}/{bitmap_stride}"
        );
        let blurred = skia::blur_a8(&src, w, h, stride, sigma);
        let mut scalar = base.clone();
        model(
            &mut scalar,
            bitmap_stride,
            &blurred.pixels,
            blurred.width,
            blurred.height,
            blurred.width,
            bx,
            by,
            at,
            mode,
        );
        for (k, &skip) in skips.iter().enumerate() {
            let mut got = base.clone();
            let mut dst = A8Mut { pixels: &mut got, width: cap_w, height: cap_h, stride: bitmap_stride };
            builders[k].blur_window(A8 { pixels: &src, width: w, height: h, stride }, sigma, &mut dst, at, mode, skip);

            let blurred = blur::blur_a8_with(&src, w, h, stride, sigma, skip);
            let mut want = base.clone();
            let mut dst = A8Mut { pixels: &mut want, width: cap_w, height: cap_h, stride: bitmap_stride };
            write_window(
                &mut dst,
                A8 { pixels: &blurred.pixels, width: blurred.width, height: blurred.height, stride: blurred.width },
                bx,
                by,
                at,
                mode,
            );
            if got != want {
                let i = got.iter().zip(&want).position(|(a, b)| a != b).unwrap();
                let diff = got.iter().zip(&want).filter(|(a, b)| a != b).count();
                panic!(
                    "{what} {skip:?}: {diff} bytes differ from the buffered route, first at ({}, {}): got {} want {}",
                    i % bitmap_stride,
                    i / bitmap_stride,
                    got[i],
                    want[i]
                );
            }
            assert!(got == scalar, "{what} {skip:?}: differs from the scalar port");
            // Untouched: row padding, everything outside the clear rect (Replace) or the used rect (SourceOver).
            let (tw, th) =
                if mode == Store::Replace { (clear_w.max(used_w), clear_h.max(used_h)) } else { (used_w, used_h) };
            for y in 0..cap_h {
                for x in 0..bitmap_stride {
                    if x >= tw || y >= th {
                        assert_eq!(
                            got[y * bitmap_stride + x],
                            base[y * bitmap_stride + x],
                            "{what} {skip:?}: ({x}, {y}) touched"
                        );
                    }
                }
            }
        }
    }
    assert!(counts.iter().all(|&c| c >= 30), "coverage {counts:?}");
}

/// Once the builder's scratch has grown, [`ShadowBuilder::blur_into`] allocates nothing on any route: σ ≥ 2 into a
/// window clipped on every side (a shape, noise), a window narrower than 16, the window inside the used rect,
/// srcover; σ < 2; no blur; an empty mask (Replace only clears).
#[test]
fn blur_into_allocates_nothing_after_warm_up() {
    use crate::test_support::allocations;
    let mut rng = Rng(0x00A1_10C8);
    let shape = disc(525, 525, 531);
    let noise = random_src(97, 61, 100, &mut rng);
    let (cap_w, cap_h, stride) = (700usize, 700usize, 712usize);
    let mut px: Vec<u8> = (0..stride * cap_h).map(|_| rng.next() as u8).collect();
    let mut builder = ShadowBuilder::new();
    let place = |x: isize, y: isize, used: usize| Placement {
        mask_x: x,
        mask_y: y,
        used_w: used,
        used_h: used,
        clear_w: used,
        clear_h: used,
    };
    let cases: [(A8, f32, Placement, Store); 8] = [
        (A8 { pixels: &shape, width: 525, height: 525, stride: 531 }, 63.0, place(63, 63, 651), Store::Replace),
        (A8 { pixels: &shape, width: 525, height: 525, stride: 531 }, 63.0, place(63, 63, 651), Store::SourceOver),
        (A8 { pixels: &noise, width: 97, height: 61, stride: 100 }, 24.0, place(-5, 7, 90), Store::Replace),
        (A8 { pixels: &noise, width: 97, height: 61, stride: 100 }, 24.0, place(20, 20, 9), Store::SourceOver),
        (A8 { pixels: &noise, width: 97, height: 61, stride: 100 }, 10.5, place(80, 80, 400), Store::Replace),
        (A8 { pixels: &noise, width: 97, height: 61, stride: 100 }, 2.0, place(3, -4, 64), Store::Replace),
        (A8 { pixels: &noise, width: 97, height: 61, stride: 100 }, 0.0, place(3, 4, 64), Store::SourceOver),
        (A8 { pixels: &[], width: 0, height: 0, stride: 0 }, 10.0, place(3, 4, 64), Store::Replace),
    ];
    for (n, &(mask, radius, at, mode)) in cases.iter().enumerate() {
        let mut dst = A8Mut { pixels: &mut px, width: cap_w, height: cap_h, stride };
        builder.blur_into(mask, radius, &mut dst, at, mode); // warm-up
        let before = allocations();
        for _ in 0..3 {
            builder.blur_into(mask, radius, &mut dst, at, mode);
        }
        let count = allocations() - before;
        assert_eq!(
            count, 0,
            "case {n} ({}x{} r {radius} {at:?} {mode:?}): {count} allocations after warm-up",
            mask.width, mask.height
        );
    }
    // The counter works: a fresh builder grows its scratch.
    let before = allocations();
    let mut dst = A8Mut { pixels: &mut px, width: cap_w, height: cap_h, stride };
    ShadowBuilder::new().blur_into(cases[0].0, 63.0, &mut dst, cases[0].2, Store::Replace);
    assert!(allocations() > before);
}

/// [`invert_used`] inverts exactly the used rect of a strided plane: every byte inside is `255 − v`, row padding,
/// columns past the used width and rows below the used height keep their value; an empty used rect is a no-op;
/// inverting twice restores the plane.
#[test]
fn invert_used_inverts_only_the_used_rect() {
    let mut rng = Rng(0x1A7E_0001);
    for &(cap_w, cap_h, stride) in &[(1usize, 1usize, 1usize), (37, 29, 48), (16, 3, 16), (130, 9, 131), (64, 64, 77)] {
        for &(used_w, used_h) in
            &[(0usize, 0usize), (0, cap_h), (cap_w, 0), (1, 1), (cap_w, cap_h), (cap_w / 2, cap_h / 2 + 1), (cap_w, 1)]
        {
            let (used_w, used_h) = (used_w.min(cap_w), used_h.min(cap_h));
            let base: Vec<u8> = (0..stride * cap_h).map(|_| rng.next() as u8).collect();
            let mut got = base.clone();
            let mut dst = A8Mut { pixels: &mut got, width: cap_w, height: cap_h, stride };
            invert_used(&mut dst, used_w, used_h);
            for y in 0..cap_h {
                for x in 0..stride {
                    let i = y * stride + x;
                    let want = if x < used_w && y < used_h { 255 - base[i] } else { base[i] };
                    assert_eq!(got[i], want, "{cap_w}x{cap_h}/{stride} used {used_w}x{used_h}: ({x}, {y})");
                }
            }
            let mut dst = A8Mut { pixels: &mut got, width: cap_w, height: cap_h, stride };
            invert_used(&mut dst, used_w, used_h);
            assert_eq!(got, base);
        }
    }
}

/// An inner-shadow rebuild: Compose's inner bitmap is `255 − B`, B being the blurred shape (moved by the offset)
/// blitted into a zeroed bitmap of Compose's size. Masks at origins that put the blurred shape inside the bitmap,
/// across each edge and wholly outside it (B empty: all 255), mask bounds reaching past the bitmap by up to
/// min(border, 128) as Skia's mask bounds do, one builder reused on a stale plane larger than the used rect; Replace
/// then [`invert_used`] against the scalar port, blit and inversion. Every byte past the used rect keeps the cleared
/// value 0.
#[test]
fn inner_rebuild_is_255_minus_blurred_shape() {
    let mut rng = Rng(0x1AAE_5001);
    let mut builder = ShadowBuilder::new();
    let cap = (300usize, 260usize);
    let stride = cap.0 + 9;
    let mut px: Vec<u8> = (0..stride * cap.1).map(|_| rng.next() as u8).collect();
    let mut prev = cap;
    let mut all_255 = 0;
    for radius in [1.5f32, 2.625, 10.5, 42.0, 63.0, 262.5] {
        let border = border_for_radius(radius) as isize;
        let m = border.min(128);
        for &(uw, uh) in &[(126usize, 126usize), (211, 90), (17, 240)] {
            let (sw, sh) = (uw * 2 / 3 + 1, uh * 2 / 3 + 1);
            // Shape origins (the device position of its unblurred mask), as Skia's mask bounds clip them.
            for &(ox, oy) in &[
                (0isize, 0isize),
                (uw as isize / 5, uh as isize / 7),
                (-(sw as isize) / 2, 3),
                (uw as isize - 4, uh as isize / 3),
                (5, -(sh as isize) + 2),
                (uw as isize + border + 1, 0),
                (0, -(sh as isize) - border - 1),
            ] {
                // compute_mask_bounds: the shape's bounds clipped to the bitmap grown by m.
                let (l, t) = (ox.max(-m), oy.max(-m));
                let (r, b) = ((ox + sw as isize).min(uw as isize + m), (oy + sh as isize).min(uh as isize + m));
                let (mw, mh) = (
                    if l < r && t < b { (r - l) as usize } else { 0 },
                    if l < r && t < b { (b - t) as usize } else { 0 },
                );
                let mask_stride = mw + 3;
                let mask = if mw > 0 { disc(mw, mh, mask_stride) } else { Vec::new() };
                let mut dst = A8Mut { pixels: &mut px, width: cap.0, height: cap.1, stride };
                let at = Placement { mask_x: l, mask_y: t, used_w: uw, used_h: uh, clear_w: prev.0, clear_h: prev.1 };
                builder.blur_into(
                    A8 { pixels: &mask, width: mw, height: mh, stride: mask_stride },
                    radius,
                    &mut dst,
                    at,
                    Store::Replace,
                );
                invert_used(&mut dst, uw, uh);

                let mut want = if mw > 0 {
                    reference(
                        &[(MaskSpec { w: mw, h: mh, stride: mask_stride, x: l, y: t }, mask.clone())],
                        radius,
                        uw,
                        uh,
                    )
                } else {
                    vec![0u8; uw * uh]
                };
                for v in &mut want {
                    *v = 255 - *v;
                }
                let got = used_region(&px, stride, (uw, uh));
                assert_eq!(got, want, "r {radius} {uw}×{uh} shape at ({ox}, {oy})");
                all_255 += got.iter().all(|&v| v == 255) as usize;
                for y in 0..prev.1.max(uh) {
                    for x in 0..prev.0.max(uw) {
                        if x >= uw || y >= uh {
                            assert_eq!(px[y * stride + x], 0, "r {radius} {uw}×{uh}: ({x}, {y}) outside used");
                        }
                    }
                }
                prev = (uw, uh);
            }
        }
    }
    assert!(all_255 >= 10, "wholly outside cases: {all_255}");
}

/// A mask read from a window inside a larger plane (`A8::sub`, the cut of a mask drawn at device coordinates) blurs
/// and writes exactly as the same bytes copied into a tightly packed mask of their own, for windows touching every
/// edge of the plane, empty ones included.
#[test]
fn sub_window_blurs_as_its_copy() {
    let mut rng = Rng(0x5AB_0001);
    let (pw, ph, ps) = (90usize, 70usize, 101usize);
    let plane = random_src(pw, ph, ps, &mut rng);
    let source = A8 { pixels: &plane, width: pw, height: ph, stride: ps };
    let (cap_w, cap_h) = (120usize, 100usize);
    let mut a = vec![SENTINEL; cap_w * cap_h];
    let mut b = vec![SENTINEL; cap_w * cap_h];
    let mut via_sub = ShadowBuilder::new();
    let mut via_copy = ShadowBuilder::new();
    let mut cases = 0;
    for &(x, y, w, h) in &[
        (0usize, 0usize, 90usize, 70usize),
        (13, 7, 40, 30),
        (50, 40, 40, 30),
        (0, 69, 90, 1),
        (89, 0, 1, 70),
        (30, 70, 10, 0),
        (90, 10, 0, 5),
        (5, 5, 0, 0),
    ] {
        for &radius in &[1.0f32, 10.5, 63.0] {
            for mode in [Store::Replace, Store::SourceOver] {
                let at = Placement {
                    mask_x: x as isize - 17,
                    mask_y: y as isize + 3,
                    used_w: cap_w,
                    used_h: cap_h,
                    clear_w: cap_w,
                    clear_h: cap_h,
                };
                let cut = source.sub(x, y, w, h);
                let mut da = A8Mut { pixels: &mut a, width: cap_w, height: cap_h, stride: cap_w };
                via_sub.blur_into(cut, radius, &mut da, at, mode);
                let copy = tight(&plane[if w == 0 || h == 0 { 0 } else { y * ps + x }..], w, h, ps);
                let mut db = A8Mut { pixels: &mut b, width: cap_w, height: cap_h, stride: cap_w };
                via_copy.blur_into(
                    A8 { pixels: &copy, width: w, height: h, stride: w.max(1) },
                    radius,
                    &mut db,
                    at,
                    mode,
                );
                assert!(a == b, "window ({x}, {y}) {w}×{h}, radius {radius}, {mode:?}");
                cases += 1;
            }
        }
    }
    assert_eq!(cases, 48);
}
