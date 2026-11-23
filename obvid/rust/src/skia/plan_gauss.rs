// Copyright 2017 Google LLC
// Copyright 2026, Serhii Yaremych (desugar-64) and the Obvid project contributors (Rust port)
// SPDX-License-Identifier: BSD-3-Clause
// See LICENSES/Skia-BSD-3-Clause.txt

//! `PlanGauss` (σ ≥ 2): three box filters as chained running sums (SkMaskBlurFilter.cpp
//! [lines 27-240](https://github.com/google/skia/blob/81ee7327e81c23b8bcf815ea6c353457cf706716/src/core/SkMaskBlurFilter.cpp#L27-L240) and
//! [978-1067](https://github.com/google/skia/blob/81ee7327e81c23b8bcf815ea6c353457cf706716/src/core/SkMaskBlurFilter.cpp#L978-L1067)).

use alloc::vec;
use alloc::vec::Vec;
use core::f64::consts::PI;

use super::BlurredMask;
use crate::math;

/// `PlanGauss`
/// ([SkMaskBlurFilter.cpp lines 27-240](https://github.com/google/skia/blob/81ee7327e81c23b8bcf815ea6c353457cf706716/src/core/SkMaskBlurFilter.cpp#L27-L240)):
/// the box sizes, border and fixed-point weight of the three-box approximation of a Gaussian, following the SVG/CSS
/// feGaussianBlur spec.
///
/// The three boxes have widths `pass0_size + 1`, `pass1_size + 1`, `pass2_size + 1`, that is d, d, d for an
/// odd window d and d, d, d + 1 for an even one. Their convolution is a symmetric kernel of odd length
/// `2·border + 1` whose weights add up to `divisor`; each output byte is
/// `(weight · Σ kernel·src + 2³¹) >> 32`.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct PlanGauss {
    /// The box width d: `max(1, floor(σ·3·√(2π)/4 + 0.5))`
    /// ([lines 30-31](https://github.com/google/skia/blob/81ee7327e81c23b8bcf815ea6c353457cf706716/src/core/SkMaskBlurFilter.cpp#L30-L31)).
    pub window: usize,
    /// `fPass0Size = window - 1`
    /// ([line 33](https://github.com/google/skia/blob/81ee7327e81c23b8bcf815ea6c353457cf706716/src/core/SkMaskBlurFilter.cpp#L33)):
    /// ring-buffer length of the first running sum.
    pub pass0_size: usize,
    /// `fPass1Size = window - 1`
    /// ([line 34](https://github.com/google/skia/blob/81ee7327e81c23b8bcf815ea6c353457cf706716/src/core/SkMaskBlurFilter.cpp#L34)).
    pub pass1_size: usize,
    /// `fPass2Size = window odd ? window - 1 : window`
    /// ([line 35](https://github.com/google/skia/blob/81ee7327e81c23b8bcf815ea6c353457cf706716/src/core/SkMaskBlurFilter.cpp#L35)).
    pub pass2_size: usize,
    /// `fBorder = window odd ? 3·((window - 1)/2) : 3·(window/2) - 1`
    /// ([line 79](https://github.com/google/skia/blob/81ee7327e81c23b8bcf815ea6c353457cf706716/src/core/SkMaskBlurFilter.cpp#L79)).
    pub border: usize,
    /// `fSlidingWindow = 2·fBorder + 1`
    /// ([line 80](https://github.com/google/skia/blob/81ee7327e81c23b8bcf815ea6c353457cf706716/src/core/SkMaskBlurFilter.cpp#L80)):
    /// the length of the combined kernel.
    pub sliding_window: usize,
    /// `window odd ? window³ : window³ + window²`
    /// ([lines 84-86](https://github.com/google/skia/blob/81ee7327e81c23b8bcf815ea6c353457cf706716/src/core/SkMaskBlurFilter.cpp#L84-L86)):
    /// the sum of the combined kernel.
    pub divisor: u64,
    /// `fWeight = round(1.0 / divisor · 2³²)`
    /// ([line 88](https://github.com/google/skia/blob/81ee7327e81c23b8bcf815ea6c353457cf706716/src/core/SkMaskBlurFilter.cpp#L88)),
    /// computed in `double`.
    pub weight: u64,
}

