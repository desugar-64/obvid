// Copyright 2026, Serhii Yaremych (desugar-64) and the Obvid project contributors
// SPDX-License-Identifier: Apache-2.0

//! How far the blurred output of a mask can reach: the margin a bitmap needs around a shape so that nothing the blur
//! writes is cut off, for the drop shadow's full extent.
//!
//! [`full_extent_margin`] is exact for `SkMaskBlurFilter`'s arithmetic (the `skia` module, which Obvid's kernels
//! reproduce): the smallest k such that every output pixel k or more pixels beyond the mask's axis-aligned pixel
//! bounds is 0, whatever the mask holds inside those bounds. Why the 1D step response gives it:
//!
//! - Both paths are separable: `PlanGauss` (σ ≥ 2) blurs every row, rounds to bytes, then blurs every column of that
//!   and rounds again; `small_blur` (σ < 2) does the columns first, then the rows. Each pass maps a line of bytes to
//!   a line of bytes, and each output byte is a non-decreasing function of the input bytes: a sum of non-decreasing
//!   terms (integer weights times the inputs in `PlanGauss`, truncated fixed-point products in `small_blur`; neither
//!   sum wraps for the kernels Skia builds), then a rounding that is non-decreasing in the sum.
//! - The mask is 0 outside its pixel bounds `[x0, x1) × [y0, y1)`. Take a row (first pass of `PlanGauss`): it is 0
//!   right of `x1 − 1` and at most 255 everywhere. By monotonicity its output at `x1 − 1 + j` is at most that of the
//!   half-plane line (255 up to `x1 − 1`, 0 after), the 1D step response `s(j)`; the left side is the mirror image
//!   (the kernel is symmetric). So the first pass leaves 0 in every column at distance ≥ k from `[x0, x1)`, where
//!   `s(k) = 0` (`s` is non-increasing in `j`: the kernel's weights past the edge only drop out as `j` grows).
//! - The second pass then reads, in such a column, only 0s: its output there is the rounding of a zero sum, 0. In the
//!   other columns it reads bytes that are 0 outside `[y0, y1)` (rows the mask leaves empty blur to 0 in the first
//!   pass) and at most 255 inside, so the same bound gives 0 at distance ≥ k from `[y0, y1)`.
//! - For `small_blur` the same holds with the axes swapped.
//!
//! Every shape lies on one side of each of its four bounding lines, so the margin k measured from the axis-aligned
//! pixel bounds holds for any shape; a straight edge along a bounding line (a half-plane) reaches `s(k − 1)`, which is
//! not 0 when `k` is below the blur's own border, so no smaller margin holds for every shape.
//!
//! [`skia_reach_margin`] is a bound, not an exact value: the margin for a draw left to the device's Skia, whose blur
//! may be its analytic rect blur or an older box blur rather than the arithmetic above.

use alloc::vec;

use super::{PlanGauss, SMALL_BLUR_SIGMA_LIMIT, SmallBlurFactors, has_no_blur, pin_sigma};
use super::{mask_blur_sigma_from_radius, sigma_from_radius};
use super::{plan_gauss_blur_line, small_blur_x};

/// The 1D step response of the blur `BlurMaskFilter(radius_px, NORMAL)` applies, past the edge: element `j − 1` is the
/// output `j` pixels beyond the last pixel of a line of 255s (j = 1..=border; beyond the border the blur writes
/// nothing). Empty when the radius gives no blur.
pub fn step_response(radius_px: f32) -> alloc::vec::Vec<u8> {
    let sigma = pin_sigma(mask_blur_sigma_from_radius(radius_px));
    if has_no_blur(sigma) {
        return vec![];
    }
    if sigma < SMALL_BLUR_SIGMA_LIMIT {
        let factors = SmallBlurFactors::new(sigma);
        let r = factors.radius;
        // A line as long as the kernel, so the outputs past its end see 255 under every tap on the inside.
        let n = 2 * r + 1;
        let mut row = vec![0u8; n + 2 * r];
        row[r..r + n].fill(255);
        small_blur_x(&mut row, n + 2 * r, 1, n, &factors, &mut vec![]);
        row[n + r..].to_vec()
    } else {
        let plan = PlanGauss::new(sigma);
        let n = plan.sliding_window;
        let out = plan_gauss_blur_line(&plan, &vec![255u8; n]);
        out[n + plan.border..].to_vec()
    }
}

/// The full-extent margin of `BlurMaskFilter(radius_px, NORMAL)` on Obvid's pipeline (module docs): the smallest k ≥ 1
/// such that the blurred output is 0 at every pixel k or more pixels beyond the mask's pixel bounds, on any axis. 1
/// without blur (the mask itself, then a ring of 0s); at most the border + 1.
pub fn full_extent_margin(radius_px: f32) -> usize {
    let s = step_response(radius_px);
    s.iter().position(|&v| v == 0).unwrap_or(s.len()) + 1
}

/// The margin for a draw left to the device's Skia: `3·⌈σ⌉ + 1` with σ = `0.57735·radius + 0.5` (not clamped to 128),
/// and at least [`full_extent_margin`]. Skia's CPU blurs of a mask write within these reaches of its pixel bounds:
/// the analytic rect blur `pad = ceil(6σ)/2` (`SkBlurMask::BlurRect`: its bounds `round(l − pad)` … `round(r + pad)`
/// lie within `pad` of `floor(l)` and `ceil(r)`), the three-box blur its border (≤ `3·⌈σ⌉`, tested), the older box
/// blurs `3·⌈σ − 1/6⌉` or `⌈1.5σ − 0.5⌉`, the direct blur for σ < 2 at most 4 (`3·⌈σ⌉` is ≥ 3, and ≥ 6 from σ > 1).
/// The + 1 is the ring of 0s around the reach.
pub fn skia_reach_margin(radius_px: f32) -> usize {
    let sigma = sigma_from_radius(radius_px);
    let reach = 3 * crate::math::ceilf(sigma) as usize + 1;
    reach.max(full_extent_margin(radius_px))
}

#[cfg(test)]
#[path = "extent_tests.rs"]
mod tests;
