// Copyright 2026, Serhii Yaremych (desugar-64) and the Obvid project contributors
// SPDX-License-Identifier: Apache-2.0

//! The shipping exports, `ObvidNative`: the library smoke check and the shadow builder
//! ([`crate::shadow::ShadowBuilder`]) behind an opaque handle, writing into A8 `Bitmap`s through libjnigraphics, and
//! the inner shadow's inversion and hole. The builder is 64-bit Arm only; the exports that lock bitmaps (`builderBlur`,
//! `builderBlurFrom`, `builderNine`, `builderNineKeep`, `builderNinePlace`, their `…Inverted` and `…InvertedHole`
//! twins,
//! `builderNineRects`, `builderInvert`, `builderInvertHole`, `builderNineMaskHole`) are Android only (the host build has no libjnigraphics).

use alloc::boxed::Box;

use super::sys::{JNIEnv, jclass};
#[cfg(target_os = "android")]
use super::sys::{jboolean, jobject};
use crate::shadow;

/// The `skia_class` argument of `builderPlanRoundRect` / `builderNine` that the library accepts: Android 16–17's
/// `filterRRectToNine` (Kotlin's `ObvidNative.SKIA_CLASS_B`). Any other value is an unknown class.
const SKIA_CLASS_B: i32 = 1;

/// `ObvidNative.abiVersion`: smoke check that the library loads; bump when the JNI surface changes.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_serhiiyaremych_obvid_ObvidNative_abiVersion(_env: *mut JNIEnv, _class: jclass) -> i32 {
    super::ABI_VERSION
}

/// `ObvidNative.builderCreate`: a new [`shadow::ShadowBuilder`]; free with `builderDestroy`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_serhiiyaremych_obvid_ObvidNative_builderCreate(
    _env: *mut JNIEnv,
    _class: jclass,
) -> i64 {
    Box::into_raw(Box::new(shadow::ShadowBuilder::new())) as i64
}

/// `ObvidNative.builderDestroy`: frees a builder made by `builderCreate` (0 is ignored).
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_serhiiyaremych_obvid_ObvidNative_builderDestroy(
    _env: *mut JNIEnv,
    _class: jclass,
    handle: i64,
) {
    if handle != 0 {
        // SAFETY: a non-zero handle comes from builderCreate and is destroyed once (caller contract).
        drop(unsafe { Box::from_raw(handle as *mut shadow::ShadowBuilder) });
    }
}

/// `ObvidNative.builderBorder`: the blur border of `BlurMaskFilter(radius_px)` (no allocation).
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_serhiiyaremych_obvid_ObvidNative_builderBorder(
    _env: *mut JNIEnv,
    _class: jclass,
    radius_px: f32,
) -> i32 {
    shadow::border_for_radius(radius_px) as i32
}

/// `ObvidNative.builderFullMargin`: the full-extent margin of `BlurMaskFilter(radius_px)` on Obvid's pipeline
/// ([`crate::skia::full_extent_margin`]; no allocation worth noting: one line of the kernel's length).
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_serhiiyaremych_obvid_ObvidNative_builderFullMargin(
    _env: *mut JNIEnv,
    _class: jclass,
    radius_px: f32,
) -> i32 {
    crate::skia::full_extent_margin(radius_px) as i32
}

/// `ObvidNative.builderSkiaReachMargin`: the full-extent margin of a draw left to the device's Skia
/// ([`crate::skia::skia_reach_margin`]).
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_serhiiyaremych_obvid_ObvidNative_builderSkiaReachMargin(
    _env: *mut JNIEnv,
    _class: jclass,
    radius_px: f32,
) -> i32 {
    crate::skia::skia_reach_margin(radius_px) as i32
}

/// `ObvidNative.builderBlur`: blurs the `mask_w × mask_h` top-left window of the A8 `mask` bitmap (drawn with
/// its origin at (`mask_x`, `mask_y`) in the shadow bitmap) and writes it into the A8 `shadow` bitmap clipped to
/// `[0, used_w) × [0, used_h)`; `mode` 0 = replace (also zeroes the rest of `[0, clear_w) × [0, clear_h)`), 1 =
/// source-over. Returns 0 or a negative status. Unlocking the shadow bitmap calls `notifyPixelsChanged` (new
/// generation id, which HWUI's texture cache keys on).
#[cfg(target_os = "android")]
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_serhiiyaremych_obvid_ObvidNative_builderBlur(
    env: *mut JNIEnv,
    _class: jclass,
    handle: i64,
    mask: jobject,
    mask_w: i32,
    mask_h: i32,
    mask_x: i32,
    mask_y: i32,
    radius_px: f32,
    shadow: jobject,
    used_w: i32,
    used_h: i32,
    clear_w: i32,
    clear_h: i32,
    mode: i32,
) -> i32 {
    let window = MaskWindow { src_x: 0, src_y: 0, w: mask_w, h: mask_h, x: mask_x, y: mask_y };
    blur_export(env, handle, mask, window, radius_px, shadow, (used_w, used_h), (clear_w, clear_h), mode)
}

/// `ObvidNative.builderBlurFrom`: `builderBlur` of the `mask_w × mask_h` window of the A8 `mask` bitmap whose
/// top-left is (`src_x`, `src_y`) in it, instead of the top-left window (a mask drawn at its device coordinates).
/// Same placement, modes and status codes.
#[cfg(target_os = "android")]
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_serhiiyaremych_obvid_ObvidNative_builderBlurFrom(
    env: *mut JNIEnv,
    _class: jclass,
    handle: i64,
    mask: jobject,
    src_x: i32,
    src_y: i32,
    mask_w: i32,
    mask_h: i32,
    mask_x: i32,
    mask_y: i32,
    radius_px: f32,
    shadow: jobject,
    used_w: i32,
    used_h: i32,
    clear_w: i32,
    clear_h: i32,
    mode: i32,
) -> i32 {
    let window = MaskWindow { src_x, src_y, w: mask_w, h: mask_h, x: mask_x, y: mask_y };
    blur_export(env, handle, mask, window, radius_px, shadow, (used_w, used_h), (clear_w, clear_h), mode)
}

