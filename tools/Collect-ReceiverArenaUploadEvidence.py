"""Collect only receiver-capacity and sparse-batch evidence for the current patch."""
from pathlib import Path
import hashlib
import json
import math
import re
import shutil
import subprocess

ROOT = Path(__file__).resolve().parents[1]
CAPTURE = ROOT / "build/reports/sparse-batch-upload"
OUT = ROOT / "docs/quality/data/receiver-arena-upload"


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def distribution(values):
    values = sorted(values)
    return {"samples": len(values), "meanMs": sum(values) / len(values) / 1e6,
            "p95Ms": values[math.ceil(len(values) * .95) - 1] / 1e6,
            "maximumMs": values[-1] / 1e6}


def values(line):
    return dict(part.split("=", 1) for part in line.split() if "=" in part)


def main():
    version = next(line.split("=", 1)[1] for line in (ROOT / "gradle.properties").read_text().splitlines()
                   if line.startswith("project_version="))
    major, minor, patch = map(int, version.split("."))
    baseline_version = f"{major}.{minor}.{patch - 1}"
    OUT.mkdir(parents=True, exist_ok=True)
    evidence_files = {}
    for side in ("baseline", "candidate", "failed-assertion"):
        folder = CAPTURE / side
        for path in sorted(folder.glob("*")):
            if not path.is_file() or path.suffix not in (".json", ".log"):
                continue
            target = OUT / side / path.name
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(path, target)
            evidence_files[target.relative_to(ROOT).as_posix()] = sha(target)
    candidate = json.loads((CAPTURE / "candidate/receipt.json").read_text(encoding="utf-8-sig"))
    baseline = json.loads((CAPTURE / "baseline/receipt.json").read_text(encoding="utf-8-sig"))
    assert all(case["passed"] and case["exit"] == 0 for case in candidate["cases"])
    native = ROOT / "build/rtxpt-sdk/bin/rtrenderer_rtxpt.dll"
    assert candidate["nativeSha256"] == sha(native)
    comparisons, fixtures, mapped = {}, {}, None
    for side in ("baseline", "candidate"):
        text = (CAPTURE / side / "RtxptSparseUploadNativeSelfTest.log").read_text(encoding="utf-8-sig")
        rows = [values(line) for line in text.splitlines() if line.startswith("SPARSE_UPLOAD_FRAME ")]
        assert len(rows) == 24 and [int(r["step"]) for r in rows] == list(range(24))
        final = values(next(line for line in text.splitlines() if line.startswith("SPARSE_UPLOAD PASS ")))
        fixtures[side] = {name: distribution([int(row[field]) for row in rows]) for name, field in
                          (("request", "requestNanos"), ("allocationSelf", "allocationSelfNanos"),
                           ("sparseRegionSelf", "sparseRegionSelfNanos"))}
        fixtures[side]["allocationCount"] = sum(int(row["allocationCount"]) for row in rows)
        fixtures[side]["afterFirstRequest"] = distribution([int(row["requestNanos"]) for row in rows[1:]])
        fixtures[side]["summary"] = final
        if side == "candidate":
            mapped = {k: int(final[k]) for k in ("frames", "mappedBatchRequests", "mappedCopies", "maxMappedCopyBytes",
                      "maxWriteBytes", "initializations", "maxBatchRequestBytes", "maxMappedAllocationBytes")}
            mapped["sameFrame"] = final["sameFrame"] == "true"
            mapped["steadyLeaseRelease"] = final["steadyLeaseRelease"] == "true"
    for path in sorted((CAPTURE / "baseline-captures").glob("*.pfm")):
        other = CAPTURE / "candidate-captures" / path.name
        comparisons[path.name] = {"baselineSha256": sha(path), "candidateSha256": sha(other), "bytes": path.stat().st_size}
        assert comparisons[path.name]["baselineSha256"] == comparisons[path.name]["candidateSha256"]
    assert len(comparisons) == 4
    live = 3318040 * 3 * 16
    source_paths = subprocess.check_output(["git", "diff", "--name-only"], cwd=ROOT, text=True).splitlines()
    source_paths += ["renderer-rtxpt/src/main/cpp/rtxpt_attribute_capacity.h", "renderer-rtxpt/src/main/cpp/rtxpt_allocation_failure.h"]
    production = {p: sha(ROOT / p) for p in sorted(set(source_paths)) if "/src/main/" in p and (ROOT / p).is_file()}
    test = "renderer-rtxpt/src/test/java/top/ceroxe/rt/renderer/rtxpt/RtxptSparseUploadNativeSelfTest.java"
    test_classes = ROOT / "renderer-rtxpt/build/classes/java/test/top/ceroxe/rt/renderer/rtxpt"
    data = {"version": version, "baselineVersion": baseline_version, "branch": "codex/resource-root-followup",
            "releaseKind": "compatible-receiver-arena-batched-upload", "nativeSha256": sha(native),
            "correctness": {"attributeCapacityCpu": {"passed": True, "exit": 0, "source": "rtxpt_resource_import_selftest.cpp", "observation": "Bounded executable result captured in task; 50000 allocator operations, large cold/growth/overflow checks passed"},
                "sparseRegionCpu": {"passed": True, "exit": 0, "sources": 25000, "probes": 2198, "observation": "Bounded executable result captured in task; collision/backshift/overflow/travel oracle passed"},
                **{case["name"]: case for case in candidate["cases"]}},
            "attributeCapacity": {"liveBytes": live, "oldColdBytes": 256 << 20, "newColdBytes": (live + 65535) // 65536 * 65536,
                "limitBytes": 512 << 20, "oversizeRejected": True, "growthSlackBytes": 16 << 20,
                "growthFixture": {"requiredIncrementBytes": 310123, "maximumRequiredBytes": 300 << 20, "growths": 26, "migrationBytes": 3017081088,
                    "limit": "Lower retained capacity can require more migrations. Not a measured MC transfer total or speedup."}},
            "mappedUpload": mapped, "outputEquivalence": comparisons, "fixtureTimings": fixtures,
            "baselineNativeSha256": baseline["nativeSha256"],
            "failureAttribution": {"hresult": "0x8007000e", "buffer": "Native receiver attributes arena", "exclusiveMemoryDomainProven": False,
                "hostTreeLimitBytes": 9500000000, "hostReportedPeakBytes": 9712000000, "newQueries": ["process", "systemCommit", "job", "DXGI_local", "DXGI_nonLocal"],
                "newFailureSnapshotRuntimeExercised": False},
            "productionSourceSha256": production,
            "productionClassSha256": {p.relative_to(ROOT).as_posix(): sha(p) for p in sorted((ROOT / "renderer-rtxpt/build/classes/java/main").rglob("*.class"))},
            "testSourceSha256": {test: sha(ROOT / test)},
            "testClassSha256": {p.relative_to(ROOT).as_posix(): sha(p) for p in sorted(test_classes.glob("RtxptSparseUploadNativeSelfTest*.class"))},
            "evidenceFileSha256": evidence_files,
            "limits": "Only affected native correctness and same-fixture 24-update observations. Not MC 709-block performance, not 180 FPS. CPU terminal observations retain their actual completed result; no raw CPU log was redirected at execution time. Failed first candidate's obsolete transient-page assertion is retained. Large receiver cold allocation/reopen must be exercised by the host."}
    target = ROOT / "docs/quality/data/receiver-arena-and-batched-upload.json"
    target.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"version": version, "nativeSha256": sha(native), "capturesEqual": len(comparisons), "mappedUpload": mapped,
                      "fixtureTimings": fixtures}, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
