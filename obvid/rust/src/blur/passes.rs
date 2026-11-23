// Copyright 2026, Serhii Yaremych (desugar-64) and the Obvid project contributors
// SPDX-License-Identifier: Apache-2.0
// Implements the arithmetic of Skia's SkMaskBlurFilter (BSD-3-Clause), see NOTICE.

//! The σ ≥ 2 blur in two passes over 16 lines at a time (module docs of [`crate::blur`], "Layout" and "Interior
//! skip"): the horizontal pass into the intermediate plane, the vertical pass into the destination window, and the
//! scan that runs the recurrence between skips and stores the outputs it keeps.

use core::arch::aarch64::*;

use super::{InteriorSkip, MIN_SKIP, Scratch, Store, Window, grown};
use crate::neon::LANES;
use crate::neon::a8::{src_over, src_over16};
use crate::neon::box3::{NARROW_T0_MAX_WINDOW, State, steps};
use crate::neon::flags::{FLAG_PAD, Skip, ZERO_RUN, equal_to_previous, find_skips};
use crate::neon::transpose::transpose16;
use crate::skia::PlanGauss;

/// Per-plan constants of the interior skip (module docs, "Interior skip").
#[derive(Clone, Copy)]
struct SkipPlan {
    /// Skia's sliding window L = 2·border + 1: an output's support.
    support: usize,
    /// R = a + b + c = L + 2: the support of T0, after which the state is the steady state.
    state_len: usize,
    /// W·divisor: the steady accumulator is 2³¹ + W·divisor·v.
    weight_divisor: u64,
    /// Shortest skip taken.
    min_skip: usize,
}

impl SkipPlan {
    fn new(plan: &PlanGauss, mode: InteriorSkip) -> SkipPlan {
        let support = plan.sliding_window;
        let state_len = if plan.window.is_multiple_of(2) { 3 * plan.window + 1 } else { 3 * plan.window };
        debug_assert_eq!(state_len, support + 2);
        let min_skip = if mode == InteriorSkip::Always { 1 } else { MIN_SKIP };
        SkipPlan { support, state_len, weight_divisor: plan.weight * plan.divisor, min_skip }
    }

    /// Whether to detect runs on an axis whose lines are `len` long (output `len + 2·border`): in `Auto`, when a
    /// run over the whole line (the longest possible skip, len + 1 − L outputs) would save at least `1/part` of
    /// the line's outputs.
    fn detect(&self, mode: InteriorSkip, len: usize, part: usize) -> bool {
        match mode {
            InteriorSkip::Off => false,
            InteriorSkip::Always => true,
            InteriorSkip::Auto => part * (len + 1).saturating_sub(self.support) >= len + self.support - 1,
        }
    }
}

