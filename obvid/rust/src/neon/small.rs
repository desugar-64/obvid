// Copyright 2026, Serhii Yaremych (desugar-64) and the Obvid project contributors
// SPDX-License-Identifier: Apache-2.0

//! σ < 2: Skia's `small_blur` (the direct Gaussian in 8.8 fixed point) on 16 outputs at a time, the same bytes as
//! the scalar port [`crate::skia::small_blur_into`] for every input.
//!
//! # What is computed
//! In either direction, output j of a line is `to_byte(0x80 + Σ_{k=0..2r} mulhi(s[j − k] << 8, G[|k − r|]))`, all
//! in wrapping `u16`, with `mulhi(a, b) = (a·b) >> 16`, G the 0.16 weights of [`SmallBlurFactors`] and sources
//! outside the line contributing nothing. The vertical pass runs first (source columns into the destination from
//! column `radius_x` on, `height + 2·radius_y` rows), then the horizontal pass, in place on every row.
//!
//! # How
//! - **The product without widening the source.** For a byte s and a weight g = 256·gh + gl (gh, gl bytes),
//!   `mulhi(s << 8, g) = ⌊s·g / 256⌋ = s·gh + ⌊s·gl / 256⌋` exactly (s·gh·256 is a multiple of 256, so the floor
//!   only acts on s·gl). Per tap and 8 lanes: `umlal` (s·gh into the accumulator, u8 × u8 → u16) and `umull` +
//!   `usra` (s·gl, then its high byte added). The weights' bytes are broadcast once per pass.
//! - **Wrapping.** Every step is an addition mod 2¹⁶ (`umlal` and `usra` do not saturate), and addition mod 2¹⁶ is
//!   associative and commutative: each output receives the same terms as in Skia's (or the scalar port's) order,
//!   so the sum is the same `u16` even where it wraps. The s·gh and ⌊s·gl / 256⌋ terms go to separate accumulators
//!   (two shorter dependency chains) that are added at the end; `vshrn #8` then takes the byte.
//! - **Vertical pass**, one output row at a time, 16 columns per block: tap k reads source row j − k, or a zero row
//!   (the row buffer, zeroed first) above or below the source, so every tap is unconditional. A last block narrower
//!   than 16 is moved back to end at the last column and overlaps the previous one (the overlapped bytes are written
//!   twice with the same value; the pass never reads the destination). A source narrower than 16 goes through a
//!   16-byte stack copy per load and store.
//! - **Horizontal pass**, one row at a time: the row's input (the `width` bytes at `radius_x`) is copied into the
//!   row buffer between 2r zeros before and zeros after, so output j reads the buffer at j..=j + 2r, one unaligned
//!   16-byte load per tap and block (taps are symmetric, so the order of the loads does not matter). The output
//!   blocks go straight into the row; the last block overlaps the previous one as in the vertical pass, and a row
//!   narrower than 16 stores through a stack copy. Loads from the buffer (in L1) are cheaper than a transpose to
//!   reuse the vertical kernel: the arithmetic per tap is the same either way, and the shifted loads need no
//!   16-row grouping, no back-transpose and no handling of partial row groups.

use alloc::vec::Vec;
use core::arch::aarch64::*;

use super::LANES;
use crate::skia::{GAUSS_ARRAY_MAX, SmallBlurFactors};

/// `kHalf`: one half in 8.8, added once to every output (round half up).
const HALF: u16 = 0x80;

/// The weights G[0..=r] split into their high and low bytes, each broadcast to 16 lanes.
#[derive(Clone, Copy)]
struct Taps {
    hi: [uint8x16_t; GAUSS_ARRAY_MAX],
    lo: [uint8x16_t; GAUSS_ARRAY_MAX],
}

impl Taps {
    fn new(factors: &SmallBlurFactors) -> Taps {
        // SAFETY: plain register operations.
        unsafe {
            Taps {
                hi: core::array::from_fn(|i| vdupq_n_u8((factors.factors[i] >> 8) as u8)),
                lo: core::array::from_fn(|i| vdupq_n_u8(factors.factors[i] as u8)),
            }
        }
    }
}

