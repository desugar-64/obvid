// Copyright 2026, Serhii Yaremych (desugar-64) and the Obvid project contributors
// SPDX-License-Identifier: Apache-2.0

package dev.serhiiyaremych.obvid

import android.graphics.Bitmap
import android.util.Log
import dev.serhiiyaremych.obvid.builder.ShadowBitmapBuilder

/**
 * JNI entry to the Rust crate in `obvid/rust`, the shipping surface: the library smoke check and the `builder*` calls
 * of [ShadowBitmapBuilder]. Internal; its members stay public so that their JVM names match the Rust `Java_…` symbols
 * (internal members get mangled names). The test natives are in `ObvidTesting` (debug and benchmark builds
 * only).
 */
internal object ObvidNative {
  init {
    System.loadLibrary("obvid")
  }

  /**
   * Smoke check that the native library loads: the Rust `ABI_VERSION` (`jni.rs`), which [NativeShadows]
   * compares with its `EXPECTED_ABI`. When the JNI surface changes, bump both (and the value `NativeLoadTest` in
   * :benchmark expects).
   */
  @JvmStatic external fun abiVersion(): Int

  // Shadow builder (`shadow::ShadowBuilder`, used by [ShadowBitmapBuilder]). 64-bit Arm only.

  /** Binds the calling thread to the CPUs set in [mask] (bit n = CPU n): 0, or -1 when the kernel refuses. */
  @JvmStatic external fun pinCurrentThread(mask: Long): Int

  /** A new native builder (kernel scratch and nine-patch mask buffer, grow-only). Free it with [builderDestroy]. */
  @JvmStatic external fun builderCreate(): Long

  /** Frees a builder made by [builderCreate]; 0 is ignored. */
  @JvmStatic external fun builderDestroy(handle: Long)

  /** The blur border (Skia's margin) of `BlurMaskFilter(radiusPx, NORMAL)` on each side of a mask. No allocation. */
  @JvmStatic external fun builderBorder(radiusPx: Float): Int

  /**
   * The full-extent margin of `BlurMaskFilter(radiusPx, NORMAL)` on Obvid's pipeline: the smallest k ≥ 1 such that the
   * blurred output is 0 at every pixel k or more pixels beyond the mask's pixel bounds, for any mask (`skia::extent`).
   * 1 without blur. Allocates one line of the kernel's length.
   */
  @JvmStatic external fun builderFullMargin(radiusPx: Float): Int

  /**
   * The full-extent margin of a draw left to the device's Skia (its analytic rect blur, older box blurs): a bound,
   * `3·⌈σ⌉ + 1` with σ = `0.57735·radiusPx + 0.5`, at least [builderFullMargin].
   */
  @JvmStatic external fun builderSkiaReachMargin(radiusPx: Float): Int

  /**
   * Blurs the top-left [maskW] × [maskH] window of the ALPHA_8 [mask] (its origin at ([maskX], [maskY]) in the shadow
   * bitmap) at the sigma of `BlurMaskFilter(radiusPx, NORMAL)` and writes it into the ALPHA_8 [shadow] at
   * (mask origin − border), clipped to `[0, usedW) × [0, usedH)`. [mode] 0 = replace: copies the window and zeroes the
   * rest of `[0, max(clearW, usedW)) × [0, max(clearH, usedH))`; 1 = source-over (Skia's A8 mask blit). Both bitmaps
   * are locked for the call; unlocking [shadow] bumps its generation id. Returns 0, or a negative status: −1 bad
   * argument, −2 lock failed, −3 not ALPHA_8, −4 a rect exceeds its bitmap.
   */
  @JvmStatic
  external fun builderBlur(
    handle: Long,
    mask: Bitmap,
    maskW: Int,
    maskH: Int,
    maskX: Int,
    maskY: Int,
    radiusPx: Float,
    shadow: Bitmap,
    usedW: Int,
    usedH: Int,
    clearW: Int,
    clearH: Int,
    mode: Int,
  ): Int

