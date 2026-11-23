// Copyright 2026, Serhii Yaremych (desugar-64) and the Obvid project contributors
// SPDX-License-Identifier: Apache-2.0

//! Host tests for `shadow::nine` (declared in nine.rs as `mod tests`). The writer is checked against Skia's
//! `draw_nine` written out per pixel (the formula in nine.rs's module docs); the whole route against the scalar port
//! `skia::blur_a8` plus that per-pixel formula; the geometry on hand-computed float cases and on Compose's own
//! geometry at density 2.625.

use super::*;
use crate::shadow::border_for_radius;
use crate::test_support::{Rng, allocations};

/// Byte the tests put in padding and outside every rect that must stay untouched.
const SENTINEL: u8 = 0xA5;

// ---- reference -------------------------------------------------------------------------------------------------

/// The per-pixel formula of nine.rs's module docs, as written: the value Skia's `draw_nine` (fillCenter) gives
/// device pixel (x, y), or None outside the outer rect. `mask` is `mw × mh`, tightly packed.
fn reference_value(mask: &[u8], mw: usize, mh: usize, p: &NinePatch, x: isize, y: isize) -> Option<u8> {
    if x < p.outer_l || x >= p.outer_r || y < p.outer_t || y >= p.outer_b {
        return None;
    }
    let (mw, mh) = (mw as isize, mh as isize);
    let (cx, cy) = (p.center_x as isize, p.center_y as isize);
    let (inner_l, inner_r) = (p.outer_l + cx, p.outer_r + (cx + 1 - mw));
    let (inner_t, inner_b) = (p.outer_t + cy, p.outer_b + (cy + 1 - mh));
    // None = CENTER.
    let mx = if x < inner_l {
        Some(x - p.outer_l)
    } else if x < inner_r {
        None
    } else {
        Some(x - (p.outer_r - mw))
    };
    let my = if y < inner_t {
        Some(y - p.outer_t)
    } else if y < inner_b {
        None
    } else {
        Some(y - (p.outer_b - mh))
    };
    let at = |mx: isize, my: isize| mask[(my * mw + mx) as usize];
    Some(match (mx, my) {
        (None, None) => 255,
        (None, Some(my)) => at(cx, my),
        (Some(mx), None) => at(mx, cy),
        (Some(mx), Some(my)) => at(mx, my),
    })
}

/// The whole write by [`reference_value`]: Compose's zeroed bitmap with the nine-patch blitted inside the used rect,
/// then every other pixel of the clear rect zero; the rest of `dst` (stride `stride`) untouched.
#[allow(clippy::too_many_arguments)]
fn reference_write(
    dst: &mut [u8],
    stride: usize,
    mask: &[u8],
    mw: usize,
    mh: usize,
    p: &NinePatch,
    used: (usize, usize),
    clear: (usize, usize),
) {
    let (cw, ch) = (clear.0.max(used.0), clear.1.max(used.1));
    for y in 0..ch {
        for x in 0..cw {
            let inside = x < used.0 && y < used.1;
            dst[y * stride + x] =
                if inside { reference_value(mask, mw, mh, p, x as isize, y as isize).unwrap_or(0) } else { 0 };
        }
    }
}

/// A patch with the stretch geometry of a uniform round rect (`ceil(r) = rcx`, `rcy`), margin `m`, and an outer rect
/// at (`ol`, `ot`) that is `extra_w` / `extra_h` (≥ 1) wider / taller than the blurred mask (the centre is then at
/// least 2 px, as `f32(W) < width` guarantees).
fn patch(m: usize, rcx: usize, rcy: usize, ol: isize, ot: isize, extra_w: usize, extra_h: usize) -> NinePatch {
    let (small_w, small_h) = (2 * (rcx + m) + 1, 2 * (rcy + m) + 1);
    let (mw, mh) = (small_w + 2 * m, small_h + 2 * m);
    NinePatch {
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
    }
}

fn random_bytes(n: usize, rng: &mut Rng) -> Vec<u8> {
    (0..n).map(|_| rng.next() as u8).collect()
}

/// A soft-edged round rect `w × h` with corner radius (`rx`, `ry`), stride `stride` (padding = SENTINEL): a stand-in
/// for the small rrect Android's `Canvas` draws (any bytes would do; this one looks like the real input).
fn small_rrect(w: usize, h: usize, stride: usize, rx: f32, ry: f32) -> Vec<u8> {
    let mut v = vec![SENTINEL; stride * h];
    let (rx, ry) = (rx as f64, ry as f64);
    for y in 0..h {
        for x in 0..w {
            let mut n = 0;
            for s in 0..16 {
                let px = x as f64 + ((s % 4) as f64 + 0.5) / 4.0;
                let py = y as f64 + ((s / 4) as f64 + 0.5) / 4.0;
                let qx = (px.min(w as f64 - px) - rx).min(0.0) / rx;
                let qy = (py.min(h as f64 - py) - ry).min(0.0) / ry;
                if qx * qx + qy * qy <= 1.0 {
                    n += 1;
                }
            }
            v[y * stride + x] = (n * 255 / 16) as u8;
        }
    }
    v
}

// ---- writer ----------------------------------------------------------------------------------------------------

#[test]
fn write_nine_matches_formula_on_random_geometry() {
    let mut rng = Rng(0x9A7C_4E11);
    let (cap_w, cap_h, stride) = (110usize, 96usize, 123usize);
    // Clipping regimes seen: outer crossing left / top / right / bottom, outer missing the used rect, used < 16 wide.
    let mut seen = [0usize; 6];
    let cases = 3000;
    for _ in 0..cases {
        let m = rng.range(0, 12) as usize;
        let p = patch(
            m,
            rng.range(1, 14) as usize,
            rng.range(1, 14) as usize,
            rng.range(-60, 70),
            rng.range(-60, 70),
            rng.range(1, 40) as usize,
            rng.range(1, 40) as usize,
        );
        let (mw, mh) = (p.mask_w(), p.mask_h());
        let mask_stride = mw + rng.below(4) as usize;
        let mut mask = random_bytes(mask_stride * mh, &mut rng);
        let tight: Vec<u8> = (0..mh).flat_map(|y| mask[y * mask_stride..y * mask_stride + mw].to_vec()).collect();
        for y in 0..mh {
            mask[y * mask_stride + mw..(y + 1) * mask_stride].fill(SENTINEL);
        }
        let used = (rng.range(1, cap_w as isize) as usize, rng.range(1, cap_h as isize) as usize);
        let clear = (rng.range(0, cap_w as isize) as usize, rng.range(0, cap_h as isize) as usize);
        seen[0] += (p.outer_l < 0) as usize;
        seen[1] += (p.outer_t < 0) as usize;
        seen[2] += (p.outer_r > used.0 as isize) as usize;
        seen[3] += (p.outer_b > used.1 as isize) as usize;
        seen[4] += (p.outer_l >= used.0 as isize || p.outer_t >= used.1 as isize) as usize;
        seen[5] += (used.0 < 16) as usize;

        let mut base: Vec<u8> = random_bytes(stride * cap_h, &mut rng); // stale pixels
        for y in 0..cap_h {
            base[y * stride + cap_w..(y + 1) * stride].fill(SENTINEL);
        }
        let mut want = base.clone();
        reference_write(&mut want, stride, &tight, mw, mh, &p, used, clear);
        let mut got = base.clone();
        let mut dst = A8Mut { pixels: &mut got, width: cap_w, height: cap_h, stride };
        write_nine(&mut dst, A8 { pixels: &mask, width: mw, height: mh, stride: mask_stride }, &p, used, clear);
        assert_eq!(got, want, "{p:?} used {used:?} clear {clear:?}");
    }
    assert!(seen.iter().all(|&n| n > 100), "clipping regimes not all exercised: {seen:?}");
}

