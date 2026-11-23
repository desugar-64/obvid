// Copyright 2026, Serhii Yaremych (desugar-64) and the Obvid project contributors
// SPDX-License-Identifier: Apache-2.0
// Implements the arithmetic of Skia's SkMaskBlurFilter (BSD-3-Clause), see NOTICE.

//! The three-box recurrence on 16 lines at once (one line per lane): the registers, the step loop and Skia's
//! `finalScale` from the accumulators. The scan around it (skips, passes) is in `crate::blur`.
//!
//! # What is computed
//! For one line (a row in the horizontal pass, a column in the vertical one) Skia's scan produces, at every output
//! position i = 0..len + 2·border, `finalScale(Σ_k K[k]·x[i − k])`: the full convolution of the zero-padded line with
//! the combined kernel K of three boxes of widths d, d, d (odd window d) or d, d, d + 1 (even). Its forward phase,
//! its `noChangeCount` zero steps and its backward fill from the right end all produce exactly that value (K is
//! symmetric; see `skia::plan_gauss_scan` and the proof in the tests, which compare against it).
//! The sums are exact integers (the true values fit in u32), so any exact way of computing the same convolution
//! gives the same `sum2`, and `finalScale` is applied to the same integer. Axis order and per-axis rounding are
//! Skia's: horizontal first, rounded to bytes, then vertical.
//!
//! # How (per line, 16 lines in the 16 lanes of a register)
//! The three running sums with ring buffers are replaced by their equivalent "differences, then integrators" form
//! (a cascaded-integrator-comb filter): with z the one-step delay,
//! `box_a · box_b · box_c = Δ / (1 − z)³`, `Δ = (1 − z^a)(1 − z^b)(1 − z^c)`, so
//! - `e[n]  = Δ·x[n]`: `x[n] − 3x[n−d] + 3x[n−2d] − x[n−3d]` (odd) or
//!   `x[n] − 2x[n−d] − x[n−d−1] + x[n−2d] + 2x[n−2d−1] − x[n−3d−1]` (even), |e| ≤ 1020: i16;
//! - `T0 += e` (i16 for d ≤ 64, where |T0| ≤ 510·d ≤ 32640; i32 above), `T1 += T0` (i32, |T1| ≤ 255·d²),
//! - and `sum2 = Σ T1`. Only the input history is read back (bytes at fixed offsets), no ring buffers of sums.
//!
//! `finalScale(sum2) = (W·sum2 + 2³¹) >> 32` (u64 W = round(2³²/divisor)) is not formed from sum2 at all: the kernel
//! accumulates `U = 2³¹ + W·sum2` directly in 64-bit lanes, `U += W·T1` (`smlal`, W < 2²⁷, |T1| < 2²⁴, signed product
//! exact), starting from U = 2³¹. U ≡ 2³¹ + W·sum2 (mod 2⁶⁴), and the true value is < 2⁴⁰, so U *is* Skia's
//! `fWeight * sum + kHalf`, and the output byte is bits 32..40 of U (`uzp2` takes the high words, two `uzp1` the low
//! byte of each; Skia's `SkTo<uint8_t>` truncation is the same byte). No division approximation is involved; the
//! test `final_scale_from_accumulator_is_exact` checks every output boundary of every window anyway.

use core::arch::aarch64::*;

use super::LANES;

/// Largest window whose first integrator `T0` fits i16: |T0| ≤ 2·255·d ≤ 32767.
pub const NARROW_T0_MAX_WINDOW: usize = 64;

/// The recurrence's registers for 16 lines: T0 (i16 × 16 or i32 × 16), T1 (i32 × 16), U (i64 × 16) = 2³¹ + W·sum2.
/// `u[k]` holds lanes 2k, 2k + 1.
#[derive(Clone, Copy)]
pub(crate) struct State {
    pub t0n: [int16x8_t; 2],
    pub t0w: [int32x4_t; 4],
    pub t1: [int32x4_t; 4],
    pub u: [int64x2_t; 8],
}

impl State {
    /// Line start (all input before it is zero).
    #[inline(always)]
    pub fn zero() -> State {
        // SAFETY: plain register operations.
        unsafe {
            State {
                t0n: [vdupq_n_s16(0); 2],
                t0w: [vdupq_n_s32(0); 4],
                t1: [vdupq_n_s32(0); 4],
                u: [vdupq_n_s64(1i64 << 31); 8],
            }
        }
    }

    /// The state after R steps of constant input `v` (per lane): T0 = T1 = 0, U = 2³¹ + W·divisor·v.
    #[inline(always)]
    pub fn steady(v: uint8x16_t, weight_divisor: u64) -> State {
        let mut bytes = [0u8; LANES];
        // SAFETY: 16-byte store into a 16-byte array, 2-lane loads from a 16-lane array.
        unsafe {
            vst1q_u8(bytes.as_mut_ptr(), v);
            let lanes: [i64; LANES] =
                core::array::from_fn(|i| ((1u64 << 31) + weight_divisor * bytes[i] as u64) as i64);
            let mut s = State::zero();
            for (k, u) in s.u.iter_mut().enumerate() {
                *u = vld1q_s64(lanes.as_ptr().add(2 * k));
            }
            s
        }
    }
}