  /**
   * [builderBlur] of the [maskW] × [maskH] window of the ALPHA_8 [mask] whose top-left is ([srcX], [srcY]) in it,
   * instead of its top-left window: a mask drawn at its device coordinates. Same placement, modes and status codes.
   */
  @JvmStatic
  external fun builderBlurFrom(
    handle: Long,
    mask: Bitmap,
    srcX: Int,
    srcY: Int,
    maskW: Int,
    maskH: Int,
    maskX: Int,
    maskY: Int,
    radiusPx: Float,
    shadow: Bitmap,
    usedW: Int,
    usedH: Int,
    clearW: Int,
    clearH: Int,
    mode: Int,
  ): Int

  /**
   * Skia's route (`shadow::nine::plan_round_rect`) for `drawRoundRect(left, top, right, bottom, rx, ry)` with
   * `BlurMaskFilter(radiusPx, NORMAL)` on a canvas with an identity matrix (Compose's round-rect shadow), for the
   * Skia class [roundRectClass] (only [SKIA_CLASS_B] is known): [PLAN_SKIA] (rect, empty, over Skia's limits, unknown
   * class), [PLAN_FULL_MASK] (a simple round rect with nothing to stretch, or an oval: Skia blurs the whole mask of the
   * shape's path), or any other value, a nine-patch, whose small round rect is `(0, 0, w, h)` with w = [planRectWidth]
   * and h = [planRectHeight]. Pure, no allocation.
   */
  @JvmStatic
  external fun builderPlanRoundRect(
    roundRectClass: Int,
    left: Float,
    top: Float,
    right: Float,
    bottom: Float,
    rx: Float,
    ry: Float,
    radiusPx: Float,
  ): Long

  /**
   * The `roundRectClass` of [builderPlanRoundRect] and [builderNine]: Android 16–17's `filterRRectToNine` (integer
   * pieces with a stretch of 1), the only class the library accepts.
   */
  const val SKIA_CLASS_B = 1

  /** The two plans that are not a nine-patch; a nine-patch packs two floats ≥ 3, never 0 or 1. */
  const val PLAN_SKIA = 0L
  const val PLAN_FULL_MASK = 1L

  /** The nine-patch's small round rect width: the f32 in the low 32 bits. */
  fun planRectWidth(plan: Long): Float = Float.fromBits(plan.toInt())

  /** Its height: the f32 in the high 32 bits. */
  fun planRectHeight(plan: Long): Float = Float.fromBits((plan ushr 32).toInt())

  /**
   * The nine-patch route: with the arguments of a [builderPlanRoundRect] call that returned a nine-patch (the
   * plan is recomputed natively), blurs the top-left `ceil(w) × ceil(h)` window of the ALPHA_8 [mask] (the small
   * round rect `(0, 0, w, h)` of the plan with corner radii ([rx], [ry]), drawn anti-aliased without a mask filter)
   * and writes Skia's nine-patch into the ALPHA_8 [shadow], clipped to `[0, usedW) × [0, usedH)`; every other pixel of
   * `[0, max(clearW, usedW)) × [0, max(clearH, usedH))` is set to 0. Same status codes as [builderBlur] (−1 also for
   * an unknown class or a plan that is not a nine-patch). The blurred small round rect is not kept (the builder's own
   * buffer): for a first build whose mask is not published.
   */
  @JvmStatic
  external fun builderNine(
    handle: Long,
    mask: Bitmap,
    roundRectClass: Int,
    left: Float,
    top: Float,
    right: Float,
    bottom: Float,
    rx: Float,
    ry: Float,
    radiusPx: Float,
    shadow: Bitmap,
    usedW: Int,
    usedH: Int,
    clearW: Int,
    clearH: Int,
  ): Int

  /**
   * [builderNine] that keeps the blurred small round rect: it is blurred into the top-left
   * `(ceil(w) + 2·border) × (ceil(h) + 2·border)` window of the ALPHA_8 [keep] (border = [builderBorder] of
   * [radiusPx]; the rest of [keep] is untouched) and the nine-patch is written from there. [builderNinePlace] can then
   * write the nine-patch of any round rect whose plan has the same small round rect ([builderPlanRoundRect]'s value),
   * the same corner radii and the same [radiusPx] from [keep] alone. Same status codes as [builderNine] (−4 also when
   * [keep] is smaller than the blurred mask; [mask], [keep] and [shadow] must be distinct bitmaps).
   */
  @JvmStatic
  external fun builderNineKeep(
    handle: Long,
    mask: Bitmap,
    keep: Bitmap,
    roundRectClass: Int,
    left: Float,
    top: Float,
    right: Float,
    bottom: Float,
    rx: Float,
    ry: Float,
    radiusPx: Float,
    shadow: Bitmap,
    usedW: Int,
    usedH: Int,
    clearW: Int,
    clearH: Int,
  ): Int