#[test]
fn write_nine_minimal_centre_and_full_cover() {
    // The smallest centre Skia allows (2 px) with the outer rect exactly the used rect, and the used rect inside the
    // centre only (all 255).
    let mut rng = Rng(77);
    let p = patch(3, 2, 5, 0, 0, 1, 1);
    let (mw, mh) = (p.mask_w(), p.mask_h());
    let mask = random_bytes(mw * mh, &mut rng);
    let used = ((p.outer_r) as usize, (p.outer_b) as usize);
    let mut got = vec![9u8; used.0 * used.1];
    let mut dst = A8Mut { pixels: &mut got, width: used.0, height: used.1, stride: used.0 };
    write_nine(&mut dst, A8 { pixels: &mask, width: mw, height: mh, stride: mw }, &p, used, used);
    let mut want = vec![9u8; used.0 * used.1];
    reference_write(&mut want, used.0, &mask, mw, mh, &p, used, used);
    assert_eq!(got, want);
    // Centre columns: exactly two, the left one at outer_l + cx.
    let cx = p.center_x;
    assert_eq!(got[(p.center_y) * used.0 + cx], 255);
    assert_eq!(got[(p.center_y) * used.0 + cx + 1], 255);
    assert_eq!(got[(p.center_y) * used.0 + cx + 2], mask[p.center_y * mw + cx + 1]);

    let inside = patch(4, 3, 3, -40, -40, 100, 100);
    let mut px = vec![1u8; 12 * 12];
    let mut dst = A8Mut { pixels: &mut px, width: 12, height: 12, stride: 12 };
    let mask = random_bytes(inside.mask_w() * inside.mask_h(), &mut rng);
    write_nine(
        &mut dst,
        A8 { pixels: &mask, width: inside.mask_w(), height: inside.mask_h(), stride: inside.mask_w() },
        &inside,
        (12, 12),
        (0, 0),
    );
    assert!(px.iter().all(|&b| b == 255));
}

/// [`write_nine_inverted`] equals [`write_nine`] followed by [`crate::shadow::invert_used`] exactly, on the
/// geometry of `write_nine_matches_formula_on_random_geometry` (every clipping regime, stale pixels, mask and bitmap
/// strides); the bytes past the bitmap width and the stale pixels outside the clear rect stay untouched.
#[test]
fn write_nine_inverted_matches_write_then_invert_on_random_geometry() {
    use crate::shadow::invert_used;
    let mut rng = Rng(0x1A7C_4E12);
    let (cap_w, cap_h, stride) = (110usize, 96usize, 123usize);
    // Clipping regimes seen: outer crossing left / top / right / bottom, outer missing the used rect, used < 16 wide.
    let mut seen = [0usize; 6];
    let cases = 3000;
    for _ in 0..cases {
        let m = rng.range(0, 12) as usize;
        let p = patch(
            m,
            rng.range(1, 14) as usize,
            rng.range(1, 14) as usize,
            rng.range(-60, 70),
            rng.range(-60, 70),
            rng.range(1, 40) as usize,
            rng.range(1, 40) as usize,
        );
        let (mw, mh) = (p.mask_w(), p.mask_h());
        let mask_stride = mw + rng.below(4) as usize;
        let mut mask = random_bytes(mask_stride * mh, &mut rng);
        for y in 0..mh {
            mask[y * mask_stride + mw..(y + 1) * mask_stride].fill(SENTINEL);
        }
        let mask_a8 = A8 { pixels: &mask, width: mw, height: mh, stride: mask_stride };
        let used = (rng.range(1, cap_w as isize) as usize, rng.range(1, cap_h as isize) as usize);
        let clear = (rng.range(0, cap_w as isize) as usize, rng.range(0, cap_h as isize) as usize);
        seen[0] += (p.outer_l < 0) as usize;
        seen[1] += (p.outer_t < 0) as usize;
        seen[2] += (p.outer_r > used.0 as isize) as usize;
        seen[3] += (p.outer_b > used.1 as isize) as usize;
        seen[4] += (p.outer_l >= used.0 as isize || p.outer_t >= used.1 as isize) as usize;
        seen[5] += (used.0 < 16) as usize;

        let mut base: Vec<u8> = random_bytes(stride * cap_h, &mut rng); // stale pixels
        for y in 0..cap_h {
            base[y * stride + cap_w..(y + 1) * stride].fill(SENTINEL);
        }
        let mut want = base.clone();
        let mut dst = A8Mut { pixels: &mut want, width: cap_w, height: cap_h, stride };
        write_nine(&mut dst, mask_a8, &p, used, clear);
        invert_used(&mut dst, used.0, used.1);
        let mut got = base.clone();
        let mut dst = A8Mut { pixels: &mut got, width: cap_w, height: cap_h, stride };
        write_nine_inverted(&mut dst, mask_a8, &p, used, clear);
        assert_eq!(got, want, "{p:?} used {used:?} clear {clear:?}");
        let (cw, ch) = (clear.0.max(used.0), clear.1.max(used.1));
        for y in 0..cap_h {
            for x in 0..stride {
                if x >= cw || y >= ch {
                    assert_eq!(
                        got[y * stride + x],
                        base[y * stride + x],
                        "untouched ({x}, {y}): {p:?} used {used:?} clear {clear:?}"
                    );
                }
            }
        }
    }
    assert!(seen.iter().all(|&n| n > 100), "clipping regimes not all exercised: {seen:?}");
}

