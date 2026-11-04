"""Analyze one production run without rerunning rendering (standard library only)."""
import argparse
import csv
import hashlib
import json
import math
from collections import defaultdict
from pathlib import Path


def distribution(values):
    ordered = sorted(values)
    if not ordered:
        return {"samples": 0}
    result = {"samples": len(ordered)}
    for name, fraction in (("p50Ms", .50), ("p95Ms", .95), ("p99Ms", .99)):
        result[name] = ordered[max(0, math.ceil(len(ordered) * fraction) - 1)]
    result["maxMs"] = ordered[-1]
    result["aboveMs"] = {str(limit): sum(x > limit for x in ordered)
                         for limit in (4, 5.56, 7, 16.7, 33, 50, 100, 1000)}
    return result


def analyze(summary_path, gpu_path):
    summary = json.loads(summary_path.read_text(encoding="utf-8-sig"))
    cpu_path = summary_path.with_suffix(".csv")
    with cpu_path.open(encoding="utf-8-sig", newline="") as stream:
        rows = list(csv.DictReader(stream))
    cpu = {}
    for category, subset in (("all", rows), ("stable", [r for r in rows if int(r["textures"]) == 0]),
                             ("texture", [r for r in rows if int(r["textures"]) > 0 and int(r["meshes"]) == 0]),
                             ("meshAndTexture", [r for r in rows if int(r["meshes"]) > 0])):
        cpu[category] = {
            "transactionWall": distribution((int(r["applyNanos"]) + int(r["callNanos"]) + int(r["releaseNanos"])) / 1e6 for r in subset),
            "synchronousCalls": distribution((int(r["applyNanos"]) + int(r["attemptNanos"]) + int(r["releaseNanos"])) / 1e6 for r in subset),
            "acceptedCall": distribution(int(r["acceptedNanos"]) / 1e6 for r in subset),
        }
    scopes, paired, frames = defaultdict(list), [], defaultdict(dict)
    metadata = []
    if gpu_path is not None:
        with gpu_path.open(encoding="utf-8-sig", newline="") as stream:
            for row in csv.DictReader(stream):
                if not (row.get("frame") or "").isdigit():
                    metadata.append(row)
                    continue
                scopes[row["name"]].append(float(row["inclusive_ms"]))
                frames[(row["frame"], row["queue"])][row["name"]] = row
        for values in frames.values():
            if "NativeLightingDispatch" in values and "NativeDiffuseGiDLSSRR" in values:
                pair = [values["NativeLightingDispatch"], values["NativeDiffuseGiDLSSRR"]]
                paired.append((max(int(r["end_tick"]) for r in pair) - min(int(r["begin_tick"]) for r in pair)) * 1000 / int(pair[0]["frequency"]))
    inputs = [summary_path, cpu_path] + ([gpu_path] if gpu_path else [])
    frame_kinds = defaultdict(list)
    for values in frames.values():
        if "NativeLightingFrameFull" in values:
            kind = "reused" if "NativeLightingReuse" in values else "recomputed"
            frame_kinds[kind].append(float(values["NativeLightingFrameFull"]["inclusive_ms"]))
    return {"run": summary, "cpu": cpu,
            "gpu": {name: distribution(values) for name, values in sorted(scopes.items())},
            "pairedDispatchThroughRr": distribution(paired), "gpuFooter": metadata,
            "fullGpuByEvaluation": {name: distribution(values) for name, values in sorted(frame_kinds.items())},
            "inputSha256": {str(path): hashlib.sha256(path.read_bytes()).hexdigest() for path in inputs},
            "boundary": "CPU wall includes apply, admission attempts, defer sleeps and release; synchronousCalls excludes sleeps. GPU paired interval uses same frame/queue; full frame includes resolve and output only when its named scope exists."}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("summary", type=Path)
    parser.add_argument("--gpu", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    arguments = parser.parse_args()
    result = analyze(arguments.summary, arguments.gpu)
    arguments.output.parent.mkdir(parents=True, exist_ok=True)
    arguments.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"fps": result["run"]["fps"], "gpu": result["gpu"], "cpu": result["cpu"]}, ensure_ascii=False))
