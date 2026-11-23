"""Microbenchmark tables from the per-case run directories written by scripts/run_bench.sh.

usage: python3 scripts/analyze_micro.py [--cases] <dir> [density]   one row per case
       python3 scripts/analyze_micro.py --compare <dir>             Compose's case vs Obvid's (`cpu_<case>`)
<dir> is a suite's run directory, e.g. results/<device>/traces-<run>/micro (DropShadowRebuildBenchmark) or
.../inner (InnerShadowRebuildBenchmark, recognized by that directory name).

--cases, per case: time (min and median of Microbenchmark's runs), allocations per draw, the shadow bitmap in
pixels (density default 2.625, i.e. 420 dpi; a drop shadow's side is size + 2·(radius + spread), an inner shadow's
the size, plus 2·ceil(radius) for a path; for a `size` case, the walk's largest), and from the case's
own Perfetto trace the time-weighted clock of the BenchmarkRunner thread over its Running time, with the share of that
time per core. Counters are time-weighted, never averaged per sample.

--compare: per case, Compose's median vs Obvid's and their ratio, allocations, each run's clock and start skin.

A run directory named `<case>.<suffix>` is a repeat (the second order of a pair) and gets its own column or row.
"""
import glob, json, math, os, re, sys

from perfetto_util import cluster_for, query

# Runner thread running, split by frequency steps of its core. Microbenchmark's own "Benchmark Time"
# slice sits on a different clock (monotonic) from the sched data (boottime) in these traces, so the
# window is the runner's whole Running time: warmup, measurement and allocation passes run the same loop.
SQL = """
with run as (select st.ts a, st.ts + st.dur b, st.cpu from thread_state st
        join thread t using(utid) where t.name = 'BenchmarkRunner' and st.state = 'Running'),
f as (select cct.cpu, c.ts, lead(c.ts, 1, (select end_ts from trace_bounds)) over (partition by cct.cpu order by c.ts) te,
      c.value v from counter c join cpu_counter_track cct on c.track_id = cct.id where cct.type = 'cpu_frequency')
select run.cpu, sum(min(run.b, f.te) - max(run.a, f.ts)) ns, sum((min(run.b, f.te) - max(run.a, f.ts)) * f.v) fns
from run join f on f.cpu = run.cpu and f.ts < run.b and f.te > run.a group by 1
"""


def clock_and_cores(trace, cluster):
    """Time-weighted MHz of the BenchmarkRunner thread while running, and its share of run time per cluster."""
    rows = [(int(c), float(ns), float(fns)) for c, ns, fns in query(SQL, trace)]
    total = sum(ns for _, ns, _ in rows)
    mhz = sum(fns for _, _, fns in rows) / total / 1e3 if total else float('nan')
    share = {}
    for cpu, ns, _ in rows:
        share[cluster.get(cpu, cpu)] = share.get(cluster.get(cpu, cpu), 0) + ns / total
    cores = ', '.join(f'{k} {v:.0%}' for k, v in sorted(share.items(), key=lambda kv: -kv[1]))
    return mhz, cores


def read_skin(d):
    p = os.path.join(d, 'skin.txt')
    return float(open(p).read().strip()) if os.path.exists(p) else float('nan')


PATH_SHAPES = {'mixed', 'morph'}


def bitmap_side(root, shape, size, radius, spread, density):
    """Compose's shadow bitmap side in px for a case of the suite in `root`."""
    if os.path.basename(os.path.normpath(root)) == 'inner':
        side = math.ceil(size * density)
        return side + 2 * math.ceil(radius * density) if shape in PATH_SHAPES else side
    return math.ceil((size + 2 * (radius + spread)) * density)