#[test]
fn write_nine_inverted_minimal_centre_and_full_cover() {
    use crate::shadow::invert_used;
    // As write_nine_minimal_centre_and_full_cover: the 2 px centre with the outer rect exactly the used rect, and the
    // used rect inside the centre only (all 0 once inverted).
    let mut rng = Rng(78);
    let p = patch(3, 2, 5, 0, 0, 1, 1);
    let (mw, mh) = (p.mask_w(), p.mask_h());
    let mask = random_bytes(mw * mh, &mut rng);
    let mask_a8 = A8 { pixels: &mask, width: mw, height: mh, stride: mw };
    let used = ((p.outer_r) as usize, (p.outer_b) as usize);
    let mut got = vec![9u8; used.0 * used.1];
    let mut dst = A8Mut { pixels: &mut got, width: used.0, height: used.1, stride: used.0 };
    write_nine_inverted(&mut dst, mask_a8, &p, used, used);
    let mut want = vec![9u8; used.0 * used.1];
    let mut dst = A8Mut { pixels: &mut want, width: used.0, height: used.1, stride: used.0 };
    write_nine(&mut dst, mask_a8, &p, used, used);
    invert_used(&mut dst, used.0, used.1);
    assert_eq!(got, want);
    let cx = p.center_x;
    assert_eq!(got[(p.center_y) * used.0 + cx], 0);
    assert_eq!(got[(p.center_y) * used.0 + cx + 1], 0);
    assert_eq!(got[(p.center_y) * used.0 + cx + 2], 255 - mask[p.center_y * mw + cx + 1]);

    let inside = patch(4, 3, 3, -40, -40, 100, 100);
    let mut px = vec![1u8; 12 * 12];
    let mut dst = A8Mut { pixels: &mut px, width: 12, height: 12, stride: 12 };
    let mask = random_bytes(inside.mask_w() * inside.mask_h(), &mut rng);
    write_nine_inverted(
        &mut dst,
        A8 { pixels: &mask, width: inside.mask_w(), height: inside.mask_h(), stride: inside.mask_w() },
        &inside,
        (12, 12),
        (0, 0),
    );
    assert!(px.iter().all(|&b| b == 0));
}

// ---- whole route -----------------------------------------------------------------------------------------------

/// Compose's round-rect geometry for a square layout of `size` px: (l, t, r, b) as `createOuterShadowBitmap` passes
/// them to `drawRoundRect`, and the bitmap size.
fn compose_rect(size: f32, radius: f32, spread: f32) -> ([f32; 4], (usize, usize)) {
    let outset = radius * 2.0 + spread * 2.0;
    let (sw, sh) = (size + outset, size + outset);
    ([radius, radius, sw - radius, sh - radius], (sw.ceil() as usize, sh.ceil() as usize))
}

/// The nine-patch of Compose's round rect, or None when Skia does something else.
fn compose_patch(size: f32, radius: f32, spread: f32, corner: f32) -> Option<(NinePatch, (usize, usize))> {
    let ([l, t, r, b], used) = compose_rect(size, radius, spread);
    match plan_round_rect(l, t, r, b, corner, corner, radius) {
        RoundRectRoute::NinePatch(p) => Some((p, used)),
        _ => None,
    }
}

#[test]
fn nine_into_matches_scalar_blur_and_formula() {
    // Small σ (< 2, small_blur) and PlanGauss, odd and even windows; one builder reused across all, stale bitmap.
    let mut rng = Rng(0x5EED_0009);
    let mut builder = ShadowBuilder::new();
    let (cap_w, cap_h) = (1000usize, 1000usize);
    let stride = cap_w + 5;
    let mut px = random_bytes(stride * cap_h, &mut rng);
    let mut prev = (cap_w, cap_h);
    let mut cases = 0;
    let mut small_sigma = 0;
    let mut fractional = 0;
    for &radius in &[1.0f32, 2.625, 3.4, 10.5, 21.0, 63.0, 168.0] {
        for &(size, spread, corner) in &[
            (525.0f32, 0.0f32, 42.0f32),
            (315.0, 21.0, 10.5),
            (126.0, 21.0, 13.0),
            (400.0, -21.0, 105.0),
            (250.0, 0.0, 16.5375),
        ] {
            let Some((p, used)) = compose_patch(size, radius, spread, corner) else { continue };
            if used.0 > cap_w || used.1 > cap_h {
                continue;
            }
            small_sigma += (p.sigma < 2.0) as usize;
            fractional += (p.rect_w != p.small_w as f32) as usize;
            let small_stride = p.small_w + rng.below(7) as usize;
            let small = small_rrect(p.small_w, p.small_h, small_stride, corner, corner);
            let mut dst = A8Mut { pixels: &mut px, width: cap_w, height: cap_h, stride };
            builder.nine_into(
                A8 { pixels: &small, width: p.small_w, height: p.small_h, stride: small_stride },
                &p,
                &mut dst,
                used,
                prev,
            );

            let tight: Vec<u8> =
                (0..p.small_h).flat_map(|y| small[y * small_stride..y * small_stride + p.small_w].to_vec()).collect();
            let blurred = skia::blur_a8(&tight, p.small_w, p.small_h, p.small_w, p.sigma);
            assert_eq!((blurred.width, blurred.height, blurred.border_x), (p.mask_w(), p.mask_h(), p.border));
            for y in 0..prev.1.max(used.1) {
                for x in 0..prev.0.max(used.0) {
                    let want = if x < used.0 && y < used.1 {
                        reference_value(&blurred.pixels, blurred.width, blurred.height, &p, x as isize, y as isize)
                            .unwrap_or(0)
                    } else {
                        0
                    };
                    assert_eq!(
                        px[y * stride + x],
                        want,
                        "r {radius} size {size} spread {spread} corner {corner}: ({x}, {y})"
                    );
                }
            }
            prev = used;
            cases += 1;
        }
    }
    assert!(cases >= 25 && small_sigma >= 3, "cases {cases}, small sigma {small_sigma}");
    assert_eq!(fractional, 0, "fractional small rrects");
}

/// Compose's inner round rect (`createInnerShadowBrush`) for a square layout of `size` px: the rect inset by `spread`
/// and moved by (`ox`, `oy`), and the bitmap size `ceil(size)`.
fn compose_inner_rect(size: f32, spread: f32, ox: f32, oy: f32) -> ([f32; 4], (usize, usize)) {
    let (l, t) = (ox + spread, oy + spread);
    let (r, b) = (l.max(ox + size - spread), t.max(oy + size - spread));
    ([l, t, r, b], (size.ceil() as usize, size.ceil() as usize))
}

