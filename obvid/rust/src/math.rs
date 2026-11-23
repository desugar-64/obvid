// Copyright 2026, Serhii Yaremych (desugar-64) and the Obvid project contributors
// SPDX-License-Identifier: Apache-2.0

//! The libm functions that `core` lacks on stable, called directly (bionic's libm on Android, the system libm on the
//! host). `exp` is the very function `std`'s `f64::exp` calls. `floor`, `ceil`, `round` and `sqrt` are exact (or
//! correctly rounded) in every libm, so they give the bytes of `std`'s inline instructions (`frintm`, `frintp`,
//! `frinta`, `fsqrt`); none of them is on a hot path.

#[link(name = "m")]
unsafe extern "C" {
    /// `e^x` (not correctly rounded; the libm's own result, as Skia's `exp` gets on the same device).
    pub(crate) safe fn exp(x: f64) -> f64;
    /// Largest integer ≤ `x`.
    pub(crate) safe fn floor(x: f64) -> f64;
    /// Nearest integer, halfway cases away from zero (C `round`, Rust `f64::round`).
    pub(crate) safe fn round(x: f64) -> f64;
    /// Correctly rounded square root.
    pub(crate) safe fn sqrt(x: f64) -> f64;
    /// Correctly rounded square root, in `f32`.
    pub(crate) safe fn sqrtf(x: f32) -> f32;
    /// Largest integer ≤ `x`, in `f32`.
    pub(crate) safe fn floorf(x: f32) -> f32;
    /// Smallest integer ≥ `x`, in `f32`.
    pub(crate) safe fn ceilf(x: f32) -> f32;
}
