// Copyright 2006 The Android Open Source Project
// Copyright 2025 Google LLC
// Copyright 2026, Serhii Yaremych (desugar-64) and the Obvid project contributors (Rust port)
// SPDX-License-Identifier: BSD-3-Clause
// See LICENSES/Skia-BSD-3-Clause.txt

//! Host tests for `shadow::rects` (declared in rects.rs as `mod tests`). The plan is checked against a literal port of
//! Android 17's `filterRectsToNine` (two rects) written below, on random frames; the centre-less write against a
//! literal port of `draw_nine_clipped` (`fillCenter = false`) blitting into a zeroed bitmap; the whole route against
//! the scalar blur port `skia::blur_a8` plus that port.

use super::*;
use crate::skia::{GaussFilter, PlanGauss};
use crate::test_support::{Rng, allocations};

/// Byte the tests put outside every rect that must stay untouched.
const SENTINEL: u8 = 0xA5;

// ---- port of filterRectsToNine (Android 17 SkBlurMaskFilterImpl.cpp:438–566) ------------------------------------
// https://android.googlesource.com/platform/external/skia/+/508606366e71a3daa933a4e08bc49b84e387ec18/src/core/SkBlurMaskFilterImpl.cpp#438

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
struct SkIRect {
    l: i32,
    t: i32,
    r: i32,
    b: i32,
}

impl SkIRect {
    fn width(&self) -> i32 {
        self.r.wrapping_sub(self.l)
    }

    fn height(&self) -> i32 {
        self.b.wrapping_sub(self.t)
    }

    fn make_outset(&self, dx: i32, dy: i32) -> SkIRect {
        SkIRect { l: self.l - dx, t: self.t - dy, r: self.r + dx, b: self.b + dy }
    }
}

#[derive(Clone, Copy, Debug, PartialEq)]
struct SkRect {
    l: f32,
    t: f32,
    r: f32,
    b: f32,
}

impl SkRect {
    fn ltrb(a: [f32; 4]) -> SkRect {
        SkRect { l: a[0], t: a[1], r: a[2], b: a[3] }
    }

    fn width(&self) -> f32 {
        self.r - self.l
    }

    fn height(&self) -> f32 {
        self.b - self.t
    }

    /// `SkRect::roundOut(SkIRect*)`.
    fn round_out(&self) -> SkIRect {
        SkIRect {
            l: sk_float_floor2int(self.l),
            t: sk_float_floor2int(self.t),
            r: sk_float_ceil2int(self.r),
            b: sk_float_ceil2int(self.b),
        }
    }

    /// `SkRect::roundIn(SkIRect*)`.
    fn round_in(&self) -> SkIRect {
        SkIRect {
            l: sk_float_ceil2int(self.l),
            t: sk_float_ceil2int(self.t),
            r: sk_float_floor2int(self.r),
            b: sk_float_floor2int(self.b),
        }
    }

    fn bits(&self) -> [u32; 4] {
        [self.l.to_bits(), self.t.to_bits(), self.r.to_bits(), self.b.to_bits()]
    }
}

/// `sk_float_saturate2int`
/// ([Android 17 SkFloatingPoint.h:89](https://android.googlesource.com/platform/external/skia/+/508606366e71a3daa933a4e08bc49b84e387ec18/include/private/base/SkFloatingPoint.h#89)).
fn sk_float_saturate2int(x: f32) -> i32 {
    let max = 2147483520i32 as f32;
    let min = -2147483520i32 as f32;
    let x = if x < max { x } else { max };
    let x = if x > min { x } else { min };
    x as i32
}

fn sk_float_floor2int(x: f32) -> i32 {
    sk_float_saturate2int(x.floor())
}

fn sk_float_ceil2int(x: f32) -> i32 {
    sk_float_saturate2int(x.ceil())
}

/// `rect_exceeds`
/// ([Android 17 SkBlurMaskFilterImpl.cpp:201](https://android.googlesource.com/platform/external/skia/+/508606366e71a3daa933a4e08bc49b84e387ec18/src/core/SkBlurMaskFilterImpl.cpp#201)).
fn port_rect_exceeds(r: &SkRect, v: f32) -> bool {
    r.l < -v || r.t < -v || r.r > v || r.b > v || r.width() > v || r.height() > v
}

/// `SkBlurMask::BoxBlur` → `SkMaskBlurFilter::blur` on a mask with bounds only (`fImage == nullptr`): false when the
/// filter has no blur (kNormal), else the destination bounds (`PrepareDestination`: the source outset by the border)
/// and the border (`small_blur`'s `SkGaussFilter` radius below σ 2, else `PlanGauss::border`).
fn port_box_blur_bounds(src: SkIRect, sigma: f32) -> Option<SkIRect> {
    // SkMaskBlurFilter{sigma, sigma}: each pinned to [0, 135] in double.
    let (sigma_w, sigma_h) = ((sigma as f64).clamp(0.0, 135.0), (sigma as f64).clamp(0.0, 135.0));
    // hasNoBlur: fSigmaW < kNoWindowSigma && fSigmaH <= kNoWindowSigma, kNoWindowSigma = 1/3.
    if sigma_w < 1.0 / 3.0 && sigma_h <= 1.0 / 3.0 {
        return None;
    }
    let border = if sigma_w < 2.0 { GaussFilter::new(sigma_w).radius() } else { PlanGauss::new(sigma_w).border } as i32;
    Some(src.make_outset(border, border))
}

