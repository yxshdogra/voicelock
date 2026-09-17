#!/usr/bin/env python3
"""Score a spike-log.jsonl against the Milestone 0 bar (README.md):
>=90% true-positive, <1 false-positive per hour, <5% battery per hour.

Usage: score.py <spike-log.jsonl> [--window 6.0]
"""
import argparse
import bisect
import json
import sys

TP_BAR = 0.90
FP_PER_HOUR_BAR = 1.0
BATTERY_PER_HOUR_BAR = 5.0


def score(rows, window_s):
    """Compute the M0 metrics from a list of parsed JSONL row dicts.

    Returns a dict of counts and metrics; the three *_pass fields (and
    overall_pass) are None when there isn't enough data to judge them.
    """
    window_ms = window_s * 1000

    attempts = sorted((r for r in rows if r.get("kind") == "attempt"), key=lambda r: r["t"])
    detects = sorted((r for r in rows if r.get("kind") == "detect"), key=lambda r: r["t"])
    fp_marked = sum(1 for r in rows if r.get("kind") == "false_positive")

    attempt_times = [a["t"] for a in attempts]
    hit_attempts = set()
    fp_count = 0
    for d in detects:
        t = d["t"]
        idx = bisect.bisect_left(attempt_times, t) - 1
        if idx >= 0 and 0 < t - attempt_times[idx] <= window_ms:
            hit_attempts.add(idx)
        else:
            fp_count += 1

    hits = len(hit_attempts)
    tp_rate = (hits / len(attempts)) if attempts else None

    # Uptime: sum of service start/stop sessions, falling back to the
    # overall time span of the log when there are no service rows at all.
    service_rows = sorted((r for r in rows if r.get("kind") == "service"), key=lambda r: r["t"])
    if service_rows:
        uptime_ms = 0
        pending_start = None
        for r in service_rows:
            event = r.get("event")
            if event == "start":
                if pending_start is None:
                    pending_start = r["t"]
            elif event == "stop":
                if pending_start is not None:
                    uptime_ms += r["t"] - pending_start
                    pending_start = None
        if pending_start is not None:
            last_t = rows[-1]["t"] if rows else pending_start
            uptime_ms += last_t - pending_start
    else:
        ts = [r["t"] for r in rows if "t" in r]
        uptime_ms = (max(ts) - min(ts)) if ts else 0

    uptime_hours = uptime_ms / 3.6e6
    fp_per_hour = (fp_count / uptime_hours) if uptime_hours > 0 else None

    # Battery: sum drops between consecutive unplugged samples.
    battery_rows = sorted((r for r in rows if r.get("kind") == "battery"), key=lambda r: r["t"])
    drop = 0
    unplugged_ms = 0
    for prev, cur in zip(battery_rows, battery_rows[1:]):
        if prev.get("charging") is False and cur.get("charging") is False and cur["pct"] < prev["pct"]:
            drop += prev["pct"] - cur["pct"]
            unplugged_ms += cur["t"] - prev["t"]
    unplugged_hours = unplugged_ms / 3.6e6
    battery_per_hour = (drop / unplugged_hours) if unplugged_hours > 0 else None

    pass_tp = None if tp_rate is None else tp_rate >= TP_BAR
    pass_fp = None if fp_per_hour is None else fp_per_hour < FP_PER_HOUR_BAR
    pass_battery = None if battery_per_hour is None else battery_per_hour < BATTERY_PER_HOUR_BAR
    if None in (pass_tp, pass_fp, pass_battery):
        overall_pass = None
    else:
        overall_pass = pass_tp and pass_fp and pass_battery

    return {
        "attempts": len(attempts),
        "detects": len(detects),
        "hits": hits,
        "fp_count": fp_count,
        "fp_marked": fp_marked,
        "tp_rate": tp_rate,
        "uptime_hours": uptime_hours,
        "fp_per_hour": fp_per_hour,
        "unplugged_hours": unplugged_hours,
        "battery_per_hour": battery_per_hour,
        "pass_tp": pass_tp,
        "pass_fp": pass_fp,
        "pass_battery": pass_battery,
        "overall_pass": overall_pass,
    }


def _fmt(value, suffix="", pct=False):
    if value is None:
        return "n/a (insufficient data)"
    if pct:
        return f"{value * 100:.1f}{suffix}"
    return f"{value:.2f}{suffix}"


def _verdict(passed):
    if passed is None:
        return "N/A"
    return "PASS" if passed else "FAIL"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("log", help="path to spike-log.jsonl")
    parser.add_argument("--window", type=float, default=6.0, help="attempt->detect pairing window in seconds (default 6.0)")
    args = parser.parse_args()

    rows = []
    malformed = 0
    with open(args.log, "r") as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            try:
                rows.append(json.loads(line))
            except json.JSONDecodeError:
                malformed += 1

    result = score(rows, args.window)

    print("VoiceLock M0 scoring report")
    print("----------------------------")
    print(f"rows parsed:        {len(rows)}")
    print(f"malformed lines:    {malformed}")
    print(f"attempts:           {result['attempts']}")
    print(f"detects:            {result['detects']}")
    print(f"hits (true pos.):   {result['hits']}")
    print(f"computed FPs:       {result['fp_count']}")
    print(f"user-marked FPs:    {result['fp_marked']}")
    print(f"uptime hours:       {result['uptime_hours']:.2f}")
    print(f"unplugged hours:    {result['unplugged_hours']:.2f}")
    print()
    print(f"true-positive rate: {_fmt(result['tp_rate'], '%', pct=True)} (bar: >={TP_BAR * 100:.0f}%) -> {_verdict(result['pass_tp'])}")
    print(f"false positives/hr: {_fmt(result['fp_per_hour'], '/hr')} (bar: <{FP_PER_HOUR_BAR:.1f}/hr) -> {_verdict(result['pass_fp'])}")
    print(f"battery %/hr:       {_fmt(result['battery_per_hour'], '%/hr')} (bar: <{BATTERY_PER_HOUR_BAR:.1f}%/hr) -> {_verdict(result['pass_battery'])}")
    print()
    print(f"OVERALL: {_verdict(result['overall_pass'])}")

    if result["overall_pass"] is None:
        return 2
    return 0 if result["overall_pass"] else 1


if __name__ == "__main__":
    sys.exit(main())
