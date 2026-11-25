"""Where the app's main thread spends its shadow draws, from a `scripts/profile.sh macro` run: simpleperf samples taken
inside the shadow sections of the same run's Perfetto traces.

usage: python3 scripts/report_shadow_profile.py <raw-dir> <out-dir> [case ...]
  e.g. python3 scripts/report_shadow_profile.py results/<device>/traces-reentry-profile results/<device>/reentry-profile

<raw-dir> holds <suite>/<case>/*.perfetto-trace (Macrobenchmark) or *.pftrace (scripts/run_perfetto_tiers.sh, through
`RUNNER=perfetto scripts/profile.sh macro`) and simpleperf/<case>/*.data (one record per app process); no case = every
case under simpleperf/. The suite is SUITE (reentry, tiers, ...) or, when unset, the one directory of <raw-dir> other
than simpleperf/ that holds the case. A trace with a demo:measure section (an autoplay run, see analyze_tiers.py) counts
inside that window only: sections, event windows and frames by start; one whose demo:measure never ended is skipped.
Core clusters: the device's of the raw-dir path (perfetto_util.cluster_for).

1. Sections: the main thread's `demo:dropShadow` and `demo:innerShadow` sections (every shadow draw); rebuilds are those
   longer than THRESHOLD_US (default 1000 µs; hits last ~0.1 ms, rebuilds over 1.5 ms in the re-entry list: check the
   histogram analyze_reentry.py prints; a small shadow can rebuild in less, so "per build" undercounts builds where
   that happens), their duration, their Running time and its time-weighted clock and cores from the trace
   (perfetto_util), and their bounds on CLOCK_MONOTONIC (`to_monotonic`), the clock of simpleperf's sample times.
2. Samples: every main-thread (tid = pid) sample whose time lies inside a section, from the record that covers
   the trace's time. cpu-clock samples are 250 µs of on-CPU time each, user and kernel alike: on this device the
   kernel part of a sample (a syscall, a page fault) is reported at the user instruction that entered the kernel, so
   kernel time counts in the user frame that caused it and cannot be split from it. page-faults samples are one fault
   each.
3. Categories (rebuild sections only): each sample's frames from the section marker's draw (ROOT_FRAMES: the sample's
   `TraceDrawNode.draw` or `SectionStartNode.draw`) down to the leaf, by the first rule of `categorize` that matches
   (see there); a sample without that frame is `unattributed` (a call chain that DWARF unwinding cut short, or
   main-thread work in the section outside the draw).
4. Stages (all sections, and rebuild sections apart): the same frames by `stage_of` into STAGES, one set of names for
   Compose and Obvid's (see there); unattributed samples count as `other` and are also reported on their own. Frames:
   the
   ones FrameTimingMetric counts (perfetto_util.frame_timings) whose doFrame starts inside a record's time span.
5. Event windows (perfetto_util.EVENT_WINDOWS: `demo:reveal`, `demo:nav <direction>`, `demo:chip`, from the tap until the
   screen has settled): the stages of step 4 again over the sections that start inside a window of each name, per
   window. Only windows inside a record's time span count.

Java names are de-obfuscated with the sample's R8 mapping (MAPPING, default the release build's); ART frames are
shown (trampolines: JNI transitions). libobvid.so is stripped except its JNI exports: frames inside it are named
from an unstripped build of the same code (RUST_LIB: the build's own, obvid/build/outputs/native-debug-symbols/arm64-v8a/
libobvid.so), used only if its .text is identical to the APK's (APK, default the release APK).

Output per case: <case>-breakdown.md (categories: samples, share of the in-build samples, ms per build = samples ×
0.25 ms / rebuilds; page faults per build, when the run recorded them (profile.sh FAULTS=1); leaf functions per category; sections: duration vs Running vs sampled
time, clock and cores) and <case>-folded.txt (folded stacks from the section marker's draw to the leaf, for a flame
graph), both over rebuild sections; <case>-stages.json and <case>-stages.md over every section (format in
`stage_report`); stdout: one line per case and the top unattributed / `other` leaf symbols, for a reviewer to check
the stage rules.
"""
import bisect, collections, glob, json, os, re, subprocess, sys, tempfile, zipfile

from perfetto_util import EVENT_WINDOWS, SHADOW_SECTIONS_SQL, cluster_for, frame_timings, measure_window, query

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
NDK = os.environ.get('NDK', os.path.join(os.environ.get('ANDROID_HOME', ''), 'ndk', '30.0.16248370'))
sys.path.insert(0, os.path.join(NDK, 'simpleperf'))
from simpleperf_report_lib import ReportLib  # noqa: E402