/// The σ ≥ 2 blur of the `window` of the blurred mask: horizontal pass into `scratch.inter`, vertical pass into
/// `dst` (`dst[0]` = blurred pixel (`window.x0`, `window.y0`), rows `dst_stride` apart) with `store`.
///
/// # Safety
/// `src` holds `height` rows of `width` bytes `stride` apart; `width, height > 0`; `EVEN` / `WIDE` match the plan.
/// (The window and `dst` are checked.)
#[allow(clippy::too_many_arguments)]
pub(super) unsafe fn plan_gauss<const EVEN: bool, const WIDE: bool>(
    src: &[u8],
    width: usize,
    height: usize,
    stride: usize,
    plan: &PlanGauss,
    window: Window,
    dst: &mut [u8],
    dst_stride: usize,
    store: Store,
    scratch: &mut Scratch,
    mode: InteriorSkip,
) {
    let d = plan.window;
    assert!((4..=255).contains(&d) && d.is_multiple_of(2) == EVEN && (d > NARROW_T0_MAX_WINDOW) == WIDE);
    // W < 2³¹ so that it is a non-negative i32 multiplier (W ≤ 2³²/80 for d ≥ 4).
    let Ok(weight) = i32::try_from(plan.weight) else { panic!("weight fits i32") };
    let border = plan.border;
    let dst_w = width + 2 * border;
    let dst_h = height + 2 * border;
    let Window { x0: wx0, y0: wy0, x1: wx1, y1: wy1 } = window;
    assert!(wx0 < wx1 && wx1 <= dst_w && wy0 < wy1 && wy1 <= dst_h, "window outside the destination");
    let (ww, wh) = (wx1 - wx0, wy1 - wy0);
    assert!(
        dst_stride >= ww && super::span(wh, dst_stride, ww).is_some_and(|n| dst.len() >= n),
        "dst too short for the window"
    );
    let zeros = 3 * d + 1; // the largest tap offset: 3d (odd) or 3d + 1 (even)
    // `inter` holds the window's columns: column c is blurred column wx0 + c.
    let blocks = ww.div_ceil(LANES);
    let inter_w = blocks * LANES;
    // Horizontal steps 0..count_h (output n reads input columns ≤ n): up to the window's last 16-column block.
    // They read the source columns < src_cols, transposed in blocks up to cols16, zeros after them.
    let count_h = wx0 + inter_w;
    let src_cols = width.min(count_h);
    let cols16 = src_cols.next_multiple_of(LANES);
    let hist_cols = cols16.max(count_h);
    // Vertical steps 0..wy1 read intermediate rows < wy1: the image rows < h_rows, zero rows from `height` on.
    let count_v = wy1;
    let h_rows = height.min(wy1);
    let inter_h = zeros + count_v;

    let skip = SkipPlan::new(plan, mode);
    // Horizontal detection is cheap (on the transposed input blocks): on from 1/8. The vertical flags cost more
    // (every intermediate block, ~4 % of a blur on Apple Silicon): on from 1/3, and per row group only where the
    // horizontal pass itself skipped ≥ 1/8 of its steps (rows without flags count as "not equal", which can only
    // shorten a run). `Always` computes everything.
    let detect_h = skip.detect(mode, width, 8);
    let detect_v = skip.detect(mode, height, 3);
    // Vertical groups: 16 columns of `inter` at x = min(16g, ww − 16), or one group at 0 when ww < 16.
    let groups = blocks;
    let group_x = |g: usize| if ww < LANES { 0 } else { (g * LANES).min(ww - LANES) };
    // Vertical flags of one group: steps 0..count_v, written 16 rows at a time.
    let vf_stride = h_rows.next_multiple_of(LANES).max(count_v) + FLAG_PAD;

    let Scratch { hist, out, inter, hflags, vflags, skips, .. } = scratch;
    let hist = grown(hist, (zeros + hist_cols) * LANES);
    let out = grown(out, inter_w.max(wh) * LANES);
    let inter = grown(inter, inter_h * inter_w);
    let hflags = grown(hflags, if detect_h { hist_cols + FLAG_PAD } else { 0 });
    let vflags = grown(vflags, if detect_v { groups * vf_stride } else { 0 });

    // Zero the parts never written: the history before the row and past its last transposed block, the
    // intermediate rows above the image and below it (when the window reaches past it).
    hist[..zeros * LANES].fill(0);
    hist[(zeros + cols16) * LANES..].fill(0);
    inter[..zeros * inter_w].fill(0);
    inter[(zeros + h_rows) * inter_w..].fill(0);
    if detect_h {
        // Steps past the last transposed block read zeros: equal to the step before, except the first one when
        // the row ends exactly at a block boundary (then the step before is a real column; set per group).
        // (When the steps stop before the row's end, the flags past the last block are never used.)
        hflags[cols16..].fill(0xFF);
    }

    let hist = hist.as_mut_ptr();
    let out = out.as_mut_ptr();
    let inter = inter.as_mut_ptr();
    let hflags = hflags.as_mut_ptr();
    let vflags = vflags.as_mut_ptr();
    let src = src.as_ptr();
    let dst = dst.as_mut_ptr();
    // Where the steps before a window put their outputs (never read).
    let mut dump = [0u8; LANES];
    let dump = dump.as_mut_ptr();

    // SAFETY: the buffers were grown to the sizes above (history: zeros + hist_cols vectors; `out`: inter_w and wh
    // vectors; `inter`: inter_h rows of inter_w; flags: hist_cols and count_v plus FLAG_PAD), every source row read
    // is < h_rows ≤ height and every source column < src_cols ≤ width (the last block staged), and the window rows
    // in `dst` were checked above; the scans' taps reach at most 3d + 1 vectors / rows back, into the zeros.
    unsafe {
        let zero = vdupq_n_u8(0);
        let run_vector = |input: *const u8, in_stride: usize, run: usize| {
            if run == ZERO_RUN { zero } else { vld1q_u8(input.add(run * in_stride)) }
        };
        // Horizontal pass, 16 rows at a time.
        #[cfg(test)]
        super::tests::note_pass(0);
        let mut y0 = 0;
        while y0 < h_rows {
            let rows = LANES.min(h_rows - y0);
            let row = |l: usize| src.add((y0 + l.min(rows - 1)) * stride);

            // The rows into the history, transposed: hist[zeros + x] = column x of the 16 rows. With detection,
            // hflags[x] = 0xFF where column x equals column x − 1 in all 16 rows (column −1 = 0).
            let mut last_column = zero;
            let mut x0 = 0;
            while x0 < src_cols {
                let mut block = [zero; LANES];
                if x0 + LANES <= width {
                    for (l, v) in block.iter_mut().enumerate() {
                        *v = vld1q_u8(row(l).add(x0));
                    }
                } else {
                    // Last, partial block: staged through a zeroed 16 × 16 array (columns past the row are zero,
                    // as in the history).
                    let mut staged = [[0u8; LANES]; LANES];
                    for (l, s) in staged.iter_mut().enumerate() {
                        core::ptr::copy_nonoverlapping(row(l).add(x0), s.as_mut_ptr(), width - x0);
                    }
                    for (v, s) in block.iter_mut().zip(staged.iter()) {
                        *v = vld1q_u8(s.as_ptr());
                    }
                }
                transpose16(&mut block);
                for (i, v) in block.iter().enumerate() {
                    vst1q_u8(hist.add((zeros + x0 + i) * LANES), *v);
                }
                if detect_h {
                    vst1q_u8(hflags.add(x0), equal_to_previous(&block, last_column));
                    last_column = block[LANES - 1];
                }
                x0 += LANES;
            }
            skips.clear();
            if detect_h {
                if src_cols == width && width == cols16 && width < count_h {
                    // Step `width` reads zeros; it equals the step before iff the last column is zero in all rows.
                    *hflags.add(width) = if vmaxvq_u8(last_column) == 0 { 0xFF } else { 0 };
                }
                find_skips(hflags, count_h, skip.support, skip.state_len, skip.min_skip, skips);
            }

            // Outputs wx0.. to out[0..], one vector per position.
            let input = hist.add(zeros * LANES) as *const u8;
            let sink = Sink { out, stride: LANES, put: Put::Direct, stage: out, dump };
            scan::<EVEN, WIDE>(input, LANES, count_h, wx0, &sink, d, weight, &skip, skips);

            // The outputs back to rows: inter row zeros + y0 + l, columns 16k..16k+16 = steps wx0 + 16k.. (a block
            // inside a skip is the run's vector in every column: row l is its lane l). With detection, the vertical
            // flags of rows y0..y0 + 16 for the groups at x = 16k: row y0 + l vs the row above it over the group's
            // 16 columns (lanes of a last row group shorter than 16 are fixed after the pass).
            let inter_row = |y: usize| inter.add((zeros + y) * inter_w);
            let row_vflags = detect_v
                && (mode == InteriorSkip::Always
                    || !detect_h
                    || 8 * skips.iter().map(|s| s.to - s.from).sum::<usize>() >= count_h);
            if detect_v && !row_vflags {
                for g in 0..groups {
                    vst1q_u8(vflags.add(g * vf_stride + y0), zero);
                }
            }
            let mut next = 0;
            for k in 0..blocks {
                let (first_step, end_step) = (wx0 + k * LANES, wx0 + (k + 1) * LANES);
                while next < skips.len() && skips[next].to < end_step {
                    next += 1;
                }
                let mut block = [zero; LANES];
                if next < skips.len() && skips[next].from <= first_step {
                    let mut lanes = [0u8; LANES];
                    vst1q_u8(lanes.as_mut_ptr(), run_vector(input, LANES, skips[next].run));
                    for (v, &b) in block.iter_mut().zip(lanes.iter()) {
                        *v = vdupq_n_u8(b);
                    }
                } else {
                    for (i, v) in block.iter_mut().enumerate() {
                        *v = vld1q_u8(out.add((k * LANES + i) * LANES));
                    }
                    transpose16(&mut block);
                }
                for (l, v) in block.iter().take(rows).enumerate() {
                    vst1q_u8(inter_row(y0 + l).add(k * LANES), *v);
                }
                if row_vflags && group_x(k) == k * LANES {
                    let above = vld1q_u8(inter_row(y0).sub(inter_w).add(k * LANES));
                    vst1q_u8(vflags.add(k * vf_stride + y0), equal_to_previous(&block, above));
                }
            }
            if row_vflags && group_x(groups - 1) != (groups - 1) * LANES {
                // The last group overlaps the previous one (not 16-aligned): from the output vectors (lane l =
                // row y0 + l), lane l vs lane l − 1 over its 16 columns; lane 0 vs the row above, from `inter`.
                // (Output vectors inside a skip were stored, so `out` is complete.)
                let x = group_x(groups - 1);
                let mut acc = zero;
                for c in x..x + LANES {
                    let o = vld1q_u8(out.add(c * LANES));
                    acc = vorrq_u8(acc, veorq_u8(o, vextq_u8::<15>(o, o)));
                }
                let first_equal =
                    vmaxvq_u8(veorq_u8(vld1q_u8(inter_row(y0).add(x)), vld1q_u8(inter_row(y0).sub(inter_w).add(x))))
                        == 0;
                let f = vsetq_lane_u8::<0>(if first_equal { 0xFF } else { 0 }, vceqzq_u8(acc));
                vst1q_u8(vflags.add((groups - 1) * vf_stride + y0), f);
            }
            y0 += LANES;
        }

        if detect_v && h_rows == height {
            // Rows from `height` on are zero rows: step `height` equals the step before iff the last image row is
            // zero over the group; the rest are equal. (This also overwrites the repeated-row lanes of a last row
            // group shorter than 16. When the steps stop before `height`, the flags past them are never used.)
            for g in 0..groups {
                let x = group_x(g);
                let flags = vflags.add(g * vf_stride);
                let last = vld1q_u8(inter.add((zeros + height - 1) * inter_w + x));
                *flags.add(height) = if vmaxvq_u8(last) == 0 { 0xFF } else { 0 };
                core::ptr::write_bytes(flags.add(height + 1), 0xFF, vf_stride - height - 1);
            }
        }

        // Vertical pass, 16 columns at a time, reading inter rows in place, steps 0..wy1, window rows wy0..wy1.
        #[cfg(test)]
        super::tests::note_pass(1);
        let first = inter.add(zeros * inter_w) as *const u8;
        let lane_index = vcombine_u8(vcreate_u8(0x0706_0504_0302_0100), vcreate_u8(0x0F0E_0D0C_0B0A_0908));
        for g in 0..groups {
            skips.clear();
            if detect_v {
                find_skips(vflags.add(g * vf_stride), count_v, skip.support, skip.state_len, skip.min_skip, skips);
            }
            let x = group_x(g); // the last group overlaps the previous one
            let put = match store {
                _ if ww < LANES => Put::Narrow { len: ww, over: store == Store::SourceOver },
                Store::Replace => Put::Direct,
                // Lanes below 16g − x were blended by the previous group: a zero mask leaves them as they are.
                Store::SourceOver => Put::Over(vcgeq_u8(lane_index, vdupq_n_u8((g * LANES - x) as u8))),
            };
            let sink = Sink { out: dst.add(x), stride: dst_stride, put, stage: out, dump };
            scan::<EVEN, WIDE>(first.add(x), inter_w, count_v, wy0, &sink, d, weight, &skip, skips);
        }
    }
}

