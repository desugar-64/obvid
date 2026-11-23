// Copyright 2026, Serhii Yaremych (desugar-64) and the Obvid project contributors
// SPDX-License-Identifier: Apache-2.0

//! Host tests for `neon::transpose` (declared in transpose.rs as `mod tests`).

use super::*;

#[test]
fn transpose16_is_a_transpose() {
    let mut bytes = [[0u8; 16]; 16];
    for (i, row) in bytes.iter_mut().enumerate() {
        for (j, b) in row.iter_mut().enumerate() {
            *b = (i * 16 + j) as u8;
        }
    }
    let mut r: [uint8x16_t; 16] = core::array::from_fn(|i| unsafe { vld1q_u8(bytes[i].as_ptr()) });
    transpose16(&mut r);
    for (i, v) in r.iter().enumerate() {
        let mut out = [0u8; 16];
        unsafe { vst1q_u8(out.as_mut_ptr(), *v) };
        for (j, &b) in out.iter().enumerate() {
            assert_eq!(b, bytes[j][i], "out[{i}][{j}]");
        }
    }
}
