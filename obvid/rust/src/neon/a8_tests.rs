// Copyright 2026, Serhii Yaremych (desugar-64) and the Obvid project contributors
// SPDX-License-Identifier: Apache-2.0

//! Host tests for `neon::a8` (declared in a8.rs as `mod tests`). The reference is Skia's formula written out.

use super::*;
use crate::test_support::{Rng, reference_src_over};

/// Every (d, m): the scalar [`src_over`], and the row blitter [`src_over_row`] on a row of 261 bytes (16 NEON chunks
/// and a scalar tail of 5), equal Skia's formula.
#[test]
fn src_over_matches_skia_formula_and_blitter() {
    for d in 0..=255u32 {
        let mask: Vec<u8> = (0..261u32).map(|i| (i % 256) as u8).collect();
        let mut row = vec![d as u8; mask.len()];
        src_over_row(&mut row, &mask);
        for (i, &m) in mask.iter().enumerate() {
            let want = reference_src_over(d, m as u32);
            assert_eq!(src_over(d as u8, m) as u32, want, "d {d} m {m}");
            assert_eq!(row[i] as u32, want, "row blitter d {d} m {m} at {i}");
        }
    }
}

/// [`src_over16`] is identical to [`src_over`] for all 256 × 256 inputs, in every lane.
#[test]
fn src_over16_matches_scalar_for_every_input() {
    for d in 0..=255u8 {
        for m0 in (0..=255u8).step_by(16) {
            let ds: [u8; 16] = core::array::from_fn(|l| d.wrapping_add(l as u8 * 17)); // other d values in other lanes
            let ms: [u8; 16] = core::array::from_fn(|l| m0 + l as u8);
            let mut got = [0u8; 16];
            // SAFETY: 16-byte loads and store on 16-byte arrays.
            unsafe { vst1q_u8(got.as_mut_ptr(), src_over16(vld1q_u8(ds.as_ptr()), vld1q_u8(ms.as_ptr()))) };
            for l in 0..16 {
                assert_eq!(got[l], src_over(ds[l], ms[l]), "d {} m {} lane {l}", ds[l], ms[l]);
            }
        }
    }
}

/// [`src_over_row`] on rows of every length 0..=70 and random content against the scalar model.
#[test]
fn rows_of_every_length_match_scalar() {
    let mut rng = Rng(0xA8A8_0001);
    for n in 0..=70usize {
        let mask: Vec<u8> = (0..n).map(|_| rng.next() as u8).collect();
        let base: Vec<u8> = (0..n + 9).map(|_| rng.next() as u8).collect();
        let mut row = base[..n].to_vec();
        src_over_row(&mut row, &mask);
        let want: Vec<u8> = base[..n].iter().zip(&mask).map(|(&d, &m)| src_over(d, m)).collect();
        assert_eq!(row, want, "src_over n {n}");
    }
}

#[test]
fn replace_is_src_over_onto_zero() {
    // Replace copies the window: valid only because srcover of m onto the zeroed bitmap is m for every byte.
    for m in 0..=255u8 {
        assert_eq!(src_over(0, m), m, "m {m}");
    }
}

/// [`invert`] is `255 − v` and an involution, for every byte.
#[test]
fn invert_is_255_minus_v_for_every_value() {
    for v in 0..=255u8 {
        assert_eq!(invert(v) as u32, 255 - v as u32, "v {v}");
        assert_eq!(invert(invert(v)), v, "v {v}");
    }
}

/// [`invert_row`] on rows of every length 0..=200 (64-byte steps, 16-byte steps and every tail length), every
/// alignment of the row start, and random content, against the scalar [`invert`]; the bytes around the row stay.
#[test]
fn invert_row_matches_scalar_for_every_length_and_offset() {
    let mut rng = Rng(0xA8A8_0002);
    for n in 0..=200usize {
        for start in 0..LANES {
            let base: Vec<u8> = (0..start + n + 7).map(|_| rng.next() as u8).collect();
            let mut buf = base.clone();
            invert_row(&mut buf[start..start + n]);
            let mut want = base.clone();
            for v in &mut want[start..start + n] {
                *v = invert(*v);
            }
            assert_eq!(buf, want, "n {n} start {start}");
        }
    }
    // Every value in every lane position of a 64-byte step and the tail.
    let mut all: Vec<u8> = (0..=255u8).chain(0..=255u8).chain(0..=37u8).collect();
    invert_row(&mut all);
    let want: Vec<u8> = (0..=255u8).chain(0..=255u8).chain(0..=37u8).map(invert).collect();
    assert_eq!(all, want);
}

