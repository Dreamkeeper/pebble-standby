#!/usr/bin/env python3
"""Reconstruct the watch worker's per-minute timeline from companion logs.

The companion logs one line per DataLogging heartbeat record
("WORKER HEARTBEAT via DataLogging: ... flush-latency=Ns ...") when it
receives it, which is minutes after the watch wrote it. This tool
shifts every record back to its true time (log time - flush latency),
decodes the flags byte, and prints what matters for a false-nag or
missed-alarm post-mortem: nag/fault events, hunts, gated or absent
readings, flat-pulse stretches, and a +/-15 min window around each nag.

Usage:
    python tools/worker_log_timeline.py cm-20260908.log cm-20260909.log
    python tools/worker_log_timeline.py --around "09-09 02:05" cm-20260909.log
    python tools/worker_log_timeline.py --window 30 --all cm-*.log

Get the logs with Debug -> View logs -> Share or Send to my server (a
diagnostics .zip, which this tool reads directly), or via ADB:
    adb pull /sdcard/Android/data/org.cryomonitor.companion/files/logs/
"""
import argparse
import datetime as dt
import io
import re
import sys
import zipfile

REC = re.compile(
    r"^(\d\d-\d\d \d\d:\d\d:\d\d)\.\d+ I/DataLog: WORKER HEARTBEAT via DataLogging: "
    r"stage=(\d+) batt=(\d+)% bpm=(\d+) susp=(\d+) flush-latency=(-?\d+)s "
    r"changeAge=(\d+)s motionAge=(\d+)s flags=0x([0-9a-f]+) heap=(\d+)B")
EVENT = re.compile(r"^(\d\d-\d\d \d\d:\d\d:\d\d)\.\d+ [WIE]/(MonitorService|WatchLink|PebbleKit2Listener): "
                   r"(watch reports not worn|watch reports sensor fault.*|pk2: watchapp (?:opened|closed).*|"
                   r".*PRE_ALARM.*|.*ALARM.*|.*escalat.*)$", re.I)

FLAG_BITS = [(0x01, "CHG"), (0x02, "LAB"), (0x04, "HUNT"), (0x08, "NAGGED"),
             (0x10, "everpulse"), (0x20, "SUSP"), (0x40, "GATED")]


def flags_text(f):
    return " ".join(n for b, n in FLAG_BITS if f & b and n != "everpulse") or "-"


def _lines(path):
    """Lines of a plain log file, or of every logs/cm-*.log inside a
    diagnostics bundle (.zip), oldest file first."""
    if path.lower().endswith(".zip"):
        with zipfile.ZipFile(path) as z:
            for name in sorted(n for n in z.namelist()
                               if n.startswith("logs/cm-") and n.endswith(".log")):
                with z.open(name) as raw:
                    yield from io.TextIOWrapper(raw, encoding="utf-8", errors="replace")
    else:
        with open(path, encoding="utf-8", errors="replace") as fh:
            yield from fh


def parse(paths, year):
    recs, events = [], []
    for path in paths:
        for line in _lines(path):
            m = REC.match(line)
            if m:
                logged = dt.datetime.strptime(f"{year}-{m.group(1)}", "%Y-%m-%d %H:%M:%S")
                t = logged - dt.timedelta(seconds=int(m.group(6)))
                recs.append(dict(t=t, stage=int(m.group(2)), batt=int(m.group(3)),
                                 bpm=int(m.group(4)), susp=int(m.group(5)),
                                 change=int(m.group(7)), motion=int(m.group(8)),
                                 flags=int(m.group(9), 16), heap=int(m.group(10))))
                continue
            e = EVENT.match(line)
            if e:
                events.append((dt.datetime.strptime(f"{year}-{e.group(1)}", "%Y-%m-%d %H:%M:%S"),
                               e.group(3).strip()))
    recs.sort(key=lambda r: r["t"])
    events.sort()
    return recs, events


def fmt(r, mark=""):
    return (f"{r['t']:%m-%d %H:%M:%S}  st={r['stage']} bpm={r['bpm']:3} chg={r['change']:5}s "
            f"mot={r['motion']:5}s batt={r['batt']:3}% heap={r['heap']:4}  {flags_text(r['flags']):14} {mark}")


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("logs", nargs="+")
    ap.add_argument("--year", type=int, default=dt.date.today().year)
    ap.add_argument("--around", help='"MM-DD HH:MM" to centre the window on (default: each nag)')
    ap.add_argument("--window", type=int, default=15, help="minutes each side (default 15)")
    ap.add_argument("--all", action="store_true", help="print every record")
    a = ap.parse_args()

    recs, events = parse(a.logs, a.year)
    if not recs:
        sys.exit("no worker heartbeat records found")
    print(f"records: {len(recs)}  span: {recs[0]['t']} -> {recs[-1]['t']}")

    nags = [(t, e) for t, e in events if "not worn" in e.lower() or "sensor fault" in e.lower()]
    print(f"\nEVENTS ({len(events)}):")
    for t, e in events:
        print(f"  {t:%m-%d %H:%M:%S}  {e}")

    print("\nSTRETCHES: bpm absent/gated (bpm=0) while records continue")
    run = []
    for r in recs + [None]:
        if r is not None and r["bpm"] == 0:
            run.append(r)
        elif run:
            print(f"  {run[0]['t']:%m-%d %H:%M} .. {run[-1]['t']:%H:%M}  {len(run)} min  "
                  f"motion={'still' if all(x['motion'] >= 60 for x in run) else 'moving'}  "
                  f"gated={'yes' if any(x['flags'] & 0x40 for x in run) else 'no/unknown'}")
            run = []

    print("\nFLAT PULSE >= 150 s while still >= 150 s (nag-eligible minutes):")
    for r in recs:
        if r["change"] >= 150 and r["motion"] >= 150 and r["bpm"] > 0:
            print("  " + fmt(r))

    print("\nHUNTS (burst sampling active at record time):")
    for r in recs:
        if r["flags"] & 0x04:
            print("  " + fmt(r))

    centres = []
    if a.around:
        centres = [dt.datetime.strptime(f"{a.year}-{a.around}", "%Y-%m-%d %H:%M")]
    else:
        centres = [t for t, _ in nags]
    for c in centres:
        print(f"\nWINDOW +/-{a.window} min around {c:%m-%d %H:%M}:")
        for r in recs:
            d = (r["t"] - c).total_seconds()
            if abs(d) <= a.window * 60:
                print("  " + fmt(r, "<-- event" if abs(d) < 60 else ""))

    if a.all:
        print("\nALL RECORDS:")
        for r in recs:
            print("  " + fmt(r))


if __name__ == "__main__":
    main()
