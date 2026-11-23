"""Shared by the analysis scripts: trace_processor_shell queries, the core-cluster maps, the main thread's clock inside
`demo:dropShadow`, the demo section names, the capture check.

As a script it checks captures for data the tracing service reports lost (run_perfetto.sh calls it after each capture):

  python3 scripts/perfetto_util.py check <trace> [<trace> ...]

prints one line per trace: its kernel span (first to last sched/ftrace event) and either `ok` or a WARNING naming each
nonzero data-loss stat, failed flushes first (a producer that does not answer a flush holds the capture until
flush_timeout_ms and may lose its last chunks). Exit status 0 either way: a warning does not stop a batch.
"""
import os, subprocess, sys, tempfile

TP = os.environ.get('TRACE_PROCESSOR', os.path.expanduser('~/.local/share/perfetto/prebuilts/trace_processor_shell'))

# Core types by ro.product.model (the results/<device>/ folder name); other devices are labelled cpu0..cpu7.
CLUSTERS = {
    'Pixel_6': {0: 'A55', 1: 'A55', 2: 'A55', 3: 'A55', 4: 'A76', 5: 'A76', 6: 'X1', 7: 'X1'},
}
GENERIC = {i: f'cpu{i}' for i in range(16)}


def cluster_for(path):
    """The core-cluster map of the device a results path belongs to (results/<device>/...)."""
    return next((m for model, m in CLUSTERS.items() if f'/{model}/' in f'{os.path.abspath(path)}/'), GENERIC)


def query(sql, trace):
    """Rows of a trace_processor_shell query as lists of strings (quotes stripped, NULL as '[NULL]')."""
    with tempfile.NamedTemporaryFile('w', suffix='.sql', delete=False) as q:
        q.write(sql)
    try:
        out = subprocess.run([TP, '-q', q.name, trace], capture_output=True, text=True).stdout
    finally:
        os.unlink(q.name)
    return [l.replace('"', '').split(',') for l in out.strip().splitlines()[1:]]


def num(x):
    return 0.0 if x in ('', '[NULL]', None) else float(x)


# The sample's own trace sections are `demo:<name>`; traces recorded before the rename name them `OB:<name>`. Queries
# match both spellings and report the current one.
DEMO_PREFIX = 'demo:'
OLD_DEMO_PREFIX = 'OB:'


def demo_names(*names):
    """Both spellings of the demo sections `names` (each without its prefix)."""
    return tuple(p + n for n in names for p in (DEMO_PREFIX, OLD_DEMO_PREFIX))


def sql_list(names):
    """An SQL list literal of `names`."""
    return '(' + ', '.join(f"'{n}'" for n in names) + ')'


def demo_name_sql(column):
    """SQL: `column` with the old demo prefix replaced by the current one."""
    return (f"case when {column} glob '{OLD_DEMO_PREFIX}*' then '{DEMO_PREFIX}' || substr({column}, "
            f"{len(OLD_DEMO_PREFIX) + 1}) else {column} end")


DROP_SECTION = DEMO_PREFIX + 'dropShadow'
DROP_SECTION_SQL = sql_list(demo_names('dropShadow'))


# Main thread (tid = pid) Running inside demo:dropShadow sections longer than {min_ns}, split by its core's frequency steps.
SHADOW_CLOCK = """
with p as (select upid, pid from process where name = '{pkg}'),
main as (select utid from thread join p using(upid) where tid = p.pid),
sec as (select s.ts, s.ts + s.dur te from slice s join thread_track tt on s.track_id = tt.id
        where tt.utid in (select utid from main) and s.name in """ + DROP_SECTION_SQL + """ and s.dur > {min_ns}),
run as (select max(st.ts, sec.ts) a, min(st.ts + st.dur, sec.te) b, st.cpu from sec
        join thread_state st on st.ts < sec.te and st.ts + st.dur > sec.ts
        where st.utid in (select utid from main) and st.state = 'Running'),
f as (select cct.cpu, c.ts, lead(c.ts, 1, (select end_ts from trace_bounds)) over (partition by cct.cpu order by c.ts) te,
      c.value v from counter c join cpu_counter_track cct on c.track_id = cct.id where cct.type = 'cpu_frequency')
select run.cpu, sum(min(run.b, f.te) - max(run.a, f.ts)) ns, sum((min(run.b, f.te) - max(run.a, f.ts)) * f.v) fns
from run join f on f.cpu = run.cpu and f.ts < run.b and f.te > run.a group by 1
"""


