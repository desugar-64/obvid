// Copyright 2006 The Android Open Source Project
// Copyright 2012 Google Inc.
// Copyright 2026, Serhii Yaremych (desugar-64) and the Obvid project contributors (Rust port)
// SPDX-License-Identifier: BSD-3-Clause
// See LICENSES/Skia-BSD-3-Clause.txt

//! The nine-patch route for a blurred uniform round rect, as Skia's CPU path draws it (`filterRRectToNine` +
//! `draw_nine`): Skia rasterizes a small round rect at the origin, blurs it, and stretches it into the device rect.
//! Compose's round-rect shadow (`DropShadowRenderer.createOuterShadowBitmap(size, radius, spread, cornerRadius)`)
//! takes this path; reproducing it exactly only needs the small rrect drawn by Android's `Canvas` (the caller)
//! and Obvid's blur. The geometry is Android 16–17's `filterRRectToNine` (since Skia b76693e0: integer pieces with a
//! stretch of 1), used whatever the device's Skia version.
//!
//! - [`plan_round_rect`] (steps 0–5 in its doc): Skia's decision and geometry for
//!   `Canvas.drawRoundRect(l, t, r, b, rx, ry)` with `BlurMaskFilter(radius_px, NORMAL)` under an identity CTM (Compose
//!   draws the round rect without a translate: `drawRoundRect(radius, radius, shadowWidth − radius, shadowHeight −
//!   radius, …)`, so `SkRRect::transform` copies the rrect): [`RoundRectRoute::NinePatch`] with the small rrect and
//!   the stretch geometry, [`RoundRectRoute::FullMask`] when Skia blurs the whole mask of the shape's path (a simple
//!   rrect that leaves nothing to stretch, or an oval), or [`RoundRectRoute::Skia`] for everything this route does
//!   not cover (rect, empty, non-finite, over 32767, no blur).
//! - [`ShadowBuilder::nine_into`]: blurs the small rrect the caller drew (`(0, 0, patch.rect_w, rect_h)` into a
//!   `patch.small_w × small_h` A8 window at the origin, anti-aliased, no mask filter) with Obvid's kernel into a
//!   grow-only buffer of the builder, then [`write_nine`] writes the nine-patch into the shadow bitmap.
//! - [`ShadowBuilder::nine_keep_into`] / [`place_nine`]: the same, with the blurred small rrect kept in a caller
//!   buffer, and the nine-patch written again from that buffer alone. The blurred small rrect depends only on
//!   [`NinePatch::small_key`] (never on the rect's position or size), so a later round rect with an equal key is
//!   placed without drawing or blurring anything: what Skia's nine-patch cache does.
//! - [`write_nine`]: `draw_nine` with `fillCenter = true` into a zeroed bitmap, clipped to the used rect (Compose's
//!   bitmap, also the canvas clip). Per pixel (mask `MW × MH`, outer rect `outer`, centre (cx, cy)):
//!   `mx = x − outer.l` left of `outer.l + cx`, centre up to `outer.r + cx + 1 − MW`, `x − (outer.r − MW)` after;
//!   `my` likewise; the value is 255 in the centre (a `blitRect`, not `mask[cy][cx]`), `mask[my][cx]` on the top and
//!   bottom bands, `mask[cy][mx]` on the left and right bands, `mask[my][mx]` in the corners. Skia's blit of opaque
//!   black with coverage c onto 0 writes c, so the bitmap gets these values; every other pixel of the clear rect
//!   is set to 0 (the bitmap is reused and holds an older shadow).
//! - [`write_nine_inverted`] and the inverted twins of the three writers above ([`ShadowBuilder::nine_inverted_into`],
//!   [`ShadowBuilder::nine_keep_inverted_into`], [`place_nine_inverted`]): the same write with every value v inside
//!   the used rect stored as `255 − v`, in the same pass: the inner shadow's bitmap, exactly what the plain
//!   write followed by [`super::invert_used`] leaves. Their `…_hole` forms also return the bitmap's hole
//!   ([`super::hole::nine_hole`]).
//!
//! The writer and the blur of the small mask read only its [`Stretch`] (the small mask's size and blur, the outer rect,
//! the centre), which the frame nine-patch ([`super::rects`]) shares, with the centre left unfilled there.
//!
//! Not modelled: `SkCanvas`'s quick reject and the tiler's bounds test. Both skip a draw whose blurred bounds miss
//! the clip; Compose's rrect lies inside its bitmap, so they never skip it.

use super::hole::{Hole, map_nine_hole, nine_hole, nine_mask_hole};
use super::{A8, A8Mut, ShadowBuilder};
use crate::blur::{self, InteriorSkip, Store, Window};
use crate::math;
use crate::neon::a8;
use crate::skia;