PKG = 'dev.serhiiyaremych.obvid.sample'
SUITE = os.environ.get('SUITE')
THRESHOLD_NS = float(os.environ.get('THRESHOLD_US', 1000)) * 1e3
MAPPING = os.environ.get('MAPPING', os.path.join(ROOT, 'sample/build/outputs/mapping/release/mapping.txt'))
APK = os.environ.get('APK', os.path.join(ROOT, 'sample/build/outputs/apk/release/sample-release.apk'))
RUST_LIB = os.environ.get('RUST_LIB')
LLVM = os.path.join(NDK, 'toolchains/llvm/prebuilt/darwin-x86_64/bin')
PERIOD_MS = 0.25
ROOT_FRAMES = ('dev.serhiiyaremych.obvid.sample.TraceDrawNode.draw',
               'dev.serhiiyaremych.obvid.sample.SectionStartNode.draw')
EXPORT = 'Java_dev_serhiiyaremych_obvid_ObvidNative_'
OUR_LIB = 'libobvid.so'

SECTIONS = """
with p as (select upid, pid from process where name = '{pkg}'),
main as (select utid from thread join p using(upid) where tid = p.pid)
select s.ts, s.dur, to_monotonic(s.ts) from slice s join thread_track tt on s.track_id = tt.id
where tt.utid in (select utid from main) and s.name in {names} and s.dur > {min_ns} order by s.ts
"""

# Main thread Running time per section and core, with its frequency-weighted sum (ns·kHz).
SECTION_CLOCK = """
with p as (select upid, pid from process where name = '{pkg}'),
main as (select utid from thread join p using(upid) where tid = p.pid),
sec as (select s.ts, s.ts + s.dur te from slice s join thread_track tt on s.track_id = tt.id
        where tt.utid in (select utid from main) and s.name in {names} and s.dur > {min_ns}),
run as (select sec.ts sts, max(st.ts, sec.ts) a, min(st.ts + st.dur, sec.te) b, st.cpu from sec
        join thread_state st on st.ts < sec.te and st.ts + st.dur > sec.ts
        where st.utid in (select utid from main) and st.state = 'Running'),
f as (select cct.cpu, c.ts, lead(c.ts, 1, (select end_ts from trace_bounds)) over (partition by cct.cpu order by c.ts) te,
      c.value v from counter c join cpu_counter_track cct on c.track_id = cct.id where cct.type = 'cpu_frequency')
select run.sts, run.cpu, sum(min(run.b, f.te) - max(run.a, f.ts)) ns, sum((min(run.b, f.te) - max(run.a, f.ts)) * f.v) fns
from run join f on f.cpu = run.cpu and f.ts < run.b and f.te > run.a group by 1, 2
"""


def has(frames, *needles):
    return any(n in f for f in frames for n in needles)


def first(frames, prefix):
    return next((f for f in frames if f.startswith(prefix)), None)


def leaf_name(frames):
    return frames[0] if frames else '?'


def categorize(frames):
    """(category, detail) of a sample from its frames, leaf first, up to (excluding) the root frame.

    Rules, first match wins:
    - an Obvid JNI export on the chain: `jni <export>`, detailed as AndroidBitmap_lockPixels / unlockPixels (anywhere
      below the export), the Rust function at the leaf, or the leaf (a libc call from Rust, an ART call back);
    - `Bitmap.createBitmap`: `bitmap alloc`, detailed by the native allocator frames (`mmap`, `munmap`, scudo, other
      native), GC / native-allocation accounting, or Java/ART;
    - a Java ObvidNative method without an export below it: `jni transition` (ART trampolines and stubs);
    - `android.graphics.Canvas` / `BaseCanvas` drawing (Skia under hwui): `canvas <method>`, detailed by the Skia part
      on the chain (SKIA_PARTS) and the leaf;
    - Canvas, Paint, BlurMaskFilter object setup (constructors, setBitmap): `canvas/paint setup`;
    - ART GC frames: `gc`;
    - anything else: `other`, detailed by the deepest app (base.odex) frame.
    """
    export = first(frames, EXPORT)
    if export:
        name = 'jni ' + export[len(EXPORT):].split('(')[0]
        if has(frames, 'AndroidBitmap_lockPixels'):
            return name, 'AndroidBitmap_lockPixels'
        if has(frames, 'AndroidBitmap_unlockPixels'):
            return name, 'AndroidBitmap_unlockPixels'
        return name, leaf_name(frames)
    if has(frames, 'android.graphics.Bitmap.createBitmap', 'Bitmap_creator'):
        if has(frames, 'art::gc::', 'registerNativeAllocation', 'VMRuntime'):
            return 'bitmap alloc', 'GC / native-allocation accounting'
        if has(frames, 'allocateHeapBitmap'):
            for k in ('munmap', 'mmap', 'scudo', 'memset'):
                if has(frames[:3], k):
                    return 'bitmap alloc', f'native: {k}'
            return 'bitmap alloc', 'native: ' + leaf_name(frames)
        return 'bitmap alloc', 'Java/ART: ' + leaf_name(frames)
    if has(frames, 'dev.serhiiyaremych.obvid.ObvidNative.'):
        return 'jni transition', leaf_name(frames)
    draw = next((f for f in reversed(frames) if re.match(r'android\.graphics\.(Base)?Canvas\.(draw|native)', f)), None)
    if draw:
        method = draw.split('.')[-1].split('(')[0]
        part = next((name for name, keys in SKIA_PARTS if has(frames, *keys)), 'other')
        return f'canvas {method}', f'{part} / {short(leaf_name(frames))}'
    if has(frames, 'android.graphics.Canvas', 'android.graphics.BaseCanvas', 'android.graphics.Paint',
           'android.graphics.BlurMaskFilter', 'android.graphics.MaskFilter'):
        return 'canvas/paint setup', leaf_name(frames)
    if has(frames, 'art::gc::'):
        return 'gc', leaf_name(frames)
    app = next((f for f in frames if f.startswith(('dev.serhiiyaremych', 'androidx.'))), leaf_name(frames))
    return 'other', app


