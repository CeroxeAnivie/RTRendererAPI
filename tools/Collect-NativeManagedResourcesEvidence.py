"""Collect completed generic native-managed-resource evidence; never run rendering tests."""
import argparse
import csv
import hashlib
import json
import math
from pathlib import Path
import re
import shutil
import subprocess

ROOT = Path(__file__).resolve().parents[1]
MODULES = ("renderer-api", "renderer-core", "renderer-nvidia", "renderer-rtxpt")
KIND = "compatible-native-managed-resources"
VERSION, BASELINE = "7.3.5", "7.3.3"
PROFILE = ROOT / "tools/data/streaming-scene-load-profile.json"
REPORTS = ROOT / "build/reports/native-managed-resources"
COPY_ROOT = ROOT / "docs/quality/data/native-managed-resources-evidence"
LICENSE_SOURCE = ROOT / "build/rtxpt-sdk/External/Rtxmu/LICENSE.txt"
LICENSE_RUNTIME = ROOT / "build/rtxpt-sdk/bin/ThirdPartyLicenses/RTXMU.txt"
NATIVE = ROOT / "build/rtxpt-sdk/bin/rtrenderer_rtxpt.dll"
PREFIX = "META-INF/native/windows-x86_64/"


def require(condition, message):
    if not condition:
        raise ValueError(message)


