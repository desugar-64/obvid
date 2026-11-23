// Copyright 2026, Serhii Yaremych (desugar-64) and the Obvid project contributors
// SPDX-License-Identifier: Apache-2.0

//! Host tests for `blur` (declared in blur.rs as `mod tests`). On Apple Silicon the NEON code runs natively. The
//! reference is the scalar port `skia::blur_a8` (identical to Skia's C++). The helpers and the step counters the
//! passes report to (test builds only) are shared with the interior-skip tests in `blur/passes_tests.rs`.

use super::*;
use crate::neon::flags::FLAG_PAD;
use crate::skia;
use crate::test_support::{Rng, allocations, reference_src_over};

#[derive(Clone, Copy, Debug)]
pub(super) enum Kind {
    Random,
    Zero,
    Full,
    /// Mostly 0 / 255 with some edge values.
    Speckle,
    /// Filled disc with anti-aliased edge (4 × 4 supersampled coverage).
    Disc,
    /// Rounded rect with anti-aliased edges.
    RoundRect,
}

pub(super) const KINDS: [Kind; 6] = [Kind::Random, Kind::Zero, Kind::Full, Kind::Speckle, Kind::Disc, Kind::RoundRect];

/// Coverage of a shape over pixel (x, y), 16 sub-samples, rounded to a byte.
pub(super) fn coverage(x: usize, y: usize, inside: impl Fn(f64, f64) -> bool) -> u8 {
    let mut n = 0;
    for sy in 0..4 {
        for sx in 0..4 {
            if inside(x as f64 + (sx as f64 + 0.5) / 4.0, y as f64 + (sy as f64 + 0.5) / 4.0) {
                n += 1;
            }
        }
    }
    ((n * 255 + 8) / 16) as u8
}

/// A `width × height` mask with row stride `stride`; padding bytes hold garbage that must be ignored.
pub(super) fn mask(width: usize, height: usize, stride: usize, kind: Kind, rng: &mut Rng) -> Vec<u8> {
    let mut m = vec![0xA5u8; stride * height.max(1)];
    let (w, h) = (width as f64, height as f64);
    for y in 0..height {
        for x in 0..width {
            m[y * stride + x] = match kind {
                Kind::Random => rng.next() as u8,
                Kind::Zero => 0,
                Kind::Full => 255,
                Kind::Speckle => match rng.below(10) {
                    0..=3 => 0,
                    4..=7 => 255,
                    _ => rng.next() as u8,
                },
                Kind::Disc => coverage(x, y, |px, py| {
                    let r = 0.45 * w.min(h);
                    (px - w / 2.0).powi(2) + (py - h / 2.0).powi(2) <= r * r
                }),
                Kind::RoundRect => coverage(x, y, |px, py| {
                    let (l, t, r, b) = (0.1 * w + 0.3, 0.15 * h + 0.6, 0.9 * w - 0.2, 0.85 * h - 0.7);
                    if px < l || px > r || py < t || py > b {
                        return false;
                    }
                    let rad = (0.2 * w.min(h)).min(0.5 * (r - l)).min(0.5 * (b - t));
                    let cx = px.clamp(l + rad, r - rad);
                    let cy = py.clamp(t + rad, b - rad);
                    (px - cx).powi(2) + (py - cy).powi(2) <= rad * rad
                }),
            };
        }
    }
    m
}

/// Asserts NEON == scalar port for one case, in every interior-skip mode (off, auto, always, and always with a
/// scratch full of garbage), with a readable
/// failure. Returns the steps the `Always` run skipped and ran (from [`note_steps`]).
pub(super) fn check(src: &[u8], width: usize, height: usize, stride: usize, sigma: f64, what: &str) -> (usize, usize) {
    let [h, v] = check_passes(src, width, height, stride, sigma, what);
    (h.0 + v.0, h.1 + v.1)
}