/// The inner shadow's nine-patch: Compose's inner geometry (inset by the spread, moved by the offset, so the outer rect
/// may cross any edge of the bitmap or miss it), `nine_into` and [`crate::shadow::invert_used`] against the scalar
/// port, the per-pixel formula and `255 − v`; one builder on a stale plane, each clear rect the previous used rect.
#[test]
fn inner_nine_matches_scalar_blur_formula_and_inversion() {
    use crate::shadow::invert_used;
    let mut rng = Rng(0x1AAE_9001);
    let mut builder = ShadowBuilder::new();
    let (cap_w, cap_h) = (600usize, 600usize);
    let stride = cap_w + 3;
    let mut px = random_bytes(stride * cap_h, &mut rng);
    let mut prev = (cap_w, cap_h);
    let (mut cases, mut crossing, mut missing) = (0, 0, 0);
    for &radius in &[2.625f32, 10.5, 21.0, 63.0] {
        for &(size, spread, corner) in
            &[(525.0f32, 0.0f32, 42.0f32), (315.0, 21.0, 10.5), (400.0, -21.0, 105.0), (250.5, 4.25, 16.5375)]
        {
            for &(ox, oy) in &[
                (0.0f32, 0.0f32),
                (21.0, 42.0),
                (-21.0, -63.0),
                (8.53, -19.69),
                (size * 0.6 + 0.3, -size * 0.35),
                (size + 400.0, 0.0),
                (0.0, -size - 400.0),
            ] {
                let ([l, t, r, b], used) = compose_inner_rect(size, spread, ox, oy);
                let RoundRectRoute::NinePatch(p) = plan_round_rect(l, t, r, b, corner, corner, radius) else {
                    continue;
                };
                let small = small_rrect(p.small_w, p.small_h, p.small_w, corner, corner);
                let mut dst = A8Mut { pixels: &mut px, width: cap_w, height: cap_h, stride };
                builder.nine_into(
                    A8 { pixels: &small, width: p.small_w, height: p.small_h, stride: p.small_w },
                    &p,
                    &mut dst,
                    used,
                    prev,
                );
                invert_used(&mut dst, used.0, used.1);

                let blurred = skia::blur_a8(&small, p.small_w, p.small_h, p.small_w, p.sigma);
                for y in 0..prev.1.max(used.1) {
                    for x in 0..prev.0.max(used.0) {
                        let want = if x < used.0 && y < used.1 {
                            255 - reference_value(
                                &blurred.pixels,
                                blurred.width,
                                blurred.height,
                                &p,
                                x as isize,
                                y as isize,
                            )
                            .unwrap_or(0)
                        } else {
                            0
                        };
                        assert_eq!(
                            px[y * stride + x],
                            want,
                            "r {radius} size {size} spread {spread} offset ({ox}, {oy}): ({x}, {y})"
                        );
                    }
                }
                let (uw, uh) = (used.0 as isize, used.1 as isize);
                crossing += (p.outer_l < 0 || p.outer_t < 0 || p.outer_r > uw || p.outer_b > uh) as usize;
                missing += (p.outer_l >= uw || p.outer_t >= uh || p.outer_r <= 0 || p.outer_b <= 0) as usize;
                prev = used;
                cases += 1;
            }
        }
    }
    assert!(cases >= 60 && crossing >= 30 && missing >= 10, "cases {cases}, crossing {crossing}, missing {missing}");
}

#[test]
fn nine_routes_allocate_nothing_after_warm_up() {
    let geometries = [
        (525.0f32, 63.0f32, 0.0f32, 42.0f32),
        (315.0, 2.625, 21.0, 10.5),
        (945.0, 168.0, 21.0, 42.0),
        (525.0, 1.0, 0.0, 13.0),
    ];
    let patches: Vec<(NinePatch, (usize, usize))> =
        geometries.iter().map(|&(s, r, sp, c)| compose_patch(s, r, sp, c).unwrap()).collect();
    let smalls: Vec<Vec<u8>> = patches
        .iter()
        .zip(&geometries)
        .map(|((p, _), g)| small_rrect(p.small_w, p.small_h, p.small_w, g.3, g.3))
        .collect();
    let cap = patches.iter().fold((0, 0), |a, (_, u)| (a.0.max(u.0), a.1.max(u.1)));
    let mut px = vec![0u8; cap.0 * cap.1];
    let keep_cap = patches.iter().fold((0, 0), |a, (p, _)| (a.0.max(p.mask_w()), a.1.max(p.mask_h())));
    let mut keep = vec![0u8; keep_cap.0 * keep_cap.1];
    let mut builder = ShadowBuilder::new();
    let run = |builder: &mut ShadowBuilder, px: &mut [u8], keep: &mut [u8]| {
        for ((p, used), small) in patches.iter().zip(&smalls) {
            let small = A8 { pixels: small, width: p.small_w, height: p.small_h, stride: p.small_w };
            let mut dst = A8Mut { pixels: px, width: cap.0, height: cap.1, stride: cap.0 };
            builder.nine_into(small, p, &mut dst, *used, cap);
            let mut kept = A8Mut { pixels: keep, width: keep_cap.0, height: keep_cap.1, stride: keep_cap.0 };
            builder.nine_keep_into(small, p, &mut kept, &mut dst, *used, cap);
            place_nine(
                &mut dst,
                A8 { pixels: keep, width: keep_cap.0, height: keep_cap.1, stride: keep_cap.0 },
                p,
                *used,
                cap,
            );
        }
    };
    run(&mut builder, &mut px, &mut keep); // warm-up
    let before = allocations();
    for _ in 0..3 {
        run(&mut builder, &mut px, &mut keep);
    }
    assert_eq!(allocations() - before, 0, "allocations after warm-up");
    assert!(builder.nine.len() >= patches.iter().map(|(p, _)| p.mask_w() * p.mask_h()).max().unwrap());
}

// ---- kept small mask --------------------------------------------------------------------------------------------

/// A plane of `w × h` (stride `stride`) filled with random bytes from `seed`: the same stale contents for two writers.
fn stale_plane(w: usize, h: usize, stride: usize, seed: u32) -> Vec<u8> {
    let mut rng = Rng(seed);
    let mut v = random_bytes(stride * h, &mut rng);
    v.truncate((h - 1) * stride + w);
    v
}

