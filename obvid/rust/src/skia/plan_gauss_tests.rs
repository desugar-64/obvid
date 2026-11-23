// Copyright 2026, Serhii Yaremych (desugar-64) and the Obvid project contributors
// SPDX-License-Identifier: Apache-2.0

//! Host tests for `skia::plan_gauss` (declared in plan_gauss.rs as `mod tests`): the plan's parameters and the scan
//! against naive references written from the definitions.

use super::*;
use crate::skia::blur_a8;
use crate::test_support::{Rng, random_mask, test_sigmas};

// ---- naive references, written from the definitions ---------------------------------------------------

/// The window from the spec formula, written independently of `PlanGauss::new`.
fn naive_window(sigma: f64) -> usize {
    let w = (sigma * 3.0 * (2.0 * std::f64::consts::PI).sqrt() / 4.0 + 0.5).floor();
    if w < 1.0 { 1 } else { w as usize }
}

/// Box widths of the three passes: d, d, d (odd) or d, d, d+1 (even).
fn naive_box_widths(d: usize) -> [usize; 3] {
    if d % 2 == 1 { [d, d, d] } else { [d, d, d + 1] }
}

/// Full convolution with a box of width `w` in exact integers: y[j] = Σ x[i], i in [j-w+1, j].
fn naive_box_full(x: &[u64], w: usize) -> Vec<u64> {
    let n = x.len() + w - 1;
    (0..n)
        .map(|j| {
            let lo = j.saturating_sub(w - 1);
            let hi = j.min(x.len() - 1);
            (lo..=hi).map(|i| x[i]).sum()
        })
        .collect()
}

/// One line, three explicit box passes on exact u64 sums, one rounding with the plan's weight.
fn naive_line(src: &[u8], d: usize, weight: u64) -> Vec<u8> {
    let mut v: Vec<u64> = src.iter().map(|&b| b as u64).collect();
    for w in naive_box_widths(d) {
        v = naive_box_full(&v, w);
    }
    v.iter().map(|&s| ((weight * s + (1u64 << 31)) >> 32) as u8).collect()
}

/// 2D: rows first (rounded to bytes), then columns (rounded).
fn naive_plan_gauss_2d(src: &[u8], width: usize, height: usize, stride: usize, sigma: f64) -> (Vec<u8>, usize, usize) {
    let d = naive_window(sigma);
    let weight = PlanGauss::from_window(d).weight;
    let rows: Vec<Vec<u8>> = (0..height).map(|y| naive_line(&src[y * stride..y * stride + width], d, weight)).collect();
    let dst_w = rows[0].len();
    let mut out = vec![0u8; 0];
    let mut dst_h = 0;
    let mut columns = Vec::with_capacity(dst_w);
    for x in 0..dst_w {
        let col: Vec<u8> = rows.iter().map(|r| r[x]).collect();
        let blurred = naive_line(&col, d, weight);
        dst_h = blurred.len();
        columns.push(blurred);
    }
    out.resize(dst_w * dst_h, 0);
    for (x, col) in columns.iter().enumerate() {
        for (y, &v) in col.iter().enumerate() {
            out[y * dst_w + x] = v;
        }
    }
    (out, dst_w, dst_h)
}

// ---- PlanGauss parameters ------------------------------------------------------------------------------

#[test]
fn plan_parameters_match_the_definitions() {
    for d in 1..=255usize {
        let p = PlanGauss::from_window(d);
        let widths = naive_box_widths(d);
        assert_eq!([p.pass0_size + 1, p.pass1_size + 1, p.pass2_size + 1], widths, "d={d}");
        // The kernel length is Σ widths - 2; its centre is the border.
        let kernel_len = widths.iter().sum::<usize>() - 2;
        assert_eq!(p.sliding_window, kernel_len, "d={d}");
        assert_eq!(2 * p.border + 1, kernel_len, "d={d}");
        // The divisor is the kernel's sum = product of the box widths.
        assert_eq!(p.divisor, widths.iter().map(|&w| w as u64).product::<u64>(), "d={d}");
        // Skia's double weight equals the integer round(2³²/divisor) for every window.
        let int_weight = ((1u64 << 32) + p.divisor / 2) / p.divisor;
        assert_eq!(p.weight, int_weight, "d={d}");
        // Largest exact running value fits in u32.
        assert!(255 * p.divisor < 1u64 << 32, "d={d}");
    }
}

#[test]
fn window_from_sigma() {
    assert_eq!(PlanGauss::new(2.0).window, 4);
    for i in 0..20000 {
        let sigma = 2.0 + i as f64 * (133.0 / 20000.0);
        assert_eq!(PlanGauss::new(sigma).window, naive_window(sigma), "sigma {sigma}");
    }
}

