// Copyright 2026, Serhii Yaremych (desugar-64) and the Obvid project contributors
// SPDX-License-Identifier: Apache-2.0

//! The native half of the shadow builder (`ShadowBitmapBuilder` in Kotlin). One [`ShadowBuilder`] per Kotlin builder,
//! behind an opaque handle; it owns the kernel [`Scratch`], grow-only, so a rebuild allocates nothing once it has
//! grown.
//!
//! A rebuild of the mask pipeline is one or two calls of [`ShadowBuilder::blur_into`]: the A8 mask Android's `Canvas`
//! drew (Skia's mask bounds, no mask filter) is blurred by the NEON kernel (interior skip on) straight into the
//! shadow bitmap. The blurred mask's top-left lands at the mask's origin minus the blur border, and only its window
//! inside Compose's bitmap (the used rect `[0, used_w) × [0, used_h)`, the bitmap Compose would allocate) is
//! computed and stored ([`blur::blur_a8_window_into`]): Skia's blur border (≈ 3σ) is wider than Compose's padding
//! (r + spread), so the outputs outside the used rect are never formed, and there is no blurred-mask buffer to copy.
//!
//! Two modes, as Skia's `filterPath` blit (`SkA8_Blitter::blitMask`, black paint) does them into Compose's bitmap:
//! - [`Store::Replace`], the first mask: the bitmap held zeros there, and srcover onto 0 is the mask value itself,
//!   so the window is stored plainly. It also clears: every other pixel of the clear rect `[0, clear_w) × [0,
//!   clear_h)` (the used rect plus whatever an earlier, larger rebuild may have left) is set to 0, so the used region
//!   holds no stale pixels and each byte is written once.
//! - [`Store::SourceOver`], the path + spread stroke: `d = div255((255 − m)·d + 255·m)`
//!   ([`crate::neon::a8::src_over`]) over the window; nothing else is touched.
//!
//! Bitmaps are addressed through their real row stride (`AndroidBitmap_getInfo().stride`); bytes past each row's
//! width and rows past the clear rect are never written. The JNI wrapper (`jni/builder.rs`, Android only) locks the
//! bitmaps. The tests check the direct path against `write_window`, which writes the window of a whole blurred
//! mask from a buffer (the same window and clear as the direct path).
//!
//! [`nine`]: the nine-patch route of a blurred uniform round rect (Android 16–17's geometry), [`ShadowBuilder::nine_into`],
//! or with the blurred small round rect kept by the caller and placed again ([`ShadowBuilder::nine_keep_into`],
//! [`nine::place_nine`]).
//!
//! [`rects`]: the nine-patch route of a blurred frame, two nested rects (Android 17's `filterRectsToNine`): the small
//! frame blurred and stretched without its centre ([`ShadowBuilder::nine_rects_into`]).
//!
//! Inner shadows use the same writes with Compose's inner geometry (a bitmap of the component's size, or grown by the
//! blur padding for a path; the shape moved by the offset, so masks and nine-patches may lie partly or wholly outside
//! the used rect and are clipped to it), then [`invert_used`]: Compose's inner bitmap is `255 − B`, B being what the
//! blurred draw leaves in an empty bitmap, because its opaque `Xor` rect over A8 is exactly `255 − d`. The nine-patch
//! writes have inverted twins that store `255 − B` in the same pass ([`nine::write_nine_inverted`]).
//!
//! [`hole`]: the rect of 0s of an inner shadow's bitmap, which its final draw may leave out: read from the bitmap
//! after the inversion, or for the round-rect nine-patch from its blurred small mask.

pub mod hole;
pub mod nine;
pub mod rects;

use alloc::vec::Vec;

use crate::blur::{self, InteriorSkip, Scratch, Store, Window};
use crate::neon::a8;
use crate::skia;

impl Store {
    /// The `mode` of `ObvidNative.builderBlur`: 0 → `Replace`, 1 → `SourceOver`.
    pub fn from_index(i: i32) -> Option<Store> {
        match i {
            0 => Some(Store::Replace),
            1 => Some(Store::SourceOver),
            _ => None,
        }
    }
}

/// A8 pixels with a row stride: `height` rows of `width` bytes, `stride` bytes apart.
#[derive(Clone, Copy, Debug)]
pub struct A8<'a> {
    pub pixels: &'a [u8],
    pub width: usize,
    pub height: usize,
    pub stride: usize,
}