/// What Skia does with a blurred round rect (identity CTM); see the module docs.
#[derive(Clone, Copy, Debug, PartialEq)]
pub enum RoundRectRoute {
    /// Not handled here: draw it with Android's `Canvas` and `BlurMaskFilter` (rect, empty, non-finite, a rect over
    /// Skia's 32767 limit, a blur too small to blur, radius ≤ 0).
    Skia,
    /// Skia blurs the whole mask of the shape's path: a simple round rect too small for a nine-patch (the small
    /// rrect's width ≥ the rect's width, or the same in y; Skia then draws `SkPath::RRect(rrect)` through
    /// `filterPath`), or an oval (`SkCanvas::onDrawRRect` sends it to `drawOval`, which fills `SkPath::Oval(rect)`
    /// through `filterPath`; `filterRRectToNine` would return unimplemented for it anyway).
    FullMask,
    NinePatch(NinePatch),
}

impl RoundRectRoute {
    /// The route for the JNI caller in one `i64`: 0 = [`RoundRectRoute::Skia`], 1 = [`RoundRectRoute::FullMask`],
    /// otherwise [`RoundRectRoute::NinePatch`] with the f32 bits of `rect_w` in bits 0..32 and of `rect_h` in bits
    /// 32..64. Both are ≥ 3 (positive, exponent ≥ 1), so a nine-patch never packs to 0 or 1.
    pub fn packed(&self) -> i64 {
        match self {
            RoundRectRoute::Skia => 0,
            RoundRectRoute::FullMask => 1,
            RoundRectRoute::NinePatch(p) => p.rect_w.to_bits() as i64 | (p.rect_h.to_bits() as i64) << 32,
        }
    }
}

/// The nine-patch of a blurred simple round rect (`filterRRectToNine`).
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct NinePatch {
    /// The small rrect `(0, 0, rect_w, rect_h)` the caller rasterizes (Skia's `smallR`), with the radii passed to
    /// [`plan_round_rect`] (`setRectRadii(smallR, rrect.radii())`; a nine-patch's are never scaled):
    /// `W = 2·(ceil(rx) + border) + 1` (whole), likewise H with ry.
    pub rect_w: f32,
    pub rect_h: f32,
    /// The small rrect's corner radii: `setRectXY`'s, which for a nine-patch are the given ones (never scaled, see
    /// [`plan_round_rect`]).
    pub rx: f32,
    pub ry: f32,
    /// The A8 mask it is drawn into at the origin (`smallR.roundOut()`): `ceil(rect_w) × ceil(rect_h)`.
    pub small_w: usize,
    pub small_h: usize,
    /// The device sigma the mask filter blurs with (`SkMaskBlurFilter`'s input).
    pub sigma: f64,
    /// Skia's margin m: the blur border on each side. The blurred mask is `(W + 2m) × (H + 2m)`.
    pub border: usize,
    /// The device rect's `roundOut()` outset by m (`dstM.fBounds`): the pixels the nine-patch covers.
    pub outer_l: isize,
    pub outer_t: isize,
    pub outer_r: isize,
    pub outer_b: isize,
    /// The stretch column and row in the blurred mask (`fCenter`): `m + ceil(rx) + m`, likewise with ry.
    pub center_x: usize,
    pub center_y: usize,
}

impl NinePatch {
    /// The stretch geometry [`write_nine`] and the blur of the small mask read.
    pub fn stretch(&self) -> Stretch {
        Stretch {
            small_w: self.small_w,
            small_h: self.small_h,
            sigma: self.sigma,
            border: self.border,
            outer_l: self.outer_l,
            outer_t: self.outer_t,
            outer_r: self.outer_r,
            outer_b: self.outer_b,
            center_x: self.center_x,
            center_y: self.center_y,
        }
    }

    /// Width of the blurred small mask, `W + 2m`.
    pub fn mask_w(&self) -> usize {
        self.small_w + 2 * self.border
    }

    /// Height of the blurred small mask, `H + 2m`.
    pub fn mask_h(&self) -> usize {
        self.small_h + 2 * self.border
    }

    /// Every input of the blurred small rrect: the small rrect `(0, 0, rect_w, rect_h)` with radii (`rx`, `ry`) (the
    /// caller's draw; `small_w × small_h` is `ceil` of the rect) and the sigma of its blur (the border and so the mask
    /// size follow from it). Two patches with equal keys have identical blurred small masks, whatever their
    /// outer rects.
    pub fn small_key(&self) -> SmallMaskKey {
        SmallMaskKey {
            rect_w: self.rect_w.to_bits(),
            rect_h: self.rect_h.to_bits(),
            rx: self.rx.to_bits(),
            ry: self.ry.to_bits(),
            sigma: self.sigma.to_bits(),
        }
    }
}

