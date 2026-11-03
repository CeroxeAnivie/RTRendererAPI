"""Summarize the public mixed workload CSVs. Uses only Python's standard library."""
import argparse
import collections
import csv
import json
import math
import statistics
from pathlib import Path


def summarize(path):
    phases = collections.defaultdict(list)
    with Path(path).open(encoding="utf-8-sig", newline="") as stream:
        for row in csv.DictReader(stream):
            phases[row["phase"]].append(row)
    result = {}
    for phase, rows in phases.items():
        values = sorted(float(row["submitMs"]) for row in rows)
        result[phase] = {
            "count": len(values),
            "p50Ms": statistics.median(values),
            "p95Ms": values[math.ceil(len(values) * .95) - 1],
            "p99Ms": values[math.ceil(len(values) * .99) - 1],
            "maximumMs": max(values),
            "over50ms": sum(v > 50 for v in values),
            "over100ms": sum(v > 100 for v in values),
            "over1000ms": sum(v > 1000 for v in values),
            "applyP50Ms": statistics.median(float(row["applyMs"]) for row in rows),
            "raw": rows,
        }
    return result


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("baseline")
    parser.add_argument("candidate")
    parser.add_argument("--output", required=True)
    args = parser.parse_args()
    baseline, candidate = summarize(args.baseline), summarize(args.candidate)
    comparison = {}
    for phase in baseline.keys() & candidate.keys():
        old, new = baseline[phase]["p50Ms"], candidate[phase]["p50Ms"]
        comparison[phase] = {"p50SpeedRatio": old / new, "p50ReductionPercent": (1 - new / old) * 100}
        print(f"{phase:12s} {old:10.3f} -> {new:10.3f} ms, {old/new:.2f}x")
    Path(args.output).write_text(json.dumps({"baseline": baseline, "candidate": candidate, "comparison": comparison}, indent=2) + "\n", encoding="utf-8")