/// Placing from a kept blurred small rrect equals a full `nine_into` for every round rect with the same small key:
/// drop geometries (sizes, spreads) and inner ones (offsets crossing and missing the bitmap), with the keep buffer
/// larger than the mask and strided, and one plane written over and over (each clear rect the previous used rect).
#[test]
fn place_from_kept_mask_matches_nine_into() {
    let (cap_w, cap_h) = (700usize, 700usize);
    let stride = cap_w + 9;
    let mut placed = stale_plane(cap_w, cap_h, stride, 0x0FF5_E701);
    let mut fresh = placed.clone();
    let mut keeper = ShadowBuilder::new();
    let mut reference = ShadowBuilder::new();
    let (mut keys, mut places, mut moved, mut resized) = (0, 0, 0, 0);
    for &(radius, rx, ry) in &[
        (63.0f32, 42.0f32, 42.0f32),
        (10.5, 10.5, 30.25),
        (2.625, 16.5375, 16.5375),
        (1.0, 5.0, 3.0),
        (21.0, 13.0, 13.0),
    ] {
        // Geometries: drop shadows of several sizes and spreads, then inner shadows of several offsets.
        let mut rects: Vec<([f32; 4], (usize, usize))> = Vec::new();
        for &size in &[525.0f32, 315.0, 400.5, 600.0] {
            for &spread in &[0.0f32, 21.0, -10.5] {
                rects.push((compose_rect(size, radius, spread).0, compose_rect(size, radius, spread).1));
            }
            for &(ox, oy) in &[
                (0.0f32, 0.0f32),
                (21.0, -42.0),
                (8.53, 19.69),
                (size * 0.7, -size * 0.4),
                (size + 300.0, 0.0),
                (-3.5, -size - 300.0),
            ] {
                rects.push(compose_inner_rect(size, 10.5, ox, oy));
            }
        }
        let mut kept: Option<(SmallMaskKey, Vec<u8>, usize)> = None;
        let mut prev = (cap_w, cap_h);
        let mut first_outer = None;
        for ([l, t, r, b], used) in rects {
            if used.0 > cap_w || used.1 > cap_h {
                continue;
            }
            let RoundRectRoute::NinePatch(p) = plan_round_rect(l, t, r, b, rx, ry, radius) else { continue };
            let small = small_rrect(p.small_w, p.small_h, p.small_w + 3, p.rx, p.ry);
            let small_a8 = A8 { pixels: &small, width: p.small_w, height: p.small_h, stride: p.small_w + 3 };
            let mut dst = A8Mut { pixels: &mut fresh, width: cap_w, height: cap_h, stride };
            reference.nine_into(small_a8, &p, &mut dst, used, prev);
            let mut dst = A8Mut { pixels: &mut placed, width: cap_w, height: cap_h, stride };
            match &kept {
                None => {
                    // A keep buffer wider and taller than the mask, padding = SENTINEL.
                    let (kw, kh, ks) = (p.mask_w() + 5, p.mask_h() + 2, p.mask_w() + 11);
                    let mut keep = vec![SENTINEL; ks * kh];
                    let mut keep_a8 = A8Mut { pixels: &mut keep, width: kw, height: kh, stride: ks };
                    keeper.nine_keep_into(small_a8, &p, &mut keep_a8, &mut dst, used, prev);
                    for y in 0..kh {
                        for x in 0..ks {
                            if x >= p.mask_w() || y >= p.mask_h() {
                                assert_eq!(keep[y * ks + x], SENTINEL, "keep padding ({x}, {y})");
                            }
                        }
                    }
                    kept = Some((p.small_key(), keep, ks));
                    first_outer = Some((p.outer_l, p.outer_t, p.outer_r - p.outer_l, p.outer_b - p.outer_t));
                    keys += 1;
                }
                Some((key, keep, ks)) => {
                    assert_eq!(p.small_key(), *key, "the rect's position and size are not in the key");
                    let kh = keep.len() / ks;
                    place_nine(
                        &mut dst,
                        A8 { pixels: keep, width: p.mask_w() + 5, height: kh, stride: *ks },
                        &p,
                        used,
                        prev,
                    );
                    let (ol, ot, ow, oh) = first_outer.unwrap();
                    moved += (p.outer_l != ol || p.outer_t != ot) as usize;
                    resized += (p.outer_r - p.outer_l != ow || p.outer_b - p.outer_t != oh) as usize;
                    places += 1;
                }
            }
            assert!(placed == fresh, "radius {radius} radii ({rx}, {ry}) rect ({l}, {t}, {r}, {b})");
            prev = used;
        }
    }
    assert!(
        keys == 5 && places >= 80 && moved >= 60 && resized >= 30,
        "keys {keys}, places {places}, moved {moved}, resized {resized}"
    );
}

/// The inverted twins ([`ShadowBuilder::nine_inverted_into`], [`ShadowBuilder::nine_keep_inverted_into`],
/// [`place_nine_inverted`]) equal their plain writers followed by [`crate::shadow::invert_used`], exactly, on
/// Compose's inner geometries (offsets crossing and missing the bitmap) and drop ones, each on its own stale strided
/// plane written over and over (each clear rect the previous used rect); the inverted keep twin keeps the same
/// (not inverted) blurred small rrect, with the keep padding untouched.
#[test]
fn inverted_twins_match_plain_then_invert() {
    use crate::shadow::invert_used;
    let (cap_w, cap_h) = (640usize, 640usize);
    let stride = cap_w + 7;
    let seed = 0x1A7E_7001;
    // Planes: [plain + invert_used, inverted] for nine_into, nine_keep_into and place_nine.
    let mut planes: Vec<[Vec<u8>; 2]> = (0..3)
        .map(|i| [stale_plane(cap_w, cap_h, stride, seed + i), stale_plane(cap_w, cap_h, stride, seed + i)])
        .collect();
    let (mut plain, mut inverted) = (ShadowBuilder::new(), ShadowBuilder::new());
    let (mut cases, mut crossing, mut missing) = (0, 0, 0);
    for &(radius, rx, ry) in
        &[(63.0f32, 42.0f32, 42.0f32), (10.5, 10.5, 30.25), (2.625, 16.5375, 16.5375), (1.0, 5.0, 3.0)]
    {
        let mut rects: Vec<([f32; 4], (usize, usize))> = Vec::new();
        for &size in &[525.0f32, 315.0, 400.5] {
            rects.push(compose_rect(size, radius, 0.0));
            for &(ox, oy) in &[
                (0.0f32, 0.0f32),
                (21.0, -42.0),
                (8.53, 19.69),
                (size * 0.7, -size * 0.4),
                (size + 300.0, 0.0),
                (-3.5, -size - 300.0),
            ] {
                rects.push(compose_inner_rect(size, 10.5, ox, oy));
            }
        }
        let mut prev = (cap_w, cap_h);
        let mut keep_pair: Option<(Vec<u8>, usize, usize, usize)> = None;
        for ([l, t, r, b], used) in rects {
            if used.0 > cap_w || used.1 > cap_h {
                continue;
            }
            let RoundRectRoute::NinePatch(p) = plan_round_rect(l, t, r, b, rx, ry, radius) else { continue };
            let small = small_rrect(p.small_w, p.small_h, p.small_w + 2, p.rx, p.ry);
            let small_a8 = A8 { pixels: &small, width: p.small_w, height: p.small_h, stride: p.small_w + 2 };
            let what = format!("radius {radius} radii ({rx}, {ry}) rect ({l}, {t}, {r}, {b})");

            // nine_into.
            let [a, b2] = &mut planes[0];
            let mut dst = A8Mut { pixels: a, width: cap_w, height: cap_h, stride };
            plain.nine_into(small_a8, &p, &mut dst, used, prev);
            invert_used(&mut dst, used.0, used.1);
            let mut dst = A8Mut { pixels: b2, width: cap_w, height: cap_h, stride };
            inverted.nine_inverted_into(small_a8, &p, &mut dst, used, prev);
            assert!(planes[0][0] == planes[0][1], "nine_inverted_into: {what}");

            // nine_keep_into: keep buffers wider and taller than the mask, strided, padding = SENTINEL.
            let (kw, kh, ks) = (p.mask_w() + 3, p.mask_h() + 2, p.mask_w() + 9);
            let (mut keep_a, mut keep_b) = (vec![SENTINEL; ks * kh], vec![SENTINEL; ks * kh]);
            let [a, b2] = &mut planes[1];
            let mut dst = A8Mut { pixels: a, width: cap_w, height: cap_h, stride };
            plain.nine_keep_into(
                small_a8,
                &p,
                &mut A8Mut { pixels: &mut keep_a, width: kw, height: kh, stride: ks },
                &mut dst,
                used,
                prev,
            );
            invert_used(&mut dst, used.0, used.1);
            let mut dst = A8Mut { pixels: b2, width: cap_w, height: cap_h, stride };
            inverted.nine_keep_inverted_into(
                small_a8,
                &p,
                &mut A8Mut { pixels: &mut keep_b, width: kw, height: kh, stride: ks },
                &mut dst,
                used,
                prev,
            );
            assert!(planes[1][0] == planes[1][1], "nine_keep_inverted_into: {what}");
            assert!(keep_a == keep_b, "kept mask: {what}");
            for y in 0..kh {
                for x in 0..ks {
                    if x >= p.mask_w() || y >= p.mask_h() {
                        assert_eq!(keep_b[y * ks + x], SENTINEL, "keep padding ({x}, {y}): {what}");
                    }
                }
            }

            // place_nine from the first kept mask of this small key.
            let (keep, kw, kh, ks) = keep_pair.get_or_insert((keep_b, kw, kh, ks));
            let kept = A8 { pixels: keep, width: *kw, height: *kh, stride: *ks };
            let [a, b2] = &mut planes[2];
            let mut dst = A8Mut { pixels: a, width: cap_w, height: cap_h, stride };
            place_nine(&mut dst, kept, &p, used, prev);
            invert_used(&mut dst, used.0, used.1);
            let mut dst = A8Mut { pixels: b2, width: cap_w, height: cap_h, stride };
            place_nine_inverted(&mut dst, kept, &p, used, prev);
            assert!(planes[2][0] == planes[2][1], "place_nine_inverted: {what}");

            let (uw, uh) = (used.0 as isize, used.1 as isize);
            crossing += (p.outer_l < 0 || p.outer_t < 0 || p.outer_r > uw || p.outer_b > uh) as usize;
            missing += (p.outer_l >= uw || p.outer_t >= uh || p.outer_r <= 0 || p.outer_b <= 0) as usize;
            prev = used;
            cases += 1;
        }
    }
    assert!(cases >= 60 && crossing >= 30 && missing >= 10, "cases {cases}, crossing {crossing}, missing {missing}");
}

