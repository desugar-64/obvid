// Copyright 2026, Serhii Yaremych (desugar-64) and the Obvid project contributors
// SPDX-License-Identifier: Apache-2.0

package dev.serhiiyaremych.obvid.sample

import android.content.Context
import android.provider.Settings
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.DropShadowScope
import androidx.compose.ui.draw.InnerShadowScope
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.shadow.Shadow
import dev.serhiiyaremych.obvid.ShadowBuilds
import dev.serhiiyaremych.obvid.ShadowExtent
import androidx.compose.ui.draw.dropShadow as composeDropShadow
import androidx.compose.ui.draw.innerShadow as composeInnerShadow
import dev.serhiiyaremych.obvid.dropShadow as cpuDropShadow
import dev.serhiiyaremych.obvid.innerShadow as cpuInnerShadow

/** Intent string extra that picks the shadow implementation: `compose` (default), `cpu` or `none`. */
const val ShadowImplExtra = "shadowImpl"

/** The `dropShadow` the sample draws with; [None] draws the same screen without any shadow modifier. */
enum class ShadowImpl(val extra: String, val label: String) {
  Compose("compose", "Compose dropShadow"),
  Cpu("cpu", "CPU dropShadow"),
  None("none", "no shadow");

  companion object {
    fun parse(value: String?): ShadowImpl =
      entries.firstOrNull { it.extra == value } ?: if (value == null) Compose else error("Unknown $ShadowImplExtra '$value'")
  }
}

/** Set once by `MainActivity` from [ShadowImplExtra] before any content; every run starts a fresh process. */
var shadowImpl: ShadowImpl = ShadowImpl.Compose

/**
 * The implementation a screen's shadows draw with, provided at its root: by `MainActivity` from [shadowImpl] for a
 * scenario, by the demo app's Compose / Obvid switch. Obvid by default (previews). Static: a switch rebuilds the screen
 * anyway.
 */
val LocalShadowImpl = staticCompositionLocalOf { ShadowImpl.Cpu }

/**
 * The one place the sample draws a shadow (scenarios and the stress grid): Compose's
 * `androidx.compose.ui.draw.dropShadow` or Obvid's, `dev.serhiiyaremych.obvid.dropShadow`, per [shadowImpl].
 *
 * Each draw sits inside the [ShadowTraceSection] trace section, the same way for both; [ShadowImpl.None] keeps the
 * section and draws no shadow.
 */
fun Modifier.demoShadow(shape: Shape, shadow: Shadow): Modifier =
  when (shadowImpl) {
    ShadowImpl.Compose -> traceDraw().composeDropShadow(shape, shadow)
    ShadowImpl.Cpu -> traceDraw().cpuDropShadow(shape, shadow)
    ShadowImpl.None -> traceDraw()
  }

/** Block form: the block runs in the draw phase, so animated params invalidate draw only. */
fun Modifier.demoShadow(shape: Shape, block: DropShadowScope.() -> Unit): Modifier =
  when (shadowImpl) {
    ShadowImpl.Compose -> traceDraw().composeDropShadow(shape, block)
    ShadowImpl.Cpu -> traceDraw().cpuDropShadow(shape, block)
    ShadowImpl.None -> traceDraw()
  }

/**
 * The tier scenarios' drop shadow, per [shadowImpl], inside a [ShadowTraceSection] section that holds the shadow draw
 * only ([shadowSection]); [ShadowImpl.None] keeps the empty section. [impl] overrides the process-wide choice.
 */
fun Modifier.measuredDropShadow(shape: Shape, shadow: Shadow, impl: ShadowImpl = shadowImpl): Modifier =
  shadowSection(
    ShadowTraceSection,
    when (impl) {
      ShadowImpl.Compose -> Modifier.composeDropShadow(shape, shadow)
      ShadowImpl.Cpu -> Modifier.cpuDropShadow(shape, shadow)
      ShadowImpl.None -> Modifier
    },
  )

/**
 * The global setting that, when non-zero, makes every inner shadow below draw nothing (drop shadows unchanged), to
 * measure what the inner shadows cost: `adb shell settings put global obvid_demo_no_inner 1`, then force-stop the
 * sample; `settings delete global obvid_demo_no_inner` to undo. Read once per process by [readNoInnerShadowsSetting].
 */
const val NoInnerShadowsSetting = "obvid_demo_no_inner"

private var noInnerShadows = false
private var noInnerShadowsRead = false

/** Reads [NoInnerShadowsSetting] on the first call in this process; call it at a screen root, before any inner shadow. */
fun readNoInnerShadowsSetting(context: Context) {
  if (noInnerShadowsRead) return
  noInnerShadowsRead = true
  noInnerShadows = try {
    Settings.Global.getInt(context.contentResolver, NoInnerShadowsSetting, 0) != 0
  } catch (_: SecurityException) {
    false
  }
}

/**
 * The global setting that, when 1, builds Obvid's first shadows on worker threads ([ShadowBuilds.Parallel]) in the demo
 * app and its scenes: `adb shell settings put global obvid_demo_parallel_builds 1`, then force-stop the sample;
 * `settings delete global obvid_demo_parallel_builds` to undo. Read once per process by [readParallelBuildsSetting].
 */
const val ParallelBuildsSetting = "obvid_demo_parallel_builds"

