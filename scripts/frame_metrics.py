"""Macrobenchmark's frame and trace-section metrics, computed on the host from Perfetto traces.

usage: python3 scripts/frame_metrics.py <scenario dir> [<scenario dir> ...]
       (each dir holds one trace per iteration: *.perfetto-trace or *.pftrace)

For devices where Macrobenchmark's on-device trace processor does not answer,
scripts/run_perfetto.sh captures the traces with Macrobenchmark's own Perfetto config and this script
computes the metrics. It mirrors androidx.benchmark 1.5:
- FrameTimingMetric (FrameTimingQuery.kt, API >= 31): per RenderThread `DrawFrame*` slice, the UI-thread
  `Choreographer#doFrame*` slice with the same trailing frame id; the actual frame-timeline slice is the
  last unused one that starts before ui.ts + 50 µs and contains the UI slice's midpoint; the expected
  slice has the actual slice's id. frameDurationCpuMs = rt.end - ui.ts; frameOverrunMs =
  max(actual.end, rt.end) - expected.end. Slices with dur <= 0 and "resynced" ones are dropped.
- Percentiles over all frames of all iterations, linear interpolation at p·(n-1) (MetricResult).
- TraceSectionMetric Sum/Count for `demo:dropShadow` in the app (median over iterations).
- MemoryUsageMetric(Last) RssAnon: the last `mem.rss.anon` value of the app (median over iterations).
Validated against Macrobenchmark's own JSON for the same traces.
WINDOW_S=<s> keeps only the first <s> seconds of each trace from its first kernel (sched/ftrace) event: frames by UI
slice start, sections by start, RSS as the last value, each in [start, start + s). `perfetto` from the shell records
more than duration_ms when a producer misses the final flush (~9 s of kernel events for 5000 under traced's default
5 s flush timeout), so this matches Macrobenchmark's 5 s measure block. The anchor is the first kernel event, not trace_bounds or the first slice: a stale
packet can put those many seconds early. Without WINDOW_S the whole trace counts.
"""
import glob, os, re, statistics, sys

from perfetto_util import DROP_SECTION_SQL, kernel_start, query

PKG = os.environ.get('PKG', 'dev.serhiiyaremych.obvid.sample')
WINDOW_NS = int(float(os.environ.get('WINDOW_S', '0')) * 1e9)

FRAMES = f"""
select 'S' kind, slice.name, slice.ts, slice.dur from slice
  join thread_track on slice.track_id = thread_track.id join thread using(utid) join process using(upid)
  where ((slice.name like 'Choreographer#doFrame%' and slice.name not like 'Choreographer#doFrame - resynced to%'
          and process.pid = thread.tid) or (slice.name like 'DrawFrame%' and thread.name = 'RenderThread'))
    and process.name like '{PKG}'
union all
select 'A', a.name, a.ts, a.dur from actual_frame_timeline_slice a join process using(upid) where process.name like '{PKG}'
union all
select 'E', e.name, e.ts, e.dur from expected_frame_timeline_slice e join process using(upid) where process.name like '{PKG}'
"""
SECTIONS = f"""
select count(*) n, sum(s.dur) ns from slice s join thread_track tt on s.track_id = tt.id join thread using(utid)
  join process using(upid) where process.name like '{PKG}' and s.name in {DROP_SECTION_SQL} and s.dur > 0
  and s.ts >= {{lo}} and s.ts < {{hi}}
"""
RSS = f"""
select c.value from counter c join process_counter_track t on c.track_id = t.id join process using(upid)
  where process.name like '{PKG}' and t.name = 'mem.rss.anon' and c.ts >= {{lo}} and c.ts < {{hi}}
  order by c.ts desc limit 1
"""


def window(trace):
    """[lo, hi) in trace ns: WINDOW_NS from the first kernel event, or everything when WINDOW_S is unset."""
    if not WINDOW_NS:
        return -2**63, 2**63 - 1
    start = kernel_start(trace)
    return start, start + WINDOW_NS


def frame_id(name):
    m = re.search(r'(\d+)$', name)
    return int(m.group(1)) if m else None


def frames(trace, lo, hi):
    ui, rt, act, exp = {}, [], [], {}
    for kind, name, ts, dur in query(FRAMES, trace):
        ts, dur = int(ts), int(dur)
        if dur <= 0 or 'resynced' in name:
            continue
        if kind == 'S' and name.startswith('Choreographer') and not lo <= ts < hi:
            continue
        if kind == 'A':
            act.append((ts, dur, frame_id(name)))
        elif kind == 'E':
            exp[frame_id(name)] = (ts, dur)
        elif name.startswith('Choreographer#doFrame'):
            ui[frame_id(name)] = (ts, dur)
        else:
            rt.append((ts, dur, frame_id(name)))
    act.sort()
    out = []
    for rts, rdur, fid in sorted(rt):
        u = ui.get(fid)
        if u is None:
            continue
        uts, udur = u
        mid = uts + udur // 2
        pick = None
        for i in range(len(act) - 1, -1, -1):  # lastOrNull over the pool (ordered by ts)
            ats, adur, aid = act[i]
            if ats < uts + 50_000 and ats <= mid <= ats + adur:
                pick = i
                break
        if pick is None:
            continue
        ats, adur, aid = act.pop(pick)
        e = exp.get(aid)
        if e is None:
            continue
        cpu = rts + rdur - uts
        overrun = max(ats + adur, rts + rdur) - (e[0] + e[1])
        out.append((cpu / 1e6, overrun / 1e6))
    return out


def pct(values, p):
    v = sorted(values)
    k = p / 100 * (len(v) - 1)
    lo = int(k)
    hi = min(lo + 1, len(v) - 1)
    return v[lo] + (v[hi] - v[lo]) * (k - lo)


print('| scenario | iters | frames/iter | frame CPU ms P50 / P90 / P99 | overrun ms P50 / P90 / P99 '
      '| shadow draws/frame | shadow ms/draw | shadow ms/frame | RSS anon MB |')
print('|---|---|---|---|---|---|---|---|---|')
for d in sys.argv[1:]:
    traces = sorted(glob.glob(os.path.join(d, '*.perfetto-trace')) + glob.glob(os.path.join(d, '*.pftrace')))
    cpu, over, counts, sums, frames_per, rss = [], [], [], [], [], []
    for t in traces:
        lo, hi = window(t)
        f = frames(t, lo, hi)
        cpu += [c for c, _ in f]
        over += [o for _, o in f]
        frames_per.append(len(f))
        (n, ns), = query(SECTIONS.format(lo=lo, hi=hi), t)
        counts.append(int(n))
        sums.append(0.0 if ns in ('', '[NULL]') else float(ns) / 1e6)
        r = query(RSS.format(lo=lo, hi=hi), t)
        if r:
            rss.append(float(r[0][0]) / 1024 / 1024)
    if not cpu:
        print(f'| {os.path.basename(d)} | {len(traces)} | no frames |')
        continue
    fr, n, s = statistics.median(frames_per), statistics.median(counts), statistics.median(sums)
    print(f'| {os.path.basename(d)} | {len(traces)} | {fr:.0f} | {pct(cpu, 50):.1f} / {pct(cpu, 90):.1f} / {pct(cpu, 99):.1f} | '
          f'{pct(over, 50):.1f} / {pct(over, 90):.1f} / {pct(over, 99):.1f} | {n / fr:.1f} | {s / n if n else 0:.3f} | '
          f'{s / fr:.2f} | {statistics.median(rss) if rss else float("nan"):.0f} |')
