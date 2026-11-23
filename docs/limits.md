# Limits

## arm64-v8a only

On other ABIs, or if the library does not load, the modifiers use the Compose `dropShadow` and `innerShadow` (with one
log warning).

## Some cases use the Skia of the device

As in Compose: rects and rect-shaped paths, radius 0, and inner shadows of paths with a positive spread at some offsets.
These cases agree with the Compose output of that device and are only slightly faster.

## Compose's hard edge stays by default

The Compose shadow bitmap stops at the radius from the shape. At that line, a large blur behind a solid shape still has
approximately 8–12 of 255 levels. Thus, a strong colored glow on a dark background can stop at a straight line. By
default, Obvid keeps this edge, because it is the Compose output. With `LocalShadowExtent provides ShadowExtent.Full`,
the drop shadows below continue until the blur is exactly zero. This is approximately 1.4–1.6 times the radius from the
shape. The edge goes away, but the bitmap is usually 15–36% larger. Outside the Compose window, the output is not
identical to Compose. This option does not affect inner shadows.

## Compose 1.13 or newer, minSdk 24

Obvid is built and tested with Compose UI 1.13.0 (BOM 2026.11.01). This version needs compileSdk 37.1 and minSdk 24.

## Threads

As in Compose, the thread that draws a shadow also builds it during the draw pass. Each UI thread has its own cache.

## Memory

When shadows go off-screen, they stay in the cache for 3 s (up to 10 MiB). Thus, they do not need a rebuild when you
scroll back. Obvid removes them when the app goes to the background or when memory is low.

## Size

In a minimal release app (R8), Obvid adds approximately 68 KB to the download and 131 KB to the arm64 APK.
`libobvid.so` is 100 KB (`no_std` Rust). It is stored without compression because Android loads it in place. The dex
adds 21 KB. From an App Bundle, devices with other ABIs get only the dex.