/// The mask window of `builderBlur` / `builderBlurFrom`: `w × h` at (`src_x`, `src_y`) in the mask bitmap, its
/// origin at (`x`, `y`) in the shadow bitmap.
#[cfg(target_os = "android")]
struct MaskWindow {
    src_x: i32,
    src_y: i32,
    w: i32,
    h: i32,
    x: i32,
    y: i32,
}

/// `builderBlur` / `builderBlurFrom`: checks the arguments, locks both bitmaps and runs
/// [`shadow::ShadowBuilder::blur_into`].
#[cfg(target_os = "android")]
#[allow(clippy::too_many_arguments)]
fn blur_export(
    env: *mut JNIEnv,
    handle: i64,
    mask: jobject,
    window: MaskWindow,
    radius_px: f32,
    shadow: jobject,
    used: (i32, i32),
    clear: (i32, i32),
    mode: i32,
) -> i32 {
    use super::bitmap::Locked;
    use super::{BUILDER_BAD_ARGUMENT, BUILDER_OUT_OF_BOUNDS};
    use crate::blur::Store;
    use shadow::{A8, A8Mut, Placement};
    let Some(mode) = Store::from_index(mode) else { return BUILDER_BAD_ARGUMENT };
    let MaskWindow { src_x, src_y, w: mask_w, h: mask_h, x: mask_x, y: mask_y } = window;
    let ((used_w, used_h), (clear_w, clear_h)) = (used, clear);
    if handle == 0
        || src_x < 0
        || src_y < 0
        || mask_w < 0
        || mask_h < 0
        || used_w < 0
        || used_h < 0
        || clear_w < 0
        || clear_h < 0
    {
        return BUILDER_BAD_ARGUMENT;
    }
    // SAFETY: env and the mask bitmap are valid for this call; the guard unlocks before returning.
    let m = match unsafe { Locked::lock(env, mask) } {
        Ok(l) => l,
        Err(e) => return e,
    };
    // SAFETY: as above, for the shadow bitmap.
    let s = match unsafe { Locked::lock(env, shadow) } {
        Ok(l) => l,
        Err(e) => return e,
    };
    let (sx, sy, mw, mh) = (src_x as usize, src_y as usize, mask_w as usize, mask_h as usize);
    let (uw, uh) = (used_w as usize, used_h as usize);
    let (cw, ch) = ((clear_w as usize).max(uw), (clear_h as usize).max(uh));
    if sx + mw > m.width || sy + mh > m.height || cw > s.width || ch > s.height || core::ptr::eq(m.pixels, s.pixels) {
        return BUILDER_OUT_OF_BOUNDS;
    }
    // SAFETY: the locked mask buffer holds `len()` bytes and stays locked while `m` lives.
    let mask_px = unsafe { core::slice::from_raw_parts(m.pixels as *const u8, m.len()) };
    // SAFETY: as above for the shadow buffer; it is a different bitmap (checked above), so the slices do not alias.
    let shadow_px = unsafe { core::slice::from_raw_parts_mut(s.pixels, s.len()) };
    // SAFETY: the handle is non-zero (checked above), comes from builderCreate and is not destroyed yet (caller
    // contract); calls on one handle are serialized by the Kotlin side.
    let builder = unsafe { &mut *(handle as *mut shadow::ShadowBuilder) };
    let src = A8 { pixels: mask_px, width: m.width, height: m.height, stride: m.stride }.sub(sx, sy, mw, mh);
    let mut dst = A8Mut { pixels: shadow_px, width: s.width, height: s.height, stride: s.stride };
    let at = Placement {
        mask_x: mask_x as isize,
        mask_y: mask_y as isize,
        used_w: uw,
        used_h: uh,
        clear_w: cw,
        clear_h: ch,
    };
    builder.blur_into(src, radius_px, &mut dst, at, mode);
    0
}

/// `ObvidNative.builderPlanRoundRect`: Skia's route for `drawRoundRect(left, top, right, bottom, rx, ry)` with
/// `BlurMaskFilter(radius_px)` on an identity canvas, as Android 16–17's Skia decides it, for the Skia class
/// `skia_class` (only [`SKIA_CLASS_B`] is known), packed by [`shadow::nine::RoundRectRoute::packed`]: 0 = Skia,
/// 1 = full mask, otherwise a nine-patch with the f32 bits of the small rrect's width in bits 0..32 and of its height
/// in bits 32..64. An unknown class is 0 (Skia). Pure, no allocation.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_serhiiyaremych_obvid_ObvidNative_builderPlanRoundRect(
    _env: *mut JNIEnv,
    _class: jclass,
    skia_class: i32,
    left: f32,
    top: f32,
    right: f32,
    bottom: f32,
    rx: f32,
    ry: f32,
    radius_px: f32,
) -> i64 {
    use shadow::nine::{RoundRectRoute, plan_round_rect};
    if skia_class != SKIA_CLASS_B {
        return RoundRectRoute::Skia.packed();
    }
    plan_round_rect(left, top, right, bottom, rx, ry, radius_px).packed()
}

