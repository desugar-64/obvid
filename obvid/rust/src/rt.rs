// Copyright 2026, Serhii Yaremych (desugar-64) and the Obvid project contributors
// SPDX-License-Identifier: Apache-2.0

//! What `std` would otherwise provide to the `no_std` library: the panic handler, the global allocator, the link
//! to libc and `rust_eh_personality`. Host tests use `std`'s, so those are `cfg(not(test))`.
//!
//! Panic: on Android, `obvid panicked at <file>:<line>:<col>` (no payload, so no `core::fmt` formatting) goes
//! to bionic's abort message (the tombstone's and logcat's `Abort message: …` line); then `abort()`. Elsewhere, only
//! `abort()`. Allocation failure takes the same path: `alloc`'s default handler panics (at its own location in
//! `library/alloc/src/alloc.rs`).
//!
//! Allocator: libc `malloc` / `calloc` / `realloc` / `free`, with `posix_memalign` when the alignment is above
//! `malloc`'s guarantee or the size (the rule of `std`'s `System` allocator on Unix).
//!
//! `rust_eh_personality`: the prebuilt `core` and `alloc` are compiled for unwinding, so their unwind tables name
//! it. With `panic = "abort"` nothing unwinds through Rust frames and it is never called (Android's crash unwinder
//! reads `.eh_frame` only); it is defined so that the library has no undefined Rust symbol.

use core::alloc::{GlobalAlloc, Layout};
use core::ffi::{CStr, c_void};

// rustc links with `-nodefaultlibs`; without `std` nothing else names libc on Android.
#[cfg_attr(target_os = "android", link(name = "c"))]
unsafe extern "C" {
    #[cfg(not(test))]
    safe fn abort() -> !;
    fn malloc(size: usize) -> *mut c_void;
    fn calloc(count: usize, size: usize) -> *mut c_void;
    fn realloc(ptr: *mut c_void, size: usize) -> *mut c_void;
    fn free(ptr: *mut c_void);
    fn posix_memalign(out: *mut *mut c_void, align: usize, size: usize) -> i32;
}

#[cfg(all(not(test), target_os = "android"))]
unsafe extern "C" {
    /// bionic (API 21+): copies `msg` into the process's abort message.
    fn android_set_abort_message(msg: *const core::ffi::c_char);
}

/// Size of the panic message buffer, the NUL included.
pub(crate) const MESSAGE_CAP: usize = 256;

/// Writes `obvid panicked at <file>:<line>:<col>` (or `… at an unknown location`) into `buf`, NUL-terminated,
/// and returns it. A message longer than the buffer is cut (the end of the file path and the numbers go first).
#[cfg_attr(not(any(test, target_os = "android")), allow(dead_code))]
pub(crate) fn panic_message<'a>(buf: &'a mut [u8; MESSAGE_CAP], location: Option<(&str, u32, u32)>) -> &'a CStr {
    let mut w = Writer { buf, len: 0 };
    w.push(b"obvid panicked at ");
    match location {
        Some((file, line, column)) => {
            w.push(file.as_bytes());
            w.push(b":");
            w.push_u32(line);
            w.push(b":");
            w.push_u32(column);
        }
        None => w.push(b"an unknown location"),
    }
    let len = w.len;
    buf[len] = 0;
    // The first NUL is at `len` or before it (a NUL inside the file path ends the message there).
    CStr::from_bytes_until_nul(&buf[..=len]).unwrap_or_default()
}

/// Appends bytes to a fixed buffer, keeping the last byte for the NUL.
struct Writer<'a> {
    buf: &'a mut [u8; MESSAGE_CAP],
    len: usize,
}

impl Writer<'_> {
    fn push(&mut self, bytes: &[u8]) {
        let n = bytes.len().min(MESSAGE_CAP - 1 - self.len);
        self.buf[self.len..self.len + n].copy_from_slice(&bytes[..n]);
        self.len += n;
    }

    fn push_u32(&mut self, mut v: u32) {
        let mut digits = [0u8; 10];
        let mut i = digits.len();
        loop {
            i -= 1;
            digits[i] = b'0' + (v % 10) as u8;
            v /= 10;
            if v == 0 {
                break;
            }
        }
        self.push(&digits[i..]);
    }
}

