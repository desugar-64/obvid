// Copyright 2026, Serhii Yaremych (desugar-64) and the Obvid project contributors
// SPDX-License-Identifier: Apache-2.0

//! A8 stores into a shadow bitmap, as Skia's `filterPath` blit (`SkA8_Blitter::blitMask`, black paint) does them:
//! source-over on 16 lanes (the window stores of `crate::blur`) and per row; and the inversion `255 − v` of an inner
//! shadow (Compose's opaque `Xor` rect over the blurred shape, which on A8 is exactly `255 − d`), in place or fused
//! with a copy; and the length of a run of one value at either end of a row (the inner shadow's hole).

use core::arch::aarch64::*;

use super::LANES;

/// Skia's A8 srcover mask blit for black paint (`SkA8_Blitter::blitMask`): `div255((255 − m)·d + 255·m)` with
/// `SkDiv255Round`'s `((prod + 128) · 257) >> 16`. The scalar reference of [`src_over16`].
#[inline]
pub(crate) fn src_over(d: u8, m: u8) -> u8 {
    let (d, m) = (d as u32, m as u32);
    ((((255 - m) * d + 255 * m + 128) * 257) >> 16) as u8
}

/// [`src_over`] on 16 lanes. `t = (255 − m)·d + 255·m + 128 ≤ 255² + 128` fits u16, and `(t · 257) >> 16` =
/// `(t + (t >> 8)) >> 8` (both are `⌊(t + t/256) / 256⌋`; `t + (t >> 8)` < 2¹⁶ too), so every step is a u16 lane
/// operation. Identical to [`src_over`] for all 256 × 256 inputs (exhaustive test).
#[inline(always)]
pub(crate) fn src_over16(d: uint8x16_t, m: uint8x16_t) -> uint8x16_t {
    // SAFETY: plain register operations.
    unsafe {
        let inv = vmvnq_u8(m); // 255 − m
        let half = vdupq_n_u16(128);
        let lo = vaddq_u16(vmlal_u8(vmull_u8(vget_low_u8(inv), vget_low_u8(d)), vget_low_u8(m), vdup_n_u8(255)), half);
        let hi = vaddq_u16(vmlal_high_u8(vmull_high_u8(inv, d), m, vdupq_n_u8(255)), half);
        vshrn_high_n_u16::<8>(vshrn_n_u16::<8>(vsraq_n_u16::<8>(lo, lo)), vsraq_n_u16::<8>(hi, hi))
    }
}

/// The source-over store of one row: `row[i] = src_over(row[i], mask[i])`, 16 lanes at a time, the tail scalar.
#[inline]
pub(crate) fn src_over_row(row: &mut [u8], mask: &[u8]) {
    assert!(row.len() == mask.len(), "row and mask lengths differ");
    let n = row.len();
    let mut i = 0;
    while i + LANES <= n {
        // SAFETY: i + 16 ≤ n, the length of both slices.
        unsafe {
            let d = vld1q_u8(row.as_ptr().add(i));
            let m = vld1q_u8(mask.as_ptr().add(i));
            vst1q_u8(row.as_mut_ptr().add(i), src_over16(d, m));
        }
        i += LANES;
    }
    for (d, &m) in row[i..].iter_mut().zip(&mask[i..]) {
        *d = src_over(*d, m);
    }
}

/// `255 − v`: what an opaque `Xor` rect leaves on an A8 pixel `v` (Skia's `xor_`, `div255(255·(255 − v))`, is exact
/// there). The scalar reference of [`invert_row`].
#[inline]
pub(crate) fn invert(v: u8) -> u8 {
    255 - v
}

/// [`invert`] over one row in place: 64 bytes per step (four registers), then 16, then a scalar tail. `255 − v` is
/// the bitwise complement of a byte (`vmvnq_u8`).
#[inline]
pub(crate) fn invert_row(row: &mut [u8]) {
    let n = row.len();
    let p = row.as_mut_ptr();
    let mut i = 0;
    while i + 4 * LANES <= n {
        // SAFETY: i + 64 ≤ n, the length of `row`.
        unsafe {
            let a = vld1q_u8(p.add(i));
            let b = vld1q_u8(p.add(i + LANES));
            let c = vld1q_u8(p.add(i + 2 * LANES));
            let d = vld1q_u8(p.add(i + 3 * LANES));
            vst1q_u8(p.add(i), vmvnq_u8(a));
            vst1q_u8(p.add(i + LANES), vmvnq_u8(b));
            vst1q_u8(p.add(i + 2 * LANES), vmvnq_u8(c));
            vst1q_u8(p.add(i + 3 * LANES), vmvnq_u8(d));
        }
        i += 4 * LANES;
    }
    while i + LANES <= n {
        // SAFETY: i + 16 ≤ n.
        unsafe { vst1q_u8(p.add(i), vmvnq_u8(vld1q_u8(p.add(i)))) };
        i += LANES;
    }
    for v in &mut row[i..] {
        *v = invert(*v);
    }
}

