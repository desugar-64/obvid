// Copyright 2026, Serhii Yaremych (desugar-64) and the Obvid project contributors
// SPDX-License-Identifier: Apache-2.0

//! Host tests for the interior skip of `blur::passes` (declared in passes.rs as `mod tests`): the steady state is
//! exact, and skipping gives the scalar port's bytes on shapes, run-length boundaries, flat and per-line constant
//! masks and random scenes. Helpers and step counters from `blur_tests.rs`.

use super::*;
use crate::blur::tests::{
    Kind, Shape, check, check_passes, coverage, in_disc, in_rrect, mask, render, shape_mask, sigma_for_window,
    take_steps,
};
use crate::blur::{Store, Window, blur_a8_window_into, blur_a8_with, blur_geometry};
use crate::neon::box3::final_bytes;
use crate::skia;
use crate::test_support::Rng;

/// The steady state is exact: for every window and every byte v, `finalScale(v·divisor) = v` (scalar and through
/// the NEON accumulator), and the kernel's own registers after R = L + 2 steps of per-lane constant input,
/// following random history, are exactly [`State::steady`] (T0 = T1 = 0, U = 2³¹ + W·divisor·v).
#[test]
fn steady_state_is_exact() {
    fn regs(s: &State) -> Vec<i64> {
        let mut v = Vec::new();
        unsafe {
            for r in s.t0n {
                let mut a = [0i16; 8];
                vst1q_s16(a.as_mut_ptr(), r);
                v.extend(a.iter().map(|&x| x as i64));
            }
            for r in s.t0w.iter().chain(s.t1.iter()) {
                let mut a = [0i32; 4];
                vst1q_s32(a.as_mut_ptr(), *r);
                v.extend(a.iter().map(|&x| x as i64));
            }
            for r in s.u {
                let mut a = [0i64; 2];
                vst1q_s64(a.as_mut_ptr(), r);
                v.extend(a);
            }
        }
        v
    }
    fn recurrence<const EVEN: bool, const WIDE: bool>(d: usize, rng: &mut Rng) {
        let plan = PlanGauss::from_window(d);
        let skip = SkipPlan::new(&plan, InteriorSkip::Always);
        assert_eq!(skip.state_len, skip.support + 2, "d {d}");
        let weight = plan.weight as i32;
        for round in 0..4 {
            // 16 lanes of constants covering all 256 bytes over 16 rounds of 4 windows; round 0: 0 and 255 only.
            let v: [u8; 16] = core::array::from_fn(|l| match round {
                0 => [0, 255][l % 2],
                _ => (rng.next() as u8).wrapping_add(l as u8 * 16),
            });
            let zeros = 3 * d + 1;
            let history = 2 * d + rng.below(3 * d as u32) as usize;
            let total = history + skip.state_len;
            let mut input = vec![0u8; (zeros + total) * LANES];
            for n in 0..total {
                for l in 0..LANES {
                    input[(zeros + n) * LANES + l] = if n < history { rng.next() as u8 } else { v[l] };
                }
            }
            let mut out = vec![0u8; total * LANES];
            let mut state = State::zero();
            unsafe {
                steps::<EVEN, WIDE>(
                    &mut state,
                    input.as_ptr().add(zeros * LANES),
                    LANES,
                    out.as_mut_ptr(),
                    LANES,
                    0,
                    total,
                    d,
                    weight,
                );
                let steady = State::steady(vld1q_u8(v.as_ptr()), skip.weight_divisor);
                assert_eq!(regs(&state), regs(&steady), "d {d} round {round}: registers after R constant steps");
                let mut b = [0u8; 16];
                vst1q_u8(b.as_mut_ptr(), final_bytes(&steady.u));
                assert_eq!(b, v, "d {d}: steady output");
            }
            // The last L outputs only see the constant: they are v.
            assert_eq!(&out[(total - 1) * LANES..], &v, "d {d}");
        }
    }
    let mut rng = Rng(0x2_4);
    for d in 4..=254 {
        let plan = PlanGauss::from_window(d);
        for v in 0..=255u32 {
            assert_eq!(plan.final_scale(v * plan.divisor as u32), v as u8, "d {d} v {v}");
        }
        let skip = SkipPlan::new(&plan, InteriorSkip::Always);
        for chunk in 0..16 {
            let v: [u8; 16] = core::array::from_fn(|l| (chunk * 16 + l) as u8);
            let mut b = [0u8; 16];
            unsafe {
                vst1q_u8(b.as_mut_ptr(), final_bytes(&State::steady(vld1q_u8(v.as_ptr()), skip.weight_divisor).u))
            };
            assert_eq!(b, v, "d {d}");
        }
        match (d % 2 == 0, d > NARROW_T0_MAX_WINDOW) {
            (false, false) => recurrence::<false, false>(d, &mut rng),
            (false, true) => recurrence::<false, true>(d, &mut rng),
            (true, false) => recurrence::<true, false>(d, &mut rng),
            (true, true) => recurrence::<true, true>(d, &mut rng),
        }
    }
}

