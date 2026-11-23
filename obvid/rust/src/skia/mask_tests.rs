// Copyright 2026, Serhii Yaremych (desugar-64) and the Obvid project contributors
// SPDX-License-Identifier: Apache-2.0

//! Host tests for `skia::mask` (declared in mask.rs as `mod tests`): the whole blur's properties and Skia's golden
//! outputs.

use super::*;
use crate::skia::GaussFilter;
use crate::test_support::{Rng, random_mask, test_sigmas};

#[test]
fn zero_input_gives_zero() {
    for sigma in [0.5, 1.0, 1.9, 2.0, 7.0, 64.0, 128.0] {
        for &(w, h) in &[(1usize, 1usize), (5, 9), (33, 2)] {
            let src = vec![0u8; w * h];
            let out = blur_a8(&src, w, h, w, sigma);
            assert!(out.pixels.iter().all(|&p| p == 0), "sigma {sigma} {w}x{h}");
        }
    }
}

#[test]
fn output_size_and_border() {
    for sigma in [0.5f64, 0.6, 1.0, 1.5, 1.99, 2.0, 2.3, 9.0, 100.0, 128.0] {
        let (w, h) = (11usize, 6usize);
        let src = vec![255u8; w * h];
        let out = blur_a8(&src, w, h, w, sigma);
        let border = if sigma < 2.0 { GaussFilter::new(sigma).radius() } else { PlanGauss::new(sigma).border };
        assert_eq!((out.border_x, out.border_y), (border, border), "sigma {sigma}");
        assert_eq!((out.width, out.height), (w + 2 * border, h + 2 * border), "sigma {sigma}");
        assert_eq!(out.pixels.len(), out.width * out.height);
    }
    // No blur below 1/3: copy, zero border.
    let src = [1u8, 2, 3, 9, 4, 5, 6, 9];
    let out = blur_a8(&src, 3, 2, 4, 0.3);
    assert_eq!(out, BlurredMask { pixels: vec![1, 2, 3, 4, 5, 6], width: 3, height: 2, border_x: 0, border_y: 0 });
}

fn mirror_x(p: &[u8], w: usize, h: usize) -> Vec<u8> {
    (0..h).flat_map(|y| (0..w).rev().map(move |x| p[y * w + x])).collect()
}

fn mirror_y(p: &[u8], w: usize, h: usize) -> Vec<u8> {
    (0..h).rev().flat_map(|y| p[y * w..(y + 1) * w].to_vec()).collect()
}

/// Mirrored input → mirrored output, both axes, for odd and even windows and for small_blur. Holds
/// because every kernel is symmetric: three boxes convolve to a symmetric kernel even when one has an
/// even width (d, d, d+1 → odd length 3d-1), and the backward fill uses the same kernel reversed.
#[test]
fn mirror_symmetry() {
    let mut rng = Rng(0x5EED_0001);
    let mut sigmas = vec![0.5, 0.8, 1.2, 1.7, 1.99];
    sigmas.extend(test_sigmas().into_iter().filter(|&s| s <= 40.0));
    for sigma in sigmas {
        for &(w, h) in &[(1usize, 9usize), (9, 1), (7, 12), (23, 19)] {
            let src = random_mask(w, h, w, 0, &mut rng);
            let out = blur_a8(&src, w, h, w, sigma);
            let out_mx = blur_a8(&mirror_x(&src, w, h), w, h, w, sigma);
            let out_my = blur_a8(&mirror_y(&src, w, h), w, h, w, sigma);
            assert_eq!(out_mx.pixels, mirror_x(&out.pixels, out.width, out.height), "x sigma {sigma} {w}x{h}");
            assert_eq!(out_my.pixels, mirror_y(&out.pixels, out.width, out.height), "y sigma {sigma} {w}x{h}");
        }
    }
}

// ---- golden outputs of Skia's own C++ ------------------------------------------------------------------

fn from_hex(h: &str) -> Vec<u8> {
    (0..h.len()).step_by(2).map(|i| u8::from_str_radix(&h[i..i + 2], 16).unwrap()).collect()
}

