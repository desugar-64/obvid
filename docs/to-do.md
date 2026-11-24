# To do

Ideas that were measured or scoped, but are not in 0.1.1.

## Parallel builds when a screen opens

When a screen with many shadows appears, the main thread builds all the shadows in that one frame, one after the other.
On a Xiaomi Pad 5, the first frame of the Discover feed builds 36 shadows in 21 ms (Compose: 89 ms; medians of 5). These
builds are independent. If the big cores share them, this part of the frame can become several times faster. To divide
one blur between cores is not worth it: approximately 1.25 times faster in an earlier experiment, before the cost to
wake the cores. Obvid will keep this change only if the first-frame time, janky frames, and power all improve.

## Faster first builds on little cores

After an idle screen, the main thread often runs the first frame on a little core. On a Xiaomi Pad 5 (Cortex-A55,
1.8 GHz), the first build of one 200 dp card takes 3.5 ms there. A warm rebuild on a big core takes 0.24 ms, and Compose
takes 14.3 ms in the same frame. Most of this time is the nine-patch stretch into a new bitmap. The next steps: profile
it (arithmetic or first-touch memory), use warm bitmaps again from a small pool, and write less of the flat interior
that the card covers.

## Fewer draw commands for shadows that scroll

On a tablet with a weak GPU, band-only inner shadows decrease the GPU time for each frame by 29%. Then the RenderThread
becomes the limit, because it sends the draw commands (approximately 60 shadow draws for each frame in the demo feed).
The next step is to merge or cache these draws, for a steady 60 fps on that tablet.

## Drop shadows under opaque content

Usually, the element on top hides the middle of a drop shadow, but the GPU still blends it. If Obvid skips the middle,
the output changes when the content is translucent. Thus, this option will be opt-in.

## More shapes on the fast path

Plain rects, radius 0 and drop-shadow frames with spread still use the Skia of the device. Right now, they are 1.06–1.26
times faster than Compose. Inner shadows of paths with spread use the Obvid blur, but Android still draws the shrunk
path at a fractional offset with filtering, and this takes most of the time: 1.5–1.6 times faster than Compose, against
7–10 times without spread. The next step is to keep that drawn mask while only the radius changes.

## A downsampled blur for large radii

This option will be opt-in and approximate. Its quality loss (PSNR, max error) will be published with it.

## A GPU route

An analytic round-rect shadow in AGSL for the simplest shapes, to compare with the CPU route.

## More ABIs

Currently, 32-bit ARM (NEON) and x86_64 (emulators) use the Compose shadows.

## Maven Central

After the GitHub Pages repository shows that it is reliable.