/// `ObvidNative.builderNine`: the nine-patch route. Recomputes the plan of `builderPlanRoundRect` from the same
/// arguments (it must be a nine-patch), blurs the `ceil(w) × ceil(h)` top-left window of the A8 `mask` bitmap (the
/// small round rect `(0, 0, w, h)` the caller drew at the origin) and writes the nine-patch into the A8 `shadow`
/// bitmap clipped to `[0, used_w) × [0, used_h)`, zeroing the rest of
/// `[0, max(clear_w, used_w)) × [0, max(clear_h, used_h))`. Returns 0 or a negative status (−1 also when the plan is
/// not a nine-patch or the class is unknown). Unlocking the shadow bitmap calls `notifyPixelsChanged` (new
/// generation id).
#[cfg(target_os = "android")]
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_serhiiyaremych_obvid_ObvidNative_builderNine(
    env: *mut JNIEnv,
    _class: jclass,
    handle: i64,
    mask: jobject,
    skia_class: i32,
    left: f32,
    top: f32,
    right: f32,
    bottom: f32,
    rx: f32,
    ry: f32,
    radius_px: f32,
    shadow: jobject,
    used_w: i32,
    used_h: i32,
    clear_w: i32,
    clear_h: i32,
) -> i32 {
    let rrect = RoundRectArgs { skia_class, left, top, right, bottom, rx, ry, radius_px };
    nine_export(env, handle, mask, rrect, shadow, (used_w, used_h), (clear_w, clear_h), Write::Plain) as i32
}

/// `ObvidNative.builderNineInverted`: `builderNine` with every value v inside `[0, used_w) × [0, used_h)` written
/// as `255 − v` ([`shadow::nine::write_nine_inverted`]): `builderNine` followed by `builderInvert(shadow, used_w,
/// used_h)`, in one pass. Same arguments and status codes.
#[cfg(target_os = "android")]
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_serhiiyaremych_obvid_ObvidNative_builderNineInverted(
    env: *mut JNIEnv,
    _class: jclass,
    handle: i64,
    mask: jobject,
    skia_class: i32,
    left: f32,
    top: f32,
    right: f32,
    bottom: f32,
    rx: f32,
    ry: f32,
    radius_px: f32,
    shadow: jobject,
    used_w: i32,
    used_h: i32,
    clear_w: i32,
    clear_h: i32,
) -> i32 {
    let rrect = RoundRectArgs { skia_class, left, top, right, bottom, rx, ry, radius_px };
    nine_export(env, handle, mask, rrect, shadow, (used_w, used_h), (clear_w, clear_h), Write::Inverted) as i32
}

/// `ObvidNative.builderNineKeep`: `builderNine` that also keeps the blurred small round rect: it is blurred into
/// the top-left `(ceil(w) + 2m) × (ceil(h) + 2m)` window of the A8 `keep` bitmap (m = `builderBorder(radius_px)`;
/// the rest of `keep` is untouched), and the nine-patch is written from there into the A8 `shadow` bitmap, as
/// `builderNine` writes it. `builderNinePlace` can then write the nine-patch of any round rect whose plan has the same
/// small round rect, radii and radius from `keep` alone. Same status codes as `builderNine` (−4 also when `keep` is
/// smaller than the blurred mask; the three bitmaps must be distinct).
#[cfg(target_os = "android")]
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_serhiiyaremych_obvid_ObvidNative_builderNineKeep(
    env: *mut JNIEnv,
    _class: jclass,
    handle: i64,
    mask: jobject,
    keep: jobject,
    skia_class: i32,
    left: f32,
    top: f32,
    right: f32,
    bottom: f32,
    rx: f32,
    ry: f32,
    radius_px: f32,
    shadow: jobject,
    used_w: i32,
    used_h: i32,
    clear_w: i32,
    clear_h: i32,
) -> i32 {
    let rrect = RoundRectArgs { skia_class, left, top, right, bottom, rx, ry, radius_px };
    nine_keep_export(env, handle, mask, keep, rrect, shadow, (used_w, used_h), (clear_w, clear_h), Write::Plain) as i32
}

/// `ObvidNative.builderNineKeepInverted`: `builderNineKeep` with the nine-patch written as
/// `builderNineInverted` writes it (`keep` gets the same blurred small round rect, not inverted, for either place
/// export). Same arguments and status codes.
#[cfg(target_os = "android")]
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_serhiiyaremych_obvid_ObvidNative_builderNineKeepInverted(
    env: *mut JNIEnv,
    _class: jclass,
    handle: i64,
    mask: jobject,
    keep: jobject,
    skia_class: i32,
    left: f32,
    top: f32,
    right: f32,
    bottom: f32,
    rx: f32,
    ry: f32,
    radius_px: f32,
    shadow: jobject,
    used_w: i32,
    used_h: i32,
    clear_w: i32,
    clear_h: i32,
) -> i32 {
    let rrect = RoundRectArgs { skia_class, left, top, right, bottom, rx, ry, radius_px };
    nine_keep_export(env, handle, mask, keep, rrect, shadow, (used_w, used_h), (clear_w, clear_h), Write::Inverted)
        as i32
}

/// `ObvidNative.builderNinePlace`: writes the nine-patch of the plan of these arguments (it must be a nine-patch)
/// into the A8 `shadow` bitmap, as `builderNine` writes it, from the blurred small round rect that `builderNineKeep`
/// left in the A8 `keep` bitmap; no drawing, no blur, no builder. The caller guarantees that `keep` was written for a
/// plan with the same small round rect (`builderPlanRoundRect`'s value), the same radii and the same radius: the
/// bytes are not checked. Same status codes as `builderNineKeep`.
#[cfg(target_os = "android")]
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_serhiiyaremych_obvid_ObvidNative_builderNinePlace(
    env: *mut JNIEnv,
    _class: jclass,
    keep: jobject,
    skia_class: i32,
    left: f32,
    top: f32,
    right: f32,
    bottom: f32,
    rx: f32,
    ry: f32,
    radius_px: f32,
    shadow: jobject,
    used_w: i32,
    used_h: i32,
    clear_w: i32,
    clear_h: i32,
) -> i32 {
    let rrect = RoundRectArgs { skia_class, left, top, right, bottom, rx, ry, radius_px };
    place_export(env, keep, None, rrect, shadow, (used_w, used_h), (clear_w, clear_h), Write::Plain) as i32
}

