# To do

Ideas that were measured or scoped, but are not in Obvid yet.

## Parallel rebuilds

[Parallel builds](how-it-works.md#parallel-builds) send only the bitmap of each new shadow to a worker. A shadow that
changes size still rebuilds in the draw. On a Xiaomi Pad 5, the demo's field of changing shapes rebuilds approximately
20 shadows in each frame: 15.5–16.2 ms of frames of approximately 27 ms. If a node also queues a rebuild when the layout
measures a new size, workers can do this part too.

Two more ideas to measure with it:

- **Newest job first:** the draw goes in the order of the layout and builds a job itself when no worker took it. If
  the workers take the newest job first, the draw and the workers start at the two ends of the queue. Then the draw
  waits for a worker less often.
- **Queue at placement, not at measurement:** a lazy list can measure items for look-ahead and not place them. Each
  such item starts a build that no draw uses. Placement comes later than measurement, so the workers get less time
  before the draw.

## Faster first builds

After an idle screen, the main thread often runs the first frame on a little core. On a Xiaomi Pad 5 (Cortex-A55,
1.8 GHz), the first build of one 200 dp card takes 3.5 ms there. A warm rebuild takes 0.24 ms, and Compose takes
14.3 ms in the same frame. The largest parts are the blur of the nine-patch's small round rect (1.55 ms) and the first
writes into new memory (approximately 200 page faults). The nine-patch write is only 0.33 ms
([parts](how-it-works.md#frame-timing)). To fill new buffers without zeros first gave no gain
([#12](https://github.com/desugar-64/obvid/issues/12)). The next step is to keep the blur's buffers after the builder
closes. This prevents the page faults, but keeps approximately 0.5 to 1 MB while the app is idle.

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
