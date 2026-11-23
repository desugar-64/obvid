// Copyright 2026, Serhii Yaremych (desugar-64) and the Obvid project contributors
// SPDX-License-Identifier: Apache-2.0

//! Reusable NEON building blocks (aarch64), 16 bytes per register: the 16 × 16 transpose, the three-box recurrence,
//! the interior-skip flags, the A8 stores into a bitmap row (source-over, inversion) and the σ < 2 direct blur.
//! [`crate::blur`] builds the exact blur and its window stores from them.

pub(crate) mod a8;
pub(crate) mod box3;
pub(crate) mod flags;
pub(crate) mod small;
pub(crate) mod transpose;

/// Lines per register (one byte per lane).
pub(crate) const LANES: usize = 16;
