// Copyright 2026, Serhii Yaremych (desugar-64) and the Obvid project contributors
// SPDX-License-Identifier: Apache-2.0
// Implements the arithmetic of Skia's SkMaskBlurFilter (BSD-3-Clause), see NOTICE.

//! The exact blur: Android's `BlurMaskFilter(radius, NORMAL)` on an A8 mask, identical to the scalar port
//! [`skia::blur_a8`] for every input, with reused scratch. σ ≥ 2 (`PlanGauss`) runs the NEON three-box recurrence
//! ([`crate::neon::box3`]) in two passes with the interior skip; σ < 2 (`small_blur`) and the no-blur case run the
//! scalar port's code.
//!
//! One entry point, [`blur_a8_window_into`]: it computes and stores a window of the blurred mask (the part a shadow
//! bitmap keeps) straight into the destination, plain ([`Store::Replace`]) or as Skia's A8 srcover blit
//! ([`Store::SourceOver`]). The whole blurred mask into a caller buffer is the full window ([`Window::full`]) with
//! plain stores.
//!
//! # Layout
//! The blurred mask is `dst_w × dst_h` (the source grown by the border on each side); the window is
//! `[wx0, wx1) × [wy0, wy1)` of it, `ww × wh`.
//! - Horizontal pass, 16 source rows per group: each 16 × 16 block of the rows is transposed in registers into a
//!   history buffer (`hist`, one 16-byte vector per column: lane l = row l), preceded by 3d + 1 zero vectors so the
//!   taps never need a bounds test, and followed by zeros past the row (Skia's zero leading edge). The recurrence is
//!   causal (output n reads input columns ≤ n), so the kernel runs positions `0..wx0 + inter_w` only (`inter_w` =
//!   `ww` rounded up to 16) and only the source columns those read are transposed; outputs before `wx0` are dropped,
//!   the others go to `out` (one vector per position). Each 16 × 16 output block is transposed back and stored into
//!   the row-major intermediate plane `inter`, whose column c is blurred column `wx0 + c` (the window's columns
//!   only, so the vertical groups are 16-aligned in `inter`), 3d + 1 zero rows above the image rows. Only source
//!   rows `< wy1` are filtered: vertical output n reads intermediate rows ≤ n. Zero rows follow the image rows up
//!   to row `wy1` when the window reaches past them.
//! - Vertical pass, 16 adjacent columns per group: the rows of `inter` are already one vector per step (lane =
//!   column), so the kernel reads them in place (taps = rows d, 2d, 3d… above), runs steps `0..wy1`, drops the
//!   outputs before `wy0` and writes the window rows directly. No transposes. A last group narrower than 16
//!   overlaps the previous one (plain stores: the same bytes written twice; srcover: the overlapped lanes blend a
//!   zero mask, which leaves them as they are); a window narrower than 16 goes through `out` and stores its own
//!   bytes only. Srcover goes through `out` too (`src_over16` of each row, the bitmap read once).
//! - A row group with fewer than 16 rows repeats its last row in the unused lanes and stores only its own rows.
//!
//! # Interior skip
//! A shadow mask is mostly flat (255 inside, 0 outside, constant along straight anti-aliased edges); only the band
//! around the outline needs the recurrence. Per scan (16 lines, steps n = 0..count over the zero-padded input x):
//! - **Exact facts.** Write the three boxes as polynomials in z of widths a, b, c (d, d, d or d, d, d + 1) and
//!   R = a + b + c. `T0 = Δ/(1−z)·x`, `T1 = Δ/(1−z)²·x` and `sum2 = Δ/(1−z)³·x` are FIR filters of x of lengths
//!   R, R − 1 and R − 2 = L (Skia's sliding window), and the first two have coefficient sum 0 (a factor (1 − z^c) is
//!   left). So if, in every lane, x is constant v over x[n−R+1..=n], then after step n: T0 = 0, T1 = 0,
//!   sum2 = v·divisor, U = 2³¹ + W·divisor·v, and the output byte is v (`finalScale(v·divisor) = v` for every byte
//!   v and every window; checked exhaustively by a test). The true values always fit their lanes, so the registers
//!   hold exactly these integers: this *is* the state the recurrence reaches (a test compares the registers).
//! - **Skip.** For a run x[s..t) constant per lane (lanes may differ; x[s] is the run's vector; a run of zeros
//!   reaching the line start extends to −∞): outputs n ∈ [s + L − 1, t) are x[s] (their whole support lies in
//!   the run); they are stored without running the recurrence (with the window's store: srcover of the constant
//!   vector, nothing for a zero one), and if t − s ≥ R the state after step t − 1 is set to the steady state above
//!   and the recurrence resumes at t, reading its taps from the real history (the input is still transposed in
//!   full; only the arithmetic is skipped). If the run reaches the end of the scan no state is needed. In the
//!   horizontal pass, a 16-column output block inside a skip is written back without its transpose: row l is lane
//!   l of the run's vector.
//! - **Detection.** One flag byte per step: "x[n] equals x[n − 1] in all 16 lanes" (x[−1] = 0), 16 steps at a
//!   time by `equal_to_previous` (XOR with the neighbour, a `umaxp` tree). Horizontal pass: on the transposed
//!   input blocks. Vertical pass: in the horizontal pass, on each intermediate block after its back-transpose
//!   (lane = column, one vector per row), so the vertical pass gets its flags without reading the plane again.
//!   The flags become a list of skips (`find_skips`, 32 flags per test).
//! - **When.** [`InteriorSkip::Auto`] (the default) detects horizontally only if a run over a full row could save
//!   at least 1/8 of its outputs (`width + 1 − L ≥ dst_w / 8`), vertically only from 1/3 and only in row groups
//!   where the horizontal pass skipped ≥ 1/8 of its steps (the vertical flags cost ~4 % of a blur on Apple Silicon);
//!   small shapes with big blurs pay nothing. The thresholds look at the whole line, not the window (a shadow's
//!   window cuts only part of the border).

