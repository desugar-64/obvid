// Copyright 2026, Serhii Yaremych (desugar-64) and the Obvid project contributors
// SPDX-License-Identifier: Apache-2.0

//! Scalar port of the blur that Android's `BlurMaskFilter(radius, NORMAL)` applies to an A8 mask on the CPU.
//!
//! This is the definition of "correct" for Obvid's kernels: the NEON three-box blur and the interior skip must
//! produce exactly these bytes. It mirrors Skia line by line, quirks included, and favours clarity over speed.
//!
//! Sources: Skia main at google/skia@81ee7327e81c23b8bcf815ea6c353457cf706716 (2026-11-18), and Android 17's
//! frameworks/base for the radius. Each item of the port links the lines it follows.
//! - [`MaskFilter.cpp`][MaskFilter] (frameworks/base `libs/hwui/jni`, android17-release): radius → sigma.
//! - [`SkBlurMaskFilterImpl.cpp`][SkBlurMaskFilterImpl]: device sigma = `min(ctm.mapRadius(sigma), 128)`, then
//!   `SkBlurMask::BoxBlur`.
//! - [`SkBlurMask.cpp`][SkBlurMask] `BoxBlur`: `SkMaskBlurFilter{sigma, sigma}`, the no-blur shortcut, kNormal = no
//!   post-step.
//! - [`SkMaskBlurFilter.cpp`][SkMaskBlurFilter]: σ < 2 → `small_blur` (direct Gaussian, `SkGaussFilter` weights,
//!   8.8 × 0.16 fixed point); otherwise `PlanGauss` (three running box sums, one rounding per axis).
//! - [`SkGaussFilter.cpp`][SkGaussFilter]: the discrete Gaussian weights (Lindeberg, Bessel functions) for σ < 2.
//! - [`SkMask.cpp`][SkMask] `PrepareDestination`: destination = source grown by the border on each side.
//!
//! The arithmetic of all of the above is the same in Skia main (2026-11), AOSP android14-release
//! (`1145116447a46710a65e3a5deabb7d7c752af5ea`) and [android17-release][aosp17]
//! (`508606366e71a3daa933a4e08bc49b84e387ec18`); only the no-blur threshold changed, and it never triggers for a
//! radius > 0 (σ ≥ 0.5).
//!
//! Floating point: AOSP builds every C/C++ file with `-ffp-contract=off` (build/soong `cc/config/global.go`),
//! so `a * b + c` is two rounded operations, as in Rust (which never contracts). The only libm call whose
//! result is not correctly rounded is `exp` in `SkGaussFilter` (σ < 2 only); see `GaussFilter`.
//!
//! [MaskFilter]: https://android.googlesource.com/platform/frameworks/base/+/94b4c163b7dfe5ce3607f7bb8456f9573f7de57d/libs/hwui/jni/MaskFilter.cpp
//! [SkBlurMaskFilterImpl]: https://github.com/google/skia/blob/81ee7327e81c23b8bcf815ea6c353457cf706716/src/core/SkBlurMaskFilterImpl.cpp
//! [SkBlurMask]: https://github.com/google/skia/blob/81ee7327e81c23b8bcf815ea6c353457cf706716/src/core/SkBlurMask.cpp
//! [SkMaskBlurFilter]: https://github.com/google/skia/blob/81ee7327e81c23b8bcf815ea6c353457cf706716/src/core/SkMaskBlurFilter.cpp
//! [SkGaussFilter]: https://github.com/google/skia/blob/81ee7327e81c23b8bcf815ea6c353457cf706716/src/core/SkGaussFilter.cpp
//! [SkMask]: https://github.com/google/skia/blob/81ee7327e81c23b8bcf815ea6c353457cf706716/src/core/SkMask.cpp
//! [aosp17]: https://android.googlesource.com/platform/external/skia/+/508606366e71a3daa933a4e08bc49b84e387ec18

mod extent;
mod mask;
mod plan_gauss;
mod sigma;
mod small_blur;

pub use extent::{full_extent_margin, skia_reach_margin, step_response};
pub use mask::{BlurredMask, blur_a8};
pub use plan_gauss::{PlanGauss, plan_gauss_blur, plan_gauss_blur_line, plan_gauss_scan};
pub use sigma::*;
pub use small_blur::{
    GAUSS_ARRAY_MAX, GaussFilter, SmallBlurFactors, small_blur, small_blur_into, small_blur_x, small_blur_y,
};