/// How a scan stores an output vector into its window.
#[derive(Clone, Copy)]
enum Put {
    /// Plain 16-byte stores, straight from the step loop.
    Direct,
    /// Staged, then Skia's srcover blit of the vector masked by these lanes (0x00 lanes: a zero mask, which
    /// leaves the byte as it is).
    Over(uint8x16_t),
    /// Staged, then only the first `len` (< 16) bytes stored: plainly, or srcover'd with `over`.
    Narrow { len: usize, over: bool },
}

/// Where a scan's kept outputs go: window row r (step `first + r`) at `out + r·stride`, with `put`. The row
/// pointers advance with `wrapping_add`: after the last row they point past it, possibly outside `dst`.
struct Sink {
    out: *mut u8,
    stride: usize,
    put: Put,
    /// Staging (one vector per window row) for every `put` but [`Put::Direct`].
    stage: *mut u8,
    /// 16 writable bytes for the outputs of the steps before the window.
    dump: *mut u8,
}

impl Sink {
    /// Stores `v` as window rows `from..to`.
    ///
    /// # Safety
    /// The rows are inside the window (`out + r·stride`, 16 bytes or `len`, writable).
    #[inline(always)]
    unsafe fn put_rows(&self, from: usize, to: usize, v: uint8x16_t) {
        // SAFETY: rows from..to of the window (caller).
        unsafe {
            let mut p = self.out.add(from * self.stride);
            match self.put {
                Put::Direct => {
                    for _ in from..to {
                        vst1q_u8(p, v);
                        p = p.wrapping_add(self.stride);
                    }
                }
                Put::Over(lanes) => {
                    let m = vandq_u8(v, lanes);
                    if vmaxvq_u8(m) == 0 {
                        return; // srcover of 0 is the identity
                    }
                    for _ in from..to {
                        vst1q_u8(p, src_over16(vld1q_u8(p), m));
                        p = p.wrapping_add(self.stride);
                    }
                }
                Put::Narrow { .. } => {
                    for r in from..to {
                        self.put_narrow(r, v);
                    }
                }
            }
        }
    }