/// What `draw_nine` reads of a nine-patch, whatever shape the small mask holds: the small mask's size (`small_w ×
/// small_h`, drawn by the caller), the sigma it is blurred with and its border m (the blurred mask is
/// `(small_w + 2m) × (small_h + 2m)`), the outer rect (`fOuterRect`) and the stretch column and row in the blurred
/// mask (`fCenter`).
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Stretch {
    pub small_w: usize,
    pub small_h: usize,
    pub sigma: f64,
    pub border: usize,
    pub outer_l: isize,
    pub outer_t: isize,
    pub outer_r: isize,
    pub outer_b: isize,
    pub center_x: usize,
    pub center_y: usize,
}

impl Stretch {
    /// Width of the blurred small mask, `small_w + 2m`.
    pub fn mask_w(&self) -> usize {
        self.small_w + 2 * self.border
    }

    /// Height of the blurred small mask, `small_h + 2m`.
    pub fn mask_h(&self) -> usize {
        self.small_h + 2 * self.border
    }

    /// Whether [`write_nine`] can write it: the centre column and row lie inside the blurred mask and the centre
    /// (`draw_nine_clipped`'s innerR) is not empty on either axis.
    pub fn is_consistent(&self) -> bool {
        let (mw, mh) = (self.mask_w() as isize, self.mask_h() as isize);
        let (cx, cy) = (self.center_x as isize, self.center_y as isize);
        cx < mw
            && cy < mh
            && self.outer_l + cx < self.outer_r + (cx + 1 - mw)
            && self.outer_t + cy < self.outer_b + (cy + 1 - mh)
    }
}

/// [`NinePatch::small_key`]: the bits of the small rrect, its radii and the blur sigma.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct SmallMaskKey {
    pub rect_w: u32,
    pub rect_h: u32,
    pub rx: u32,
    pub ry: u32,
    pub sigma: u64,
}

/// `std::min(a, b)` on floats: `(b < a) ? b : a`.
fn sk_min(a: f32, b: f32) -> f32 {
    if b < a { b } else { a }
}

/// `std::max(a, b)` on floats: `(a < b) ? b : a` (returns `a` on ties).
fn sk_max(a: f32, b: f32) -> f32 {
    if a < b { b } else { a }
}

/// `SkScalarFloorToInt` / `SkScalarCeilToInt` (`sk_float_floor2int` / `sk_float_ceil2int`): `floorf` / `ceilf`, then
/// [`saturate2int`].
pub(super) fn floor_to_int(x: f32) -> i32 {
    saturate2int(math::floorf(x))
}

pub(super) fn ceil_to_int(x: f32) -> i32 {
    saturate2int(math::ceilf(x))
}

/// `sk_float_saturate2int`: clamped to ±`SK_MaxS32FitsInFloat` (2147483520, the largest float below 2³¹) by two
/// float comparisons, then cast (a NaN fails the first comparison and becomes the maximum).
fn saturate2int(x: f32) -> i32 {
    const MAX_S32_FITS_IN_FLOAT: f32 = 2_147_483_520.0;
    let x = if x < MAX_S32_FITS_IN_FLOAT { x } else { MAX_S32_FITS_IN_FLOAT };
    let x = if x > -MAX_S32_FITS_IN_FLOAT { x } else { -MAX_S32_FITS_IN_FLOAT };
    x as i32
}

/// The type `SkRRect::setRectXY` gives a rect and radii.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum RRectType {
    Empty,
    Rect,
    Oval,
    Simple,
}

/// An `SkRRect` with four equal corners: the sorted rect and the radii after `setRectXY`'s scaling.
#[derive(Clone, Copy, Debug, PartialEq)]
struct RRect {
    l: f32,
    t: f32,
    r: f32,
    b: f32,
    rx: f32,
    ry: f32,
    kind: RRectType,
}

impl RRect {
    /// `SkRect::width()`: `fRight − fLeft` in float.
    fn width(&self) -> f32 {
        self.r - self.l
    }

    fn height(&self) -> f32 {
        self.b - self.t
    }
}