/// [`check`], with the `Always` run's (skipped, total) steps per pass.
pub(super) fn check_passes(
    src: &[u8],
    width: usize,
    height: usize,
    stride: usize,
    sigma: f64,
    what: &str,
) -> [(usize, usize); 2] {
    let expected = skia::blur_a8(src, width, height, stride, sigma);
    let mut counted = [(0, 0); 2];
    for (mode, poisoned) in [
        (InteriorSkip::Off, false),
        (InteriorSkip::Auto, false),
        (InteriorSkip::Always, false),
        (InteriorSkip::Always, true),
    ] {
        take_pass_steps();
        let actual = if poisoned {
            // A scratch full of garbage (a fresh one is zero-filled, which could hide a stale read of a zero run).
            let g = blur_geometry(width, height, sigma);
            let (w16, z) = (g.width + 16, 3 * 255 + 1);
            let mut scratch = Scratch::new();
            scratch.hist = vec![0xA5; (z + w16) * 16];
            scratch.out = vec![0x5A; w16.max(g.height) * 16];
            scratch.inter = vec![0xC3; (z + g.height) * w16];
            scratch.hflags = vec![0xFF; w16 + FLAG_PAD];
            scratch.vflags = vec![0xFF; w16.div_ceil(16) * (g.height + 16 + FLAG_PAD)];
            let mut pixels = vec![0u8; g.width * g.height];
            blur_a8_window_into(
                src,
                width,
                height,
                stride,
                sigma,
                Window::full(g.width, g.height),
                &mut pixels,
                g.width,
                Store::Replace,
                &mut scratch,
                mode,
            );
            skia::BlurredMask { pixels, width: g.width, height: g.height, border_x: g.border_x, border_y: g.border_y }
        } else {
            blur_a8_with(src, width, height, stride, sigma, mode)
        };
        if mode == InteriorSkip::Always && !poisoned {
            counted = take_pass_steps();
        }
        assert_eq!(
            (actual.width, actual.height, actual.border_x, actual.border_y),
            (expected.width, expected.height, expected.border_x, expected.border_y),
            "{what}: geometry"
        );
        if actual.pixels != expected.pixels {
            let i = actual.pixels.iter().zip(&expected.pixels).position(|(a, b)| a != b).unwrap();
            let diff = actual.pixels.iter().zip(&expected.pixels).filter(|(a, b)| a != b).count();
            panic!(
                "{what} ({mode:?}{}): {diff} bytes differ; first at ({}, {}): neon {} scalar {}",
                if poisoned { ", poisoned scratch" } else { "" },
                i % expected.width,
                i / expected.width,
                actual.pixels[i],
                expected.pixels[i]
            );
        }
    }
    counted
}

thread_local! {
    /// (skipped, total) kernel steps per pass (0 horizontal, 1 vertical) on this thread since [`take_pass_steps`].
    static STEPS: std::cell::Cell<[(usize, usize); 2]> = const { std::cell::Cell::new([(0, 0); 2]) };
    static PASS: std::cell::Cell<usize> = const { std::cell::Cell::new(0) };
}

/// Called by the kernel (test builds only) when a pass starts.
pub(super) fn note_pass(pass: usize) {
    PASS.with(|p| p.set(pass));
}

/// Called by the kernel (test builds only): `total` steps of a scan, `skipped` of them not run.
pub(super) fn note_steps(total: usize, skipped: usize) {
    let pass = PASS.with(|p| p.get());
    STEPS.with(|c| {
        let mut v = c.get();
        v[pass].0 += skipped;
        v[pass].1 += total;
        c.set(v);
    });
}

/// Per pass (skipped, total) since the last call, and reset.
pub(super) fn take_pass_steps() -> [(usize, usize); 2] {
    STEPS.with(|c| c.replace([(0, 0); 2]))
}

/// Both passes together.
pub(super) fn take_steps() -> (usize, usize) {
    let [h, v] = take_pass_steps();
    (h.0 + v.0, h.1 + v.1)
}

/// A σ in the middle of window d's σ range: σ·3√(2π)/4 = d, so floor(… + 0.5) = d.
pub(super) fn sigma_for_window(d: usize) -> f64 {
    let sigma = d as f64 / (3.0 * (2.0 * std::f64::consts::PI).sqrt() / 4.0);
    assert_eq!(PlanGauss::new(sigma).window, d, "sigma {sigma}");
    sigma
}

/// A `width × height` mask (row stride `stride`, garbage padding) of the coverage of `inside`, 4 × 4 supersampled.
pub(super) fn render(width: usize, height: usize, stride: usize, inside: impl Fn(f64, f64) -> bool) -> Vec<u8> {
    let mut m = vec![0xA5u8; stride * height.max(1)];
    for y in 0..height {
        for x in 0..width {
            m[y * stride + x] = coverage(x, y, &inside);
        }
    }
    m
}

pub(super) fn in_disc(px: f64, py: f64, cx: f64, cy: f64, r: f64) -> bool {
    (px - cx).powi(2) + (py - cy).powi(2) <= r * r
}

pub(super) fn in_rrect(px: f64, py: f64, (l, t, r, b): (f64, f64, f64, f64), rad: f64) -> bool {
    if px < l || px > r || py < t || py > b {
        return false;
    }
    let rad = rad.min(0.5 * (r - l)).min(0.5 * (b - t));
    let cx = px.clamp(l + rad, r - rad);
    let cy = py.clamp(t + rad, b - rad);
    (px - cx).powi(2) + (py - cy).powi(2) <= rad * rad
}

#[derive(Clone, Copy, Debug)]
pub(super) enum Shape {
    Disc,
    RoundRect,
    Ring,
    TwoDiscs,
    /// A ring with a wedge cut out on the right: several spans per row, concave.
    C,
    /// Vertical bars `bar` wide, `gap` apart, at a fractional phase (anti-aliased sides).
    VStripes {
        bar: f64,
        gap: f64,
    },
    /// Horizontal bars.
    HStripes {
        bar: f64,
        gap: f64,
    },
}

