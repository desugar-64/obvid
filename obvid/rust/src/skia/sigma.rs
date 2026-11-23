// Copyright 2006 The Android Open Source Project
// Copyright 2017 Google LLC
// Copyright 2026, Serhii Yaremych (desugar-64) and the Obvid project contributors (Rust port)
// SPDX-License-Identifier: BSD-3-Clause
// See LICENSES/Skia-BSD-3-Clause.txt

//! Android's `BlurMaskFilter` radius → Skia sigma → device sigma, and `SkMaskBlurFilter`'s sigma pin and the
//! limits that choose its path (no blur, `small_blur`, `PlanGauss`).

use crate::math;

// ---------------------------------------------------------------------------------------------------------------
// Android: BlurMaskFilter radius → Skia sigma → device sigma
// ---------------------------------------------------------------------------------------------------------------

/// `kBLUR_SIGMA_SCALE`
/// ([MaskFilter.cpp line 14](https://android.googlesource.com/platform/frameworks/base/+/94b4c163b7dfe5ce3607f7bb8456f9573f7de57d/libs/hwui/jni/MaskFilter.cpp#14)
/// in android17;
/// [SkBlurMask.cpp line 35](https://github.com/google/skia/blob/81ee7327e81c23b8bcf815ea6c353457cf706716/src/core/SkBlurMask.cpp#L35)
/// in Skia, which android14's MaskFilter.cpp calls instead). 1/√3 rounded to five digits, as an `f32`.
pub const BLUR_SIGMA_SCALE: f32 = 0.57735;

/// Android's radius → sigma conversion, `BlurMaskFilter.nativeConstructor` →
/// `SkMaskFilterGlue::createBlur` → `convertRadiusToSigma`
/// ([MaskFilter.cpp lines 16-18](https://android.googlesource.com/platform/frameworks/base/+/94b4c163b7dfe5ce3607f7bb8456f9573f7de57d/libs/hwui/jni/MaskFilter.cpp#16),
/// android17): `return radius > 0 ? kBLUR_SIGMA_SCALE * radius + 0.5f : 0.0f;` all in `float`, no FMA.
/// Android 14 calls `SkBlurMask::ConvertRadiusToSigma`, which is the same expression.
pub fn sigma_from_radius(radius_px: f32) -> f32 {
    if radius_px > 0.0 { BLUR_SIGMA_SCALE * radius_px + 0.5 } else { 0.0 }
}

/// `SkMatrix::mapRadius` (SkMatrix.cpp) for a CTM that is identity plus a translate, which is what Compose's
/// shadow canvas has: the vectors (σ, 0) and (0, σ) are not changed by a translate, their lengths are
/// `sqrt(σ·σ + 0·0)` (`SkPoint::Length`, float), and the result is the geometric mean `sqrt(d0 · d1)`.
/// For IEEE binary floats `sqrt(x·x) == |x|` (no overflow or underflow here), so this returns `sigma`
/// unchanged; a test checks that for every float in the range in use.
pub fn map_radius_translate_only(sigma: f32) -> f32 {
    let d0 = math::sqrtf(sigma * sigma + 0.0 * 0.0);
    let d1 = math::sqrtf(0.0 * 0.0 + sigma * sigma);
    math::sqrtf(d0 * d1)
}

/// `kMaxBlurDeviceSigma`
/// ([SkBlurMaskFilterImpl.cpp line 51](https://github.com/google/skia/blob/81ee7327e81c23b8bcf815ea6c353457cf706716/src/core/SkBlurMaskFilterImpl.cpp#L51);
/// `kMAX_BLUR_SIGMA` in android14's SkBlurMF.cpp,
/// `kMaxBlurSigma` in android17): the device sigma is clamped to 128 before the blur.
pub const MAX_BLUR_DEVICE_SIGMA: f32 = 128.0;

