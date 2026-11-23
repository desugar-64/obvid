// Copyright 2026, Serhii Yaremych (desugar-64) and the Obvid project contributors
// SPDX-License-Identifier: Apache-2.0

//! Host tests for `neon::small` (declared in small.rs as `mod tests`): the NEON `small_blur` against the scalar port
//! `skia::small_blur_into`, byte for byte.

use super::*;
use crate::skia::{self, NO_BLUR_SIGMA, SMALL_BLUR_SIGMA_LIMIT};
use crate::test_support::{Rng, allocations};

/// Source contents: uniform noise, mostly 0/255 with edge values, a few non-zero bytes in zeros, all 255, all 0.
const CONTENTS: u32 = 5;

fn source(width: usize, height: usize, stride: usize, content: u32, rng: &mut Rng) -> Vec<u8> {
    let mut m = vec![0xA5u8; stride * height.max(1)]; // padding bytes must be ignored
    for y in 0..height {
        for x in 0..width {
            m[y * stride + x] = match content {
                0 => rng.next() as u8,
                1 => match rng.below(10) {
                    0..=3 => 0,
                    4..=7 => 255,
                    _ => rng.next() as u8,
                },
                2 => {
                    if rng.below(16) == 0 {
                        rng.next() as u8
                    } else {
                        0
                    }
                }
                3 => 255,
                _ => 0,
            };
        }
    }
    m
}

/// Both versions into destinations with `dst_stride` = output width + `pad` (pre-filled with the same garbage),
/// the NEON one with a poisoned row buffer; the whole destinations, padding included, must be equal.
#[allow(clippy::too_many_arguments)]
fn check(
    src: &[u8],
    width: usize,
    height: usize,
    stride: usize,
    fx: &SmallBlurFactors,
    fy: &SmallBlurFactors,
    pad: usize,
    row: &mut Vec<u8>,
    what: &str,
) {
    let dst_w = width + 2 * fx.radius;
    let dst_h = height + 2 * fy.radius;
    let dst_stride = dst_w + pad;
    let mut want = vec![0x5Au8; dst_stride * dst_h + pad];
    let mut got = want.clone();
    skia::small_blur_into(src, width, height, stride, fx, fy, &mut want, dst_stride, &mut Vec::new());
    row.fill(0xC3);
    small_blur_into(src, width, height, stride, fx, fy, &mut got, dst_stride, row);
    if got != want {
        let i = got.iter().zip(&want).position(|(a, b)| a != b).unwrap();
        panic!(
            "{what}: {width}x{height}/{stride} rx {} ry {} pad {pad}: first difference at row {} col {}: got {} want {}",
            fx.radius,
            fy.radius,
            i / dst_stride,
            i % dst_stride,
            got[i],
            want[i]
        );
    }
}

/// The σ < 2 range [1/3, 2): a fine sweep, plus the two sides of every point where the radius changes (found by
/// bisection) and the ends.
fn small_sigmas() -> Vec<f64> {
    let radius = |s: f64| SmallBlurFactors::new(s).radius;
    let mut sigmas: Vec<f64> = (0..400).map(|i| NO_BLUR_SIGMA + (i as f64 / 400.0) * (2.0 - NO_BLUR_SIGMA)).collect();
    let mut boundaries = 0;
    for i in 0..sigmas.len() - 1 {
        let (mut a, mut b) = (sigmas[i], sigmas[i + 1]);
        if radius(a) == radius(b) {
            continue;
        }
        while b - a > 1e-12 {
            let m = 0.5 * (a + b);
            if radius(m) == radius(a) { a = m } else { b = m }
        }
        sigmas.extend([a, b]);
        boundaries += 1;
    }
    assert_eq!(boundaries, 3, "radius 1..=4 has three boundaries in [1/3, 2)");
    sigmas.extend([NO_BLUR_SIGMA, 0.5, 1.99, 1.9999, f64::from_bits(SMALL_BLUR_SIGMA_LIMIT.to_bits() - 1)]);
    sigmas
}

/// Every σ of the sweep (each radius, both sides of each radius change) on random sizes, strides and contents.
#[test]
fn every_sigma_matches_scalar() {
    let mut rng = Rng(0x5E0A_11B1);
    let mut row = Vec::new();
    let mut radii = [0usize; 5];
    for (i, &sigma) in small_sigmas().iter().enumerate() {
        let f = SmallBlurFactors::new(sigma);
        radii[f.radius] += 1;
        for n in 0..6 {
            let width = 1 + rng.below(if n % 2 == 0 { 40 } else { 120 }) as usize;
            let height = 1 + rng.below(if n % 3 == 0 { 20 } else { 70 }) as usize;
            let stride = width + rng.below(5) as usize;
            let content = ((i + n) as u32) % CONTENTS;
            let src = source(width, height, stride, content, &mut rng);
            check(&src, width, height, stride, &f, &f, n % 3, &mut row, &format!("sigma {sigma}"));
        }
    }
    assert!(radii[1..].iter().all(|&n| n > 0), "{radii:?}");
}

