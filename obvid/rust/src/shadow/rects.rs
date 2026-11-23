// Copyright 2006 The Android Open Source Project
// Copyright 2026, Serhii Yaremych (desugar-64) and the Obvid project contributors (Rust port)
// SPDX-License-Identifier: BSD-3-Clause
// See LICENSES/Skia-BSD-3-Clause.txt

//! The nine-patch route for a blurred frame, two nested rects of one path, as Skia's CPU path draws it
//! (`SkMaskFilterBase::filterPath` → `countNestedRects` = 2 → `filterRects` → `SkBlurMaskFilterImpl::filterRectsToNine`
//! → `draw_nine` with `fillCenter = false`): Skia draws a small even-odd frame, blurs it and stretches it into the
//! device frame without filling the centre. Reproducing it exactly only needs the small frame drawn by
//! Android's `Canvas` (the caller) and Obvid's blur. The geometry is Android 17's `filterRectsToNine` with two rects,
//! used whatever the device's Skia version.
//!
//! - [`plan_rects`]: Skia's decision and geometry for the outer and inner rect (as `IsNestedFillRects` returns them:
//!   the rect that contains the other first) blurred by `BlurMaskFilter(radius_px, NORMAL)` under a translate-only
//!   CTM: [`RectsRoute::NinePatch`] with the small frame and the stretch geometry, [`RectsRoute::Mask`] when Skia
//!   returns unimplemented and blurs the whole mask of the path (`filterPath`'s general route), or
//!   [`RectsRoute::Skia`] when it does not blur (no blur sigma) or the input is not a frame this module models.
//! - [`ShadowBuilder::nine_rects_into`] / [`ShadowBuilder::nine_rects_inverted_into`]: blur the small frame the caller
//!   drew into a `patch.stretch.small_w × small_h` A8 window (`roundOut(smallR0)`, drawn under the translate
//!   `(−mask_l, −mask_t)`) with Obvid's kernel and write the nine-patch without its centre: the centre stays 0, or 255
//!   in the inverted write (an inner shadow's `255 − B`).

use super::nine::{Stretch, ceil_to_int, floor_to_int};
use super::{A8, A8Mut, ShadowBuilder};
use crate::blur;
use crate::skia;

/// What Skia does with a blurred frame; see the module docs.
#[derive(Clone, Copy, Debug, PartialEq)]
pub enum RectsRoute {
    /// Not handled here: draw it with Android's `Canvas` and `BlurMaskFilter` (no blur sigma, radius ≤ 0, rects that
    /// are not finite, empty or nested).
    Skia,
    /// `filterRectsToNine` returns unimplemented (an outer rect over 32767, a hole too small for the blur, a small
    /// frame under 2 px): Skia blurs the whole mask of the path.
    Mask,
    NinePatch(RectsPatch),
}

/// The tag bit of a packed [`RectsRoute::NinePatch`].
pub const RECTS_NINE_TAG: i64 = 1 << 62;

impl RectsRoute {
    /// The route for the JNI caller in one `i64`: 0 = [`RectsRoute::Skia`], 1 = [`RectsRoute::Mask`], otherwise
    /// [`RECTS_NINE_TAG`] with `dx` in bits 0..32 and `dy` in bits 32..62 (both ≥ 0 and below 2¹⁷: the outer rect is at
    /// most 32767 wide and high).
    pub fn packed(&self) -> i64 {
        match self {
            RectsRoute::Skia => 0,
            RectsRoute::Mask => 1,
            RectsRoute::NinePatch(p) => RECTS_NINE_TAG | p.dx as i64 | (p.dy as i64) << 32,
        }
    }
}

/// The nine-patch of a blurred frame (`filterRectsToNine`, two rects).
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct RectsPatch {
    /// The whole pixels the frame shrinks by on each axis: `roundIn(inner)`'s width minus `2m + 3` (Skia's float
    /// `dx`, integral), likewise in y.
    pub dx: i32,
    pub dy: i32,
    /// The small frame the caller draws, even-odd, anti-aliased: `smallR0 = (o.l, o.t, o.r − dx, o.b − dy)` and
    /// `smallR1 = (i.l, i.t, i.r − dx, i.b − dy)`, LTRB in float.
    pub small_outer: [f32; 4],
    pub small_inner: [f32; 4],
    /// The device origin of the small mask, `roundOut(smallR0)`'s top-left: the caller draws the small frame under the
    /// translate `(−mask_l, −mask_t)` into a `stretch.small_w × small_h` window.
    pub mask_l: i32,
    pub mask_t: i32,
    /// The blur and the stretch: the small mask is `roundOut(smallR0)`, the outer rect `roundOut(outer)` outset by m,
    /// the centre `(2m + 2 + roundIn(inner).l − roundOut(outer).l, …)`.
    pub stretch: Stretch,
}

