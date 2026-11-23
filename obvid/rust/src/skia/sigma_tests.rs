// Copyright 2026, Serhii Yaremych (desugar-64) and the Obvid project contributors
// SPDX-License-Identifier: Apache-2.0

//! Host tests for `skia::sigma` (declared in sigma.rs as `mod tests`).

use super::*;
use crate::skia::PlanGauss;

#[test]
fn sigma_from_radius_matches_android_expression() {
    assert_eq!(sigma_from_radius(0.0), 0.0);
    assert_eq!(sigma_from_radius(-3.0), 0.0);
    // float multiply then float add (no FMA): check against the same in f64 rounded twice.
    for r in [0.25f32, 1.0, 2.625, 10.5, 24.0 * 2.625, 63.0, 168.0, 221.0, 300.0] {
        let expected = ((0.57735f32 as f64 * r as f64) as f32 as f64 + 0.5) as f32;
        assert_eq!(sigma_from_radius(r), expected, "r={r}");
    }
    assert_eq!(sigma_from_radius(1.0), 1.07735f32);
}

/// mapRadius is the identity for a translate-only CTM: every float sigma from 0.5 to 256.
#[test]
fn map_radius_is_identity_for_translate() {
    let mut bits = 0.5f32.to_bits();
    let end = 256.0f32.to_bits();
    while bits <= end {
        let s = f32::from_bits(bits);
        assert_eq!(map_radius_translate_only(s), s, "sigma {s}");
        bits += 1;
    }
}

#[test]
fn device_sigma_clamps_at_128() {
    assert_eq!(device_sigma(127.5), 127.5);
    assert_eq!(device_sigma(128.0), 128.0);
    assert_eq!(device_sigma(200.0), 128.0);
    // Radius at which the clamp starts: 0.57735·r + 0.5 > 128 ⇔ r > ~220.84 px.
    assert!(mask_blur_sigma_from_radius(220.0) < 128.0);
    assert_eq!(mask_blur_sigma_from_radius(221.0), 128.0);
    assert_eq!(PlanGauss::new(128.0).window, 241);
    assert_eq!(pin_sigma(f64::NAN), 0.0);
    assert_eq!(pin_sigma(500.0), 135.0);
    assert!(!has_no_blur(0.5));
    assert!(has_no_blur(0.3));
}
