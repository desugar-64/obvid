// Copyright 2026, Serhii Yaremych (desugar-64) and the Obvid project contributors
// SPDX-License-Identifier: Apache-2.0

//! The hole of an inner shadow's bitmap: an axis-aligned rect of pixels that are all 0. The final draw fills the
//! outline with the bitmap as a shader, so a pixel whose shader value is 0 blends a transparent source; under a blend
//! mode that keeps the destination for one, the draw may leave the hole out. Conservative: every pixel inside the hole
//! is 0, zeros outside it are allowed.
//!
//! - [`invert_used_hole`]: the full-mask routes' inversion, which finds each row's run of 0s around the seed column
//!   during the pass ([`a8::invert_row_zero_run`]: whether each 64-byte step was all 255 comes with its inversion, and
//!   only the ends of the run are read back), then the rect from those runs.
//! - [`zero_hole`]: the same rect read from an already inverted bitmap (the reference of [`invert_used_hole`]).
//! - [`nine_hole`]: read from the nine-patch's blurred small mask (a fraction of the bitmap's size) and mapped through
//!   the stretch: the inverted nine-patch is 0 exactly where the plain one is 255, which is the filled centre and the
//!   255s of the mask, each repeated over the pixels its row and column stretch to. The rect in the mask
//!   ([`nine_mask_hole`]) does not depend on the placement, so a kept mask's rect is found once and only mapped for
//!   each placement ([`map_nine_hole`]).
//!
//! Both grow the rect from a seed pixel ([`grow`]).

use alloc::vec::Vec;

use super::nine::Stretch;
use super::{A8, A8Mut};
use crate::neon::a8;

/// The pixels `[l, r) × [t, b)`; empty when `l >= r` or `t >= b`.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub struct Hole {
    pub l: usize,
    pub t: usize,
    pub r: usize,
    pub b: usize,
}

impl Hole {
    pub const EMPTY: Hole = Hole { l: 0, t: 0, r: 0, b: 0 };

    pub fn is_empty(&self) -> bool {
        self.l >= self.r || self.t >= self.b
    }

    pub fn area(&self) -> usize {
        if self.is_empty() { 0 } else { (self.r - self.l) * (self.b - self.t) }
    }

    /// The hole for the JNI caller in one non-negative `i64`: `l` in bits 0..16, `t` in 16..32, `r` in 32..48, `b` in
    /// 48..63. 0 when empty or when an edge exceeds 32767 (then no hole is reported).
    pub fn packed(&self) -> i64 {
        const MAX: usize = 0x7FFF;
        if self.is_empty() || self.r > MAX || self.b > MAX {
            return 0;
        }
        self.l as i64 | (self.t as i64) << 16 | (self.r as i64) << 32 | (self.b as i64) << 48
    }
}

/// The largest rect of 0s [`grow`] finds around (`seed_x`, `seed_y`) in `bitmap`; empty when the seed is outside it or
/// not 0. Reads only the runs of 0s around the seed column of the rows it visits, plus the byte that ends each run.
pub fn zero_hole(bitmap: A8, seed_x: usize, seed_y: usize) -> Hole {
    let A8 { pixels, width, height, stride } = bitmap;
    if seed_x >= width || seed_y >= height {
        return Hole::EMPTY;
    }
    let row = |y: usize| &pixels[y * stride..y * stride + width];
    grow(height, (seed_x, seed_y), width, |y, lo, hi| run_around(row(y), seed_x, lo, hi, 0), |h| h.area())
}

