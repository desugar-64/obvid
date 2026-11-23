"""Per-frame cost of the tier scenarios, from Perfetto traces: Macrobenchmark's (scripts/run_bench.sh tiers) or plain
Perfetto captures of the sample's autoplay (scripts/run_perfetto_tiers.sh, for a device Macrobenchmark cannot run on).

usage: python3 scripts/analyze_tiers.py <dir> [json-out]      e.g. results/<device>/traces-tiers/tiers

<dir> holds one directory per case: `<scenario>` (Compose), `cpu_<scenario>` (Obvid's), `none_<scenario>` (the same
screen without shadows), each with one trace per iteration (*.perfetto-trace from Macrobenchmark, *.pftrace from
run_perfetto_tiers.sh) and skin.txt.

Measured window: a Macrobenchmark trace holds the measure block only, so all of it counts. An autoplay trace also
holds the app's setup and the capture's start and stop; only its demo:measure section counts (perfetto_util
.measure_window: the input script, from its start to the end of its last idle). Frames by doFrame start, shadow
sections and event windows by start (main-thread Running inside doFrame and inside shadow sections: of those that start
in the window), Running time over the whole trace cut to the window. A trace whose demo:measure never ended is skipped.
Frame metrics in both layouts are FrameTimingMetric's definitions computed from the trace (perfetto_util.frame_timings,
validated against Macrobenchmark's JSON); the one difference is the window: Macrobenchmark's trace also takes in the
frames of the few hundred ms between starting the trace and the measure block and between its end and the trace's
stop, which an autoplay trace's window leaves out (idle in the reveal, scroll, nav and interact scripts; a few more
frames of the same animation in the animate ones). Running time "in the whole trace" is Running in the window.

Per case, over all iterations (percentiles over all frames, linear interpolation at p·(n-1)
as Macrobenchmark's; per-frame means = total over all iterations / all frames):
- frames: the frames FrameTimingMetric counts (perfetto_util.frame_timings), per iteration (median);
- frame CPU (DrawFrame end - doFrame start) and overrun P50 / P90, as FrameTimingMetric defines them;
- main thread: Choreographer#doFrame duration P50 / P90; its Running time inside doFrame per frame and in the whole
  trace per frame; its time-weighted clock and core share inside doFrame;
- RenderThread: DrawFrame duration P50 / P90 and mean per frame; its Running time in the whole trace per frame; its
  clock and cores;
- shadow sections (demo:dropShadow + demo:innerShadow, main thread): count and ms per frame, drop and inner apart; main
  thread clock and cores inside them;
- skin temperature at the start of the case.
Counters are time-weighted (each frequency step weighs by the Running time it covers).

Per scenario, against its `none_` case: the main-thread overhead (Running in doFrame per frame, impl - none) and the
RenderThread overhead (Running per frame, impl - none) of each implementation, with doFrame P50 and DrawFrame mean
deltas. The `none_` case's own numbers are Compose's frame cost without shadows (the baseline).

Event windows (perfetto_util.EVENT_WINDOWS): async sections `demo:nav <direction>` (t6_nav, t7_nav: tap to one frame
after the transition settles), `demo:reveal` (t0_single, t1_static, t7_reveal: tap to two frames after the reveal)
and `demo:chip` (t7_interact: chip tap to two frames after the new category's cards are composed). Per event, by its order in the iteration
(medians over iterations): frames, CPU ms of the first 10 frames, max frame CPU ms, overrun frames (> 0) and their
summed overrun ms, shadow-section ms; then the frames outside every event window ("rest").

json-out (default <dir>/tiers.json): the same numbers per case and per scenario, for charts.
"""
import collections, glob, json, os, statistics, sys

from perfetto_util import (DO_FRAME, EVENT_WINDOWS, IN_SHADOW_SECTIONS, MAIN_THREAD, RENDER_THREAD, SHADOW_SECTIONS,
                           SHADOW_SECTIONS_SQL, WHOLE_TRACE, add_running, clip_windows, cluster_for, demo_name_sql,
                           frame_timings, measure_window, num, query, running_summary, windows_starting_in)

