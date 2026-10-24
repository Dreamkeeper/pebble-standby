#!/usr/bin/env python3
"""
Quiet record: how long Standby has watched a wearer and how often it spoke.

Reads the companion's log files (cm-YYYYMMDD.log, pulled over ADB or
shared from Debug -> View logs) and/or diagnostics bundles (.zip, as
uploaded to the server) and prints the figures a responder cares about,
per month and in total:

    calendar days with logs, watch minute records and the worker
    coverage they add up to, nights watched, questions asked on the
    wrist and how they ended, countdowns that reached the phone, alarms,
    and alarms that went out to contacts.

"Questions on the wrist" are the check-ins the wearer saw: the phone
logs each one the watch cancelled (motion, pulse, button, pause) and
each countdown or alarm it received. A question the watch asked and the
wearer answered within the same minute leaves exactly one line.

Usage:
    python tools/quiet_record.py logs/cm-*.log
    python tools/quiet_record.py --json diag-*.zip
    python tools/quiet_record.py --year 2026 --since 2026-09-01 cm-*.log

Duplicate lines (the same day in a log and in a bundle) are counted once.
"""
import argparse
import collections
import datetime as dt
import io
import json
import re
import sys
import zipfile

STAMP = re.compile(r"^(\d\d)-(\d\d) (\d\d):(\d\d):(\d\d)\.\d+ ([VDIWE])/(\w+): (.*)$")
RECORD = "WORKER HEARTBEAT via DataLogging"
CANCELLED = re.compile(r"watch cancelled alert \(reason=(\d+)")
PREALARM = re.compile(r"PRE-ALARM from watch: (\w+)")
ALARM = re.compile(r"^ALARM from watch: (\w+)")
ESCALATED = re.compile(r"POST https?://\S+/api/v1/alarm -> (\d+)")

REASONS = {0: "moved", 1: "pulse returned", 2: "pressed the button", 3: "paused"}


def lines(path):
    if path.lower().endswith(".zip"):
        with zipfile.ZipFile(path) as z:
            for name in sorted(n for n in z.namelist()
                               if n.startswith("logs/cm-") and n.endswith(".log")):
                with z.open(name) as raw:
                    yield from io.TextIOWrapper(raw, encoding="utf-8", errors="replace")
    else:
        with open(path, encoding="utf-8", errors="replace") as fh:
            yield from fh


def collect(paths, year, since=None):
    seen = set()
    months = collections.defaultdict(lambda: collections.Counter())
    days = collections.defaultdict(set)
    night_records = collections.Counter()   # (month, night-date) -> records in 23:00-07:00
    ends = collections.Counter()
    detectors = collections.Counter()
    for path in paths:
        for line in lines(path):
            line = line.rstrip("\n")
            if line in seen:
                continue
            seen.add(line)
            m = STAMP.match(line)
            if not m:
                continue
            t = dt.datetime(year, int(m.group(1)), int(m.group(2)),
                            int(m.group(3)), int(m.group(4)), int(m.group(5)))
            if since and t.date() < since:
                continue
            tag, msg = m.group(7), m.group(8)
            month = t.strftime("%Y-%m")
            days[month].add(t.date())
            c = months[month]
            if tag == "DataLog" and RECORD in msg:
                c["records"] += 1
                if t.hour >= 23 or t.hour < 7:
                    night = t.date() if t.hour >= 23 else t.date() - dt.timedelta(days=1)
                    night_records[(month, night)] += 1
                continue
            if tag == "MonitorService":
                k = CANCELLED.search(msg)
                if k:
                    c["cancelled"] += 1
                    ends[REASONS.get(int(k.group(1)), "other")] += 1
                    continue
                k = PREALARM.search(msg)
                if k:
                    c["countdowns"] += 1
                    detectors[k.group(1)] += 1
                    continue
                k = ALARM.match(msg)
                if k:
                    c["alarms"] += 1
                    detectors[k.group(1)] += 1
                    continue
            if tag == "ServerClient":
                k = ESCALATED.search(msg)
                if k and k.group(1).startswith("2"):
                    c["escalated"] += 1
    for (month, night), n in night_records.items():
        if n >= 240:                      # at least four hours of records
            months[month]["nights"] += 1
    return months, days, ends, detectors


def row(label, c, ndays):
    cov = c["records"] / 1440.0
    questions = c["cancelled"] + c["alarms"]
    return [label, ndays, c["records"], f"{cov:.1f}", c["nights"], questions,
            c["countdowns"], c["alarms"], c["escalated"]]


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("logs", nargs="+")
    ap.add_argument("--year", type=int, default=dt.date.today().year,
                    help="year of the logs (the lines carry none)")
    ap.add_argument("--since", type=lambda s: dt.date.fromisoformat(s),
                    help="ignore lines before this date (YYYY-MM-DD)")
    ap.add_argument("--json", action="store_true")
    a = ap.parse_args()

    months, days, ends, detectors = collect(a.logs, a.year, a.since)
    if not months:
        sys.exit("no companion log lines found")
    total = collections.Counter()
    for c in months.values():
        total.update(c)
    all_days = set().union(*days.values())

    if a.json:
        out = {m: dict(months[m], days=len(days[m])) for m in sorted(months)}
        out["total"] = dict(total, days=len(all_days))
        out["question_endings"] = dict(ends)
        out["countdown_detectors"] = dict(detectors)
        print(json.dumps(out, indent=2))
        return

    head = ["Month", "Days with logs", "Minute records", "Worker days", "Nights watched",
            "Questions on the wrist", "Countdowns on the phone", "Alarms", "Sent to contacts"]
    rows = [row(m, months[m], len(days[m])) for m in sorted(months)]
    rows.append(row("**Total**", total, len(all_days)))
    print("| " + " | ".join(head) + " |")
    print("|" + "---|" * len(head))
    for r in rows:
        print("| " + " | ".join(str(x) for x in r) + " |")
    print()
    if ends:
        print("How the questions ended: " + ", ".join(
            f"{n} {k}" for k, n in sorted(ends.items(), key=lambda kv: -kv[1])) +
              " (watchapps before 0.5.13 reported every ending as moved).")
    if detectors:
        print("Countdowns and alarms by detector: " + ", ".join(
            f"{n} {k}" for k, n in sorted(detectors.items(), key=lambda kv: -kv[1])) + ".")
    print("Worker days = minute records / 1440. A night counts when at least "
          "four hours of records fall between 23:00 and 07:00.")


if __name__ == "__main__":
    main()
