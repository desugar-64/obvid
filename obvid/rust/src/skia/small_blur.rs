// Copyright 2017 Google LLC
// Copyright 2026, Serhii Yaremych (desugar-64) and the Obvid project contributors (Rust port)
// SPDX-License-Identifier: BSD-3-Clause
// See LICENSES/Skia-BSD-3-Clause.txt

//! σ < 2: `SkGaussFilter`'s discrete Gaussian weights (SkGaussFilter.cpp) and `small_blur`, the direct blur in 8.8
//! fixed point (SkMaskBlurFilter.cpp
//! [lines 320-965](https://github.com/google/skia/blob/81ee7327e81c23b8bcf815ea6c353457cf706716/src/core/SkMaskBlurFilter.cpp#L320-L965)).

use alloc::vec;
use alloc::vec::Vec;

use super::BlurredMask;
use crate::math;

// ---------------------------------------------------------------------------------------------------------------
// SkGaussFilter (σ < 2): discrete Gaussian weights
// ---------------------------------------------------------------------------------------------------------------

/// `SkGaussFilter::kGaussArrayMax` (SkGaussFilter.h).
pub const GAUSS_ARRAY_MAX: usize = 6;

/// `kGoodEnough`
/// ([SkGaussFilter.cpp line 18](https://github.com/google/skia/blob/81ee7327e81c23b8bcf815ea6c353457cf706716/src/core/SkGaussFilter.cpp#L18)):
/// stop growing the filter at the first weight ≤ 1 %.
const GOOD_ENOUGH: f64 = 1.0 / 100.0;

/// `SkGaussFilter`: the one-sided weights `basis[0..n]` of a symmetric kernel of radius `n - 1`
/// (basis[0] is the centre). Lindeberg's discrete Gaussian `I_n(σ²) / e^{σ²}`, normalised to sum to 1.
///
/// Calls libm's `exp` ([`crate::math::exp`]): on the device bionic's, the function Skia calls. libm `exp` is not
/// guaranteed correctly rounded, so the host's could in principle differ from bionic's in the last ulp; it only
/// matters if that flips `round(w · 65536)`, which needs the product to sit within ~1e-11 of a half. Checked on the
/// device by `DropShadowExactnessTest`.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct GaussFilter {
    pub basis: [f64; GAUSS_ARRAY_MAX],
    pub n: usize,
}

impl GaussFilter {
    /// `SkGaussFilter::SkGaussFilter(double sigma)`, 0 ≤ σ < 2
    /// ([SkGaussFilter.cpp lines 109-113](https://github.com/google/skia/blob/81ee7327e81c23b8bcf815ea6c353457cf706716/src/core/SkGaussFilter.cpp#L109-L113)).
    pub fn new(sigma: f64) -> GaussFilter {
        assert!((0.0..2.0).contains(&sigma), "SkGaussFilter needs 0 <= sigma < 2");
        let mut basis = [0.0f64; GAUSS_ARRAY_MAX];
        let n = calculate_bessel_factors(sigma, &mut basis);
        GaussFilter { basis, n }
    }

    /// `radius() = fN - 1`.
    pub fn radius(&self) -> usize {
        self.n - 1
    }

    /// The `fN` weights, centre first (`begin()..end()`).
    pub fn weights(&self) -> &[f64] {
        &self.basis[..self.n]
    }
}

/// `normalize`
/// ([SkGaussFilter.cpp lines 22-45](https://github.com/google/skia/blob/81ee7327e81c23b8bcf815ea6c353457cf706716/src/core/SkGaussFilter.cpp#L22-L45)).
/// Sums from the smallest weight to the largest, divides, then
/// puts the remaining slop into `gauss[0]` so that `gauss[0] + 2·Σ gauss[1..]` is 1.
fn normalize(gauss: &mut [f64]) {
    assert!(!gauss.is_empty());
    let mut sum = 0.0;
    for i in (1..gauss.len()).rev() {
        sum += 2.0 * gauss[i];
    }
    sum += gauss[0];

    for g in gauss.iter_mut() {
        *g /= sum;
    }

    sum = 0.0;
    for i in (1..gauss.len()).rev() {
        sum += 2.0 * gauss[i];
    }
    gauss[0] = 1.0 - sum;
}