/// σ for the shape tests: odd and even windows, narrow and wide T0, the clamp.
const SKIP_SIGMAS: [f64; 11] = [2.0, 3.3, 5.0, 6.56, 14.36, 24.6, 36.87, 64.0, 97.5, 128.0, 135.0];

/// Anti-aliased discs, rounded rects and rings from 20 to 1000 px, every σ: skip on == off == scalar.
#[test]
fn skip_shapes_all_sizes_match_scalar() {
    let mut skipped = 0;
    for &size in &[20usize, 33, 64, 100, 129, 250, 333, 512, 700, 1000] {
        for shape in [Shape::Disc, Shape::RoundRect, Shape::Ring] {
            let (w, h) = (size, size * 3 / 4 + 1);
            let stride = w + size % 5;
            let src = shape_mask(w, h, stride, shape);
            for &sigma in &SKIP_SIGMAS {
                skipped += check(&src, w, h, stride, sigma, &format!("{shape:?} {w}x{h}/{stride} sigma {sigma}")).0;
            }
        }
    }
    assert!(skipped > 0);
}

/// Several solid spans per row: two discs, a "C", a ring, and stripes narrower and wider than the kernel (around
/// L and R = L + 2), both directions.
#[test]
fn skip_several_spans_match_scalar() {
    for &sigma in &[2.0, 5.0, 6.56, 14.36, 36.87, 64.0, 97.5] {
        let plan = PlanGauss::new(sigma);
        let l = plan.sliding_window as f64;
        let mut shapes = vec![Shape::TwoDiscs, Shape::C, Shape::Ring];
        for bar in [0.3 * l, l - 1.0, l, l + 1.0, l + 2.0, l + 3.0, 2.0 * l + 0.5] {
            for gap in [0.5 * l, l + 2.0] {
                shapes.push(Shape::VStripes { bar, gap });
                shapes.push(Shape::HStripes { bar, gap });
            }
        }
        for shape in shapes {
            let (w, h) = match shape {
                Shape::VStripes { .. } | Shape::HStripes { .. } => {
                    (4 * plan.sliding_window + 37, 4 * plan.sliding_window + 21)
                }
                _ => (300, 211),
            };
            let src = shape_mask(w, h, w + 3, shape);
            check(&src, w, h, w + 3, sigma, &format!("{shape:?} {w}x{h} sigma {sigma}"));
        }
    }
}

/// Solid stretches exactly at the boundaries: run lengths k = L − 2 … L + 2 (outputs fully inside the run start at
/// k = L) and R − 1 … R + 1 (the state restart needs k ≥ R) for every kind of window, in rows and in columns, with
/// the run surrounded by noise that never equals the run, touching the left / top edge, touching the right / bottom
/// edge, all-255 and per-line constants (each line its own value). For the interior case the number of skipped
/// horizontal steps is asserted exactly: k − L + 1 per row group iff k ≥ R, plus the steps past the output.
#[test]
fn skip_run_length_boundaries() {
    let mut rng = Rng(0x2_4_1);
    for d in [4usize, 5, 9, 12, 27, 46, 63, 64, 65, 69, 120, 183, 241] {
        let sigma = sigma_for_window(d);
        let plan = PlanGauss::new(sigma);
        let (l, r) = (plan.sliding_window, plan.sliding_window + 2);
        let mut ks: Vec<usize> = (l - 2..=l + 2).chain(r - 1..=r + 1).collect();
        ks.dedup();
        for &k in &ks {
            for (variant, per_line) in [
                ("interior", false),
                ("interior", true),
                ("left", false),
                ("left", true),
                ("right", false),
                ("right", true),
            ] {
                let pad = 7 + d % 5;
                let (lead, trail) = match variant {
                    "interior" => (pad, pad + 3),
                    "left" => (0, pad),
                    _ => (pad, 0),
                };
                let width = lead + k + trail;
                let height = 37;
                let value = |y: usize| if per_line { (y * 53 % 254 + 1) as u8 } else { 255 };
                let mut src = vec![0u8; width * height];
                for y in 0..height {
                    for x in 0..width {
                        src[y * width + x] = if x >= lead && x < lead + k {
                            value(y)
                        } else {
                            // Noise in 1..=254, never the run's value.
                            let mut n = 1 + rng.below(254) as u8;
                            if n == value(y) {
                                n = n % 254 + 1;
                            }
                            n
                        };
                    }
                }
                let what = format!("d {d} k {k} (L {l}) {variant} per_line {per_line}");
                let [(skipped, _), _] = check_passes(&src, width, height, width, sigma, &what);
                if variant == "interior" {
                    let dst_w = width + 2 * plan.border;
                    let tail = dst_w.next_multiple_of(16) - dst_w; // steps past the output, after the zero trail
                    let per_group = if k >= r { k - l + 1 } else { 0 } + tail;
                    assert_eq!(skipped, height.div_ceil(16) * per_group, "{what}: skipped steps");
                }
                // The same in columns.
                let t: Vec<u8> = (0..width)
                    .flat_map(|x| (0..height).map(move |y| (x, y)))
                    .map(|(x, y)| src[y * width + x])
                    .collect();
                check(&t, height, width, height, sigma, &format!("{what} transposed"));
            }
        }
    }
}