use alloc::vec::Vec;

use crate::neon::a8;
use crate::neon::box3::NARROW_T0_MAX_WINDOW;
use crate::neon::flags::Skip;
use crate::skia::{self, PlanGauss, SmallBlurFactors};

mod passes;

/// The kernel's buffers, owned by the caller and reused across calls; they only grow.
#[derive(Default)]
pub struct Scratch {
    /// Horizontal pass: one row group's input, transposed, one vector per column, 3d + 1 zero vectors first.
    hist: Vec<u8>,
    /// Horizontal pass output per position (one vector each); also the staging rows of the vertical pass when it
    /// does not store plainly 16 bytes at a time (srcover, a window narrower than 16).
    out: Vec<u8>,
    /// Row-major intermediate plane (horizontal result over the window's columns), zero rows above and below.
    inter: Vec<u8>,
    /// Interior skip, horizontal pass: one "same as the previous column" flag per step of the current row group.
    hflags: Vec<u8>,
    /// Interior skip, vertical pass: per column group, one "same as the previous row" flag per step.
    vflags: Vec<u8>,
    /// Interior skip: the skips of the current scan.
    skips: Vec<Skip>,
    /// σ < 2: the row buffer of `small_blur`'s horizontal pass.
    small_row: Vec<u8>,
    /// σ < 2 into a part of the mask or with srcover: the whole blurred mask, then the window is stored from it.
    small_mask: Vec<u8>,
}

impl Scratch {
    pub fn new() -> Scratch {
        Scratch::default()
    }
}

/// Whether the kernel skips the recurrence over flat stretches (module docs, "Interior skip"). The bytes are the
/// same in every mode; only the work differs.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
#[cfg_attr(not(test), allow(dead_code))]
pub(crate) enum InteriorSkip {
    /// Always run the full recurrence.
    Off,
    /// The default: detect on an axis when a full-line run could save ≥ 1/8 of its outputs; skip runs that save
    /// at least [`MIN_SKIP`] steps.
    Auto,
    /// Detect on both axes and skip every run, however short (tests: exercises every boundary).
    Always,
}

/// Shortest skip [`InteriorSkip::Auto`] takes (a restart costs about two steps).
const MIN_SKIP: usize = 8;

/// `v[..n]`, growing `v` (zero-filled) if it is shorter. Never shrinks.
pub(crate) fn grown(v: &mut Vec<u8>, n: usize) -> &mut [u8] {
    if v.len() < n {
        v.resize(n, 0);
    }
    &mut v[..n]
}

/// Size of a blurred mask: the source grown by the border on each side (Skia's `PrepareDestination`).
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct BlurGeometry {
    pub width: usize,
    pub height: usize,
    pub border_x: usize,
    pub border_y: usize,
}