/// `computeXformedSigma` under a translate: `BlurMaskFilter`'s `0.57735·r + 0.5`, `mapRadius` keeps it, capped at 128.
fn port_sigma(radius_px: f32) -> f32 {
    let s = if radius_px > 0.0 { 0.57735f32 * radius_px + 0.5 } else { 0.0 };
    if 128.0 < s { 128.0 } else { s }
}

/// `filterRectsToNine`'s result for two rects.
#[derive(Debug)]
enum Port {
    False,
    Unimplemented,
    True(PortNine),
}

#[derive(Debug)]
struct PortNine {
    /// `dstM.fBounds` (the patch's outer rect) and `center`.
    outer: SkIRect,
    center: (i32, i32),
    dx: f32,
    dy: f32,
    small: [SkRect; 2],
    /// `srcM.fBounds` after `draw_rects_into_mask` (`roundOut(smallR0)`) and the blurred mask's bounds.
    drawn: SkIRect,
    blurred: SkIRect,
}

/// `filterRectsToNine` with `rects.size() == 2`, kNormal, as written.
fn port_filter_rects_to_nine(rects: [SkRect; 2], sigma: f32) -> Port {
    if port_rect_exceeds(&rects[0], 32767.0) {
        return Port::Unimplemented;
    }
    let src_m = rects[0].round_out();
    // filterMask(&dstM, srcM, …)
    let Some(dst_m) = port_box_blur_bounds(src_m, sigma) else { return Port::False };
    let mut small_w = dst_m.width() - src_m.width() + 2;
    let mut small_h = dst_m.height() - src_m.height() + 2;
    let inner_ir = rects[1].round_in();
    let center = (small_w + (inner_ir.l - src_m.l), small_h + (inner_ir.t - src_m.t));
    small_w += 1;
    small_h += 1;
    let dx = (inner_ir.width() - small_w) as f32;
    let dy = (inner_ir.height() - small_h) as f32;
    if dx < 0.0 || dy < 0.0 {
        return Port::Unimplemented;
    }
    let r0 = SkRect { l: rects[0].l, t: rects[0].t, r: rects[0].r - dx, b: rects[0].b - dy };
    if r0.width() < 2.0 || r0.height() < 2.0 {
        return Port::Unimplemented;
    }
    let r1 = SkRect { l: rects[1].l, t: rects[1].t, r: rects[1].r - dx, b: rects[1].b - dy };
    // draw_rects_into_mask → prepare_to_draw_into_mask: bounds = roundOut(smallR0); filterMask on it.
    let drawn = r0.round_out();
    let Some(blurred) = port_box_blur_bounds(drawn, sigma) else { return Port::False };
    Port::True(PortNine { outer: dst_m, center, dx, dy, small: [r0, r1], drawn, blurred })
}

/// The plan's answer against the port's for one frame; returns the port's outcome as 0 (False), 1 (Unimplemented) or
/// 2 (True), 3 for True with a centre one wider or higher than `dx + 1` / `dy + 1`.
fn check_against_port(outer: [f32; 4], inner: [f32; 4], radius: f32) -> usize {
    let got = plan_rects(outer, inner, radius);
    let port = port_filter_rects_to_nine([SkRect::ltrb(outer), SkRect::ltrb(inner)], port_sigma(radius));
    let ctx = || format!("outer {outer:?} inner {inner:?} radius {radius}");
    match port {
        Port::False => {
            assert_eq!(got, RectsRoute::Skia, "{}", ctx());
            0
        }
        Port::Unimplemented => {
            assert_eq!(got, RectsRoute::Mask, "{}", ctx());
            1
        }
        Port::True(n) => {
            let RectsRoute::NinePatch(p) = got else { panic!("{}: {got:?}, port {n:?}", ctx()) };
            let s = &p.stretch;
            assert_eq!((p.dx as f32, p.dy as f32), (n.dx, n.dy), "{}", ctx());
            assert_eq!(SkRect::ltrb(p.small_outer).bits(), n.small[0].bits(), "{}", ctx());
            assert_eq!(SkRect::ltrb(p.small_inner).bits(), n.small[1].bits(), "{}", ctx());
            assert_eq!(
                (p.mask_l, p.mask_t, s.small_w as i32, s.small_h as i32),
                (n.drawn.l, n.drawn.t, n.drawn.width(), n.drawn.height()),
                "{}",
                ctx()
            );
            assert_eq!((s.mask_w() as i32, s.mask_h() as i32), (n.blurred.width(), n.blurred.height()), "{}", ctx());
            assert_eq!(
                (s.outer_l, s.outer_t, s.outer_r, s.outer_b),
                (n.outer.l as isize, n.outer.t as isize, n.outer.r as isize, n.outer.b as isize),
                "{}",
                ctx()
            );
            assert_eq!((s.center_x as i32, s.center_y as i32), n.center, "{}", ctx());
            assert_eq!(s.sigma, port_sigma(radius) as f64, "{}", ctx());
            // The centre is dx + 1 wide (dy + 1 high), or one more where `o.r − dx` rounds away a fraction of o.r and
            // the small mask loses a column (row).
            let inner_w =
                (s.outer_r + (s.center_x as isize + 1 - s.mask_w() as isize)) - (s.outer_l + s.center_x as isize);
            let inner_h =
                (s.outer_b + (s.center_y as isize + 1 - s.mask_h() as isize)) - (s.outer_t + s.center_y as isize);
            let (ex, ey) = (inner_w - (p.dx as isize + 1), inner_h - (p.dy as isize + 1));
            assert!((0..=1).contains(&ex) && (0..=1).contains(&ey), "{}: centre {inner_w} × {inner_h}", ctx());
            if ex + ey > 0 {
                return 3;
            }
            2
        }
    }
}