/// The shape filling a `w × h` mask (touching or nearly touching its edges).
pub(super) fn shape_mask(w: usize, h: usize, stride: usize, shape: Shape) -> Vec<u8> {
    let (wf, hf) = (w as f64, h as f64);
    let (cx, cy, r) = (wf / 2.0, hf / 2.0, 0.5 * wf.min(hf) - 0.3);
    match shape {
        Shape::Disc => render(w, h, stride, |x, y| in_disc(x, y, cx, cy, r)),
        Shape::RoundRect => render(w, h, stride, |x, y| in_rrect(x, y, (0.4, 0.0, wf - 0.4, hf), 0.2 * wf.min(hf))),
        Shape::Ring => render(w, h, stride, |x, y| in_disc(x, y, cx, cy, r) && !in_disc(x, y, cx, cy, 0.55 * r)),
        Shape::TwoDiscs => {
            let r2 = 0.24 * wf.min(2.0 * hf);
            render(w, h, stride, |x, y| in_disc(x, y, 0.26 * wf, cy, r2) || in_disc(x, y, 0.74 * wf, cy, r2))
        }
        Shape::C => render(w, h, stride, |x, y| {
            in_disc(x, y, cx, cy, r) && !in_disc(x, y, cx, cy, 0.5 * r) && !(x > cx && (y - cy).abs() < 0.3 * r)
        }),
        Shape::VStripes { bar, gap } => render(w, h, stride, |x, _| (x + 0.37).rem_euclid(bar + gap) < bar),
        Shape::HStripes { bar, gap } => render(w, h, stride, |_, y| (y + 0.61).rem_euclid(bar + gap) < bar),
    }
}

// ---- NEON vs scalar port -----------------------------------------------------------------------------------

/// Every window 4..=254 (σ 2.13…135), sizes and strides varying with the window, kinds cycling.
#[test]
fn every_window_matches_scalar() {
    let mut rng = Rng(1);
    for d in 4..=254 {
        let sigma = sigma_for_window(d);
        let width = 1 + (d * 7) % 53;
        let height = 1 + (d * 13) % 41;
        let stride = width + d % 3;
        let kind = KINDS[d % KINDS.len()];
        let src = mask(width, height, stride, kind, &mut rng);
        check(&src, width, height, stride, sigma, &format!("d {d} {width}x{height} {kind:?}"));
    }
}

/// σ from 2 to 135 (odd and even windows, both sides of the i16/i32 switch at d = 64/65), sizes 1×1, 1×N, N×1,
/// lines shorter than the window and than the kernel, widths and heights around multiples of 16, strides wider
/// than the width, every mask kind.
#[test]
fn sizes_and_masks_match_scalar() {
    let sigmas = [
        2.0, 2.13, 2.5, 2.7, 3.3, 5.0, 7.5, 10.0, 14.36, 17.3, 24.6, 33.5, 34.5, 37.0, 50.0, 64.0, 100.0, 127.5, 128.0,
        135.0,
    ];
    let sizes = [
        (1, 1),
        (1, 9),
        (9, 1),
        (1, 70),
        (70, 1),
        (2, 3),
        (5, 4),
        (15, 17),
        (16, 16),
        (17, 15),
        (31, 33),
        (33, 47),
        (64, 5),
        (100, 37),
    ];
    let mut rng = Rng(2);
    for &sigma in &sigmas {
        let plan = PlanGauss::new(sigma);
        for &(width, height) in &sizes {
            for (i, kind) in KINDS.iter().enumerate() {
                let stride = if i % 2 == 0 { width } else { width + 1 + i };
                let src = mask(width, height, stride, *kind, &mut rng);
                check(
                    &src,
                    width,
                    height,
                    stride,
                    sigma,
                    &format!("sigma {sigma} (d {}) {width}x{height}/{stride} {kind:?}", plan.window),
                );
            }
        }
    }
}

/// Shape-like masks at realistic sizes (as the shadow pipeline will feed them).
#[test]
fn shapes_match_scalar() {
    let mut rng = Rng(3);
    for &(width, height) in &[(200, 150), (130, 257), (48, 48)] {
        for &sigma in &[2.0, 5.0, 14.36, 24.6, 37.0, 100.0] {
            for kind in [Kind::Disc, Kind::RoundRect] {
                let src = mask(width, height, width, kind, &mut rng);
                check(&src, width, height, width, sigma, &format!("{width}x{height} sigma {sigma} {kind:?}"));
            }
        }
    }
}