/// All-0 and all-255 masks (touching every edge), sizes around multiples of 16, strides wider than the width; a
/// zero mask skips everything but the steps whose output is not zero (none) in `Always`.
#[test]
fn skip_flat_masks_match_scalar() {
    let mut rng = Rng(9);
    for &sigma in &[2.0, 6.56, 24.6, 36.87, 97.5, 128.0] {
        for &(w, h) in &[(1usize, 1usize), (15, 17), (16, 16), (17, 33), (100, 64), (300, 1), (1, 300), (513, 257)] {
            for kind in [Kind::Zero, Kind::Full] {
                let stride = w + (w % 3);
                let src = mask(w, h, stride, kind, &mut rng);
                let (skipped, total) = check(&src, w, h, stride, sigma, &format!("{kind:?} {w}x{h} sigma {sigma}"));
                if matches!(kind, Kind::Zero) {
                    assert_eq!(skipped, total, "zero mask {w}x{h} sigma {sigma}");
                }
            }
        }
    }
}

/// Lines that are constant but each with its own value (rows constant, or columns constant), plus a rect of one
/// arbitrary level: the run's vector need not be 0 or 255, nor the same in every lane.
#[test]
fn skip_per_line_constants_match_scalar() {
    for &sigma in &[2.0, 5.0, 14.36, 36.87, 64.0, 128.0] {
        for &(w, h) in &[(97usize, 61usize), (260, 300), (513, 40)] {
            let rows: Vec<u8> = (0..h).flat_map(|y| (0..w).map(move |_| (y * 37 % 256) as u8)).collect();
            let cols: Vec<u8> = (0..h).flat_map(|_| (0..w).map(|x| (x * 29 % 256) as u8)).collect();
            let level: Vec<u8> = (0..h)
                .flat_map(|y| {
                    (0..w).map(move |x| if x > w / 7 && x < w - 3 && y > 2 && y < h * 5 / 6 { 173 } else { 0 })
                })
                .collect();
            for (name, src) in [("rows", &rows), ("cols", &cols), ("level", &level)] {
                check(src, w, h, w, sigma, &format!("{name} {w}x{h} sigma {sigma}"));
            }
        }
    }
}