    /// The staged rows `from..to` into the window.
    ///
    /// # Safety
    /// As [`Sink::put_rows`]; the staged rows were written.
    #[inline(always)]
    unsafe fn put_staged(&self, from: usize, to: usize) {
        // SAFETY: rows from..to of the window and of the stage (caller).
        unsafe {
            let mut p = self.out.add(from * self.stride);
            let mut s = self.stage.add(from * LANES) as *const u8;
            match self.put {
                Put::Direct => unreachable!("direct outputs are not staged"),
                Put::Over(lanes) => {
                    for _ in from..to {
                        vst1q_u8(p, src_over16(vld1q_u8(p), vandq_u8(vld1q_u8(s), lanes)));
                        p = p.wrapping_add(self.stride);
                        s = s.add(LANES);
                    }
                }
                Put::Narrow { .. } => {
                    for r in from..to {
                        self.put_narrow(r, vld1q_u8(s));
                        s = s.add(LANES);
                    }
                }
            }
        }
    }

    /// The first `len` bytes of `v` into window row `r` ([`Put::Narrow`] only).
    ///
    /// # Safety
    /// As [`Sink::put_rows`].
    #[inline(always)]
    unsafe fn put_narrow(&self, r: usize, v: uint8x16_t) {
        let Put::Narrow { len, over } = self.put else { unreachable!() };
        let mut bytes = [0u8; LANES];
        // SAFETY: a 16-byte store into a 16-byte array; the row holds `len` writable bytes (caller).
        let row = unsafe {
            vst1q_u8(bytes.as_mut_ptr(), v);
            core::slice::from_raw_parts_mut(self.out.add(r * self.stride), len)
        };
        if over {
            for (d, &m) in row.iter_mut().zip(&bytes) {
                *d = src_over(*d, m);
            }
        } else {
            row.copy_from_slice(&bytes[..len]);
        }
    }
}