/// Bars of width d at period 2d drive T0 to ±510·d, the edge of i16 at d = 64.
#[test]
fn worst_case_bars_match_scalar() {
    for d in [4usize, 5, 63, 64, 65, 66, 128, 241] {
        let sigma = sigma_for_window(d);
        let (width, height) = (8 * d + 5, 37);
        for phase in [0, 1] {
            let src: Vec<u8> = (0..height)
                .flat_map(|_| (0..width).map(move |x| if (x / d + phase) % 2 == 0 { 255 } else { 0 }))
                .collect();
            check(&src, width, height, width, sigma, &format!("bars d {d} phase {phase}"));
            // Same bars vertically.
            let src_ref = &src;
            let t: Vec<u8> = (0..width).flat_map(|y| (0..height).map(move |x| src_ref[x * width + y])).collect();
            check(&t, height, width, height, sigma, &format!("vertical bars d {d} phase {phase}"));
        }
    }
}

/// σ < 2 (NEON `small_blur`) and the no-blur case give the scalar port's bytes.
#[test]
fn small_sigma_passes_through() {
    let mut rng = Rng(4);
    for &sigma in &[0.0, 0.2, 0.5, 1.0, 1.5, 1.99] {
        let src = mask(23, 19, 25, Kind::Random, &mut rng);
        check(&src, 23, 19, 25, sigma, &format!("sigma {sigma}"));
    }
}

/// Empty sources give a zero mask of the grown size, as the scalar port.
#[test]
fn empty_source() {
    for &(w, h) in &[(0, 0), (0, 5), (7, 0)] {
        check(&[], w, h, w, 10.0, &format!("{w}x{h}"));
    }
}

/// The full window with a destination stride wider than the output leaves the bytes between rows alone, and one
/// `Scratch` reused across calls of different sizes and σ (large, small, large again; growing and not) gives the
/// same bytes as a fresh one each time.
#[test]
fn into_with_stride_and_reused_scratch() {
    let mut rng = Rng(5);
    let mut scratch = Scratch::new();
    let cases = [
        (300, 200, 37.0),
        (17, 9, 2.0),
        (5, 70, 64.0),
        (64, 64, 100.0),
        (1, 1, 5.0),
        (300, 200, 37.0),
        (250, 260, 24.6),
        (33, 33, 1.5),
        (40, 3, 128.0),
    ];
    for (n, &(width, height, sigma)) in cases.iter().enumerate() {
        let kind = KINDS[n % KINDS.len()];
        let src = mask(width, height, width + 3, kind, &mut rng);
        let expected = skia::blur_a8(&src, width, height, width + 3, sigma);
        let g = blur_geometry(width, height, sigma);
        assert_eq!((g.width, g.height, g.border_x), (expected.width, expected.height, expected.border_x));
        let dst_stride = g.width + 11;
        let mut dst = vec![0x5Au8; dst_stride * g.height];
        blur_a8_window_into(
            &src,
            width,
            height,
            width + 3,
            sigma,
            Window::full(g.width, g.height),
            &mut dst,
            dst_stride,
            Store::Replace,
            &mut scratch,
            InteriorSkip::Auto,
        );
        for y in 0..g.height {
            assert_eq!(
                &dst[y * dst_stride..y * dst_stride + g.width],
                &expected.pixels[y * g.width..(y + 1) * g.width],
                "case {n} row {y}"
            );
            assert!(
                dst[y * dst_stride + g.width..(y + 1) * dst_stride].iter().all(|&b| b == 0x5A),
                "case {n} row {y} padding"
            );
        }
        // Fresh scratch, same bytes.
        assert_eq!(blur_a8_with(&src, width, height, width + 3, sigma, InteriorSkip::Auto), expected, "case {n} fresh");
    }
}

/// σ < 2 into the full window (the NEON `small_blur` straight into the caller's buffer, its row buffer in the
/// scratch): the scalar port's bytes for σ across (0.5, 2), with a destination stride wider than the output (the
/// bytes between rows stay as they were) and one scratch reused across sizes going up and down.
#[test]
fn small_sigma_into_matches_scalar() {
    let mut rng = Rng(0x53);
    let mut scratch = Scratch::new();
    for i in 0..60 {
        let sigma = 0.5 + (i as f64 + 0.5) * (1.5 / 60.0);
        let (width, height) = (1 + rng.below(70) as usize, 1 + rng.below(50) as usize);
        let stride = width + rng.below(4) as usize;
        let src = mask(width, height, stride, KINDS[i % KINDS.len()], &mut rng);
        let expected = skia::blur_a8(&src, width, height, stride, sigma);
        let g = blur_geometry(width, height, sigma);
        let dst_stride = g.width + 1 + i % 5;
        let mut dst = vec![0x5Au8; dst_stride * g.height];
        blur_a8_window_into(
            &src,
            width,
            height,
            stride,
            sigma,
            Window::full(g.width, g.height),
            &mut dst,
            dst_stride,
            Store::Replace,
            &mut scratch,
            InteriorSkip::Auto,
        );
        for y in 0..g.height {
            assert_eq!(
                &dst[y * dst_stride..y * dst_stride + g.width],
                &expected.pixels[y * g.width..(y + 1) * g.width],
                "sigma {sigma} row {y}"
            );
            assert!(
                dst[y * dst_stride + g.width..(y + 1) * dst_stride].iter().all(|&b| b == 0x5A),
                "sigma {sigma} row {y} padding"
            );
        }
    }
}

