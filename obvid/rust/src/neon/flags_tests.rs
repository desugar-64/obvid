// Copyright 2026, Serhii Yaremych (desugar-64) and the Obvid project contributors
// SPDX-License-Identifier: Apache-2.0

//! Host tests for `neon::flags` (declared in flags.rs as `mod tests`). `find_skips` is covered through the blur's
//! skip tests (run lengths at every boundary, against the scalar port).

use super::*;
use crate::test_support::Rng;

/// [`equal_to_previous`]: byte i = 0xFF iff vector i equals vector i − 1 (vector −1 = `prev`) in every lane, on
/// random vectors with random repeats and single-lane, single-bit differences.
#[test]
fn equal_to_previous_flags() {
    let mut rng = Rng(0x2_4_3);
    for _ in 0..20000 {
        let mut bytes = [[0u8; 16]; 17]; // [0] = prev
        for i in 0..17 {
            bytes[i] = if i > 0 && rng.below(2) == 0 {
                let mut b = bytes[i - 1];
                if rng.below(3) == 0 {
                    b[rng.below(16) as usize] ^= 1 << rng.below(8);
                }
                b
            } else if rng.below(4) == 0 {
                [[0u8; 16], [255u8; 16]][rng.below(2) as usize]
            } else {
                core::array::from_fn(|_| rng.next() as u8)
            };
        }
        let v: [uint8x16_t; 16] = core::array::from_fn(|i| unsafe { vld1q_u8(bytes[i + 1].as_ptr()) });
        let mut got = [0u8; 16];
        unsafe { vst1q_u8(got.as_mut_ptr(), equal_to_previous(&v, vld1q_u8(bytes[0].as_ptr()))) };
        for i in 0..16 {
            assert_eq!(got[i], if bytes[i + 1] == bytes[i] { 0xFF } else { 0 }, "{i}");
        }
    }
}