/// The small key changes with every input of the blurred small rrect (either radius, even below a pixel, so the
/// rect stays; the blur's sigma) and with nothing else (the rect's edges); equal keys mean equal blurred masks (a
/// radius past the 128 sigma clamp), and a nine-patch's radii are the given ones.
#[test]
fn small_key_covers_every_small_mask_input() {
    let key = |l: f32, t: f32, r: f32, b: f32, rx: f32, ry: f32, radius: f32| {
        nine(plan_round_rect(l, t, r, b, rx, ry, radius)).small_key()
    };
    let blurred = |p: &NinePatch| {
        let small = small_rrect(p.small_w, p.small_h, p.small_w, p.rx, p.ry);
        skia::blur_a8(&small, p.small_w, p.small_h, p.small_w, p.sigma).pixels
    };
    let base = (63.0f32, 63.0f32, 588.0f32, 588.0f32, 41.5f32, 30.5f32, 63.0f32);
    let (l, t, r, b, rx, ry, radius) = base;
    let k = key(l, t, r, b, rx, ry, radius);
    let p = nine(plan_round_rect(l, t, r, b, rx, ry, radius));
    assert_eq!((p.rx, p.ry), (rx, ry));
    assert_eq!((k.rect_w, k.rect_h, k.sigma), (p.rect_w.to_bits(), p.rect_h.to_bits(), p.sigma.to_bits()));

    // Edges: moved, grown, shrunk, fractional: the same key.
    for &(dl, dt, dr, db) in &[
        (10.0f32, 0.0f32, 10.0f32, 0.0f32),
        (-700.5, 33.25, -700.5, 33.25),
        (0.0, 0.0, 400.0, 0.0),
        (0.3, 0.7, -100.2, 250.9),
    ] {
        assert_eq!(key(l + dl, t + dt, r + dr, b + db, rx, ry, radius), k, "edges moved by ({dl}, {dt}, {dr}, {db})");
    }
    // Each radius: one ulp (same rect), a sub-pixel step (same rect), a pixel step (new rect).
    for step in [|v: f32| v.next_up(), |v: f32| v + 0.25, |v: f32| v + 1.0] {
        let kx = key(l, t, r, b, step(rx), ry, radius);
        let ky = key(l, t, r, b, rx, step(ry), radius);
        assert_ne!(kx, k);
        assert_ne!(ky, k);
        assert_ne!(kx.rx, k.rx);
        assert_ne!(ky.ry, k.ry);
    }
    assert_eq!(key(l, t, r, b, rx + 0.25, ry, radius).rect_w, k.rect_w, "a sub-pixel radius step keeps the rect");
    // The blur radius: every step that changes sigma changes the key; a step that keeps sigma keeps the mask.
    let mut changed = 0;
    for i in 1..200 {
        let rad = radius + i as f32 * 0.01;
        let q = nine(plan_round_rect(l, t, r, b, rx, ry, rad));
        if q.sigma != p.sigma {
            assert_ne!(q.small_key(), k, "radius {rad}");
            changed += 1;
        } else {
            assert_eq!(q.small_key(), k);
        }
    }
    assert!(changed > 150, "sigma changed {changed} times");

    // Past the sigma clamp two radii give one sigma, one key and one blurred mask.
    let big = (0.0f32, 0.0f32, 3000.0f32, 3000.0f32, 5.0f32, 5.0f32);
    let p1 = nine(plan_round_rect(big.0, big.1, big.2, big.3, big.4, big.5, 1000.0));
    let p2 = nine(plan_round_rect(big.0 + 7.0, big.1, big.2 + 50.0, big.3, big.4, big.5, 1100.0));
    assert_eq!(p1.small_key(), p2.small_key());
    assert!(blurred(&p1) == blurred(&p2));
    // And a changed key over the base: a different blurred mask.
    let q = nine(plan_round_rect(l, t, r, b, rx + 0.25, ry, radius));
    assert!(blurred(&q) != blurred(&p));
}

// ---- geometry --------------------------------------------------------------------------------------------------

fn nine(route: RoundRectRoute) -> NinePatch {
    match route {
        RoundRectRoute::NinePatch(p) => p,
        other => panic!("expected a nine-patch, got {other:?}"),
    }
}