/// `ObvidNative.builderNinePlaceInverted`: `builderNinePlace` with the nine-patch written as
/// `builderNineInverted` writes it. Same arguments, contract and status codes.
#[cfg(target_os = "android")]
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_serhiiyaremych_obvid_ObvidNative_builderNinePlaceInverted(
    env: *mut JNIEnv,
    _class: jclass,
    keep: jobject,
    skia_class: i32,
    left: f32,
    top: f32,
    right: f32,
    bottom: f32,
    rx: f32,
    ry: f32,
    radius_px: f32,
    shadow: jobject,
    used_w: i32,
    used_h: i32,
    clear_w: i32,
    clear_h: i32,
) -> i32 {
    let rrect = RoundRectArgs { skia_class, left, top, right, bottom, rx, ry, radius_px };
    place_export(env, keep, None, rrect, shadow, (used_w, used_h), (clear_w, clear_h), Write::Inverted) as i32
}

/// The round rect arguments of the nine-patch exports, as `builderPlanRoundRect` takes them.
#[cfg(target_os = "android")]
struct RoundRectArgs {
    skia_class: i32,
    left: f32,
    top: f32,
    right: f32,
    bottom: f32,
    rx: f32,
    ry: f32,
    radius_px: f32,
}

#[cfg(target_os = "android")]
impl RoundRectArgs {
    /// The plan of these arguments if it is a nine-patch of the known class, else None (the exports' −1).
    fn nine_patch(&self) -> Option<shadow::nine::NinePatch> {
        use shadow::nine::{RoundRectRoute, plan_round_rect};
        if self.skia_class != SKIA_CLASS_B {
            return None;
        }
        match plan_round_rect(self.left, self.top, self.right, self.bottom, self.rx, self.ry, self.radius_px) {
            RoundRectRoute::NinePatch(patch) => Some(patch),
            _ => None,
        }
    }
}

/// What a nine-patch export writes: the plain nine-patch, the inverted one, or the inverted one and its hole.
#[cfg(target_os = "android")]
#[derive(Clone, Copy, PartialEq, Eq)]
enum Write {
    Plain,
    Inverted,
    InvertedHole,
}

/// `builderNine` / `builderNineInverted` / `builderNineInvertedHole`: checks the arguments, locks both bitmaps and
/// runs [`shadow::ShadowBuilder::nine_into`], [`shadow::ShadowBuilder::nine_inverted_into`] or
/// [`shadow::ShadowBuilder::nine_inverted_hole_into`]. Returns 0 (the packed hole for `InvertedHole`) or a negative
/// status.
#[cfg(target_os = "android")]
#[allow(clippy::too_many_arguments)]
fn nine_export(
    env: *mut JNIEnv,
    handle: i64,
    mask: jobject,
    rrect: RoundRectArgs,
    shadow: jobject,
    used: (i32, i32),
    clear: (i32, i32),
    write: Write,
) -> i64 {
    use super::bitmap::Locked;
    use super::{BUILDER_BAD_ARGUMENT, BUILDER_OUT_OF_BOUNDS};
    use shadow::{A8, A8Mut};
    let ((used_w, used_h), (clear_w, clear_h)) = (used, clear);
    if handle == 0 || used_w < 0 || used_h < 0 || clear_w < 0 || clear_h < 0 {
        return BUILDER_BAD_ARGUMENT as i64;
    }
    let Some(patch) = rrect.nine_patch() else { return BUILDER_BAD_ARGUMENT as i64 };
    // SAFETY: env and the mask bitmap are valid for this call; the guard unlocks before returning.
    let m = match unsafe { Locked::lock(env, mask) } {
        Ok(l) => l,
        Err(e) => return e as i64,
    };
    // SAFETY: as above, for the shadow bitmap.
    let s = match unsafe { Locked::lock(env, shadow) } {
        Ok(l) => l,
        Err(e) => return e as i64,
    };
    let (uw, uh) = (used_w as usize, used_h as usize);
    let (cw, ch) = ((clear_w as usize).max(uw), (clear_h as usize).max(uh));
    if patch.small_w > m.width
        || patch.small_h > m.height
        || cw > s.width
        || ch > s.height
        || core::ptr::eq(m.pixels, s.pixels)
    {
        return BUILDER_OUT_OF_BOUNDS as i64;
    }
    // SAFETY: the locked mask buffer holds `len()` bytes and stays locked while `m` lives.
    let mask_px = unsafe { core::slice::from_raw_parts(m.pixels as *const u8, m.len()) };
    // SAFETY: as above for the shadow buffer; it is a different bitmap (checked above), so the slices do not alias.
    let shadow_px = unsafe { core::slice::from_raw_parts_mut(s.pixels, s.len()) };
    // SAFETY: the handle is non-zero (checked above), comes from builderCreate and is not destroyed yet (caller
    // contract); calls on one handle are serialized by the Kotlin side.
    let builder = unsafe { &mut *(handle as *mut shadow::ShadowBuilder) };
    let small = A8 { pixels: mask_px, width: patch.small_w, height: patch.small_h, stride: m.stride };
    let mut dst = A8Mut { pixels: shadow_px, width: s.width, height: s.height, stride: s.stride };
    match write {
        Write::Plain => builder.nine_into(small, &patch, &mut dst, (uw, uh), (cw, ch)),
        Write::Inverted => builder.nine_inverted_into(small, &patch, &mut dst, (uw, uh), (cw, ch)),
        Write::InvertedHole => {
            return builder.nine_inverted_hole_into(small, &patch, &mut dst, (uw, uh), (cw, ch)).packed();
        }
    }
    0
}