/// `dst[i] = invert(src[i])`: [`invert_row`] fused with a copy, 64 bytes per step (four registers), then 16, then a
/// scalar tail. Panics if the lengths differ.
#[inline]
pub(crate) fn invert_copy_row(dst: &mut [u8], src: &[u8]) {
    assert!(dst.len() == src.len(), "dst and src lengths differ");
    let n = dst.len();
    let (d, s) = (dst.as_mut_ptr(), src.as_ptr());
    let mut i = 0;
    while i + 4 * LANES <= n {
        // SAFETY: i + 64 ≤ n, the length of both slices.
        unsafe {
            let a = vld1q_u8(s.add(i));
            let b = vld1q_u8(s.add(i + LANES));
            let c = vld1q_u8(s.add(i + 2 * LANES));
            let e = vld1q_u8(s.add(i + 3 * LANES));
            vst1q_u8(d.add(i), vmvnq_u8(a));
            vst1q_u8(d.add(i + LANES), vmvnq_u8(b));
            vst1q_u8(d.add(i + 2 * LANES), vmvnq_u8(c));
            vst1q_u8(d.add(i + 3 * LANES), vmvnq_u8(e));
        }
        i += 4 * LANES;
    }
    while i + LANES <= n {
        // SAFETY: i + 16 ≤ n.
        unsafe { vst1q_u8(d.add(i), vmvnq_u8(vld1q_u8(s.add(i)))) };
        i += LANES;
    }
    for (d, &s) in dst[i..].iter_mut().zip(&src[i..]) {
        *d = invert(s);
    }
}

/// [`invert_row`] that also returns the run of 0s of the inverted row around column `x` (the bytes that were 255) as
/// `(a, b)` with `a ≤ x < b`, or None when `row[x]` was not 255. Found during the pass: each 64-byte step also tests
/// whether all its bytes were 255, the stretch of such steps around `x` is taken and its ends are extended into the
/// neighbouring bytes, read back from the inverted row ([`leading_run`], [`trailing_run`]); when `x`'s step is not all
/// 255 (or `x` lies in the tail after the last whole step) the run is read back around `x`. Panics if `x` is outside
/// the row.
#[inline]
pub(crate) fn invert_row_zero_run(row: &mut [u8], x: usize) -> Option<(usize, usize)> {
    let n = row.len();
    assert!(x < n, "column outside the row");
    let p = row.as_mut_ptr();
    let mut i = 0;
    // The current stretch of all-255 steps starts at `from`; `found` is the stretch holding `x` once it has ended.
    let mut from = 0;
    let mut found = None;
    let mut tracking = true;
    while i + 4 * LANES <= n {
        // SAFETY: i + 64 ≤ n, the length of `row`.
        let all_255 = unsafe {
            let a = vld1q_u8(p.add(i));
            let b = vld1q_u8(p.add(i + LANES));
            let c = vld1q_u8(p.add(i + 2 * LANES));
            let d = vld1q_u8(p.add(i + 3 * LANES));
            vst1q_u8(p.add(i), vmvnq_u8(a));
            vst1q_u8(p.add(i + LANES), vmvnq_u8(b));
            vst1q_u8(p.add(i + 2 * LANES), vmvnq_u8(c));
            vst1q_u8(p.add(i + 3 * LANES), vmvnq_u8(d));
            tracking && vminvq_u8(vandq_u8(vandq_u8(a, b), vandq_u8(c, d))) == 255
        };
        if tracking && !all_255 {
            if from <= x && x < i {
                found = Some((from, i));
                tracking = false;
            } else {
                from = i + 4 * LANES;
                tracking = from <= x;
            }
        }
        i += 4 * LANES;
    }
    invert_row(&mut row[i..]);
    let (from, to) = match found {
        Some(stretch) => stretch,
        None if tracking && from <= x && x < i => (from, i),
        None => {
            if row[x] != 0 {
                return None;
            }
            return Some((x - trailing_run(&row[..x], 0), x + 1 + leading_run(&row[x + 1..], 0)));
        }
    };
    Some((from - trailing_run(&row[..from], 0), to + leading_run(&row[to..], 0)))
}