impl<'a> A8<'a> {
    /// The `width × height` window of these pixels whose top-left is (`x`, `y`), with the same stride. Panics if the
    /// window is not inside `self.width × self.height`.
    pub fn sub(&self, x: usize, y: usize, width: usize, height: usize) -> A8<'a> {
        assert!(x + width <= self.width && y + height <= self.height, "window outside the image");
        // An empty window reads nothing: it keeps the start, which may lie past the last row.
        let start = if width == 0 || height == 0 { 0 } else { y * self.stride + x };
        A8 { pixels: &self.pixels[start..], width, height, stride: self.stride }
    }
}

/// A writable A8 plane (the shadow bitmap): `height` rows of `width` bytes, `stride` bytes apart.
#[derive(Debug)]
pub struct A8Mut<'a> {
    pub pixels: &'a mut [u8],
    pub width: usize,
    pub height: usize,
    pub stride: usize,
}

/// Where the blurred mask goes in the shadow bitmap.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Placement {
    /// Device position of the unblurred mask's top-left (Skia's mask bounds origin, may be negative); the blurred
    /// mask lands at this minus the blur border.
    pub mask_x: isize,
    pub mask_y: isize,
    /// Compose's bitmap size: the only pixels written with mask values.
    pub used_w: usize,
    pub used_h: usize,
    /// [`Store::Replace`] only: zero every pixel of `[0, clear_w) × [0, clear_h)` outside the window. Values
    /// below the used size are raised to it.
    pub clear_w: usize,
    pub clear_h: usize,
}

/// The blur border (Skia's margin) `BlurMaskFilter(radius_px, NORMAL)` adds on each side of a mask.
pub fn border_for_radius(radius_px: f32) -> usize {
    blur::blur_geometry(0, 0, skia::mask_blur_sigma_from_radius(radius_px)).border_x
}

/// The native builder state: the kernel scratch and the nine-patch's blurred small mask, grow-only.
#[derive(Default)]
pub struct ShadowBuilder {
    scratch: Scratch,
    /// The blurred small round rect of the nine-patch route ([`nine`]).
    nine: Vec<u8>,
    /// Each row's run of 0s around the seed column of [`ShadowBuilder::invert_hole`].
    runs: Vec<Option<(usize, usize)>>,
}

impl ShadowBuilder {
    pub fn new() -> ShadowBuilder {
        ShadowBuilder::default()
    }

    /// [`invert_used`] of the used rect, returning the hole of the result grown from `seed`
    /// ([`hole::invert_used_hole`], with the builder's scratch). Allocates nothing once the scratch has grown. Panics
    /// if the used rect exceeds `dst`.
    pub fn invert_hole(&mut self, dst: &mut A8Mut, used: (usize, usize), seed: (usize, usize)) -> hole::Hole {
        hole::invert_used_hole(dst, used, seed, &mut self.runs)
    }

    /// Blurs `mask` at the sigma of `BlurMaskFilter(radius_px, NORMAL)` and writes it into `dst` at `at`, clipped to
    /// the used rect, with `mode`. Panics if the used or clear rect exceeds `dst`.
    pub fn blur_into(&mut self, mask: A8, radius_px: f32, dst: &mut A8Mut, at: Placement, mode: Store) {
        self.blur_window(mask, skia::mask_blur_sigma_from_radius(radius_px), dst, at, mode, InteriorSkip::Auto)
    }

    /// The direct route (module docs): the window of the blurred mask inside the used rect is blurred straight into
    /// `dst`; with [`Store::Replace`] the rest of the clear rect is zeroed first. Called with the shipping skip by
    /// [`ShadowBuilder::blur_into`], with every skip by the tests.
    fn blur_window(&mut self, mask: A8, sigma: f64, dst: &mut A8Mut, at: Placement, mode: Store, skip: InteriorSkip) {
        let g = blur::blur_geometry(mask.width, mask.height, sigma);
        // An empty mask blurs to zeros: nothing to store (Replace only clears).
        let (bw, bh) = if mask.width == 0 || mask.height == 0 { (0, 0) } else { (g.width, g.height) };
        // The blurred mask's top-left in `dst`.
        let (x, y) = (at.mask_x - g.border_x as isize, at.mask_y - g.border_y as isize);
        let w = used_window(dst, x, y, bw, bh, at);
        if mode == Store::Replace {
            clear_outside(dst, at, w);
        }
        if !w.is_empty() {
            let in_mask = Window {
                x0: (w.x0 as isize - x) as usize,
                y0: (w.y0 as isize - y) as usize,
                x1: (w.x1 as isize - x) as usize,
                y1: (w.y1 as isize - y) as usize,
            };
            let window_px = &mut dst.pixels[w.y0 * dst.stride + w.x0..];
            blur::blur_a8_window_into(
                mask.pixels,
                mask.width,
                mask.height,
                mask.stride,
                sigma,
                in_mask,
                window_px,
                dst.stride,
                mode,
                &mut self.scratch,
                skip,
            );
        }
    }
}