/// `builderNineKeep` / `builderNineKeepInverted` / `builderNineKeepInvertedHole`: checks the arguments, locks the
/// three bitmaps and runs [`shadow::ShadowBuilder::nine_keep_into`],
/// [`shadow::ShadowBuilder::nine_keep_inverted_into`] or [`shadow::ShadowBuilder::nine_keep_inverted_hole_into`].
/// Returns 0 (the packed hole for `InvertedHole`) or a negative status.
#[cfg(target_os = "android")]
#[allow(clippy::too_many_arguments)]
fn nine_keep_export(
    env: *mut JNIEnv,
    handle: i64,
    mask: jobject,
    keep: jobject,
    rrect: RoundRectArgs,
    shadow: jobject,
    used: (i32, i32),
    clear: (i32, i32),
    write: Write,
) -> i64 {
    use super::bitmap::Locked;
    use super::{BUILDER_BAD_ARGUMENT, BUILDER_OUT_OF_BOUNDS};
    use shadow::{A8, A8Mut};
    let ((used_w, used_h), (clear_w, clear_h)) = (used, clear);
    if handle == 0 || used_w < 0 || used_h < 0 || clear_w < 0 || clear_h < 0 {
        return BUILDER_BAD_ARGUMENT as i64;
    }
    let Some(patch) = rrect.nine_patch() else { return BUILDER_BAD_ARGUMENT as i64 };
    // SAFETY: env and the mask bitmap are valid for this call; the guard unlocks before returning.
    let m = match unsafe { Locked::lock(env, mask) } {
        Ok(l) => l,
        Err(e) => return e as i64,
    };
    // SAFETY: as above, for the keep bitmap.
    let k = match unsafe { Locked::lock(env, keep) } {
        Ok(l) => l,
        Err(e) => return e as i64,
    };
    // SAFETY: as above, for the shadow bitmap.
    let s = match unsafe { Locked::lock(env, shadow) } {
        Ok(l) => l,
        Err(e) => return e as i64,
    };
    let (uw, uh) = (used_w as usize, used_h as usize);
    let (cw, ch) = ((clear_w as usize).max(uw), (clear_h as usize).max(uh));
    let distinct =
        !core::ptr::eq(m.pixels, k.pixels) && !core::ptr::eq(m.pixels, s.pixels) && !core::ptr::eq(k.pixels, s.pixels);
    if patch.small_w > m.width
        || patch.small_h > m.height
        || patch.mask_w() > k.width
        || patch.mask_h() > k.height
        || cw > s.width
        || ch > s.height
        || !distinct
    {
        return BUILDER_OUT_OF_BOUNDS as i64;
    }
    // SAFETY: the locked mask buffer holds `len()` bytes and stays locked while `m` lives.
    let mask_px = unsafe { core::slice::from_raw_parts(m.pixels as *const u8, m.len()) };
    // SAFETY: as above for the keep buffer; it is a different bitmap from the other two (checked above).
    let keep_px = unsafe { core::slice::from_raw_parts_mut(k.pixels, k.len()) };
    // SAFETY: as above for the shadow buffer.
    let shadow_px = unsafe { core::slice::from_raw_parts_mut(s.pixels, s.len()) };
    // SAFETY: the handle is non-zero (checked above), comes from builderCreate and is not destroyed yet (caller
    // contract); calls on one handle are serialized by the Kotlin side.
    let builder = unsafe { &mut *(handle as *mut shadow::ShadowBuilder) };
    let small = A8 { pixels: mask_px, width: patch.small_w, height: patch.small_h, stride: m.stride };
    let mut kept = A8Mut { pixels: keep_px, width: k.width, height: k.height, stride: k.stride };
    let mut dst = A8Mut { pixels: shadow_px, width: s.width, height: s.height, stride: s.stride };
    match write {
        Write::Plain => builder.nine_keep_into(small, &patch, &mut kept, &mut dst, (uw, uh), (cw, ch)),
        Write::Inverted => builder.nine_keep_inverted_into(small, &patch, &mut kept, &mut dst, (uw, uh), (cw, ch)),
        Write::InvertedHole => {
            return builder
                .nine_keep_inverted_hole_into(small, &patch, &mut kept, &mut dst, (uw, uh), (cw, ch))
                .packed();
        }
    }
    0
}