/// Random scenes: 1–4 random primitives (discs, rounded rects, rings, stripes, flat rects of a random level, noise
/// patches) over a 0 or 255 background, random sizes 1–420, strides, σ 2–135.
#[test]
fn skip_random_scenes_match_scalar() {
    #[derive(Clone, Copy, Debug)]
    enum Prim {
        Disc(f64, f64, f64),
        RRect(f64, f64, f64, f64, f64),
        Ring(f64, f64, f64, f64),
        Stripes(bool, f64, f64),
        Level(usize, usize, usize, usize, u8),
        Noise(usize, usize, usize, usize),
    }
    let mut rng = Rng(0x2_4_2);
    let unit = |rng: &mut Rng| rng.below(1 << 20) as f64 / (1 << 20) as f64;
    for case in 0..400 {
        let w = 1 + rng.below(if case % 4 == 0 { 420 } else { 160 }) as usize;
        let h = 1 + rng.below(if case % 4 == 1 { 420 } else { 160 }) as usize;
        let stride = w + rng.below(5) as usize;
        // σ log-uniform in [2, 135].
        let sigma = 2.0 * (135.0f64 / 2.0).powf(unit(&mut rng));
        let (wf, hf) = (w as f64, h as f64);
        let mut prims = Vec::new();
        for _ in 0..1 + rng.below(4) {
            let (x, y) = (unit(&mut rng) * wf, unit(&mut rng) * hf);
            let s = (0.1 + unit(&mut rng)) * wf.max(hf);
            prims.push(match rng.below(6) {
                0 => Prim::Disc(x, y, s / 2.0),
                1 => Prim::RRect(x - s / 2.0, y - s / 3.0, x + s / 2.0, y + s / 3.0, unit(&mut rng) * s / 3.0),
                2 => Prim::Ring(x, y, s / 2.0, unit(&mut rng) * s / 2.0),
                3 => Prim::Stripes(rng.below(2) == 0, 1.0 + unit(&mut rng) * s, 1.0 + unit(&mut rng) * s),
                4 => {
                    let (x0, y0) = (rng.below(w as u32) as usize, rng.below(h as u32) as usize);
                    Prim::Level(
                        x0,
                        y0,
                        x0 + rng.below(w as u32) as usize,
                        y0 + rng.below(h as u32) as usize,
                        rng.next() as u8,
                    )
                }
                _ => {
                    let (x0, y0) = (rng.below(w as u32) as usize, rng.below(h as u32) as usize);
                    Prim::Noise(
                        x0,
                        y0,
                        x0 + rng.below(1 + w as u32 / 4) as usize,
                        y0 + rng.below(1 + h as u32 / 4) as usize,
                    )
                }
            });
        }
        let background = if rng.below(4) == 0 { 255 } else { 0 };
        let mut src = vec![background; stride * h];
        for p in &prims {
            let shape = |px: f64, py: f64| match *p {
                Prim::Disc(cx, cy, r) => in_disc(px, py, cx, cy, r),
                Prim::RRect(l, t, r, b, rad) => in_rrect(px, py, (l, t, r, b), rad),
                Prim::Ring(cx, cy, ro, ri) => in_disc(px, py, cx, cy, ro) && !in_disc(px, py, cx, cy, ri),
                Prim::Stripes(vertical, bar, gap) => (if vertical { px } else { py }).rem_euclid(bar + gap) < bar,
                _ => false,
            };
            for yy in 0..h {
                for xx in 0..w {
                    let at = &mut src[yy * stride + xx];
                    match *p {
                        Prim::Level(x0, y0, x1, y1, v) => {
                            if (x0..x1).contains(&xx) && (y0..y1).contains(&yy) {
                                *at = v;
                            }
                        }
                        Prim::Noise(x0, y0, x1, y1) => {
                            if (x0..x1).contains(&xx) && (y0..y1).contains(&yy) {
                                *at = rng.next() as u8;
                            }
                        }
                        _ => {
                            let c = coverage(xx, yy, shape);
                            if background == 0 { *at = (*at).max(c) } else { *at = (*at).min(255 - c) }
                        }
                    }
                }
            }
        }
        check(
            &src,
            w,
            h,
            stride,
            sigma,
            &format!("case {case}: {w}x{h}/{stride} sigma {sigma} bg {background} {prims:?}"),
        );
    }
}

/// The default (`Auto`) does skip on a large flat interior, and does not detect when the line is shorter than the
/// kernel (small shape, big blur).
#[test]
fn auto_skips_only_where_it_pays() {
    let rr = shape_mask(525, 525, 525, Shape::RoundRect);
    take_steps();
    blur_a8_with(&rr, 525, 525, 525, 36.87, InteriorSkip::Auto);
    let (skipped, total) = take_steps();
    assert!(skipped * 3 > total, "rounded rect 525 sigma 36.87: {skipped} / {total}");
    let disc = shape_mask(126, 126, 126, Shape::Disc);
    take_steps();
    blur_a8_with(&disc, 126, 126, 126, 97.5, InteriorSkip::Auto);
    assert_eq!(take_steps().0, 0);
}

// ---- timing (Mac only, indicative) -------------------------------------------------------------------------