// ---- random frames -----------------------------------------------------------------------------------------------

/// A fractional part: none, halves, quarters, 0.37, near 0 and 1, or any 1/65536.
fn frac(rng: &mut Rng) -> f32 {
    match rng.below(9) {
        0 => 0.0,
        1 => 0.5,
        2 => 0.25,
        3 => 0.75,
        4 => 0.37,
        5 => 0.001,
        6 => 0.999,
        _ => rng.below(65536) as f32 / 65536.0,
    }
}

/// A Compose-like blur radius in px: tiny, small blur (σ < 2), PlanGauss, past the 128 sigma cap.
fn radius(rng: &mut Rng) -> f32 {
    let list = [0.001f32, 0.05, 0.3, 1.0, 2.0, 2.625, 3.4, 5.25, 10.5, 21.0, 42.0, 63.0, 100.0, 168.0, 300.0];
    if rng.below(4) == 0 { rng.below(20000) as f32 / 100.0 + 0.01 } else { list[rng.below(list.len() as u32) as usize] }
}

/// The border the plan uses for `radius` (`blur_geometry`), to aim holes at the `2m + 3` threshold.
fn border(radius: f32) -> i32 {
    blur::blur_geometry(0, 0, skia::mask_blur_sigma_from_radius(radius)).border_x as i32
}

/// A frame whose hole is `2m + 3 + k` whole pixels after `roundIn` on the axis `axis` (0 = x, 1 = y; the other is
/// wide), k in −3..=3, with fractional edges on both rects and margins (0 = touching) on each side.
fn threshold_frame(rng: &mut Rng, radius: f32, axis: usize) -> ([f32; 4], [f32; 4]) {
    let m = border(radius);
    let k = rng.range(-3, 3) as i32;
    let mut outer = [0f32; 4];
    let mut inner = [0f32; 4];
    for a in 0..2 {
        let hole = if a == axis { (2 * m + 3 + k).max(0) } else { 2 * m + 3 + rng.range(0, 400) as i32 };
        let l = rng.range(-300, 600) as f32 + frac(rng);
        let margin_l = if rng.below(4) == 0 { 0.0 } else { rng.range(0, 80) as f32 + frac(rng) };
        let margin_r = if rng.below(4) == 0 { 0.0 } else { rng.range(0, 80) as f32 + frac(rng) };
        // roundIn of [il, ir] is `hole` wide: il = ceil-aligned start minus a fraction, ir = start + hole plus one.
        let start = (l + margin_l).ceil();
        let il = if rng.below(2) == 0 { start } else { start - frac(rng).min(0.999) };
        let il = il.max(l + margin_l).max(l);
        let in_l = il.ceil();
        let ir = in_l + hole as f32 + if rng.below(2) == 0 { 0.0 } else { frac(rng).min(0.999) };
        let r = ir + margin_r;
        outer[a] = l;
        outer[a + 2] = r;
        inner[a] = il;
        inner[a + 2] = ir;
    }
    (outer, inner)
}

/// A frame anywhere: outer at fractional edges, width and height 1–900, inner with random margins (some 0).
fn general_frame(rng: &mut Rng) -> ([f32; 4], [f32; 4]) {
    let mut outer = [0f32; 4];
    let mut inner = [0f32; 4];
    for a in 0..2 {
        let l = rng.range(-400, 700) as f32 + frac(rng);
        let w = rng.range(1, 900) as f32 + frac(rng);
        let r = l + w;
        let ml = if rng.below(5) == 0 { 0.0 } else { (rng.below(1000) as f32 / 1000.0) * w * 0.5 };
        let mr = if rng.below(5) == 0 { 0.0 } else { (rng.below(1000) as f32 / 1000.0) * w * 0.5 };
        let (il, ir) = (l + ml, r - mr);
        let (il, ir) = if il < ir { (il, ir) } else { (l, r) };
        outer[a] = l;
        outer[a + 2] = r;
        inner[a] = il.max(l);
        inner[a + 2] = ir.min(r);
    }
    (outer, inner)
}

/// A frame at or across Skia's 32767 limit: an edge around ±32767 or a width around 32767.
fn huge_frame(rng: &mut Rng) -> ([f32; 4], [f32; 4]) {
    let near = [-1.0f32, -0.5, -0.001, 0.0, 0.001, 0.5, 1.0, 3.0];
    let d = near[rng.below(near.len() as u32) as usize];
    let (l, r) = match rng.below(3) {
        0 => {
            let r = 32767.0 + d;
            (r - rng.range(10, 2000) as f32 - frac(rng), r)
        }
        1 => {
            let l = -32767.0 + d;
            (l, l + rng.range(10, 2000) as f32 + frac(rng))
        }
        _ => {
            let l = -16000.0 - frac(rng);
            (l, l + 32767.0 + d)
        }
    };
    let (t, b) = (-50.0 + frac(rng), 700.0 + frac(rng));
    let mx = (r - l) * 0.25;
    let outer = if rng.below(2) == 0 { [l, t, r, b] } else { [t, l, b, r] };
    let inner =
        if outer[0] == l { [l + mx, t + 100.0, r - mx, b - 100.0] } else { [t + 100.0, l + mx, b - 100.0, r - mx] };
    (outer, inner)
}

