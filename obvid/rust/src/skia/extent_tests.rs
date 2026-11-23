// Copyright 2026, Serhii Yaremych (desugar-64) and the Obvid project contributors
// SPDX-License-Identifier: Apache-2.0

//! Host tests for `skia::extent` (declared in extent.rs as `mod tests`): the margins against a brute-force 2D blur of
//! a few shapes, and their bounds.

use super::*;
use crate::skia::{blur_a8, mask_blur_sigma_from_radius};

/// Radii in px: the small-blur range (σ < 2), Compose radii at density 2.625 (1, 2, 4, 8, 12, 24, 36, 48 dp) and a few
/// others, fractional ones included.
fn radii() -> Vec<f32> {
    let mut r =
        vec![0.25f32, 0.5, 1.0, 1.5, 2.0, 2.6, 3.0, 3.3, 4.0, 5.0, 6.5, 7.0, 10.0, 13.7, 17.0, 29.9, 40.0, 55.5, 80.0];
    for dp in [1.0f32, 2.0, 4.0, 8.0, 12.0, 24.0, 36.0, 48.0] {
        r.push(dp * 2.625);
    }
    r
}

/// An A8 mask of `w × h` whose coverage at a pixel is the share of its 8 × 8 sub-samples for which `inside` holds
/// (rounded to 255ths), as an anti-aliased rasterizer gives.
fn raster(w: usize, h: usize, inside: impl Fn(f64, f64) -> bool) -> Vec<u8> {
    let mut m = vec![0u8; w * h];
    for y in 0..h {
        for x in 0..w {
            let mut n = 0u32;
            for sy in 0..8 {
                for sx in 0..8 {
                    if inside(x as f64 + (sx as f64 + 0.5) / 8.0, y as f64 + (sy as f64 + 0.5) / 8.0) {
                        n += 1;
                    }
                }
            }
            m[y * w + x] = ((n * 255 + 32) / 64) as u8;
        }
    }
    m
}

/// The test shapes on a `side × side` canvas, a margin of empty pixels around each: a rect with fractional edges, a
/// disc, a thin diagonal line (1.2 px wide) and a ring (4 px wide).
fn shapes(side: usize) -> Vec<(&'static str, Vec<u8>)> {
    let s = side as f64;
    let c = s / 2.0;
    vec![
        ("rect", raster(side, side, |x, y| x >= 3.3 && x < s - 4.6 && y >= 5.75 && y < s - 2.2)),
        ("disc", raster(side, side, |x, y| (x - c).hypot(y - c) <= c - 3.4)),
        (
            "line",
            raster(side, side, |x, y| {
                x > 3.0 && x < s - 3.0 && y > 3.0 && y < s - 3.0 && ((x - y) / 2f64.sqrt()).abs() <= 0.6
            }),
        ),
        (
            "ring",
            raster(side, side, |x, y| {
                let d = (x - c).hypot(y - c);
                d <= c - 2.7 && d >= c - 6.7
            }),
        ),
    ]
}

/// The pixel bounds `[x0, x1) × [y0, y1)` of the non-zero bytes of a `w × h` mask.
fn bounds(m: &[u8], w: usize, h: usize) -> (isize, isize, isize, isize) {
    let (mut x0, mut y0, mut x1, mut y1) = (isize::MAX, isize::MAX, isize::MIN, isize::MIN);
    for y in 0..h {
        for x in 0..w {
            if m[y * w + x] != 0 {
                x0 = x0.min(x as isize);
                y0 = y0.min(y as isize);
                x1 = x1.max(x as isize + 1);
                y1 = y1.max(y as isize + 1);
            }
        }
    }
    (x0, y0, x1, y1)
}

/// For every shape and radius, the whole blur (Skia's arithmetic, [`blur_a8`]) has no non-zero byte outside the
/// full-extent window (the mask's pixel bounds grown by [`full_extent_margin`]), and the window's outermost ring is all
/// 0.
#[test]
fn full_window_keeps_every_byte_and_ends_in_zeros() {
    for radius in radii() {
        let k = full_extent_margin(radius) as isize;
        let sigma = mask_blur_sigma_from_radius(radius);
        for side in [9usize, 40, 97] {
            for (name, mask) in shapes(side) {
                let (x0, y0, x1, y1) = bounds(&mask, side, side);
                let out = blur_a8(&mask, side, side, side, sigma);
                let (bx, by) = (out.border_x as isize, out.border_y as isize);
                // Window in source coordinates.
                let (wl, wt, wr, wb) = (x0 - k, y0 - k, x1 + k, y1 + k);
                let mut ring_nonzero = 0;
                let mut lost = 0;
                for oy in 0..out.height as isize {
                    for ox in 0..out.width as isize {
                        let v = out.pixels[(oy * out.width as isize + ox) as usize];
                        if v == 0 {
                            continue;
                        }
                        let (x, y) = (ox - bx, oy - by);
                        if x < wl || x >= wr || y < wt || y >= wb {
                            lost += 1;
                        } else if x == wl || x == wr - 1 || y == wt || y == wb - 1 {
                            ring_nonzero += 1;
                        }
                    }
                }
                let ctx = format!("{name} side {side} radius {radius} sigma {sigma} margin {k} border {bx}");
                assert_eq!(lost, 0, "bytes outside the window: {ctx}");
                assert_eq!(ring_nonzero, 0, "non-zero outer ring: {ctx}");
            }
        }
    }
}

