"""Macrobenchmark table: one row per scenario directory of a run (scripts/run_bench.sh macro|grid).

usage: python3 scripts/analyze_macro.py <dir> [package]      e.g. results/<device>/traces-<run>/macro

From Macrobenchmark's JSON (medians over iterations, percentiles over all frames): frame CPU time,
frame overrun, frames per iteration, `demo:dropShadow` sections (the sample's marker around each card's
shadow draw) per frame and their mean, anonymous RSS at the end. From each iteration's Perfetto trace
(summed over iterations): the main thread's time-weighted clock and core share while inside
`demo:dropShadow`, and RenderThread's DrawFrame time per frame, and texture uploads per frame. Counters are time-weighted.
"""
import glob, json, os, sys

from perfetto_util import add_shadow_clock, clock_summary, cluster_for, demo_names, num, query

if len(sys.argv) < 2:
    sys.exit(__doc__.split('\n\n')[1])
root = sys.argv[1]
package = sys.argv[2] if len(sys.argv) > 2 else 'dev.serhiiyaremych.obvid.sample'
CLUSTER = cluster_for(root)

# RenderThread DrawFrame slices of the app.
RENDER = """
select count(*) n, sum(s.dur) ns from slice s join thread_track tt on s.track_id = tt.id
join thread t using(utid) join process p using(upid)
where p.name = '{pkg}' and t.name = 'RenderThread' and s.name like 'DrawFrame%'
"""
# HWUI texture uploads ("Texture upload(id) WxH") of the app on any thread.
UPLOAD = """
select count(*) n, sum(s.dur) ns from slice s join thread_track tt on s.track_id = tt.id
join thread t using(utid) join process p using(upid)
where p.name = '{pkg}' and s.name like 'Texture upload%'
"""


def metric(metrics, name, key='median'):
    m = metrics.get(name)
    return m[key] if m and key in m else float('nan')


def section_metric(metrics, suffix):
    """Macrobenchmark's `<section><suffix>` metric of demo:dropShadow, or of its old name in an older run."""
    names = [n + suffix for n in demo_names('dropShadow')]
    return metric(metrics, next((n for n in names if n in metrics), names[0]))


print('| scenario | frames/iter | frame CPU ms P50 / P90 / P99 | overrun ms P50 / P99 | shadow draws/frame '
      '| shadow ms/draw | shadow ms/frame | main clock MHz (cores) | RenderThread ms/frame | uploads/frame, ms/frame | RSS anon MB | skin °C |')
print('|---|---|---|---|---|---|---|---|---|---|---|---|')
for d in sorted(glob.glob(os.path.join(root, '*'))):
    name = os.path.basename(d)
    data = glob.glob(os.path.join(d, '*-benchmarkData.json'))
    if not data:
        print(f'| {name} | no JSON |')
        continue
    b = json.load(open(data[0]))['benchmarks'][0]
    m, s = b['metrics'], b.get('sampledMetrics', {})
    frames = metric(m, 'frameCount')
    count = section_metric(m, 'Count')
    total = section_metric(m, 'SumMs')
    clock = {}
    rt_n = rt_ns = up_n = up_ns = 0
    for trace in sorted(glob.glob(os.path.join(d, '*.perfetto-trace'))):
        add_shadow_clock(clock, trace, package, CLUSTER)
        for n, ns in query(RENDER.format(pkg=package), trace):
            rt_n += int(num(n))
            rt_ns += num(ns)
        for n, ns in query(UPLOAD.format(pkg=package), trace):
            up_n += int(num(n))
            up_ns += num(ns)
    mhz, cores = clock_summary(clock)
    skin = open(os.path.join(d, 'skin.txt')).read().strip()
    print(f"| {name} | {frames:.0f} | {metric(s, 'frameDurationCpuMs', 'P50'):.1f} / {metric(s, 'frameDurationCpuMs', 'P90'):.1f} / "
          f"{metric(s, 'frameDurationCpuMs', 'P99'):.1f} | {metric(s, 'frameOverrunMs', 'P50'):.1f} / "
          f"{metric(s, 'frameOverrunMs', 'P99'):.1f} | {count / frames:.1f} | {total / count:.3f} | {total / frames:.2f} | "
          f"{mhz:.0f} ({cores}) | {rt_ns / rt_n / 1e6 if rt_n else float('nan'):.2f} | "
          f"{up_n / rt_n if rt_n else 0:.1f}, {up_ns / rt_n / 1e6 if rt_n else 0:.2f} | "
          f"{metric(m, 'memoryRssAnonLastKb') / 1024:.0f} | {float(skin):.1f} |")
