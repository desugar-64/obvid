// Copyright 2026, Serhii Yaremych (desugar-64) and the Obvid project contributors
// SPDX-License-Identifier: Apache-2.0

//! Host tests for `shadow::hole` (declared in hole.rs as `mod tests`). Every hole is checked against the bitmap it was
//! read for: each pixel inside it must be 0. The bitmaps are the inner routes' own writes (the mask route's blur and
//! inversion of supersampled shapes at Compose's inner geometry, and the round-rect nine-patch's three inverted
//! writers), synthetic grids, and random nine-patches; the size of the holes is checked where the geometry leaves a
//! large one.

use super::*;
use crate::blur::Store;
use crate::shadow::nine::{
    NinePatch, RoundRectRoute, kept_mask_hole, place_nine_inverted, place_nine_inverted_hole, plan_round_rect,
    write_nine_inverted,
};
use crate::shadow::{A8Mut, Placement, ShadowBuilder, border_for_radius, invert_used};
use crate::test_support::Rng;

/// Every pixel of `hole` in the plane `px` (stride `stride`) is 0, and the hole lies inside `used`.
fn assert_zero_inside(px: &[u8], stride: usize, used: (usize, usize), hole: Hole, what: &str) {
    if hole.is_empty() {
        assert_eq!(hole, Hole::EMPTY, "{what}: an empty hole is EMPTY");
        return;
    }
    assert!(hole.r <= used.0 && hole.b <= used.1, "{what}: hole {hole:?} outside the used rect {used:?}");
    for y in hole.t..hole.b {
        for x in hole.l..hole.r {
            assert_eq!(px[y * stride + x], 0, "{what}: hole {hole:?} has {} at ({x}, {y})", px[y * stride + x]);
        }
    }
}

// ---- grids -----------------------------------------------------------------------------------------------------

#[test]
fn packed_hole() {
    assert_eq!(Hole::EMPTY.packed(), 0);
    assert_eq!(Hole { l: 5, t: 5, r: 5, b: 9 }.packed(), 0, "empty");
    let h = Hole { l: 1, t: 2, r: 300, b: 32767 };
    let p = h.packed();
    assert!(p > 0);
    assert_eq!((p & 0xFFFF, (p >> 16) & 0xFFFF, (p >> 32) & 0xFFFF, p >> 48), (1, 2, 300, 32767));
    assert_eq!(Hole { l: 0, t: 0, r: 32768, b: 10 }.packed(), 0, "over 32767");
    assert_eq!(Hole { l: 0, t: 0, r: 10, b: 40000 }.packed(), 0, "over 32767");
}

/// A rect of zeros in a plane of non-zeros is found exactly from any seed inside it (the greedy grows it whole), for
/// widths around the 16-byte word; a seed on a non-zero, or outside the plane, gives no hole.
#[test]
fn zero_hole_finds_a_plain_rect() {
    for &(w, h) in &[(1usize, 1usize), (40, 30), (100, 77), (17, 200)] {
        let stride = w + 7;
        for &(l, t, r, b) in
            &[(0usize, 0usize, w, h), (w / 3, h / 4, w - w / 5, h - 1), (w / 2, h / 2, w / 2 + 1, h / 2 + 1)]
        {
            if l >= r || t >= b {
                continue;
            }
            let mut px = vec![0xA5u8; stride * h];
            for y in 0..h {
                for x in 0..w {
                    px[y * stride + x] =
                        if (l..r).contains(&x) && (t..b).contains(&y) { 0 } else { 1 + (x * 7 + y) as u8 % 200 };
                }
            }
            let plane = A8 { pixels: &px, width: w, height: h, stride };
            for &(sx, sy) in &[(l, t), (r - 1, b - 1), ((l + r) / 2, (t + b) / 2)] {
                assert_eq!(
                    zero_hole(plane, sx, sy),
                    Hole { l, t, r, b },
                    "{w}×{h} rect ({l}, {t}, {r}, {b}) seed ({sx}, {sy})"
                );
            }
            if l > 0 {
                assert_eq!(zero_hole(plane, l - 1, t), Hole::EMPTY);
            }
            assert_eq!(zero_hole(plane, w, 0), Hole::EMPTY);
            assert_eq!(zero_hole(plane, 0, h), Hole::EMPTY);
        }
    }
}