/// [`super::invert_used`] over the used rect `[0, used.0) × [0, used.1)` of `dst`, returning the [`zero_hole`] of
/// the result grown from `seed`, found from each row's run of 0s around the seed column as the row is inverted
/// ([`a8::invert_row_zero_run`]); `runs` is the scratch for those runs (cleared, grown only). Without a hole when the
/// seed lies outside the used rect (the inversion is the same). Panics if the used rect exceeds `dst`.
pub fn invert_used_hole(
    dst: &mut A8Mut,
    used: (usize, usize),
    seed: (usize, usize),
    runs: &mut Vec<Option<(usize, usize)>>,
) -> Hole {
    let (used_w, used_h) = used;
    let (sx, sy) = seed;
    if sx >= used_w || sy >= used_h {
        super::invert_used(dst, used_w, used_h);
        return Hole::EMPTY;
    }
    assert!(used_w <= dst.width && used_h <= dst.height, "used rect larger than the bitmap");
    assert!(dst.stride >= dst.width, "stride smaller than width");
    runs.clear();
    for y in 0..used_h {
        runs.push(a8::invert_row_zero_run(&mut dst.pixels[y * dst.stride..y * dst.stride + used_w], sx));
    }
    grow(used_h, seed, used_w, |y, lo, hi| runs[y].map(|(a, b)| (a.max(lo), b.min(hi))), |h| h.area())
}

/// The hole of the inverted nine-patch [`super::nine::write_nine_inverted`] writes from `mask` (the blurred small
/// mask, `stretch.mask_w() × mask_h()`) with `stretch` (centre filled), clipped to the used rect:
/// [`nine_mask_hole`] mapped by [`map_nine_hole`]. Panics as [`nine_mask_hole`].
pub fn nine_hole(mask: A8, stretch: &Stretch, used: (usize, usize)) -> Hole {
    map_nine_hole(nine_mask_hole(mask, stretch), stretch, used)
}

/// The rect of the blurred small mask `mask` (`stretch.mask_w() × mask_h()`) whose image is the hole of the inverted
/// nine-patch, in mask coordinates: [`grow`] on the mask, the seed at the stretch centre (`center_x`, `center_y`), a
/// pixel qualifying when it is 255 or is the centre pixel itself (written as the filled centre, 255 whatever the mask
/// holds there), each candidate scored by its own area. It depends on the mask's bytes and the centre only, not on
/// where the nine-patch is placed, so a kept mask's rect serves every placement ([`map_nine_hole`]). Never empty (the
/// centre pixel qualifies). Panics if `mask` is not the stretch's size or the stretch is inconsistent.
pub fn nine_mask_hole(mask: A8, stretch: &Stretch) -> Hole {
    let (mw, mh) = (stretch.mask_w(), stretch.mask_h());
    assert!(mask.width == mw && mask.height == mh && mask.stride >= mw, "mask does not match the stretch");
    assert!(stretch.is_consistent(), "inconsistent stretch");
    let (cx, cy) = (stretch.center_x, stretch.center_y);
    let row = |y: usize| &mask.pixels[y * mask.stride..y * mask.stride + mw];
    grow(
        mh,
        (cx, cy),
        mw,
        |y, lo, hi| {
            let r = row(y);
            if y == cy || r[cx] == 255 { Some((run_start(r, cx, lo, 255), run_end(r, cx + 1, hi, 255))) } else { None }
        },
        |h| h.area(),
    )
}

/// The image of the mask rect `mask_hole` ([`nine_mask_hole`] of the stretch's mask) in the inverted nine-patch of
/// `stretch`, clipped to the used rect (empty when nothing is left, or when `mask_hole` does not hold the stretch
/// centre or lies outside the mask). A mask rect `[a, b) × [c, d)` around the centre maps to
/// `[outer_l + a, outer_r − mask_w + b) × [outer_t + c, outer_b − mask_h + d)`: mask column `mx < center_x` is written
/// at `outer_l + mx`, the centre column across `[outer_l + center_x, outer_r + center_x + 1 − mask_w)`, a column after
/// it at `mx + outer_r − mask_w`; the rows likewise.
pub fn map_nine_hole(mask_hole: Hole, stretch: &Stretch, used: (usize, usize)) -> Hole {
    let (mw, mh) = (stretch.mask_w(), stretch.mask_h());
    let (cx, cy) = (stretch.center_x, stretch.center_y);
    let h = mask_hole;
    if !(h.l <= cx && cx < h.r && h.t <= cy && cy < h.b && h.r <= mw && h.b <= mh) {
        return Hole::EMPTY;
    }
    let clip = |v: isize, max: usize| v.clamp(0, max as isize) as usize;
    let hole = Hole {
        l: clip(stretch.outer_l + h.l as isize, used.0),
        t: clip(stretch.outer_t + h.t as isize, used.1),
        r: clip(stretch.outer_r - mw as isize + h.r as isize, used.0),
        b: clip(stretch.outer_b - mh as isize + h.b as isize, used.1),
    };
    if hole.is_empty() { Hole::EMPTY } else { hole }
}