#[test]
fn plan_matches_port_on_random_frames() {
    let mut rng = Rng(0xF2A3_E5C1);
    // Port outcomes: False, Unimplemented, True; then the unimplemented reasons seen.
    let mut outcomes = [0usize; 3];
    let mut wider_centre = 0;
    let (mut threshold_below, mut threshold_at, mut threshold_above) = (0, 0, 0);
    let (mut huge_unimpl, mut huge_nine) = (0, 0);
    let mut touching = 0;
    let mut small_blur_nine = 0;
    let cases = 40_000;
    for i in 0..cases {
        let r = radius(&mut rng);
        let (outer, inner) = match i % 4 {
            0 => general_frame(&mut rng),
            1 | 2 => {
                let axis = rng.below(2) as usize;
                threshold_frame(&mut rng, r, axis)
            }
            _ => {
                if rng.below(3) == 0 {
                    huge_frame(&mut rng)
                } else {
                    general_frame(&mut rng)
                }
            }
        };
        if !contains(outer, inner) {
            assert_eq!(plan_rects(outer, inner, r), RectsRoute::Skia);
            continue;
        }
        let o = match check_against_port(outer, inner, r) {
            3 => {
                wider_centre += 1;
                2
            }
            o => o,
        };
        outcomes[o] += 1;
        let m = border(r);
        let hole_w = SkRect::ltrb(inner).round_in().width() - (2 * m + 3);
        let hole_h = SkRect::ltrb(inner).round_in().height() - (2 * m + 3);
        let hole = hole_w.min(hole_h);
        if !port_rect_exceeds(&SkRect::ltrb(outer), 32767.0) {
            match hole {
                -1 => threshold_below += 1,
                0 => threshold_at += 1,
                1 => threshold_above += 1,
                _ => {}
            }
        }
        let far =
            outer.iter().any(|v| v.abs() > 30000.0) || outer[2] - outer[0] > 30000.0 || outer[3] - outer[1] > 30000.0;
        if far {
            if o == 1 && port_rect_exceeds(&SkRect::ltrb(outer), 32767.0) {
                huge_unimpl += 1;
            } else if o == 2 {
                huge_nine += 1;
            }
        }
        touching += (o == 2
            && (outer[0] == inner[0] || outer[2] == inner[2] || outer[1] == inner[1] || outer[3] == inner[3]))
            as usize;
        small_blur_nine += (o == 2 && port_sigma(r) < 2.0) as usize;
    }
    println!(
        "outcomes (false, unimplemented, nine) {outcomes:?}; holes at 2m+3 −1 / 0 / +1: {threshold_below} / {threshold_at} / \
         {threshold_above}; near 32767: unimplemented {huge_unimpl}, nine {huge_nine}; touching nine {touching}; small-blur nine {small_blur_nine}; centre wider than dx + 1 {wider_centre}"
    );
    assert_eq!(outcomes[0], 0, "radius > 0 always blurs");
    assert!(outcomes[1] > 3000 && outcomes[2] > 3000, "{outcomes:?}");
    assert!(
        threshold_below > 300 && threshold_at > 300 && threshold_above > 300,
        "{threshold_below} {threshold_at} {threshold_above}"
    );
    assert!(huge_unimpl > 300 && huge_nine > 50, "{huge_unimpl} {huge_nine}");
    assert!(touching > 300 && small_blur_nine > 300, "{touching} {small_blur_nine}");
}

/// Radius ≤ 0 or NaN (no mask filter), non-finite edges, rects that are not nested or are empty: not a frame this
/// route models (Skia). Tiny radii still blur (σ ≥ 0.5).
#[test]
fn plan_routes_to_skia() {
    let outer = [10.5f32, 20.25, 400.0, 300.75];
    let inner = [100.0f32, 110.0, 300.0, 200.0];
    assert!(matches!(plan_rects(outer, inner, 2.625), RectsRoute::NinePatch(_)));
    for r in [0.0f32, -1.0, f32::NAN] {
        assert_eq!(plan_rects(outer, inner, r), RectsRoute::Skia, "radius {r}");
    }
    assert!(matches!(plan_rects(outer, inner, 1e-6), RectsRoute::NinePatch(_)));
    for v in [f32::NAN, f32::INFINITY, f32::NEG_INFINITY] {
        for k in 0..8 {
            let (mut o, mut i) = (outer, inner);
            if k < 4 {
                o[k] = v
            } else {
                i[k - 4] = v
            }
            assert_eq!(plan_rects(o, i, 10.5), RectsRoute::Skia, "{o:?} {i:?}");
        }
    }
    // Not nested: swapped, overlapping, disjoint, empty inner (zero width), inverted outer.
    assert_eq!(plan_rects(inner, outer, 10.5), RectsRoute::Skia);
    assert_eq!(plan_rects(outer, [5.0, 110.0, 300.0, 200.0], 10.5), RectsRoute::Skia);
    assert_eq!(plan_rects([0.0, 0.0, 10.0, 10.0], [20.0, 0.0, 30.0, 10.0], 10.5), RectsRoute::Skia);
    assert_eq!(plan_rects(outer, [100.0, 110.0, 100.0, 200.0], 10.5), RectsRoute::Skia);
    assert_eq!(plan_rects([400.0, 20.25, 10.5, 300.75], inner, 10.5), RectsRoute::Skia);
    // Equal rects are nested for SkRect::contains; the hole is the whole rect.
    assert!(matches!(plan_rects(outer, outer, 2.625), RectsRoute::NinePatch(_)));
}