def digest(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def relative(path):
    return path.resolve().relative_to(ROOT.resolve()).as_posix()


def read(path):
    require(path.is_file(), "Missing evidence: " + relative(path))
    return json.loads(path.read_text(encoding="utf-8-sig"))


def integer(value):
    return isinstance(value, int) and not isinstance(value, bool)


def finite(value):
    return isinstance(value, (int, float)) and not isinstance(value, bool) and math.isfinite(value) and value >= 0


def hashes(paths):
    result = {relative(path): digest(path) for path in sorted(set(paths)) if path.is_file()}
    require(bool(result), "An evidence hash inventory is empty")
    return result


def distributions(group):
    require(isinstance(group, dict) and bool(group), "Timing distributions are missing")
    for name, value in group.items():
        require(integer(value.get("samples")) and value["samples"] > 0,
                "Timing sample count is invalid: " + name)
        fields = ("meanMs", "p50Ms", "p95Ms", "p99Ms", "maxMs")
        require(all(finite(value.get(field)) for field in fields), "Invalid timing: " + name)
        require(value["p50Ms"] <= value["p95Ms"] <= value["p99Ms"] <= value["maxMs"],
                "Timing quantiles are unordered: " + name)


def class_identity(environment):
    rows = environment.get("testClasses")
    require(isinstance(rows, list) and bool(rows), "Executed test class identity is missing")
    result = {row["name"]: row["sha256"].lower() for row in rows}
    require(len(result) == len(rows) and "RtxptStreamingSceneLoadTest.class" in result,
            "Test class identity inventory is incomplete or duplicated")
    require(all(re.fullmatch(r"[0-9a-f]{64}", value) for value in result.values()), "Invalid test class digest")
    return result


def budget_evidence():
    budget_path = ROOT / "renderer-rtxpt/src/main/cpp/rtxpt_native_budget.h"
    text = budget_path.read_text(encoding="utf-8-sig")
    compact = re.sub(r"\s+", "", text)
    require("enumDomain:unsigned{Resource,Upload}" in compact,
            "The two native budget domains changed")
    require("Limit=2ull*1024*1024*1024;" in compact,
            "The native 2 GiB domain hard limit changed")
    require("bytes>Limit" in compact and "value>Limit-bytes" in compact,
            "Native per-allocation or aggregate admission guard is missing")
    wrapper = ROOT / "tools/Invoke-BoundedBuild.ps1"
    wrapper_text = re.sub(r"\s+", "", wrapper.read_text(encoding="utf-8-sig"))
    require("(memoryLimitGB-0.5)*1e9" in wrapper_text, "The effective Windows Job limit expression changed")
    runner = ROOT / "tools/Invoke-StreamingSceneLoad.ps1"
    require("-MemoryLimitGB 10" in runner.read_text(encoding="utf-8-sig"), "The streaming entry is not bounded")
    return {"nativeDomains": {"Resource": 2 << 30, "Upload": 2 << 30},
            "nativeLimitProof": relative(budget_path), "nativeLimitProofSha256": digest(budget_path),
            "jobLimitBytes": 9_500_000_000, "jobRequestedParameter": 10,
            "jobLimitMeaning": "Effective Job bytes from the executed wrapper expression (10 - 0.5) * 1e9; not the runner field's GiB label",
            "jobWrapper": relative(wrapper), "jobWrapperSha256": digest(wrapper)}


def stream_run(directory, profile, profile_sha):
    summary, environment = read(directory / "summary.json"), read(directory / "environment.json")
    contract = read(directory / "workload.json")
    require(summary.get("schema") == "streaming-scene-load-result-v1"
            and environment.get("schema") == "streaming-scene-load-environment-v1"
            and contract.get("schema") == "streaming-scene-load-contract-v1", "Unknown streaming result schema")
    require(summary.get("status") == "PASS" and summary.get("failure") is None, "Streaming run did not finish successfully")
    require(all(value.get("profileSha256", "").lower() == profile_sha for value in (summary, environment, contract)),
            "Streaming profile identities differ")
    with (directory / "frames.csv").open(encoding="utf-8-sig", newline="") as stream:
        rows = list(csv.DictReader(stream))
    require(bool(rows), "Streaming frame evidence is empty")
    fields = ("step", "sequence", "revision", "owner_apply_to_fresh_nanos", "apply_nanos", "submit_active_nanos",
              "admission_wait_nanos", "acquire_nanos", "release_nanos", "generation_nanos", "deferrals",
              "resident_meshes", "resident_vertices", "resident_triangles", "resident_sources", "mesh_upserts",
              "texture_upserts", "material_upserts", "instance_upserts", "replayed_mesh_instance_removals",
              "observed_aggregate_removals", "unclassified_aggregate_removals", "source_patches", "source_upserts",
              "source_removals", "synthetic_asset_bytes", "native_source_record_bytes", "native_source_index_bytes",
              "diagnostic_poll_nanos")
    for row in rows:
        for field in fields:
            row[field] = int(row[field])
            require(field == "step" or row[field] >= 0, "Negative frame counter: " + field)
    require([r["sequence"] for r in rows] == list(range(len(rows))), "Frame sequence has a gap or duplicate")
    require(summary.get("acceptedFrames") == summary.get("freshIdentityChecks") == len(rows),
            "Every accepted frame needs an exact fresh identity check")
    streaming = [r for r in rows if r["phase"] == "streaming"]
    require(len(profile["curves"]) == len(streaming) == contract.get("curveSteps") == 637,
            "The full 637-step streaming curve was not executed")
    require([r["step"] for r in streaming] == list(range(637)), "Streaming steps are missing or reordered")
    totals = {key: 0 for key in ("mesh_upserts", "texture_upserts", "material_upserts", "instance_upserts",
                               "replayed_mesh_instance_removals", "observed_aggregate_removals", "unclassified_aggregate_removals",
                               "source_patches", "source_upserts", "source_removals")}
    for row, observed in zip(streaming, profile["curves"]):
        update = observed["updates"]
        require(row["resident_meshes"] == observed["resident_meshes"], "Resident mesh curve differs at step " + str(row["step"]))
        for key in ("mesh_upserts", "texture_upserts", "material_upserts", "instance_upserts"):
            require(row[key] == update[key], "Resource upsert curve differs: " + key)
        require(row["observed_aggregate_removals"] == update["removals"]
                and row["replayed_mesh_instance_removals"] + row["unclassified_aggregate_removals"] == update["removals"],
                "Observed removals were conflated with replayed resource operations")
        for key in totals:
            totals[key] += row[key]
    require(totals["source_patches"] == 519 and totals["source_upserts"] == 36661
            and totals["source_removals"] == 33933, "Observed source interval totals differ")
    require(len(profile["source_observations"]) == contract.get("sourceObservations") == 59,
            "All source observation intervals are required")
    for observation in profile["source_observations"]:
        require(streaming[observation["step"]]["resident_sources"] == observation["sources"],
                "Source resident observation differs")
    terminal = {"meshes": 3662, "vertices": 6636080, "triangles": 3318040}
    require(profile["terminal_converged"] == contract.get("terminalTarget") == terminal,
            "Terminal target identity differs")
    converged = [row for row in rows if row["phase"] == "convergence"]
    require(len(converged) == 1 and all(converged[0]["resident_" + key] == value for key, value in terminal.items()),
            "The terminal scale was not actually accepted")
    required_phases = {"cold", "warmup", "stationary", "streaming", "convergence", "terminal-warmup",
                       "terminal-stationary", "deformation", "attributes", "slow-consumer", "consumer-released",
                       "reopen-cold", "reopen-warmup", "reopen-stationary"}
    require(required_phases <= {row["phase"] for row in rows}, "A required streaming lifecycle phase is missing")
    sessions = summary.get("sessions", [])
    require(len(sessions) == 2 and [s.get("session") for s in sessions] == ["primary", "reopen"],
            "A completed close/reopen pair is required")
    expected_assets = {"workerThreads": 4, "shaderCompilerThreads": 1, "geometryBytes": 1024 << 20,
                       "textureBytes": 256 << 20, "totalAssetBytes": 1280 << 20}
    for session in sessions:
        name = session["session"]
        require(finite(session.get("rendererOpenNanos")) and session["rendererOpenNanos"] > 0
                and finite(session.get("rendererCloseNanos")) and session["rendererCloseNanos"] > 0,
                "Renderer open/close did not complete")
        require(session.get("assetBudgets") == expected_assets, "Asset budgets or parallelism differ")
        require(all(session["resident"][key] == value for key, value in terminal.items()), "Session did not retain the terminal scale")
        execution = session["execution"]
        require(execution.get("active") is True and execution.get("gridWidth") == 1280
                and execution.get("gridHeight") == 800 and execution.get("actualSignalMask") == 1
                and execution.get("fullBeautyFrames") == 0
                and execution.get("lightingFrames") == sum(r["session"] == name for r in rows),
                "Live direct-only execution does not match accepted frame evidence")
    require(sessions[0].get("admissionDeferred") is True and sessions[0].get("heldFreshLeases", 0) > 0,
            "Retained-consumer admission and release were not exercised")
    resources_path = directory / "resources.jsonl"
    with resources_path.open(encoding="utf-8-sig") as stream:
        resources = [json.loads(line) for line in stream if line.strip()]
    require(len(resources) == len(rows), "Per-frame resource evidence is incomplete")
    for row, counters in zip(rows, resources):
        require(all(counters[key] == row[key] for key in ("session", "phase", "step", "sequence", "revision")),
                "Resource counters are not joined to their exact fresh frame")
        require(all(key in counters for key in ("upload", "pressure", "maintenance", "dynamic", "sparse")),
                "Resource counter domains are missing")
        require(counters["upload"]["createdBytes"] - counters["upload"]["releasedBytes"] == counters["upload"]["retainedBytes"],
                "Upload lifetime accounting is inconsistent")
    distributions(summary.get("ownerDistributions"))
    distributions(summary.get("gpuDistributions"))
    require(summary.get("gpuScopes", 0) > 0, "GPU timing evidence is absent")
    require(summary.get("frameCap") == "NONE" and summary.get("giSamples") == 0
            and summary.get("reconstruction") == "DISABLED", "Streaming execution policy differs")
    for filename in ("primary-cpu-scopes.csv", "reopen-cpu-scopes.csv", "gpu-scopes.csv"):
        require((directory / filename).is_file(), "Missing diagnostic scopes: " + filename)
    return {"directory": relative(directory), "profileSha256": profile_sha,
            "testClasses": class_identity(environment), "acceptedFrames": len(rows), "streamingSteps": len(streaming),
            "streamingTotals": totals, "terminalTarget": terminal, "sessions": sessions,
            "ownerDistributions": summary["ownerDistributions"], "gpuDistributions": summary["gpuDistributions"],
            "gpuScopes": summary["gpuScopes"], "gpuPendingQueriesAtLastPoll": summary["gpuPendingQueriesAtLastPoll"],
            "gpuDroppedQueries": summary["gpuDroppedQueries"], "gpuMissedScopes": summary["gpuMissedScopes"],
            "absolutePerformanceComparisonAllowed": environment.get("absolutePerformanceComparisonAllowed") is True,
            "environmentPolicy": {key: environment.get(key) for key in ("frameCap", "vsync", "backgroundWindow",
                                  "cpuFrameReadback", "diagnosticLevel", "maximumHeapGiB", "jobMemoryLimitGiB")},
            "rawSummarySha256": digest(directory / "summary.json"), "rawEnvironmentSha256": digest(directory / "environment.json")}


def stream_pair():
    profile = read(PROFILE)
    profile_sha = digest(PROFILE)
    require(profile.get("output_extent") == [2560, 1600] and profile.get("lighting_extent") == [1280, 800],
            "The fixed viewport or receiver extent changed")
    old = stream_run(ROOT / "build/reports/streaming-scene-load-old", profile, profile_sha)
    new = stream_run(ROOT / "build/reports/streaming-scene-load-new", profile, profile_sha)
    environments = [read(ROOT / side["directory"] / "environment.json") for side in (old, new)]
    for environment in environments:
        samples = environment.get("gpuBaselinePercent", [])
        known = environment.get("gpuBaselineKnown") is True
        clean = known and len(samples) == 3 and all(finite(sample) and sample <= 10 for sample in samples)
        require(environment.get("absolutePerformanceComparisonAllowed") is clean,
                "Absolute-performance permission does not match the initial GPU observations")
        require(not known or (len(samples) == 3 and environment.get("gpuInitialPeakPercent") == max(samples)),
                "Initial GPU peak is inconsistent")
    if all(e.get("hardware") for e in environments):
        require(all(environments[0]["hardware"].get(k) == environments[1]["hardware"].get(k)
                    for k in ("uuid", "name", "driver")), "Old and new hardware identities differ")
    local = Path.home() / ".m2/repository/top/ceroxe/rt"
    baseline_artifacts = {}
    for module in MODULES:
        path = local / module / BASELINE / (module + "-" + BASELINE + ".jar")
        require(path.is_file(), "Fixed baseline artifact is missing: " + path.name)
        value = digest(path)
        require(any(row.get("name") == path.name and row.get("sha256", "").lower() == value
                    for row in environments[0].get("classpath", [])), "Baseline execution artifact identity differs: " + path.name)
        baseline_artifacts[path.name] = value
    current_native = digest(NATIVE)
    runtime_matches = any(row.get("name") == NATIVE.name and row.get("sha256", "").lower() == current_native
                          for row in environments[1].get("runtime", []))
    candidate_jar = ROOT / "renderer-rtxpt/build/libs" / ("renderer-rtxpt-" + VERSION + ".jar")
    packaged_matches = candidate_jar.is_file() and any(row.get("name") == candidate_jar.name
                        and row.get("sha256", "").lower() == digest(candidate_jar)
                        for row in environments[1].get("classpath", []))
    require(runtime_matches or packaged_matches, "Candidate streaming JNI is not tied to the tested current artifact")
    require(old["testClasses"] == new["testClasses"], "Old and new runs did not execute identical test class bytes")
    require(old["environmentPolicy"] == new["environmentPolicy"] == {"frameCap": "NONE", "vsync": False,
            "backgroundWindow": True, "cpuFrameReadback": False, "diagnosticLevel": "DEEP", "maximumHeapGiB": 2,
            "jobMemoryLimitGiB": 10}, "Old and new environment policies differ")
    source = ROOT / "renderer-rtxpt/src/test/java/top/ceroxe/rt/renderer/rtxpt/RtxptStreamingSceneLoadTest.java"
    text = re.sub(r"\s+", "", source.read_text(encoding="utf-8-sig"))
    for token in (".maxFramesInFlight(2)", "RendererResourceOptions.medium().withAssetBytes(1024L<<20,256L<<20,1280L<<20)",
                  "NativeLightingStyle.AdditionMode.HOST_RESPONSE_RATIO_V1,0,0,0,0,1,1,1,0,255,1"):
        require(token in text, "The frozen source-declared fixture policy changed: " + token)
    return {"profilePath": relative(PROFILE), "profileSha256": profile_sha, "sourceDeclaredConfigurationSha256": digest(source),
            "baselineArtifactSha256": baseline_artifacts, "candidateNativeSha256": current_native,
            "configurationEvidence": "Same frozen test class bytes plus locked source-declared policy and observed environment/session counters; no claim of unreported runtime fields",
            "baseline": old, "candidate": new,
            "absolutePerformanceComparisonAllowed": old["absolutePerformanceComparisonAllowed"] and new["absolutePerformanceComparisonAllowed"]}


def correctness(receipt_path, native_checks_path):
    receipt, native_checks = read(receipt_path), read(native_checks_path)
    require(receipt.get("nativeSha256", "").lower() == digest(NATIVE), "Correctness JNI differs from the current native binary")
    results, log_files = {}, []
    for document, path in ((receipt, receipt_path), (native_checks, native_checks_path)):
        cases = document.get("cases")
        if isinstance(cases, list):
            entries = [(case["name"], case) for case in cases]
        else:
            require(isinstance(cases, dict), "Correctness cases must be a list or dictionary")
            entries = list(cases.items())
        require(bool(entries), "A correctness receipt contains no completed case")
        for name, case in entries:
            require(case.get("passed") is True and integer(case.get("exit")) and case["exit"] == 0,
                    "Affected correctness case did not pass: " + name)
            stdout = case.get("stdout")
            require(isinstance(stdout, str) and bool(stdout.strip()), "Captured stdout is missing: " + name)
            potential = Path(stdout)
            log = potential if potential.is_absolute() else path.parent / potential
            is_path = "\n" not in stdout and "\r" not in stdout and len(stdout) < 512
            if is_path and not log.is_file() and not potential.is_absolute() and (ROOT / potential).is_file():
                log = ROOT / potential
            if is_path and log.is_file():
                log_files.append(log)
                provenance = {"path": relative(log), "sha256": digest(log)}
            else:
                require(not (is_path and potential.suffix.lower() in (".log", ".txt")), "Missing stdout log: " + name)
                provenance = {"embeddedIn": relative(path), "sha256": hashlib.sha256(stdout.encode("utf-8")).hexdigest(),
                              "meaning": "Captured stdout retained inside the original receipt"}
            value = {"passed": True, "exit": 0, "receipt": relative(path), "stdoutEvidence": provenance}
            if name in results:
                require(results[name]["passed"] == value["passed"] and results[name]["exit"] == value["exit"], "Conflicting case receipts")
                results[name].setdefault("additionalRuns", []).append(value)
            else:
                results[name] = value
    require(len(results) >= 5, "At least five distinct affected correctness cases are required")
    return results, log_files


def equivalence():
    baseline = ROOT / "build/reports/native-managed-resources/baseline-captures"
    candidate = REPORTS / "candidate-captures"
    old, new = sorted(baseline.glob("*.pfm")), sorted(candidate.glob("*.pfm"))
    require(len(old) == len(new) == 4 and {p.name for p in old} == {p.name for p in new}, "Exactly four matched sparse output captures are required")
    result = {}
    for path in old:
        other = candidate / path.name
        require(digest(path) == digest(other), "Sparse output changed: " + path.name)
        result[path.name] = {"baselinePath": relative(path), "candidatePath": relative(other),
                             "baselineSha256": digest(path), "candidateSha256": digest(other), "bytes": path.stat().st_size}
    return result


def license_evidence():
    require(LICENSE_SOURCE.is_file() and LICENSE_RUNTIME.is_file(), "The upstream or staged runtime license is missing")
    require(LICENSE_SOURCE.read_bytes() == LICENSE_RUNTIME.read_bytes(), "Staged license differs from the upstream source")
    return {"runtimeEntry": "ThirdPartyLicenses/RTXMU.txt", "sourcePath": relative(LICENSE_SOURCE),
            "sourceSha256": digest(LICENSE_SOURCE), "stagedPath": relative(LICENSE_RUNTIME), "stagedSha256": digest(LICENSE_RUNTIME)}


def current_inventories():
    production, classes, tests, test_classes = [], [], [], []
    for module in MODULES:
        production += [p for p in (ROOT / module / "src/main").rglob("*") if p.is_file()]
        current = list((ROOT / module / "build/classes/java/main").rglob("*.class"))
        require(bool(current), "Missing current production classes: " + module)
        classes += current
        tests += [p for p in (ROOT / module / "src/test").rglob("*") if p.is_file() and p.suffix in (".java", ".cpp", ".h", ".hpp")]
        test_classes += list((ROOT / module / "build/classes/java/test").rglob("*.class"))
    fixed = ROOT / "build/streaming-scene-load-classes/top/ceroxe/rt/renderer/rtxpt"
    fixed_classes = list(fixed.glob("RtxptStreamingSceneLoadTest*.class"))
    require(bool(fixed_classes), "Fixed executed streaming test class files are missing")
    test_classes += fixed_classes
    staged = []
    extensions = {".c", ".cc", ".cpp", ".cxx", ".h", ".hpp", ".inl", ".hlsl", ".hlsli", ".cmake"}
    for name in ("Rtxpt", "External/Rtxmu", "External/Donut"):
        folder = ROOT / "build/rtxpt-sdk" / name
        selected = [p for p in folder.rglob("*") if p.is_file()
                    and (p.suffix.lower() in extensions or p.name == "CMakeLists.txt")]
        require(bool(selected), "Compiled native stage source tree is missing: " + name)
        staged += selected
    return {"productionSourceSha256": hashes(production), "productionClassSha256": hashes(classes),
            "testSourceSha256": hashes(tests), "testClassSha256": hashes(test_classes),
            "nativeStageSourceSha256": hashes(staged)}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, default=ROOT / "docs/quality/data/native-managed-resources.json")
    parser.add_argument("--branch", help="Target authorized branch; defaults to the current branch")
    args = parser.parse_args()
    version = next(line.split("=", 1)[1] for line in (ROOT / "gradle.properties").read_text(encoding="utf-8").splitlines() if line.startswith("project_version="))
    require(version == VERSION, "This evidence collector is for version " + VERSION)
    receipt, checks = REPORTS / "candidate/receipt.json", REPORTS / "candidate/native-checks.json"
    cases, logs = correctness(receipt, checks)
    streaming, captures, budgets, license_record = stream_pair(), equivalence(), budget_evidence(), license_evidence()
    inventories = current_inventories()
    executed = streaming["candidate"]["testClasses"]
    fixed = ROOT / "build/streaming-scene-load-classes/top/ceroxe/rt/renderer/rtxpt"
    require(executed == {p.name: digest(p) for p in fixed.glob("RtxptStreamingSceneLoadTest*.class")}, "Executed streaming classes no longer match current fixed files")
    roots = (REPORTS / "candidate", REPORTS / "candidate-captures", ROOT / "build/reports/native-managed-resources/baseline-captures",
             ROOT / "build/reports/streaming-scene-load-old", ROOT / "build/reports/streaming-scene-load-new")
    raw_files = sorted(set(logs + [p for folder in roots for p in folder.rglob("*") if p.is_file()]))
    original_hashes = hashes(raw_files)
    branch = args.branch or subprocess.check_output(["git", "branch", "--show-current"], cwd=ROOT, text=True).strip()
    copies, skipped = {}, []
    for path in raw_files:
        if path.suffix.lower() not in (".json", ".jsonl", ".log", ".txt") or path.suffix.lower() == ".jsonl":
            continue
        if path.stat().st_size > 1_048_576:
            skipped.append(relative(path))
            continue
        target = COPY_ROOT / relative(path)
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(path, target)
        copies[relative(target)] = digest(target)
    data = {"schema": "native-managed-resources-release-evidence-v1", "version": VERSION, "baselineVersion": BASELINE,
            "branch": branch, "releaseKind": KIND, "nativeSha256": digest(NATIVE), "correctness": cases,
            "outputEquivalence": captures, "streaming": streaming, "budgets": budgets, "runtimeLicense": license_record,
            **inventories, "originalEvidenceSha256": original_hashes, "evidenceFileSha256": copies,
            "smallEvidenceCopiesSkippedBySize": skipped,
            "toolFileSha256": hashes([Path(__file__), ROOT / "tools/Verify-IncrementalDiagnosticsRelease.py", ROOT / "tools/Invoke-StreamingSceneLoad.ps1", ROOT / "tools/Invoke-BoundedBuild.ps1", PROFILE, LICENSE_SOURCE, LICENSE_RUNTIME]),
            "limits": "Logical synthetic geometry/source-identity replay, not reconstruction of an application scene. Unknown aggregate removals remain unclassified. Fresh-lease CPU timings do not include GPU consumer completion. GPU pending/lost scopes and CPU observer completeness remain explicit; busy or unknown initial GPU forbids absolute performance conclusions. No unavailable result is converted to a passing observation."}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"output": relative(args.output), "version": VERSION, "baselineVersion": BASELINE,
                      "correctnessCases": len(cases), "matchedCaptures": len(captures),
                      "streamingStepsPerRun": 637, "absolutePerformanceComparisonAllowed": streaming["absolutePerformanceComparisonAllowed"]}, indent=2))


if __name__ == "__main__":
    main()