/// The part of a `width × height` blurred mask at (`x`, `y`) inside the used rect of `at`, in `dst` coordinates
/// (`x1 = x0`, `y1 = y0` when empty). Panics if the used rect exceeds `dst`.
fn used_window(dst: &A8Mut, x: isize, y: isize, width: usize, height: usize, at: Placement) -> Window {
    let (used_w, used_h) = (at.used_w, at.used_h);
    assert!(used_w <= dst.width && used_h <= dst.height, "used rect larger than the bitmap");
    assert!(dst.stride >= dst.width, "stride smaller than width");
    let x0 = x.clamp(0, used_w as isize) as usize;
    let y0 = y.clamp(0, used_h as isize) as usize;
    let x1 = (x + width as isize).clamp(0, used_w as isize) as usize;
    let y1 = (y + height as isize).clamp(0, used_h as isize) as usize;
    if x0 < x1 && y0 < y1 { Window { x0, y0, x1, y1 } } else { Window { x0, y0, x1: x0, y1: y0 } }
}

/// Zeroes every pixel of the clear rect of `at` (raised to the used rect) outside `window` (`dst` coordinates).
/// Panics if the clear rect exceeds `dst`.
fn clear_outside(dst: &mut A8Mut, at: Placement, window: Window) {
    let clear_w = at.clear_w.max(at.used_w);
    let clear_h = at.clear_h.max(at.used_h);
    assert!(clear_w <= dst.width && clear_h <= dst.height, "clear rect larger than the bitmap");
    for dy in 0..clear_h {
        let row = &mut dst.pixels[dy * dst.stride..dy * dst.stride + clear_w];
        if (window.y0..window.y1).contains(&dy) {
            row[..window.x0].fill(0);
            row[window.x1..].fill(0);
        } else {
            row.fill(0);
        }
    }
}

/// Inverts the used rect `[0, used_w) × [0, used_h)` of `dst` in place (`255 − v`, [`a8::invert_row`] per row); the
/// bytes past each row's used width and the rows below it are not touched. Panics if the used rect exceeds `dst`.
pub fn invert_used(dst: &mut A8Mut, used_w: usize, used_h: usize) {
    assert!(used_w <= dst.width && used_h <= dst.height, "used rect larger than the bitmap");
    assert!(dst.stride >= dst.width, "stride smaller than width");
    for y in 0..used_h {
        a8::invert_row(&mut dst.pixels[y * dst.stride..y * dst.stride + used_w]);
    }
}

/// Writes `src` (a blurred mask whose top-left is at (`x`, `y`) in `dst`) into `dst`, clipped to the used rect of
/// `at`, with `mode` (module docs): the direct route's window and clear (`used_window`, `clear_outside`), then the
/// window stored from `src`. Panics if the used or clear rect exceeds `dst`. The tests' reference of the direct route.
#[cfg(test)]
pub fn write_window(dst: &mut A8Mut, src: A8, x: isize, y: isize, at: Placement, mode: Store) {
    let w = used_window(dst, x, y, src.width, src.height, at);
    if mode == Store::Replace {
        clear_outside(dst, at, w);
    }
    for dy in w.y0..w.y1 {
        let from = (dy as isize - y) as usize * src.stride + (w.x0 as isize - x) as usize;
        let s = &src.pixels[from..from + (w.x1 - w.x0)];
        let d = &mut dst.pixels[dy * dst.stride + w.x0..dy * dst.stride + w.x1];
        match mode {
            Store::Replace => d.copy_from_slice(s),
            Store::SourceOver => a8::src_over_row(d, s),
        }
    }
}

#[cfg(test)]
#[path = "shadow_tests.rs"]
mod tests;