/// `SkRRect::setRectXY`
/// ([Android 17 SkRRect.cpp:54](https://android.googlesource.com/platform/external/skia/+/508606366e71a3daa933a4e08bc49b84e387ec18/src/core/SkRRect.cpp#54))
/// after `initializeRect`
/// ([:216](https://android.googlesource.com/platform/external/skia/+/508606366e71a3daa933a4e08bc49b84e387ec18/src/core/SkRRect.cpp#216)),
/// in float exactly as written.
fn set_rect_xy(l: f32, t: f32, r: f32, b: f32, mut rx: f32, mut ry: f32) -> RRect {
    let empty = RRect { l: 0.0, t: 0.0, r: 0.0, b: 0.0, rx: 0.0, ry: 0.0, kind: RRectType::Empty };
    // initializeRect: non-finite → empty; `makeSorted` (std::min / std::max); `isEmpty` = !(l < r && t < b).
    if !(l.is_finite() && t.is_finite() && r.is_finite() && b.is_finite()) {
        return empty;
    }
    let (sl, st, sr, sb) = (sk_min(l, r), sk_min(t, b), sk_max(l, r), sk_max(t, b));
    if !(sl < sr && st < sb) {
        return RRect { l: sl, t: st, r: sr, b: sb, ..empty };
    }
    let mut rr = RRect { l: sl, t: st, r: sr, b: sb, rx: 0.0, ry: 0.0, kind: RRectType::Rect };
    if !(rx.is_finite() && ry.is_finite()) {
        rx = 0.0;
        ry = 0.0;
    }
    let (w, h) = (rr.width(), rr.height());
    if w < rx + rx || h < ry + ry {
        // sk_ieee_float_divide: plain IEEE division.
        let scale = sk_min(w / (rx + rx), h / (ry + ry));
        rx *= scale;
        ry *= scale;
    }
    if rx <= 0.0 || ry <= 0.0 {
        return rr; // setRect: all corners square
    }
    rr.rx = rx;
    rr.ry = ry;
    // SkScalarHalf(x) = x * 0.5f.
    rr.kind = if rx >= w * 0.5 && ry >= h * 0.5 { RRectType::Oval } else { RRectType::Simple };
    rr
}

/// `rect_exceeds`
/// ([Android 17 SkBlurMaskFilterImpl.cpp:201](https://android.googlesource.com/platform/external/skia/+/508606366e71a3daa933a4e08bc49b84e387ec18/src/core/SkBlurMaskFilterImpl.cpp#201)).
fn rect_exceeds(rr: &RRect, v: f32) -> bool {
    rr.l < -v || rr.t < -v || rr.r > v || rr.b > v || rr.width() > v || rr.height() > v
}

/// Skia's route and nine-patch geometry for `Canvas.drawRoundRect(l, t, r, b, rx, ry)` with
/// `BlurMaskFilter(radius_px, NORMAL)` on a canvas with an identity matrix, as Android 16–17's Skia decides it, in
/// these steps:
/// 0. `SkCanvas::drawRoundRect`: `rx > 0 && ry > 0` else a rect; `setRectXY` (sort, scale the radii when
///    `width < 2·rx` or `height < 2·ry`, rect / oval / simple). Simple and oval go on.
/// 1. Identity CTM: the device rrect is the rrect.
/// 2. σ = `min(mapRadius(0.57735·r + 0.5), 128)` ([`skia::mask_blur_sigma_from_radius`]); no blur → not Obvid's.
/// 3. Oval: full mask (see [`RoundRectRoute::FullMask`]; no 32767 limit on that path). Simple: `filterRRectToNine`,
///    no rect over 32767.
/// 4. m = the blur border (`SkBlurMask::BoxBlur` on bounds only); outer = `roundOut(rect)` outset by m (`dstM`).
/// 5. The small rrect and the full-mask test (`plan_small_rrect`).
///
/// Rejections Skia would send to `drawPath` (over 32767, no blur) return [`RoundRectRoute::Skia`]: Skia's own draw
/// stays exact there. A nine-patch's radii are never scaled: scaling sets `2·rx' ≈ width` (or the same in y) to
/// within a few ulps, and then the small rrect (≥ `2·rx' + 1`) is wider than the rect for any width ≤ 32767, so it
/// is a full mask.
pub fn plan_round_rect(l: f32, t: f32, r: f32, b: f32, rx: f32, ry: f32, radius_px: f32) -> RoundRectRoute {
    // Compose sets no mask filter at radius ≤ 0; SkCanvas::drawRoundRect draws a rect unless both radii are > 0
    // (NaN fails every test).
    let positive = |x: f32| x > 0.0;
    if !positive(radius_px) || !(positive(rx) && positive(ry)) {
        return RoundRectRoute::Skia;
    }
    let rr = set_rect_xy(l, t, r, b, rx, ry);
    match rr.kind {
        RRectType::Oval => {}
        RRectType::Simple if !rect_exceeds(&rr, 32767.0) => {}
        _ => return RoundRectRoute::Skia,
    }
    let sigma = skia::mask_blur_sigma_from_radius(radius_px);
    if skia::has_no_blur(skia::pin_sigma(sigma)) {
        return RoundRectRoute::Skia;
    }
    if rr.kind == RRectType::Oval {
        return RoundRectRoute::FullMask;
    }
    let m = blur::blur_geometry(0, 0, sigma).border_x as i32;
    let Some(small) = plan_small_rrect(&rr, m) else {
        return RoundRectRoute::FullMask;
    };
    let m = m as isize;
    RoundRectRoute::NinePatch(NinePatch {
        rect_w: small.rect_w,
        rect_h: small.rect_h,
        rx: rr.rx,
        ry: rr.ry,
        small_w: ceil_to_int(small.rect_w) as usize,
        small_h: ceil_to_int(small.rect_h) as usize,
        sigma,
        border: m as usize,
        outer_l: floor_to_int(rr.l) as isize - m,
        outer_t: floor_to_int(rr.t) as isize - m,
        outer_r: ceil_to_int(rr.r) as isize + m,
        outer_b: ceil_to_int(rr.b) as isize + m,
        center_x: small.center_x,
        center_y: small.center_y,
    })
}