PKG = os.environ.get('PKG', 'dev.serhiiyaremych.obvid.sample')
IMPLS = ('cpu', 'none')
FIRST_FRAMES = 10

SECTIONS = f"""
select {demo_name_sql('s.name')}, s.ts, s.dur from slice s join thread_track tt on s.track_id = tt.id join thread t using(utid)
join process p using(upid) where p.name = '{{pkg}}' and t.tid = p.pid and s.name in {SHADOW_SECTIONS_SQL}
and s.dur > 0
"""
DROP_SECTION, INNER_SECTION = SHADOW_SECTIONS


def pct(values, p):
    v = sorted(values)
    if not v:
        return float('nan')
    k = p / 100 * (len(v) - 1)
    lo = int(k)
    hi = min(lo + 1, len(v) - 1)
    return v[lo] + (v[hi] - v[lo]) * (k - lo)


def med(values):
    return statistics.median(values) if values else float('nan')


def split_case(name):
    """(implementation, scenario) of a case directory name."""
    for impl in IMPLS:
        if name.startswith(impl + '_'):
            return impl, name[len(impl) + 1:]
    return 'compose', name


def analyze_case(d, cluster):
    traces = sorted(glob.glob(os.path.join(d, '*.perfetto-trace')) + glob.glob(os.path.join(d, '*.pftrace')))
    used = 0
    frames_per, cpu, over, ui, rt = [], [], [], [], []
    main_frame, main_all, rt_all, in_sections = {}, {}, {}, {}
    sec_n = collections.Counter()
    sec_ns = collections.Counter()
    events = collections.defaultdict(lambda: collections.defaultdict(list))  # key -> field -> per-iteration values
    rest_cpu = []
    for t in traces:
        try:
            w = measure_window(t)
        except ValueError as e:
            print(f'skipped: {e}')
            continue
        used += 1
        lo, hi = w if w else (-2**63, 2**63 - 1)
        inside_window = lambda ts: lo <= ts < hi
        f = [r for r in frame_timings(t, PKG) if inside_window(r['ui_ts'])]
        frames_per.append(len(f))
        cpu += [r['cpu_ns'] / 1e6 for r in f]
        over += [r['overrun_ns'] / 1e6 for r in f]
        ui += [r['ui_dur'] / 1e6 for r in f]
        rt += [r['rt_dur'] / 1e6 for r in f]
        add_running(main_frame, t, PKG, cluster, MAIN_THREAD, windows_starting_in(DO_FRAME, w))
        add_running(main_all, t, PKG, cluster, MAIN_THREAD, clip_windows(WHOLE_TRACE, w))
        add_running(rt_all, t, PKG, cluster, RENDER_THREAD, clip_windows(WHOLE_TRACE, w))
        add_running(in_sections, t, PKG, cluster, MAIN_THREAD, windows_starting_in(IN_SHADOW_SECTIONS, w))
        sections = [(n, int(ts), int(dur)) for n, ts, dur in query(SECTIONS.format(pkg=PKG), t) if inside_window(int(ts))]
        for n, _, dur in sections:
            sec_n[n] += 1
            sec_ns[n] += dur
        evs = [(n, int(ts), int(dur)) for n, ts, dur, _ in query(EVENT_WINDOWS, t) if inside_window(int(ts))]
        inside = set()
        for k, (n, ts, dur) in enumerate(evs, 1):
            te = ts + dur
            fr = [i for i, r in enumerate(f) if ts <= r['ui_ts'] <= te]
            inside.update(fr)
            e = events[f'{k} {n}']
            e['frames'].append(len(fr))
            e['first'].append([f[i]['cpu_ns'] / 1e6 for i in fr[:FIRST_FRAMES]])
            e['max_cpu_ms'].append(max((f[i]['cpu_ns'] / 1e6 for i in fr), default=0.0))
            e['overrun_frames'].append(sum(1 for i in fr if f[i]['overrun_ns'] > 0))
            e['overrun_ms'].append(sum(max(0, f[i]['overrun_ns']) for i in fr) / 1e6)
            e['shadow_ms'].append(sum(d for _, s, d in sections if ts <= s <= te) / 1e6)
        rest_cpu += [r['cpu_ns'] / 1e6 for i, r in enumerate(f) if i not in inside]
    n = sum(frames_per)
    if not n:
        return None
    mf_ns, mf_mhz, mf_cores = running_summary(main_frame)
    ma_ns, _, _ = running_summary(main_all)
    ra_ns, ra_mhz, ra_cores = running_summary(rt_all)
    _, s_mhz, s_cores = running_summary(in_sections)
    skin_file = os.path.join(d, 'skin.txt')
    out = dict(
        iterations=used, frames_per_iteration=med(frames_per), frames=n,
        frame_cpu_ms=dict(p50=pct(cpu, 50), p90=pct(cpu, 90)), overrun_ms=dict(p50=pct(over, 50), p90=pct(over, 90)),
        main=dict(do_frame_ms=dict(p50=pct(ui, 50), p90=pct(ui, 90)), running_in_do_frame_ms_per_frame=mf_ns / n / 1e6,
                  running_ms_per_frame=ma_ns / n / 1e6, mhz=mf_mhz, cores=mf_cores),
        render_thread=dict(draw_frame_ms=dict(p50=pct(rt, 50), p90=pct(rt, 90), mean=sum(rt) / n),
                           running_ms_per_frame=ra_ns / n / 1e6, mhz=ra_mhz, cores=ra_cores),
        shadow=dict(sections_per_frame=sum(sec_n.values()) / n, ms_per_frame=sum(sec_ns.values()) / n / 1e6,
                    drop_ms_per_frame=sec_ns[DROP_SECTION] / n / 1e6,
                    inner_ms_per_frame=sec_ns[INNER_SECTION] / n / 1e6, mhz=s_mhz, cores=s_cores),
        skin=float(open(skin_file).read().split()[0]) if os.path.exists(skin_file) else float('nan'),
    )
    if events:
        ev = {}
        for key, e in events.items():
            longest = max(len(x) for x in e['first'])
            ev[key] = dict(
                frames=med(e['frames']),
                first_frames_cpu_ms=[med([x[i] for x in e['first'] if len(x) > i]) for i in range(longest)],
                max_cpu_ms=med(e['max_cpu_ms']), overrun_frames=med(e['overrun_frames']),
                overrun_ms=med(e['overrun_ms']), shadow_ms=med(e['shadow_ms']),
            )
        out['events'] = ev
        out['rest'] = dict(frames=len(rest_cpu), cpu_ms=dict(p50=pct(rest_cpu, 50), p90=pct(rest_cpu, 90)))
    return out