/// [`invert_copy_row`] on rows of every length 0..=100 (64-byte steps, 16-byte steps and every tail length), every
/// alignment of the source and destination starts, and random content, against the scalar [`invert`]; the source is
/// unchanged and the bytes around the destination row stay.
#[test]
fn invert_copy_row_matches_scalar_for_every_length_and_offset() {
    let mut rng = Rng(0xA8A8_0003);
    for n in 0..=100usize {
        for (src_start, dst_start) in [(0, 0), (1, 0), (0, 1), (3, 7), (15, 2), (9, 15), (16, 16), (5, 5)] {
            let src: Vec<u8> = (0..src_start + n + 5).map(|_| rng.next() as u8).collect();
            let base: Vec<u8> = (0..dst_start + n + 7).map(|_| rng.next() as u8).collect();
            let mut buf = base.clone();
            let before = src.clone();
            invert_copy_row(&mut buf[dst_start..dst_start + n], &src[src_start..src_start + n]);
            let mut want = base.clone();
            for (d, &s) in want[dst_start..dst_start + n].iter_mut().zip(&src[src_start..src_start + n]) {
                *d = invert(s);
            }
            assert_eq!(buf, want, "n {n} src {src_start} dst {dst_start}");
            assert_eq!(src, before);
        }
    }
}

/// [`leading_run`] and [`trailing_run`] count the bytes equal to `v` at either end of a row, against a plain count,
/// for every length up to 200, a first other byte at every position (and none), and the values 0, 255 and one between.
#[test]
fn runs_match_scalar_for_every_length_and_break() {
    let mut rng = Rng(0x4E0_0001);
    for v in [0u8, 255, 77] {
        for n in 0..200usize {
            for other in (0..=n).chain([n + 1]) {
                let mut row = vec![v; n];
                if other < n {
                    row[other] = v ^ (1 + rng.below(255) as u8);
                }
                // Stray other bytes past the first one must not matter.
                for _ in 0..2 {
                    if other + 1 < n {
                        let at = other + 1 + rng.below((n - other - 1) as u32) as usize;
                        row[at] = rng.next() as u8;
                    }
                }
                let lead = row.iter().take_while(|&&b| b == v).count();
                let trail = row.iter().rev().take_while(|&&b| b == v).count();
                assert_eq!(leading_run(&row, v), lead, "v {v} n {n} other {other}");
                assert_eq!(trailing_run(&row, v), trail, "v {v} n {n} other {other}");
            }
        }
    }
}

/// [`invert_row_zero_run`] inverts as [`invert_row`] does and returns the run of 0s around its column as a plain count
/// does, for lengths around the 64-byte step, 255-runs of every extent around columns at every position (step
/// boundaries included), stray non-255 bytes, and a column on a non-255 byte.
#[test]
fn invert_row_zero_run_matches_scalar() {
    let mut rng = Rng(0x4E0_0002);
    for n in (1..300usize).step_by(7).chain([63, 64, 65, 127, 128, 129, 192, 256, 257]) {
        for _ in 0..60 {
            let mut row: Vec<u8> = (0..n).map(|_| if rng.below(3) == 0 { 255 } else { rng.next() as u8 }).collect();
            let x = rng.below(n as u32) as usize;
            let (a, b) = (x - rng.below(x as u32 + 1) as usize, x + 1 + rng.below((n - x) as u32) as usize);
            row[a..b].fill(255);
            if rng.below(5) == 0 {
                row[x] = rng.below(255) as u8;
            }
            let mut want = row.clone();
            invert_row(&mut want);
            let run = (want[x] == 0).then(|| {
                let l = want[..x].iter().rev().take_while(|&&v| v == 0).count();
                let r = want[x..].iter().take_while(|&&v| v == 0).count();
                (x - l, x + r)
            });
            assert_eq!(invert_row_zero_run(&mut row, x), run, "n {n} x {x} run {a}..{b}");
            assert_eq!(row, want, "n {n} x {x}");
        }
    }
}