# Skia parts under a Canvas draw, the first whose frame is on the sample's chain.
SKIA_PARTS = [
    ('skia blur', ('SkBlurMask', 'SkMaskBlurFilter', 'BlurMaskFilterImpl::filterMask', 'blur_')),
    ('skia blurred-mask cache lookup', ('SkMaskCache', 'SkResourceCache')),
    ('skia mask rasterization', ('draw_rrect_into_mask', 'SkScan', 'SkAAClip', 'SuperBlitter')),
    ('skia nine-patch blit (draw_nine)', ('draw_nine',)),
    ('skia blit', ('Blitter',)),
]


def short(name):
    """A symbol without its parameter list."""
    return name.split('(')[0]


# Stages of a shadow draw, one set of names for Compose and Obvid's, in chart order.
STAGES = ('alloc', 'mask', 'blur', 'write', 'resample', 'glue', 'record', 'other')

# Frame-name keys of the stage rules (substrings; see stage_of for their order).
ALLOC_KEYS = ('android.graphics.Bitmap.createBitmap', 'Bitmap_creator', 'allocateHeapBitmap',
              'androidx.compose.ui.graphics.ImageBitmapKt.ImageBitmap', 'A8BitmapsKt.newA8')
RECORD_KEYS = ('android.graphics.BaseRecordingCanvas.', 'android.graphics.RecordingCanvas.', 'SkiaRecordingCanvas',
               'DisplayListData', 'RecordingCanvas::')
DRAW_SCOPE_DRAW = re.compile(r'(CanvasDrawScope|LayoutNodeDrawScope)\.draw(?!Content|Direct)')
SECTION_END = 'dev.serhiiyaremych.obvid.sample.SectionEndNode'
SKIA_RESAMPLE = ('gather', 'bilinear', 'bilerp', 'bicubic', 'SkImageShader', 'mipmap', 'clamp_x', 'repeat_x',
                 'mirror_x')
SKIA_BLUR = ('SkBlurMask::', 'SkMaskBlurFilter', 'BlurMaskFilterImpl::filterMask', 'SkBlurEngine', 'blur_',
             'BoxBlur', 'GaussianBlur')
SKIA_CACHE = ('SkMaskCache', 'SkResourceCache')
SKIA_WRITE_PATCH = ('draw_nine', 'blitClippedMask', 'SkSpriteBlitter', 'Sprite_', 'drawSprite')
SKIA_MASK = ('draw_rrect_into_mask', 'SkScan', 'SkAAClip', 'SuperBlitter', 'aaa_fill_path', 'AdditiveBlitter',
             'DrawToMask', 'SkEdgeBuilder', 'SkAnalyticEdge', 'SkEdge::', 'SkStroke')
SKIA_BLUR_LATE = ('filterRRectToNine', 'filterRectsToNine', 'filterRRectMask', 'filterRectMask')
SKIA_MASK_FILTER = ('SkMaskFilter', 'filterPath', 'filterMask', 'NinePatch', 'filterRRect', 'filterRects')
CANVAS_DRAW = re.compile(r'android\.graphics\.(Base)?Canvas\.(draw|native)')
SETUP_KEYS = ('android.graphics.Canvas', 'android.graphics.BaseCanvas', 'android.graphics.Paint',
              'android.graphics.BlurMaskFilter', 'android.graphics.MaskFilter', 'android.graphics.Shader',
              'android.graphics.BitmapShader', 'ColorFilter', 'androidx.compose.ui.graphics.AndroidPaint',
              'androidx.compose.ui.graphics.AndroidCanvas', 'androidx.compose.ui.graphics.ShaderBrush',
              'androidx.compose.ui.graphics.AndroidShader')
OURS = 'dev.serhiiyaremych.obvid.'
SHADOW_CODE = (OURS, 'androidx.compose.ui.graphics.shadow.', 'androidx.compose.ui.draw.', 'ShadowContext',
               'androidx.collection.')