/// Random grids (zeros in blobs, stripes and noise): every hole is all zeros, holds its seed and is at least the seed
/// row's run (the starting rect); on a disc of zeros the hole covers most of the disc's inscribed square.
#[test]
fn zero_hole_random_grids_are_conservative() {
    let mut rng = Rng(0x401E_0001);
    let mut found = 0;
    for case in 0..400 {
        let (w, h) = (1 + rng.below(90) as usize, 1 + rng.below(90) as usize);
        let stride = w + rng.below(20) as usize;
        let mut px = vec![0xA5u8; stride * h];
        let kind = case % 4;
        let (cx, cy, rr) = (rng.below(w as u32) as f64, rng.below(h as u32) as f64, 1.0 + rng.below(60) as f64);
        for y in 0..h {
            for x in 0..w {
                let zero = match kind {
                    0 => (x as f64 - cx).powi(2) + (y as f64 - cy).powi(2) <= rr * rr && rng.below(50) != 0,
                    1 => (x + y * 3) % (2 + case % 9) != 0,
                    2 => rng.below(4) != 0,
                    _ => true,
                };
                px[y * stride + x] = if zero { 0 } else { 1 + rng.below(255) as u8 };
            }
        }
        let plane = A8 { pixels: &px, width: w, height: h, stride };
        let (sx, sy) = (rng.below(w as u32) as usize, rng.below(h as u32) as usize);
        let hole = zero_hole(plane, sx, sy);
        assert_zero_inside(&px, stride, (w, h), hole, &format!("case {case}"));
        if px[sy * stride + sx] != 0 {
            assert_eq!(hole, Hole::EMPTY, "case {case}: seed on a non-zero");
            continue;
        }
        assert!(
            (hole.l..hole.r).contains(&sx) && (hole.t..hole.b).contains(&sy),
            "case {case}: hole {hole:?} misses its seed"
        );
        let row = &px[sy * stride..sy * stride + w];
        let run = run_around(row, sx, 0, w, 0).unwrap();
        assert!(hole.area() >= run.1 - run.0, "case {case}: hole {hole:?} smaller than the seed row's run {run:?}");
        if kind == 3 {
            assert_eq!(hole, Hole { l: 0, t: 0, r: w, b: h }, "case {case}: all zeros");
        }
        found += 1;
    }
    assert!(found > 150, "found {found}");
}

// ---- inner shadow, mask route ----------------------------------------------------------------------------------

/// A shape in component coordinates: whether the point is inside.
type Inside = Box<dyn Fn(f64, f64) -> bool>;

/// The shapes of the mask route's tests for a square component of `s` px: a round rect with mixed corners, a 9-lobed
/// cookie (concave), an ellipse, a frame (the box minus a centred rect inset by a quarter) and a thin diagonal band.
fn shapes(s: f64) -> Vec<(&'static str, Inside)> {
    let mixed = move |x: f64, y: f64| {
        if !(0.0..s).contains(&x) || !(0.0..s).contains(&y) {
            return false;
        }
        // Corner radii: top-left, top-right, bottom-right, bottom-left.
        let rad = [0.04 * s, 0.16 * s, 0.24 * s, 0.08 * s];
        let (r, cx, cy) = match (x < s / 2.0, y < s / 2.0) {
            (true, true) => (rad[0], rad[0], rad[0]),
            (false, true) => (rad[1], s - rad[1], rad[1]),
            (false, false) => (rad[2], s - rad[2], s - rad[2]),
            (true, false) => (rad[3], rad[3], s - rad[3]),
        };
        let outside_x = if x < s / 2.0 { x < cx } else { x > cx };
        let outside_y = if y < s / 2.0 { y < cy } else { y > cy };
        !(outside_x && outside_y) || (x - cx).powi(2) + (y - cy).powi(2) <= r * r
    };
    let cookie = move |x: f64, y: f64| {
        let (dx, dy) = (x - s / 2.0, y - s / 2.0);
        let a = dy.atan2(dx);
        (dx * dx + dy * dy).sqrt() <= s * 0.45 * (1.0 + 0.08 * (9.0 * a).cos()) / 1.08
    };
    let ellipse =
        move |x: f64, y: f64| ((x - s / 2.0) / (0.5 * s)).powi(2) + ((y - s / 2.0) / (0.35 * s)).powi(2) <= 1.0;
    let frame = move |x: f64, y: f64| {
        let d = (s / 4.0).floor() + 0.5;
        (0.0..s).contains(&x) && (0.0..s).contains(&y) && !((d..s - d).contains(&x) && (d..s - d).contains(&y))
    };
    let band = move |x: f64, y: f64| (x - y).abs() < 0.1 * s && (0.0..s).contains(&x) && (0.0..s).contains(&y);
    vec![
        ("mixed", Box::new(mixed)),
        ("cookie", Box::new(cookie)),
        ("ellipse", Box::new(ellipse)),
        ("frame", Box::new(frame)),
        ("band", Box::new(band)),
    ]
}

