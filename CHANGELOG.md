# Changelog

## 0.1.1

- Shadows with blur radii below approximately 2.6 px (for example 0.5–0.9 dp on a Pixel 6) use the Obvid blur on all
  shapes, with a NEON version of the small blur of Skia ([#2](https://github.com/desugar-64/obvid/issues/2)). One
  rebuild on a Pixel 6 is 3.0–4.1 times faster than Compose for paths and circles, and 5.2–8.5 times for round rects.
  Before, these shadows used the Skia of the device: about 1.1 times faster than Compose, and round rects at 0.9 dp
  were slower. The pixels stay identical to Compose on Android 16–17.
- Fixed: three kinds of rare paths differed from Compose on every Android version
  ([#4](https://github.com/desugar-64/obvid/issues/4)): paths with an inverse fill type (drop and inner shadows), drop
  shadows with a spread of 0.5 px or less, and drop shadows whose spread stroke is a rect (for example a straight line
  with a spread). They now use the Skia of the device, as in Compose. With `ShadowExtent.Full`, an inverse fill keeps
  the bitmap of Compose.

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