/// Runs `count` steps of the three-box blur on 16 lines at once ([`steps`]: inputs, taps and outputs). The state
/// starts at zero (line start), as Skia's scan does. The outputs of steps before `first` are dropped (written to
/// `sink.dump`); step `first + r` is stored as window row r of `sink`.
///
/// `skips` (from [`find_skips`], in order): their outputs are stored as the run's vector without running the
/// recurrence, which restarts from the steady state after each (module docs, "Interior skip").
///
/// # Safety
/// Every input address and every window row above is readable / writable for 16 bytes (`len` for
/// [`Put::Narrow`]); `first ≤ count`; the stage holds `count − first` vectors.
#[allow(clippy::too_many_arguments)]
#[inline(always)]
unsafe fn scan<const EVEN: bool, const WIDE: bool>(
    input: *const u8,
    in_stride: usize,
    count: usize,
    first: usize,
    sink: &Sink,
    d: usize,
    weight: i32,
    skip: &SkipPlan,
    skips: &[Skip],
) {
    // SAFETY: the addresses the steps and stores reach are those of the contract above.
    unsafe {
        #[cfg(test)]
        super::tests::note_steps(count, skips.iter().map(|s| s.to - s.from).sum());
        let mut state = State::zero();
        // Window row r reads the input at step first + r: the steps run on the offset input, so that the output
        // pointer they are given is the window's own.
        let window_input = input.add(first * in_stride);
        let mut n = 0; // next step to run
        let mut i = 0; // next skip
        loop {
            let end = if i < skips.len() { skips[i].from } else { count };
            let before = end.min(first);
            if n < before {
                steps::<EVEN, WIDE>(&mut state, input, in_stride, sink.dump, 0, n, before, d, weight);
                n = before;
            }
            if n < end {
                let (from, to) = (n - first, end - first);
                if let Put::Direct = sink.put {
                    steps::<EVEN, WIDE>(
                        &mut state,
                        window_input,
                        in_stride,
                        sink.out,
                        sink.stride,
                        from,
                        to,
                        d,
                        weight,
                    );
                } else {
                    steps::<EVEN, WIDE>(&mut state, window_input, in_stride, sink.stage, LANES, from, to, d, weight);
                    sink.put_staged(from, to);
                }
            }
            if i == skips.len() {
                break;
            }
            let s = skips[i];
            let value = if s.run == ZERO_RUN { vdupq_n_u8(0) } else { vld1q_u8(input.add(s.run * in_stride)) };
            if s.to > first {
                sink.put_rows(s.from.max(first) - first, s.to - first, value);
            }
            state = State::steady(value, skip.weight_divisor);
            n = s.to;
            i += 1;
        }
    }
}

#[cfg(test)]
#[path = "passes_tests.rs"]
mod tests;