/// Every width 1..=40 and widths around multiples of 16 (the block tails of both passes), every height 1..=20, at
/// one σ per radius and both sides of each radius change; all contents, strides wider than the width.
#[test]
fn every_size_matches_scalar() {
    let mut rng = Rng(0x0516_2026);
    let mut row = Vec::new();
    let sigmas = small_sigmas();
    let mut picked: Vec<f64> = sigmas[400..406].to_vec(); // the boundary pairs
    picked.extend([0.4, 0.9, 1.3, 1.9]);
    let widths: Vec<usize> =
        (1..=40).chain([45, 46, 47, 48, 49, 50, 62, 63, 64, 65, 66, 79, 80, 81, 127, 128, 129]).collect();
    for &sigma in &picked {
        let f = SmallBlurFactors::new(sigma);
        for &width in &widths {
            for height in 1..=20 {
                let stride = width + (width + height) % 3;
                let content = ((width + height) as u32) % CONTENTS;
                let src = source(width, height, stride, content, &mut rng);
                check(&src, width, height, stride, &f, &f, height % 2, &mut row, &format!("sigma {sigma}"));
            }
        }
    }
}

/// Different radii across and down (the API allows it; the pipeline uses one σ).
#[test]
fn different_radii_per_axis() {
    let mut rng = Rng(0xD1FF);
    let mut row = Vec::new();
    let per_radius = [0.4, 0.9, 1.3, 1.9].map(SmallBlurFactors::new);
    for fx in &per_radius {
        for fy in &per_radius {
            for &(width, height) in &[(1, 1), (3, 17), (15, 2), (16, 16), (17, 5), (33, 40), (100, 9)] {
                let src = source(width, height, width + 1, (width as u32) % CONTENTS, &mut rng);
                check(&src, width, height, width + 1, fx, fy, 2, &mut row, "axes");
            }
        }
    }
}

/// Different weights across and down at the same filter radius (σx 0.4, σy 0.5: both radius 1), on one opaque pixel
/// (the output is the outer product of the two kernels, so weights taken from the wrong axis transpose it) and on
/// noise.
#[test]
fn different_weights_same_radius_per_axis() {
    let fx = SmallBlurFactors::new(0.4);
    let fy = SmallBlurFactors::new(0.5);
    assert_eq!((fx.radius, fy.radius), (1, 1));
    assert_ne!(fx.factors, fy.factors);
    let mut row = Vec::new();
    for &(width, height) in &[(1, 1), (5, 5), (9, 3), (17, 17), (40, 21)] {
        let mut impulse = vec![0u8; width * height];
        impulse[(height / 2) * width + width / 2] = 255;
        check(&impulse, width, height, width, &fx, &fy, 1, &mut row, "impulse");
        check(&impulse, width, height, width, &fy, &fx, 1, &mut row, "impulse, axes swapped");
    }
    let mut rng = Rng(0x0405);
    for &(width, height) in &[(16, 16), (33, 7), (7, 33), (100, 60)] {
        let src = source(width, height, width + 2, 0, &mut rng);
        check(&src, width, height, width + 2, &fx, &fy, 0, &mut row, "noise");
        check(&src, width, height, width + 2, &fy, &fx, 0, &mut row, "noise, axes swapped");
    }
    // The impulse's two orientations differ, so the check above would catch weights read from the wrong axis.
    let (mut a, mut b) = (vec![0u8; 7 * 7], vec![0u8; 7 * 7]);
    let mut one = vec![0u8; 25];
    one[12] = 255;
    small_blur_into(&one, 5, 5, 5, &fx, &fy, &mut a, 7, &mut row);
    small_blur_into(&one, 5, 5, 5, &fy, &fx, &mut b, 7, &mut row);
    assert_ne!(a, b);
}

/// Weights that make the 8.8 sums wrap and that use every byte of a weight (0xFF in either byte, 0xFFFF): the
/// kernel's per-tap split and its accumulation order give the same wrapped `u16` as the scalar port.
#[test]
fn wrapping_weights_match_scalar() {
    let mut rng = Rng(0xFFFF);
    let mut row = Vec::new();
    let tables: [[u16; GAUSS_ARRAY_MAX]; 5] = [
        [0xFFFF; GAUSS_ARRAY_MAX],
        [0xFF00, 0x00FF, 0xFFFF, 0x0101, 0x8080, 0],
        [0x00FF; GAUSS_ARRAY_MAX],
        [0xFF00; GAUSS_ARRAY_MAX],
        [0x8001, 0x7FFF, 0xC000, 0x3FFF, 0xFFFE, 0],
    ];
    for factors in tables {
        for radius in 1..=4 {
            let f = SmallBlurFactors { factors, radius };
            for &(width, height) in &[(1, 1), (5, 30), (16, 3), (31, 31), (70, 12)] {
                for content in 0..CONTENTS {
                    let src = source(width, height, width, content, &mut rng);
                    check(&src, width, height, width, &f, &f, 1, &mut row, "wrapping");
                }
            }
        }
    }
}