RUST_MASK = re.compile(r'::mask::')
RUST_BLUR = re.compile(r'blur|box3|plan_gauss|transpose|::passes::|sigma')
RUST_WRITE = re.compile(r'nine|write|place|rects|invert|copy_row|put_|store_rows|clear_outside|src_over|stretch|blit')


def stage_of(frames):
    """Stage of a sample from its frames, leaf first, up to (excluding) the section marker's draw. First match wins:

    1. `alloc`: a bitmap allocation on the chain (Bitmap.createBitmap and its native part, mmap included; Compose's
       ImageBitmap(); Obvid's newA8), with its GC / native-allocation accounting.
    2. `record`: the recording canvas on the chain (BaseRecordingCanvas, RecordingCanvas, SkiaRecordingCanvas,
       DisplayListData) or a DrawScope draw call (CanvasDrawScope / LayoutNodeDrawScope draw*, not drawContent): the
       final draw of the shadow into the display list, with its paint setup. Both builders draw on a Canvas, never
       through a DrawScope.
    3. Obvid's JNI export on the chain: AndroidBitmap_lockPixels / unlockPixels -> `glue`; else the first named Rust
       frame
       from the leaf up: `::jni::` -> `glue`; `::mask::` -> `mask`; blur, box3, plan_gauss, transpose, passes, sigma
       -> `blur`; nine, write, place, rects, invert, copy_row, put_, store_rows, clear_outside, src_over, stretch,
       blit -> `write`. A libc leaf (memset, memmove) takes the stage of the Rust frame that called it. Nothing
       named (RUST_LIB missing or mismatched) -> `other`.
    4. A ObvidNative Java method without an export below it: `glue` (JNI transition).
    5. A Canvas draw on a software canvas (android.graphics.Canvas / BaseCanvas draw* or native*) or
       Bitmap.eraseColor: a clear (drawColor, drawPaint, eraseColor) -> `mask`; else by the Skia frames on the chain:
       gather / bilinear / bilerp / bicubic / SkImageShader / mipmap / clamp_x / repeat_x / mirror_x -> `resample`;
       SkBlurMask::, SkMaskBlurFilter, BlurMaskFilterImpl::filterMask, SkBlurEngine, blur_, BoxBlur, GaussianBlur ->
       `blur`; SkMaskCache, SkResourceCache (Skia's blurred-mask cache lookup) -> `glue`; draw_nine, blitClippedMask,
       sprite blits -> `write`; mask rasterization (draw_rrect_into_mask, SkScan, SkAAClip, supersampling and
       analytic AA blitters, DrawToMask, edge building, SkStroke) -> `mask`; filterRRectToNine and the other
       nine-patch builders' own code -> `blur`. What is left is blitting and the draw call's own overhead: a
       drawBitmap / drawImage -> `write`; the builder's other draws without a mask filter on the chain -> `mask`
       (they rasterize masks into Obvid's buffers); anything else -> `write` (Compose's one draw call rasterizes, blurs
       and blits: what is left is the blit and its setup).
    6. Canvas, Paint, MaskFilter, Shader, ColorFilter setup (constructors, setBitmap, setters; Compose's AndroidPaint,
       AndroidCanvas wrappers, ShaderBrush): `glue`.
    7. The section's end marker (SectionEndNode: the trace write that closes the section) or a GC frame: `other`.
    8. Anything else whose deepest app frame is shadow code (Obvid's, androidx.compose.ui.graphics.shadow, the
       androidx.compose.ui.draw shadow nodes, the shadow contexts, androidx.collection maps: store lookups, cache
       keys, builder creation, parameter conversion): `glue`; the rest: `other`.
    """
    if has(frames, *ALLOC_KEYS):
        return 'alloc'
    if has(frames, *RECORD_KEYS) or any(DRAW_SCOPE_DRAW.search(f) for f in frames):
        return 'record'
    if first(frames, EXPORT):
        if has(frames, 'AndroidBitmap_lockPixels', 'AndroidBitmap_unlockPixels'):
            return 'glue'
        for f in frames:
            if not f.startswith('rust '):
                continue
            if '::jni::' in f:
                return 'glue'
            if RUST_MASK.search(f):
                return 'mask'
            if RUST_BLUR.search(f):
                return 'blur'
            if RUST_WRITE.search(f):
                return 'write'
        return 'other'
    if has(frames, OURS + 'ObvidNative.'):
        return 'glue'
    draw = next((f for f in reversed(frames) if CANVAS_DRAW.match(f) or f.startswith('android.graphics.Bitmap.eraseColor')), None)
    if draw:
        if re.search(r'\.(draw|native)(Color|Paint)|eraseColor', draw):
            return 'mask'
        for keys, stage in ((SKIA_RESAMPLE, 'resample'), (SKIA_BLUR, 'blur'), (SKIA_CACHE, 'glue'),
                            (SKIA_WRITE_PATCH, 'write'), (SKIA_MASK, 'mask'), (SKIA_BLUR_LATE, 'blur')):
            if has(frames, *keys):
                return stage
        if re.search(r'\.(draw|native)(Bitmap|Image)', draw):
            return 'write'
        if has(frames, OURS) and not has(frames, *SKIA_MASK_FILTER):
            return 'mask'
        return 'write'
    if has(frames, *SETUP_KEYS):
        return 'glue'
    if has(frames, SECTION_END, 'art::gc::', 'atrace_'):
        return 'other'
    app = next((f for f in frames if f.startswith(('dev.serhiiyaremych', 'androidx.', 'android.', 'java.'))), None)
    if app and app.startswith(SHADOW_CODE) and not app.startswith('dev.serhiiyaremych.obvid.sample.'):
        return 'glue'
    return 'other'


