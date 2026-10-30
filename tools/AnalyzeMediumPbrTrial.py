"""Compare frozen PBR trials; keep GPU and exclusive wall clocks separate."""
import argparse
import csv
import hashlib
import json
import sys
from pathlib import Path
from statistics import mean

sys.dont_write_bytecode = True
from AnalyzeMediumWallTimeline import analyze


def window(root, label, variant, mode):
    folder = root / f"{label}-{variant}-{mode}"
    suffix = "profiled" if mode == "timings" else "unprofiled"
    rows = list(csv.DictReader((folder / f"pbr-direct-uniform-1080p-{suffix}.csv").open(encoding="utf-8-sig")))
    frames = sum(int(r["frames"]) for r in rows)
    wall = sum(float(r["wallMs"]) for r in rows)
    result = dict(frames=frames, wallMs=wall, msPerFrame=wall / frames, fps=frames * 1000 / wall,
                  initialGpu=(folder / "initial-gpu.csv").read_text(encoding="utf-8-sig").strip(), rounds=rows)
    if mode == "timings":
        result["host"] = analyze(folder)
        result["gpuRounds"] = [json.loads(r["profileData"]) for r in rows]
        keys = ["pbrLightPacketsGpuMsPerFrame", "pbrMediumKernelGpuMsPerFrame",
                "pbrPrimaryPacketsGpuMsPerFrame", "pbrContributionRestoreGpuMsPerFrame"]
        result["pbrGpuRanked"] = sorted([dict(stage=k, msPerFrame=mean(r[k] for r in result["gpuRounds"]))
                                         for k in keys], key=lambda r: -r["msPerFrame"])
        result["pbrPrepareMs"] = mean(r["pbrMediumPrepareMsPerFrame"] for r in result["gpuRounds"])
    return result


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("trial", type=Path)
    p.add_argument("--label", default="first")
    p.add_argument("--baseline-images", type=Path, required=True)
    p.add_argument("--output", type=Path, required=True)
    args = p.parse_args()
    root = args.trial
    before = {m: window(root, args.label, "control", m) for m in ("timings", "off")}
    after = {m: window(root, args.label, "candidate", m) for m in ("timings", "off")}
    images = list((root / "first-candidate-image").glob("*.rgba16f"))
    assert len(images) == 8
    assert all(x.read_bytes() == (args.baseline_images / x.name).read_bytes() for x in images), "HDR mismatch"
    validation = []
    for path in root.rglob("validation.jsonl"):
        records = [json.loads(line) for line in path.read_text(encoding="utf-8-sig").splitlines()]
        validation.append(dict(file=str(path.relative_to(root)),
                               warnings=sum(r.get("severity") == "WARNING" for r in records),
                               errors=sum(r.get("severity") == "ERROR" for r in records)))
    assert not any(v["warnings"] or v["errors"] for v in validation)
    native = {}
    for suite, name in (("pbr", "result.json"), ("pbr-scattering", "scattering-result.json")):
        native[suite] = json.loads((root / f"native-{suite}" / name).read_text(encoding="utf-8-sig"))
        assert native[suite]["passed"]
    reduction = lambda a, b: 100 * (1 - a / b)
    bg, ag = before["timings"]["gpuRounds"], after["timings"]["gpuRounds"]
    stages = {r["stage"]: dict(before=r["msPerFrame"], after=next(a["msPerFrame"] for a in after["timings"]["pbrGpuRanked"] if a["stage"] == r["stage"]),
                              roundReductionPct=[reduction(a[r["stage"]], b[r["stage"]]) for a, b in zip(ag, bg)])
              for r in before["timings"]["pbrGpuRanked"]}
    for r in stages.values():
        r["reductionPct"] = reduction(r["after"], r["before"])
    result = dict(scene="pbr-direct-uniform", extent=[1920, 1080], spp=1, before=before, after=after,
                  artifacts={v: hashlib.sha256((root / f"{v}-core.jar").read_bytes()).hexdigest() for v in ("control", "candidate")},
                  correctness=dict(byteIdenticalHdrFrames=len(images), validation=validation, native=native),
                  gain=dict(stages=stages, pbrPrepareReductionPct=reduction(after["timings"]["pbrPrepareMs"], before["timings"]["pbrPrepareMs"]),
                            diagnosticsOffFpsGainPct=100 * (after["off"]["fps"] / before["off"]["fps"] - 1),
                            observationOverheadPct=100 * (after["timings"]["msPerFrame"] / after["off"]["msPerFrame"] - 1)))
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print("GPU", json.dumps(stages))
    print("PBR prepare", before["timings"]["pbrPrepareMs"], "->", after["timings"]["pbrPrepareMs"])
    print("Off FPS", before["off"]["fps"], "->", after["off"]["fps"], "gains", result["gain"]["diagnosticsOffFpsGainPct"])
    host = after["timings"]["host"]
    for r in host["rankedHostBoundaries"][:8]:
        print(r["rank"], r["path"], f'{r["selfMsPerFrame"]:.6f} | {r["selfMsPerSecond"]:.3f} | {r["selfPercentWall"]:.3f}%')
    rest = host["rankedHostBoundaries"][8:]
    print("REST", len(rest), sum(r["selfMsPerFrame"] for r in rest), sum(r["selfMsPerSecond"] for r in rest), sum(r["selfPercentWall"] for r in rest))
    print("TOTAL", host["msPerFrame"], host["fps"], "events", host["events"], "dropped", host["droppedEvents"], "overhead", result["gain"]["observationOverheadPct"])


if __name__ == "__main__":
    main()
