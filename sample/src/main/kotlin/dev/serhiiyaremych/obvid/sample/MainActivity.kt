// Copyright 2026, Serhii Yaremych (desugar-64) and the Obvid project contributors
// SPDX-License-Identifier: Apache-2.0

package dev.serhiiyaremych.obvid.sample

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.serhiiyaremych.obvid.sample.app.DemoApp
import dev.serhiiyaremych.obvid.sample.discover.DiscoverPhotos
import dev.serhiiyaremych.obvid.sample.discover.DiscoverRevealScenario
import dev.serhiiyaremych.obvid.sample.discover.DiscoverScenarios
import dev.serhiiyaremych.obvid.sample.discover.DiscoverScreen

/** Intent Long extra: finish the activity this many ms after onCreate (0 = never). */
const val FinishAfterExtra = "finishAfterMs"

/**
 * Shows one shadow scenario, chosen by the `scenario` intent extra, so Macrobenchmark and Perfetto can measure each
 * one in isolation. See [Scenarios] for the list. Without the extra it shows the tabbed demo app ([DemoApp]): the
 * Discover feed and three of the tier scenes behind a global Compose / Obvid switch, starting on Obvid unless
 * `shadowImpl` says otherwise (and with the frame HUD on with `hud=true`), without the benchmark's lock-screen and
 * keep-awake window flags. `scenario=t7_discover` (or `t7_discover_reveal`) is the Discover feed alone ([DiscoverScreen]).
 * `scenario=grid` opens the [StressGrid] instead, with extras `shadows` (none | static | mix, default
 * mix), `hud` (true | false, default false) and `first` (item index at the top at start, default 0).
 * Any scenario takes `finishAfterMs` (Long): finish the activity that long after start, and `shadowImpl`
 * (compose | cpu | none, default compose): Compose's `dropShadow`, Obvid's (see [demoShadow]), or no shadow at all.
 * With `hud=true` a scenario also shows which one is active. With `autoplay` (a TierBenchmark scenario, e.g.
 * `t7_scroll`, next to its `scenario` extra) the app runs that benchmark's input script itself, see [Autoplay].
 */
class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    val scenarioExtra = intent.getStringExtra(ScenarioExtra)
    if (scenarioExtra != null) {
      // Benchmark runs start the scenario from adb with the phone locked: draw over the lock screen.
      setShowWhenLocked(true)
      setTurnScreenOn(true)
      // Over the secure keyguard the screen times out after 10 s without input.
      window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
    // Benchmarks pass `finishAfterMs` so an animation stops rebuilding shadows once its window is over:
    // Compose's shadow cache grows every rebuilt frame, and Macrobenchmark leaves the app running while
    // it processes the trace (minutes on a slow device), long enough to push a 4 GB phone into the LMK.
    val finishAfterMs = intent.getLongExtra(FinishAfterExtra, 0L)
    if (finishAfterMs > 0) window.decorView.postDelayed({ finish() }, finishAfterMs)
    val implExtra = intent.getStringExtra(ShadowImplExtra)
    shadowImpl = ShadowImpl.parse(implExtra)
    intent.getStringExtra(AutoplayExtra)?.let {
      val token = intent.getStringExtra(AutoplayTokenExtra).orEmpty()
      Autoplay.start(this, it, token, intent.getLongExtra(NavGapExtra, Autoplay.DefaultNavGapMs))
    }
    val hud = intent.getBooleanExtra(HudExtra, false) || intent.getStringExtra(HudExtra) == "true"
    if (scenarioExtra == null || scenarioExtra in DiscoverScenarios) {
      // The demo follows the system's light or dark mode; the benchmark scenes are always dark (DemoPalette.Dark).
      val bars = if (scenarioExtra == null) {
        SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
      } else {
        SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
      }
      enableEdgeToEdge(bars, bars)
      // The demo's margins follow the window width; the benchmark scenes keep the phone layout.
      val layout = if (scenarioExtra == null) DemoLayout.forWindowWidth(resources.configuration.screenWidthDp.dp) else DemoLayout.Phone
      DiscoverPhotos.load(this, layout.margin)
      if (scenarioExtra == null) {
        // The demo for people starts on Obvid; benchmarks always pass `shadowImpl`.
        val impl = if (implExtra == null) ShadowImpl.Cpu else shadowImpl
        setContent { DemoApp(initialImpl = impl, initialHud = hud) }
      } else {
        setContent { DiscoverScreen(shadowImpl, reveal = scenarioExtra == DiscoverRevealScenario) }
      }
      return
    }
    val scenario = scenarioExtra
    if (scenario == "grid") {
      // The grid is always light: dark bar icons even when the phone is in dark mode.
      val light = SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
      enableEdgeToEdge(light, light)
      val first = intent.getStringExtra(FirstItemExtra)?.toIntOrNull() ?: intent.getIntExtra(FirstItemExtra, 0)
      setContent { StressGrid(ShadowMode.parse(intent.getStringExtra(ShadowsExtra)), hud, first) }
      return
    }
    // A dark tier scene: light bar icons, and its background behind the bars too.
    val dark = tierBackground(scenario)
    if (dark != null) {
      val bars = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
      enableEdgeToEdge(bars, bars)
    }
    setContent {
      CompositionLocalProvider(LocalShadowImpl provides shadowImpl) {
        Box(Modifier.fillMaxSize().background(dark ?: Color(0xFFF2F2F2)).safeDrawingPadding(), contentAlignment = Alignment.Center) {
          Scenario(scenario)
          // Off in benchmarks (`hud` defaults to false), so the measured screen holds only the scenario.
          if (hud) ImplLabel(Modifier.align(Alignment.BottomCenter).padding(16.dp))
        }
      }
    }
  }
}

/** The active shadow implementation, in its own layer so the animated cards do not redraw it. */
@Composable
private fun ImplLabel(modifier: Modifier) {
  Text(
    ShadowImplementationLabel,
    modifier
      .graphicsLayer()
      .background(Color(0xCC1D1B20), RoundedCornerShape(12.dp))
      .padding(horizontal = 12.dp, vertical = 8.dp),
    style = TextStyle(color = Color.White, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium, fontSize = 12.sp),
  )
}