impl PlanGauss {
    /// `PlanGauss::PlanGauss(double sigma)`. The window expression is evaluated left to right in `double`
    /// exactly as written: `sigma * 3 * sqrt(2 * SK_DoublePI) / 4 + 0.5` (`SK_DoublePI` is π as a double;
    /// android14 spells it `kPi`, same value). `sqrt` is correctly rounded on both sides.
    pub fn new(sigma: f64) -> PlanGauss {
        let possible_window = math::floor(sigma * 3.0 * math::sqrt(2.0 * PI) / 4.0 + 0.5) as i32; // static_cast<int>
        PlanGauss::from_window(possible_window.max(1) as usize)
    }

    /// The plan for a given box width (the second half of the constructor,
    /// [lines 33-88](https://github.com/google/skia/blob/81ee7327e81c23b8bcf815ea6c353457cf706716/src/core/SkMaskBlurFilter.cpp#L33-L88)).
    /// Skia only builds windows from 4 (σ = 2) to 254 (σ = 135); 241 is the largest after the 128 clamp.
    pub fn from_window(window: usize) -> PlanGauss {
        assert!((1..=255).contains(&window), "window out of range (sums must fit in u32)");
        let odd = window & 1 == 1;
        let pass0_size = window - 1;
        let pass1_size = window - 1;
        let pass2_size = if odd { window - 1 } else { window };
        let border = if odd { 3 * ((window - 1) / 2) } else { 3 * (window / 2) - 1 };
        let sliding_window = 2 * border + 1;

        // `auto window2 = window * window; auto window3 = window2 * window;` in int (fits: 255³ < 2³¹).
        let window2 = (window * window) as u64;
        let window3 = window2 * window as u64;
        let divisor = if odd { window3 } else { window3 + window2 };

        // `static_cast<uint64_t>(round(1.0 / divisor * (1ull << 32)))`: 1/divisor in double, times 2³²
        // (exact, a power of two), rounded half away from zero (C `round` = Rust `f64::round`).
        let weight = math::round(1.0 / divisor as f64 * (1u64 << 32) as f64) as u64;

        PlanGauss { window, pass0_size, pass1_size, pass2_size, border, sliding_window, divisor, weight }
    }

    /// `bufferSize()`
    /// ([line 91](https://github.com/google/skia/blob/81ee7327e81c23b8bcf815ea6c353457cf706716/src/core/SkMaskBlurFilter.cpp#L91)):
    /// one `u32` per ring-buffer slot of the three running sums.
    pub fn buffer_size(&self) -> usize {
        self.pass0_size + self.pass1_size + self.pass2_size
    }

    /// `Scan::finalScale`
    /// ([lines 205-207](https://github.com/google/skia/blob/81ee7327e81c23b8bcf815ea6c353457cf706716/src/core/SkMaskBlurFilter.cpp#L205-L207)):
    /// `SkTo<uint8_t>((fWeight * sum + kHalf) >> 32)`, kHalf = 2³¹.
    /// `SkTo` only asserts in debug builds; the release cast truncates (`as u8`). For sums of a kernel over
    /// bytes the value is always ≤ 255 (see the flat-interior test).
    pub fn final_scale(&self, sum: u32) -> u8 {
        ((self.weight * sum as u64 + (1u64 << 31)) >> 32) as u8
    }

    /// `makeBlurScan(width, ...)`
    /// ([line 225](https://github.com/google/skia/blob/81ee7327e81c23b8bcf815ea6c353457cf706716/src/core/SkMaskBlurFilter.cpp#L225)):
    /// `noChangeCount = fSlidingWindow > width ? fSlidingWindow - width : 0`.
    /// After the source runs out, this many more outputs are produced by the forward scan with zero input.
    pub fn no_change_count(&self, src_len: usize) -> usize {
        self.sliding_window.saturating_sub(src_len)
    }

    /// Output length of one scan line: `src_len + 2·border` (`PrepareDestination`).
    pub fn output_len(&self, src_len: usize) -> usize {
        src_len + 2 * self.border
    }
}

/// One of the three ring buffers of a `Scan` (`fBuffer0..fBuffer0End` etc.,
/// [lines 219-224](https://github.com/google/skia/blob/81ee7327e81c23b8bcf815ea6c353457cf706716/src/core/SkMaskBlurFilter.cpp#L219-L224)),
/// as a region of the shared `u32` buffer plus a cursor. The three regions are adjacent: [0, p0), [p0, p0+p1), [p0+p1,
/// total).
struct Ring {
    start: usize,
    end: usize,
    cursor: usize,
}

