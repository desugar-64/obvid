// Copyright 2026, Serhii Yaremych (desugar-64) and the Obvid project contributors
// SPDX-License-Identifier: Apache-2.0

plugins {
  alias(libs.plugins.android.test)
}

android {
  namespace = "dev.serhiiyaremych.obvid.macrobenchmark"
  compileSdk { version = release(37) { minorApiLevel = 1 } }
  defaultConfig {
    minSdk = 31
    targetSdk = 37
    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    ndk { abiFilters += "arm64-v8a" }
  }
  // Measures the sample's minified release build (signed with the debug key); the test APK itself is debuggable.
  buildTypes {
    create("benchmark") {
      isDebuggable = true
      signingConfig = signingConfigs.getByName("debug")
      matchingFallbacks += listOf("release")
    }
  }
  targetProjectPath = ":sample"
  experimentalProperties["android.experimental.self-instrumenting"] = true
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
}

kotlin {
  jvmToolchain(17)
}

androidComponents {
  beforeVariants(selector().all()) { it.enable = it.buildType == "benchmark" }
}

dependencies {
  implementation(libs.androidx.benchmark.macro.junit4)
  implementation(libs.androidx.test.uiautomator)
  implementation(libs.androidx.test.ext.junit)
  implementation(libs.androidx.test.runner)
  implementation(libs.junit)
}