/// The A8 coverage (4 × 4 supersampled) of `inside` moved by (`ox`, `oy`) over the device rect `[l, r) × [t, b)`, as
/// a tightly packed mask.
fn rasterize(inside: &Inside, ox: f64, oy: f64, l: isize, t: isize, r: isize, b: isize) -> Vec<u8> {
    let (w, h) = ((r - l) as usize, (b - t) as usize);
    let mut v = vec![0u8; w * h];
    for y in 0..h {
        for x in 0..w {
            let mut n = 0;
            for k in 0..16 {
                let px = (l + x as isize) as f64 + ((k % 4) as f64 + 0.5) / 4.0;
                let py = (t + y as isize) as f64 + ((k / 4) as f64 + 0.5) / 4.0;
                n += inside(px - ox, py - oy) as u32;
            }
            v[y * w + x] = (n * 255 / 16) as u8;
        }
    }
    v
}

/// The mask route's inner bitmap for a square component of `s` px (Compose's path geometry: `ceil(s) + 2·ceil(r)`
/// per axis, the shape at the component's origin, not inset, moved by the offset; Skia's mask bounds: the moved
/// shape's bounds outset by ½, rounded out, clipped to the bitmap grown by min(border, 128)), written by
/// [`ShadowBuilder::blur_into`] and [`ShadowBuilder::invert_hole`] (seeded at [`seed`]) into `px`. Returns the used
/// size and the hole.
#[allow(clippy::too_many_arguments)]
fn inner_mask_route(
    builder: &mut ShadowBuilder,
    inside: &Inside,
    s: f64,
    radius: f32,
    ox: f64,
    oy: f64,
    px: &mut [u8],
    cap: (usize, usize),
    stride: usize,
) -> ((usize, usize), Hole) {
    let pad = radius.ceil() as usize;
    let used = (s.ceil() as usize + 2 * pad, s.ceil() as usize + 2 * pad);
    let m = border_for_radius(radius).min(128) as isize;
    let l = ((ox - 0.5).floor() as isize).max(-m);
    let t = ((oy - 0.5).floor() as isize).max(-m);
    let r = ((ox + s + 0.5).ceil() as isize).min(used.0 as isize + m);
    let b = ((oy + s + 0.5).ceil() as isize).min(used.1 as isize + m);
    let (mask, mw, mh) = if l < r && t < b {
        (rasterize(inside, ox, oy, l, t, r, b), (r - l) as usize, (b - t) as usize)
    } else {
        (Vec::new(), 0, 0)
    };
    let mut dst = A8Mut { pixels: px, width: cap.0, height: cap.1, stride };
    let at = Placement { mask_x: l, mask_y: t, used_w: used.0, used_h: used.1, clear_w: used.0, clear_h: used.1 };
    builder.blur_into(A8 { pixels: &mask, width: mw, height: mh, stride: mw }, radius, &mut dst, at, Store::Replace);
    let hole = builder.invert_hole(&mut dst, used, seed(s, ox, oy));
    (used, hole)
}

/// The seed the Kotlin side passes: the component's centre moved by half the offset, in whole pixels.
fn seed(s: f64, ox: f64, oy: f64) -> (usize, usize) {
    (((s + ox) / 2.0).floor().max(0.0) as usize, ((s + oy) / 2.0).floor().max(0.0) as usize)
}

/// The mask route's inner bitmaps (shapes × sizes × radii × offsets, negative ones and ones past the blur, large
/// blurs that leave no zero, tiny components): every hole found during the inversion is the one [`zero_hole`] reads
/// from the inverted bitmap, and its pixels are all zeros. The convex shapes at small blur leave a large
/// hole (≥ 70% of the component's area for the round rect with mixed corners; ≥ 25% for the ellipse, whose largest
/// inscribed rect is 35%); the frame's centre is not 0 (outside the shape), so its seed gives no hole unless the
/// offset moves the seed into the frame's band.
#[test]
fn mask_route_holes_are_zero() {
    let mut builder = ShadowBuilder::new();
    let cap = (900usize, 900usize);
    let stride = cap.0 + 5;
    let mut px = vec![0x5Au8; stride * cap.1];
    let (mut cases, mut holes, mut large) = (0, 0, 0);
    for &s in &[525.0f64, 126.0, 30.0, 6.0] {
        for (name, inside) in shapes(s) {
            for &radius in &[1.0f32, 2.625, 10.5, 63.0, 168.0] {
                for &(ox, oy) in
                    &[(0.0f64, 0.0f64), (7.875, 13.125), (-20.0, 31.5), (8.53, -19.69), (52.5, -78.75), (s + 50.0, 0.0)]
                {
                    let (used, hole) = inner_mask_route(&mut builder, &inside, s, radius, ox, oy, &mut px, cap, stride);
                    let (sx, sy) = seed(s, ox, oy);
                    let plane = A8 { pixels: &px, width: used.0, height: used.1, stride };
                    let what = format!("{name} {s} px r {radius} offset ({ox}, {oy})");
                    assert_eq!(hole, zero_hole(plane, sx, sy), "{what}");
                    assert_zero_inside(&px, stride, used, hole, &what);
                    cases += 1;
                    holes += !hole.is_empty() as usize;
                    let share = hole.area() as f64 / (s * s);
                    if name == "frame" && ox.abs() < 0.2 * s && oy.abs() < 0.2 * s {
                        assert!(hole.is_empty(), "{what}: the frame's centre is outside the shape");
                    }
                    if s == 525.0
                        && radius <= 10.5
                        && ox.abs() < 10.0
                        && oy.abs() < 20.0
                        && (name == "mixed" || name == "ellipse")
                    {
                        let want = if name == "mixed" { 0.7 } else { 0.25 };
                        assert!(share >= want, "{what}: hole {hole:?} covers {:.1}%", share * 100.0);
                        large += 1;
                    }
                    if s <= 30.0 && radius >= 63.0 {
                        assert!(hole.is_empty(), "{what}: a blur this large leaves no 0");
                    }
                }
            }
        }
    }
    assert!(cases == 600 && holes > 120 && large == 18, "cases {cases}, holes {holes}, large {large}");
}