/// `SkRect::isEmpty`: `!(l < r && t < b)` (true for NaN).
fn is_empty(r: [f32; 4]) -> bool {
    !(r[0] < r[2] && r[1] < r[3])
}

/// `SkRect::contains(const SkRect&)`: both non-empty, `<=` / `>=` on each side.
pub fn contains(outer: [f32; 4], inner: [f32; 4]) -> bool {
    !is_empty(inner)
        && !is_empty(outer)
        && outer[0] <= inner[0]
        && outer[1] <= inner[1]
        && outer[2] >= inner[2]
        && outer[3] >= inner[3]
}

/// `rect_exceeds`
/// ([Android 17 SkBlurMaskFilterImpl.cpp:201](https://android.googlesource.com/platform/external/skia/+/508606366e71a3daa933a4e08bc49b84e387ec18/src/core/SkBlurMaskFilterImpl.cpp#201)).
fn rect_exceeds(r: [f32; 4], v: f32) -> bool {
    r[0] < -v || r[1] < -v || r[2] > v || r[3] > v || r[2] - r[0] > v || r[3] - r[1] > v
}

/// Skia's route and nine-patch geometry for a frame: the path whose `IsNestedFillRects` gives `outer` and `inner`
/// (LTRB; `outer` contains `inner`) filled with `BlurMaskFilter(radius_px, NORMAL)` under a translate-only CTM, as
/// Android 17's `filterRectsToNine` decides it
/// ([Android 17 SkBlurMaskFilterImpl.cpp:438–566](https://android.googlesource.com/platform/external/skia/+/508606366e71a3daa933a4e08bc49b84e387ec18/src/core/SkBlurMaskFilterImpl.cpp#438)),
/// in its order:
/// 1. Compose sets no mask filter at radius ≤ 0; σ = `min(mapRadius(0.57735·r + 0.5), 128)`. Rects that are not
///    finite, or not nested by `SkRect::contains`, are not a frame Skia's test returns: not modelled (Skia).
/// 2. No blur (`filterMask` returns false, here or on the general route's mask): Skia draws the path unblurred (Skia).
/// 3. `rect_exceeds(outer, 32767)` → unimplemented (Mask).
/// 4. `dstM` = `srcM = roundOut(outer)` outset by the border m (`filterMask` on bounds only).
/// 5. `smallW = dstM.w − srcM.w + 2`, `innerIR = roundIn(inner)`, `center = (smallW + innerIR.l − srcM.l, …)`;
///    `smallW += 1`; `dx = innerIR.w − smallW` (likewise y); `dx < 0 || dy < 0` → unimplemented (Mask).
/// 6. `smallR0 = (o.l, o.t, o.r − dx, o.b − dy)`; width or height < 2 → unimplemented (Mask);
///    `smallR1 = (i.l, i.t, i.r − dx, i.b − dy)`.
/// 7. The mask: `roundOut(smallR0)` (`draw_rects_into_mask`), blurred by m on each side; `draw_nine` at `dstM`'s
///    bounds with `center`. A stretch the writer cannot write (never seen: the centre is `dx + 1` wide) is left to
///    Skia.
///
/// Integer steps are Skia's `int` arithmetic, float steps its `float` arithmetic, `roundOut` / `roundIn` its
/// `sk_float_floor2int` / `sk_float_ceil2int`.
pub fn plan_rects(outer: [f32; 4], inner: [f32; 4], radius_px: f32) -> RectsRoute {
    // NaN fails the test.
    let positive = |x: f32| x > 0.0;
    if !positive(radius_px) {
        return RectsRoute::Skia;
    }
    if !outer.iter().chain(&inner).all(|v| v.is_finite()) || !contains(outer, inner) {
        return RectsRoute::Skia;
    }
    // No blur: `filterMask` fails here, or after the general route's mask for a rect over 32767; either way Skia
    // draws the path unblurred.
    let sigma = skia::mask_blur_sigma_from_radius(radius_px);
    if skia::has_no_blur(skia::pin_sigma(sigma)) {
        return RectsRoute::Skia;
    }
    if rect_exceeds(outer, 32767.0) {
        return RectsRoute::Mask;
    }
    let m = blur::blur_geometry(0, 0, sigma).border_x as i32;
    let [ol, ot, or, ob] = outer;
    let [il, it, ir, ib] = inner;
    // srcM = roundOut(outer); dstM = srcM outset by m.
    let (src_l, src_t, src_r, src_b) = (floor_to_int(ol), floor_to_int(ot), ceil_to_int(or), ceil_to_int(ob));
    let (dst_l, dst_t, dst_r, dst_b) = (src_l - m, src_t - m, src_r + m, src_b + m);
    let mut small_w = (dst_r - dst_l) - (src_r - src_l) + 2;
    let mut small_h = (dst_b - dst_t) - (src_b - src_t) + 2;
    // innerIR = roundIn(inner).
    let (in_l, in_t, in_r, in_b) = (ceil_to_int(il), ceil_to_int(it), floor_to_int(ir), floor_to_int(ib));
    let (center_x, center_y) = (small_w + (in_l - src_l), small_h + (in_t - src_t));
    small_w += 1;
    small_h += 1;
    let dx = (in_r - in_l) - small_w;
    let dy = (in_b - in_t) - small_h;
    if dx < 0 || dy < 0 {
        return RectsRoute::Mask;
    }
    let (fdx, fdy) = (dx as f32, dy as f32);
    let small_outer = [ol, ot, or - fdx, ob - fdy];
    if small_outer[2] - small_outer[0] < 2.0 || small_outer[3] - small_outer[1] < 2.0 {
        return RectsRoute::Mask;
    }
    let small_inner = [il, it, ir - fdx, ib - fdy];
    // draw_rects_into_mask: the mask bounds are roundOut(smallR0).
    let (mask_l, mask_t) = (floor_to_int(small_outer[0]), floor_to_int(small_outer[1]));
    let (mask_r, mask_b) = (ceil_to_int(small_outer[2]), ceil_to_int(small_outer[3]));
    if center_x < 0 || center_y < 0 || mask_r <= mask_l || mask_b <= mask_t {
        return RectsRoute::Skia;
    }
    let stretch = Stretch {
        small_w: (mask_r - mask_l) as usize,
        small_h: (mask_b - mask_t) as usize,
        sigma,
        border: m as usize,
        outer_l: dst_l as isize,
        outer_t: dst_t as isize,
        outer_r: dst_r as isize,
        outer_b: dst_b as isize,
        center_x: center_x as usize,
        center_y: center_y as usize,
    };
    if !stretch.is_consistent() {
        return RectsRoute::Skia;
    }
    RectsRoute::NinePatch(RectsPatch { dx, dy, small_outer, small_inner, mask_l, mask_t, stretch })
}