/// `calculate_bessel_factors`
/// ([SkGaussFilter.cpp lines 47-107](https://github.com/google/skia/blob/81ee7327e81c23b8bcf815ea6c353457cf706716/src/core/SkGaussFilter.cpp#L47-L107)).
/// Returns n, the number of weights. Note that, as in Skia, the
/// Bessel functions are evaluated at `var` = σ² (not σ), and the series stop at a term ≤ 1e-6.
fn calculate_bessel_factors(sigma: f64, gauss: &mut [f64; GAUSS_ARRAY_MAX]) -> usize {
    let var = sigma * sigma;

    // Lines 55-67: besselI_0 by its power series.
    let bessel_i0 = |t: f64| -> f64 {
        let t_squared_over_4 = t * t / 4.0;
        let mut sum = 1.0;
        let mut factor = 1.0;
        let mut k: i32 = 1;
        while factor > 1.0 / 1000000.0 {
            factor *= t_squared_over_4 / (k * k) as f64;
            sum += factor;
            k += 1;
        }
        sum
    };
    // Lines 69-80: besselI_1.
    let bessel_i1 = |t: f64| -> f64 {
        let t_squared_over_4 = t * t / 4.0;
        let mut sum = t / 2.0;
        let mut factor = sum;
        let mut k: i32 = 1;
        while factor > 1.0 / 1000000.0 {
            factor *= t_squared_over_4 / (k * (k + 1)) as f64;
            sum += factor;
            k += 1;
        }
        sum
    };

    // Lines 85-89.
    let d = math::exp(var);
    let mut b = [0.0f64; GAUSS_ARRAY_MAX];
    b[0] = bessel_i0(var);
    b[1] = bessel_i1(var);
    gauss[0] = b[0] / d;
    gauss[1] = b[1] / d;

    // Lines 95-102: `b[n+1] = -(2*n/var) * b[n] + b[n-1];` (2*n is an int, converted to double).
    // For σ < 2 the loop stops by n = 5 (gauss[5] ≤ 1 %); an index past the array would panic here.
    let mut n: usize = 1;
    while gauss[n] > GOOD_ENOUGH {
        b[n + 1] = -((2 * n as i32) as f64 / var) * b[n] + b[n - 1];
        gauss[n + 1] = b[n + 1] / d;
        n += 1;
    }

    // Line 104: normalize(gauss.first(n)).
    normalize(&mut gauss[..n]);
    n
}

/// The 0.16 fixed-point weights `small_blur` builds from an `SkGaussFilter` (`prepareGauss`,
/// [SkMaskBlurFilter.cpp lines 897-902](https://github.com/google/skia/blob/81ee7327e81c23b8bcf815ea6c353457cf706716/src/core/SkMaskBlurFilter.cpp#L897-L902)):
/// `factors[i] = static_cast<uint16_t>(round(d * (1 << 16)))`. Entries past `radius` stay unused (Skia leaves
/// them uninitialised; here they are 0). For σ ≥ 1/3 every weight is < 1, so no value reaches 65536.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct SmallBlurFactors {
    pub factors: [u16; GAUSS_ARRAY_MAX],
    pub radius: usize,
}

impl SmallBlurFactors {
    pub fn new(sigma: f64) -> SmallBlurFactors {
        let filter = GaussFilter::new(sigma);
        let mut factors = [0u16; GAUSS_ARRAY_MAX];
        for (i, &d) in filter.weights().iter().enumerate() {
            factors[i] = math::round(d * (1 << 16) as f64) as u16;
        }
        let radius = filter.radius();
        assert!((1..=4).contains(&radius), "small_blur handles radius 1..=4");
        SmallBlurFactors { factors, radius }
    }

    /// Weight of the tap `k` positions from the start of the kernel (k = 0..=2·radius): `G[|k - radius|]`.
    fn tap(&self, k: usize) -> u16 {
        self.factors[k.abs_diff(self.radius)]
    }
}