/// [`ShadowBuilder::invert_hole`] allocates nothing once its scratch has grown, and with a seed outside the used rect
/// inverts exactly as [`invert_used`] and reports no hole.
#[test]
fn invert_hole_allocates_nothing_after_warm_up_and_seeds_outside() {
    use crate::test_support::allocations;
    let mut rng = Rng(0x401E_A001);
    let (w, h, stride) = (300usize, 200usize, 307usize);
    let base: Vec<u8> = (0..stride * h).map(|i| if (i % stride) % 50 < 40 { 255 } else { rng.next() as u8 }).collect();
    let mut builder = ShadowBuilder::new();
    let mut px = base.clone();
    builder.invert_hole(&mut A8Mut { pixels: &mut px, width: w, height: h, stride }, (w, h), (10, 10));
    for &(uw, uh) in &[(300usize, 200usize), (120, 77), (1, 1)] {
        let mut px = base.clone();
        let mut dst = A8Mut { pixels: &mut px, width: w, height: h, stride };
        let start = allocations();
        let hole = builder.invert_hole(&mut dst, (uw, uh), (uw / 2, uh / 2));
        assert_eq!(allocations(), start, "{uw}×{uh}");
        assert_zero_inside(&px, stride, (uw, uh), hole, &format!("{uw}×{uh}"));
        for seed in [(uw, 0), (0, uh), (usize::MAX, 3)] {
            let mut got = base.clone();
            let mut want = base.clone();
            let hole =
                builder.invert_hole(&mut A8Mut { pixels: &mut got, width: w, height: h, stride }, (uw, uh), seed);
            invert_used(&mut A8Mut { pixels: &mut want, width: w, height: h, stride }, uw, uh);
            assert_eq!(hole, Hole::EMPTY, "{uw}×{uh} seed {seed:?}");
            assert!(got == want, "{uw}×{uh} seed {seed:?}");
        }
    }
}

// ---- inner shadow, round-rect nine-patch -----------------------------------------------------------------------

/// A soft-edged round rect `w × h` with corner radius (`rx`, `ry`), tightly packed: a stand-in for the small rrect
/// Android's `Canvas` draws.
fn small_rrect(w: usize, h: usize, rx: f32, ry: f32) -> Vec<u8> {
    let mut v = vec![0u8; w * h];
    let (rx, ry) = (rx as f64, ry as f64);
    for y in 0..h {
        for x in 0..w {
            let mut n = 0;
            for k in 0..16 {
                let px = x as f64 + ((k % 4) as f64 + 0.5) / 4.0;
                let py = y as f64 + ((k / 4) as f64 + 0.5) / 4.0;
                let qx = (px.min(w as f64 - px) - rx).min(0.0) / rx;
                let qy = (py.min(h as f64 - py) - ry).min(0.0) / ry;
                n += (qx * qx + qy * qy <= 1.0) as u32;
            }
            v[y * w + x] = (n * 255 / 16) as u8;
        }
    }
    v
}

/// Compose's inner round rect for a square component of `size` px: the rect inset by `spread`, moved by (`ox`, `oy`),
/// and the bitmap size `ceil(size)`.
fn inner_rect(size: f32, spread: f32, ox: f32, oy: f32) -> ([f32; 4], (usize, usize)) {
    let (l, t) = (ox + spread, oy + spread);
    let (r, b) = (l.max(ox + size - spread), t.max(oy + size - spread));
    ([l, t, r, b], (size.ceil() as usize, size.ceil() as usize))
}