#[cfg(not(test))]
#[panic_handler]
fn panic(info: &core::panic::PanicInfo<'_>) -> ! {
    #[cfg(target_os = "android")]
    {
        let mut buf = [0u8; MESSAGE_CAP];
        let message = panic_message(&mut buf, info.location().map(|l| (l.file(), l.line(), l.column())));
        // SAFETY: `message` is NUL-terminated; bionic copies it.
        unsafe { android_set_abort_message(message.as_ptr()) };
    }
    #[cfg(not(target_os = "android"))]
    let _ = info;
    abort()
}

// Hidden, so that the `.so` exports only the JNI functions (a `#[no_mangle]` item would be exported).
#[cfg(all(not(test), target_os = "android", target_arch = "aarch64"))]
core::arch::global_asm!(
    ".globl rust_eh_personality",
    ".hidden rust_eh_personality",
    ".type rust_eh_personality, %function",
    "rust_eh_personality:",
    "b abort",
);

#[cfg(all(not(test), not(all(target_os = "android", target_arch = "aarch64"))))]
#[unsafe(no_mangle)]
extern "C" fn rust_eh_personality() -> ! {
    abort()
}

/// The alignment of every `malloc` result: 16 on 64-bit targets (bionic, glibc, macOS), 8 on 32-bit ones.
const MIN_ALIGN: usize = if cfg!(target_pointer_width = "64") { 16 } else { 8 };

/// The global allocator over libc (module docs).
struct Malloc;

#[cfg(not(test))]
#[global_allocator]
static ALLOCATOR: Malloc = Malloc;

impl Malloc {
    /// Whether plain `malloc` meets `align` for a block of `size` bytes.
    fn malloc_aligns(align: usize, size: usize) -> bool {
        align <= MIN_ALIGN && align <= size
    }
}

// SAFETY: every block comes from malloc / calloc / realloc / posix_memalign with at least the layout's size and
// alignment, and is released by free.
unsafe impl GlobalAlloc for Malloc {
    unsafe fn alloc(&self, layout: Layout) -> *mut u8 {
        if Self::malloc_aligns(layout.align(), layout.size()) {
            // SAFETY: plain libc call.
            unsafe { malloc(layout.size()) }.cast()
        } else {
            aligned_alloc(layout)
        }
    }

    unsafe fn alloc_zeroed(&self, layout: Layout) -> *mut u8 {
        if Self::malloc_aligns(layout.align(), layout.size()) {
            // SAFETY: plain libc call.
            unsafe { calloc(layout.size(), 1) }.cast()
        } else {
            let ptr = aligned_alloc(layout);
            if !ptr.is_null() {
                // SAFETY: `ptr` is a new block of `layout.size()` bytes.
                unsafe { ptr.write_bytes(0, layout.size()) };
            }
            ptr
        }
    }

    unsafe fn dealloc(&self, ptr: *mut u8, _layout: Layout) {
        // SAFETY: `ptr` came from this allocator (caller contract), so from a libc allocation.
        unsafe { free(ptr.cast()) }
    }

    unsafe fn realloc(&self, ptr: *mut u8, layout: Layout, new_size: usize) -> *mut u8 {
        if Self::malloc_aligns(layout.align(), new_size) {
            // SAFETY: `ptr` is a live libc block (caller contract).
            return unsafe { realloc(ptr.cast(), new_size) }.cast();
        }
        // SAFETY: `new_size` is non-zero and, rounded up to `layout.align()`, fits `isize` (caller contract).
        let new_layout = unsafe { Layout::from_size_align_unchecked(new_size, layout.align()) };
        let new = aligned_alloc(new_layout);
        if !new.is_null() {
            // SAFETY: both blocks are live and distinct, each at least the copied length.
            unsafe {
                core::ptr::copy_nonoverlapping(ptr, new, layout.size().min(new_size));
                free(ptr.cast());
            }
        }
        new
    }
}

/// `posix_memalign` for `layout` (its alignment raised to the pointer size, which POSIX requires); null on failure.
fn aligned_alloc(layout: Layout) -> *mut u8 {
    let mut out: *mut c_void = core::ptr::null_mut();
    let align = layout.align().max(size_of::<usize>());
    // SAFETY: `out` is a live slot; `align` is a power of two and a multiple of the pointer size.
    if unsafe { posix_memalign(&mut out, align, layout.size()) } != 0 {
        return core::ptr::null_mut();
    }
    out.cast()
}

#[cfg(test)]
#[path = "rt_tests.rs"]
mod tests;