impl ShadowBuilder {
    /// The frame nine-patch route: blurs `small` (the `patch.stretch.small_w × small_h` window holding the small frame
    /// the caller drew) with Obvid's kernel into the builder's nine-patch buffer and writes the nine-patch without its
    /// centre into `dst`, clipped to the used rect `[0, used.0) × [0, used.1)`; every other pixel of the clear rect
    /// (raised to the used size) is set to 0. Allocates nothing once the buffers have grown. Panics if `small` is not
    /// the patch's size or a rect exceeds `dst`.
    pub fn nine_rects_into(
        &mut self,
        small: A8,
        patch: &RectsPatch,
        dst: &mut A8Mut,
        used: (usize, usize),
        clear: (usize, usize),
    ) {
        self.stretch_into::<false, false>(small, &patch.stretch, dst, used, clear);
    }

    /// [`ShadowBuilder::nine_rects_into`] with every value v inside the used rect stored as `255 − v` (the centre 255):
    /// `nine_rects_into` followed by [`super::invert_used`], in one pass. Same allocation and panics.
    pub fn nine_rects_inverted_into(
        &mut self,
        small: A8,
        patch: &RectsPatch,
        dst: &mut A8Mut,
        used: (usize, usize),
        clear: (usize, usize),
    ) {
        self.stretch_into::<true, false>(small, &patch.stretch, dst, used, clear);
    }
}

/// [`write_nine_as`] without the centre, for the tests: the write of [`ShadowBuilder::nine_rects_into`] from an already
/// blurred small frame.
#[cfg(test)]
fn write_rects(
    dst: &mut A8Mut,
    mask: A8,
    stretch: &Stretch,
    used: (usize, usize),
    clear: (usize, usize),
    inverted: bool,
) {
    use super::nine::write_nine_as;
    if inverted {
        write_nine_as::<true, false>(dst, mask, stretch, used, clear);
    } else {
        write_nine_as::<false, false>(dst, mask, stretch, used, clear);
    }
}

#[cfg(test)]
#[path = "rects_tests.rs"]
mod tests;