/// `SkBlurMaskFilterImpl::computeXformedDeviceSigma`
/// ([SkBlurMaskFilterImpl.cpp lines 113-118](https://github.com/google/skia/blob/81ee7327e81c23b8bcf815ea6c353457cf706716/src/core/SkBlurMaskFilterImpl.cpp#L113-L118))
/// with a translate-only CTM:
/// `std::min(ctm.mapRadius(fSigma), kMaxBlurDeviceSigma)`. `std::min(a, b)` is `(b < a) ? b : a`.
pub fn device_sigma(sigma: f32) -> f32 {
    let xformed = map_radius_translate_only(sigma);
    if MAX_BLUR_DEVICE_SIGMA < xformed { MAX_BLUR_DEVICE_SIGMA } else { xformed }
}

/// The whole chain from `BlurMaskFilter(radius_px)` to the `double` sigma that `SkMaskBlurFilter` receives
/// (`SkBlurMask::BoxBlur` passes the `float` sigma to the `double` constructor; the conversion is exact).
pub fn mask_blur_sigma_from_radius(radius_px: f32) -> f64 {
    device_sigma(sigma_from_radius(radius_px)) as f64
}

// ---------------------------------------------------------------------------------------------------------------
// SkMaskBlurFilter: sigma pin, no-blur test, path choice
// ---------------------------------------------------------------------------------------------------------------

/// `SkMaskBlurFilter::SkMaskBlurFilter` pins each sigma to [0, 135]
/// ([SkMaskBlurFilter.cpp lines 258-260](https://github.com/google/skia/blob/81ee7327e81c23b8bcf815ea6c353457cf706716/src/core/SkMaskBlurFilter.cpp#L258-L260)).
/// After the 128 clamp above this pin never changes anything; it is kept for literalness.
pub const MASK_BLUR_MAX_SIGMA: f64 = 135.0;

/// `SkTPin(sigma, 0.0, 135.0)` = `std::max(0.0, std::min(sigma, 135.0))`, with the C++ comparison semantics
/// (a NaN sigma becomes 0).
pub fn pin_sigma(sigma: f64) -> f64 {
    let lo_min = if MASK_BLUR_MAX_SIGMA < sigma { MASK_BLUR_MAX_SIGMA } else { sigma }; // std::min(sigma, 135)
    if 0.0 < lo_min { lo_min } else { 0.0 } // std::max(0, x) = (0 < x) ? x : 0
}

/// `kNoWindowSigma`
/// ([SkMaskBlurFilter.cpp lines 270-276](https://github.com/google/skia/blob/81ee7327e81c23b8bcf815ea6c353457cf706716/src/core/SkMaskBlurFilter.cpp#L270-L276)):
/// 1/3 unless `SK_USE_LARGER_NO_BLUR_THRESHOLD` is defined (it is not in
/// AOSP's Android.bp or SkUserConfigManual.h for android14 or android17).
pub const NO_BLUR_SIGMA: f64 = 1.0 / 3.0;

/// `SkMaskBlurFilter::hasNoBlur`
/// ([line 277](https://github.com/google/skia/blob/81ee7327e81c23b8bcf815ea6c353457cf706716/src/core/SkMaskBlurFilter.cpp#L277)):
/// `fSigmaW < kNoWindowSigma && fSigmaH <= kNoWindowSigma`.
/// Here σW = σH = σ, and `σ < 1/3` implies `σ <= 1/3`, so the test is `σ < kNoWindowSigma` alone.
/// Android 14 and 15 have `(3 * fSigmaW <= 1) && (3 * fSigmaH <= 1)` instead; the two differ only at
/// σ ≈ 1/3, far below the σ ≥ 0.5 that any radius > 0 gives. When it is true, `SkBlurMask::BoxBlur` returns
/// false for kNormal and the caller draws the unblurred mask.
pub fn has_no_blur(sigma: f64) -> bool {
    sigma < NO_BLUR_SIGMA
}

/// `SkMaskBlurFilter::blur`
/// ([line 971](https://github.com/google/skia/blob/81ee7327e81c23b8bcf815ea6c353457cf706716/src/core/SkMaskBlurFilter.cpp#L971)):
/// `if (fSigmaW < 2.0 && fSigmaH < 2.0) return small_blur(...)`.
pub const SMALL_BLUR_SIGMA_LIMIT: f64 = 2.0;

#[cfg(test)]
#[path = "sigma_tests.rs"]
mod tests;