class Stages:
    """cpu-clock samples per stage, over every section and over rebuild sections, with leaf symbols per stage."""

    def __init__(self):
        self.every = collections.Counter()
        self.builds = collections.Counter()
        self.leaves = collections.defaultdict(collections.Counter)
        self.unattributed = collections.Counter()  # leaf -> samples without the marker frame

    def add(self, below, rooted, build):
        stage = stage_of(below) if rooted else 'other'
        leaf = short(leaf_name(below))
        self.every[stage] += 1
        if build:
            self.builds[stage] += 1
        if rooted:
            self.leaves[stage][leaf] += 1
        else:
            self.unattributed[leaf] += 1


def split_case(case):
    """(implementation, scenario) of a case name: `cpu_<scenario>` Obvid's, `none_<scenario>` none, else Compose."""
    for impl in ('cpu', 'none'):
        if case.startswith(impl + '_'):
            return impl, case[len(impl) + 1:]
    return 'compose', case


def stage_report(out, case, suite, stages, frames, sections_covered, builds, events):
    """Writes <case>-stages.json and <case>-stages.md and prints the unattributed and `other` leaves.

    JSON: {case, suite, impl, scenario, period_ms, frames (FrameTimingMetric frames inside the records' spans),
    sections (covered shadow sections), builds (covered sections longer than threshold_us), threshold_us, stages (the
    order), samples {stage: n over every section}, ms_per_frame {stage: n × period / frames}, ms_per_section
    {stage: n × period / sections}, ms_per_build {stage: n over rebuild sections × period / builds}, unattributed
    {samples, ms_per_frame} (already inside `other`), section_ms_per_frame (trace duration of the covered sections /
    frames), running_ms_per_frame (main-thread Running in them / frames), events {name: {windows (covered), sections,
    builds (sections in those windows), samples {stage}, ms_per_window {stage: n × period / windows}, ms_per_build
    {stage}, section_ms_per_window}}}. Divisions by zero give null.

    events: {name: Window} from `report`.
    """
    impl, scenario = split_case(case)
    per = lambda n, d: n * PERIOD_MS / d if d else None
    n_sec = len(sections_covered)
    data = dict(
        case=case, suite=suite, impl=impl, scenario=scenario, period_ms=PERIOD_MS, frames=frames, sections=n_sec,
        builds=builds, threshold_us=THRESHOLD_NS / 1e3, stages=list(STAGES),
        samples={k: stages.every[k] for k in STAGES},
        ms_per_frame={k: per(stages.every[k], frames) for k in STAGES},
        ms_per_section={k: per(stages.every[k], n_sec) for k in STAGES},
        ms_per_build={k: per(stages.builds[k], builds) for k in STAGES},
        unattributed=dict(samples=sum(stages.unattributed.values()),
                          ms_per_frame=per(sum(stages.unattributed.values()), frames)),
        section_ms_per_frame=sum(s[2] for s in sections_covered) / 1e6 / frames if frames else None,
        running_ms_per_frame=sum(s[3] for s in sections_covered) / 1e6 / frames if frames else None,
        events={name: dict(
            windows=w.windows, sections=len(w.sections), builds=w.builds,
            samples={k: w.stages.every[k] for k in STAGES},
            ms_per_window={k: per(w.stages.every[k], w.windows) for k in STAGES},
            ms_per_build={k: per(w.stages.builds[k], w.builds) for k in STAGES},
            section_ms_per_window=sum(sec[2] for sec in w.sections) / 1e6 / w.windows if w.windows else None,
        ) for name, w in sorted(events.items())},
    )
    with open(os.path.join(out, f'{case}-stages.json'), 'w') as f:
        json.dump(data, f, indent=1)
    fmt = lambda x: '–' if x is None else f'{x:.3f}'
    total = sum(stages.every.values())
    lines = [f'# {case}: shadow stages on the main thread', '',
             f'{frames} frames, {n_sec} shadow sections ({builds} longer than {THRESHOLD_NS / 1e3:.0f} µs) inside '
             f'the records; {total} cpu-clock samples of {PERIOD_MS} ms in them. Section time '
             f'{fmt(data["section_ms_per_frame"])} ms/frame, main-thread Running in them '
             f'{fmt(data["running_ms_per_frame"])} ms/frame (trace).', '',
             '| stage | samples | share | ms / frame | ms / section | ms / build |', '|---|---|---|---|---|---|']
    for k in STAGES:
        c = stages.every[k]
        share = f'{c / total:.1%}' if total else '–'
        lines.append(f'| {k} | {c} | {share} | {fmt(data["ms_per_frame"][k])} | {fmt(data["ms_per_section"][k])} | '
                     f'{fmt(data["ms_per_build"][k])} |')
    lines.append(f'| **all** | {total} | 100% | {fmt(per(total, frames))} | {fmt(per(total, n_sec))} | '
                 f'{fmt(per(sum(stages.builds.values()), builds))} |')
    lines += ['', f'Unattributed (no section-marker frame, counted in `other`): {data["unattributed"]["samples"]} samples.',
              '', '## Leaf symbols per stage (top 10)', '']
    for k in STAGES:
        if stages.leaves[k]:
            lines.append(f'### {k}')
            lines += [f'- {c}: {leaf}' for leaf, c in stages.leaves[k].most_common(10)]
            lines.append('')
    if stages.unattributed:
        lines.append('### unattributed')
        lines += [f'- {c}: {leaf}' for leaf, c in stages.unattributed.most_common(10)]
        lines.append('')
    if data['events']:
        lines += ['## Event windows', '', 'Sections that start inside each kind of window; ms per window.', '',
                  '| event | windows | sections | builds | section ms / window | ' + ' | '.join(STAGES) + ' | all |',
                  '|---' * (len(STAGES) + 6) + '|']
        for name, e in data['events'].items():
            total_w = sum(e['samples'].values())
            lines.append(f"| {name} | {e['windows']} | {e['sections']} | {e['builds']} | {fmt(e['section_ms_per_window'])} | "
                         + ' | '.join(fmt(e['ms_per_window'][k]) for k in STAGES)
                         + f" | {fmt(per(total_w, e['windows']))} |")
        lines.append('')
    open(os.path.join(out, f'{case}-stages.md'), 'w').write('\n'.join(lines) + '\n')
    mpf = data['ms_per_frame']
    print(f'{case}: stages ms/frame over {frames} frames: ' + ', '.join(f'{k} {fmt(mpf[k])}' for k in STAGES))
    for title, counter in (('other', stages.leaves['other']), ('unattributed', stages.unattributed)):
        if counter:
            print(f'  top {title} leaves: ' + '; '.join(f'{leaf} ({c})' for leaf, c in counter.most_common(8)))


