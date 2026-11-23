"""Shadow hits and rebuilds per case of a re-entry run (scripts/run_bench.sh reentry), from the Perfetto traces.

usage: python3 scripts/analyze_reentry.py <dir> [package]      e.g. results/<device>/traces-reentry/reentry

Every `demo:dropShadow` section (the sample's marker around each card's shadow draw, app main thread) is a draw that
either reuses a built shadow (a hit: a few µs) or builds one first (a rebuild: tens to hundreds of µs). The two show
as two modes of the section durations on a log scale; the threshold between them comes from the data, pooled over
every case of the directory so that all cases are split at the same point:
1. histogram of log10(duration) in bins of 0.1 decade (×1.26);
2. Otsu's split of that histogram (the cut that maximizes the between-class variance) separates the two modes;
3. hit mode = the fullest bin below the split, rebuild mode = the fullest bin above it;
4. threshold = the middle (log scale) of the leftmost run of least-filled bins strictly between the two modes.
The histogram is printed per case with the threshold marked, so the split can be checked by eye; THRESHOLD_US=<µs>
overrides it.

Per case: sections per iteration, the duration distribution (P50 / P90 / P99 / max over all iterations' sections),
hits and rebuilds per iteration (median, min–max), the median hit and rebuild duration, rebuild time per iteration;
from Macrobenchmark's JSON frames per iteration and frame CPU time P50 / P90 / P99 (all frames of all iterations),
overrun P50 / P99; the main thread's time-weighted clock and core share inside all sections and inside rebuilds only.
Percentiles interpolate linearly at p·(n-1), as Macrobenchmark's do.
"""
import glob, json, math, os, statistics, sys

from perfetto_util import DROP_SECTION_SQL, add_shadow_clock, clock_summary, cluster_for, query

if len(sys.argv) < 2:
    sys.exit(__doc__.split('\n\n')[1])
root = sys.argv[1]
package = sys.argv[2] if len(sys.argv) > 2 else 'dev.serhiiyaremych.obvid.sample'
CLUSTER = cluster_for(root)
BIN = 0.1  # decades

SECTIONS = """
select s.dur from slice s join thread_track tt on s.track_id = tt.id join thread t using(utid) join process p using(upid)
where p.name = '{pkg}' and t.tid = p.pid and s.name in """ + DROP_SECTION_SQL + """ and s.dur > 0
"""


def pct(values, p):
    v = sorted(values)
    if not v:
        return float('nan')
    k = p / 100 * (len(v) - 1)
    lo = int(k)
    hi = min(lo + 1, len(v) - 1)
    return v[lo] + (v[hi] - v[lo]) * (k - lo)


def bin_of(us):
    return math.floor(math.log10(us) / BIN)


def otsu(hist, lo, hi):
    """First bin of the upper class: the cut in [lo, hi] that maximizes w0·w1·(m0 - m1)²."""
    total = sum(hist.values())
    best, cut = -1.0, None
    for c in range(lo + 1, hi + 1):
        n0 = sum(hist.get(b, 0) for b in range(lo, c))
        n1 = total - n0
        if not n0 or not n1:
            continue
        m0 = sum(b * hist.get(b, 0) for b in range(lo, c)) / n0
        m1 = sum(b * hist.get(b, 0) for b in range(c, hi + 1)) / n1
        v = n0 * n1 * (m0 - m1) ** 2
        if v > best:
            best, cut = v, c
    return cut


def derive_threshold(hist):
    """(threshold µs, description) from the pooled histogram, or (nan, reason)."""
    if not hist:
        return float('nan'), 'no sections'
    lo, hi = min(hist), max(hist)
    cut = otsu(hist, lo, hi)
    if cut is None:
        return float('nan'), 'one bin only'
    hit = max(range(lo, cut), key=lambda b: (hist.get(b, 0), -b))
    build = max(range(cut, hi + 1), key=lambda b: (hist.get(b, 0), -b))
    between = range(hit + 1, build)
    if not between:
        t = 10 ** (cut * BIN)
        return t, f'Otsu cut {t:.1f} µs; modes adjacent, no valley: threshold = the cut (weak separation)'
    least = min(hist.get(b, 0) for b in between)
    first = next(b for b in between if hist.get(b, 0) == least)
    last = first
    while last + 1 in between and hist.get(last + 1, 0) == least:
        last += 1
    t = 10 ** ((first + last + 1) / 2 * BIN)
    depth = least / min(hist.get(hit, 0), hist.get(build, 0))
    return t, (f'Otsu cut {10 ** (cut * BIN):.1f} µs; hit mode {10 ** ((hit + 0.5) * BIN):.1f} µs '
               f'({hist.get(hit, 0)}), rebuild mode {10 ** ((build + 0.5) * BIN):.1f} µs ({hist.get(build, 0)}); '
               f'valley {10 ** (first * BIN):.1f}–{10 ** ((last + 1) * BIN):.1f} µs holds {least} per bin '
               f'= {depth:.1%} of the smaller mode')


