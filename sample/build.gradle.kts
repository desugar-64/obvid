// Copyright 2026, Serhii Yaremych (desugar-64) and the Obvid project contributors
// SPDX-License-Identifier: Apache-2.0

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.compose.compiler)
}

// Release signing from Gradle properties or environment variables (the release workflow sets them from its secrets):
// SIGNING_KEYSTORE_FILE, SIGNING_KEYSTORE_PASSWORD, SIGNING_KEY_ALIAS, SIGNING_KEY_PASSWORD. Without all four, the
// release build is signed with the debug key so that it installs on test devices.
fun signingValue(name: String): String? =
  providers.gradleProperty(name).orElse(providers.environmentVariable(name)).orNull?.takeIf { it.isNotBlank() }
val releaseSigning =
  listOf("SIGNING_KEYSTORE_FILE", "SIGNING_KEYSTORE_PASSWORD", "SIGNING_KEY_ALIAS", "SIGNING_KEY_PASSWORD")
    .map(::signingValue)
    .takeIf { values -> values.all { it != null } }
    ?.map { it!! }

// The version of Obvid (gradle.properties), so that each release's demo installs over the previous one.
val obvidVersion = providers.gradleProperty("obvid.version").get()

android {
  namespace = "dev.serhiiyaremych.obvid.sample"
  compileSdk { version = release(37) { minorApiLevel = 1 } }
  defaultConfig {
    applicationId = "dev.serhiiyaremych.obvid.sample"
    minSdk = 31
    targetSdk = 37
    // major·10000 + minor·100 + patch: scripts/version.sh keeps minor and patch below 100.
    versionCode =
      obvidVersion.split('.').map(String::toInt).let { (major, minor, patch) -> major * 10_000 + minor * 100 + patch }
    versionName = obvidVersion
    ndk { abiFilters += "arm64-v8a" }
  }
  signingConfigs {
    if (releaseSigning != null) {
      create("release") {
        val (path, storePass, alias, keyPass) = releaseSigning
        storeFile = file(path)
        storePassword = storePass
        keyAlias = alias
        keyPassword = keyPass
      }
    }
  }
  buildTypes {
    release {
      isMinifyEnabled = true
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
      signingConfig = signingConfigs.getByName(if (releaseSigning != null) "release" else "debug")
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
  buildFeatures {
    compose = true
  }
  // MorphShape, shared with the device tests and benchmarks (fixtures/: R8 drops what the app does not use).
  sourceSets.getByName("main").kotlin.directories.add("../fixtures/src/main/kotlin")
}

kotlin {
  jvmToolchain(17)
}

// `-PcomposeReports` writes the Compose compiler's stability and skippability reports to build/compose_reports.
composeCompiler {
  if (providers.gradleProperty("composeReports").isPresent) {
    reportsDestination = layout.buildDirectory.dir("compose_reports")
    metricsDestination = layout.buildDirectory.dir("compose_reports")
  }
}

dependencies {
  implementation(project(":obvid"))
  implementation(platform(libs.androidx.compose.bom))
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.activity.compose)
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.foundation)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.ui.tooling.preview)
  debugImplementation(libs.androidx.compose.ui.tooling)
  implementation(libs.androidx.graphics.shapes)
  implementation(libs.androidx.tracing.ktx)
  // Lets Macrobenchmark reset compilation state and drop the shader cache in the release build.
  implementation(libs.androidx.profileinstaller)
}