impl Ring {
    /// The three statements Skia repeats per sum, e.g.
    /// [lines 135-137](https://github.com/google/skia/blob/81ee7327e81c23b8bcf815ea6c353457cf706716/src/core/SkMaskBlurFilter.cpp#L135-L137):
    /// `sum2 -= *buffer2Cursor; *buffer2Cursor = sum1; buffer2Cursor = (buffer2Cursor + 1) < fBuffer2End ?
    /// buffer2Cursor + 1 : fBuffer2;` Returns the value that leaves the window (the one subtracted).
    ///
    /// A zero-length region (window 1) only occurs in tests: Skia's σ ≥ 2 gives window ≥ 4, and there its
    /// code would alias the next region. Here it means a box of width 1: the value leaves immediately.
    fn exchange(&mut self, buffer: &mut [u32], value: u32) -> u32 {
        if self.start == self.end {
            return value;
        }
        let leaving = buffer[self.cursor];
        buffer[self.cursor] = value;
        self.cursor = if self.cursor + 1 < self.end { self.cursor + 1 } else { self.start };
        leaving
    }
}

/// `PlanGauss::Scan::blur`
/// ([lines 114-200](https://github.com/google/skia/blob/81ee7327e81c23b8bcf815ea6c353457cf706716/src/core/SkMaskBlurFilter.cpp#L114-L200)):
/// blurs one line of `src` into `dst_count` bytes written at `dst[dst_start + i·dst_stride]`, i = 0..dst_count
/// (`dst_count` = `src.len() + 2·border`).
///
/// Three running sums: `sum0` is a box of width p0+1 over the source, `sum1` a box of width p1+1 over
/// `sum0`, `sum2` a box of width p2+1 over `sum1`; each output is `final_scale(sum2)` taken right after the
/// leading edge enters, so output i is the full convolution of the zero-padded line at i (output 0 sees
/// only `src[0]`). Phases, as in Skia:
/// 1. forward over the source (one output per source byte);
/// 2. `noChangeCount` more forward steps with a zero leading edge (only when the line is shorter than the
///    kernel);
/// 3. reset the sums and buffers (not the cursors) and fill the rest from the right end, scanning the source
///    backwards. The kernel is symmetric, so this gives the same values a longer forward scan would.
///
/// All sums are `uint32_t` with wrapping arithmetic; the exact values fit for window ≤ 255.
pub fn plan_gauss_scan(
    plan: &PlanGauss,
    buffer: &mut [u32],
    src: &[u8],
    dst: &mut [u8],
    dst_start: usize,
    dst_stride: usize,
    dst_count: usize,
) {
    debug_assert_eq!(dst_count, plan.output_len(src.len()));
    assert!(buffer.len() >= plan.buffer_size());
    let buffer = &mut buffer[..plan.buffer_size()];
    let p0 = plan.pass0_size;
    let p1 = plan.pass1_size;
    // makeBlurScan: buffer0 = buffer, buffer1 = buffer0End, buffer2 = buffer1End.
    // https://github.com/google/skia/blob/81ee7327e81c23b8bcf815ea6c353457cf706716/src/core/SkMaskBlurFilter.cpp#L219-L224
    let mut ring0 = Ring { start: 0, end: p0, cursor: 0 };
    let mut ring1 = Ring { start: p0, end: p0 + p1, cursor: p0 };
    let mut ring2 = Ring { start: p0 + p1, end: buffer.len(), cursor: p0 + p1 };
    let no_change_count = plan.no_change_count(src.len());
    let dst_at = |i: usize| dst_start + i * dst_stride;

    // Line 120: std::memset(fBuffer0, 0x00, (fBuffer2End - fBuffer0) * sizeof(*fBuffer0));
    buffer.fill(0);
    let mut sum0: u32 = 0;
    let mut sum1: u32 = 0;
    let mut sum2: u32 = 0;

    // One step of the running sums (the loop bodies at lines 128-145, 150-167 and 181-198 are identical):
    // https://github.com/google/skia/blob/81ee7327e81c23b8bcf815ea6c353457cf706716/src/core/SkMaskBlurFilter.cpp#L128-L198
    let mut step = |leading_edge: u32, buffer: &mut [u32], sum0: &mut u32, sum1: &mut u32, sum2: &mut u32| -> u8 {
        *sum0 = sum0.wrapping_add(leading_edge);
        *sum1 = sum1.wrapping_add(*sum0);
        *sum2 = sum2.wrapping_add(*sum1);
        let out = plan.final_scale(*sum2);
        *sum2 = sum2.wrapping_sub(ring2.exchange(buffer, *sum1));
        *sum1 = sum1.wrapping_sub(ring1.exchange(buffer, *sum0));
        *sum0 = sum0.wrapping_sub(ring0.exchange(buffer, leading_edge));
        out
    };

    // Lines 126-146: consume the source generating pixels.
    let mut written = 0;
    for &s in src {
        dst[dst_at(written)] = step(s as u32, buffer, &mut sum0, &mut sum1, &mut sum2);
        written += 1;
    }

    // Lines 148-170: the leading edge is off the right side of the mask.
    for _ in 0..no_change_count {
        dst[dst_at(written)] = step(0, buffer, &mut sum0, &mut sum1, &mut sum2);
        written += 1;
    }

    // Lines 172-199: starting from the right, fill in the rest. Buffers and sums are cleared, the cursors keep
    // their positions (irrelevant: the buffers are all zero). `while (dstCursor > dst)` runs until the
    // backward cursor meets the first forward-unwritten position.
    buffer.fill(0);
    sum0 = 0;
    sum1 = 0;
    sum2 = 0;
    let mut cursor = dst_count; // dstCursor = dstEnd
    let mut src_index = src.len(); // src = srcEnd
    while cursor > written {
        cursor -= 1; // dstCursor -= dstStride
        src_index -= 1; // *(--src)
        let leading_edge = src[src_index] as u32;
        dst[dst_at(cursor)] = step(leading_edge, buffer, &mut sum0, &mut sum1, &mut sum2);
    }
}