/// The small rrect `(0, 0, rect_w, rect_h)` and the stretch centre in its blurred mask.
struct SmallRRect {
    rect_w: f32,
    rect_h: f32,
    center_x: usize,
    center_y: usize,
}

/// [Android 17 SkBlurMaskFilterImpl.cpp:356–371](https://android.googlesource.com/platform/external/skia/+/508606366e71a3daa933a4e08bc49b84e387ec18/src/core/SkBlurMaskFilterImpl.cpp#356),
/// [:428](https://android.googlesource.com/platform/external/skia/+/508606366e71a3daa933a4e08bc49b84e387ec18/src/core/SkBlurMaskFilterImpl.cpp#428),
/// four equal corners: `W = (ceil(rx) + m) + (ceil(rx) + m) + 1`;
/// `f32(W) >= width` → full mask (None); the same in y. The small rrect is `(0, 0, W, H)`, whole;
/// `fCenter = (m + ceil(rx) + m, m + ceil(ry) + m)`.
fn plan_small_rrect(rr: &RRect, m: i32) -> Option<SmallRRect> {
    let left_u = ceil_to_int(sk_max(rr.rx, rr.rx)) + m;
    let right_u = ceil_to_int(sk_max(rr.rx, rr.rx)) + m;
    let small_w = left_u + right_u + 1;
    if small_w as f32 >= rr.width() {
        return None;
    }
    let top_u = ceil_to_int(sk_max(rr.ry, rr.ry)) + m;
    let bot_u = ceil_to_int(sk_max(rr.ry, rr.ry)) + m;
    let small_h = top_u + bot_u + 1;
    if small_h as f32 >= rr.height() {
        return None;
    }
    Some(SmallRRect {
        rect_w: small_w as f32,
        rect_h: small_h as f32,
        center_x: (m + left_u) as usize,
        center_y: (m + top_u) as usize,
    })
}

impl ShadowBuilder {
    /// The nine-patch route: blurs `small` (the `patch.small_w × small_h` window holding the small round rect the
    /// caller drew at the origin)
    /// with Obvid's kernel into the builder's nine-patch buffer and writes the nine-patch into `dst` ([`write_nine`]).
    /// Allocates nothing once the buffers have grown. Panics if `small` is not the patch's size or a rect exceeds
    /// `dst`.
    pub fn nine_into(
        &mut self,
        small: A8,
        patch: &NinePatch,
        dst: &mut A8Mut,
        used: (usize, usize),
        clear: (usize, usize),
    ) {
        self.nine_into_as::<false>(small, patch, dst, used, clear);
    }

    /// [`ShadowBuilder::nine_into`] with the nine-patch written by [`write_nine_inverted`]: `nine_into` followed by
    /// [`super::invert_used`] over the used rect, in one pass. Same allocation and panics.
    pub fn nine_inverted_into(
        &mut self,
        small: A8,
        patch: &NinePatch,
        dst: &mut A8Mut,
        used: (usize, usize),
        clear: (usize, usize),
    ) {
        self.nine_into_as::<true>(small, patch, dst, used, clear);
    }

    /// [`ShadowBuilder::nine_inverted_into`], also returning the hole of the result: [`super::hole::nine_hole`] of the
    /// blurred small rrect (the builder's buffer), clipped to the used rect. Same allocation and panics.
    pub fn nine_inverted_hole_into(
        &mut self,
        small: A8,
        patch: &NinePatch,
        dst: &mut A8Mut,
        used: (usize, usize),
        clear: (usize, usize),
    ) -> Hole {
        self.nine_into_as::<true>(small, patch, dst, used, clear);
        let (mw, mh) = (patch.mask_w(), patch.mask_h());
        nine_hole(A8 { pixels: &self.nine[..mw * mh], width: mw, height: mh, stride: mw }, &patch.stretch(), used)
    }

    /// [`ShadowBuilder::nine_into`], inverted when `INVERT` ([`write_nine_as`]).
    fn nine_into_as<const INVERT: bool>(
        &mut self,
        small: A8,
        patch: &NinePatch,
        dst: &mut A8Mut,
        used: (usize, usize),
        clear: (usize, usize),
    ) {
        self.stretch_into::<INVERT, true>(small, &patch.stretch(), dst, used, clear);
    }