/// The step response is non-increasing (the margin's first 0 stays 0) and ends within the border + 1.
#[test]
fn step_response_is_monotone_and_within_the_border() {
    for radius in radii() {
        let s = step_response(radius);
        assert!(s.windows(2).all(|p| p[0] >= p[1]), "radius {radius}: {s:?}");
        let border = crate::skia::blur_a8(&[255], 1, 1, 1, mask_blur_sigma_from_radius(radius)).border_x;
        assert_eq!(s.len(), border, "radius {radius}");
        assert!(full_extent_margin(radius) <= border + 1);
        assert!(full_extent_margin(radius) >= 1);
    }
    assert!(step_response(0.0).is_empty());
    assert_eq!(full_extent_margin(0.0), 1);
    assert_eq!(full_extent_margin(-3.0), 1);
}

/// The step response is the 2D blur's own edge: a wide rect's blur, along the middle of its right and left edges, gives
/// the same bytes (the second pass rounds a column of equal bytes back to that byte). So the margin is the smallest
/// for such a rect: one pixel less and the window loses the non-zero byte at distance margin − 1.
#[test]
fn step_response_matches_a_wide_rect_edge() {
    for radius in radii() {
        let sigma = mask_blur_sigma_from_radius(radius);
        let border = crate::skia::blur_a8(&[255], 1, 1, 1, sigma).border_x;
        // Wider and taller than the kernel: the middle row's edge sees only this edge.
        let (w, h) = (2 * border + 2, 2 * border + 10);
        let mask = vec![255u8; w * h];
        let out = blur_a8(&mask, w, h, w, sigma);
        let (bx, by) = (out.border_x, out.border_y);
        let row = by + h / 2;
        let edge: Vec<u8> = (1..=bx).map(|j| out.pixels[row * out.width + bx + w - 1 + j]).collect();
        let left: Vec<u8> = (1..=bx).map(|j| out.pixels[row * out.width + bx - j]).collect();
        assert_eq!(edge, step_response(radius), "radius {radius}");
        assert_eq!(left, step_response(radius), "radius {radius} (left side)");
        let k = full_extent_margin(radius);
        if k > 1 {
            assert_ne!(edge[k - 2], 0, "radius {radius}: margin {k} not the smallest");
        }
    }
}

/// The Skia-route margin covers every reach it names: the three-box border, the analytic rect blur's pad (+ 1 for the
/// rounding of its bounds), and the full-extent margin.
#[test]
fn skia_reach_margin_covers_skia_blurs() {
    let mut r = 0.05f32;
    while r < 400.0 {
        let sigma = sigma_from_radius(r);
        let m = skia_reach_margin(r);
        let border = crate::skia::blur_a8(&[255], 1, 1, 1, mask_blur_sigma_from_radius(r)).border_x;
        let pad = crate::math::ceilf(6.0 * sigma) as usize / 2;
        assert!(m > border, "radius {r}: margin {m}, border {border}");
        assert!(m > pad, "radius {r}: margin {m}, pad {pad}");
        assert!(m >= full_extent_margin(r));
        r *= 1.07;
    }
    assert_eq!(skia_reach_margin(0.0), 1);
}

/// The margins of a few Compose radii at density 2.625 (printed with `--nocapture`), and their ratio to the radius
/// and to Compose's own window (the radius).
#[test]
fn margins_of_compose_radii() {
    for dp in [4.0f32, 8.0, 12.0, 16.0, 24.0, 36.0, 48.0, 64.0] {
        let r = dp * 2.625;
        let k = full_extent_margin(r);
        let border = crate::skia::blur_a8(&[255], 1, 1, 1, mask_blur_sigma_from_radius(r)).border_x;
        println!(
            "{dp} dp = {r} px: full {k} px ({:.3}·r), skia route {} px, border {border} px ({:.3}·r), step at r: {:?}",
            k as f32 / r,
            skia_reach_margin(r),
            border as f32 / r,
            step_response(r).get(crate::math::ceilf(r) as usize - 1),
        );
        // Past Compose's window, short of Skia's border.
        assert!(k as f32 > r && k <= border);
    }
}