class RustSymbols:
    """Rust function names of libobvid.so by file offset, from an unstripped build with identical .text."""

    def __init__(self, path):
        self.starts, self.names = [], []
        if not path:
            print('RUST_LIB not set: Rust frames stay unnamed', file=sys.stderr)
            return
        with zipfile.ZipFile(APK) as z, tempfile.TemporaryDirectory() as d:
            apk_lib = z.extract('lib/arm64-v8a/' + OUR_LIB, d)
            texts = []
            for lib in (apk_lib, path):
                out = os.path.join(d, 'text%d' % len(texts))
                subprocess.run([os.path.join(LLVM, 'llvm-objcopy'), '-O', 'binary', '--only-section=.text', lib, out],
                               check=True)
                texts.append(open(out, 'rb').read())
            if texts[0] != texts[1]:
                print('RUST_LIB .text differs from the APK\'s: Rust frames stay unnamed', file=sys.stderr)
                return
        nm = subprocess.run([os.path.join(LLVM, 'llvm-nm'), '-C', '-n', '--defined-only', path],
                            capture_output=True, text=True, check=True).stdout
        for line in nm.splitlines():
            parts = line.split(' ', 2)
            if len(parts) == 3 and parts[1] in 'tT':
                self.starts.append(int(parts[0], 16))
                self.names.append(parts[2])

    def name(self, vaddr):
        i = bisect.bisect_right(self.starts, vaddr) - 1
        return self.names[i] if i >= 0 else None


def frames_of(lib, rust):
    """Frame names of the current sample, leaf first; Rust frames named by `rust`, others by simpleperf."""
    out = []

    def add(sym):
        dso = sym.dso_name
        name = sym.symbol_name
        if dso.endswith(OUR_LIB) and not name.startswith(EXPORT):
            name = rust.name(sym.vaddr_in_file) or name
            name = 'rust ' + re.sub(r'::\{closure#\d+\}|::<.*?>$', '', name)
        out.append(name)

    add(lib.GetSymbolOfCurrentSample())
    chain = lib.GetCallChainOfCurrentSample()
    for i in range(chain.nr):
        add(chain.entries[i].symbol)
    return out