/// σ < 2 through [`blur_a8_window_into`]: σ across [1/3, 2) (every radius), random sources of every kind and size
/// up to 120 (narrower than 16 included), the full window and random partial ones, both stores, one scratch
/// (poisoned first). Reference: the scalar port's whole blur, the window stored per pixel onto random bytes; the
/// destination's other bytes stay as they were.
#[test]
fn small_sigma_window_into_matches_scalar() {
    let mut rng = Rng(0x5A11_0002);
    let mut scratch = Scratch::new();
    scratch.small_row = vec![0xA5; 300];
    scratch.small_mask = vec![0x3C; 1 << 14];
    let mut stores = [0usize; 2];
    for case in 0..400 {
        let sigma = skia::NO_BLUR_SIGMA + (case as f64 + 0.5) / 400.0 * (2.0 - skia::NO_BLUR_SIGMA);
        let size =
            |rng: &mut Rng| if rng.below(3) == 0 { 1 + rng.below(16) as usize } else { 1 + rng.below(120) as usize };
        let (width, height) = (size(&mut rng), size(&mut rng));
        let stride = width + rng.below(6) as usize;
        let kind = KINDS[case % KINDS.len()];
        let src = mask(width, height, stride, kind, &mut rng);
        let expected = skia::blur_a8(&src, width, height, stride, sigma);
        let g = blur_geometry(width, height, sigma);
        assert_eq!((g.width, g.height), (expected.width, expected.height));
        for full in [true, false] {
            let window = if full {
                Window::full(g.width, g.height)
            } else {
                let (x0, x1) = window_axis(g.width, &mut rng);
                let (y0, y1) = window_axis(g.height, &mut rng);
                Window { x0, y0, x1, y1 }
            };
            let Window { x0, y0, x1, y1 } = window;
            let store = if rng.below(2) == 0 { Store::Replace } else { Store::SourceOver };
            stores[store as usize] += 1;
            let (ww, wh) = (x1 - x0, y1 - y0);
            let dst_stride = ww + rng.below(20) as usize;
            let lead = rng.below(7) as usize;
            let base: Vec<u8> = (0..lead + wh * dst_stride + 5).map(|_| rng.next() as u8).collect();
            let mut want = base.clone();
            for y in 0..wh {
                for x in 0..ww {
                    let m = expected.pixels[(y0 + y) * g.width + x0 + x];
                    let d = &mut want[lead + y * dst_stride + x];
                    *d = match store {
                        Store::Replace => m,
                        Store::SourceOver => reference_src_over(*d as u32, m as u32) as u8,
                    };
                }
            }
            let mut got = base.clone();
            blur_a8_window_into(
                &src,
                width,
                height,
                stride,
                sigma,
                window,
                &mut got[lead..],
                dst_stride,
                store,
                &mut scratch,
                InteriorSkip::Auto,
            );
            assert!(
                got == want,
                "case {case} {kind:?} {width}x{height}/{stride} sigma {sigma} {window:?} of {}x{} {store:?}",
                g.width,
                g.height
            );
        }
    }
    assert!(stores[0] > 300 && stores[1] > 300, "{stores:?}");
}

/// One axis of a window over a blurred line of `n` pixels: whole, one pixel, narrower than 16, touching the start,
/// touching the end, anywhere, or empty.
fn window_axis(n: usize, rng: &mut Rng) -> (usize, usize) {
    if n == 0 {
        return (0, 0);
    }
    let at = |rng: &mut Rng, hi: usize| rng.below(hi as u32 + 1) as usize; // 0..=hi
    match rng.below(8) {
        0 => (0, n),
        1 => {
            let a = at(rng, n - 1);
            (a, a + 1)
        }
        2 => {
            let len = 1 + at(rng, 14).min(n - 1);
            let a = at(rng, n - len);
            (a, a + len)
        }
        3 => (0, 1 + at(rng, n - 1)),
        4 => (at(rng, n - 1), n),
        5 => {
            let a = at(rng, n);
            (a, a)
        }
        _ => {
            let (a, b) = (at(rng, n), at(rng, n));
            (a.min(b), a.max(b))
        }
    }
}