/// A rectangle of a blurred mask, in its coordinates: columns `[x0, x1)`, rows `[y0, y1)` (empty if either is).
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Window {
    pub x0: usize,
    pub y0: usize,
    pub x1: usize,
    pub y1: usize,
}

impl Window {
    /// The whole `width × height` mask.
    pub fn full(width: usize, height: usize) -> Window {
        Window { x0: 0, y0: 0, x1: width, y1: height }
    }

    pub fn is_empty(&self) -> bool {
        self.x0 >= self.x1 || self.y0 >= self.y1
    }
}

/// How [`blur_a8_window_into`] stores the window's bytes `m` into the destination bytes `d`. Also the mode of
/// [`crate::shadow::ShadowBuilder::blur_into`], where `Replace` also clears the rest of the clear rect.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Store {
    /// `d = m` (the first mask of a rebuild).
    Replace,
    /// Skia's A8 srcover mask blit for black paint, `d = div255((255 − m)·d + 255·m)` ([`a8::src_over`]) (the stroke
    /// mask of path + spread).
    SourceOver,
}

/// The output geometry of the blur of a `width × height` source at `sigma` (same as the scalar port).
pub fn blur_geometry(width: usize, height: usize, sigma: f64) -> BlurGeometry {
    let sigma = skia::pin_sigma(sigma);
    let border = if skia::has_no_blur(sigma) {
        0
    } else if sigma < skia::SMALL_BLUR_SIGMA_LIMIT {
        SmallBlurFactors::new(sigma).radius
    } else {
        PlanGauss::new(sigma).border
    };
    BlurGeometry { width: width + 2 * border, height: height + 2 * border, border_x: border, border_y: border }
}

/// The `window` of [`skia::blur_a8`]'s output stored into `dst` with `store`: `dst[0]` is blurred pixel
/// (`window.x0`, `window.y0`), window rows are `dst_stride` apart, and only the window's bytes are written (bytes
/// between rows are left as they were). The bytes are those of the whole blur in the window, stored plainly or
/// srcover'd onto `dst`. σ ≥ 2 computes only what the window needs (module docs, "Layout"); σ < 2 runs the scalar
/// port's `small_blur`, straight into `dst` for the full window with plain stores, otherwise into `scratch` with the
/// window stored from it; the no-blur case stores from the source. No route allocates once `scratch` has grown to
/// the size. Panics if the window is not inside [`blur_geometry`] or `dst` is too short for it; an empty window
/// writes nothing.
#[allow(clippy::too_many_arguments)]
pub(crate) fn blur_a8_window_into(
    src: &[u8],
    width: usize,
    height: usize,
    stride: usize,
    sigma: f64,
    window: Window,
    dst: &mut [u8],
    dst_stride: usize,
    store: Store,
    scratch: &mut Scratch,
    skip: InteriorSkip,
) {
    check_source(src, width, height, stride);
    let geometry = blur_geometry(width, height, sigma);
    let Window { x0, y0, x1, y1 } = window;
    assert!(x1 <= geometry.width && y1 <= geometry.height, "window outside the blurred mask");
    if window.is_empty() {
        return;
    }
    let (ww, wh) = (x1 - x0, y1 - y0);
    assert!(dst_stride >= ww, "dst_stride smaller than the window width");
    assert!(span(wh, dst_stride, ww).is_some_and(|n| dst.len() >= n), "dst too short");
    let sigma = skia::pin_sigma(sigma);

    if skia::has_no_blur(sigma) {
        // The blurred mask is the source (border 0).
        store_rows(&src[y0 * stride + x0..], stride, ww, wh, dst, dst_stride, store);
        return;
    }

    if width == 0 || height == 0 {
        // The blurred mask is all zeros: srcover of 0 leaves `dst` as it is.
        if store == Store::Replace {
            for y in 0..wh {
                dst[y * dst_stride..y * dst_stride + ww].fill(0);
            }
        }
        return;
    }

    if sigma < skia::SMALL_BLUR_SIGMA_LIMIT {
        let factors = SmallBlurFactors::new(sigma);
        let gw = geometry.width;
        let Scratch { small_row, small_mask, .. } = scratch;
        if store == Store::Replace && window == Window::full(gw, geometry.height) {
            // `small_blur_into` writes only the rows' bytes, so the whole mask goes straight into `dst`.
            skia::small_blur_into(src, width, height, stride, &factors, &factors, dst, dst_stride, small_row);
            return;
        }
        let mask = grown(small_mask, gw * geometry.height);
        skia::small_blur_into(src, width, height, stride, &factors, &factors, mask, gw, small_row);
        store_rows(&mask[y0 * gw + x0..], gw, ww, wh, dst, dst_stride, store);
        return;
    }

    plan_gauss(src, width, height, stride, sigma, window, dst, dst_stride, store, scratch, skip);
}