/// The 200 dp frame of the tests at density 2.625 (525 px, hole inset by 131.5 px), moved by the radius: nine-patch
/// at r 24 dp, unimplemented at 64 dp (hole 261 px < 2m + 3).
#[test]
fn plan_compose_frame() {
    let t = 63.0f32;
    let outer = [t, t, 525.0 + t, 525.0 + t];
    let inner = [131.5 + t, 131.5 + t, 393.5 + t, 393.5 + t];
    let RectsRoute::NinePatch(p) = plan_rects(outer, inner, 63.0) else { panic!() };
    let m = border(63.0);
    // roundIn(inner) = (195, 195, 456, 456): 261 px.
    assert_eq!((p.dx, p.dy), (261 - (2 * m + 3), 261 - (2 * m + 3)));
    assert_eq!((p.mask_l, p.mask_t), (63, 63));
    assert_eq!(p.stretch.small_w as i32, 525 - p.dx);
    assert_eq!(p.small_inner, [194.5, 194.5, 456.5 - p.dx as f32, 456.5 - p.dy as f32]);
    assert_eq!(plan_rects(outer, inner, 168.0), RectsRoute::Mask);
}

#[test]
fn packed_route() {
    assert_eq!(RectsRoute::Skia.packed(), 0);
    assert_eq!(RectsRoute::Mask.packed(), 1);
    let mut rng = Rng(5);
    for _ in 0..2000 {
        let axis = rng.below(2) as usize;
        let (outer, inner) = threshold_frame(&mut rng, 10.5, axis);
        if let RectsRoute::NinePatch(p) = plan_rects(outer, inner, 10.5) {
            let v = RectsRoute::NinePatch(p).packed();
            assert!(v & RECTS_NINE_TAG != 0 && v > 1);
            assert_eq!(((v & 0xFFFF_FFFF) as i32, ((v >> 32) & 0x3FFF_FFFF) as i32), (p.dx, p.dy));
        }
    }
    let RectsRoute::NinePatch(p) = plan_rects([0.0, 0.0, 32767.0, 32767.0], [1.0, 1.0, 32766.0, 32766.0], 1.0) else {
        panic!()
    };
    let v = RectsRoute::NinePatch(p).packed();
    assert_eq!(((v & 0xFFFF_FFFF) as i32, ((v >> 32) & 0x3FFF_FFFF) as i32, v >> 62), (p.dx, p.dy, 1));
}

// ---- port of draw_nine_clipped (Skia main SkMaskFilterBase.cpp:88–193) ------------------------------------------
// https://github.com/google/skia/blob/81ee7327e81c23b8bcf815ea6c353457cf706716/src/core/SkMaskFilterBase.cpp#L88-L193

/// A zeroed `w × h` bitmap with Skia's opaque black A8 blits: coverage c onto d is `c + d·(255 − c)/255`; every pixel
/// is blitted at most once (checked), so the bitmap holds the coverage.
struct Blits {
    px: Vec<u8>,
    hit: Vec<bool>,
    w: i32,
    h: i32,
}

impl Blits {
    fn blit(&mut self, x: i32, y: i32, c: u8) {
        assert!(x >= 0 && y >= 0 && x < self.w && y < self.h, "blit outside the clip ({x}, {y})");
        let i = (y * self.w + x) as usize;
        assert!(!self.hit[i], "pixel ({x}, {y}) blitted twice");
        self.hit[i] = true;
        self.px[i] = c;
    }
}

fn intersect(a: SkIRect, b: SkIRect) -> Option<SkIRect> {
    let r = SkIRect { l: a.l.max(b.l), t: a.t.max(b.t), r: a.r.min(b.r), b: a.b.min(b.b) };
    if r.l < r.r && r.t < r.b { Some(r) } else { None }
}