  /**
   * Writes the nine-patch of the plan of these arguments (it must be a nine-patch) into the ALPHA_8 [shadow] as
   * [builderNine] does, from the blurred small round rect [builderNineKeep] left in [keep]: no drawing, no blur. The
   * caller guarantees that [keep] was written for a plan with the same small round rect, corner radii and radius (the
   * bytes are not checked). Same status codes as [builderNineKeep].
   */
  @JvmStatic
  external fun builderNinePlace(
    keep: Bitmap,
    roundRectClass: Int,
    left: Float,
    top: Float,
    right: Float,
    bottom: Float,
    rx: Float,
    ry: Float,
    radiusPx: Float,
    shadow: Bitmap,
    usedW: Int,
    usedH: Int,
    clearW: Int,
    clearH: Int,
  ): Int

  /**
   * [builderNine] with every value v inside `[0, usedW) × [0, usedH)` written as `255 − v`: exactly [builderNine]
   * followed by [builderInvert] of [shadow] over the used rect, in one pass (an inner shadow's bitmap). The rest of
   * `[0, max(clearW, usedW)) × [0, max(clearH, usedH))` is set to 0. Same arguments and status codes as [builderNine].
   */
  @JvmStatic
  external fun builderNineInverted(
    handle: Long,
    mask: Bitmap,
    roundRectClass: Int,
    left: Float,
    top: Float,
    right: Float,
    bottom: Float,
    rx: Float,
    ry: Float,
    radiusPx: Float,
    shadow: Bitmap,
    usedW: Int,
    usedH: Int,
    clearW: Int,
    clearH: Int,
  ): Int

  /**
   * [builderNineKeep] with the nine-patch written as [builderNineInverted] writes it. [keep] gets the same blurred small
   * round rect (not inverted), so either [builderNinePlace] or [builderNinePlaceInverted] can place it. Same arguments
   * and status codes as [builderNineKeep].
   */
  @JvmStatic
  external fun builderNineKeepInverted(
    handle: Long,
    mask: Bitmap,
    keep: Bitmap,
    roundRectClass: Int,
    left: Float,
    top: Float,
    right: Float,
    bottom: Float,
    rx: Float,
    ry: Float,
    radiusPx: Float,
    shadow: Bitmap,
    usedW: Int,
    usedH: Int,
    clearW: Int,
    clearH: Int,
  ): Int

  /**
   * [builderNinePlace] with the nine-patch written as [builderNineInverted] writes it. Same arguments, contract and
   * status codes as [builderNinePlace].
   */
  @JvmStatic
  external fun builderNinePlaceInverted(
    keep: Bitmap,
    roundRectClass: Int,
    left: Float,
    top: Float,
    right: Float,
    bottom: Float,
    rx: Float,
    ry: Float,
    radiusPx: Float,
    shadow: Bitmap,
    usedW: Int,
    usedH: Int,
    clearW: Int,
    clearH: Int,
  ): Int

  /**
   * Skia's route (`shadow::rects::plan_rects`) for a frame: the path whose nested rects (Skia's `IsNestedFillRects`) are
   * the outer rect (the one that contains the other) and the inner rect, filled with `BlurMaskFilter(radiusPx, NORMAL)`
   * on a canvas with a translate-only matrix, as Android 17's `filterRectsToNine` decides it: [PLAN_SKIA] (no blur, or
   * rects that are not finite or not nested), [PLAN_RECTS_MASK] (Skia returns unimplemented and blurs the whole mask of
   * the path: an outer rect over 32767, a hole too small for the blur), or any other value, a nine-patch whose small
   * frame shrinks the rects' right and bottom edges by [planRectsDx] and [planRectsDy] whole pixels. Pure, no
   * allocation.
   */
  @JvmStatic
  external fun builderPlanRects(
    outerLeft: Float,
    outerTop: Float,
    outerRight: Float,
    outerBottom: Float,
    innerLeft: Float,
    innerTop: Float,
    innerRight: Float,
    innerBottom: Float,
    radiusPx: Float,
  ): Long