/// `builderNinePlace` / `builderNinePlaceInverted` / `builderNinePlaceInvertedHole`: checks the arguments, locks both
/// bitmaps and runs [`shadow::nine::place_nine`], [`shadow::nine::place_nine_inverted`] or
/// [`shadow::nine::place_nine_inverted_hole`]. Returns 0 (the packed hole for `InvertedHole`) or a negative status.
#[cfg(target_os = "android")]
#[allow(clippy::too_many_arguments)]
fn place_export(
    env: *mut JNIEnv,
    keep: jobject,
    mask_hole: Option<shadow::hole::Hole>,
    rrect: RoundRectArgs,
    shadow: jobject,
    used: (i32, i32),
    clear: (i32, i32),
    write: Write,
) -> i64 {
    use super::bitmap::Locked;
    use super::{BUILDER_BAD_ARGUMENT, BUILDER_OUT_OF_BOUNDS};
    use shadow::nine::{place_nine, place_nine_inverted, place_nine_inverted_hole};
    use shadow::{A8, A8Mut};
    let ((used_w, used_h), (clear_w, clear_h)) = (used, clear);
    if used_w < 0 || used_h < 0 || clear_w < 0 || clear_h < 0 {
        return BUILDER_BAD_ARGUMENT as i64;
    }
    let Some(patch) = rrect.nine_patch() else { return BUILDER_BAD_ARGUMENT as i64 };
    // SAFETY: env and the keep bitmap are valid for this call; the guard unlocks before returning.
    let k = match unsafe { Locked::lock(env, keep) } {
        Ok(l) => l,
        Err(e) => return e as i64,
    };
    // SAFETY: as above, for the shadow bitmap.
    let s = match unsafe { Locked::lock(env, shadow) } {
        Ok(l) => l,
        Err(e) => return e as i64,
    };
    let (uw, uh) = (used_w as usize, used_h as usize);
    let (cw, ch) = ((clear_w as usize).max(uw), (clear_h as usize).max(uh));
    if patch.mask_w() > k.width
        || patch.mask_h() > k.height
        || cw > s.width
        || ch > s.height
        || core::ptr::eq(k.pixels, s.pixels)
    {
        return BUILDER_OUT_OF_BOUNDS as i64;
    }
    // SAFETY: the locked keep buffer holds `len()` bytes and stays locked while `k` lives.
    let keep_px = unsafe { core::slice::from_raw_parts(k.pixels as *const u8, k.len()) };
    // SAFETY: as above for the shadow buffer; it is a different bitmap (checked above), so the slices do not alias.
    let shadow_px = unsafe { core::slice::from_raw_parts_mut(s.pixels, s.len()) };
    let kept = A8 { pixels: keep_px, width: k.width, height: k.height, stride: k.stride };
    let mut dst = A8Mut { pixels: shadow_px, width: s.width, height: s.height, stride: s.stride };
    match write {
        Write::Plain => place_nine(&mut dst, kept, &patch, (uw, uh), (cw, ch)),
        Write::Inverted => place_nine_inverted(&mut dst, kept, &patch, (uw, uh), (cw, ch)),
        Write::InvertedHole => {
            return place_nine_inverted_hole(&mut dst, kept, &patch, (uw, uh), (cw, ch), mask_hole).packed();
        }
    }
    0
}

/// `ObvidNative.builderPlanRects`: Skia's route for a frame, the path whose nested rects (`IsNestedFillRects`) are
/// `outer` (containing) and `inner`, filled with `BlurMaskFilter(radius_px)` under a translate-only CTM, as Android
/// 17's `filterRectsToNine` decides it, packed by [`shadow::rects::RectsRoute::packed`]: 0 = Skia, 1 = mask (Skia's
/// general path blur), otherwise [`shadow::rects::RECTS_NINE_TAG`] with `dx` in bits 0..32 and `dy` in bits 32..62.
/// Pure, no allocation.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_serhiiyaremych_obvid_ObvidNative_builderPlanRects(
    _env: *mut JNIEnv,
    _class: jclass,
    outer_l: f32,
    outer_t: f32,
    outer_r: f32,
    outer_b: f32,
    inner_l: f32,
    inner_t: f32,
    inner_r: f32,
    inner_b: f32,
    radius_px: f32,
) -> i64 {
    shadow::rects::plan_rects([outer_l, outer_t, outer_r, outer_b], [inner_l, inner_t, inner_r, inner_b], radius_px)
        .packed()
}

/// `ObvidNative.builderNineRects`: the frame nine-patch route. Recomputes the plan of `builderPlanRects` from the
/// same arguments (it must be a nine-patch), blurs the `roundOut(smallR0)`-sized top-left window of the A8 `mask`
/// bitmap (the small frame the caller drew under the translate `(−floor(smallR0.l), −floor(smallR0.t))`) and writes
/// the nine-patch without its centre into the A8 `shadow` bitmap clipped to `[0, used_w) × [0, used_h)`, zeroing the
/// rest of `[0, max(clear_w, used_w)) × [0, max(clear_h, used_h))`; with `inverted` every value v inside the used rect
/// is written as `255 − v` (the centre 255). Returns 0 or a negative status (−1 also when the plan is not a
/// nine-patch, −4 when the mask bitmap is smaller than the small frame's window). Unlocking the shadow bitmap calls
/// `notifyPixelsChanged` (new generation id).
#[cfg(target_os = "android")]
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_serhiiyaremych_obvid_ObvidNative_builderNineRects(
    env: *mut JNIEnv,
    _class: jclass,
    handle: i64,
    mask: jobject,
    outer_l: f32,
    outer_t: f32,
    outer_r: f32,
    outer_b: f32,
    inner_l: f32,
    inner_t: f32,
    inner_r: f32,
    inner_b: f32,
    radius_px: f32,
    shadow: jobject,
    used_w: i32,
    used_h: i32,
    clear_w: i32,
    clear_h: i32,
    inverted: jboolean,
) -> i32 {
    use super::bitmap::Locked;
    use super::{BUILDER_BAD_ARGUMENT, BUILDER_OUT_OF_BOUNDS};
    use shadow::rects::{RectsRoute, plan_rects};
    use shadow::{A8, A8Mut};
    if handle == 0 || used_w < 0 || used_h < 0 || clear_w < 0 || clear_h < 0 {
        return BUILDER_BAD_ARGUMENT;
    }
    let outer = [outer_l, outer_t, outer_r, outer_b];
    let inner = [inner_l, inner_t, inner_r, inner_b];
    let RectsRoute::NinePatch(patch) = plan_rects(outer, inner, radius_px) else { return BUILDER_BAD_ARGUMENT };
    // SAFETY: env and the mask bitmap are valid for this call; the guard unlocks before returning.
    let m = match unsafe { Locked::lock(env, mask) } {
        Ok(l) => l,
        Err(e) => return e,
    };
    // SAFETY: as above, for the shadow bitmap.
    let s = match unsafe { Locked::lock(env, shadow) } {
        Ok(l) => l,
        Err(e) => return e,
    };
    let (uw, uh) = (used_w as usize, used_h as usize);
    let (cw, ch) = ((clear_w as usize).max(uw), (clear_h as usize).max(uh));
    let (sw, sh) = (patch.stretch.small_w, patch.stretch.small_h);
    if sw > m.width || sh > m.height || cw > s.width || ch > s.height || core::ptr::eq(m.pixels, s.pixels) {
        return BUILDER_OUT_OF_BOUNDS;
    }
    // SAFETY: the locked mask buffer holds `len()` bytes and stays locked while `m` lives.
    let mask_px = unsafe { core::slice::from_raw_parts(m.pixels as *const u8, m.len()) };
    // SAFETY: as above for the shadow buffer; it is a different bitmap (checked above), so the slices do not alias.
    let shadow_px = unsafe { core::slice::from_raw_parts_mut(s.pixels, s.len()) };
    // SAFETY: the handle is non-zero (checked above), comes from builderCreate and is not destroyed yet (caller
    // contract); calls on one handle are serialized by the Kotlin side.
    let builder = unsafe { &mut *(handle as *mut shadow::ShadowBuilder) };
    let small = A8 { pixels: mask_px, width: sw, height: sh, stride: m.stride };
    let mut dst = A8Mut { pixels: shadow_px, width: s.width, height: s.height, stride: s.stride };
    if inverted != 0 {
        builder.nine_rects_inverted_into(small, &patch, &mut dst, (uw, uh), (cw, ch));
    } else {
        builder.nine_rects_into(small, &patch, &mut dst, (uw, uh), (cw, ch));
    }
    0
}

