# Changelog

## 0.1.0

The first release. Obvid replaces the `dropShadow` and `innerShadow` modifiers of Jetpack Compose. Change the two
imports; the calls, `Shadow`, and the two overloads stay the same.

```kotlin
// settings.gradle.kts
dependencyResolutionManagement { repositories { maven("https://desugar-64.github.io/obvid/maven") } }

// build.gradle.kts
dependencies { implementation("dev.serhiiyaremych:obvid:0.1.0") }
```

- On Android 16–17, the pixels are identical to Compose's: PSNR ∞, maximum error 0 of 255 levels.
- One shadow rebuild is 7–13 times faster. The blur is a NEON port of the Skia mask blur.
- A change of color, brush, alpha, or blend mode only changes the draw call. Obvid keeps one bitmap for each shadow.
- Inner shadows draw only the band along the edge that has shadow, with less GPU work.
- arm64-v8a only (other ABIs use the Compose shadows), minSdk 24, Compose 1.13 or newer. Refer to
  [Limits](https://github.com/desugar-64/obvid/blob/main/docs/limits.md).

The GitHub Release also has the demo app and the native debug symbols of the library, to symbolize native crashes.