def metric(metrics, name, key='median'):
    m = metrics.get(name)
    return m[key] if m and key in m else float('nan')


# Per case: list of per-trace duration lists (µs).
cases = {}
for d in sorted(glob.glob(os.path.join(root, '*'))):
    if not os.path.isdir(d):
        continue
    traces = sorted(glob.glob(os.path.join(d, '*.perfetto-trace')))
    cases[os.path.basename(d)] = (d, traces, [[int(r[0]) / 1e3 for r in query(SECTIONS.format(pkg=package), t)] for t in traces])

pooled = {}
for _, _, per_trace in cases.values():
    for durs in per_trace:
        for us in durs:
            b = bin_of(us)
            pooled[b] = pooled.get(b, 0) + 1

if os.environ.get('THRESHOLD_US'):
    threshold = float(os.environ['THRESHOLD_US'])
    how = 'set by THRESHOLD_US'
else:
    threshold, how = derive_threshold(pooled)
print(f'threshold {threshold:.1f} µs ({how}); a section longer than it counts as a rebuild\n')

# Histogram: one row per bin, one count column per case, the pooled bar on a log scale (8 marks per decade of count).
names = list(cases)
print('| bin µs | ' + ' | '.join(names) + ' | all (log bar) |')
print('|---|' + '---|' * len(names) + '---|')
case_hist = {n: {} for n in names}
for n, (_, _, per_trace) in cases.items():
    for durs in per_trace:
        for us in durs:
            b = bin_of(us)
            case_hist[n][b] = case_hist[n].get(b, 0) + 1
if pooled:
    marked = False
    for b in range(min(pooled), max(pooled) + 1):
        a, z = 10 ** (b * BIN), 10 ** ((b + 1) * BIN)
        if not marked and a >= threshold:
            print('| **threshold %.1f** |' % threshold + ' |' * len(names) + ' |')
            marked = True
        n = pooled.get(b, 0)
        print(f'| {a:.1f}–{z:.1f} | ' + ' | '.join(str(case_hist[c].get(b, 0)) for c in names) +
              f" | {'#' * round(8 * math.log10(1 + n))} {n} |")
print()

print('| case | iters | sections/iter | section µs P50 / P90 / P99 / max | hits/iter | rebuilds/iter (min–max) '
      '| hit µs P50 | rebuild µs P50 | rebuild ms/iter | frames/iter | frame CPU ms P50 / P90 / P99 | overrun ms P50 / P99 '
      '| main clock MHz in sections (cores) | in rebuilds (cores) | skin °C |')
print('|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|')
for name, (d, traces, per_trace) in cases.items():
    if not traces:
        print(f'| {name} | 0 | no traces |')
        continue
    durs = [us for t in per_trace for us in t]
    hits = [us for us in durs if us <= threshold]
    builds = [us for us in durs if us > threshold]
    builds_per = [sum(1 for us in t if us > threshold) for t in per_trace]
    hits_per = [sum(1 for us in t if us <= threshold) for t in per_trace]
    build_ms = [sum(us for us in t if us > threshold) / 1e3 for t in per_trace]
    every, rebuilt = {}, {}
    for t in traces:
        add_shadow_clock(every, t, package, CLUSTER)
        if not math.isnan(threshold):
            add_shadow_clock(rebuilt, t, package, CLUSTER, min_ns=int(threshold * 1e3))
    mhz, cores = clock_summary(every)
    bmhz, bcores = clock_summary(rebuilt)
    data = glob.glob(os.path.join(d, '*-benchmarkData.json'))
    m = s = {}
    if data:
        b = json.load(open(data[0]))['benchmarks'][0]
        m, s = b['metrics'], b.get('sampledMetrics', {})
    skin_file = os.path.join(d, 'skin.txt')
    skin = float(open(skin_file).read().strip()) if os.path.exists(skin_file) else float('nan')
    print(f"| {name} | {len(traces)} | {statistics.median(len(t) for t in per_trace):.0f} | "
          f"{pct(durs, 50):.1f} / {pct(durs, 90):.1f} / {pct(durs, 99):.1f} / {max(durs, default=float('nan')):.1f} | "
          f"{statistics.median(hits_per):.0f} | {statistics.median(builds_per):.0f} ({min(builds_per)}–{max(builds_per)}) | "
          f"{pct(hits, 50):.1f} | {pct(builds, 50):.1f} | {statistics.median(build_ms):.2f} | "
          f"{metric(m, 'frameCount'):.0f} | {metric(s, 'frameDurationCpuMs', 'P50'):.1f} / "
          f"{metric(s, 'frameDurationCpuMs', 'P90'):.1f} / {metric(s, 'frameDurationCpuMs', 'P99'):.1f} | "
          f"{metric(s, 'frameOverrunMs', 'P50'):.1f} / {metric(s, 'frameOverrunMs', 'P99'):.1f} | "
          f"{mhz:.0f} ({cores}) | {bmhz:.0f} ({bcores}) | {skin:.1f} |")