/// Panics unless `src` holds `height` rows of `width` bytes `stride` apart.
fn check_source(src: &[u8], width: usize, height: usize, stride: usize) {
    assert!(stride >= width, "stride smaller than width");
    if height > 0 {
        assert!(span(height, stride, width).is_some_and(|n| src.len() >= n), "src too short");
    }
}

/// The bytes that `rows` (> 0) rows of `width` bytes, `stride` apart, span: `(rows − 1)·stride + width`, or `None`
/// if that overflows (the checks before the raw-pointer kernels must not wrap).
pub(crate) fn span(rows: usize, stride: usize, width: usize) -> Option<usize> {
    (rows - 1).checked_mul(stride)?.checked_add(width)
}

/// `height` rows of `width` bytes from `src` (`src_stride` apart) into `dst` (`dst_stride` apart) with `store`.
fn store_rows(
    src: &[u8],
    src_stride: usize,
    width: usize,
    height: usize,
    dst: &mut [u8],
    dst_stride: usize,
    store: Store,
) {
    for y in 0..height {
        let (s, d) = (&src[y * src_stride..y * src_stride + width], &mut dst[y * dst_stride..y * dst_stride + width]);
        match store {
            Store::Replace => d.copy_from_slice(s),
            Store::SourceOver => a8::src_over_row(d, s),
        }
    }
}

/// The σ ≥ 2 route of [`blur_a8_window_into`] (sizes checked by the caller; `width, height > 0`, window not empty).
#[allow(clippy::too_many_arguments)]
fn plan_gauss(
    src: &[u8],
    width: usize,
    height: usize,
    stride: usize,
    sigma: f64,
    window: Window,
    dst: &mut [u8],
    dst_stride: usize,
    store: Store,
    scratch: &mut Scratch,
    skip: InteriorSkip,
) {
    let plan = PlanGauss::new(sigma);
    let even = plan.window.is_multiple_of(2);
    let wide = plan.window > NARROW_T0_MAX_WINDOW;
    // SAFETY: sizes checked by the callers; `plan_gauss` checks the plan's own limits and the window.
    unsafe {
        match (even, wide) {
            (false, false) => passes::plan_gauss::<false, false>(
                src, width, height, stride, &plan, window, dst, dst_stride, store, scratch, skip,
            ),
            (false, true) => passes::plan_gauss::<false, true>(
                src, width, height, stride, &plan, window, dst, dst_stride, store, scratch, skip,
            ),
            (true, false) => passes::plan_gauss::<true, false>(
                src, width, height, stride, &plan, window, dst, dst_stride, store, scratch, skip,
            ),
            (true, true) => passes::plan_gauss::<true, true>(
                src, width, height, stride, &plan, window, dst, dst_stride, store, scratch, skip,
            ),
        }
    }
}

/// The whole blur into a fresh, tightly packed [`skia::BlurredMask`] with the interior skip chosen by the caller
/// (allocates the output and a scratch): [`skia::blur_a8`]'s bytes (the testing exports and tests).
#[cfg(any(test, feature = "testing"))]
pub(crate) fn blur_a8_with(
    src: &[u8],
    width: usize,
    height: usize,
    stride: usize,
    sigma: f64,
    skip: InteriorSkip,
) -> skia::BlurredMask {
    let geometry = blur_geometry(width, height, sigma);
    let mut pixels = alloc::vec![0u8; geometry.width * geometry.height];
    let mut scratch = Scratch::new();
    let window = Window::full(geometry.width, geometry.height);
    blur_a8_window_into(
        src,
        width,
        height,
        stride,
        sigma,
        window,
        &mut pixels,
        geometry.width,
        Store::Replace,
        &mut scratch,
        skip,
    );
    skia::BlurredMask {
        pixels,
        width: geometry.width,
        height: geometry.height,
        border_x: geometry.border_x,
        border_y: geometry.border_y,
    }
}

#[cfg(test)]
#[path = "blur_tests.rs"]
mod tests;