/// [`blur_a8_window_into`] stores exactly the whole blur's window: random sources (every kind, sizes 0..200, strides
/// wider than the width), σ for no blur, σ < 2, and windows 4..=94 (odd and even, narrow and wide T0), random
/// windows of the blurred mask (whole, one pixel, narrower than 16, touching each edge, empty), both stores, every
/// interior-skip mode, one scratch reused across all cases (poisoned first). Reference: the scalar port's whole
/// blur, the window stored per pixel (copy, or Skia's srcover formula written out) onto random bytes. Every other
/// byte of the destination (before the window, between and after its rows) stays as it was.
#[test]
fn window_into_matches_scalar() {
    let mut rng = Rng(0x1E7E_0002);
    let mut scratch = Scratch::new();
    let big = (3 * 255 + 1 + 1024) * 16;
    scratch.hist = vec![0xA5; big];
    scratch.out = vec![0x5A; big];
    scratch.inter = vec![0xC3; big * 16];
    scratch.hflags = vec![0xFF; 4096];
    scratch.vflags = vec![0xFF; 1 << 16];
    scratch.small_mask = vec![0x3C; 1 << 16];
    let unit = |rng: &mut Rng| rng.below(1 << 20) as f64 / (1 << 20) as f64;
    let mut stores = [0usize; 2];
    for case in 0..1200 {
        let size = |rng: &mut Rng| match rng.below(5) {
            0 => rng.below(3) as usize,
            1 => 1 + rng.below(20) as usize,
            _ => rng.below(200) as usize,
        };
        let (width, height) = (size(&mut rng), size(&mut rng));
        let stride = width + rng.below(6) as usize;
        let kind = KINDS[case % KINDS.len()];
        let src = mask(width, height, stride, kind, &mut rng);
        let sigma = match case % 6 {
            0 => 0.2,
            1 => 0.5 + 1.49 * unit(&mut rng),
            2 | 3 => sigma_for_window(4 + rng.below(61) as usize),
            4 => sigma_for_window(65 + rng.below(30) as usize),
            _ => 2.0 + 38.0 * unit(&mut rng),
        };
        let expected = skia::blur_a8(&src, width, height, stride, sigma);
        let g = blur_geometry(width, height, sigma);
        assert_eq!((g.width, g.height), (expected.width, expected.height));
        for skip in [InteriorSkip::Off, InteriorSkip::Auto, InteriorSkip::Always] {
            let (x0, x1) = window_axis(g.width, &mut rng);
            let (y0, y1) = window_axis(g.height, &mut rng);
            let window = Window { x0, y0, x1, y1 };
            let store = if rng.below(2) == 0 { Store::Replace } else { Store::SourceOver };
            stores[store as usize] += 1;
            let (ww, wh) = (x1 - x0, y1 - y0);
            let dst_stride = ww + rng.below(20) as usize;
            let lead = rng.below(7) as usize;
            let base: Vec<u8> = (0..lead + wh * dst_stride + 5).map(|_| rng.next() as u8).collect();
            let mut want = base.clone();
            for y in 0..wh {
                for x in 0..ww {
                    let m = expected.pixels[(y0 + y) * g.width + x0 + x];
                    let d = &mut want[lead + y * dst_stride + x];
                    *d = match store {
                        Store::Replace => m,
                        Store::SourceOver => reference_src_over(*d as u32, m as u32) as u8,
                    };
                }
            }
            let mut got = base.clone();
            blur_a8_window_into(
                &src,
                width,
                height,
                stride,
                sigma,
                window,
                &mut got[lead..],
                dst_stride,
                store,
                &mut scratch,
                skip,
            );
            if got != want {
                let i = got.iter().zip(&want).position(|(a, b)| a != b).unwrap();
                let diff = got.iter().zip(&want).filter(|(a, b)| a != b).count();
                let at = i as isize - lead as isize;
                let s = dst_stride.max(1) as isize;
                panic!(
                    "case {case} {kind:?} {width}x{height}/{stride} sigma {sigma} {window:?} of {}x{} {store:?} {skip:?}: {diff} bytes differ, first at window row {} col {}: got {} want {}",
                    g.width,
                    g.height,
                    at.div_euclid(s),
                    at.rem_euclid(s),
                    got[i],
                    want[i]
                );
            }
        }
    }
    assert!(stores[0] > 1000 && stores[1] > 1000, "{stores:?}");
}

