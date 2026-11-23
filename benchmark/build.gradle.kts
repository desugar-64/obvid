// Copyright 2026, Serhii Yaremych (desugar-64) and the Obvid project contributors
// SPDX-License-Identifier: Apache-2.0

plugins {
  alias(libs.plugins.android.library)
  alias(libs.plugins.androidx.benchmark)
}

android {
  namespace = "dev.serhiiyaremych.obvid.benchmark"
  compileSdk { version = release(37) { minorApiLevel = 1 } }
  defaultConfig {
    minSdk = 31
    testInstrumentationRunner = "androidx.benchmark.junit4.AndroidBenchmarkRunner"
    ndk { abiFilters += "arm64-v8a" }
  }
  // Benchmarks run against a non-debuggable release-like build, shrunk and optimized by R8 like an app. Its
  // "benchmark" build type picks :obvid's, which adds the test and benchmark surface (`ObvidTesting`,
  // `DropShadowDrawHook`) to the release build.
  testBuildType = "benchmark"
  buildTypes {
    create("benchmark") {
      initWith(getByName("release"))
      matchingFallbacks += "release"
      androidTest { enableMinification = true }
      testProguardFiles("benchmark-proguard-rules.pro")
    }
  }
  // Shapes and mask helpers shared with :obvid's device tests and :sample.
  sourceSets.getByName("androidTest").kotlin.directories.add("../fixtures/src/main/kotlin")
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
}

kotlin {
  jvmToolchain(17)
}

dependencies {
  androidTestImplementation(project(":obvid"))
  androidTestImplementation(platform(libs.androidx.compose.bom))
  androidTestImplementation(libs.androidx.compose.ui.graphics)
  androidTestImplementation(libs.androidx.compose.foundation)
  androidTestImplementation(libs.androidx.compose.material3)
  androidTestImplementation(libs.androidx.graphics.shapes)
  androidTestImplementation(libs.androidx.benchmark.junit4)
  androidTestImplementation(libs.androidx.test.runner)
  androidTestImplementation(libs.androidx.test.ext.junit)
  androidTestImplementation(libs.junit)
}