/// An empty source gives zero rows of the grown size, the padding between rows untouched.
#[test]
fn empty_source_gives_zeros() {
    let f = SmallBlurFactors::new(1.5);
    let mut row = Vec::new();
    for &(width, height) in &[(0, 0), (0, 5), (7, 0)] {
        check(&[], width, height, width, &f, &f, 3, &mut row, "empty");
    }
}

/// A row buffer that has grown allocates nothing on later calls, smaller or equal.
#[test]
fn reused_row_buffer_allocates_nothing() {
    let mut rng = Rng(7);
    let f = SmallBlurFactors::new(1.86);
    let src = source(300, 200, 300, 1, &mut rng);
    let mut dst = vec![0u8; (300 + 8) * (200 + 8)];
    let mut row = Vec::new();
    small_blur_into(&src, 300, 200, 300, &f, &f, &mut dst, 308, &mut row);
    let before = allocations();
    for &(w, h) in &[(300, 200), (17, 3), (299, 1)] {
        small_blur_into(&src, w, h, 300, &f, &f, &mut dst, w + 8, &mut row);
    }
    assert_eq!(allocations(), before);
}

#[test]
#[should_panic(expected = "dst too short")]
fn short_destination_panics() {
    let f = SmallBlurFactors::new(1.0);
    let r = f.radius;
    let mut dst = vec![0u8; (4 + 2 * r) * (4 + 2 * r) - 1];
    small_blur_into(&[0; 16], 4, 4, 4, &f, &f, &mut dst, 4 + 2 * r, &mut Vec::new());
}

/// An output height that does not fit `usize` panics instead of wrapping to a tiny destination.
#[test]
#[should_panic(expected = "output size overflows")]
fn overflowing_output_size_panics() {
    let f = SmallBlurFactors::new(1.0);
    let mut dst = vec![0u8; 2 * f.radius + 2];
    small_blur_into(&[], 0, usize::MAX, 0, &f, &f, &mut dst, 2 * f.radius, &mut Vec::new());
}

#[test]
#[should_panic(expected = "output size overflows")]
fn overflowing_output_width_panics() {
    let f = SmallBlurFactors::new(1.0);
    let mut dst = vec![0u8; 64];
    small_blur_into(&[], usize::MAX, 0, usize::MAX, &f, &f, &mut dst, 8, &mut Vec::new());
}

#[test]
#[should_panic(expected = "src too short")]
fn short_source_panics() {
    let f = SmallBlurFactors::new(1.0);
    let r = f.radius;
    let mut dst = vec![0u8; (4 + 2 * r) * (4 + 2 * r)];
    small_blur_into(&[0; 15], 4, 4, 4, &f, &f, &mut dst, 4 + 2 * r, &mut Vec::new());
}

/// `cargo test --release neon::small::tests::timing_mac -- --ignored --nocapture`: the scalar port vs NEON on a
/// 525 × 525 mask. Host (Apple Silicon) numbers only; the project's numbers come from Microbenchmark on the devices.
#[test]
#[ignore]
fn timing_mac() {
    use std::time::Instant;
    let mut rng = Rng(8);
    let size = 525;
    let src = source(size, size, size, 1, &mut rng);
    for sigma in [0.5, 1.26, 1.86] {
        let f = SmallBlurFactors::new(sigma);
        let dst_w = size + 2 * f.radius;
        let mut want = vec![0u8; dst_w * dst_w];
        let mut got = vec![0u8; dst_w * dst_w];
        let (mut row_s, mut row_n) = (Vec::new(), Vec::new());
        let (mut scalar, mut neon) = (Vec::new(), Vec::new());
        for _ in 0..31 {
            let t = Instant::now();
            skia::small_blur_into(&src, size, size, size, &f, &f, &mut want, dst_w, &mut row_s);
            scalar.push(t.elapsed().as_secs_f64() * 1e3);
            let t = Instant::now();
            small_blur_into(&src, size, size, size, &f, &f, &mut got, dst_w, &mut row_n);
            neon.push(t.elapsed().as_secs_f64() * 1e3);
            assert_eq!(got, want);
        }
        scalar.sort_by(f64::total_cmp);
        neon.sort_by(f64::total_cmp);
        let (s, n) = (scalar[15], neon[15]);
        println!(
            "Mac only: {size}x{size} sigma {sigma} (radius {}): scalar {s:.3} ms, neon {n:.3} ms, x{:.1}",
            f.radius,
            s / n
        );
    }
}