/// Once the scratch has grown, the full window allocates nothing on any route: no blur (σ < 1/3), σ < 2
/// (`small_blur`), σ ≥ 2 (NEON, interior skip on a shape and on noise), and an empty source.
#[test]
fn full_window_allocates_nothing_after_warm_up() {
    let mut rng = Rng(0xA110C);
    let shape = shape_mask(200, 150, 203, Shape::RoundRect);
    let noise = mask(97, 61, 97, Kind::Random, &mut rng);
    let cases: [(&[u8], usize, usize, usize, f64); 7] = [
        (&shape, 200, 150, 203, 0.2),
        (&shape, 200, 150, 203, 0.5),
        (&shape, 200, 150, 203, 1.99),
        (&shape, 200, 150, 203, 2.0),
        (&shape, 200, 150, 203, 36.87),
        (&noise, 97, 61, 97, 14.36),
        (&[], 0, 0, 0, 10.0),
    ];
    let largest = cases.iter().map(|c| blur_geometry(c.1, c.2, c.4)).map(|g| g.width * g.height).max().unwrap();
    let mut dst = vec![0u8; largest];
    let mut scratch = Scratch::new();
    for &(src, width, height, stride, sigma) in &cases {
        let g = blur_geometry(width, height, sigma);
        let window = Window::full(g.width, g.height);
        blur_a8_window_into(
            src,
            width,
            height,
            stride,
            sigma,
            window,
            &mut dst,
            g.width,
            Store::Replace,
            &mut scratch,
            InteriorSkip::Auto,
        ); // warm-up
        let before = allocations();
        for _ in 0..3 {
            blur_a8_window_into(
                src,
                width,
                height,
                stride,
                sigma,
                window,
                &mut dst,
                g.width,
                Store::Replace,
                &mut scratch,
                InteriorSkip::Auto,
            );
        }
        let n = allocations() - before;
        assert_eq!(n, 0, "{width}x{height} sigma {sigma}: {n} allocations after warm-up");
    }
    // The counter works: the allocating convenience form does allocate.
    let before = allocations();
    std::hint::black_box(blur_a8_with(&shape, 200, 150, 203, 1.5, InteriorSkip::Auto));
    assert!(allocations() > before);
}

// ---- timing (Mac only, indicative) -------------------------------------------------------------------------