/// The bytes of a register that differ from `splat`: 4 bits per byte (byte j in bits 4j..4j + 4), set where they
/// differ.
#[inline(always)]
fn differ_mask(x: uint8x16_t, splat: uint8x16_t) -> u64 {
    // SAFETY: plain register operations.
    unsafe {
        vget_lane_u64::<0>(vreinterpret_u64_u8(vshrn_n_u16::<4>(vreinterpretq_u16_u8(vmvnq_u8(vceqq_u8(x, splat))))))
    }
}

/// The number of leading bytes of `row` equal to `v`: 64 bytes per step (four registers, `v` XORed out and ORed
/// together, then a horizontal max) while all match, then 16 at a time, the first other byte located in its register
/// ([`differ_mask`]); the tail is the row's last 16 bytes, overlapping the bytes already matched (bytewise only in a
/// row shorter than 16).
#[inline]
pub(crate) fn leading_run(row: &[u8], v: u8) -> usize {
    let n = row.len();
    let p = row.as_ptr();
    let mut i = 0;
    // SAFETY: plain register operations.
    let splat = unsafe { vdupq_n_u8(v) };
    while i + 4 * LANES <= n {
        // SAFETY: i + 64 ≤ n, the length of `row`.
        let any = unsafe {
            let a = veorq_u8(vld1q_u8(p.add(i)), splat);
            let b = veorq_u8(vld1q_u8(p.add(i + LANES)), splat);
            let c = veorq_u8(vld1q_u8(p.add(i + 2 * LANES)), splat);
            let d = veorq_u8(vld1q_u8(p.add(i + 3 * LANES)), splat);
            vmaxvq_u8(vorrq_u8(vorrq_u8(a, b), vorrq_u8(c, d)))
        };
        if any != 0 {
            break;
        }
        i += 4 * LANES;
    }
    while i + LANES <= n {
        // SAFETY: i + 16 ≤ n.
        let differ = differ_mask(unsafe { vld1q_u8(p.add(i)) }, splat);
        if differ != 0 {
            return i + (differ.trailing_zeros() / 4) as usize;
        }
        i += LANES;
    }
    if i < n && n >= LANES {
        // n − 16 < i: the bytes of the last register before i are known to match.
        let base = n - LANES;
        // SAFETY: base + 16 = n.
        let differ = differ_mask(unsafe { vld1q_u8(p.add(base)) }, splat) & (u64::MAX << (4 * (i - base)));
        return if differ != 0 { base + (differ.trailing_zeros() / 4) as usize } else { n };
    }
    while i < n && row[i] == v {
        i += 1;
    }
    i
}

/// The number of trailing bytes of `row` equal to `v`: [`leading_run`] from the end (the head is the row's first 16
/// bytes).
#[inline]
pub(crate) fn trailing_run(row: &[u8], v: u8) -> usize {
    let n = row.len();
    let p = row.as_ptr();
    let mut i = n;
    // SAFETY: plain register operations.
    let splat = unsafe { vdupq_n_u8(v) };
    while i >= 4 * LANES {
        // SAFETY: i − 64 ≥ 0 and i ≤ n.
        let any = unsafe {
            let a = veorq_u8(vld1q_u8(p.add(i - 4 * LANES)), splat);
            let b = veorq_u8(vld1q_u8(p.add(i - 3 * LANES)), splat);
            let c = veorq_u8(vld1q_u8(p.add(i - 2 * LANES)), splat);
            let d = veorq_u8(vld1q_u8(p.add(i - LANES)), splat);
            vmaxvq_u8(vorrq_u8(vorrq_u8(a, b), vorrq_u8(c, d)))
        };
        if any != 0 {
            break;
        }
        i -= 4 * LANES;
    }
    while i >= LANES {
        // SAFETY: i − 16 ≥ 0 and i ≤ n.
        let differ = differ_mask(unsafe { vld1q_u8(p.add(i - LANES)) }, splat);
        if differ != 0 {
            // The last other byte is byte 15 − leading_zeros / 4 of the register.
            return n - (i - (differ.leading_zeros() / 4) as usize);
        }
        i -= LANES;
    }
    if i > 0 && n >= LANES {
        // i < 16: the bytes of the first register from i on are known to match.
        // SAFETY: 16 ≤ n.
        let differ = differ_mask(unsafe { vld1q_u8(p) }, splat) & (u64::MAX >> (4 * (LANES - i)));
        return if differ != 0 { n - (LANES - (differ.leading_zeros() / 4) as usize) } else { n };
    }
    while i > 0 && row[i - 1] == v {
        i -= 1;
    }
    n - i
}

#[cfg(test)]
#[path = "a8_tests.rs"]
mod tests;