/// `draw_nine_clipped(mask, outerR, center, fillCenter, clipR)` for a mask `mw × mh` at bounds (0, 0), tightly packed,
/// into a zeroed `clip.r × clip.b` bitmap whose clip is (0, 0, w, h).
#[allow(clippy::too_many_arguments)]
fn port_draw_nine(
    mask: &[u8],
    mw: i32,
    mh: i32,
    outer: SkIRect,
    center: (i32, i32),
    fill_center: bool,
    w: i32,
    h: i32,
) -> Vec<u8> {
    let clip = SkIRect { l: 0, t: 0, r: w, b: h };
    let mut out = Blits { px: vec![0; (w * h) as usize], hit: vec![false; (w * h) as usize], w, h };
    let at = |x: i32, y: i32| mask[(y * mw + x) as usize];
    let (cx, cy) = center;
    let bounds = SkIRect { l: 0, t: 0, r: mw, b: mh };
    // A corner: the mask subset `sub` placed with its top-left at (nx, ny), clipped.
    let corner = |out: &mut Blits, sub: SkIRect, nx: i32, ny: i32| {
        if sub.width() > 0 && sub.height() > 0 {
            let dev = SkIRect { l: nx, t: ny, r: nx + sub.width(), b: ny + sub.height() };
            if let Some(r) = intersect(dev, clip) {
                for y in r.t..r.b {
                    for x in r.l..r.r {
                        out.blit(x, y, at(sub.l + x - nx, sub.t + y - ny));
                    }
                }
            }
        }
    };
    corner(&mut out, SkIRect { r: cx, b: cy, ..bounds }, outer.l, outer.t);
    let tr = SkIRect { l: cx + 1, b: cy, ..bounds };
    corner(&mut out, tr, outer.r - tr.width(), outer.t);
    let bl = SkIRect { r: cx, t: cy + 1, ..bounds };
    corner(&mut out, bl, outer.l, outer.b - bl.height());
    let br = SkIRect { l: cx + 1, t: cy + 1, ..bounds };
    corner(&mut out, br, outer.r - br.width(), outer.b - br.height());

    let inner = SkIRect {
        l: outer.l + cx - bounds.l,
        t: outer.t + cy - bounds.t,
        r: outer.r + (cx + 1 - bounds.r),
        b: outer.b + (cy + 1 - bounds.b),
    };
    if fill_center && let Some(r) = intersect(inner, clip) {
        for y in r.t..r.b {
            for x in r.l..r.r {
                out.blit(x, y, 255);
            }
        }
    }
    // top
    if let Some(r) = intersect(SkIRect { l: inner.l, t: outer.t, r: inner.r, b: inner.t }, clip) {
        let start_y = (r.t - outer.t).max(0);
        for y in start_y..start_y + r.height() {
            let a = at(cx, bounds.t + y);
            for x in r.l..r.r {
                out.blit(x, outer.t + y, a);
            }
        }
    }
    // bottom
    if let Some(r) = intersect(SkIRect { l: inner.l, t: inner.b, r: inner.r, b: outer.b }, clip) {
        let start_y = outer.b - r.b;
        for y in start_y..start_y + r.height() {
            let a = at(cx, bounds.b - y - 1);
            for x in r.l..r.r {
                out.blit(x, outer.b - y - 1, a);
            }
        }
    }
    // left: a mask row repeated (row bytes 0), its first byte at column bounds.l + r.l − outer.l.
    if let Some(r) = intersect(SkIRect { l: outer.l, t: inner.t, r: inner.l, b: inner.b }, clip) {
        let col0 = bounds.l + r.l - outer.l;
        for y in r.t..r.b {
            for x in r.l..r.r {
                out.blit(x, y, at(col0 + (x - r.l), bounds.t + cy));
            }
        }
    }
    // right
    if let Some(r) = intersect(SkIRect { l: inner.r, t: inner.t, r: outer.r, b: inner.b }, clip) {
        let col0 = bounds.r - outer.r + r.l;
        for y in r.t..r.b {
            for x in r.l..r.r {
                out.blit(x, y, at(col0 + (x - r.l), bounds.t + cy));
            }
        }
    }
    out.px
}

/// The stretch `s`'s `draw_nine` without the centre, into Compose's zeroed `used` bitmap, then as the writers leave
/// `dst` (stride `stride`): the used rect (inverted: `255 − v`), 0 elsewhere in the clear rect, the rest untouched.
#[allow(clippy::too_many_arguments)]
fn reference_write(
    dst: &mut [u8],
    stride: usize,
    mask: &[u8],
    s: &Stretch,
    used: (usize, usize),
    clear: (usize, usize),
    inverted: bool,
) {
    let outer = SkIRect { l: s.outer_l as i32, t: s.outer_t as i32, r: s.outer_r as i32, b: s.outer_b as i32 };
    let b = port_draw_nine(
        mask,
        s.mask_w() as i32,
        s.mask_h() as i32,
        outer,
        (s.center_x as i32, s.center_y as i32),
        false,
        used.0 as i32,
        used.1 as i32,
    );
    let (cw, ch) = (clear.0.max(used.0), clear.1.max(used.1));
    for y in 0..ch {
        for x in 0..cw {
            dst[y * stride + x] = if x < used.0 && y < used.1 {
                let v = b[y * used.0 + x];
                if inverted { 255 - v } else { v }
            } else {
                0
            };
        }
    }
}

fn random_bytes(n: usize, rng: &mut Rng) -> Vec<u8> {
    (0..n).map(|_| rng.next() as u8).collect()
}

/// A consistent stretch: margin `m`, small mask `sw × sh`, the centre anywhere inside the blurred mask, the outer rect
/// at (`ol`, `ot`) and `extra_w` / `extra_h` (≥ 0) wider / taller than the blurred mask (centre `extra + 1` wide).
#[allow(clippy::too_many_arguments)]
fn stretch(
    m: usize,
    sw: usize,
    sh: usize,
    cx: usize,
    cy: usize,
    ol: isize,
    ot: isize,
    extra_w: usize,
    extra_h: usize,
) -> Stretch {
    let (mw, mh) = (sw + 2 * m, sh + 2 * m);
    let s = Stretch {
        small_w: sw,
        small_h: sh,
        sigma: 0.0,
        border: m,
        outer_l: ol,
        outer_t: ot,
        outer_r: ol + (mw + extra_w) as isize,
        outer_b: ot + (mh + extra_h) as isize,
        center_x: cx.min(mw - 1),
        center_y: cy.min(mh - 1),
    };
    assert!(s.is_consistent());
    s
}

