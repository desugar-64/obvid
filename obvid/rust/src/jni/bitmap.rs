// Copyright 2026, Serhii Yaremych (desugar-64) and the Obvid project contributors
// SPDX-License-Identifier: Apache-2.0

//! A locked A8 `Bitmap` through libjnigraphics (`AndroidBitmap_*`, Android only).

use core::ffi::c_void;

use super::sys::{JNIEnv, jobject};
use super::{BUILDER_BAD_ARGUMENT, BUILDER_LOCK_FAILED, BUILDER_NOT_A8};

/// `AndroidBitmapInfo` (android/bitmap.h).
#[repr(C)]
#[derive(Default)]
struct AndroidBitmapInfo {
    width: u32,
    height: u32,
    stride: u32,
    format: i32,
    flags: u32,
}

const ANDROID_BITMAP_RESULT_SUCCESS: i32 = 0;
const ANDROID_BITMAP_FORMAT_A_8: i32 = 8;

#[link(name = "jnigraphics")]
unsafe extern "C" {
    fn AndroidBitmap_getInfo(env: *mut JNIEnv, jbitmap: jobject, info: *mut AndroidBitmapInfo) -> i32;
    fn AndroidBitmap_lockPixels(env: *mut JNIEnv, jbitmap: jobject, addr_ptr: *mut *mut c_void) -> i32;
    fn AndroidBitmap_unlockPixels(env: *mut JNIEnv, jbitmap: jobject) -> i32;
}

/// A locked A8 bitmap; unlocks (and so notifies HWUI that the pixels changed) on drop.
pub(super) struct Locked {
    env: *mut JNIEnv,
    bitmap: jobject,
    pub pixels: *mut u8,
    pub width: usize,
    pub height: usize,
    pub stride: usize,
}

impl Locked {
    /// Locks `bitmap`, which must be ALPHA_8. Errors are the builder's negative status codes.
    ///
    /// # Safety
    /// `env` and `bitmap` must be valid for the current JNI call.
    pub unsafe fn lock(env: *mut JNIEnv, bitmap: jobject) -> Result<Locked, i32> {
        if bitmap.is_null() {
            return Err(BUILDER_BAD_ARGUMENT);
        }
        let mut info = AndroidBitmapInfo::default();
        // SAFETY: env and bitmap are valid (caller contract); `info` is a live AndroidBitmapInfo.
        if unsafe { AndroidBitmap_getInfo(env, bitmap, &mut info) } != ANDROID_BITMAP_RESULT_SUCCESS {
            return Err(BUILDER_LOCK_FAILED);
        }
        if info.format != ANDROID_BITMAP_FORMAT_A_8 {
            return Err(BUILDER_NOT_A8);
        }
        let mut addr: *mut c_void = core::ptr::null_mut();
        // SAFETY: as above; `addr` is a live pointer slot. On success the pixels stay locked until `drop`.
        if unsafe { AndroidBitmap_lockPixels(env, bitmap, &mut addr) } != ANDROID_BITMAP_RESULT_SUCCESS
            || addr.is_null()
        {
            return Err(BUILDER_LOCK_FAILED);
        }
        Ok(Locked {
            env,
            bitmap,
            pixels: addr as *mut u8,
            width: info.width as usize,
            height: info.height as usize,
            stride: info.stride as usize,
        })
    }

    /// Size of the pixel buffer: `stride` bytes per row, the last row `width` bytes.
    pub fn len(&self) -> usize {
        if self.height == 0 { 0 } else { (self.height - 1) * self.stride + self.width }
    }
}

impl Drop for Locked {
    fn drop(&mut self) {
        // SAFETY: locked by `lock` in the same JNI call, with the same env and bitmap.
        unsafe { AndroidBitmap_unlockPixels(self.env, self.bitmap) };
    }
}