    /// Blurs `small` (the `stretch.small_w × small_h` small mask the caller drew) into the builder's nine-patch buffer
    /// and writes the nine-patch from there ([`write_nine_as`], inverted when `INVERT`, the centre filled when
    /// `FILL_CENTRE`). Allocates nothing once the buffers have grown. Panics if `small` is not the stretch's size, the
    /// stretch is inconsistent or a rect exceeds `dst`.
    pub(super) fn stretch_into<const INVERT: bool, const FILL_CENTRE: bool>(
        &mut self,
        small: A8,
        stretch: &Stretch,
        dst: &mut A8Mut,
        used: (usize, usize),
        clear: (usize, usize),
    ) {
        let (mw, mh) = (stretch.mask_w(), stretch.mask_h());
        let mut nine = core::mem::take(&mut self.nine);
        let blurred = blur::grown(&mut nine, mw * mh);
        self.blur_small(small, stretch, blurred, mw);
        write_nine_as::<INVERT, FILL_CENTRE>(
            dst,
            A8 { pixels: blurred, width: mw, height: mh, stride: mw },
            stretch,
            used,
            clear,
        );
        self.nine = nine;
    }

    /// [`ShadowBuilder::nine_into`] with the blurred small rrect kept: it is stored into the top-left
    /// `patch.mask_w() × mask_h()` window of `keep` (bytes past it untouched), and the nine-patch is written from
    /// there, so [`place_nine`] can write it again for any patch with the same [`NinePatch::small_key`]. Allocates
    /// nothing once the scratch has grown. Panics if `small` is not the patch's size, `keep` is smaller than the
    /// blurred mask or a rect exceeds `dst`.
    pub fn nine_keep_into(
        &mut self,
        small: A8,
        patch: &NinePatch,
        keep: &mut A8Mut,
        dst: &mut A8Mut,
        used: (usize, usize),
        clear: (usize, usize),
    ) {
        self.nine_keep_into_as::<false>(small, patch, keep, dst, used, clear);
    }

    /// [`ShadowBuilder::nine_keep_into`] with the nine-patch written by [`write_nine_inverted`]: `nine_keep_into`
    /// followed by [`super::invert_used`] over the used rect, in one pass. `keep` gets the same blurred small rrect
    /// (not inverted), for either [`place_nine`] or [`place_nine_inverted`]. Same allocation and panics.
    pub fn nine_keep_inverted_into(
        &mut self,
        small: A8,
        patch: &NinePatch,
        keep: &mut A8Mut,
        dst: &mut A8Mut,
        used: (usize, usize),
        clear: (usize, usize),
    ) {
        self.nine_keep_into_as::<true>(small, patch, keep, dst, used, clear);
    }

    /// [`ShadowBuilder::nine_keep_inverted_into`], also returning the hole of the result: [`super::hole::nine_hole`] of
    /// the kept blurred small rrect, clipped to the used rect. Same allocation and panics.
    pub fn nine_keep_inverted_hole_into(
        &mut self,
        small: A8,
        patch: &NinePatch,
        keep: &mut A8Mut,
        dst: &mut A8Mut,
        used: (usize, usize),
        clear: (usize, usize),
    ) -> Hole {
        self.nine_keep_into_as::<true>(small, patch, keep, dst, used, clear);
        let kept = A8 { pixels: keep.pixels, width: keep.width, height: keep.height, stride: keep.stride };
        nine_hole(kept.sub(0, 0, patch.mask_w(), patch.mask_h()), &patch.stretch(), used)
    }

    /// [`ShadowBuilder::nine_keep_into`], inverted when `INVERT` ([`write_nine_as`]).
    fn nine_keep_into_as<const INVERT: bool>(
        &mut self,
        small: A8,
        patch: &NinePatch,
        keep: &mut A8Mut,
        dst: &mut A8Mut,
        used: (usize, usize),
        clear: (usize, usize),
    ) {
        let (mw, mh) = (patch.mask_w(), patch.mask_h());
        assert!(
            mw <= keep.width && mh <= keep.height && keep.stride >= keep.width,
            "keep bitmap smaller than the blurred mask"
        );
        self.blur_small(small, &patch.stretch(), keep.pixels, keep.stride);
        place_nine_as::<INVERT>(
            dst,
            A8 { pixels: keep.pixels, width: keep.width, height: keep.height, stride: keep.stride },
            patch,
            used,
            clear,
        );
    }

    /// Blurs `small` (the stretch's small mask) into the `stretch.mask_w() × mask_h()` window at the start of `out`,
    /// rows `out_stride` apart. Panics if `small` is not the stretch's size or `out` is too short.
    fn blur_small(&mut self, small: A8, stretch: &Stretch, out: &mut [u8], out_stride: usize) {
        assert!(
            small.width == stretch.small_w && small.height == stretch.small_h,
            "small mask does not match the patch"
        );
        let g = blur::blur_geometry(small.width, small.height, stretch.sigma);
        assert!((g.width, g.height) == (stretch.mask_w(), stretch.mask_h()), "blurred mask does not match the patch");
        let A8 { pixels, width, height, stride } = small;
        let (window, skip) = (Window::full(g.width, g.height), InteriorSkip::Auto);
        blur::blur_a8_window_into(
            pixels,
            width,
            height,
            stride,
            stretch.sigma,
            window,
            out,
            out_stride,
            Store::Replace,
            &mut self.scratch,
            skip,
        );
    }
}