def clean(x):
    """x with NaN as None (strict JSON)."""
    if isinstance(x, float) and x != x:
        return None
    if isinstance(x, dict):
        return {k: clean(v) for k, v in x.items()}
    if isinstance(x, list):
        return [clean(v) for v in x]
    return x


def f2(x):
    return f'{x:.2f}'


if __name__ == '__main__':
    if len(sys.argv) < 2:
        sys.exit(__doc__.split('\n\n')[1])
    root = sys.argv[1]
    json_out = sys.argv[2] if len(sys.argv) > 2 else os.path.join(root, 'tiers.json')
    cluster = cluster_for(os.path.abspath(root))
    cases = {}
    for d in sorted(glob.glob(os.path.join(root, '*'))):
        if os.path.isdir(d):
            r = analyze_case(d, cluster)
            if r is None:
                print(f'{os.path.basename(d)}: no frames')
            else:
                cases[os.path.basename(d)] = r

    print('| case | iters | frames/iter | frame CPU ms P50 / P90 | overrun ms P50 / P90 | doFrame ms P50 / P90 '
          '| main Running ms/frame in doFrame (all) | main MHz (cores) | DrawFrame ms P50 / P90 / mean '
          '| RT Running ms/frame | RT MHz (cores) | shadow sections/frame | shadow ms/frame (drop + inner) '
          '| MHz in shadow (cores) | skin °C |')
    print('|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|')
    for name, c in cases.items():
        m, r, s = c['main'], c['render_thread'], c['shadow']
        print(f"| {name} | {c['iterations']} | {c['frames_per_iteration']:.0f} | {f2(c['frame_cpu_ms']['p50'])} / "
              f"{f2(c['frame_cpu_ms']['p90'])} | {f2(c['overrun_ms']['p50'])} / {f2(c['overrun_ms']['p90'])} | "
              f"{f2(m['do_frame_ms']['p50'])} / {f2(m['do_frame_ms']['p90'])} | {f2(m['running_in_do_frame_ms_per_frame'])} "
              f"({f2(m['running_ms_per_frame'])}) | {m['mhz']:.0f} ({m['cores']}) | {f2(r['draw_frame_ms']['p50'])} / "
              f"{f2(r['draw_frame_ms']['p90'])} / {f2(r['draw_frame_ms']['mean'])} | {f2(r['running_ms_per_frame'])} | "
              f"{r['mhz']:.0f} ({r['cores']}) | {s['sections_per_frame']:.1f} | {f2(s['ms_per_frame'])} "
              f"({f2(s['drop_ms_per_frame'])} + {f2(s['inner_ms_per_frame'])}) | {s['mhz']:.0f} ({s['cores']}) | "
              f"{c['skin']:.1f} |")

    scenarios = collections.defaultdict(dict)
    for name, c in cases.items():
        impl, scenario = split_case(name)
        scenarios[scenario][impl] = c
    print('\nOverhead against the `none_` case of the scenario (impl - none, ms per frame):\n')
    print('| scenario | impl | Δ main Running in doFrame | Δ doFrame P50 | Δ RT Running | Δ DrawFrame mean '
          '| shadow ms/frame | none: main Running in doFrame, RT Running (baseline) |')
    print('|---|---|---|---|---|---|---|---|')
    deltas = {}
    for scenario, by in sorted(scenarios.items()):
        base = by.get('none')
        for impl in ('compose', 'cpu'):
            c = by.get(impl)
            if c is None or base is None:
                continue
            d = dict(
                main_running_ms_per_frame=c['main']['running_in_do_frame_ms_per_frame'] - base['main']['running_in_do_frame_ms_per_frame'],
                do_frame_p50_ms=c['main']['do_frame_ms']['p50'] - base['main']['do_frame_ms']['p50'],
                rt_running_ms_per_frame=c['render_thread']['running_ms_per_frame'] - base['render_thread']['running_ms_per_frame'],
                draw_frame_mean_ms=c['render_thread']['draw_frame_ms']['mean'] - base['render_thread']['draw_frame_ms']['mean'],
            )
            deltas.setdefault(scenario, {})[impl] = d
            print(f"| {scenario} | {impl} | {f2(d['main_running_ms_per_frame'])} | {f2(d['do_frame_p50_ms'])} | "
                  f"{f2(d['rt_running_ms_per_frame'])} | {f2(d['draw_frame_mean_ms'])} | {f2(c['shadow']['ms_per_frame'])} | "
                  f"{f2(base['main']['running_in_do_frame_ms_per_frame'])}, {f2(base['render_thread']['running_ms_per_frame'])} |")

    with_events = {k: c for k, c in cases.items() if 'events' in c}
    if with_events:
        print(f'\nEvent windows (medians over iterations; first {FIRST_FRAMES} frames: frame CPU ms):\n')
        print('| case | event | frames | first frames CPU ms | max CPU ms | overrun frames | overrun ms | shadow ms |')
        print('|---|---|---|---|---|---|---|---|')
        for name, c in with_events.items():
            for key, e in c['events'].items():
                first = ' '.join(f'{x:.1f}' for x in e['first_frames_cpu_ms'])
                print(f"| {name} | {key} | {e['frames']:.0f} | {first} | {f2(e['max_cpu_ms'])} | {e['overrun_frames']:.0f} | "
                      f"{f2(e['overrun_ms'])} | {f2(e['shadow_ms'])} |")
            rest = c['rest']
            print(f"| {name} | rest | {rest['frames']} (all iterations) | P50 {f2(rest['cpu_ms']['p50'])}, "
                  f"P90 {f2(rest['cpu_ms']['p90'])} | | | | |")

    with open(json_out, 'w') as f:
        json.dump(clean(dict(cases=cases, overhead_vs_none=deltas)), f, indent=1)
    print(f'\nwrote {json_out}')