/// 16 outputs: `to_byte(0x80 + Σ_{k=0..2R} mulhi(load(k) << 8, G[|k − R|]))` per lane (module docs, "How").
#[inline(always)]
fn blur16<const R: usize>(taps: &Taps, load: impl Fn(usize) -> uint8x16_t) -> uint8x16_t {
    // SAFETY: plain register operations.
    unsafe {
        let mut hi_lo = vdupq_n_u16(HALF);
        let mut hi_hi = vdupq_n_u16(HALF);
        let mut lo_lo = vdupq_n_u16(0);
        let mut lo_hi = vdupq_n_u16(0);
        for k in 0..=2 * R {
            let s = load(k);
            let g = k.abs_diff(R);
            let (gh, gl) = (taps.hi[g], taps.lo[g]);
            hi_lo = vmlal_u8(hi_lo, vget_low_u8(s), vget_low_u8(gh));
            hi_hi = vmlal_high_u8(hi_hi, s, gh);
            lo_lo = vsraq_n_u16::<8>(lo_lo, vmull_u8(vget_low_u8(s), vget_low_u8(gl)));
            lo_hi = vsraq_n_u16::<8>(lo_hi, vmull_high_u8(s, gl));
        }
        let lo = vaddq_u16(hi_lo, lo_lo);
        let hi = vaddq_u16(hi_hi, lo_hi);
        vshrn_high_n_u16::<8>(vshrn_n_u16::<8>(lo), hi)
    }
}

/// The first `n` (< 16) bytes at `p`, zeros in the other lanes.
///
/// # Safety
/// `p` is readable for `n` bytes.
#[inline(always)]
unsafe fn load_partial(p: *const u8, n: usize) -> uint8x16_t {
    let mut bytes = [0u8; LANES];
    // SAFETY: the caller's contract; `n` < 16.
    unsafe {
        core::ptr::copy_nonoverlapping(p, bytes.as_mut_ptr(), n);
        vld1q_u8(bytes.as_ptr())
    }
}

/// The first `n` (< 16) lanes of `v` into `p`.
///
/// # Safety
/// `p` is writable for `n` bytes.
#[inline(always)]
unsafe fn store_partial(p: *mut u8, v: uint8x16_t, n: usize) {
    let mut bytes = [0u8; LANES];
    // SAFETY: the caller's contract; `n` < 16.
    unsafe {
        vst1q_u8(bytes.as_mut_ptr(), v);
        core::ptr::copy_nonoverlapping(bytes.as_ptr(), p, n);
    }
}

/// The vertical pass with radius R: source column x into column x of `dst`, rows 0..height + 2R.
///
/// # Safety
/// `src` holds `height` rows of `width` (> 0) bytes, `stride` apart; `zero` is `width` readable zero bytes; `dst` is
/// writable for `height + 2R` rows of `width` bytes, `dst_stride` apart.
#[allow(clippy::too_many_arguments)]
unsafe fn vertical<const R: usize>(
    src: *const u8,
    width: usize,
    height: usize,
    stride: usize,
    taps: &Taps,
    zero: *const u8,
    dst: *mut u8,
    dst_stride: usize,
) {
    unsafe {
        let mut rows = [zero; 2 * GAUSS_ARRAY_MAX - 1];
        for j in 0..height + 2 * R {
            // Tap k reads source row j − k, or the zero row outside the source.
            for (k, row) in rows.iter_mut().enumerate().take(2 * R + 1) {
                *row = match j.checked_sub(k) {
                    Some(y) if y < height => src.add(y * stride),
                    _ => zero,
                };
            }
            let out = dst.add(j * dst_stride);
            if width < LANES {
                let v = blur16::<R>(taps, |k| load_partial(rows[k], width));
                store_partial(out, v, width);
                continue;
            }
            let mut x = 0;
            loop {
                let v = blur16::<R>(taps, |k| vld1q_u8(rows[k].add(x)));
                vst1q_u8(out.add(x), v);
                if x + LANES == width {
                    break;
                }
                // The last block ends at the last column (overlapping the previous one if width is not a multiple
                // of 16).
                x = (x + LANES).min(width - LANES);
            }
        }
    }
}