/// Writes the nine-patch of `patch` into `dst` ([`write_nine`]) from `keep`, whose top-left
/// `patch.mask_w() × mask_h()` window holds a blurred small rrect kept by [`ShadowBuilder::nine_keep_into`] for a
/// patch with the same [`NinePatch::small_key`] (the caller's contract: the bytes are not checked). Panics if `keep`
/// is smaller than the blurred mask or a rect exceeds `dst`.
pub fn place_nine(dst: &mut A8Mut, keep: A8, patch: &NinePatch, used: (usize, usize), clear: (usize, usize)) {
    place_nine_as::<false>(dst, keep, patch, used, clear);
}

/// [`place_nine`] with the nine-patch written by [`write_nine_inverted`]: `place_nine` followed by
/// [`super::invert_used`] over the used rect, in one pass. Same contract and panics.
pub fn place_nine_inverted(dst: &mut A8Mut, keep: A8, patch: &NinePatch, used: (usize, usize), clear: (usize, usize)) {
    place_nine_as::<true>(dst, keep, patch, used, clear);
}

/// [`place_nine_inverted`], also returning the hole of the result: `mask_hole` (the kept mask's
/// [`super::hole::nine_mask_hole`], found once for its bytes), or that rect read from `keep` now when None, mapped to
/// this placement and clipped to the used rect ([`super::hole::map_nine_hole`]). Same contract and panics.
pub fn place_nine_inverted_hole(
    dst: &mut A8Mut,
    keep: A8,
    patch: &NinePatch,
    used: (usize, usize),
    clear: (usize, usize),
    mask_hole: Option<Hole>,
) -> Hole {
    place_nine_as::<true>(dst, keep, patch, used, clear);
    let stretch = patch.stretch();
    let in_mask = mask_hole.unwrap_or_else(|| nine_mask_hole(keep.sub(0, 0, patch.mask_w(), patch.mask_h()), &stretch));
    map_nine_hole(in_mask, &stretch, used)
}

/// The rect of the kept blurred small rrect in `keep` (its top-left `patch.mask_w() × mask_h()` window) whose image is
/// the inverted nine-patch's hole for every placement of a patch with the same [`NinePatch::small_key`]
/// ([`super::hole::nine_mask_hole`]). Panics if `keep` is smaller than the blurred mask.
pub fn kept_mask_hole(keep: A8, patch: &NinePatch) -> Hole {
    nine_mask_hole(keep.sub(0, 0, patch.mask_w(), patch.mask_h()), &patch.stretch())
}

/// [`place_nine`], inverted when `INVERT` ([`write_nine_as`]).
fn place_nine_as<const INVERT: bool>(
    dst: &mut A8Mut,
    keep: A8,
    patch: &NinePatch,
    used: (usize, usize),
    clear: (usize, usize),
) {
    let (mw, mh) = (patch.mask_w(), patch.mask_h());
    assert!(
        mw <= keep.width && mh <= keep.height && keep.stride >= keep.width,
        "keep bitmap smaller than the blurred mask"
    );
    write_nine_as::<INVERT, true>(
        dst,
        A8 { pixels: keep.pixels, width: mw, height: mh, stride: keep.stride },
        &patch.stretch(),
        used,
        clear,
    );
}

/// Writes the nine-patch of `mask` (the blurred small rrect, `patch.mask_w() × mask_h()`) into `dst`, clipped to the
/// used rect `[0, used.0) × [0, used.1)`, and zeroes every other pixel of the clear rect (raised to the used size);
/// the per-pixel values are in the module docs. Per row: zeros, the left part of a mask row, one value filled
/// across the centre, the right part of the same mask row, zeros. Panics if the used or clear rect exceeds `dst` or
/// the patch is inconsistent.
pub fn write_nine(dst: &mut A8Mut, mask: A8, patch: &NinePatch, used: (usize, usize), clear: (usize, usize)) {
    write_nine_as::<false, true>(dst, mask, &patch.stretch(), used, clear);
}

/// [`write_nine`] followed by [`super::invert_used`] over the used rect `[0, used.0) × [0, used.1)`, in one pass:
/// inside the used rect every value v of the plain write is stored as `255 − v` (the mask parts copied through
/// [`a8::invert_copy_row`], the zero fills as 255, the centre value c as `255 − c`, so 0 on the centre rows); every
/// other pixel of the clear rect is 0, as `invert_used` leaves it. Same panics as [`write_nine`].
pub fn write_nine_inverted(dst: &mut A8Mut, mask: A8, patch: &NinePatch, used: (usize, usize), clear: (usize, usize)) {
    write_nine_as::<true, true>(dst, mask, &patch.stretch(), used, clear);
}