def sections_of(trace, min_ns):
    """Shadow sections longer than min_ns of a trace: (mono_start, mono_end, dur_ns, running_ns, khz_weighted_ns,
    {cluster: ns})."""
    clock = collections.defaultdict(lambda: [0.0, 0.0, collections.Counter()])
    for sts, cpu, ns, fns in query(SECTION_CLOCK.format(pkg=PKG, min_ns=min_ns, names=SHADOW_SECTIONS_SQL), trace):
        c = clock[int(sts)]
        c[0] += float(ns)
        c[1] += float(fns)
        c[2][CLUSTER.get(int(cpu), cpu)] += float(ns)
    out = []
    for ts, dur, mono in query(SECTIONS.format(pkg=PKG, min_ns=min_ns, names=SHADOW_SECTIONS_SQL), trace):
        c = clock[int(ts)]
        out.append((int(mono), int(mono) + int(dur), int(dur), c[0], c[1], c[2]))
    return out


class Window:
    """Shadow sections and samples inside the event windows of one name."""

    def __init__(self):
        self.windows = 0
        self.sections = []
        self.builds = 0
        self.stages = Stages()


def windows_of(trace):
    """Event windows of a trace on CLOCK_MONOTONIC: (start, end, name)."""
    return [(int(mono), int(mono) + int(dur), name) for name, _, dur, mono in query(EVENT_WINDOWS, trace)]


def suite_of(raw, case):
    """SUITE, or the one directory of raw other than simpleperf/ that holds case."""
    if SUITE:
        return SUITE
    found = [d for d in sorted(os.listdir(raw)) if d != 'simpleperf' and os.path.isdir(os.path.join(raw, d, case))]
    if len(found) != 1:
        sys.exit(f'{case}: no single suite directory under {raw} holds it (found {found}); set SUITE')
    return found[0]


