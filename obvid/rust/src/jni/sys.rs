// Copyright 2026, Serhii Yaremych (desugar-64) and the Obvid project contributors
// SPDX-License-Identifier: Apache-2.0

//! The raw JNI types the exports use (jni.h). Primitives map one to one: `jint` = `i32`, `jlong` = `i64`,
//! `jfloat` = `f32`; the exports spell them as the Rust types.

#![allow(non_camel_case_types)]

use core::ffi::c_void;

/// `JNIEnv`: a pointer to the JNI function table. Exports receive `*mut JNIEnv`; only `testing.rs` reads the table.
pub(crate) type JNIEnv = *const c_void;

/// `jobject`: an opaque local reference.
pub(crate) type jobject = *mut c_void;

/// `jclass`: the class of a static native method.
pub(crate) type jclass = jobject;

/// `jboolean`: `uint8_t`; any value other than 0 is true.
#[cfg(any(target_os = "android", feature = "testing"))]
pub(crate) type jboolean = u8;