private var parallelBuilds = false
private var parallelBuildsRead = false

/** Where first shadows build per [ParallelBuildsSetting], read on the first call in this process. */
fun readParallelBuildsSetting(context: Context): ShadowBuilds {
  if (!parallelBuildsRead) {
    parallelBuildsRead = true
    parallelBuilds = try {
      Settings.Global.getInt(context.contentResolver, ParallelBuildsSetting, 0) == 1
    } catch (_: SecurityException) {
      false
    }
  }
  return if (parallelBuilds) ShadowBuilds.Parallel else ShadowBuilds.Inline
}

/**
 * The global setting that, when 1, gives Obvid's drop shadows the full extent ([ShadowExtent.Full]) in the demo app:
 * `adb shell settings put global obvid_demo_full_extent 1`, then force-stop the sample; `settings delete global
 * obvid_demo_full_extent` to undo. Read once per process by [readFullExtentSetting].
 */
const val FullExtentSetting = "obvid_demo_full_extent"

private var fullExtent = false
private var fullExtentRead = false

/**
 * The drop shadows' extent per [FullExtentSetting], read on the first call in this process; provide it at a screen
 * root.
 */
fun readFullExtentSetting(context: Context): ShadowExtent {
  if (!fullExtentRead) {
    fullExtentRead = true
    fullExtent = try {
      Settings.Global.getInt(context.contentResolver, FullExtentSetting, 0) == 1
    } catch (_: SecurityException) {
      false
    }
  }
  return if (fullExtent) ShadowExtent.Full else ShadowExtent.Compose
}

/** [impl], or [ShadowImpl.None] (an empty section) when [NoInnerShadowsSetting] is on. */
private fun innerImpl(impl: ShadowImpl): ShadowImpl = if (noInnerShadows) ShadowImpl.None else impl

/**
 * The tier scenarios' inner shadow, per [shadowImpl], inside an [InnerShadowTraceSection] section that holds the
 * shadow draw only. Place it after the background: the shadow draws over what precedes it in the chain.
 */
fun Modifier.measuredInnerShadow(shape: Shape, shadow: Shadow, impl: ShadowImpl = shadowImpl): Modifier =
  shadowSection(
    InnerShadowTraceSection,
    when (innerImpl(impl)) {
      ShadowImpl.Compose -> Modifier.composeInnerShadow(shape, shadow)
      ShadowImpl.Cpu -> Modifier.cpuInnerShadow(shape, shadow)
      ShadowImpl.None -> Modifier
    },
  )

/** Block form of [measuredDropShadow]: the block runs in the draw phase, so animated params invalidate draw only. */
fun Modifier.measuredDropShadow(shape: Shape, impl: ShadowImpl = shadowImpl, block: DropShadowScope.() -> Unit): Modifier =
  shadowSection(
    ShadowTraceSection,
    when (impl) {
      ShadowImpl.Compose -> Modifier.composeDropShadow(shape, block)
      ShadowImpl.Cpu -> Modifier.cpuDropShadow(shape, block)
      ShadowImpl.None -> Modifier
    },
  )

/** Block form of [measuredInnerShadow]: the block runs in the draw phase, so animated params invalidate draw only. */
fun Modifier.measuredInnerShadow(shape: Shape, impl: ShadowImpl = shadowImpl, block: InnerShadowScope.() -> Unit): Modifier =
  shadowSection(
    InnerShadowTraceSection,
    when (innerImpl(impl)) {
      ShadowImpl.Compose -> Modifier.composeInnerShadow(shape, block)
      ShadowImpl.Cpu -> Modifier.cpuInnerShadow(shape, block)
      ShadowImpl.None -> Modifier
    },
  )

/**
 * [measuredDropShadow] (block form) for a shape that changes in the draw phase: [shape] is read on every draw and a
 * new value goes to the shadow as if recomposed with it ([LiveShapeShadowElement]), so nothing recomposes.
 */
fun Modifier.measuredLiveDropShadow(
  shape: () -> Shape,
  impl: ShadowImpl = shadowImpl,
  block: DropShadowScope.() -> Unit,
): Modifier =
  shadowSection(
    ShadowTraceSection,
    when (impl) {
      ShadowImpl.Compose -> LiveShapeShadowElement(shape) { Modifier.composeDropShadow(it, block) }
      ShadowImpl.Cpu -> LiveShapeShadowElement(shape) { Modifier.cpuDropShadow(it, block) }
      ShadowImpl.None -> Modifier
    },
  )

/** [measuredInnerShadow] (block form) for a shape that changes in the draw phase, as [measuredLiveDropShadow]. */
fun Modifier.measuredLiveInnerShadow(
  shape: () -> Shape,
  impl: ShadowImpl = shadowImpl,
  block: InnerShadowScope.() -> Unit,
): Modifier =
  shadowSection(
    InnerShadowTraceSection,
    when (innerImpl(impl)) {
      ShadowImpl.Compose -> LiveShapeShadowElement(shape) { Modifier.composeInnerShadow(it, block) }
      ShadowImpl.Cpu -> LiveShapeShadowElement(shape) { Modifier.cpuInnerShadow(it, block) }
      ShadowImpl.None -> Modifier
    },
  )

/** Shown in the grid's top bar chip and, with `hud=true`, under the scenario cards. */
val ShadowImplementationLabel: String
  get() = shadowImpl.label
