// Copyright 2026, Serhii Yaremych (desugar-64) and the Obvid project contributors
// SPDX-License-Identifier: Apache-2.0

//! Host tests for `neon::box3` (declared in box3.rs as `mod tests`): the integrators' ranges and the final scale
//! from the accumulators, against the scalar port's definitions.

use super::*;
use crate::skia::{self, PlanGauss};
use crate::test_support::Rng;

/// U accumulated as the kernel does it: 2³¹ plus W times `s` in pieces that fit i32 (the kernel adds W·T1 per
/// step with |T1| < 2²⁴), through the same `smlal` instructions, then [`final_bytes`]. Lanes in order.
fn neon_final_scale(plan: &PlanGauss, sums: &[u32; 16]) -> [u8; 16] {
    let weight = i32::try_from(plan.weight).unwrap();
    let mut u = [unsafe { vdupq_n_s64(1i64 << 31) }; 8];
    // s = a + b + c with every piece < 2³¹ (c = 1 or 0 for odd s, to exercise more than one piece).
    for piece in 0..3 {
        let part: [i32; 16] = core::array::from_fn(|l| {
            let s = sums[l] as i64;
            let a = s / 2;
            let c = s & 1;
            let b = s - a - c;
            [a, b, c][piece] as i32
        });
        for i in 0..4 {
            let t = unsafe { vld1q_s32(part[4 * i..].as_ptr()) };
            unsafe {
                u[2 * i] = vmlal_n_s32(u[2 * i], vget_low_s32(t), weight);
                u[2 * i + 1] = vmlal_high_n_s32(u[2 * i + 1], t, weight);
            }
        }
    }
    let mut out = [0u8; 16];
    unsafe { vst1q_u8(out.as_mut_ptr(), final_bytes(&u)) };
    out
}

/// The finalScale formulation is exact: for every window Skia can build (4..=254; 241 is the largest after the
/// 128 device-σ clamp) and every output level k = 1..=255, the smallest sum2 that Skia's
/// `(W·sum2 + 2³¹) >> 32` maps to ≥ k is found in closed form, checked against the scalar `final_scale`, and the
/// NEON accumulator + byte extraction gives the same byte at that sum2 and the one below it. Both functions are
/// non-decreasing in sum2, so equal boundaries for every k (and equal values at 0 and at the maximum 255·divisor)
/// mean equal values for every sum2 in [0, 255·divisor]. Plus 200 random sums per window.
#[test]
fn final_scale_from_accumulator_is_exact() {
    let mut rng = Rng(0x5eed_f00d);
    let mut boundaries = 0usize;
    for d in 4..=254 {
        let plan = PlanGauss::from_window(d);
        let top = 255 * plan.divisor;
        assert!(top < 1 << 32, "d {d}: sum2 fits u32");
        assert!(plan.weight < 1 << 31, "d {d}: weight fits i32");
        assert!(plan.weight * top + (1 << 31) < 1 << 40, "d {d}: U < 2^40");
        assert_eq!(plan.final_scale(top as u32), 255, "d {d}: top");
        let mut probe: Vec<u32> = vec![0, top as u32];
        for k in 1..=255u64 {
            // Smallest s with W·s + 2³¹ ≥ k·2³².
            let b = ((k << 32) - (1 << 31)).div_ceil(plan.weight);
            if b > top {
                continue;
            }
            assert!(
                plan.final_scale(b as u32) as u64 >= k && (plan.final_scale(b as u32 - 1) as u64) < k,
                "d {d} k {k}"
            );
            probe.push(b as u32 - 1);
            probe.push(b as u32);
            boundaries += 1;
        }
        for _ in 0..200 {
            probe.push((rng.next() as u64 % (top + 1)) as u32);
        }
        for chunk in probe.chunks(16) {
            let mut sums = [0u32; 16];
            sums[..chunk.len()].copy_from_slice(chunk);
            let got = neon_final_scale(&plan, &sums);
            for l in 0..16 {
                assert_eq!(got[l], plan.final_scale(sums[l]), "d {d} sum2 {}", sums[l]);
            }
        }
    }
    assert!(boundaries > 250 * 200, "boundaries checked: {boundaries}");
}

/// The integrators' ranges: |T0| ≤ 510·d (i16 up to d = 64), |T1| ≤ 255·d² (i32), measured on the worst-case
/// inputs (bars of width d at period 2d or 2d + 1, and random lines) in exact integers, with the same tap formulas as
/// the kernel; and Σ T1 equals Skia's sum2 at every position.
#[test]
fn integrator_ranges_and_identity() {
    let mut rng = Rng(77);
    for d in [4usize, 5, 9, 10, 63, 64, 65, 120, 241, 254] {
        let plan = PlanGauss::from_window(d);
        let even = d % 2 == 0;
        let lines: Vec<Vec<u8>> = vec![
            (0..10 * d).map(|i| if (i / d) % 2 == 0 { 255 } else { 0 }).collect(),
            (0..10 * d).map(|i| if (i / d) % 2 == 1 { 255 } else { 0 }).collect(),
            // Bars of d, gaps of d + 1: the worst case of the even taps.
            (0..10 * d).map(|i| if i % (2 * d + 1) < d { 255 } else { 0 }).collect(),
            (0..7 * d + 3).map(|_| rng.next() as u8).collect(),
            vec![255; 5 * d],
        ];
        let mut max_t0 = 0i64;
        for line in &lines {
            let n_out = plan.output_len(line.len());
            let x = |i: i64| if i >= 0 && (i as usize) < line.len() { line[i as usize] as i64 } else { 0 };
            let di = d as i64;
            let (mut t0, mut t1, mut t2) = (0i64, 0i64, 0i64);
            // Skia's sum2 at every output position, from the scalar scan's own definition (full convolution).
            let expected = skia::plan_gauss_blur_line(&plan, line);
            for n in 0..n_out as i64 {
                let e = if even {
                    x(n) - 2 * x(n - di) - x(n - di - 1) + x(n - 2 * di) + 2 * x(n - 2 * di - 1) - x(n - 3 * di - 1)
                } else {
                    x(n) - 3 * x(n - di) + 3 * x(n - 2 * di) - x(n - 3 * di)
                };
                assert!(e.abs() <= 1020);
                t0 += e;
                t1 += t0;
                t2 += t1;
                max_t0 = max_t0.max(t0.abs());
                assert!(t0.abs() <= 510 * di, "d {d}: T0 {t0}");
                assert!(t1.abs() <= 255 * di * di, "d {d}: T1 {t1}");
                assert!((0..=255 * plan.divisor as i64).contains(&t2), "d {d}: sum2 {t2}");
                assert_eq!(plan.final_scale(t2 as u32), expected[n as usize], "d {d} n {n}");
            }
        }
        // The bars reach the bound, so the i16 limit at d = 64 is tight, not loose.
        assert_eq!(max_t0, 510 * d as i64, "d {d}");
        if d <= NARROW_T0_MAX_WINDOW {
            assert!(510 * d <= i16::MAX as usize);
        }
    }
}