/// The three inverted nine-patch writers at Compose's inner round-rect geometry (sizes, corners, radii, spreads,
/// offsets that cross every edge or miss the bitmap): the three `…_hole` forms write the same bitmap and return the
/// same hole (read from the builder's buffer, or from the keep buffer), all zeros. A 200 dp
/// component (525 px at 2.625) with small blur and offset keeps ≥ 70% of its area as the hole; the hole is never much
/// smaller than the one [`zero_hole`] reads from the bitmap (seeded at the stretch centre).
#[test]
fn nine_holes_are_zero_and_large() {
    let mut builder = ShadowBuilder::new();
    let mut keeper = ShadowBuilder::new();
    let cap = (700usize, 700usize);
    let stride = cap.0 + 3;
    let mut a = vec![0x5Au8; stride * cap.1];
    let mut b = a.clone();
    let mut c = a.clone();
    let (mut cases, mut holes, mut large, mut outside) = (0, 0, 0, 0);
    for &radius in &[1.0f32, 2.625, 10.5, 21.0, 63.0, 168.0] {
        for &(size, spread, corner) in &[
            (525.0f32, 0.0f32, 42.0f32),
            (525.0, 10.5, 10.5),
            (315.0, 21.0, 105.0),
            (126.0, -21.0, 16.5375),
            (400.5, 4.25, 30.0),
            (60.0, 0.0, 8.0),
        ] {
            for &(ox, oy) in &[
                (0.0f32, 0.0f32),
                (7.875, 13.125),
                (-21.0, -63.0),
                (8.53, -19.69),
                (size * 0.6 + 0.3, -size * 0.35),
                (size + 400.0, 0.0),
            ] {
                let ([l, t, r, bb], used) = inner_rect(size, spread, ox, oy);
                if used.0 > cap.0 || used.1 > cap.1 {
                    continue;
                }
                let RoundRectRoute::NinePatch(p) = plan_round_rect(l, t, r, bb, corner, corner, radius) else {
                    continue;
                };
                let small = small_rrect(p.small_w, p.small_h, p.rx, p.ry);
                let small_a8 = A8 { pixels: &small, width: p.small_w, height: p.small_h, stride: p.small_w };
                let what = format!("r {radius} size {size} spread {spread} corner {corner} offset ({ox}, {oy})");

                let mut dst = A8Mut { pixels: &mut a, width: cap.0, height: cap.1, stride };
                let from_builder = builder.nine_inverted_hole_into(small_a8, &p, &mut dst, used, used);
                assert_zero_inside(&a, stride, used, from_builder, &what);

                let (ks, kh) = (p.mask_w() + 9, p.mask_h() + 2);
                let mut keep = vec![0xA5u8; ks * kh];
                let mut keep_a8 = A8Mut { pixels: &mut keep, width: p.mask_w() + 4, height: kh, stride: ks };
                let mut dst = A8Mut { pixels: &mut b, width: cap.0, height: cap.1, stride };
                let from_keep = keeper.nine_keep_inverted_hole_into(small_a8, &p, &mut keep_a8, &mut dst, used, used);
                assert_eq!(from_keep, from_builder, "{what}");
                assert_zero_inside(&b, stride, used, from_keep, &what);

                let kept = A8 { pixels: &keep, width: p.mask_w() + 4, height: kh, stride: ks };
                let mut dst = A8Mut { pixels: &mut c, width: cap.0, height: cap.1, stride };
                let from_place = place_nine_inverted_hole(&mut dst, kept, &p, used, used, None);
                assert_eq!(from_place, from_builder, "{what}");
                assert_zero_inside(&c, stride, used, from_place, &what);
                assert!(a == b && b == c, "{what}: the three writes differ");

                let share = from_builder.area() as f64 / (size as f64 * size as f64);
                if size == 525.0 && radius <= 10.5 && ox.abs() < 10.0 && oy.abs() < 20.0 {
                    assert!(share >= 0.7, "{what}: hole {from_builder:?} covers {:.1}%", share * 100.0);
                    large += 1;
                }
                // The hole read from the bitmap from the stretch centre, where it lies inside the used rect.
                let (cx, cy) = (p.outer_l + p.center_x as isize, p.outer_t + p.center_y as isize);
                if (0..used.0 as isize).contains(&cx) && (0..used.1 as isize).contains(&cy) {
                    let read =
                        zero_hole(A8 { pixels: &a, width: used.0, height: used.1, stride }, cx as usize, cy as usize);
                    assert!(
                        from_builder.area() * 10 >= read.area() * 9,
                        "{what}: hole {from_builder:?}, from the bitmap {read:?}"
                    );
                } else {
                    outside += 1;
                }
                if p.outer_l >= used.0 as isize || p.outer_r <= 0 || p.outer_t >= used.1 as isize || p.outer_b <= 0 {
                    assert_eq!(from_builder, Hole::EMPTY, "{what}: the patch misses the bitmap");
                }
                holes += !from_builder.is_empty() as usize;
                cases += 1;
            }
        }
    }
    assert!(
        cases >= 120 && holes >= 60 && large >= 4 && outside >= 10,
        "cases {cases}, holes {holes}, large {large}, outside {outside}"
    );
}

