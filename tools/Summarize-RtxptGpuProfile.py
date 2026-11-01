"""Rank measured upstream scopes. Queue times stay separate: async work overlaps."""
import argparse
import csv
import json
import statistics
from collections import defaultdict
from pathlib import Path


def summarize(path):
    events = {}
    footer = None
    with path.open(encoding="utf-8-sig", newline="") as source:
        lines = []
        for line in source:
            if line.startswith("#"):
                footer = line.strip()
            else:
                lines.append(line)
    for row in csv.DictReader(lines):
        key = (int(row["frame"]), row["queue"], int(row["event"]))
        if key in events:
            raise ValueError(f"Duplicate GPU event: {key}")
        events[key] = dict(row, inclusive=float(row["inclusive_ms"]), children=0.0)
    if not events:
        raise ValueError("No measured scopes: check that the new DLL was loaded.")
    for (frame, queue, _), event in events.items():
        parent = int(event["parent"])
        if parent >= 0:
            events[(frame, queue, parent)]["children"] += event["inclusive"]
    frames = defaultdict(set)
    samples = defaultdict(lambda: defaultdict(float))
    inclusive = defaultdict(lambda: defaultdict(float))
    for (frame, queue, _), event in events.items():
        frames[queue].add(frame)
        exclusive = event["inclusive"] - event["children"]
        if exclusive < -0.00001:
            raise ValueError(f"Overlapping/malformed nesting at {(frame, queue, event['name'])}")
        samples[(queue, event["name"])][frame] += max(exclusive, 0.0)
        inclusive[(queue, event["name"])][frame] += event["inclusive"]
    result = {"source": str(path), "sampling": footer, "queues": {}}
    for queue in sorted(frames):
        rows = []
        for (q, name), values in samples.items():
            if q != queue:
                continue
            times = sorted(values.get(frame, 0.0) for frame in frames[queue])
            rows.append({"name": name, "mean_exclusive_ms": statistics.mean(times),
                         "p50_exclusive_ms": statistics.median(times),
                         "p95_exclusive_ms": times[min(len(times)-1, int(len(times)*.95))],
                         "max_exclusive_ms": max(times),
                         "mean_inclusive_ms": sum(inclusive[(q, name)].values())/len(times)})
        rows.sort(key=lambda row: row["mean_exclusive_ms"], reverse=True)
        total = sum(row["mean_exclusive_ms"] for row in rows)
        for row in rows:
            row["queue_share_percent"] = row["mean_exclusive_ms"]/total*100 if total else 0
        result["queues"][queue] = {"sampled_frames": len(frames[queue]), "sum_exclusive_ms": total, "ranking": rows}
    return result


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("csv", type=Path)
    args = parser.parse_args()
    result = summarize(args.csv)
    output = args.csv.with_suffix(".summary.json")
    output.write_text(json.dumps(result, ensure_ascii=False, indent=2)+"\n", encoding="utf-8")
    for queue, data in result["queues"].items():
        print(f"{queue}: {data['sampled_frames']} sampled frames; mutually exclusive sum {data['sum_exclusive_ms']:.4f} ms")
        for row in data["ranking"][:15]:
            print(f"  {row['mean_exclusive_ms']:.4f} ms  {row['queue_share_percent']:6.2f}%  {row['name']} (inclusive {row['mean_inclusive_ms']:.4f} ms)")
    print(result["sampling"])