/// The centre-less write, plain and inverted, against the port of `draw_nine_clipped` on random geometry: every
/// clipping regime, stale pixels, mask and bitmap strides; bytes past the clear rect untouched.
#[test]
fn write_without_centre_matches_draw_nine_port() {
    let mut rng = Rng(0x0C3E_7A11);
    let (cap_w, cap_h, stride) = (110usize, 96usize, 123usize);
    // Clipping regimes: outer crossing left / top / right / bottom, missing the used rect, used < 16 wide; the
    // centre column crossing the used rect.
    let mut seen = [0usize; 7];
    for case in 0..4000 {
        let m = rng.range(0, 12) as usize;
        let (sw, sh) = (rng.range(1, 30) as usize, rng.range(1, 30) as usize);
        let (mw, mh) = (sw + 2 * m, sh + 2 * m);
        let s = stretch(
            m,
            sw,
            sh,
            rng.below(mw as u32) as usize,
            rng.below(mh as u32) as usize,
            rng.range(-60, 70),
            rng.range(-60, 70),
            rng.range(0, 40) as usize,
            rng.range(0, 40) as usize,
        );
        let inverted = case % 2 == 1;
        let mask_stride = mw + rng.below(4) as usize;
        let mut mask = random_bytes(mask_stride * mh, &mut rng);
        let tight: Vec<u8> = (0..mh).flat_map(|y| mask[y * mask_stride..y * mask_stride + mw].to_vec()).collect();
        for y in 0..mh {
            mask[y * mask_stride + mw..(y + 1) * mask_stride].fill(SENTINEL);
        }
        let used = (rng.range(1, cap_w as isize) as usize, rng.range(1, cap_h as isize) as usize);
        let clear = (rng.range(0, cap_w as isize) as usize, rng.range(0, cap_h as isize) as usize);
        let (uw, uh) = (used.0 as isize, used.1 as isize);
        seen[0] += (s.outer_l < 0) as usize;
        seen[1] += (s.outer_t < 0) as usize;
        seen[2] += (s.outer_r > uw) as usize;
        seen[3] += (s.outer_b > uh) as usize;
        seen[4] += (s.outer_l >= uw || s.outer_t >= uh) as usize;
        seen[5] += (used.0 < 16) as usize;
        let inner_l = s.outer_l + s.center_x as isize;
        seen[6] += (inner_l >= 0 && inner_l < uw && s.outer_t < uh) as usize;

        let mut base = random_bytes(stride * cap_h, &mut rng);
        for y in 0..cap_h {
            base[y * stride + cap_w..(y + 1) * stride].fill(SENTINEL);
        }
        let mut want = base.clone();
        reference_write(&mut want, stride, &tight, &s, used, clear, inverted);
        let mut got = base.clone();
        let mut dst = A8Mut { pixels: &mut got, width: cap_w, height: cap_h, stride };
        write_rects(
            &mut dst,
            A8 { pixels: &mask, width: mw, height: mh, stride: mask_stride },
            &s,
            used,
            clear,
            inverted,
        );
        assert_eq!(got, want, "{s:?} used {used:?} clear {clear:?} inverted {inverted}");
    }
    assert!(seen.iter().all(|&n| n > 100), "clipping regimes not all exercised: {seen:?}");
}

/// The smallest centre (1 × 1, dx = dy = 0) with the outer rect exactly the used rect, and a used rect inside the
/// centre only: all 0, or all 255 inverted.
#[test]
fn write_without_centre_minimal_centre_and_inside_centre() {
    let mut rng = Rng(79);
    let s = stretch(3, 9, 7, 8, 6, 0, 0, 0, 0);
    let (mw, mh) = (s.mask_w(), s.mask_h());
    let mask = random_bytes(mw * mh, &mut rng);
    let used = (s.outer_r as usize, s.outer_b as usize);
    for inverted in [false, true] {
        let mut got = vec![9u8; used.0 * used.1];
        let mut dst = A8Mut { pixels: &mut got, width: used.0, height: used.1, stride: used.0 };
        write_rects(&mut dst, A8 { pixels: &mask, width: mw, height: mh, stride: mw }, &s, used, used, inverted);
        let mut want = vec![9u8; used.0 * used.1];
        reference_write(&mut want, used.0, &mask, &s, used, used, inverted);
        assert_eq!(got, want);
        let centre = got[s.center_y * used.0 + s.center_x];
        assert_eq!(centre, if inverted { 255 } else { 0 });
        // Its neighbours on the centre row are the mask's (a 1-px centre: the left part ends at cx).
        let v = mask[s.center_y * mw + s.center_x + 1];
        assert_eq!(got[s.center_y * used.0 + s.center_x + 1], if inverted { 255 - v } else { v });
    }
    let inside = stretch(4, 6, 6, 7, 7, -40, -40, 100, 100);
    let mask = random_bytes(inside.mask_w() * inside.mask_h(), &mut rng);
    for inverted in [false, true] {
        let mut px = vec![1u8; 12 * 12];
        let mut dst = A8Mut { pixels: &mut px, width: 12, height: 12, stride: 12 };
        let m = A8 { pixels: &mask, width: inside.mask_w(), height: inside.mask_h(), stride: inside.mask_w() };
        write_rects(&mut dst, m, &inside, (12, 12), (0, 0), inverted);
        assert!(px.iter().all(|&b| b == if inverted { 255 } else { 0 }));
    }
}

// ---- whole route -----------------------------------------------------------------------------------------------

/// The small frame (`smallR0` minus `smallR1`, even-odd) as coverage on a 4 × 4 grid per pixel, in the
/// `small_w × small_h` window at the mask origin, stride `stride` (padding = SENTINEL): a stand-in for what Android's
/// `Canvas` draws (any bytes would do; this one looks like the real input).
fn small_frame(p: &RectsPatch, stride: usize) -> Vec<u8> {
    let (w, h) = (p.stretch.small_w, p.stretch.small_h);
    let mut v = vec![SENTINEL; stride * h];
    let inside =
        |r: [f32; 4], x: f64, y: f64| x >= r[0] as f64 && x < r[2] as f64 && y >= r[1] as f64 && y < r[3] as f64;
    for y in 0..h {
        for x in 0..w {
            let mut n = 0;
            for s in 0..16 {
                let px = (p.mask_l as f64) + x as f64 + ((s % 4) as f64 + 0.5) / 4.0;
                let py = (p.mask_t as f64) + y as f64 + ((s / 4) as f64 + 0.5) / 4.0;
                n += (inside(p.small_outer, px, py) != inside(p.small_inner, px, py)) as u32;
            }
            v[y * stride + x] = (n * 255 / 16) as u8;
        }
    }
    v
}