#[test]
fn plan_hand_computed() {
    // RoundedCornerShape(16.dp) on 200 dp, radius 24 dp, spread 0, density 2.625: size 525, r 63, corner 42.
    let m = border_for_radius(63.0);
    let p = nine(plan_round_rect(63.0, 63.0, 588.0, 588.0, 42.0, 42.0, 63.0));
    let w = 2 * (42 + m) + 1;
    assert_eq!((p.small_w, p.small_h, p.border), (w, w, m));
    assert_eq!(p.sigma, skia::mask_blur_sigma_from_radius(63.0));
    let m = m as isize;
    assert_eq!((p.outer_l, p.outer_t, p.outer_r, p.outer_b), (63 - m, 63 - m, 588 + m, 588 + m));
    assert_eq!((p.center_x, p.center_y), (2 * m as usize + 42, 2 * m as usize + 42));

    // Fractional edges and radius, negative coordinates, elliptical corners (W from rx, H from ry).
    let m = border_for_radius(10.5);
    let p = nine(plan_round_rect(-10.3, -0.5, 200.2, 300.0, 10.5, 30.25, 10.5));
    assert_eq!((p.small_w, p.small_h), (2 * (11 + m) + 1, 2 * (31 + m) + 1));
    let m = m as isize;
    assert_eq!((p.outer_l, p.outer_t, p.outer_r, p.outer_b), (-11 - m, -1 - m, 201 + m, 300 + m));
    assert_eq!((p.center_x as isize, p.center_y as isize), (2 * m + 11, 2 * m + 31));

    // An inverted rect is sorted first.
    assert_eq!(
        plan_round_rect(200.2, 300.0, -10.3, -0.5, 10.5, 30.25, 10.5),
        plan_round_rect(-10.3, -0.5, 200.2, 300.0, 10.5, 30.25, 10.5)
    );

    // σ < 2 (small_blur's radius) and σ clamped to 128.
    let p = nine(plan_round_rect(0.0, 0.0, 100.0, 100.0, 5.0, 5.0, 1.0));
    assert!(p.sigma < 2.0);
    assert_eq!(p.border, skia::SmallBlurFactors::new(p.sigma).radius);
    let p = nine(plan_round_rect(0.0, 0.0, 3000.0, 3000.0, 5.0, 5.0, 1000.0));
    assert_eq!(p.sigma, 128.0);
    assert_eq!(p.border, skia::PlanGauss::new(128.0).border);
}

#[test]
fn plan_routes_to_skia() {
    let s = RoundRectRoute::Skia;
    // drawRoundRect draws a rect unless both radii are > 0; NaN fails `> 0`; an infinite radius becomes a rect.
    assert_eq!(plan_round_rect(0.0, 0.0, 100.0, 100.0, 0.0, 10.0, 10.0), s);
    assert_eq!(plan_round_rect(0.0, 0.0, 100.0, 100.0, 10.0, 0.0, 10.0), s);
    assert_eq!(plan_round_rect(0.0, 0.0, 100.0, 100.0, f32::NAN, 10.0, 10.0), s);
    assert_eq!(plan_round_rect(0.0, 0.0, 100.0, 100.0, f32::INFINITY, 10.0, 10.0), s);
    // No mask filter (radius ≤ 0 or NaN).
    assert_eq!(plan_round_rect(0.0, 0.0, 100.0, 100.0, 10.0, 10.0, 0.0), s);
    assert_eq!(plan_round_rect(0.0, 0.0, 100.0, 100.0, 10.0, 10.0, f32::NAN), s);
    // Empty and non-finite rects.
    assert_eq!(plan_round_rect(5.0, 0.0, 5.0, 100.0, 1.0, 1.0, 10.0), s);
    assert_eq!(plan_round_rect(0.0, 0.0, f32::INFINITY, 100.0, 1.0, 1.0, 10.0), s);
    // Over Skia's 32767 limit (Skia would draw the path; it is left to Skia): an edge, or the width.
    assert_eq!(plan_round_rect(0.0, 0.0, 32768.0, 100.0, 1.0, 1.0, 1.0), s);
    assert_eq!(plan_round_rect(-40000.0, 0.0, 0.0, 100.0, 1.0, 1.0, 1.0), s);
    assert_eq!(plan_round_rect(-16384.0, 0.0, 16385.0, 100.0, 1.0, 1.0, 1.0), s);
    assert!(matches!(plan_round_rect(0.0, 0.0, 32767.0, 100.0, 1.0, 1.0, 1.0), RoundRectRoute::NinePatch(_)));
}

#[test]
fn plan_set_rect_xy_scaling_and_pills() {
    // A pill: radius exactly h/2 on a wider rect is simple, not an oval, and H > height: full mask.
    assert_eq!(set_rect_xy(0.0, 0.0, 300.0, 100.0, 50.0, 50.0).kind, RRectType::Simple);
    assert_eq!(plan_round_rect(0.0, 0.0, 300.0, 100.0, 50.0, 50.0, 10.0), RoundRectRoute::FullMask);
    // Scaling: 2·80 > 100, scale = min(300 / 160, 100 / 160) in float, both radii times it.
    let rr = set_rect_xy(0.0, 0.0, 300.0, 100.0, 80.0, 80.0);
    let scale = (100.0f32 / 160.0f32).min(300.0f32 / 160.0f32);
    assert_eq!((rr.rx, rr.ry, rr.kind), (80.0 * scale, 80.0 * scale, RRectType::Simple));
    assert_eq!(plan_round_rect(0.0, 0.0, 300.0, 100.0, 80.0, 80.0, 10.0), RoundRectRoute::FullMask);
    // Elliptical scaling: x reaches half the width, y does not: simple, full mask.
    let rr = set_rect_xy(0.0, 0.0, 300.0, 100.0, 200.0, 10.0);
    assert_eq!((rr.rx, rr.ry, rr.kind), (150.0, 7.5, RRectType::Simple));
    assert_eq!(plan_round_rect(0.0, 0.0, 300.0, 100.0, 200.0, 10.0, 10.0), RoundRectRoute::FullMask);

    // A circle whose f32 edges round: width = fl(fl(l + d) − l) may be above, at or below d = 2·r. Above: simple;
    // at: oval; below: scaled to an oval or, when the scaled radius rounds below width / 2, simple. Full mask either
    // way (an oval is drawn as SkPath::Oval through filterPath).
    let (mut simple, mut oval) = (0, 0);
    for i in 0..2000 {
        let l = 0.1f32 + i as f32 * 0.37;
        let r = 61.3f32 + (i % 7) as f32 * 0.001;
        let right = l + 2.0 * r;
        let rr = set_rect_xy(l, l, right, right, r, r);
        let route = plan_round_rect(l, l, right, right, r, r, 10.5);
        match rr.kind {
            RRectType::Oval => {
                oval += 1;
                assert_eq!(route, RoundRectRoute::FullMask);
            }
            RRectType::Simple => {
                simple += 1;
                assert!(rr.rx < rr.width() * 0.5);
                assert_eq!(route, RoundRectRoute::FullMask);
            }
            k => panic!("{k:?}"),
        }
    }
    assert!(simple > 0 && oval > 0, "simple {simple}, oval {oval}");
}

