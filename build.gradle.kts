// Copyright 2026, Serhii Yaremych (desugar-64) and the Obvid project contributors
// SPDX-License-Identifier: Apache-2.0

plugins {
  alias(libs.plugins.android.application) apply false
  alias(libs.plugins.android.library) apply false
  alias(libs.plugins.android.test) apply false
  alias(libs.plugins.compose.compiler) apply false
  alias(libs.plugins.androidx.benchmark) apply false
  alias(libs.plugins.spotless)
}

// License headers only (no formatter): `./gradlew spotlessApply` adds them, `spotlessCheck` fails without them.
// Kotlin, Gradle Kotlin scripts and Rust get the Apache-2.0 header (spotless/copyright.txt). The Rust and Kotlin files
// that port Skia code keep Skia's BSD-3-Clause with the original copyright lines (spotless/copyright-skia-port.txt,
// files in `skiaPorts`); Obvid's NEON blur gets the Apache header plus a pointer to NOTICE
// (spotless/copyright-skia-arithmetic.txt). Copies of Google's Compose snippets keep their own Apache header.
val generated = listOf("**/build/**", "**/target/**", ".gradle/**", ".kotlin/**", ".claude/**", "results/**")
val composeSnippets = "obvid/src/androidTest/kotlin/dev/serhiiyaremych/obvid/snippets/**"
val skiaPorts = mapOf(
  "obvid/rust/src/skia/plan_gauss.rs" to listOf("Copyright 2017 Google LLC"),
  "obvid/rust/src/skia/small_blur.rs" to listOf("Copyright 2017 Google LLC"),
  "obvid/rust/src/skia/sigma.rs" to listOf("Copyright 2006 The Android Open Source Project", "Copyright 2017 Google LLC"),
  "obvid/rust/src/skia/mask.rs" to listOf(
    "Copyright 2006 The Android Open Source Project", "Copyright 2007 The Android Open Source Project",
    "Copyright 2017 Google LLC",
  ),
  "obvid/rust/src/shadow/nine.rs" to listOf("Copyright 2006 The Android Open Source Project", "Copyright 2012 Google Inc."),
  "obvid/rust/src/shadow/rects.rs" to listOf("Copyright 2006 The Android Open Source Project"),
  "obvid/rust/src/shadow/rects_tests.rs" to listOf("Copyright 2006 The Android Open Source Project", "Copyright 2025 Google LLC"),
  "obvid/src/main/kotlin/dev/serhiiyaremych/obvid/builder/SkiaRects.kt" to listOf("Copyright 2025 Google LLC"),
)
val kotlinSkiaPorts = skiaPorts.keys.filter { it.endsWith(".kt") }
val skiaArithmetic = listOf("obvid/rust/src/blur.rs", "obvid/rust/src/blur/passes.rs", "obvid/rust/src/neon/box3.rs")
// Rust has no `package` line to anchor on: the header is every leading `// ` line and blank line, so a file must not
// start with a plain `//` comment of its own (module docs are `//!`).
val rustDelimiter = "(?!// |\\n)"

spotless {
  kotlin {
    target("**/*.kt")
    targetExclude(generated + composeSnippets + kotlinSkiaPorts)
    licenseHeaderFile(rootProject.file("spotless/copyright.txt"))
  }
  kotlinGradle {
    target("**/*.kts")
    targetExclude(generated)
    licenseHeaderFile(rootProject.file("spotless/copyright.txt"), "(import|plugins|pluginManagement)")
  }
  format("rust") {
    target("**/*.rs")
    targetExclude(generated + skiaPorts.keys + skiaArithmetic)
    licenseHeaderFile(rootProject.file("spotless/copyright.txt"), rustDelimiter)
  }
  format("rustSkiaArithmetic") {
    target(skiaArithmetic)
    licenseHeaderFile(rootProject.file("spotless/copyright-skia-arithmetic.txt"), rustDelimiter)
  }
  val skiaTemplate = rootProject.file("spotless/copyright-skia-port.txt").readText()
  // One format per copyright set and language: Kotlin's header ends at `package`, Rust's at the first other line.
  skiaPorts.entries.groupBy({ it.value to it.key.endsWith(".kt") }, { it.key }).entries.forEachIndexed { i, (key, files) ->
    val (copyrights, kotlin) = key
    format("skiaPort$i") {
      target(files)
      licenseHeader(
        skiaTemplate
          .replace("\$SKIA_COPYRIGHT", copyrights.joinToString("\n") { "// $it" })
          .replace("\$PORT_LANGUAGE", if (kotlin) "Kotlin" else "Rust"),
        if (kotlin) "package " else rustDelimiter,
      )
    }
  }
}