/// Golden outputs of Skia's own SkMaskBlurFilter.cpp (google/skia@81ee7327e81c23b8bcf815ea6c353457cf706716, compiled
/// unchanged on the host with clang -ffp-contract=off), so that `cargo test` checks small_blur exactly without the C++
/// build.
/// (sigma, width, height, stride, border, src hex, dst hex)
const SKIA_GOLDEN: &[(f64, usize, usize, usize, usize, &str, &str)] = &[
    (
        0.5,
        7,
        8,
        9,
        1,
        "ffffffffffffffa5a5ffffffffffffffa5a5ffffffffffffffa5a5ffffffffffffffa5a5ffffffffffffffa5a5ffffffffffffffa5a5ffffffffffffffa5a5ffffffffffffffa5a5",
        "02171919191919170217cfe6e6e6e6e6cf1719e6ffffffffffe61919e6ffffffffffe61919e6ffffffffffe61919e6ffffffffffe61919e6ffffffffffe61919e6ffffffffffe61917cfe6e6e6e6e6cf17021719191919191702",
    ),
    (
        1.07735,
        8,
        8,
        8,
        3,
        "000064a9a964000000a9ffffffffa90064ffffffffffff64a9ffffffffffffa9a9ffffffffffffa964ffffffffffff6400a9ffffffffa900000064a9a9640000",
        "00000000000102020100000000000000000104080b0b0804010000000000010612232e2e231206010000000106183c678181673c180601000004123c81b7d0d0b7813c12040001082367b7e3f2f2e3b767230801020b2f81cff2fcfcf2cf812f0b02020b2f81cff2fcfcf2cf812f0b0201082367b7e3f2f2e3b7672308010004123c81b7d0d0b7813c120400000106183c678181673c180601000000010612232e2e2312060100000000000104080b0b0804010000000000000000010202010000000000",
    ),
    (
        1.99,
        9,
        7,
        10,
        4,
        "000000006c0021ff00a5ff00aaff5100ff0000a500530000ffff000000a500000b00ffffff00d4a54500ffffffffff0000a5ffffb1ffffff00ff00a5ff0000ffff98caff00a5",
        "000000000000000101020202010100000000000102030304050606060604020100000001020407090c0e1010100d0905020100010205080d12181d2122201a120a0502000103080e17202a343b3b352a1c0f07020002050b142130404f59584d3b261509030102060d1a2b3f556a7775654c311a0c04010207101f344c677f8e8a7758381e0d0401030813253d57728b98937d5d3b1f0e0501030915273f5770848f887456371d0d04010308122235485a6a716c5c462d180a030002060d1824303c464a473e2f1f100702000103070d141a202528262119100904010001020306090b0d1011100e0b07040201000000010102030304050504040201010000",
    ),
    (
        2.0,
        7,
        8,
        9,
        5,
        "5000ff7cff00a2a5a5fff15900000000a5a500007eff0010ffa5a5006500ffffff00a5a54f000000ff60ffa5a5ff9dff000004ffa5a5ff69ff00ff00e1a5a500ff0000dc00ffa5a5",
        "000000000001010102010101010000000000000101020405050505040302010100000001020406090b0d0d0c0a070503010100000103070c1116191917130e0a060301000102050b131b232828261f18100a0502010103070f1a25303739362e23180f07030101040913202e3c4649463d2f21140a040101040b162435455155534a3a2a1a0d060201050c1828394a575c5b5242301e10070201050d192a3b4c595e5d5445332011070201050d19293a495559585042312011070201050c172634404a4d4c45392b1c0f070201040a131f2a343b3d3b362d22160c05020103070e161e25292b29261f1810090401000204080d121619191917130e0a0502010001020407090b0c0d0c0b09070503010000000101020304050505050403020100000000000000010101010101010100000000",
    ),
    (
        2.5,
        3,
        5,
        4,
        6,
        "ffffffa5ffffffa5ffffffa5ffffffa5ffffffa5",
        "000000000101010101010100000000000001010203030403030201010000000102030507080908070503020100000103060a0e1112110e0a060301000102060b12191e1f1e19120b060201010308101a252c2f2c251a1008030101040b1522303a3d3a3022150b040101050d1928384448443828190d050101050e1b2b3c484c483c2b1b0e050101050d1928384448443828190d050101040b1522303a3d3a3022150b0401010308101a252c2f2c251a100803010102060b12191e1f1e19120b060201000103060a0e1112110e0a06030100000102030507080908070503020100000001010203030403030201010000000000000101010101010100000000",
    ),
];

#[test]
fn matches_skia_golden_cases() {
    for &(sigma, w, h, stride, border, src_hex, dst_hex) in SKIA_GOLDEN {
        let got = blur_a8(&from_hex(src_hex), w, h, stride, sigma);
        assert_eq!((got.border_x, got.border_y), (border, border), "sigma {sigma}");
        assert_eq!((got.width, got.height), (w + 2 * border, h + 2 * border), "sigma {sigma}");
        assert_eq!(got.pixels, from_hex(dst_hex), "sigma {sigma}");
    }
}
