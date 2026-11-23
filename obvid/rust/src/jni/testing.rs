// Copyright 2026, Serhii Yaremych (desugar-64) and the Obvid project contributors
// SPDX-License-Identifier: Apache-2.0

//! Cargo feature `testing` only (debug and benchmark builds): `ObvidTesting`, the test surface. The blur on a
//! `ByteArray` for the on-device exactness test.

use alloc::vec;
use core::ffi::{CStr, c_char, c_void};

use super::sys::{JNIEnv, jboolean, jclass, jobject};
use crate::{blur, blur::InteriorSkip, skia};

/// `jsize` (jni.h).
#[allow(non_camel_case_types)]
type jsize = i32;

/// The start of `JNINativeInterface` (jni.h), the table a `JNIEnv` points to, up to the last function used here.
/// The comments are slot indices; the assertions below check them.
#[repr(C)]
struct FnTable {
    _slots_0_5: [*const c_void; 6],
    /// 6
    find_class: unsafe extern "system" fn(*mut JNIEnv, *const c_char) -> jclass,
    _slots_7_13: [*const c_void; 7],
    /// 14
    throw_new: unsafe extern "system" fn(*mut JNIEnv, jclass, *const c_char) -> i32,
    _slots_15_170: [*const c_void; 156],
    /// 171
    get_array_length: unsafe extern "system" fn(*mut JNIEnv, jobject) -> jsize,
    _slots_172_175: [*const c_void; 4],
    /// 176
    new_byte_array: unsafe extern "system" fn(*mut JNIEnv, jsize) -> jobject,
    _slots_177_199: [*const c_void; 23],
    /// 200
    get_byte_array_region: unsafe extern "system" fn(*mut JNIEnv, jobject, jsize, jsize, *mut i8),
    _slots_201_207: [*const c_void; 7],
    /// 208
    set_byte_array_region: unsafe extern "system" fn(*mut JNIEnv, jobject, jsize, jsize, *const i8),
}

const _: () = {
    use core::mem::{offset_of, size_of};
    const SLOT: usize = size_of::<*const c_void>();
    assert!(offset_of!(FnTable, find_class) == 6 * SLOT);
    assert!(offset_of!(FnTable, throw_new) == 14 * SLOT);
    assert!(offset_of!(FnTable, get_array_length) == 171 * SLOT);
    assert!(offset_of!(FnTable, new_byte_array) == 176 * SLOT);
    assert!(offset_of!(FnTable, get_byte_array_region) == 200 * SLOT);
    assert!(offset_of!(FnTable, set_byte_array_region) == 208 * SLOT);
};

/// `ObvidTesting.abiVersion`: smoke check that the library with the testing surface loads.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_serhiiyaremych_obvid_ObvidTesting_abiVersion(_env: *mut JNIEnv, _class: jclass) -> i32 {
    super::ABI_VERSION
}

/// `ObvidTesting.blurA8`: a tightly packed A8 mask of `width` × `height` blurred at the sigma Android derives
/// from `BlurMaskFilter(radius_px, NORMAL)`, by [`skia::blur_a8`] (the scalar port) or, with `neon` ≠ 0, by
/// [`blur::blur_a8_with`] (NEON; the same bytes). Returns the blurred mask,
/// grown by `ObvidNative.builderBorder` on each side (Skia's `PrepareDestination` layout). Copies in and out of
/// Java arrays (allocates the output, and the scratch with `neon`, per call); throws a `RuntimeException` if `src`
/// is null or too short. 64-bit Arm only.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_serhiiyaremych_obvid_ObvidTesting_blurA8(
    env: *mut JNIEnv,
    _class: jclass,
    src: jobject,
    width: i32,
    height: i32,
    radius_px: f32,
    neon: jboolean,
) -> jobject {
    // SAFETY: `env` is the JNIEnv of this call; it points to the VM's function table.
    let jni = unsafe { &**(env as *const *const FnTable) };
    if src.is_null() {
        return throw(env, jni, c"src is null");
    }
    let (w, h) = (width.max(0) as usize, height.max(0) as usize);
    // SAFETY: `src` is a live, non-null byte[] reference of this call.
    let len = unsafe { (jni.get_array_length)(env, src) };
    if (len as usize) < w * h {
        return throw(env, jni, c"src is shorter than width * height");
    }
    let mut pixels = vec![0u8; len as usize];
    // SAFETY: `pixels` holds `len` bytes, the whole array.
    unsafe { (jni.get_byte_array_region)(env, src, 0, len, pixels.as_mut_ptr().cast()) };
    let sigma = skia::mask_blur_sigma_from_radius(radius_px);
    let out = if neon != 0 {
        blur::blur_a8_with(&pixels, w, h, w, sigma, InteriorSkip::Auto)
    } else {
        skia::blur_a8(&pixels, w, h, w, sigma)
    };
    let Ok(n) = jsize::try_from(out.pixels.len()) else {
        return throw(env, jni, c"blurred mask is too large for a byte[]");
    };
    // SAFETY: as above. A null result leaves the VM's OutOfMemoryError pending.
    let array = unsafe { (jni.new_byte_array)(env, n) };
    if !array.is_null() {
        // SAFETY: `array` is a new byte[] of `n` bytes; `out.pixels` holds `n` bytes.
        unsafe { (jni.set_byte_array_region)(env, array, 0, n, out.pixels.as_ptr().cast()) };
    }
    array
}

/// Throws a `java.lang.RuntimeException` with `message` and returns null (the value the VM ignores while the
/// exception is pending).
fn throw(env: *mut JNIEnv, jni: &FnTable, message: &CStr) -> jobject {
    // SAFETY: `env` is the JNIEnv of this call; both strings are NUL-terminated.
    unsafe {
        let class = (jni.find_class)(env, c"java/lang/RuntimeException".as_ptr());
        if !class.is_null() {
            (jni.throw_new)(env, class, message.as_ptr());
        }
    }
    core::ptr::null_mut()
}
