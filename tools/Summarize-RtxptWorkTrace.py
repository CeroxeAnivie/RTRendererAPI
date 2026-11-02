"""Summarize sparse optical work and FG delivery without running the renderer."""
import argparse
import collections
import csv
import json
import re
import statistics
from pathlib import Path


def summarize(log):
    source = log.read_text(encoding="utf-8-sig", errors="replace")
    records = []
    for match in re.finditer(r"\[unformatted args\] 0: (210|211|220|221|230) ([^\r\n]*)", source):
        fields = dict(re.findall(r"(\d+): (\([^)]*\)|[^\s]+)", match[2]))
        key = tuple(int(n) for n in fields["1"].strip("()").split(","))
        records.append((int(match[1]), key, fields))
    traversals = [row for row in records if row[0] == 210]
    identities = collections.Counter((row[1], *(row[2].get(str(i)) for i in range(3, 7)))
                                      for row in traversals)
    initializations = {row[1] for row in records if row[0] == 230}
    restores = {(row[1][0], row[1][1], row[1][2], int(row[2]["2"].strip("()").split(",")[0]))
                for row in records if row[0] == 221}
    saves = [row for row in records if row[0] == 220]
    timeline = []
    incomplete = 0
    for line in re.findall(r"RTXPT_FG_TIMELINE[^\r\n]*", source):
        values = dict(re.findall(r"(\w+)=([-+.0-9]+)", line))
        if not {"intervalNative", "intervalActual", "foreground", "status"} <= values.keys():
            incomplete += 1
            continue
        row = {k: float(v) if "." in v else int(v) for k, v in values.items()}
        row["deliveryMultiplier"] = row["intervalActual"] / row["intervalNative"]
        timeline.append(row)
    result = {
        "source": str(log),
        "scope": "Sparse pixels in frames 320 and 321; counts are not whole-frame costs.",
        "workCounts": dict(collections.Counter(row[0] for row in records)),
        "traversals": len(traversals), "uniqueTraversalIdentities": len(identities),
        "repeatedTraversalIdentities": sum(n - 1 for n in identities.values()),
        "firstHitInitializations": len(initializations),
        "initializationsThenOverwrittenByRestore": len(initializations & restores),
        "savesBeforeFirstObservedGlassFlag": sum(
            (int(row[2]["2"].strip("()").split(",")[0]) & 4) == 0 for row in saves),
        "timeline": timeline, "incompleteTimelineRecords": incomplete,
    }
    presentmon = log.with_name(log.stem + "-presentmon.csv")
    if presentmon.exists():
        with presentmon.open(encoding="utf-8-sig", newline="") as stream:
            rows = [r for r in csv.DictReader(stream) if r.get("ProcessID") and r.get("TimeInMs")]
        result["presentmon"] = {"rows": len(rows), "frameTypes": dict(collections.Counter(
            r["FrameType"] for r in rows)), "presentModes": dict(collections.Counter(
            r["PresentMode"] for r in rows))}
        for field in ("MsBetweenDisplayChange", "MsBetweenPresents", "MsGPUTime"):
            values = [float(r[field]) for r in rows if r.get(field) not in (None, "NA", "")]
            result["presentmon"][field] = {"samples": len(values), "mean": statistics.mean(values)}
    return result


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("log", type=Path)
    args = parser.parse_args()
    result = summarize(args.log)
    output = args.log.with_suffix(".work-summary.json")
    output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({k: v for k, v in result.items() if k != "timeline"}, ensure_ascii=False, indent=2))
    print(f"Timeline: {len(result['timeline'])} complete records; {output}")