/// Frames as Compose moves them (drop: by the radius into a `size + 2r` bitmap; inner: by an offset into a
/// `size + 2·ceil(r)` bitmap, crossing or missing its edges), through [`ShadowBuilder::nine_rects_into`] and its
/// inverted twin on one builder and a stale plane (each clear rect the previous used rect), against the scalar blur
/// port and the `draw_nine_clipped` port.
#[test]
fn nine_rects_matches_scalar_blur_and_draw_nine_port() {
    let mut rng = Rng(0x5EED_F2A3);
    let mut builder = ShadowBuilder::new();
    let (cap_w, cap_h) = (700usize, 700usize);
    let stride = cap_w + 3;
    let mut px = random_bytes(stride * cap_h, &mut rng);
    let mut prev = (cap_w, cap_h);
    let (mut cases, mut crossing, mut small_sigma) = (0, 0, 0);
    for &radius in &[1.0f32, 2.625, 3.4, 10.5, 21.0, 42.0] {
        for &(size, inset) in &[(525.0f32, 131.5f32), (315.0, 40.25), (168.0, 30.37), (400.0, 150.0), (250.5, 70.75)] {
            for &(ox, oy) in &[(0.0f32, 0.0f32), (8.53, -19.69), (-60.25, 33.0), (size * 0.6 + 0.3, -size * 0.35)] {
                let inverted = ox != 0.0 || oy != 0.0;
                let (t, pad) = if inverted { (0.0, radius.ceil()) } else { (radius, radius) };
                let used = ((size + 2.0 * pad).ceil() as usize, (size + 2.0 * pad).ceil() as usize);
                let (x0, y0) = (t + ox, t + oy);
                let outer = [x0, y0, size + x0, size + y0];
                let inner = [inset + x0, inset + y0, size - inset + x0, size - inset + y0];
                let RectsRoute::NinePatch(p) = plan_rects(outer, inner, radius) else { continue };
                let s = p.stretch;
                let small_stride = s.small_w + rng.below(5) as usize;
                let small = small_frame(&p, small_stride);
                let small_a8 = A8 { pixels: &small, width: s.small_w, height: s.small_h, stride: small_stride };
                let mut dst = A8Mut { pixels: &mut px, width: cap_w, height: cap_h, stride };
                if inverted {
                    builder.nine_rects_inverted_into(small_a8, &p, &mut dst, used, prev);
                } else {
                    builder.nine_rects_into(small_a8, &p, &mut dst, used, prev);
                }
                let blurred = skia::blur_a8(&small, s.small_w, s.small_h, small_stride, s.sigma);
                assert_eq!((blurred.width, blurred.height, blurred.border_x), (s.mask_w(), s.mask_h(), s.border));
                let mut want = px.clone();
                reference_write(&mut want, stride, &blurred.pixels, &s, used, prev, inverted);
                assert_eq!(px, want, "r {radius} size {size} inset {inset} offset ({ox}, {oy})");
                let (uw, uh) = (used.0 as isize, used.1 as isize);
                crossing += (s.outer_l < 0 || s.outer_t < 0 || s.outer_r > uw || s.outer_b > uh) as usize;
                small_sigma += (s.sigma < 2.0) as usize;
                prev = used;
                cases += 1;
            }
        }
    }
    assert!(
        cases >= 60 && crossing >= 30 && small_sigma >= 8,
        "cases {cases}, crossing {crossing}, small sigma {small_sigma}"
    );
}

#[test]
fn nine_rects_allocates_nothing_after_warm_up() {
    let frames = [
        ([63.0f32, 63.0, 588.0, 588.0], [194.5f32, 194.5, 456.5, 456.5], 63.0f32),
        ([2.625, 2.625, 128.625, 128.625], [34.125, 34.125, 97.125, 97.125], 2.625),
        ([10.5, 10.5, 535.5, 400.25], [100.37, 60.0, 400.0, 300.0], 10.5),
    ];
    let patches: Vec<RectsPatch> = frames
        .iter()
        .map(|&(o, i, r)| match plan_rects(o, i, r) {
            RectsRoute::NinePatch(p) => p,
            other => panic!("{other:?}"),
        })
        .collect();
    let smalls: Vec<Vec<u8>> = patches.iter().map(|p| small_frame(p, p.stretch.small_w)).collect();
    let cap = (700usize, 700usize);
    let mut px = vec![0u8; cap.0 * cap.1];
    let mut builder = ShadowBuilder::new();
    let run = |builder: &mut ShadowBuilder, px: &mut [u8]| {
        for (p, small) in patches.iter().zip(&smalls) {
            let small =
                A8 { pixels: small, width: p.stretch.small_w, height: p.stretch.small_h, stride: p.stretch.small_w };
            let mut dst = A8Mut { pixels: px, width: cap.0, height: cap.1, stride: cap.0 };
            builder.nine_rects_into(small, p, &mut dst, (650, 650), cap);
            builder.nine_rects_inverted_into(small, p, &mut dst, (650, 650), cap);
        }
    };
    run(&mut builder, &mut px); // warm-up
    let before = allocations();
    for _ in 0..3 {
        run(&mut builder, &mut px);
    }
    assert_eq!(allocations() - before, 0, "allocations after warm-up");
}
