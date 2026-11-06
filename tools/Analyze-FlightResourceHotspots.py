"""Match a streaming JFR window to complete retained native slow trees."""
import argparse
import collections
import csv
import json
import math
from pathlib import Path


def records(path):
    with path.open(encoding="utf-8-sig", newline="") as source:
        yield from csv.DictReader(source)


def details(row):
    return dict(part.split("=", 1) for part in row["details"].split(";") if "=" in part)


def summary(values):
    values = sorted(values)
    if not values:
        return {"samples": 0}
    return {"samples": len(values), "mean": sum(values) / len(values),
            **{name: values[min(len(values) - 1, math.ceil(len(values) * p) - 1)]
               for name, p in (("p50", .5), ("p95", .95), ("p99", .99))},
            "maximum": values[-1]}


def analyze(capture):
    windows = [row for row in records(capture / "jfr-evidence.csv")
               if row["type"] == "mcvulkanrt.BenchmarkWindow"
               and details(row).get("streaming") == "true"]
    if len(windows) != 1:
        raise ValueError("Expected exactly one streaming window")
    window = windows[0]
    begin = int(window["epoch_ns"])
    end = begin + int(window["duration_ns"])
    identities = [row for row in records(capture / "jfr-evidence.csv")
                  if row["type"] == "mcvulkanrt.ReuseDecision"
                  and begin <= int(row["epoch_ns"]) <= end]
    sequences = {int(details(row)["frame"]) for row in identities}
    complete, incomplete = set(), set()
    for path in (capture / "diagnostics").glob("rtxpt-native-slow-groups.csv*"):
        for row in records(path):
            key = (row["session"], row["trigger_id"], row["root_scope_id"])
            if row["complete"] == "true" and int(row["dropped_events"]) == 0:
                complete.add(key)
            else:
                incomplete.add(key)
    events = {}
    copies = 0
    for path in (capture / "diagnostics").glob("rtxpt-native-slow-cpu.csv*"):
        for row in records(path):
            if int(row["sequence"]) not in sequences:
                continue
            group = (row["session"], row["trigger_id"], row["trigger_root_scope_id"])
            if group not in complete:
                continue
            copies += 1
            events[(row["session"], row["scope_id"])] = row
    result = {"capture": str(capture), "window": window,
              "identities": len(identities), "sequences": len(sequences),
              "sequenceMin": min(sequences), "sequenceMax": max(sequences),
              "completeGroups": len(complete), "incompleteGroups": len(incomplete),
              "retainedEventCopies": copies, "uniqueRetainedEvents": len(events),
              "caveat": "Retained slow trees are selected observations, not all-frame means. "
                        "Stage sample counts differ. Pre-dedup dirty bytes are not actual GPU bytes.",
              "stages": {}}
    for stage in ("IMPORT", "STAGING_ALLOCATE", "SPARSE_REGIONS", "SPARSE_UPLOAD", "RESOURCE_INDEX"):
        rows = [row for row in events.values() if row["stage"] == stage]
        samples = {"inclusiveMs": summary([int(row["wall_inclusive_ns"]) / 1e6 for row in rows]),
                   "selfTotalMs": sum(int(row["wall_self_ns"]) for row in rows) / 1e6,
                   "slowest": []}
        for row in sorted(rows, key=lambda item: int(item["wall_self_ns"]), reverse=True)[:4]:
            chain, seen, parent = [], set(), row
            while parent and parent["scope_id"] not in seen:
                seen.add(parent["scope_id"])
                chain.append({"stage": parent["stage"], "scope": parent["scope_id"],
                              "inclusiveMs": int(parent["wall_inclusive_ns"]) / 1e6,
                              "selfMs": int(parent["wall_self_ns"]) / 1e6,
                              "bytes": int(parent["bytes"]), "items": int(parent["items"]),
                              "reason": int(parent["reason"])})
                parent = events.get((parent["session"], parent["parent_scope_id"]))
            children = collections.defaultdict(float)
            for child in events.values():
                if child["session"] == row["session"] and child["parent_scope_id"] == row["scope_id"]:
                    children[child["stage"]] += int(child["wall_inclusive_ns"]) / 1e6
            samples["slowest"].append({"sequence": int(row["sequence"]), "revision": int(row["revision"]),
                                        "chain": chain, "directChildrenInclusiveMs": dict(children)})
        result["stages"][stage] = samples
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("capture", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    result = analyze(args.capture)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"identities": result["identities"], "uniqueRetainedEvents": result["uniqueRetainedEvents"],
                      "stages": {key: value["inclusiveMs"] for key, value in result["stages"].items()}}))


if __name__ == "__main__":
    main()