/// A kept mask's rect found once ([`kept_mask_hole`]) and only mapped for each placement gives the same hole as the
/// rect read again at that placement and as [`nine_hole`] from the builder's own blur, for many placements of one key
/// (Compose's inner geometry: sizes, spreads and offsets that move and resize the outer rect, cross every edge and miss
/// the bitmap); every hole is all zeros. A packed rect survives [`Hole::unpacked`]; a rect that does not hold the
/// centre maps to no hole.
#[test]
fn cached_mask_hole_matches_fresh_for_every_placement() {
    let mut rng = Rng(0x401E_C001);
    let mut builder = ShadowBuilder::new();
    let cap = (700usize, 700usize);
    let stride = cap.0 + 3;
    let mut a = vec![0x5Au8; stride * cap.1];
    let mut b = a.clone();
    let (mut placements, mut holes) = (0, 0);
    for &(radius, corner) in &[(10.5f32, 42.0f32), (2.625, 16.5375), (63.0, 30.0), (21.0, 105.0), (1.0, 8.0)] {
        let mut kept: Option<(super::super::nine::SmallMaskKey, Vec<u8>, usize, usize, Hole)> = None;
        for _ in 0..60 {
            let size = 120.0 + rng.below(480) as f32 + rng.below(4) as f32 * 0.25;
            let spread = rng.range(-20, 20) as f32 * 0.5;
            let (ox, oy) = (rng.range(-300, 300) as f32 * 0.37, rng.range(-300, 300) as f32 * 0.41);
            let ([l, t, r, bb], used) = inner_rect(size, spread, ox, oy);
            if used.0 > cap.0 || used.1 > cap.1 {
                continue;
            }
            let RoundRectRoute::NinePatch(p) = plan_round_rect(l, t, r, bb, corner, corner, radius) else { continue };
            let small = small_rrect(p.small_w, p.small_h, p.rx, p.ry);
            let small_a8 = A8 { pixels: &small, width: p.small_w, height: p.small_h, stride: p.small_w };
            let what = format!("r {radius} corner {corner} size {size} spread {spread} offset ({ox}, {oy})");
            let mut dst = A8Mut { pixels: &mut a, width: cap.0, height: cap.1, stride };
            let fresh = builder.nine_inverted_hole_into(small_a8, &p, &mut dst, used, used);
            let entry = match &kept {
                Some(k) if k.0 == p.small_key() => k,
                _ => {
                    let (kw, kh) = (p.mask_w() + 3, p.mask_h() + 1);
                    let mut keep = vec![0xA5u8; kw * kh];
                    let mut keep_a8 = A8Mut { pixels: &mut keep, width: kw, height: kh, stride: kw };
                    let mut dst = A8Mut { pixels: &mut b, width: cap.0, height: cap.1, stride };
                    builder.nine_keep_inverted_into(small_a8, &p, &mut keep_a8, &mut dst, used, used);
                    let cached = kept_mask_hole(A8 { pixels: &keep, width: kw, height: kh, stride: kw }, &p);
                    assert_eq!(Hole::unpacked(cached.packed()), Some(cached));
                    kept = Some((p.small_key(), keep, kw, kh, cached));
                    kept.as_ref().unwrap()
                }
            };
            let keep_a8 = A8 { pixels: &entry.1, width: entry.2, height: entry.3, stride: entry.2 };
            let mut dst = A8Mut { pixels: &mut b, width: cap.0, height: cap.1, stride };
            let mapped = place_nine_inverted_hole(&mut dst, keep_a8, &p, used, used, Some(entry.4));
            assert!(a == b, "{what}: placement differs from the blur");
            let mut dst = A8Mut { pixels: &mut b, width: cap.0, height: cap.1, stride };
            let read = place_nine_inverted_hole(&mut dst, keep_a8, &p, used, used, None);
            assert_eq!(mapped, read, "{what}");
            assert_eq!(mapped, fresh, "{what}");
            assert_zero_inside(&b, stride, used, mapped, &what);
            // A rect that misses the centre is no hole.
            let off = Hole { l: p.center_x + 1, t: entry.4.t, r: p.center_x + 2, b: entry.4.b };
            assert_eq!(map_nine_hole(off, &p.stretch(), used), Hole::EMPTY, "{what}");
            placements += 1;
            holes += !mapped.is_empty() as usize;
        }
    }
    assert_eq!(Hole::unpacked(-1), None);
    assert!(placements >= 200 && holes >= 100, "placements {placements}, holes {holes}");
}