  /** [builderPlanRects]' mask route (its nine-patch values have bit 62 set, never 0 or 1). */
  const val PLAN_RECTS_MASK = 1L

  /** A frame nine-patch's dx: the int in the low 32 bits. */
  fun planRectsDx(plan: Long): Int = plan.toInt()

  /** Its dy: bits 32..62. */
  fun planRectsDy(plan: Long): Int = ((plan ushr 32) and 0x3FFF_FFFFL).toInt()

  /**
   * The frame nine-patch route: with the arguments of a [builderPlanRects] call that returned a nine-patch (the plan is
   * recomputed natively), blurs the top-left window of the ALPHA_8 [mask] the size of `roundOut(smallR0)` (the small
   * frame `smallR0 = (outerLeft, outerTop, outerRight − dx, outerBottom − dy)` minus `smallR1` (the inner rect
   * likewise), even-odd, anti-aliased, no mask filter, drawn under the translate `(−floor(smallR0.left),
   * −floor(smallR0.top))`) and writes Skia's nine-patch without its centre into the ALPHA_8 [shadow], clipped to
   * `[0, usedW) × [0, usedH)`; every other pixel of `[0, max(clearW, usedW)) × [0, max(clearH, usedH))` is set to 0.
   * With [inverted] every value v inside the used rect is written as `255 − v` (an inner shadow's bitmap; the centre
   * 255). Same status codes as [builderBlur] (−1 also for a plan that is not a nine-patch).
   */
  @JvmStatic
  external fun builderNineRects(
    handle: Long,
    mask: Bitmap,
    outerLeft: Float,
    outerTop: Float,
    outerRight: Float,
    outerBottom: Float,
    innerLeft: Float,
    innerTop: Float,
    innerRight: Float,
    innerBottom: Float,
    radiusPx: Float,
    shadow: Bitmap,
    usedW: Int,
    usedH: Int,
    clearW: Int,
    clearH: Int,
    inverted: Boolean,
  ): Int

  /**
   * Inverts `[0, usedW) × [0, usedH)` of the ALPHA_8 [shadow] in place (`255 − v`): an inner shadow's bitmap from the
   * blurred shape written by [builderBlur] or [builderBlurFrom] (Compose's opaque `Xor` rect over A8 is exactly
   * `255 − d`; the nine-patch exports have `…Inverted` twins that write it in the same pass). The bitmap is locked
   * for the call; unlocking it bumps its generation id. Same status codes as [builderBlur].
   */
  @JvmStatic external fun builderInvert(shadow: Bitmap, usedW: Int, usedH: Int): Int

  /**
   * [builderInvert], also returning the hole of the inverted bitmap: a rect of 0s inside `[0, usedW) × [0, usedH)`
   * grown from the seed pixel ([seedX], [seedY]) (none when the seed lies outside the used rect or is not 0), found
   * while inverting. Returns the packed hole ([holeLeft], …; 0 = no hole) or a negative status as [builderInvert]'s
   * (−1 also for a zero [handle]).
   */
  @JvmStatic external fun builderInvertHole(handle: Long, shadow: Bitmap, usedW: Int, usedH: Int, seedX: Int, seedY: Int): Long

  /**
   * [builderNineInverted], also returning the hole of the written bitmap (a rect of 0s inside the used rect, read from
   * the blurred small round rect and its stretch, not from the bitmap). Returns the packed hole ([holeLeft], …; 0 = no
   * hole) or a negative status as [builderNineInverted]'s.
   */
  @JvmStatic
  external fun builderNineInvertedHole(
    handle: Long,
    mask: Bitmap,
    roundRectClass: Int,
    left: Float,
    top: Float,
    right: Float,
    bottom: Float,
    rx: Float,
    ry: Float,
    radiusPx: Float,
    shadow: Bitmap,
    usedW: Int,
    usedH: Int,
    clearW: Int,
    clearH: Int,
  ): Long