/// One line through one plan, into a fresh `Vec` of `src.len() + 2·border` bytes (for tests and kernels).
pub fn plan_gauss_blur_line(plan: &PlanGauss, src: &[u8]) -> Vec<u8> {
    let n = plan.output_len(src.len());
    let mut dst = vec![0u8; n];
    let mut buffer = vec![0u32; plan.buffer_size()];
    if !src.is_empty() {
        plan_gauss_scan(plan, &mut buffer, src, &mut dst, 0, 1, n);
    }
    dst
}

/// The σ ≥ 2 half of `SkMaskBlurFilter::blur`
/// ([lines 978-1067](https://github.com/google/skia/blob/81ee7327e81c23b8bcf815ea6c353457cf706716/src/core/SkMaskBlurFilter.cpp#L978-L1067)),
/// A8 source.
///
/// Horizontal pass: each source row y is scanned and written transposed into column y of `tmp`
/// (`tmpW = srcH`, `tmpH = dstW`). Vertical pass: each `tmp` row (one destination column, in memory order) is
/// scanned and written transposed back into column y of the destination. Each pass rounds to bytes.
pub fn plan_gauss_blur(
    src: &[u8],
    width: usize,
    height: usize,
    stride: usize,
    plan_w: &PlanGauss,
    plan_h: &PlanGauss,
) -> BlurredMask {
    let border_x = plan_w.border;
    let border_y = plan_h.border;
    let dst_w = width + 2 * border_x;
    let dst_h = height + 2 * border_y;
    let mut pixels = vec![0u8; dst_w * dst_h];
    if width == 0 || height == 0 {
        return BlurredMask { pixels, width: dst_w, height: dst_h, border_x, border_y };
    }

    // Lines 1000-1001: one buffer, sized for the larger plan, shared by both passes.
    let mut buffer = vec![0u32; plan_w.buffer_size().max(plan_h.buffer_size())];

    // Lines 1004-1011.
    let tmp_w = height;
    let tmp_h = dst_w;
    let mut tmp = vec![0u8; tmp_w * tmp_h];

    // Lines 1014, 1025-1032: blur horizontally, and transpose. tmpStart = &tmp[y], stride tmpW.
    for y in 0..height {
        let row = &src[y * stride..y * stride + width];
        plan_gauss_scan(plan_w, &mut buffer, row, &mut tmp, y, tmp_w, tmp_h);
    }

    // Lines 1058-1065: blur vertically (tmp rows are destination columns), and transpose back.
    // dstStart = &dst->image()[y], stride dst->fRowBytes (= dstW).
    for y in 0..tmp_h {
        let line = &tmp[y * tmp_w..(y + 1) * tmp_w];
        plan_gauss_scan(plan_h, &mut buffer, line, &mut pixels, y, dst_w, dst_h);
    }

    BlurredMask { pixels, width: dst_w, height: dst_h, border_x, border_y }
}

#[cfg(test)]
#[path = "plan_gauss_tests.rs"]
mod tests;