def add_shadow_clock(acc, trace, pkg, cluster, min_ns=0):
    """Adds the trace's main-thread Running time inside demo:dropShadow (sections longer than min_ns) to acc, a dict
    cluster -> [ns, ns·kHz]."""
    for cpu, ns, fns in query(SHADOW_CLOCK.format(pkg=pkg, min_ns=min_ns), trace):
        c = acc.setdefault(cluster.get(int(cpu), cpu), [0.0, 0.0])
        c[0] += float(ns)
        c[1] += float(fns)


def clock_summary(acc):
    """(time-weighted MHz, 'cluster share, ...') of an add_shadow_clock accumulator."""
    run_ns = sum(v[0] for v in acc.values())
    if not run_ns:
        return float('nan'), ''
    mhz = sum(v[1] for v in acc.values()) / run_ns / 1e3
    cores = ', '.join(f'{k} {v[0] / run_ns:.0%}' for k, v in sorted(acc.items(), key=lambda kv: -kv[1][0]))
    return mhz, cores


# The first kernel event of the trace. trace_bounds is not usable as an anchor: a stale packet (a slice or a
# process_stats sample from before the session) can put its start many seconds early.
KERNEL_START = """
select min(ts) from (select min(ts) ts from sched union all select min(ts) ts from ftrace_event)
"""
KERNEL_SPAN = """
select (select min(ts) from (select min(ts) ts from sched union all select min(ts) ts from ftrace_event)),
       (select max(ts) from (select max(ts + dur) ts from sched where dur > 0 union all select max(ts) ts from ftrace_event))
"""
DATA_LOSS = """
select name, idx, value from stats where value > 0 and (severity = 'data_loss' or name like '%flush%fail%')
order by name like '%flush%' desc, name
"""


def kernel_start(trace):
    (t,), = query(KERNEL_START, trace)
    return int(t)


def check(trace):
    (a, b), = query(KERNEL_SPAN, trace)
    span = f'{(int(b) - int(a)) / 1e9:.1f} s' if a != '[NULL]' and b != '[NULL]' else 'no kernel events'
    lost = [f"{n}{'' if i == '[NULL]' else f'[{i}]'}={v}" for n, i, v in query(DATA_LOSS, trace)]
    rows = query(MEASURE, trace)
    if rows and int(rows[0][1]) <= 0:
        lost.append(f'{MEASURE_SECTION} never ended')
    measured = f', measured {int(rows[0][1]) / 1e9:.2f} s' if rows and int(rows[0][1]) > 0 else ''
    return f"{os.path.basename(trace)}: kernel span {span}{measured}, " + (f"WARNING {', '.join(lost)}" if lost else 'ok')


# The measured window of a trace the sample's autoplay drove (scripts/run_perfetto_tiers.sh): its async section
# demo:measure around the input script. Macrobenchmark traces have none: they hold the measure block only.
MEASURE_SECTION = DEMO_PREFIX + 'measure'
MEASURE = f"select ts, dur, to_monotonic(ts) from slice where name in {sql_list(demo_names('measure'))} order by ts limit 1"


def measure_window(trace, mono=False):
    """(start, end) of the trace's demo:measure section, in trace time or with mono on CLOCK_MONOTONIC; None without one.
    Raises ValueError if the section never ended (the capture stopped inside the script)."""
    rows = query(MEASURE, trace)
    if not rows:
        return None
    ts, dur, start_mono = (int(x) for x in rows[0])
    if dur <= 0:
        raise ValueError(f'{trace}: {MEASURE_SECTION} never ended')
    start = start_mono if mono else ts
    return start, start + dur


