// Copyright 2026, Serhii Yaremych (desugar-64) and the Obvid project contributors
// SPDX-License-Identifier: Apache-2.0

//! The 16 × 16 byte transpose (rows of a block ↔ lanes of 16 registers).

use core::arch::aarch64::*;

use super::LANES;

/// In-register 16 × 16 byte transpose: afterwards byte j of `r[i]` is byte i of the old `r[j]`.
/// Four `trn` stages (bytes, halfwords, words, doublewords), each transposing 2 × 2 blocks of the previous size.
#[inline(always)]
pub(crate) fn transpose16(r: &mut [uint8x16_t; LANES]) {
    // SAFETY: plain register operations.
    unsafe {
        for i in (0..16).step_by(2) {
            let (a, b) = (r[i], r[i + 1]);
            r[i] = vtrn1q_u8(a, b);
            r[i + 1] = vtrn2q_u8(a, b);
        }
        for i in [0, 1, 4, 5, 8, 9, 12, 13] {
            let (a, b) = (vreinterpretq_u16_u8(r[i]), vreinterpretq_u16_u8(r[i + 2]));
            r[i] = vreinterpretq_u8_u16(vtrn1q_u16(a, b));
            r[i + 2] = vreinterpretq_u8_u16(vtrn2q_u16(a, b));
        }
        for i in [0, 1, 2, 3, 8, 9, 10, 11] {
            let (a, b) = (vreinterpretq_u32_u8(r[i]), vreinterpretq_u32_u8(r[i + 4]));
            r[i] = vreinterpretq_u8_u32(vtrn1q_u32(a, b));
            r[i + 4] = vreinterpretq_u8_u32(vtrn2q_u32(a, b));
        }
        for i in 0..8 {
            let (a, b) = (vreinterpretq_u64_u8(r[i]), vreinterpretq_u64_u8(r[i + 8]));
            r[i] = vreinterpretq_u8_u64(vtrn1q_u64(a, b));
            r[i + 8] = vreinterpretq_u8_u64(vtrn2q_u64(a, b));
        }
    }
}

#[cfg(test)]
#[path = "transpose_tests.rs"]
mod tests;