/// `ObvidNative.builderInvert`: inverts `[0, used_w) × [0, used_h)` of the A8 `shadow` bitmap in place
/// (`255 − v`, [`shadow::invert_used`]): an inner shadow's bitmap from the blurred shape the builder wrote. Returns 0
/// or a negative status. Unlocking the bitmap calls `notifyPixelsChanged` (new generation id).
#[cfg(target_os = "android")]
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_serhiiyaremych_obvid_ObvidNative_builderInvert(
    env: *mut JNIEnv,
    _class: jclass,
    shadow: jobject,
    used_w: i32,
    used_h: i32,
) -> i32 {
    use super::bitmap::Locked;
    use super::{BUILDER_BAD_ARGUMENT, BUILDER_OUT_OF_BOUNDS};
    use shadow::A8Mut;
    if used_w < 0 || used_h < 0 {
        return BUILDER_BAD_ARGUMENT;
    }
    // SAFETY: env and the bitmap are valid for this call; the guard unlocks before returning.
    let s = match unsafe { Locked::lock(env, shadow) } {
        Ok(l) => l,
        Err(e) => return e,
    };
    let (uw, uh) = (used_w as usize, used_h as usize);
    if uw > s.width || uh > s.height {
        return BUILDER_OUT_OF_BOUNDS;
    }
    // SAFETY: the locked buffer holds `len()` bytes and stays locked while `s` lives.
    let shadow_px = unsafe { core::slice::from_raw_parts_mut(s.pixels, s.len()) };
    let mut dst = A8Mut { pixels: shadow_px, width: s.width, height: s.height, stride: s.stride };
    shadow::invert_used(&mut dst, uw, uh);
    0
}

/// `ObvidNative.builderInvertHole`: `builderInvert`, also returning the hole of the inverted used rect grown from
/// (`seed_x`, `seed_y`) ([`shadow::ShadowBuilder::invert_hole`]; none when the seed lies outside the used rect),
/// packed by [`shadow::hole::Hole::packed`]: ≥ 0 (0 = no hole), or a negative status as `builderInvert`'s (−1 also
/// for a zero handle).
#[cfg(target_os = "android")]
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_serhiiyaremych_obvid_ObvidNative_builderInvertHole(
    env: *mut JNIEnv,
    _class: jclass,
    handle: i64,
    shadow: jobject,
    used_w: i32,
    used_h: i32,
    seed_x: i32,
    seed_y: i32,
) -> i64 {
    use super::bitmap::Locked;
    use super::{BUILDER_BAD_ARGUMENT, BUILDER_OUT_OF_BOUNDS};
    use shadow::A8Mut;
    if handle == 0 || used_w < 0 || used_h < 0 {
        return BUILDER_BAD_ARGUMENT as i64;
    }
    // SAFETY: env and the bitmap are valid for this call; the guard unlocks before returning.
    let s = match unsafe { Locked::lock(env, shadow) } {
        Ok(l) => l,
        Err(e) => return e as i64,
    };
    let (uw, uh) = (used_w as usize, used_h as usize);
    if uw > s.width || uh > s.height {
        return BUILDER_OUT_OF_BOUNDS as i64;
    }
    // SAFETY: the locked buffer holds `len()` bytes and stays locked while `s` lives.
    let shadow_px = unsafe { core::slice::from_raw_parts_mut(s.pixels, s.len()) };
    let mut dst = A8Mut { pixels: shadow_px, width: s.width, height: s.height, stride: s.stride };
    // SAFETY: the handle is non-zero (checked above), comes from builderCreate and is not destroyed yet (caller
    // contract); calls on one handle are serialized by the Kotlin side.
    let builder = unsafe { &mut *(handle as *mut shadow::ShadowBuilder) };
    // A negative seed lies outside the used rect.
    let seed =
        (if seed_x < 0 { usize::MAX } else { seed_x as usize }, if seed_y < 0 { usize::MAX } else { seed_y as usize });
    builder.invert_hole(&mut dst, (uw, uh), seed).packed()
}