def clip_windows(windows, window):
    """The windows query (rows ts, te) cut to window = (start, end) in trace time; unchanged for None."""
    if window is None:
        return windows
    lo, hi = window
    return f'select max(ts, {lo}) ts, min(te, {hi}) te from ({windows}) where ts < {hi} and te > {lo}'


def windows_starting_in(windows, window):
    """The rows of the windows query (ts, te) that start inside window = (start, end), whole; unchanged for None."""
    if window is None:
        return windows
    lo, hi = window
    return f'select ts, te from ({windows}) where ts >= {lo} and ts < {hi}'


if __name__ == '__main__':
    if len(sys.argv) < 3 or sys.argv[1] != 'check':
        sys.exit('usage: perfetto_util.py check <trace> [<trace> ...]')
    for t in sys.argv[2:]:
        print(check(t))


# The tier scenarios' shadow sections (scripts/analyze_tiers.py, scripts/report_shadow_profile.py).
SHADOW_SECTIONS = (DROP_SECTION, DEMO_PREFIX + 'innerShadow')
SHADOW_SECTIONS_SQL = sql_list(demo_names('dropShadow', 'innerShadow'))

# The tier scenarios' event windows, async sections from a tap until the screen has settled: `demo:nav <direction>`
# (t6_nav, t7_nav), `demo:reveal` (t0_single, t1_static, t7_reveal), `demo:chip` (t7_interact: a category switch that
# replaces every visible card). Rows: name (current prefix), ts, dur, start on CLOCK_MONOTONIC (simpleperf's clock).
EVENT_WINDOWS = f"""
select {demo_name_sql('name')}, ts, dur, to_monotonic(ts) from slice
where ({' or '.join(f"name glob '{n}*'" for n in demo_names('nav', 'reveal', 'chip'))}) and dur > 0 order by ts
"""

# Main-thread doFrame and RenderThread DrawFrame slices of the app, and its frame-timeline slices (FrameTimingMetric's
# inputs, as in frame_metrics.py), with each start also on CLOCK_MONOTONIC (simpleperf's clock).
FRAME_SLICES = """
select 'S' kind, slice.name, slice.ts, slice.dur, to_monotonic(slice.ts) mono from slice
  join thread_track on slice.track_id = thread_track.id join thread using(utid) join process using(upid)
  where ((slice.name like 'Choreographer#doFrame%' and slice.name not like 'Choreographer#doFrame - resynced to%'
          and process.pid = thread.tid) or (slice.name like 'DrawFrame%' and thread.name = 'RenderThread'))
    and process.name = '{pkg}'
union all
select 'A', a.name, a.ts, a.dur, 0 from actual_frame_timeline_slice a join process using(upid) where process.name = '{pkg}'
union all
select 'E', e.name, e.ts, e.dur, 0 from expected_frame_timeline_slice e join process using(upid) where process.name = '{pkg}'
"""


def frame_timings(trace, pkg):
    """The frames FrameTimingMetric counts (its pairing, see frame_metrics.py), in time order: dicts with ui_ts, ui_dur,
    ui_mono (doFrame start on CLOCK_MONOTONIC), rt_ts, rt_dur, cpu_ns (DrawFrame end - doFrame start) and overrun_ns
    (max(actual end, DrawFrame end) - expected end)."""
    import re
    fid = lambda name: (lambda m: int(m.group(1)) if m else None)(re.search(r'(\d+)$', name))
    ui, rt, act, exp = {}, [], [], {}
    for kind, name, ts, dur, mono in query(FRAME_SLICES.format(pkg=pkg), trace):
        ts, dur = int(ts), int(dur)
        if dur <= 0 or 'resynced' in name:
            continue
        if kind == 'A':
            act.append((ts, dur, fid(name)))
        elif kind == 'E':
            exp[fid(name)] = (ts, dur)
        elif name.startswith('Choreographer#doFrame'):
            ui[fid(name)] = (ts, dur, int(mono))
        else:
            rt.append((ts, dur, fid(name)))
    act.sort()
    out = []
    for rts, rdur, f in sorted(rt):
        u = ui.get(f)
        if u is None:
            continue
        uts, udur, umono = u
        mid = uts + udur // 2
        pick = next((i for i in range(len(act) - 1, -1, -1)
                     if act[i][0] < uts + 50_000 and act[i][0] <= mid <= act[i][0] + act[i][1]), None)
        if pick is None:
            continue
        ats, adur, aid = act.pop(pick)
        e = exp.get(aid)
        if e is None:
            continue
        out.append(dict(ui_ts=uts, ui_dur=udur, ui_mono=umono, rt_ts=rts, rt_dur=rdur, cpu_ns=rts + rdur - uts,
                        overrun_ns=max(ats + adur, rts + rdur) - (e[0] + e[1])))
    out.sort(key=lambda r: r['ui_ts'])
    return out


