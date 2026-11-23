// Copyright 2026, Serhii Yaremych (desugar-64) and the Obvid project contributors
// SPDX-License-Identifier: Apache-2.0

package dev.serhiiyaremych.obvid

import androidx.annotation.RestrictTo

/**
 * The test surface of the native library (the Rust crate's `testing` feature): in the debug and benchmark builds only,
 * never in release. The blur on Java arrays for the on-device exactness test.
 */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
public object ObvidTesting {
  init {
    System.loadLibrary("obvid")
  }

  /** Smoke check that the library with the testing surface loads; the same version as the shipping surface's. */
  @JvmStatic public external fun abiVersion(): Int

  /**
   * The mask blur on a tightly packed A8 mask of [width] × [height], at the sigma Android derives from
   * `BlurMaskFilter(radiusPx, NORMAL)`: the scalar port of Skia's (`skia::blur_a8`), or with [neon] the NEON version
   * (`blur::blur_a8_with`: NEON for sigma >= 2, the scalar port's code below; identical output). The result is
   * the source grown by [ObvidNative.builderBorder] on each side (Skia's `SkMask::PrepareDestination` layout).
   * Copies in and out; 64-bit Arm only.
   */
  @JvmStatic
  public external fun blurA8(src: ByteArray, width: Int, height: Int, radiusPx: Float, neon: Boolean): ByteArray
}