/// Random nine-patches (margins, corner sizes, outer rects crossing every edge of the used rect, centres of 2 px and
/// up) over random blurred masks with a block of 255s of random extent around the centre and stray 255s elsewhere:
/// the hole of [`write_nine_inverted`]'s output is all zeros, and holds the whole centre when the centre lies inside
/// the used rect.
#[test]
fn nine_hole_random_patches_are_conservative() {
    let mut rng = Rng(0x401E_9001);
    let cap = (260usize, 220usize);
    let stride = cap.0 + 11;
    let mut px = vec![0x5Au8; stride * cap.1];
    let (mut nonempty, mut centred) = (0, 0);
    for case in 0..600 {
        let m = rng.below(12) as usize;
        let (rcx, rcy) = (rng.below(20) as usize, rng.below(20) as usize);
        let (small_w, small_h) = (2 * (rcx + m) + 1, 2 * (rcy + m) + 1);
        let (mw, mh) = (small_w + 2 * m, small_h + 2 * m);
        let (ol, ot) = (rng.range(-80, 200), rng.range(-80, 180));
        let (extra_w, extra_h) = (1 + rng.below(150) as usize, 1 + rng.below(150) as usize);
        let p = NinePatch {
            rect_w: small_w as f32,
            rect_h: small_h as f32,
            rx: rcx as f32,
            ry: rcy as f32,
            small_w,
            small_h,
            sigma: 0.0,
            border: m,
            outer_l: ol,
            outer_t: ot,
            outer_r: ol + (mw + extra_w) as isize,
            outer_b: ot + (mh + extra_h) as isize,
            center_x: m + rcx + m,
            center_y: m + rcy + m,
        };
        let (cx, cy) = (p.center_x, p.center_y);
        let mut mask: Vec<u8> =
            (0..mw * mh).map(|_| if rng.below(8) == 0 { 255 } else { rng.below(255) as u8 }).collect();
        let (bl, br) = (cx - rng.below(cx as u32 + 1) as usize, cx + 1 + rng.below((mw - cx) as u32) as usize);
        let (bt, bb) = (cy - rng.below(cy as u32 + 1) as usize, cy + 1 + rng.below((mh - cy) as u32) as usize);
        for y in bt..bb {
            for x in bl..br {
                if rng.below(30) != 0 || (x, y) == (cx, cy) {
                    mask[y * mw + x] = 255;
                }
            }
        }
        if rng.below(4) == 0 {
            // The centre pixel itself not 255: the filled centre still is.
            mask[cy * mw + cx] = 17;
        }
        let used = (1 + rng.below(cap.0 as u32) as usize, 1 + rng.below(cap.1 as u32) as usize);
        let mask_a8 = A8 { pixels: &mask, width: mw, height: mh, stride: mw };
        let mut dst = A8Mut { pixels: &mut px, width: cap.0, height: cap.1, stride };
        write_nine_inverted(&mut dst, mask_a8, &p, used, used);
        let hole = nine_hole(mask_a8, &p.stretch(), used);
        assert_zero_inside(&px, stride, used, hole, &format!("case {case} {p:?} used {used:?}"));
        nonempty += !hole.is_empty() as usize;
        // The centre (inner rect) clipped to the used rect: always 0, so inside the hole when it starts the hole.
        let (il, ir) = (ol + cx as isize, p.outer_r + cx as isize + 1 - mw as isize);
        let (it, ib) = (ot + cy as isize, p.outer_b + cy as isize + 1 - mh as isize);
        let clip = |v: isize, max: usize| v.clamp(0, max as isize) as usize;
        let centre = Hole { l: clip(il, used.0), t: clip(it, used.1), r: clip(ir, used.0), b: clip(ib, used.1) };
        if !centre.is_empty() {
            assert!(hole.area() >= centre.area(), "case {case}: hole {hole:?} smaller than the centre {centre:?}");
            centred += 1;
        }
    }
    assert!(nonempty > 200 && centred > 150, "non-empty {nonempty}, centred {centred}");
}

// ---- timing (Mac only, indicative) -----------------------------------------------------------------------------