// ---- PlanGauss vs naive --------------------------------------------------------------------------------

#[test]
fn plan_gauss_line_matches_naive() {
    let mut rng = Rng(0x1234_5678);
    let mut windows_seen = std::collections::BTreeSet::new();
    for sigma in test_sigmas() {
        let plan = PlanGauss::new(sigma);
        windows_seen.insert(plan.window);
        let lens = [
            1usize,
            2,
            3,
            5,
            7,
            plan.window - 1,
            plan.window,
            plan.window + 1,
            plan.sliding_window - 1,
            plan.sliding_window,
            plan.sliding_window + 1,
            2 * plan.sliding_window + 3,
            61,
            200,
        ];
        for &len in &lens {
            if len == 0 {
                continue;
            }
            for kind in 0..3 {
                let src = random_mask(len, 1, len, kind, &mut rng);
                let got = plan_gauss_blur_line(&plan, &src);
                let want = naive_line(&src, plan.window, plan.weight);
                assert_eq!(got, want, "sigma {sigma} window {} len {len} kind {kind}", plan.window);
            }
        }
    }
    assert!(windows_seen.iter().any(|d| d % 2 == 0) && windows_seen.iter().any(|d| d % 2 == 1));
}

#[test]
fn plan_gauss_line_matches_naive_every_window() {
    let mut rng = Rng(0xDEAD_BEEF);
    for d in 2..=255usize {
        let plan = PlanGauss::from_window(d);
        for &len in &[1usize, d / 2 + 1, plan.sliding_window - 1, plan.sliding_window + 5] {
            let src = random_mask(len, 1, len, 1, &mut rng);
            assert_eq!(plan_gauss_blur_line(&plan, &src), naive_line(&src, d, plan.weight), "d={d} len={len}");
        }
    }
}

#[test]
fn plan_gauss_2d_matches_naive() {
    let mut rng = Rng(0x0BAD_F00D);
    let sizes = [
        (1usize, 1usize),
        (1, 17),
        (17, 1),
        (1, 64),
        (64, 1),
        (3, 5),
        (8, 8),
        (13, 7),
        (31, 29),
        (40, 3),
        (3, 40),
        (57, 41),
    ];
    let sigmas = test_sigmas();
    for (i, &sigma) in sigmas.iter().enumerate() {
        for (j, &(w, h)) in sizes.iter().enumerate() {
            // Large sigmas on all sizes would be slow in the naive O(n·w) reference: thin them out.
            if sigma > 40.0 && (i + j) % 3 != 0 {
                continue;
            }
            let stride = w + (j % 3);
            let kind = ((i + j) % 3) as u32;
            let src = random_mask(w, h, stride, kind, &mut rng);
            let got = blur_a8(&src, w, h, stride, sigma);
            let (want, want_w, want_h) = naive_plan_gauss_2d(&src, w, h, stride, sigma);
            let plan = PlanGauss::new(sigma);
            assert_eq!((got.width, got.height), (want_w, want_h), "sigma {sigma} {w}x{h}");
            assert_eq!((got.border_x, got.border_y), (plan.border, plan.border));
            assert_eq!(got.width, w + 2 * plan.border);
            assert_eq!(got.height, h + 2 * plan.border);
            assert!(got.pixels == want, "sigma {sigma} window {} {w}x{h} stride {stride} kind {kind}", plan.window);
        }
    }
}

// ---- flat interior -------------------------------------------------------------------------------------

/// For every window 1..=255: the output for a full kernel of 255s, from the formula and from a real scan.
/// The list of windows where it is not 255 is printed (run with --nocapture) and pinned here.
#[test]
fn flat_interior_value_per_window() {
    let mut not_255 = Vec::new();
    for d in 1..=255usize {
        let p = PlanGauss::from_window(d);
        let v = (p.weight * 255 * p.divisor + (1u64 << 31)) >> 32;
        assert_eq!(v as u8 as u64, v, "d={d}: flat value {v} does not fit a byte");
        if v != 255 {
            not_255.push((d, v));
        }
        if d >= 2 {
            // A line long enough to have outputs with the whole kernel on 255s.
            let src = vec![255u8; p.sliding_window + 8];
            let out = plan_gauss_blur_line(&p, &src);
            let first_full = 2 * p.border; // output j sees src[j-2b..=j]
            let last_full = src.len() - 1;
            for (j, &o) in out.iter().enumerate().take(last_full + 1).skip(first_full) {
                assert_eq!(o as u64, v, "d={d} j={j}");
            }
        }
    }
    println!("flat interior != 255 for windows: {not_255:?}");
    assert!(not_255.is_empty(), "windows whose flat interior is not 255: {not_255:?}");
}