/// `ObvidNative.builderNineInvertedHole`: `builderNineInverted`, also returning the hole of the written bitmap
/// ([`shadow::hole::nine_hole`], read from the blurred small round rect, clipped to the used rect), packed by
/// [`shadow::hole::Hole::packed`]: ≥ 0 (0 = no hole), or a negative status as `builderNineInverted`'s.
#[cfg(target_os = "android")]
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_serhiiyaremych_obvid_ObvidNative_builderNineInvertedHole(
    env: *mut JNIEnv,
    _class: jclass,
    handle: i64,
    mask: jobject,
    skia_class: i32,
    left: f32,
    top: f32,
    right: f32,
    bottom: f32,
    rx: f32,
    ry: f32,
    radius_px: f32,
    shadow: jobject,
    used_w: i32,
    used_h: i32,
    clear_w: i32,
    clear_h: i32,
) -> i64 {
    let rrect = RoundRectArgs { skia_class, left, top, right, bottom, rx, ry, radius_px };
    nine_export(env, handle, mask, rrect, shadow, (used_w, used_h), (clear_w, clear_h), Write::InvertedHole)
}

/// `ObvidNative.builderNineKeepInvertedHole`: `builderNineKeepInverted`, also returning the hole as
/// `builderNineInvertedHole` does. Same arguments; the packed hole or a negative status.
#[cfg(target_os = "android")]
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_serhiiyaremych_obvid_ObvidNative_builderNineKeepInvertedHole(
    env: *mut JNIEnv,
    _class: jclass,
    handle: i64,
    mask: jobject,
    keep: jobject,
    skia_class: i32,
    left: f32,
    top: f32,
    right: f32,
    bottom: f32,
    rx: f32,
    ry: f32,
    radius_px: f32,
    shadow: jobject,
    used_w: i32,
    used_h: i32,
    clear_w: i32,
    clear_h: i32,
) -> i64 {
    let rrect = RoundRectArgs { skia_class, left, top, right, bottom, rx, ry, radius_px };
    nine_keep_export(env, handle, mask, keep, rrect, shadow, (used_w, used_h), (clear_w, clear_h), Write::InvertedHole)
}

/// `ObvidNative.builderNinePlaceInvertedHole`: `builderNinePlaceInverted`, also returning the hole as
/// `builderNineInvertedHole` does, mapped from `mask_hole`: the kept mask's rect from `builderNineMaskHole` (packed),
/// or with a negative `mask_hole` that rect read from `keep` now. Same arguments and contract otherwise (the caller
/// guarantees that `mask_hole` was found for `keep`'s bytes); the packed hole or a negative status.
#[cfg(target_os = "android")]
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_serhiiyaremych_obvid_ObvidNative_builderNinePlaceInvertedHole(
    env: *mut JNIEnv,
    _class: jclass,
    keep: jobject,
    mask_hole: i64,
    skia_class: i32,
    left: f32,
    top: f32,
    right: f32,
    bottom: f32,
    rx: f32,
    ry: f32,
    radius_px: f32,
    shadow: jobject,
    used_w: i32,
    used_h: i32,
    clear_w: i32,
    clear_h: i32,
) -> i64 {
    let rrect = RoundRectArgs { skia_class, left, top, right, bottom, rx, ry, radius_px };
    let mask_hole = shadow::hole::Hole::unpacked(mask_hole);
    place_export(env, keep, mask_hole, rrect, shadow, (used_w, used_h), (clear_w, clear_h), Write::InvertedHole)
}

/// `ObvidNative.builderNineMaskHole`: the rect of the blurred small round rect kept in the A8 `keep` bitmap (its
/// top-left window, as `builderNineKeep` leaves it for these arguments' plan, which must be a nine-patch) whose image
/// is the inverted nine-patch's hole for every placement of that mask ([`shadow::nine::kept_mask_hole`]), packed by
/// [`shadow::hole::Hole::packed`] (in mask pixels), or a negative status (−1 not a nine-patch, the lock statuses, −4
/// `keep` smaller than the blurred mask). Reads only.
#[cfg(target_os = "android")]
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_serhiiyaremych_obvid_ObvidNative_builderNineMaskHole(
    env: *mut JNIEnv,
    _class: jclass,
    keep: jobject,
    skia_class: i32,
    left: f32,
    top: f32,
    right: f32,
    bottom: f32,
    rx: f32,
    ry: f32,
    radius_px: f32,
) -> i64 {
    use super::bitmap::Locked;
    use super::{BUILDER_BAD_ARGUMENT, BUILDER_OUT_OF_BOUNDS};
    use shadow::A8;
    let rrect = RoundRectArgs { skia_class, left, top, right, bottom, rx, ry, radius_px };
    let Some(patch) = rrect.nine_patch() else { return BUILDER_BAD_ARGUMENT as i64 };
    // SAFETY: env and the keep bitmap are valid for this call; the guard unlocks before returning.
    let k = match unsafe { Locked::lock(env, keep) } {
        Ok(l) => l,
        Err(e) => return e as i64,
    };
    if patch.mask_w() > k.width || patch.mask_h() > k.height {
        return BUILDER_OUT_OF_BOUNDS as i64;
    }
    // SAFETY: the locked keep buffer holds `len()` bytes and stays locked while `k` lives.
    let keep_px = unsafe { core::slice::from_raw_parts(k.pixels as *const u8, k.len()) };
    shadow::nine::kept_mask_hole(A8 { pixels: keep_px, width: k.width, height: k.height, stride: k.stride }, &patch)
        .packed()
}