// ---------------------------------------------------------------------------------------------------------------
// small_blur (σ < 2): direct Gaussian in 8.8 fixed point
// ---------------------------------------------------------------------------------------------------------------

/// `kHalf = 0x80u`
/// ([line 347](https://github.com/google/skia/blob/81ee7327e81c23b8bcf815ea6c353457cf706716/src/core/SkMaskBlurFilter.cpp#L347)):
/// one half in 8.8, added once to every output for round-half-up.
const SMALL_HALF: u16 = 0x80;

/// `mulhi(s, g)` on `skvx::Vec<8, uint16_t>`: the high 16 bits of the 32-bit product. The source is a byte
/// in 8.8 (`a << 8`, `load`,
/// [line 330](https://github.com/google/skia/blob/81ee7327e81c23b8bcf815ea6c353457cf706716/src/core/SkMaskBlurFilter.cpp#L330)),
/// the weight is 0.16, the result is 8.8 truncated.
fn mulhi(a: u16, b: u16) -> u16 {
    ((a as u32 * b as u32) >> 16) as u16
}

/// Converts an 8.8 accumulator back to a byte (`store`,
/// [line 334](https://github.com/google/skia/blob/81ee7327e81c23b8bcf815ea6c353457cf706716/src/core/SkMaskBlurFilter.cpp#L334):
/// `cast<uint8_t>(v >> 8)`).
fn to_byte(v: u16) -> u8 {
    (v >> 8) as u8
}

/// One output of either direction of `small_blur`: `to_byte(kHalf + Σ_k mulhi(src[j - k] << 8, G[|k - r|]))`
/// over k = 0..=2r, with source positions outside [0, len) contributing nothing, all in wrapping `u16`.
///
/// Skia gets the same sum in a different order: `blur_x_radius_N`
/// ([lines 428-557](https://github.com/google/skia/blob/81ee7327e81c23b8bcf815ea6c353457cf706716/src/core/SkMaskBlurFilter.cpp#L428-L557))
/// scatters each source vector into the two accumulators d0/d8 at shifts 0..2r, and `blur_y_radius_N`
/// ([lines 701-773](https://github.com/google/skia/blob/81ee7327e81c23b8bcf815ea6c353457cf706716/src/core/SkMaskBlurFilter.cpp#L701-L773))
/// keeps a delay line d01, d12, ... whose last element starts at `v + kHalf`. Each output receives exactly one
/// kHalf and one truncated product per tap; `u16` addition is associative mod 2¹⁶, so the order does not
/// change the result.
fn small_blur_output(factors: &SmallBlurFactors, line: impl Fn(usize) -> Option<u8>, j: usize) -> u8 {
    let r = factors.radius;
    let mut acc = SMALL_HALF;
    for k in 0..=2 * r {
        if let Some(s) = j.checked_sub(k).and_then(&line) {
            acc = acc.wrapping_add(mulhi((s as u16) << 8, factors.tap(k)));
        }
    }
    to_byte(acc)
}

/// The vertical pass of `small_blur` (`direct_blur_y` → `blur_y_rect` → `blur_column`,
/// [lines 778-882](https://github.com/google/skia/blob/81ee7327e81c23b8bcf815ea6c353457cf706716/src/core/SkMaskBlurFilter.cpp#L778-L882)):
/// every source column x goes into column x of `dst` (the caller passes the destination from column `radius_x` on),
/// rows 0..height+2·radius_y, `dst_stride` bytes apart. `blur_column` writes `srcH` rows in its loop and the last
/// 2·radius rows by flushing the delay line
/// ([lines 804-815](https://github.com/google/skia/blob/81ee7327e81c23b8bcf815ea6c353457cf706716/src/core/SkMaskBlurFilter.cpp#L804-L815)).
/// Writes only columns 0..width of `dst`.
pub fn small_blur_y(
    src: &[u8],
    width: usize,
    height: usize,
    stride: usize,
    factors_y: &SmallBlurFactors,
    dst: &mut [u8],
    dst_stride: usize,
) {
    let dst_h = height + 2 * factors_y.radius;
    for x in 0..width {
        let column = |y: usize| if y < height { Some(src[y * stride + x]) } else { None };
        for j in 0..dst_h {
            dst[j * dst_stride + x] = small_blur_output(factors_y, column, j);
        }
    }
}