def report(raw, out, case):
    suite = suite_of(raw, case)
    traces = []
    for t in sorted(glob.glob(os.path.join(raw, suite, case, '*.perfetto-trace')) +
                    glob.glob(os.path.join(raw, suite, case, '*.pftrace'))):
        try:
            traces.append((t, measure_window(t, mono=True)))
        except ValueError as e:
            print(f'skipped: {e}')
    in_window = lambda w, mono: w is None or w[0] <= mono < w[1]
    records = sorted(glob.glob(os.path.join(raw, 'simpleperf', case, '*.data')))
    every = sorted(s for t, w in traces for s in sections_of(t, 0) if in_window(w, s[0]))
    every_starts = [s[0] for s in every]
    windows = sorted(x for t, w in traces for x in windows_of(t) if in_window(w, x[0]))
    window_starts = [w[0] for w in windows]

    def window_of(mono):
        """Name of the event window holding time `mono`, or None (windows of one trace do not overlap)."""
        k = bisect.bisect_right(window_starts, mono) - 1
        return windows[k][2] if k >= 0 and mono <= windows[k][1] else None

    events = collections.defaultdict(Window)
    sections = [s for s in every if s[2] > THRESHOLD_NS]  # rebuilds
    build_index = {id(s): i for i, s in enumerate(sections)}
    frames_mono = [f['ui_mono'] for t, w in traces for f in frame_timings(t, PKG) if in_window(w, f['ui_mono'])]
    rust = RUST
    cats = collections.Counter()
    details = collections.defaultdict(collections.Counter)
    faults = collections.Counter()
    fault_details = collections.defaultdict(collections.Counter)
    folded = collections.Counter()
    cores = collections.Counter()
    sampled = collections.Counter()  # per section index: cpu-clock samples
    covered = set()  # rebuild sections with at least one record covering them
    stages = Stages()
    spans = []  # (first, last) sample time of each record
    for rec in records:
        lib = ReportLib()
        lib.SetLogSeverity('error')
        lib.SetRecordFile(rec)
        lib.ShowArtFrames(True)
        if os.path.exists(MAPPING):
            lib.AddProguardMappingFile(MAPPING)
        first_t = last_t = None
        while (s := lib.GetNextSample()) is not None:
            t = s.time
            first_t = t if first_t is None else min(first_t, t)
            last_t = t if last_t is None else max(last_t, t)
            if s.tid != s.pid:
                continue
            j = bisect.bisect_right(every_starts, t) - 1
            if j < 0 or t > every[j][1]:
                continue
            event = lib.GetEventOfCurrentSample().name
            frames = frames_of(lib, rust)
            root = min((frames.index(r) for r in ROOT_FRAMES if r in frames), default=None)
            below = frames if root is None else frames[:root]
            build = every[j][2] > THRESHOLD_NS
            if event == 'cpu-clock':
                stages.add(below, root is not None, build)
                name = window_of(every[j][0])
                if name:
                    events[name].stages.add(below, root is not None, build)
            i = build_index.get(id(every[j]))
            if i is None:
                continue
            if root is not None:
                cat, detail = categorize(below)
            else:
                cat, detail = 'unattributed', leaf_name(frames)
            if event == 'cpu-clock':
                cats[cat] += 1
                details[cat][detail] += 1
                sampled[i] += 1
                cores[CLUSTER.get(s.cpu, s.cpu)] += 1
                folded[';'.join(reversed(below))] += 1
            elif event == 'page-faults':
                faults[cat] += 1
                fault_details[cat][detail] += 1
        lib.Close()
        if first_t is not None:
            covered.update(i for i, sec in enumerate(sections) if sec[0] >= first_t and sec[1] <= last_t)
            spans.append((first_t, last_t))

    inside = lambda a, b: any(f <= a and b <= l for f, l in spans)
    for a, b, name in windows:
        if inside(a, b):
            events[name].windows += 1
    for sec in every:
        name = window_of(sec[0])
        if name and inside(sec[0], sec[1]):
            events[name].sections.append(sec)
            events[name].builds += sec[2] > THRESHOLD_NS
    stage_report(out, case, suite, stages,
                 frames=sum(1 for m in frames_mono if inside(m, m)),
                 sections_covered=[sec for sec in every if inside(sec[0], sec[1])],
                 builds=sum(1 for sec in sections if inside(sec[0], sec[1])),
                 events=events)

    built = sorted(covered)
    n = len(built)
    total = sum(cats.values())
    dur = sum(sections[i][2] for i in built)
    run = sum(sections[i][3] for i in built)
    khz = sum(sections[i][4] for i in built)
    clusters = collections.Counter()
    for i in built:
        clusters.update(sections[i][5])
    lines = [f'# {case}: main thread inside rebuild sections', '',
             f'Rebuild sections (demo:dropShadow / demo:innerShadow > {THRESHOLD_NS / 1e3:.0f} µs) covered by a record: {n} '
             f'(of {len(sections)} in {len(traces)} traces; {len(records)} records).', '']
    if not n:
        open(os.path.join(out, f'{case}-breakdown.md'), 'w').write('\n'.join(lines) + '\n')
        return None
    mhz = khz / run / 1e3 if run else float('nan')
    core_trace = ', '.join(f'{k} {v / run:.0%}' for k, v in clusters.most_common()) if run else ''
    core_samples = ', '.join(f'{k} {v / total:.0%}' for k, v in cores.most_common()) if total else ''
    lines += [
        '| per build | ms |', '|---|---|',
        f'| section duration (trace) | {dur / n / 1e6:.3f} |',
        f'| main thread Running in the section (trace) | {run / n / 1e6:.3f} |',
        f'| cpu-clock samples × 0.25 ms | {total * PERIOD_MS / n:.3f} |', '',
        f'Main-thread clock while Running in these sections (trace, time-weighted): {mhz:.0f} MHz ({core_trace}); '
        f'cores of the samples: {core_samples}.', '',
        '| category | samples | share | ms / build | page faults / build |', '|---|---|---|---|---|',
    ]
    for cat in sorted(set(cats) | set(faults), key=lambda k: (-cats[k], -faults[k])):
        c = cats[cat]
        lines.append(f'| {cat} | {c} | {c / total:.1%} | {c * PERIOD_MS / n:.3f} | {faults[cat] / n:.1f} |')
    lines.append(f'| **all** | {total} | 100% | {total * PERIOD_MS / n:.3f} | {sum(faults.values()) / n:.1f} |')
    lines += ['', '## Details per category', '', 'samples (share of all in-build samples), page faults per build, detail', '']
    for cat in sorted(set(cats) | set(faults), key=lambda k: (-cats[k], -faults[k])):
        lines.append(f'### {cat}')
        keys = sorted(set(details[cat]) | set(fault_details[cat]), key=lambda d: (-details[cat][d], -fault_details[cat][d]))
        for d in keys[:15]:
            c = details[cat][d]
            lines.append(f'- {c} ({c / total:.1%}), {fault_details[cat][d] / n:.1f} faults: {d}')
        lines.append('')
    open(os.path.join(out, f'{case}-breakdown.md'), 'w').write('\n'.join(lines) + '\n')
    with open(os.path.join(out, f'{case}-folded.txt'), 'w') as f:
        for stack, c in folded.most_common():
            f.write(f'{stack} {c}\n')
    return n, total, cats, faults, dur, run, mhz, core_trace


if __name__ == '__main__':
    if len(sys.argv) < 3:
        sys.exit(__doc__.split('\n\n')[1])
    raw, out = sys.argv[1], sys.argv[2]
    os.makedirs(out, exist_ok=True)
    case_list = sys.argv[3:] or sorted(os.listdir(os.path.join(raw, 'simpleperf')))
    RUST = RustSymbols(RUST_LIB)
    CLUSTER = cluster_for(os.path.abspath(raw))
    for c in case_list:
        r = report(raw, out, c)
        print(f'{c}: ' + ('no covered rebuild section' if r is None else
                          f'{r[0]} builds, {r[1]} samples, {r[1] * PERIOD_MS / r[0]:.3f} ms sampled / build, '
                          f'section {r[4] / r[0] / 1e6:.3f} ms, Running {r[5] / r[0] / 1e6:.3f} ms, {r[6]:.0f} MHz'))