#[test]
fn plan_width_threshold_is_f32_width() {
    let m = border_for_radius(10.5);
    let w = (2 * (4 + m) + 1) as f32;
    let tall = 1000.0;
    // width == W: full mask; one ulp more: nine-patch.
    assert_eq!(plan_round_rect(0.0, 0.0, w, tall, 4.0, 4.0, 10.5), RoundRectRoute::FullMask);
    let p = nine(plan_round_rect(0.0, 0.0, w.next_up(), tall, 4.0, 4.0, 10.5));
    assert_eq!(p.outer_r - p.outer_l, w as isize + 1 + 2 * m as isize); // ceil(W + ulp) = W + 1
    // The test is on R − L, not on the rounded-out width: [0.5, W + 0.5] has width W (full mask) though it covers
    // W + 1 pixels; [0.25, W + 0.5] has width W + 0.25 and the 2-pixel centre.
    assert_eq!(plan_round_rect(0.5, 0.0, w + 0.5, tall, 4.0, 4.0, 10.5), RoundRectRoute::FullMask);
    let p = nine(plan_round_rect(0.25, 0.0, w + 0.5, tall, 4.0, 4.0, 10.5));
    let inner_l = p.outer_l + p.center_x as isize;
    let inner_r = p.outer_r + p.center_x as isize + 1 - p.mask_w() as isize;
    assert_eq!(inner_r - inner_l, 2);
    // Same in y, with x fitting.
    assert_eq!(plan_round_rect(0.0, 0.0, tall, w, 4.0, 4.0, 10.5), RoundRectRoute::FullMask);
    assert!(matches!(plan_round_rect(0.0, 0.0, tall, w.next_up(), 4.0, 4.0, 10.5), RoundRectRoute::NinePatch(_)));
    // The float subtraction decides: edges whose exact difference exceeds W can still subtract to W in f32.
    let l = 1000.3f32;
    let r = l + w; // rounded
    let route = plan_round_rect(l, 0.0, r, tall, 4.0, 4.0, 10.5);
    assert_eq!(matches!(route, RoundRectRoute::NinePatch(_)), r - l > w, "{l} {r} {}", r - l);
}

#[test]
fn plan_compose_geometry_sweep() {
    // Compose's round-rect call at density 2.625 over the test matrix's sizes, radii, spreads and corners. A
    // nine-patch never has scaled radii (the caller draws the small rrect with the original ones), its centre is at
    // least 2 px, and both routes occur. Never Skia: the circles (corner = size / 2) are ovals or simple, full masks.
    let d = 2.625f32;
    let (mut nine_count, mut full, mut skia_count) = (0, 0, 0);
    for size_dp in [48.0f32, 100.0, 120.0, 200.0, 360.0] {
        let size = (size_dp * d).round();
        for radius_dp in [1.0f32, 4.0, 24.0, 64.0] {
            let radius = radius_dp * d;
            for spread_dp in [0.0f32, 8.0, -8.0] {
                let spread = spread_dp * d;
                for corner in [4.0f32 * d, 16.0 * d, 40.0 * d, 6.3 * d, size / 2.0] {
                    let ([l, t, r, b], _) = compose_rect(size, radius, spread);
                    match plan_round_rect(l, t, r, b, corner, corner, radius) {
                        RoundRectRoute::NinePatch(p) => {
                            nine_count += 1;
                            let rr = set_rect_xy(l, t, r, b, corner, corner);
                            assert_eq!((rr.rx, rr.ry), (corner, corner));
                            assert_eq!((p.rx, p.ry), (corner, corner));
                            let m = border_for_radius(radius);
                            assert_eq!(p.small_w, 2 * (corner.ceil() as usize + m) + 1);
                            assert!((p.small_w as f32) < r - l);
                            assert!(p.outer_r - p.outer_l - p.mask_w() as isize >= 1);
                            assert_eq!(
                                (p.outer_l, p.outer_r),
                                (l.floor() as isize - m as isize, r.ceil() as isize + m as isize)
                            );
                        }
                        RoundRectRoute::FullMask => full += 1,
                        RoundRectRoute::Skia => skia_count += 1,
                    }
                }
            }
        }
    }
    assert!(nine_count > 50 && full > 50 && skia_count == 0, "nine {nine_count}, full {full}, skia {skia_count}");
}

#[test]
fn packed_route() {
    assert_eq!(RoundRectRoute::Skia.packed(), 0);
    assert_eq!(RoundRectRoute::FullMask.packed(), 1);
    let unpack = |v: i64| (f32::from_bits(v as u32), f32::from_bits((v >> 32) as u32));
    let p = nine(plan_round_rect(0.0, 0.0, 32767.0, 32000.0, 1.3, 9000.0, 1.0));
    let v = RoundRectRoute::NinePatch(p).packed();
    assert!(v != 0 && v != 1);
    assert_eq!(unpack(v), (p.rect_w, p.rect_h));
    assert!(p.rect_h > 18000.0);
    // The smallest small rrect still packs to neither 0 nor 1.
    let tiny = NinePatch { rect_w: 3.0, rect_h: 3.0, ..nine(plan_round_rect(0.0, 0.0, 100.0, 100.0, 1.0, 1.0, 1.0)) };
    assert!(RoundRectRoute::NinePatch(tiny).packed() > 1);
}

// ---- ovals -----------------------------------------------------------------------------------------------------

#[test]
fn ovals_are_full_masks() {
    // CircleShape (radius = half the side) with spread 0 or < 0 (the rect shrinks, the radius stays): an oval after
    // setRectXY. Skia blurs the whole mask of SkPath::Oval; no 32767 limit on that path. Spread > 0: simple.
    let d = 2.625f32;
    let mut ovals = 0;
    for (w, h) in [(48.0f32, 48.0f32), (120.0, 120.0), (200.0, 200.0), (360.0, 360.0), (360.0, 48.0), (48.0, 200.0)] {
        let (w, h) = ((w * d).round(), (h * d).round());
        let corner = w.min(h) / 2.0;
        for radius_dp in [1.0f32, 4.0, 24.0, 64.0] {
            let radius = radius_dp * d;
            for spread_dp in [0.0f32, -8.0, 8.0] {
                let spread = spread_dp * d;
                let outset = radius * 2.0 + spread * 2.0;
                let (l, t, r, b) = (radius, radius, w + outset - radius, h + outset - radius);
                let route = plan_round_rect(l, t, r, b, corner, corner, radius);
                if set_rect_xy(l, t, r, b, corner, corner).kind == RRectType::Oval {
                    ovals += 1;
                    assert_eq!(route, RoundRectRoute::FullMask, "{w}x{h} r {radius} s {spread}");
                } else {
                    assert_ne!(route, RoundRectRoute::Skia, "{w}x{h} r {radius} s {spread}");
                }
            }
        }
    }
    assert_eq!(plan_round_rect(0.0, 0.0, 100.0, 100.0, 50.0, 50.0, 10.0), RoundRectRoute::FullMask);
    assert_eq!(plan_round_rect(0.0, 0.0, 100.0, 100.0, 80.0, 80.0, 10.0), RoundRectRoute::FullMask);
    assert_eq!(plan_round_rect(0.0, 0.0, 40000.0, 40000.0, 20000.0, 20000.0, 10.0), RoundRectRoute::FullMask);
    // Still no mask filter at radius 0.
    assert_eq!(plan_round_rect(0.0, 0.0, 100.0, 100.0, 50.0, 50.0, 0.0), RoundRectRoute::Skia);
    assert!(ovals >= 24, "ovals {ovals}");
}
