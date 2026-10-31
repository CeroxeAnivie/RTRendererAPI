"""Validate completed medium feature-matrix evidence and rank exclusive wall latency."""
import argparse
import csv
import hashlib
import json
import math
import sys
from pathlib import Path

sys.dont_write_bytecode = True
from AnalyzeMediumWallTimeline import analyze


def read_json(path):
    return json.loads(path.read_text(encoding="utf-8-sig"))


def matrix(directory, suffix):
    coverage = read_json(directory / f"all-{suffix}-coverage.json")
    if not coverage.get("complete"):
        raise ValueError(f"Incomplete matrix: {directory}")
    required = set(coverage["requiredFeatures"])
    executed = set(coverage["executedFeatures"])
    if required - executed:
        raise ValueError(f"Missing features: {sorted(required - executed)}")
    cases = {}
    for case in coverage["cases"]:
        name = case["case"]
        if name in cases:
            raise ValueError(f"Duplicate case: {name}")
        path = directory / case["csv"]
        with path.open(encoding="utf-8-sig", newline="") as stream:
            rows = list(csv.DictReader(stream))
        frames = sum(int(row["frames"]) for row in rows)
        wall_ms = sum(float(row["wallMs"]) for row in rows)
        if frames != case["measuredFrames"] or not math.isfinite(wall_ms) or wall_ms <= 0:
            raise ValueError(f"Invalid completed window: {name}")
        item = dict(case=name, frames=frames, msPerFrame=wall_ms / frames,
                    fps=frames * 1000 / wall_ms, features=case["features"], nativeRoutes=case["nativeRoutes"],
                    csvSha256=hashlib.sha256(path.read_bytes()).hexdigest())
        if suffix == "profiled":
            stem = path.name.removesuffix("-profiled.csv")
            host = analyze(directory, [f"{stem}-wall-timeline-round-{row['round']}.json" for row in rows])
            if host["frames"] != frames or host["droppedEvents"]:
                raise ValueError(f"Invalid wall timeline: {name}")
            # CSV wall values are rounded to six decimals; allow only that rounding error.
            if abs(host["msPerFrame"] - item["msPerFrame"]) > 0.000001:
                raise ValueError(f"CSV/timeline window mismatch: {name}")
            stages = {}
            for row in rows:
                for stage in json.loads(row["stageData"])["stages"]:
                    if stage["totalMs"] < 0 or stage["calls"] <= 0 or not math.isfinite(stage["totalMs"]):
                        raise ValueError(f"Invalid GPU stage: {name}: {stage}")
                    total = stages.setdefault(stage["stage"], dict(totalMs=0, calls=0))
                    total["totalMs"] += stage["totalMs"]
                    total["calls"] += stage["calls"]
            if "ZERO_DENSITY" not in case["features"] and not any(
                    key.startswith("cuda.") and value["totalMs"] > 0 for key, value in stages.items()):
                raise ValueError(f"Native medium execution has no nonzero CUDA timing: {name}")
            item["host"] = host
            item["gpuStages"] = sorted([dict(stage=key, msPerFrame=value["totalMs"] / frames, **value)
                                        for key, value in stages.items()], key=lambda row: -row["msPerFrame"])
        cases[name] = item
    return dict(complete=True, caseCount=len(cases), requiredFeatures=sorted(required),
                executedFeatures=sorted(executed), initialGpu=(directory / "initial-gpu.csv").read_text(
                    encoding="utf-8-sig").strip(), cases=cases)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("timings", type=Path)
    parser.add_argument("--off", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    measured = matrix(args.timings, "profiled")
    off = matrix(args.off, "unprofiled") if args.off else None
    if off and (measured["cases"].keys() != off["cases"].keys()
                or measured["requiredFeatures"] != off["requiredFeatures"]):
        raise ValueError("Timings/off matrices have different cases or feature requirements")
    ranked = sorted(measured["cases"].values(), key=lambda row: -row["msPerFrame"])
    for index, row in enumerate(ranked, 1):
        row["rank"] = index
        if off:
            control = off["cases"][row["case"]]
            row["offMsPerFrame"] = control["msPerFrame"]
            row["offFps"] = control["fps"]
            row["observedOverheadPercent"] = 100 * (row["msPerFrame"] / control["msPerFrame"] - 1)
    result = dict(interpretation="Each case is an independent completed-frame window, not additive across cases. "
                  "Host tables partition wall; GPU evidence may be nested. Short-window diagnostics overhead "
                  "is an observation, not a stable acceptance result.", complete=True,
                  caseCount=measured["caseCount"], requiredFeatures=measured["requiredFeatures"],
                  executedFeatures=measured["executedFeatures"], initialGpu=measured["initialGpu"],
                  offInitialGpu=off["initialGpu"] if off else None, rankedCases=ranked)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"Complete: {len(ranked)} cases / {len(measured['requiredFeatures'])} required features")
    for row in ranked:
        print(f"{row['rank']:2d} {row['case']:45s} {row['msPerFrame']:12.6f} ms/frame {row['fps']:9.3f} FPS")
    print("Largest case exclusive host boundaries:")
    for row in ranked[0]["host"]["rankedHostBoundaries"][:10]:
        print(f"{row['selfMsPerFrame']:12.6f} ms/frame {row['selfMsPerSecond']:9.3f} ms/second {row['path']}")


if __name__ == "__main__":
    main()
