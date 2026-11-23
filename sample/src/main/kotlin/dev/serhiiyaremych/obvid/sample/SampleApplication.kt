// Copyright 2026, Serhii Yaremych (desugar-64) and the Obvid project contributors
// SPDX-License-Identifier: Apache-2.0

package dev.serhiiyaremych.obvid.sample

import android.app.Application
import android.os.Trace
import android.provider.Settings
import android.util.Log
import androidx.compose.runtime.tooling.ComposeToolingApi
import androidx.compose.runtime.tooling.ComposeToolingFlags
import kotlin.concurrent.timer

/** The global setting that turns on Compose's verbose tracing ([SampleApplication]). */
const val VerboseTracingSetting = "obvid_verbose_tracing"

/** Process counter track set to 1 while Compose's verbose tracing is on ([SampleApplication]). */
const val VerboseTracingCounter = "demo:composeVerboseTracing"

/**
 * Turns on Compose's verbose tracing (`ComposeToolingFlags.isVerboseTracingEnabled`: trace sections for effects,
 * measure and layout passes, lazy prefetch) when the global setting [VerboseTracingSetting] is non-zero. For diagnostic
 * Perfetto captures only: the extra sections cost time in every frame, so never in a timing run.
 *
 *     adb shell settings put global obvid_verbose_tracing 1      # on, from the next process start
 *     adb shell settings delete global obvid_verbose_tracing     # off (the setting survives reboots)
 *
 * Read once per process, before any Compose code runs: `am force-stop` the sample after changing it. While on, the
 * process logs one line (tag `ObvidSample`) and sets the counter [VerboseTracingCounter] to 1 every second from a
 * background thread, so any capture of the app shows the counter track. Off (the default), nothing else happens.
 *
 * A global setting because it is the switch both sides reach through public API: `adb shell settings` writes it
 * without root, and any app reads `Settings.Global` (a key the framework does not define is not subject to the
 * readable-settings restriction). A `debug.*` system property needs the hidden `android.os.SystemProperties`, an
 * intent extra arrives only after `Application.onCreate`, and a flag file in the app's data needs `run-as`, which the
 * release (non-debuggable) sample does not allow.
 */
class SampleApplication : Application() {
  @OptIn(ComposeToolingApi::class)
  override fun onCreate() {
    // Before super.onCreate(), before any activity or Compose code runs.
    if (verboseTracingRequested()) {
      ComposeToolingFlags.isVerboseTracingEnabled = true
      Log.i(TAG, "Compose verbose tracing on (global setting $VerboseTracingSetting)")
      timer("ob-verbose-marker", daemon = true, period = 1000L) { Trace.setCounter(VerboseTracingCounter, 1L) }
    }
    super.onCreate()
  }

  private fun verboseTracingRequested(): Boolean =
    try {
      Settings.Global.getInt(contentResolver, VerboseTracingSetting, 0) != 0
    } catch (e: SecurityException) {
      Log.w(TAG, "cannot read global setting $VerboseTracingSetting; Compose verbose tracing off", e)
      false
    }

  private companion object {
    const val TAG = "ObvidSample"
  }
}
