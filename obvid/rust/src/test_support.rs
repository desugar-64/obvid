// Copyright 2026, Serhii Yaremych (desugar-64) and the Obvid project contributors
// SPDX-License-Identifier: Apache-2.0

//! Helpers shared by the host tests (test builds only): a deterministic generator, the inputs several test files
//! use, and the test binary's allocation counter.

use crate::skia::mask_blur_sigma_from_radius;

/// xorshift32, deterministic.
pub struct Rng(pub u32);

impl Rng {
    pub fn next(&mut self) -> u32 {
        let mut x = self.0;
        x ^= x << 13;
        x ^= x >> 17;
        x ^= x << 5;
        self.0 = x;
        x
    }

    pub fn below(&mut self, n: u32) -> u32 {
        self.next() % n
    }

    pub fn range(&mut self, lo: isize, hi: isize) -> isize {
        lo + self.below((hi - lo + 1) as u32) as isize
    }
}

/// Random masks of several kinds: uniform bytes, mostly 0/255 with a few edge values (like a rasterized
/// shape), and all-255.
pub fn random_mask(width: usize, height: usize, stride: usize, kind: u32, rng: &mut Rng) -> Vec<u8> {
    let mut m = vec![0xA5u8; stride * height.max(1)]; // padding bytes must be ignored
    for y in 0..height {
        for x in 0..width {
            m[y * stride + x] = match kind {
                0 => rng.next() as u8,
                1 => match rng.below(10) {
                    0..=3 => 0,
                    4..=7 => 255,
                    _ => rng.next() as u8,
                },
                _ => 255,
            };
        }
    }
    m
}

/// Sigmas from 2 to 128: from real radii, near parity changes of the window, and the extremes.
pub fn test_sigmas() -> Vec<f64> {
    let mut s: Vec<f64> = vec![
        2.0, 2.1, 2.5, 3.0, 3.2, 4.0, 4.5, 5.3, 6.0, 7.7, 8.0, 10.0, 12.7, 16.0, 23.4, 32.0, 47.5, 64.0, 90.0, 127.9,
        128.0,
    ];
    // Compose radii in px on a 2.625 density device: 1, 2, 4, 8, 16, 24, 64 dp.
    for dp in [1.0f32, 2.0, 4.0, 8.0, 16.0, 24.0, 64.0] {
        s.push(mask_blur_sigma_from_radius(dp * 2.625));
    }
    // For each window 4..=40 and a few large ones, a sigma in its middle.
    for d in (4..=40).chain([63, 64, 100, 101, 200, 201, 240, 241]) {
        let c = 4.0 / (3.0 * (2.0 * std::f64::consts::PI).sqrt());
        s.push((d as f64) * c);
    }
    s.retain(|&x| (2.0..=128.0).contains(&x));
    s
}

/// `div255((255 − m)·d + 255·m)` written out independently: round((255 − m)·d / 255 + m) with Skia's rounding.
pub fn reference_src_over(d: u32, m: u32) -> u32 {
    let prod = (255 - m) * d + 255 * m;
    ((prod + 128) * 257) >> 16
}

/// Skia's A8 srcover mask blit (`SkA8_Blitter::blitMask`, black paint), written out per pixel: the mask (`mw × mh`,
/// tightly packed) at (`x`, `y`), clipped to the `w × h` destination.
#[allow(clippy::too_many_arguments)]
pub fn blit_mask_a8(dst: &mut [u8], w: usize, h: usize, mask: &[u8], mw: usize, mh: usize, x: isize, y: isize) {
    for my in 0..mh {
        for mx in 0..mw {
            let (dx, dy) = (x + mx as isize, y + my as isize);
            if dx >= 0 && dy >= 0 && (dx as usize) < w && (dy as usize) < h {
                let d = &mut dst[dy as usize * w + dx as usize];
                *d = reference_src_over(*d as u32, mask[my * mw + mx] as u32) as u8;
            }
        }
    }
}

/// Counts the allocations (alloc and realloc calls) of the current thread, so that tests running in parallel do not
/// disturb each other's counts.
struct CountingAllocator;

thread_local! {
    static ALLOCATIONS: std::cell::Cell<usize> = const { std::cell::Cell::new(0) };
}

// SAFETY: forwards every call to the system allocator unchanged; the counter is a const-initialised thread local
// without a destructor, so touching it never allocates.
unsafe impl std::alloc::GlobalAlloc for CountingAllocator {
    unsafe fn alloc(&self, layout: std::alloc::Layout) -> *mut u8 {
        ALLOCATIONS.with(|c| c.set(c.get() + 1));
        // SAFETY: the caller's contract, passed on.
        unsafe { std::alloc::System.alloc(layout) }
    }

    unsafe fn dealloc(&self, ptr: *mut u8, layout: std::alloc::Layout) {
        // SAFETY: the caller's contract, passed on.
        unsafe { std::alloc::System.dealloc(ptr, layout) }
    }

    unsafe fn realloc(&self, ptr: *mut u8, layout: std::alloc::Layout, new_size: usize) -> *mut u8 {
        ALLOCATIONS.with(|c| c.set(c.get() + 1));
        // SAFETY: the caller's contract, passed on.
        unsafe { std::alloc::System.realloc(ptr, layout, new_size) }
    }
}

#[global_allocator]
static ALLOCATOR: CountingAllocator = CountingAllocator;

/// Allocations (alloc and realloc calls) of the current thread so far.
pub fn allocations() -> usize {
    ALLOCATIONS.with(|c| c.get())
}
