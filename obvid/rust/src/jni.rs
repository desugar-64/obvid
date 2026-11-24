// Copyright 2026, Serhii Yaremych (desugar-64) and the Obvid project contributors
// SPDX-License-Identifier: Apache-2.0

//! The JNI exports. Shipping (`builder.rs`): `ObvidNative`, the library smoke check and the shadow builder on
//! locked `Bitmap`s (`bitmap.rs`, libjnigraphics). With Cargo feature `testing` (`testing.rs`, debug and benchmark
//! builds only): `ObvidTesting`, the blur on Java arrays for the on-device exactness test.
//!
//! The crate is built with `panic = "abort"`, so a panic in an export would take the app down: every export checks
//! its handle and arguments and returns a status, 0 or an empty result instead (array exports throw a Java
//! `RuntimeException`). The raw JNI types are in `sys.rs`.

#[cfg(target_os = "android")]
mod bitmap;
mod builder;
mod sys;
#[cfg(feature = "testing")]
mod testing;

/// What both `abiVersion` exports return; bump it when the JNI surface the Kotlin side expects changes.
const ABI_VERSION: i32 = 2;

/// Status codes of the exports that lock bitmaps (`builderBlur`, `builderNine`, `builderInvert`, …; 0 = success).
#[cfg(target_os = "android")]
const BUILDER_BAD_ARGUMENT: i32 = -1;
#[cfg(target_os = "android")]
const BUILDER_LOCK_FAILED: i32 = -2;
#[cfg(target_os = "android")]
const BUILDER_NOT_A8: i32 = -3;
#[cfg(target_os = "android")]
const BUILDER_OUT_OF_BOUNDS: i32 = -4;