/// `cargo test --release blur::tests::timing_mac -- --ignored --nocapture`: scalar port vs NEON, median of a few runs.
/// Host (Apple Silicon) numbers only; the project's numbers come from Microbenchmark on the devices.
#[test]
#[ignore]
fn timing_mac() {
    use std::time::Instant;
    let mut rng = Rng(6);
    let median = |mut v: Vec<f64>| {
        v.sort_by(|a, b| a.partial_cmp(b).unwrap());
        v[v.len() / 2]
    };
    for &(size, sigma) in &[(600usize, 37.0f64), (1000, 37.0), (600, 5.0), (600, 100.0), (200, 14.36), (48, 14.36)] {
        let src = mask(size, size, size, Kind::RoundRect, &mut rng);
        let g = blur_geometry(size, size, sigma);
        let mut dst = vec![0u8; g.width * g.height];
        let mut scratch = Scratch::new();
        let runs = if size >= 600 { 9 } else { 51 };
        let mut scalar = Vec::new();
        let mut neon = Vec::new();
        for _ in 0..runs {
            let t = Instant::now();
            let m = skia::blur_a8(&src, size, size, size, sigma);
            scalar.push(t.elapsed().as_secs_f64() * 1e3);
            std::hint::black_box(&m);
            let t = Instant::now();
            blur_a8_window_into(
                &src,
                size,
                size,
                size,
                sigma,
                Window::full(g.width, g.height),
                &mut dst,
                g.width,
                Store::Replace,
                &mut scratch,
                InteriorSkip::Auto,
            );
            neon.push(t.elapsed().as_secs_f64() * 1e3);
            std::hint::black_box(&dst);
            assert_eq!(m.pixels, dst);
        }
        let (s, n) = (median(scalar), median(neon));
        println!(
            "Mac only: {size}x{size} sigma {sigma} (d {}, out {}x{}): scalar {s:.3} ms, neon {n:.3} ms, x{:.1}",
            PlanGauss::new(sigma).window,
            g.width,
            g.height,
            s / n
        );
    }

    // The shadow pipeline, one `ShadowBuilder::blur_into` (the window blurred straight into the bitmap) against the
    // buffered route (the whole mask blurred into a buffer, then `shadow::write_window`), for masks like
    // Compose's at density 2.625: an anti-aliased rounded rect (corners 16 dp) of n dp at offset r in a
    // bitmap of n + 2r (the used rect, also the clear rect), Skia's mask bounds (shape outset by ½, rounded out).
    // The buffered route ("old" in the output) and the direct one ("new") alternate in ABBA order; p50 overall and
    // per order (old first / new first), p10.
    use crate::shadow::{self, A8, A8Mut, Placement, ShadowBuilder};
    let density = 2.625;
    println!(
        "Mac only, indicative. pipeline: size dp | r dp | mode | mask -> blurred, used | old p50 | new p50 | saved p50 | saved p50 old-first / new-first | saved p10"
    );
    for &(size_dp, r_dp, mode) in &[
        (48.0f64, 4.0f64, Store::Replace),
        (200.0, 24.0, Store::Replace),
        (200.0, 24.0, Store::SourceOver),
        (360.0, 24.0, Store::Replace),
        (360.0, 64.0, Store::Replace),
    ] {
        let n = size_dp * density;
        let r_px = r_dp * density;
        let sigma = skia::mask_blur_sigma_from_radius(r_px as f32);
        let (left, right) = ((r_px - 0.5).floor(), (r_px + n + 0.5).ceil());
        let side = (right - left) as usize;
        let o = r_px - left;
        let src = render(side, side, side, |x, y| in_rrect(x, y, (o, o, o + n, o + n), (16.0 * density).min(n / 2.0)));
        let used = (n + 2.0 * r_px).ceil() as usize;
        let at = Placement {
            mask_x: left as isize,
            mask_y: left as isize,
            used_w: used,
            used_h: used,
            clear_w: used,
            clear_h: used,
        };
        let g = blur_geometry(side, side, sigma);
        let window = Window::full(g.width, g.height);
        let (bx, by) = (at.mask_x - g.border_x as isize, at.mask_y - g.border_y as isize);
        let mut bitmaps = [vec![0u8; used * used], vec![0u8; used * used]];
        let mut buffer = vec![0u8; g.width * g.height];
        let mut scratch = Scratch::new();
        let mut builder = ShadowBuilder::new();
        let runs = if side > 600 {
            200
        } else if side > 300 {
            400
        } else {
            2000
        };
        let mut t = [Vec::new(), Vec::new()];
        let mut by_order = [[Vec::new(), Vec::new()], [Vec::new(), Vec::new()]]; // [old first, new first][old, new]
        for i in 0..runs + 4 {
            let new_first = !(i % 4 == 0 || i % 4 == 3);
            let order = if new_first { [1, 0] } else { [0, 1] };
            for m in order {
                let bitmap = &mut bitmaps[m];
                // Stale content, as a rebuild finds it (not timed).
                bitmap.fill(if mode == Store::Replace { 0x77 } else { 0x21 });
                let mut dst = A8Mut { pixels: bitmap, width: used, height: used, stride: used };
                let mask = A8 { pixels: &src, width: side, height: side, stride: side };
                let start = Instant::now();
                if m == 0 {
                    blur_a8_window_into(
                        &src,
                        side,
                        side,
                        side,
                        sigma,
                        window,
                        &mut buffer,
                        g.width,
                        Store::Replace,
                        &mut scratch,
                        InteriorSkip::Auto,
                    );
                    let blurred = A8 { pixels: &buffer, width: g.width, height: g.height, stride: g.width };
                    shadow::write_window(&mut dst, blurred, bx, by, at, mode);
                } else {
                    builder.blur_into(mask, r_px as f32, &mut dst, at, mode);
                }
                let ms = start.elapsed().as_secs_f64() * 1e3;
                std::hint::black_box(&dst);
                if i >= 4 {
                    t[m].push(ms);
                    by_order[new_first as usize][m].push(ms);
                }
            }
        }
        assert_eq!(bitmaps[0], bitmaps[1], "{size_dp} dp r {r_dp} {mode:?}");
        let p = |v: &mut Vec<f64>, q: usize| {
            v.sort_by(|a, b| a.partial_cmp(b).unwrap());
            v[v.len() * q / 100]
        };
        let saved = |old: f64, new: f64| 100.0 * (old - new) / old;
        let (old50, new50) = (p(&mut t[0], 50), p(&mut t[1], 50));
        let (old10, new10) = (p(&mut t[0], 10), p(&mut t[1], 10));
        let [mut of, mut nf] = by_order;
        let of_saved = saved(p(&mut of[0], 50), p(&mut of[1], 50));
        let nf_saved = saved(p(&mut nf[0], 50), p(&mut nf[1], 50));
        println!(
            "{size_dp} | {r_dp} | {mode:?} | {side} -> {}, {used} | {old50:.3} | {new50:.3} | {:+.1}% | {of_saved:+.1}% / {nf_saved:+.1}% | {:+.1}%",
            g.width,
            saved(old50, new50),
            saved(old10, new10)
        );
    }
}

/// A source span that overflows `usize` is rejected, not wrapped below the slice length: with stride 2⁶³, the
/// unchecked `(3 − 1)·stride + 1` wraps to 1, the length of `src`.
#[test]
#[should_panic(expected = "src too short")]
fn overflowing_source_span_panics() {
    let src = [0u8; 1];
    let mut dst = [0u8; 16];
    let window = Window { x0: 0, y0: 0, x1: 1, y1: 1 };
    blur_a8_window_into(
        &src,
        1,
        3,
        1 << 63,
        3.0,
        window,
        &mut dst,
        16,
        Store::Replace,
        &mut Scratch::new(),
        InteriorSkip::Auto,
    );
}

/// The same for the destination: window rows 2⁶³ apart.
#[test]
#[should_panic(expected = "dst too short")]
fn overflowing_destination_span_panics() {
    let src = [0u8; 16];
    let mut dst = [0u8; 1];
    let window = Window { x0: 0, y0: 0, x1: 1, y1: 3 };
    blur_a8_window_into(
        &src,
        4,
        4,
        4,
        3.0,
        window,
        &mut dst,
        1 << 63,
        Store::Replace,
        &mut Scratch::new(),
        InteriorSkip::Auto,
    );
}
