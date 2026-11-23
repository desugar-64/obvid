---
name: build-and-measure
description: Build, test, benchmark and trace Obvid. Use when building the library or demo, running device tests, measuring performance (Microbenchmark, Macrobenchmark, Perfetto, simpleperf, power), reading traces, or comparing Obvid with Compose's own shadows.
---

# Build and measure Obvid

## Prerequisites
- JDK 17 or newer (Gradle downloads 17 if missing), Android SDK with the NDK version in `obvid/build.gradle.kts`
  (`ndkVersion`), `adb`.
- rustup. The compiler and the `aarch64-linux-android` target are pinned in `obvid/rust/rust-toolchain.toml` and
  install themselves on the first build; Gradle runs Cargo itself.
- For trace analysis: Python 3 and Perfetto's `trace_processor_shell` (default path
  `~/.local/share/perfetto/prebuilts/trace_processor_shell`, override with `TRACE_PROCESSOR`).

## Build and check
```sh
./gradlew :obvid:check :sample:assembleRelease   # library, lint, API dump check, release-AAR check
./gradlew spotlessApply                           # license headers (no formatter)
./gradlew :obvid:updateKotlinAbi                  # after an intended public API change
cd obvid/rust && cargo test --release             # host tests; the NEON ones need an arm64 host
```
- The library build disassembles the unstripped `.so` and fails if `neon::box3::steps` loses its NEON
  `smlal`/`smlal2` instructions (`:obvid:cargoBuild{Plain,Testing}Codegen`). A failure there is a ~2× slowdown that
  every test still passes; fix the code generation, never relax the check.
- APKs: demo `sample/build/outputs/apk/release/sample-release.apk`; Microbenchmarks
  `./gradlew :benchmark:assembleBenchmarkAndroidTest`; Macrobenchmarks `./gradlew :macrobenchmark:assembleBenchmark`;
  device tests `./gradlew :obvid:assembleDebugAndroidTest`.

## Devices
- With several devices connected, set `ANDROID_SERIAL` on every command. Don't plug or unplug USB during a run.
- A device must be unlocked and stay awake during a run (`adb shell svc power stayon true`); adb can't unlock a secure
  lock screen. Afterwards: `svc power stayon false`, `input keyevent KEYCODE_SLEEP`.
- Results go to `results/<ro.product.model>/<run>/` (gitignored).

## Device tests
`./gradlew :obvid:connectedDebugAndroidTest`, or with outputs kept:
```sh
OUT=/sdcard/Android/media/dev.serhiiyaremych.obvid.test/additional_test_output
adb shell am instrument -w -r -e additionalTestOutputDir $OUT \
  dev.serhiiyaremych.obvid.test/androidx.test.runner.AndroidJUnitRunner
adb pull $OUT results/<device>/<run>/
```
Identity with Compose is asserted on Android 16–17 (Compose there uses the Skia that Obvid ports); older versions
are measured, not asserted (`CrossVersionQualityTest`, band summaries). Each test writes a `*-summary.txt` next to its
CSV. `-e notClass dev.serhiiyaremych.obvid.FullExtentTest` skips the opt-in's test where it only measures.

## Measuring
Rules: release build, R8, AOT `speed` (the scripts compile it); a temperature gate before every case
(`scripts/lib.sh`: skin < 36 °C by default, `GATE_ZONE=battery` where the thermal HAL reports nothing); read the CPU
clock from each run's trace; standard tools only. Sampling: one run for effects > 15%, both orders for 3–15%, call
< 3% a tie. Every case compares Compose (`<case>`) with Obvid (`cpu_<case>`); `none_<case>` draws no shadows.

- **One rebuild** (Microbenchmark): `TRACES=<run> scripts/run_bench.sh micro|inner [case ...]`, then
  `python3 scripts/analyze_micro.py --compare results/<device>/<run>/micro`.
- **Frames in the demo's scenes** (Macrobenchmark `TierBenchmark`): install the demo and the macrobenchmark APK, then
  `TRACES=<run> scripts/run_bench.sh tiers <case> ...`, and
  `python3 scripts/analyze_macro.py results/<device>/<run>/tiers` (frame time, janky share, memory, shadow sections).
  Scenarios: `t0_single` (one card appears: first frame), `t8_glow_pulse` / `t8_glow_spin` (animated glow; spin
  changes only the brush), `t4_field` (stress: every visible tile rebuilds every frame), `t7_*` (a Discover feed:
  reveal, scroll, interact, nav), and others listed in `TierBenchmark`.
- **Where Macrobenchmark can't run** (its on-device trace processor hangs) or records no frame timeline for a few-frame
  case: `TRACES=<run> scripts/run_perfetto_tiers.sh <case> ...` (the demo plays the same input script itself), then
  `python3 scripts/analyze_tiers.py results/<device>/<run>/tiers`.
- **Vendor builds that confirm every APK install** with a dialog: `COMPILATION=ignore` makes Macrobenchmark skip its
  per-case reinstall (the script compiles `speed` itself instead).
- **CPU profiles:** `scripts/profile.sh counters|heat|macro ...` (simpleperf), reported with
  `scripts/report_shadow_profile.py`.
- **Power:** record Perfetto's `android.power` data source with `collect_power_rails: true` while a demo screen runs;
  average each rail as energy delta over time. Compare runs only at the same frame rate (watts from a run that drops
  frames are not comparable).

## Traces
- Library sections (only when a shadow is built, never for a cached draw): `Obvid dropShadow build`,
  `Obvid innerShadow build`, and inside them `Obvid outline`, `Obvid blur`, `Obvid nine-patch`, `Obvid Skia route`.
- Demo sections, the same for both libraries: `demo:dropShadow` / `demo:innerShadow` around each shadow draw,
  `demo:measure` (the measured window), `demo:reveal`, `demo:chip`, `demo:nav ...`.
- Compose's verbose sections, for diagnostic captures only (never a timing run):
  `adb shell settings put global obvid_verbose_tracing 1`, force-stop the demo, capture,
  `adb shell settings delete global obvid_verbose_tracing`.
- Other demo switches (read once per process): `obvid_demo_no_inner` (no inner shadows),
  `obvid_demo_full_extent` (`ShadowExtent.Full`).
- Screenshots of traces for docs: show only the demo's process lanes and the system lanes the point needs; same time
  span and scale for both libraries.

## Reading results
- A first frame after an idle app often runs the main thread on a little core with a fresh bitmap; it costs several
  times a warm rebuild. Report the two separately.
- Compose rebuilds on colour and brush changes and keeps every rebuilt bitmap until the view detaches or the app goes
  to the background; check memory (RssAnon) at the end of animated runs.