/// The horizontal pass of `small_blur` (`direct_blur_x` → `blur_x_rect` → `blur_row`,
/// [lines 562-649](https://github.com/google/skia/blob/81ee7327e81c23b8bcf815ea6c353457cf706716/src/core/SkMaskBlurFilter.cpp#L562-L649)),
/// in place on `dst_h` rows of `width + 2·radius_x` bytes, `dst_stride` apart: in every row, the `width` bytes at
/// `radius_x..radius_x+width` are the input and all the row's bytes are the output. Skia reads each group of 8 before
/// it overwrites bytes behind it (the write position trails the read position by `radius_x` ≥ 1), so reading the row
/// first (into `row_in`, grown to `width`, never shrunk) gives the same result.
pub fn small_blur_x(
    dst: &mut [u8],
    dst_stride: usize,
    dst_h: usize,
    width: usize,
    factors_x: &SmallBlurFactors,
    row_in: &mut Vec<u8>,
) {
    let radius_x = factors_x.radius;
    let dst_w = width + 2 * radius_x;
    if row_in.len() < width {
        row_in.resize(width, 0);
    }
    let row_in = &mut row_in[..width];
    for y in 0..dst_h {
        let row = &mut dst[y * dst_stride..y * dst_stride + dst_w];
        row_in.copy_from_slice(&row[radius_x..radius_x + width]);
        let line = |x: usize| row_in.get(x).copied();
        for (j, out) in row.iter_mut().enumerate() {
            *out = small_blur_output(factors_x, line, j);
        }
    }
}

/// `small_blur`
/// ([SkMaskBlurFilter.cpp lines 884-965](https://github.com/google/skia/blob/81ee7327e81c23b8bcf815ea6c353457cf706716/src/core/SkMaskBlurFilter.cpp#L884-L965))
/// into `dst`: `height + 2·radius_y` rows of `width + 2·radius_x` bytes, `dst_stride`
/// apart (bytes between rows are left as they were). Blurs vertically into the destination, then horizontally in
/// place; border = the filter radius per axis. `row` is the horizontal pass's row buffer: grown, never shrunk, so a
/// reused one makes this allocation-free. An empty source gives zeros.
#[allow(clippy::too_many_arguments)]
pub fn small_blur_into(
    src: &[u8],
    width: usize,
    height: usize,
    stride: usize,
    factors_x: &SmallBlurFactors,
    factors_y: &SmallBlurFactors,
    dst: &mut [u8],
    dst_stride: usize,
    row: &mut Vec<u8>,
) {
    let dst_w = width + 2 * factors_x.radius;
    let dst_h = height + 2 * factors_y.radius;
    if width == 0 || height == 0 {
        for y in 0..dst_h {
            dst[y * dst_stride..y * dst_stride + dst_w].fill(0);
        }
        return;
    }
    // Skia's destination is uninitialised malloc; columns outside radius_x..radius_x+width are never read before
    // the horizontal pass writes them.
    small_blur_y(src, width, height, stride, factors_y, &mut dst[factors_x.radius..], dst_stride);
    small_blur_x(dst, dst_stride, dst_h, width, factors_x, row);
}

/// [`small_blur_into`] into a fresh, tightly packed [`BlurredMask`].
pub fn small_blur(
    src: &[u8],
    width: usize,
    height: usize,
    stride: usize,
    factors_x: &SmallBlurFactors,
    factors_y: &SmallBlurFactors,
) -> BlurredMask {
    let border_x = factors_x.radius;
    let border_y = factors_y.radius;
    let dst_w = width + 2 * border_x;
    let dst_h = height + 2 * border_y;
    let mut pixels = vec![0u8; dst_w * dst_h];
    small_blur_into(src, width, height, stride, factors_x, factors_y, &mut pixels, dst_w, &mut Vec::new());
    BlurredMask { pixels, width: dst_w, height: dst_h, border_x, border_y }
}

#[cfg(test)]
#[path = "small_blur_tests.rs"]
mod tests;
