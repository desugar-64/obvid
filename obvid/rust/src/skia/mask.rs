// Copyright 2006 The Android Open Source Project
// Copyright 2007 The Android Open Source Project
// Copyright 2017 Google LLC
// Copyright 2026, Serhii Yaremych (desugar-64) and the Obvid project contributors (Rust port)
// SPDX-License-Identifier: BSD-3-Clause
// See LICENSES/Skia-BSD-3-Clause.txt

//! The blurred mask and the entry point: `SkBlurMask::BoxBlur` + `SkMaskBlurFilter::blur` for an A8 source.

use alloc::vec::Vec;

use super::{PlanGauss, SMALL_BLUR_SIGMA_LIMIT, SmallBlurFactors, has_no_blur, pin_sigma, plan_gauss_blur, small_blur};

/// The blurred mask, laid out as Skia's `SkMaskBuilder::PrepareDestination` lays it out (SkMask.cpp):
/// `width = src_width + 2·border_x`, `height = src_height + 2·border_y`, row bytes = `width`, and the source
/// pixel (x, y) sits at (x + border_x, y + border_y). Skia offsets the bounds by (-border_x, -border_y) and
/// returns the border as the margin.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct BlurredMask {
    pub pixels: Vec<u8>,
    pub width: usize,
    pub height: usize,
    pub border_x: usize,
    pub border_y: usize,
}

/// `SkBlurMask::BoxBlur` + `SkMaskBlurFilter::blur` for an A8 source, kNormal style, σW = σH = `sigma`.
///
/// `src` holds `height` rows of `width` bytes, `stride` bytes apart. `sigma` is the `double` the filter is
/// built with (use [`mask_blur_sigma_from_radius`] for a `BlurMaskFilter` radius); it is pinned to [0, 135]
/// as Skia does.
///
/// - No blur (σ < 1/3): Skia's `BoxBlur` returns false and the unblurred mask is drawn; this returns a copy
///   of the source with a zero border.
/// - σ < 2: `small_blur`; border = the `SkGaussFilter` radius (1..=4).
/// - otherwise: `PlanGauss`; border = [`PlanGauss::border`].
///
/// An empty source (width or height 0) returns a zero-filled mask of the grown size. Skia never blurs one
/// (the mask is not created for empty bounds); its scan would write past the end of `tmp` there.
pub fn blur_a8(src: &[u8], width: usize, height: usize, stride: usize, sigma: f64) -> BlurredMask {
    assert!(stride >= width, "stride smaller than width");
    if height > 0 {
        assert!(src.len() >= (height - 1) * stride + width, "src too short");
    }
    let sigma = pin_sigma(sigma);

    if has_no_blur(sigma) {
        let mut pixels = Vec::with_capacity(width * height);
        for y in 0..height {
            pixels.extend_from_slice(&src[y * stride..y * stride + width]);
        }
        return BlurredMask { pixels, width, height, border_x: 0, border_y: 0 };
    }

    if sigma < SMALL_BLUR_SIGMA_LIMIT {
        let factors = SmallBlurFactors::new(sigma);
        small_blur(src, width, height, stride, &factors, &factors)
    } else {
        let plan = PlanGauss::new(sigma);
        plan_gauss_blur(src, width, height, stride, &plan, &plan)
    }
}

#[cfg(test)]
#[path = "mask_tests.rs"]
mod tests;