  /** [builderNineKeepInverted], also returning the hole as [builderNineInvertedHole] does. */
  @JvmStatic
  external fun builderNineKeepInvertedHole(
    handle: Long,
    mask: Bitmap,
    keep: Bitmap,
    roundRectClass: Int,
    left: Float,
    top: Float,
    right: Float,
    bottom: Float,
    rx: Float,
    ry: Float,
    radiusPx: Float,
    shadow: Bitmap,
    usedW: Int,
    usedH: Int,
    clearW: Int,
    clearH: Int,
  ): Long

  /**
   * [builderNinePlaceInverted], also returning the hole as [builderNineInvertedHole] does, mapped to this placement
   * from [maskHole]: [keep]'s rect from [builderNineMaskHole] (the caller guarantees it was found for [keep]'s bytes),
   * or with [MASK_HOLE_UNKNOWN] that rect read from [keep] now.
   */
  @JvmStatic
  external fun builderNinePlaceInvertedHole(
    keep: Bitmap,
    maskHole: Long,
    roundRectClass: Int,
    left: Float,
    top: Float,
    right: Float,
    bottom: Float,
    rx: Float,
    ry: Float,
    radiusPx: Float,
    shadow: Bitmap,
    usedW: Int,
    usedH: Int,
    clearW: Int,
    clearH: Int,
  ): Long

  /**
   * The rect of the blurred small round rect kept in [keep] (as [builderNineKeep] leaves it for these arguments'
   * plan) whose image is the inverted nine-patch's hole at every placement of that mask, packed as a hole (in mask
   * pixels), for [builderNinePlaceInvertedHole]; or a negative status as [builderNinePlace]'s. Reads only.
   */
  @JvmStatic
  external fun builderNineMaskHole(
    keep: Bitmap,
    roundRectClass: Int,
    left: Float,
    top: Float,
    right: Float,
    bottom: Float,
    rx: Float,
    ry: Float,
    radiusPx: Float,
  ): Long

  /** A kept mask's rect for [builderNinePlaceInvertedHole] not found yet. */
  const val MASK_HOLE_UNKNOWN = -1L

  /** A packed hole's left edge (inclusive), in bitmap pixels: bits 0..16. */
  fun holeLeft(hole: Long): Int = (hole and 0xFFFF).toInt()

  /** Its top edge (inclusive): bits 16..32. */
  fun holeTop(hole: Long): Int = ((hole ushr 16) and 0xFFFF).toInt()

  /** Its right edge (exclusive): bits 32..48. */
  fun holeRight(hole: Long): Int = ((hole ushr 32) and 0xFFFF).toInt()

  /** Its bottom edge (exclusive): bits 48..63. */
  fun holeBottom(hole: Long): Int = ((hole ushr 48) and 0x7FFF).toInt()
}

/**
 * Whether the native pipeline can be used: the library loads (it is packaged for arm64-v8a only) and its JNI surface
 * is the one this Kotlin code expects. Checked once; any failure (missing library, wrong ABI, link error) makes the
 * modifiers delegate to Compose's own `dropShadow` / `innerShadow`.
 */
internal object NativeShadows {
  private const val EXPECTED_ABI = 2

  val isAvailable: Boolean by lazy {
    try {
      val abi = ObvidNative.abiVersion()
      if (abi != EXPECTED_ABI) logFallback("native ABI $abi, expected $EXPECTED_ABI", null)
      abi == EXPECTED_ABI
    } catch (t: Throwable) {
      logFallback(t.toString(), t)
      false
    }
  }

  // Runs at most once per process: from the lazy initializer above.
  private fun logFallback(reason: String, t: Throwable?) {
    Log.w("Obvid", "Native pipeline unavailable ($reason); dropShadow and innerShadow fall back to Compose's own", t)
  }

  /** Test switch: forces the Compose fallback for modifiers created while it is set. */
  @Volatile var forceComposeFallback: Boolean = false

  /**
   * Test switch: an inner shadow whose build found a hole is drawn in one draw anyway, not in bands, while it is set.
   * Read only by draws that would go in bands.
   */
  @Volatile var forceSingleDraw: Boolean = false

  /**
   * Whether the modifiers delegate to Compose's `dropShadow` / `innerShadow`: the library is unavailable or the
   * fallback is forced.
   */
  val useComposeFallback: Boolean
    get() = forceComposeFallback || !isAvailable
}
