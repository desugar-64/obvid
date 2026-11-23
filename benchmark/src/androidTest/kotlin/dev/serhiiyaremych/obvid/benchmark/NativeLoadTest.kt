// Copyright 2026, Serhii Yaremych (desugar-64) and the Obvid project contributors
// SPDX-License-Identifier: Apache-2.0

package dev.serhiiyaremych.obvid.benchmark

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.serhiiyaremych.obvid.ObvidTesting
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Smoke test: the Rust library with the test and benchmark surface (Cargo feature `testing`, packaged by the
 * `benchmark` build type) loads inside the benchmark APK.
 */
@RunWith(AndroidJUnit4::class)
class NativeLoadTest {
  @Test fun libraryLoads() = assertEquals(1, ObvidTesting.abiVersion())
}
