// Copyright 2026, Serhii Yaremych (desugar-64) and the Obvid project contributors
// SPDX-License-Identifier: Apache-2.0

//! Obvid: the native half of a drop-in `Modifier.dropShadow` whose pixels are identical to
//! Compose's on Android 16–17's Skia (`BlurMaskFilter(radius, NORMAL)` on an A8 mask, then Skia's A8 mask blit), on
//! every Android version: on older ones it keeps that output rather than the device Skia's (the masks themselves are
//! still rasterized by the device's `Canvas`).
//!
//! - [`skia`]: the scalar port of Skia's mask blur, the definition of "correct" for everything else.
//! - `neon`: reusable NEON building blocks (transpose, the three-box recurrence, skip flags, A8 stores, the σ < 2
//!   direct blur).
//! - [`blur`]: the exact blur in NEON, with reused scratch: the whole mask into a caller buffer, or only a
//!   window of it straight into a destination (plain or srcover stores).
//! - [`shadow`]: the bitmap pipeline (blur the window of a mask that the shadow bitmap keeps straight into it), and
//!   `shadow::nine`, Skia's nine-patch of a blurred round rect (Android 16–17's geometry); `innerShadow` uses both
//!   with the shape moved by its offset, then inverts the bitmap (`255 − v`).
//! - `jni`: the exports; the shipping ones always, the test surface with Cargo feature `testing`.
//!
//! - `math`: the libm functions the code uses that `core` lacks.
//! - `rt`: what `std` would otherwise provide: the panic handler and the global allocator over libc `malloc`.
//!
//! The NEON code (and so everything above `skia`) is 64-bit Arm only; host tests run natively on Apple Silicon.
//!
//! The library is `no_std` (`core` + `alloc`): `std`'s panic machinery would pull in its backtrace symbolizer, whose
//! output Android discards. Host tests (`cfg(test)`) build with `std`.

#![cfg_attr(not(test), no_std)]

extern crate alloc;

#[cfg(target_arch = "aarch64")]
pub mod blur;
#[cfg(target_arch = "aarch64")]
mod jni;
mod math;
#[cfg(target_arch = "aarch64")]
mod neon;
mod rt;
#[cfg(target_arch = "aarch64")]
pub mod shadow;
pub mod skia;
#[cfg(test)]
mod test_support;