impl Hole {
    /// The hole of [`Hole::packed`]'s value; None for a negative value.
    pub fn unpacked(packed: i64) -> Option<Hole> {
        if packed < 0 {
            return None;
        }
        let at = |shift: u32| ((packed >> shift) & 0xFFFF) as usize;
        Some(Hole { l: at(0), t: at(16), r: at(32), b: at(48) })
    }
}

/// Grows a rect of qualifying pixels in a grid of `rows` rows and `cols` columns from `seed` (x, y):
/// `run(y, lo, hi)` gives the run of qualifying pixels of row y around the seed column within `[lo, hi)` (which holds
/// the seed column) as `(a, b)` with `lo ≤ a ≤ seed.x < b ≤ hi`, or None when the seed column's pixel does not
/// qualify. The rect starts as the seed row's run; each step adds the row above or below it, whichever run is wider
/// (above on a tie), and narrows the columns to that run; it stops when neither row qualifies. The largest `area` seen
/// wins (the grid rect; empty when the seed pixel does not qualify). Each row is read at most once: a candidate row's
/// run stays valid when the columns narrow (it is clipped to them).
fn grow(
    rows: usize,
    seed: (usize, usize),
    cols: usize,
    mut run: impl FnMut(usize, usize, usize) -> Option<(usize, usize)>,
    area: impl Fn(&Hole) -> usize,
) -> Hole {
    let (_, sy) = seed;
    let Some((mut lo, mut hi)) = run(sy, 0, cols) else { return Hole::EMPTY };
    let (mut y0, mut y1) = (sy, sy + 1);
    let mut best = Hole { l: lo, t: y0, r: hi, b: y1 };
    let mut best_area = area(&best);
    let mut up = if y0 > 0 { run(y0 - 1, lo, hi) } else { None };
    let mut down = if y1 < rows { run(y1, lo, hi) } else { None };
    loop {
        let take_up = match (up, down) {
            (None, None) => break,
            (Some(_), None) => true,
            (None, Some(_)) => false,
            (Some(u), Some(d)) => u.1 - u.0 >= d.1 - d.0,
        };
        if take_up {
            (lo, hi) = up.unwrap_or((lo, hi));
            y0 -= 1;
            down = down.map(|(a, b)| (a.max(lo), b.min(hi)));
            up = if y0 > 0 { run(y0 - 1, lo, hi) } else { None };
        } else {
            (lo, hi) = down.unwrap_or((lo, hi));
            y1 += 1;
            up = up.map(|(a, b)| (a.max(lo), b.min(hi)));
            down = if y1 < rows { run(y1, lo, hi) } else { None };
        }
        let rect = Hole { l: lo, t: y0, r: hi, b: y1 };
        let a = area(&rect);
        if a > best_area {
            best = rect;
            best_area = a;
        }
    }
    best
}

/// The run of `v` around column `x` of `row` within `[lo, hi)`, or None when `row[x]` is not `v`.
#[inline]
fn run_around(row: &[u8], x: usize, lo: usize, hi: usize, v: u8) -> Option<(usize, usize)> {
    if row[x] != v {
        return None;
    }
    Some((run_start(row, x, lo, v), run_end(row, x + 1, hi, v)))
}

/// The smallest `a` in `[lo, from]` with `row[a..from]` all `v`.
#[inline]
fn run_start(row: &[u8], from: usize, lo: usize, v: u8) -> usize {
    from - a8::trailing_run(&row[lo..from], v)
}

/// The largest `b` in `[from, hi]` with `row[from..b]` all `v`.
#[inline]
fn run_end(row: &[u8], from: usize, hi: usize, v: u8) -> usize {
    from + a8::leading_run(&row[from..hi], v)
}

#[cfg(test)]
#[path = "hole_tests.rs"]
mod tests;
