// Copyright 2026, Serhii Yaremych (desugar-64) and the Obvid project contributors
// SPDX-License-Identifier: Apache-2.0

//! Interior-skip detection (`crate::blur`'s module docs, "Interior skip"): one flag byte per step, "this vector equals
//! the one before it in all 16 lanes", and the list of skips a scan takes from its flags.

use alloc::vec::Vec;
use core::arch::aarch64::*;

use super::LANES;

/// Padding after each flag array: the run search reads 32 bytes at a time.
pub(crate) const FLAG_PAD: usize = 32;

/// One skipped stretch of a scan: outputs `from..to` are the input vector at step `run` (zero when `run` is
/// [`ZERO_RUN`], a run of zeros from the line start), and the recurrence restarts at `to` from the steady state.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) struct Skip {
    pub from: usize,
    pub to: usize,
    pub run: usize,
}

/// [`Skip::run`] of a run of zeros reaching back past the line start.
pub(crate) const ZERO_RUN: usize = usize::MAX;

/// The skips of one scan of `count` steps from its flags (0xFF where the input vector equals the one before it,
/// the one before step 0 being zero; readable up to `count + FLAG_PAD`), appended to `skips` in order. `support` is
/// Skia's sliding window L (an output's support), `state_len` R = L + 2 (the run after which the state is the steady
/// state), `min_skip` the shortest skip taken.
///
/// A maximal stretch [a, b) of set flags means the input is constant over [a − 1, b) (over (−∞, b) of zeros when
/// a = 0). Outputs from a − 1 + L − 1 (from 0) to b see only the run; the state after step b − 1 is the steady
/// state if b − (a − 1) ≥ R (always for the zero run), and is not needed if b = count.
///
/// # Safety
/// `flags[..count + FLAG_PAD]` is readable.
#[inline(never)]
pub(crate) unsafe fn find_skips(
    flags: *const u8,
    count: usize,
    support: usize,
    state_len: usize,
    min_skip: usize,
    skips: &mut Vec<Skip>,
) {
    let mut from = 0;
    while from < count {
        // SAFETY: from < count.
        let a = unsafe { find_flag(flags, from, count, true) };
        if a == count {
            break;
        }
        let b = unsafe { find_flag(flags, a + 1, count, false) };
        from = b + 1; // flags[b] is clear (or b = count)
        let (start, run) = if a == 0 { (0, ZERO_RUN) } else { (a - 1 + support - 1, a - 1) };
        let state_ok = a == 0 || b - (a - 1) >= state_len;
        if start < b && b - start >= min_skip && (b == count || state_ok) {
            skips.push(Skip { from: start, to: b, run });
        }
    }
}

/// First index i in [from, to) with `flags[i] != 0` equal to `set`, or `to`. Reads 32 bytes at a time (flags are
/// 0x00 / 0xFF and readable up to `to + FLAG_PAD`).
///
/// # Safety
/// `flags[from..to + FLAG_PAD]` is readable.
#[inline(always)]
pub(crate) unsafe fn find_flag(flags: *const u8, from: usize, to: usize, set: bool) -> usize {
    let flip = if set { 0 } else { u64::MAX };
    let word = |i: usize| unsafe { (flags.add(i) as *const u64).read_unaligned() ^ flip };
    let mut i = from;
    while i < to {
        // SAFETY: i < to, so i + 32 ≤ to + FLAG_PAD.
        let w = [word(i), word(i + 8), word(i + 16), word(i + 24)];
        if w[0] | w[1] | w[2] | w[3] != 0 {
            for (j, &w) in w.iter().enumerate() {
                if w != 0 {
                    return (i + 8 * j + (u64::from_le(w).trailing_zeros() / 8) as usize).min(to);
                }
            }
        }
        i += 32;
    }
    to
}

/// Interior-skip detection for 16 consecutive vectors `v[0..16]` of a line, `prev` the one before `v[0]`: byte i is
/// 0xFF iff `v[i]` equals the vector before it in all 16 lanes. XOR with the neighbour, then a `umaxp` tree (8 + 4
/// + 2 + 1) reduces each XOR to one byte in order.
#[inline(always)]
pub(crate) fn equal_to_previous(v: &[uint8x16_t; LANES], prev: uint8x16_t) -> uint8x16_t {
    // SAFETY: plain register operations.
    unsafe {
        let x: [uint8x16_t; LANES] = core::array::from_fn(|i| veorq_u8(v[i], if i == 0 { prev } else { v[i - 1] }));
        let a: [uint8x16_t; 8] = core::array::from_fn(|k| vpmaxq_u8(x[2 * k], x[2 * k + 1]));
        let b: [uint8x16_t; 4] = core::array::from_fn(|k| vpmaxq_u8(a[2 * k], a[2 * k + 1]));
        let c: [uint8x16_t; 2] = core::array::from_fn(|k| vpmaxq_u8(b[2 * k], b[2 * k + 1]));
        vceqzq_u8(vpmaxq_u8(c[0], c[1]))
    }
}

#[cfg(test)]
#[path = "flags_tests.rs"]
mod tests;