/// Steps `from..to` of the three-box blur on 16 lines at once, from `state` (left updated), one output vector per
/// step. Step n reads the vector at `input + n·in_stride` and the taps `d, 2d, 3d` (odd) or `d, d+1, 2d, 2d+1, 3d+1`
/// (even) vectors before it, which must exist (zeros before the line start); it writes `out + n·out_stride`.
///
/// Not inlined: the hot loop then compiles the same whatever its caller does (inlined into the pass loops, the wide
/// kernel loses ~10 % to register allocation).
///
/// # Safety
/// Every address above is readable / writable for 16 bytes.
#[allow(clippy::too_many_arguments)]
#[inline(never)]
pub(crate) unsafe fn steps<const EVEN: bool, const WIDE: bool>(
    state: &mut State,
    input: *const u8,
    in_stride: usize,
    out: *mut u8,
    out_stride: usize,
    from: usize,
    to: usize,
    d: usize,
    weight: i32,
) {
    unsafe {
        let s = in_stride;
        let (o_d, o_d1, o_2d, o_2d1, o_3d) = if EVEN {
            (d * s, (d + 1) * s, 2 * d * s, (2 * d + 1) * s, (3 * d + 1) * s)
        } else {
            (d * s, 0, 2 * d * s, 0, 3 * d * s)
        };

        let State { mut t0n, mut t0w, mut t1, mut u } = *state;

        // With LTO every caller is visible and `weight` is provably non-negative; LLVM then drops the signed
        // widening multiply-accumulate below and emits scalar 64-bit multiplies per lane. An opaque value keeps `smlal`.
        let weight = core::hint::black_box(weight);

        let mut p = input.add(from * s);
        let mut q = out.add(from * out_stride);
        for _ in from..to {
            let x = vld1q_u8(p);
            let sub = |a: uint8x16_t, b: uint8x16_t| {
                (
                    vreinterpretq_s16_u16(vsubl_u8(vget_low_u8(a), vget_low_u8(b))),
                    vreinterpretq_s16_u16(vsubl_high_u8(a, b)),
                )
            };
            let (e_lo, e_hi) = if EVEN {
                let y = vld1q_u8(p.sub(o_d));
                let y1 = vld1q_u8(p.sub(o_d1));
                let z = vld1q_u8(p.sub(o_2d));
                let z1 = vld1q_u8(p.sub(o_2d1));
                let w = vld1q_u8(p.sub(o_3d));
                // (x[n] − x[n−3d−1]) + (x[n−2d] − x[n−d−1]) + 2·(x[n−2d−1] − x[n−d])
                let a = sub(x, w);
                let b = sub(z, y1);
                let c = sub(z1, y);
                (vmlaq_n_s16(vaddq_s16(a.0, b.0), c.0, 2), vmlaq_n_s16(vaddq_s16(a.1, b.1), c.1, 2))
            } else {
                let y = vld1q_u8(p.sub(o_d));
                let z = vld1q_u8(p.sub(o_2d));
                let w = vld1q_u8(p.sub(o_3d));
                // (x[n] − x[n−3d]) + 3·(x[n−2d] − x[n−d])
                let a = sub(x, w);
                let b = sub(z, y);
                (vmlaq_n_s16(a.0, b.0, 3), vmlaq_n_s16(a.1, b.1, 3))
            };
            if WIDE {
                t0w[0] = vaddw_s16(t0w[0], vget_low_s16(e_lo));
                t0w[1] = vaddw_high_s16(t0w[1], e_lo);
                t0w[2] = vaddw_s16(t0w[2], vget_low_s16(e_hi));
                t0w[3] = vaddw_high_s16(t0w[3], e_hi);
                for i in 0..4 {
                    t1[i] = vaddq_s32(t1[i], t0w[i]);
                }
            } else {
                t0n[0] = vaddq_s16(t0n[0], e_lo);
                t0n[1] = vaddq_s16(t0n[1], e_hi);
                t1[0] = vaddw_s16(t1[0], vget_low_s16(t0n[0]));
                t1[1] = vaddw_high_s16(t1[1], t0n[0]);
                t1[2] = vaddw_s16(t1[2], vget_low_s16(t0n[1]));
                t1[3] = vaddw_high_s16(t1[3], t0n[1]);
            }
            for i in 0..4 {
                u[2 * i] = vmlal_n_s32(u[2 * i], vget_low_s32(t1[i]), weight);
                u[2 * i + 1] = vmlal_high_n_s32(u[2 * i + 1], t1[i], weight);
            }
            vst1q_u8(q, final_bytes(&u));
            // Wrapping: after the last step both point one line past the last one read or written, which can lie
            // outside the allocation (`add` would be undefined behaviour there even unused).
            p = p.wrapping_add(s);
            q = q.wrapping_add(out_stride);
        }
        *state = State { t0n, t0w, t1, u };
    }
}

/// Skia's `finalScale` from the accumulators: `(U >> 32) as u8` for each of the 16 lanes (lane order kept).
/// U = 2³¹ + W·sum2 < 2⁴⁰, so the high word is the output and fits a byte.
#[inline(always)]
pub(crate) fn final_bytes(u: &[int64x2_t; 8]) -> uint8x16_t {
    // SAFETY: plain register operations (NEON is part of the aarch64 baseline).
    unsafe {
        let w = |i: usize| vreinterpretq_u32_s64(u[i]);
        let h0 = vuzp2q_u32(w(0), w(1));
        let h1 = vuzp2q_u32(w(2), w(3));
        let h2 = vuzp2q_u32(w(4), w(5));
        let h3 = vuzp2q_u32(w(6), w(7));
        let a = vuzp1q_u16(vreinterpretq_u16_u32(h0), vreinterpretq_u16_u32(h1));
        let b = vuzp1q_u16(vreinterpretq_u16_u32(h2), vreinterpretq_u16_u32(h3));
        vuzp1q_u8(vreinterpretq_u8_u16(a), vreinterpretq_u8_u16(b))
    }
}

#[cfg(test)]
#[path = "box3_tests.rs"]
mod tests;