def cases(root, density):
    print('| case | bitmap px | min ms | median ms | allocs | clock MHz | cores (share of run time) | skin °C |')
    print('|---|---|---|---|---|---|---|---|')
    for d in sorted(glob.glob(os.path.join(root, '*'))):
        name = os.path.basename(d)
        data = glob.glob(os.path.join(d, '*-benchmarkData.json'))
        trace = glob.glob(os.path.join(d, '*.perfetto-trace'))
        if not data or not trace:
            print(f'| {name} | missing output |')
            continue
        b = json.load(open(data[0]))['benchmarks'][0]['metrics']
        m = re.match(r'(\w+)_(\w+)_(\d+)dp_r(\d+(?:p\d+)?)_s(\d+)', name)  # r0p5: radius 0.5 dp
        size, spread = int(m.group(3)), int(m.group(5))
        radius = float(m.group(4).replace('p', '.'))
        side = bitmap_side(root, m.group(2), size, radius, spread, density)
        mhz, cores = clock_and_cores(trace[0], cluster_for(root))
        t = b['timeNs']
        print(f"| {name} | {side}² | {t['minimum'] / 1e6:.3f} | {t['median'] / 1e6:.3f} | "
              f"{b['allocationCount']['median']:.0f} | {mhz:.0f} | {cores} | {read_skin(d):.1f} |")


GEOMETRY_ORDER = ['48dp_r24_s0', '100dp_r24_s0', '200dp_r24_s0', '360dp_r24_s0', '200dp_r4_s0', '200dp_r8_s0',
                  '200dp_r64_s0', '200dp_r24_s8']


def compare(root):
    """Compose's case vs Obvid's (`cpu_<case>`), medians and ratio, each run's clock and start skin."""
    runs = {}
    for d in sorted(glob.glob(os.path.join(root, '*'))):
        name = os.path.basename(d)
        data = glob.glob(os.path.join(d, '*-benchmarkData.json'))
        trace = glob.glob(os.path.join(d, '*.perfetto-trace'))
        if not data or not trace:
            runs[name] = None
            continue
        b = json.load(open(data[0]))['benchmarks'][0]['metrics']
        mhz, cores = clock_and_cores(trace[0], cluster_for(root))
        runs[name] = dict(min=b['timeNs']['minimum'] / 1e6, med=b['timeNs']['median'] / 1e6,
                          allocs=b['allocationCount']['median'], mhz=mhz, cores=cores, skin=read_skin(d))
    print('Median (min) ms per draw; allocations per draw; runner clock MHz (time-weighted), start skin °C. '
          'Ratio = Compose median ÷ Obvid.')
    print()
    print('| case | Compose ms | Obvid ms | Compose ÷ Obvid | allocs C / Obvid | clock MHz C / Obvid | skin °C C / Obvid |')
    print('|---|---|---|---|---|---|---|')
    order = {'rebuild': 0, 'sprite': 1, 'cached': 2, 'offset': 3, 'size': 4, 'color': 5}
    shapes = {'round': 0, 'mixed': 1, 'morph': 2, 'circle': 3, 'rect': 4}
    def sort_key(n):
        base, _, suffix = n.partition('.')
        mode, shape, geometry = base.split('_', 2)
        return (order.get(mode, 9), shapes.get(shape, 9),
                GEOMETRY_ORDER.index(geometry) if geometry in GEOMETRY_ORDER else 9, suffix)
    # A `.b` directory is the second order of a pair (Obvid's ran first); it is its own row.
    keys = sorted((n for n in runs if not n.startswith('cpu_')), key=sort_key)
    for n in keys:
        c, f = runs[n], runs.get('cpu_' + n)
        if not c or not f:
            print(f'| {n} | missing |')
            continue
        print(f"| {n} | {c['med']:.3f} ({c['min']:.3f}) | {f['med']:.3f} ({f['min']:.3f}) | {c['med'] / f['med']:.2f}× | "
              f"{c['allocs']:.0f} / {f['allocs']:.0f} | {c['mhz']:.0f} / {f['mhz']:.0f} | {c['skin']:.1f} / {f['skin']:.1f} |")
    print()
    print('Cores (share of the runner\'s Running time) per run:')
    print()
    for n in sorted(runs):
        if runs[n]:
            print(f"- {n}: {runs[n]['cores']}")


if __name__ == '__main__':
    args = sys.argv[1:]
    mode = args.pop(0) if args and args[0].startswith('--') else '--cases'
    if mode not in ('--cases', '--compare') or not args:
        sys.exit(__doc__.split('\n\n')[1])
    if mode == '--compare':
        compare(args[0])
    else:
        cases(args[0], float(args[1]) if len(args) > 1 else 2.625)