/// `cargo test --release shadow::hole::tests::timing_mac -- --ignored --nocapture`: the inner routes' build with and
/// without the hole (median of runs): the mask route (blur, then [`invert_used`] or [`ShadowBuilder::invert_hole`]) and
/// the nine-patch's placement of a kept mask (then [`nine_hole`]).
#[test]
#[ignore]
fn timing_mac() {
    use std::time::Instant;
    fn median(mut v: Vec<f64>) -> f64 {
        v.sort_by(|a, b| a.partial_cmp(b).unwrap());
        v[v.len() / 2]
    }
    const RUNS: usize = 41;
    let mut builder = ShadowBuilder::new();
    let cap = (1100usize, 1100usize);
    let stride = cap.0;
    let mut px = vec![0u8; stride * cap.1];
    println!("mask route (cookie): blur + inversion µs, the same with the hole found during the inversion");
    for &s in &[126.0f64, 525.0, 945.0] {
        for &radius in &[10.5f32, 63.0] {
            let inside = shapes(s).into_iter().find(|(n, _)| *n == "cookie").unwrap().1;
            let (ox, oy) = (7.875f64, 13.125f64);
            // The rasterization stands in for Android's Canvas draw: not timed.
            let pad = radius.ceil() as usize;
            let used = (s.ceil() as usize + 2 * pad, s.ceil() as usize + 2 * pad);
            let m = border_for_radius(radius).min(128) as isize;
            let (l, t) = (((ox - 0.5).floor() as isize).max(-m), ((oy - 0.5).floor() as isize).max(-m));
            let (r, b) = (
                ((ox + s + 0.5).ceil() as isize).min(used.0 as isize + m),
                ((oy + s + 0.5).ceil() as isize).min(used.1 as isize + m),
            );
            let mask = rasterize(&inside, ox, oy, l, t, r, b);
            let (mw, mh) = ((r - l) as usize, (b - t) as usize);
            let at =
                Placement { mask_x: l, mask_y: t, used_w: used.0, used_h: used.1, clear_w: used.0, clear_h: used.1 };
            let (mut plain, mut fused) = (Vec::new(), Vec::new());
            let mut area = 0;
            for run in 0..RUNS {
                // Both orders, alternating.
                for with_hole in [run % 2 == 0, run % 2 != 0] {
                    let start = Instant::now();
                    let mut dst = A8Mut { pixels: &mut px, width: cap.0, height: cap.1, stride };
                    builder.blur_into(
                        A8 { pixels: &mask, width: mw, height: mh, stride: mw },
                        radius,
                        &mut dst,
                        at,
                        Store::Replace,
                    );
                    if with_hole {
                        area = builder.invert_hole(&mut dst, used, seed(s, ox, oy)).area();
                        fused.push(start.elapsed().as_secs_f64() * 1e6);
                    } else {
                        invert_used(&mut dst, used.0, used.1);
                        plain.push(start.elapsed().as_secs_f64() * 1e6);
                    }
                }
            }
            let (pm, fm) = (median(plain), median(fused));
            println!(
                "  {s} px r {radius}: {pm:.1} µs, with the hole {fm:.1} µs ({:+.2}%), hole {:.0}% of the component",
                100.0 * (fm - pm) / pm,
                100.0 * area as f64 / (s * s)
            );
        }
    }
    println!("nine-patch placement (corner 42 px): place µs, with the hole (kept mask's rect mapped) µs");
    for &size in &[126.0f32, 525.0, 945.0] {
        for &radius in &[10.5f32, 63.0] {
            let ([l, t, r, b], used) = inner_rect(size, 0.0, 7.875, 13.125);
            let RoundRectRoute::NinePatch(p) = plan_round_rect(l, t, r, b, 42.0, 42.0, radius) else { continue };
            let small = small_rrect(p.small_w, p.small_h, p.rx, p.ry);
            let mut keep = vec![0u8; p.mask_w() * p.mask_h()];
            let mut keep_a8 = A8Mut { pixels: &mut keep, width: p.mask_w(), height: p.mask_h(), stride: p.mask_w() };
            let mut dst = A8Mut { pixels: &mut px, width: cap.0, height: cap.1, stride };
            builder.nine_keep_inverted_into(
                A8 { pixels: &small, width: p.small_w, height: p.small_h, stride: p.small_w },
                &p,
                &mut keep_a8,
                &mut dst,
                used,
                used,
            );
            let kept = A8 { pixels: &keep, width: p.mask_w(), height: p.mask_h(), stride: p.mask_w() };
            let cached = kept_mask_hole(kept, &p);
            let (mut place, mut with_hole, mut read) = (Vec::new(), Vec::new(), Vec::new());
            for run in 0..RUNS {
                for k in 0..3 {
                    let which = (run + k) % 3;
                    let start = Instant::now();
                    let mut dst = A8Mut { pixels: &mut px, width: cap.0, height: cap.1, stride };
                    match which {
                        0 => place_nine_inverted(&mut dst, kept, &p, used, used),
                        1 => {
                            std::hint::black_box(place_nine_inverted_hole(
                                &mut dst,
                                kept,
                                &p,
                                used,
                                used,
                                Some(cached),
                            ));
                        }
                        _ => {
                            std::hint::black_box(place_nine_inverted_hole(&mut dst, kept, &p, used, used, None));
                        }
                    }
                    let us = start.elapsed().as_secs_f64() * 1e6;
                    [&mut place, &mut with_hole, &mut read][which].push(us);
                }
            }
            let (pm, hm, rm) = (median(place), median(with_hole), median(read));
            println!(
                "  {size} px r {radius}: {pm:.2} µs, with the hole {hm:.2} µs ({:+.1}%), reading the rect each time {rm:.2} µs",
                100.0 * (hm - pm) / pm
            );
        }
    }
}