# Running time of the app's threads matching {thread} (SQL on `thread` t and the process row p) inside the windows
# {windows} (a query of rows ts, te), per CPU: 'r' rows the whole Running time, 'f' rows the part with a known CPU
# frequency and its frequency-weighted sum (ns·kHz).
THREAD_RUNNING = """
with p as (select upid, pid from process where name = '{pkg}'),
th as (select t.utid from thread t join p using(upid) where {thread}),
win as ({windows}),
run as (select max(st.ts, win.ts) a, min(st.ts + st.dur, win.te) b, st.cpu from win
        join thread_state st on st.ts < win.te and st.ts + st.dur > win.ts
        where st.utid in (select utid from th) and st.state = 'Running'),
f as (select cct.cpu, c.ts, lead(c.ts, 1, (select end_ts from trace_bounds)) over (partition by cct.cpu order by c.ts) te,
      c.value v from counter c join cpu_counter_track cct on c.track_id = cct.id where cct.type = 'cpu_frequency')
select 'r', run.cpu, sum(run.b - run.a), 0 from run group by 2
union all
select 'f', run.cpu, sum(min(run.b, f.te) - max(run.a, f.ts)), sum((min(run.b, f.te) - max(run.a, f.ts)) * f.v)
from run join f on f.cpu = run.cpu and f.ts < run.b and f.te > run.a group by 2
"""
MAIN_THREAD = 't.tid = p.pid'
RENDER_THREAD = "t.name = 'RenderThread'"
WHOLE_TRACE = 'select start_ts ts, end_ts te from trace_bounds'
DO_FRAME = """select s.ts, s.ts + s.dur te from slice s join thread_track tt on s.track_id = tt.id join thread t using(utid)
  join process p using(upid) where p.name = '{pkg}' and t.tid = p.pid and s.name like 'Choreographer#doFrame%'
  and s.name not like 'Choreographer#doFrame - resynced to%' and s.dur > 0"""
IN_SHADOW_SECTIONS = """select s.ts, s.ts + s.dur te from slice s join thread_track tt on s.track_id = tt.id
  join thread t using(utid) join process p using(upid) where p.name = '{pkg}' and t.tid = p.pid
  and s.name in """ + SHADOW_SECTIONS_SQL + ' and s.dur > 0'


def add_running(acc, trace, pkg, cluster, thread, windows):
    """Adds the Running time of `thread` inside `windows` (see THREAD_RUNNING) to acc, a dict
    cluster -> [ns, ns with a known frequency, ns·kHz]."""
    sql = THREAD_RUNNING.format(pkg=pkg, thread=thread, windows=windows.format(pkg=pkg))
    for kind, cpu, ns, fns in query(sql, trace):
        c = acc.setdefault(cluster.get(int(cpu), cpu), [0.0, 0.0, 0.0])
        if kind == 'r':
            c[0] += num(ns)
        else:
            c[1] += num(ns)
            c[2] += num(fns)


def running_summary(acc):
    """(Running ns, time-weighted MHz, 'cluster share, ...') of an add_running accumulator."""
    run_ns = sum(v[0] for v in acc.values())
    known = sum(v[1] for v in acc.values())
    mhz = sum(v[2] for v in acc.values()) / known / 1e3 if known else float('nan')
    cores = ', '.join(f'{k} {v[0] / run_ns:.0%}' for k, v in sorted(acc.items(), key=lambda kv: -kv[1][0])) if run_ns else ''
    return run_ns, mhz, cores