/// [`write_nine`], or [`write_nine_inverted`] when `INVERT`; without `FILL_CENTRE`, `draw_nine` with `fillCenter =
/// false` (Skia's frame nine-patch): the centre is not blitted, so it keeps the zeroed bitmap's 0 (255 when inverted)
/// while the bands and corners are written as with the fill. Both modes are compile-time constants, so the plain write
/// has no extra work per row or per pixel.
pub(super) fn write_nine_as<const INVERT: bool, const FILL_CENTRE: bool>(
    dst: &mut A8Mut,
    mask: A8,
    patch: &Stretch,
    used: (usize, usize),
    clear: (usize, usize),
) {
    let (used_w, used_h) = used;
    let (clear_w, clear_h) = (clear.0.max(used_w), clear.1.max(used_h));
    assert!(clear_w <= dst.width && clear_h <= dst.height, "clear rect larger than the bitmap");
    assert!(dst.stride >= dst.width, "stride smaller than width");
    let (mw, mh) = (mask.width as isize, mask.height as isize);
    assert!(
        mask.width == patch.mask_w() && mask.height == patch.mask_h() && mask.stride >= mask.width,
        "mask does not match the patch"
    );
    let (cx, cy) = (patch.center_x as isize, patch.center_y as isize);
    let (ol, ot, or, ob) = (patch.outer_l, patch.outer_t, patch.outer_r, patch.outer_b);
    // draw_nine_clipped's innerR: the centre is [inner_l, inner_r) × [inner_t, inner_b).
    let (inner_l, inner_r) = (ol + cx, or + (cx + 1 - mw));
    let (inner_t, inner_b) = (ot + cy, ob + (cy + 1 - mh));
    assert!(cx < mw && cy < mh && inner_l < inner_r && inner_t < inner_b, "inconsistent patch");
    // The three column ranges, clipped to the used width (they stay contiguous: l1 == c0, c1 == r0).
    let clip_x = |x: isize| x.clamp(0, used_w as isize) as usize;
    let (l0, l1, r0, r1) = (clip_x(ol), clip_x(inner_l), clip_x(inner_r), clip_x(or));
    // Mask column of the first pixel of the left and right parts (used only when the part is not empty, so both are
    // inside the mask: l0 ≥ ol and r0 ≥ inner_r = or − mw + cx + 1).
    let (left_src, right_src) = ((l0 as isize - ol) as usize, (r0 as isize - (or - mw)) as usize);
    // What a zero of the plain write becomes inside the used rect.
    let zero = if INVERT { 255 } else { 0 };
    for dy in 0..clear_h {
        let row = &mut dst.pixels[dy * dst.stride..dy * dst.stride + clear_w];
        let y = dy as isize;
        if dy >= used_h || y < ot || y >= ob {
            if INVERT && dy < used_h {
                row[..used_w].fill(255);
                row[used_w..].fill(0);
            } else {
                row.fill(0);
            }
            continue;
        }
        // Mask row and centre value: a top / bottom row is mask row my with its column cx across the centre; a
        // centre row is mask row cy with 255 across the centre (0 when the centre is not filled).
        let (my, centre_row) = if y < inner_t {
            (y - ot, false)
        } else if y < inner_b {
            (cy, true)
        } else {
            (y - (ob - mh), false)
        };
        let src = &mask.pixels[my as usize * mask.stride..my as usize * mask.stride + mask.width];
        let centre = if centre_row { if FILL_CENTRE { 255 } else { 0 } } else { src[cx as usize] };
        row[..l0].fill(zero);
        if l1 > l0 {
            copy_row::<INVERT>(&mut row[l0..l1], &src[left_src..left_src + (l1 - l0)]);
        }
        row[l1..r0].fill(if INVERT { 255 - centre } else { centre });
        if r1 > r0 {
            copy_row::<INVERT>(&mut row[r0..r1], &src[right_src..right_src + (r1 - r0)]);
        }
        if INVERT {
            // r1 ≤ used_w: the columns were clipped to the used width.
            row[r1..used_w].fill(255);
            row[used_w..].fill(0);
        } else {
            row[r1..].fill(0);
        }
    }
}

/// A part of a mask row into `dst`: copied, or inverted ([`a8::invert_copy_row`]) when `INVERT`.
#[inline(always)]
fn copy_row<const INVERT: bool>(dst: &mut [u8], src: &[u8]) {
    if INVERT {
        a8::invert_copy_row(dst, src);
    } else {
        dst.copy_from_slice(src);
    }
}

#[cfg(test)]
#[path = "nine_tests.rs"]
mod tests;