/// The horizontal pass with radius R, in place on `dst_h` rows of `width + 2R` bytes, `dst_stride` apart: the
/// `width` bytes at R are each row's input.
///
/// # Safety
/// `dst` is readable and writable for `dst_h` rows of `width + 2R` bytes, `dst_stride` apart; `buf` is
/// `width + 4R + 16` bytes, zero outside `2R..2R + width`.
unsafe fn horizontal<const R: usize>(
    dst: *mut u8,
    dst_stride: usize,
    dst_h: usize,
    width: usize,
    taps: &Taps,
    buf: *mut u8,
) {
    unsafe {
        let dst_w = width + 2 * R;
        for y in 0..dst_h {
            let row = dst.add(y * dst_stride);
            // buf[2R + i] = input i, so output j reads buf[j..=j + 2R] (G is symmetric: tap m has weight G[|m − R|]).
            core::ptr::copy_nonoverlapping(row.add(R), buf.add(2 * R), width);
            let buf = buf as *const u8;
            if dst_w < LANES {
                // Reads buf[0..16 + 2R], inside the buffer.
                let v = blur16::<R>(taps, |m| vld1q_u8(buf.add(m)));
                store_partial(row, v, dst_w);
                continue;
            }
            let mut x = 0;
            loop {
                // Reads up to buf[dst_w − 1 + 2R] = buf[width + 4R − 1].
                let v = blur16::<R>(taps, |m| vld1q_u8(buf.add(x + m)));
                vst1q_u8(row.add(x), v);
                if x + LANES == dst_w {
                    break;
                }
                x = (x + LANES).min(dst_w - LANES);
            }
        }
    }
}

/// `small_blur` into `dst`: `height + 2·radius_y` rows of `width + 2·radius_x` bytes, `dst_stride` apart (bytes
/// between rows are left as they were); the bytes of [`crate::skia::small_blur_into`]. Blurs vertically into the
/// destination, then horizontally in place. `row` is the row buffer of both passes (`width + 4·radius_x + 16`
/// bytes): grown, never shrunk, so a reused one makes this allocation-free. An empty source gives zeros. Panics if
/// the output size overflows, `src` or `dst` is too short or a stride is narrower than its rows.
#[allow(clippy::too_many_arguments)]
pub(crate) fn small_blur_into(
    src: &[u8],
    width: usize,
    height: usize,
    stride: usize,
    factors_x: &SmallBlurFactors,
    factors_y: &SmallBlurFactors,
    dst: &mut [u8],
    dst_stride: usize,
    row: &mut Vec<u8>,
) {
    let (rx, ry) = (factors_x.radius, factors_y.radius);
    assert!((1..=4).contains(&rx) && (1..=4).contains(&ry), "small_blur handles radius 1..=4");
    let (Some(dst_w), Some(dst_h)) = (width.checked_add(2 * rx), height.checked_add(2 * ry)) else {
        panic!("output size overflows");
    };
    assert!(dst_stride >= dst_w, "dst_stride smaller than the output width");
    let dst_span = (dst_h - 1).checked_mul(dst_stride).and_then(|n| n.checked_add(dst_w));
    assert!(dst_span.is_some_and(|n| dst.len() >= n), "dst too short");
    if width == 0 || height == 0 {
        for y in 0..dst_h {
            dst[y * dst_stride..y * dst_stride + dst_w].fill(0);
        }
        return;
    }
    assert!(stride >= width, "stride smaller than width");
    let src_span = (height - 1).checked_mul(stride).and_then(|n| n.checked_add(width));
    assert!(src_span.is_some_and(|n| src.len() >= n), "src too short");

    let buf = crate::blur::grown(row, width + 4 * rx + LANES);
    buf.fill(0);
    let (tx, ty) = (Taps::new(factors_x), Taps::new(factors_y));
    // SAFETY: sizes checked above. The vertical pass writes columns rx..rx + width of each output row and reads the
    // buffer (all zeros, ≥ width bytes) as its zero row; the horizontal pass then reads and rewrites whole rows.
    unsafe {
        let (s, b) = (src.as_ptr(), buf.as_mut_ptr());
        let d = dst.as_mut_ptr();
        let dv = d.add(rx);
        match ry {
            1 => vertical::<1>(s, width, height, stride, &ty, b, dv, dst_stride),
            2 => vertical::<2>(s, width, height, stride, &ty, b, dv, dst_stride),
            3 => vertical::<3>(s, width, height, stride, &ty, b, dv, dst_stride),
            _ => vertical::<4>(s, width, height, stride, &ty, b, dv, dst_stride),
        }
        match rx {
            1 => horizontal::<1>(d, dst_stride, dst_h, width, &tx, b),
            2 => horizontal::<2>(d, dst_stride, dst_h, width, &tx, b),
            3 => horizontal::<3>(d, dst_stride, dst_h, width, &tx, b),
            _ => horizontal::<4>(d, dst_stride, dst_h, width, &tx, b),
        }
    }
}

#[cfg(test)]
#[path = "small_tests.rs"]
mod tests;
