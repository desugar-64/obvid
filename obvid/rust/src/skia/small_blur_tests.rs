// Copyright 2026, Serhii Yaremych (desugar-64) and the Obvid project contributors
// SPDX-License-Identifier: Apache-2.0

//! Host tests for `skia::small_blur` (declared in small_blur.rs as `mod tests`).

use super::*;
use crate::skia::blur_a8;
use crate::test_support::{Rng, random_mask};

/// Exact u16 tables from Skia's own SkGaussFilter.cpp (google/skia@81ee7327e81c23b8bcf815ea6c353457cf706716) compiled
/// on the host with clang -ffp-contract=off, through prepareGauss's expression.
#[test]
fn gauss_factor_tables() {
    let cases: &[(f64, &[u16])] = &[
        (0.5, &[52510, 6513]),
        (1.07735, &[28150, 14087, 3876, 730]),
        (1.5, &[19276, 14165, 6685, 2280]),
        (1.99, &[13997, 12065, 7904, 4082, 1720]),
    ];
    for &(sigma, want) in cases {
        let f = SmallBlurFactors::new(sigma);
        assert_eq!(f.radius + 1, want.len(), "sigma {sigma}");
        assert_eq!(&f.factors[..want.len()], want, "sigma {sigma}");
    }
}

#[test]
fn gauss_filter_properties() {
    let mut last_radius = 0;
    for i in 0..2000 {
        let sigma = 1.0 / 3.0 + i as f64 * ((2.0 - 1.0 / 3.0) / 2000.0);
        let g = GaussFilter::new(sigma);
        let w = g.weights();
        assert!((1..=4).contains(&g.radius()), "sigma {sigma}");
        // normalize: g0 + 2·Σ g[1..] == 1 to within an ulp or two.
        let total = w[0] + 2.0 * w[1..].iter().sum::<f64>();
        assert!((total - 1.0).abs() < 1e-15, "sigma {sigma}: {total}");
        // Monotone decreasing, and the radius never shrinks as sigma grows.
        assert!(w.windows(2).all(|p| p[0] > p[1]), "sigma {sigma}: {w:?}");
        assert!(g.radius() >= last_radius);
        last_radius = g.radius();
    }
}

/// Naive float direct convolution of one line with SkGaussFilter's double weights, rounded half up.
fn naive_gauss_line(line: &[u8], weights: &[f64]) -> Vec<u8> {
    let r = weights.len() - 1;
    (0..line.len() + 2 * r)
        .map(|j| {
            let mut acc = 0.0f64;
            for k in 0..=2 * r {
                if let Some(i) = j.checked_sub(k)
                    && i < line.len()
                {
                    acc += line[i] as f64 * weights[k.abs_diff(r)];
                }
            }
            (acc + 0.5).floor() as u8
        })
        .collect()
}

/// Each pass of small_blur against the naive float convolution of the same input: within ±1.
/// Then the whole 2D result against a naive float 2D (rounded after each pass, same order): the
/// difference is reported; the bound asserted is ±2 because a ±1 from pass 1 can carry into pass 2.
#[test]
fn small_blur_matches_naive_float() {
    let mut rng = Rng(0xC0FF_EE11);
    let mut max_pass_diff = 0i32;
    let mut max_2d_diff = 0i32;
    let mut sigmas: Vec<f64> = (0..60).map(|i| 0.34 + i as f64 * (1.65 / 60.0)).collect();
    sigmas.extend([0.5, 1.07735, 1.5, 1.99, 1.9999]);
    for (i, &sigma) in sigmas.iter().enumerate() {
        let f = SmallBlurFactors::new(sigma);
        let g = GaussFilter::new(sigma);
        for &(w, h) in &[(1usize, 1usize), (1, 13), (13, 1), (7, 9), (8, 8), (17, 16), (31, 5)] {
            let stride = w + i % 2;
            let src = random_mask(w, h, stride, (i % 3) as u32, &mut rng);
            let r = f.radius;
            let (dw, dh) = (w + 2 * r, h + 2 * r);

            // Pass 1 (vertical) alone.
            let mut mid = vec![0u8; dw * dh];
            small_blur_y(&src, w, h, stride, &f, &mut mid[r..], dw);
            for x in 0..w {
                let col: Vec<u8> = (0..h).map(|y| src[y * stride + x]).collect();
                let want = naive_gauss_line(&col, g.weights());
                for (y, &v) in want.iter().enumerate() {
                    let d = mid[y * dw + r + x] as i32 - v as i32;
                    max_pass_diff = max_pass_diff.max(d.abs());
                    assert!(d.abs() <= 1, "y pass sigma {sigma} {w}x{h} ({x},{y}) {d}");
                }
            }

            // Pass 2 (horizontal) alone, on Skia's pass-1 output.
            let mut out = mid.clone();
            small_blur_x(&mut out, dw, dh, w, &f, &mut Vec::new());
            for y in 0..dh {
                let want = naive_gauss_line(&mid[y * dw + r..y * dw + r + w], g.weights());
                for (x, &v) in want.iter().enumerate() {
                    let d = out[y * dw + x] as i32 - v as i32;
                    max_pass_diff = max_pass_diff.max(d.abs());
                    assert!(d.abs() <= 1, "x pass sigma {sigma} {w}x{h} ({x},{y}) {d}");
                }
            }

            // Whole thing through the public entry point.
            let got = blur_a8(&src, w, h, stride, sigma);
            assert_eq!(got.pixels, out);
            let mut naive_mid = vec![0u8; w * dh];
            for x in 0..w {
                let col: Vec<u8> = (0..h).map(|y| src[y * stride + x]).collect();
                for (y, v) in naive_gauss_line(&col, g.weights()).into_iter().enumerate() {
                    naive_mid[y * w + x] = v;
                }
            }
            for y in 0..dh {
                let want = naive_gauss_line(&naive_mid[y * w..(y + 1) * w], g.weights());
                for (x, &v) in want.iter().enumerate() {
                    let d = got.pixels[y * dw + x] as i32 - v as i32;
                    max_2d_diff = max_2d_diff.max(d.abs());
                    assert!(d.abs() <= 2, "2d sigma {sigma} {w}x{h} ({x},{y}) {d}");
                }
            }
        }
    }
    println!("small_blur vs naive float: max per-pass diff {max_pass_diff}, max 2D diff {max_2d_diff}");
}