/// `cargo test --release timing_skip_mac -- --ignored --nocapture`: interior skip off vs on (`Auto`), for masks
/// like Compose's at density 2.625: an anti-aliased rounded rect (corners 16 dp, capped at half the side)
/// and a disc of 48 / 200 / 360 dp (126 / 525 / 945 px), blur radius 4 / 24 / 64 dp (σ = 0.57735·r_px + 0.5 =
/// 6.56 / 36.87 / 97.5). The shape sits at offset r_px (Compose draws it `outset / 2` into its layer), and the
/// mask is Skia's: shape bounds outset by ½, rounded out (so a fractional r_px gives half-covered edge pixels, an
/// integer one a zero column and row on each side). Plus a noise mask: detection on where `Auto` would turn it on,
/// nothing to skip (the worst case of the detection cost). Off and on alternate in ABBA order on one scratch;
/// median and 10th percentile. **Mac only (Apple Silicon), indicative**; identical code in both modes (rows where
/// detection is off) reads ±2 %, so call < 3 % a tie. The project's numbers come from Microbenchmark on the devices.
#[test]
#[ignore]
fn timing_skip_mac() {
    use std::time::Instant;
    let density = 2.625;
    println!(
        "Mac only, indicative. size dp (px) | r dp | sigma (d) | shape | mask -> out | off ms p50 | on ms p50 | saved p50 | saved p10 | steps skipped"
    );
    for &size_dp in &[48.0f64, 200.0, 360.0] {
        for &r_dp in &[4.0f64, 24.0, 64.0] {
            let n = size_dp * density;
            let r_px = r_dp * density;
            let sigma = skia::mask_blur_sigma_from_radius(r_px as f32);
            let (left, right) = ((r_px - 0.5).floor(), (r_px + n + 0.5).ceil());
            let side = (right - left) as usize;
            let o = r_px - left; // the shape's left / top edge in mask coordinates
            for shape in ["rrect", "disc", "noise"] {
                let src = match shape {
                    "rrect" => render(side, side, side, |x, y| {
                        in_rrect(x, y, (o, o, o + n, o + n), (16.0 * density).min(n / 2.0))
                    }),
                    "disc" => render(side, side, side, |x, y| in_disc(x, y, o + n / 2.0, o + n / 2.0, n / 2.0)),
                    _ => {
                        let mut rng = Rng(11);
                        (0..side * side).map(|_| rng.next() as u8).collect()
                    }
                };
                let g = blur_geometry(side, side, sigma);
                let mut dst = [vec![0u8; g.width * g.height], vec![0u8; g.width * g.height]];
                let mut scratch = Scratch::new();
                let runs = if side > 600 {
                    200
                } else if side > 300 {
                    400
                } else {
                    2000
                };
                let mut t = [Vec::new(), Vec::new()];
                for i in 0..runs + 4 {
                    let order = if i % 4 == 0 || i % 4 == 3 { [0, 1] } else { [1, 0] };
                    for m in order {
                        let mode = [InteriorSkip::Off, InteriorSkip::Auto][m];
                        let start = Instant::now();
                        blur_a8_window_into(
                            &src,
                            side,
                            side,
                            side,
                            sigma,
                            Window::full(g.width, g.height),
                            &mut dst[m],
                            g.width,
                            Store::Replace,
                            &mut scratch,
                            mode,
                        );
                        if i >= 4 {
                            t[m].push(start.elapsed().as_secs_f64() * 1e3);
                        }
                    }
                }
                assert_eq!(dst[0], dst[1]);
                for v in t.iter_mut() {
                    v.sort_by(|a, b| a.partial_cmp(b).unwrap());
                }
                let p = |v: &Vec<f64>, q: usize| v[v.len() * q / 100];
                take_steps();
                blur_a8_window_into(
                    &src,
                    side,
                    side,
                    side,
                    sigma,
                    Window::full(g.width, g.height),
                    &mut dst[1],
                    g.width,
                    Store::Replace,
                    &mut scratch,
                    InteriorSkip::Auto,
                );
                let (skipped, total) = take_steps();
                println!(
                    "{size_dp} ({n:.0}) | {r_dp} | {sigma:.2} ({}) | {shape} | {side} -> {} | {:.3} | {:.3} | {:+.1}% | {:+.1}% | {:.1}%",
                    PlanGauss::new(sigma).window,
                    g.width,
                    p(&t[0], 50),
                    p(&t[1], 50),
                    100.0 * (p(&t[0], 50) - p(&t[1], 50)) / p(&t[0], 50),
                    100.0 * (p(&t[0], 10) - p(&t[1], 10)) / p(&t[0], 10),
                    100.0 * skipped as f64 / total as f64
                );
            }
        }
    }
}
