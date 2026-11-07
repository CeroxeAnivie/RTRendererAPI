"""Verify compatible renderer release bytes without rerunning rendering tests."""
import argparse
import hashlib
import importlib.util
import io
import json
import math
from pathlib import Path
import struct
import subprocess
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parents[1]
MODULES = ("renderer-api", "renderer-core", "renderer-nvidia", "renderer-rtxpt")
PREFIX = "META-INF/native/windows-x86_64/"
spec = importlib.util.spec_from_file_location("release_bytes", ROOT / "tools/Verify-ResourceLatencyRelease.py")
bytes_tool = importlib.util.module_from_spec(spec)
spec.loader.exec_module(bytes_tool)


def sha(data):
    return hashlib.sha256(data).hexdigest()


def previous_patch_baseline(evidence):
    major, minor, patch = map(int, evidence["version"].split("."))
    return patch > 0 and evidence["baselineVersion"] == ".".join(map(str, (major, minor, patch - 1)))


def check_managed_resource_locality(evidence, check):
    check("managed-baseline-version", previous_patch_baseline(evidence))
    cost = evidence["updateCost"]
    cost_stages = {"tree", "prepare", "regions"}
    check("same-fixture-mixed-update-cost", cost["samples"] == 32 and cost["sources"] == 42191
          and cost["upserts"] == 4096 and cost["removeInsert"] == 128
          and set(cost["baseline"]) == set(cost["candidate"]) == cost_stages)
    locality = evidence["locality"]
    check("bounded-topology-frontier", locality["maximumFrontierVisited"] == 23
          and locality["liveNodes"] == 49999)
    scale = evidence["managedScale"]
    scale_stages = {"owner", "FRAME", "TRANSLATION", "RESOURCES", "SPARSE_REGIONS",
                    "SPARSE_QUERY_PREPARATION"}
    check("same-fixture-managed-scale", scale["samples"] == 16 and scale["warmup"] == 4
          and scale["meshes"] == 3300 and scale["sources"] == 45000
          and scale["meshDelta"] == 128 and scale["sourceDelta"] == 150
          and set(scale["baseline"]) == set(scale["candidate"]) == scale_stages)
    for name, group in (("mixed-update", cost), ("managed-scale", scale)):
        check(name + "-timings-finite-nonnegative", all(
            isinstance(value, (int, float)) and not isinstance(value, bool)
            and math.isfinite(value) and value >= 0
            for side in ("baseline", "candidate") for timing in group[side].values()
            for value in (timing["meanMs"], timing["p95Ms"])))
    required = {"outputDeferral", "retryIdentity", "activePoolExcluded", "incompatibleReplacement",
                "patchGpu", "bootstrap", "sameNamedFile", "crossThreadSnapshots"}
    managed = evidence["managedCorrectness"]
    check("managed-resource-correctness", required <= set(managed)
          and all(managed[name] is True for name in required))
    equivalence = evidence["outputEquivalence"]
    if isinstance(equivalence, dict):
        records = list(equivalence.items())
    elif isinstance(equivalence, list):
        records = [(record.get("name"), record) for record in equivalence if isinstance(record, dict)]
    else:
        records = []
    check("equivalent-output-file-inventory", len(records) == len(equivalence) and len(records) >= 4
          and all(isinstance(name, str) and name for name, _ in records)
          and len({name for name, _ in records}) == len(records))
    check("equivalent-output-sha256", all(isinstance(record, dict)
          and isinstance(record.get("baselineSha256"), str)
          and len(record["baselineSha256"]) == 64
          and all(c in "0123456789abcdefABCDEF" for c in record["baselineSha256"])
          and record["baselineSha256"] == record.get("candidateSha256") for _, record in records))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--evidence", type=Path, default=ROOT / "docs/quality/data/incremental-diagnostic-transport.json")
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--local", action="store_true")
    parser.add_argument("--source-commit")
    args = parser.parse_args()
    evidence = json.loads(args.evidence.read_text(encoding="utf-8"))
    version = next(s.split("=", 1)[1] for s in (ROOT / "gradle.properties").read_text().splitlines()
                   if s.startswith("project_version="))
    local = Path.home() / ".m2/repository/top/ceroxe/rt"
    checks, modules = [], {}

    def check(name, ok):
        if not ok:
            raise ValueError(name)
        checks.append(name)

    def git(*arguments):
        return subprocess.check_output(["git", *arguments], cwd=ROOT, text=True).strip()

    check("evidence-version", evidence["version"] == version)
    retained = evidence.get("releaseKind") == "compatible-retained-update-maintenance"
    managed = evidence.get("releaseKind") == "compatible-managed-resource-locality"
    continuous = evidence.get("releaseKind") == "compatible-continuous-bootstrap"
    batched = evidence.get("releaseKind") == "compatible-receiver-arena-batched-upload"
    check("affected-checks-pass", all((c["passed"] is True if managed or continuous else c["passed"])
          and c["exit"] == 0 for c in evidence["correctness"].values())
          and (len(evidence["correctness"]) == 1 if continuous else len(evidence["correctness"]) >= 12 if managed else
               len(evidence["correctness"]) >= 6 if retained else len(evidence["correctness"]) == 4 if batched else len(evidence["correctness"]) == 3))
    for group in ("productionSourceSha256", "productionClassSha256", "testClassSha256", "evidenceFileSha256"):
        check(group, bool(evidence[group]) and all(sha((ROOT / p).read_bytes()) == h for p, h in evidence[group].items()))
    if batched:
        check("batched-baseline-version", previous_patch_baseline(evidence))
        capacity = evidence["attributeCapacity"]
        check("real-receiver-capacity-and-limit", capacity["liveBytes"] == 3318040 * 3 * 16
              and capacity["newColdBytes"] >= capacity["liveBytes"]
              and capacity["newColdBytes"] - capacity["liveBytes"] < 65536
              and capacity["oldColdBytes"] == 256 * 1024 * 1024
              and capacity["limitBytes"] == 512 * 1024 * 1024 and capacity["oversizeRejected"] is True)
        upload = evidence["mappedUpload"]
        check("actual-mapped-batch-execution", upload["frames"] == upload["mappedBatchRequests"] == 24
              and upload["mappedCopies"] > 0 and 0 < upload["maxMappedCopyBytes"] <= 1024 * 1024
              and upload["maxWriteBytes"] <= 1024 * 1024
              and upload["initializations"] == 1 and upload["sameFrame"] is True
              and upload["steadyLeaseRelease"] is True
              and upload["maxMappedAllocationBytes"] <= max(4 * 1024 * 1024,
                  math.ceil(upload["maxBatchRequestBytes"] / (2 * 1024 * 1024)) * 2 * 1024 * 1024))
        captures = evidence["outputEquivalence"]
        check("four-fresh-output-captures", len(captures) == 4 and all(
            r["baselineSha256"] == r["candidateSha256"] and len(r["baselineSha256"]) == 64 for r in captures.values()))
        check("affected-tests-current-source", bool(evidence["testSourceSha256"]) and all(
            sha((ROOT / p).read_bytes()) == h for p, h in evidence["testSourceSha256"].items()))
        check("allocation-memory-domain-evidence-not-invented", evidence["failureAttribution"]["hresult"] == "0x8007000e"
              and evidence["failureAttribution"]["exclusiveMemoryDomainProven"] is False)
    elif continuous:
        check("continuous-baseline-version", previous_patch_baseline(evidence))
        check("current-bootstrap-test-source", bool(evidence["testSourceSha256"]) and all(
            sha((ROOT / p).read_bytes()) == h for p, h in evidence["testSourceSha256"].items()))
        check("continuous-first-frame-runtime-state", all(value in evidence["observedFirstFrameState"] for value in
            ("input=SCENE_ANALYTIC", "allocatedCacheEntries=0", "restirDi=false", "restirGi=false", "sharc=false", "sceneLighting=true")))
        failure = evidence["baselineRegression"]
        check("first-submit-baseline-failure-preserved", failure["exit"] == 1 and failure["expected"] is True
              and failure["error"] == "Continuous lighting requires SCENE_ANALYTIC without ReSTIR or radiance cache")
        required = {"firstSubmit", "emptyReset", "populatedReset", "repeatedEmptyReset", "managedFile",
                    "ordinaryFileRejected", "memoryReloadEmpty", "backendInputRejected", "restoredAnalytic"}
        check("continuous-bootstrap-and-file-guards", set(evidence["continuousBootstrap"]) == required
              and all(evidence["continuousBootstrap"][name] is True for name in required))
        check("continuous-host-equivalent-policy", evidence["configuration"] == {
            "input":"SCENE_ANALYTIC", "continuity":"CONTINUOUS", "restirDi":False, "restirGi":False,
            "radianceCache":False, "reconstruction":"NONE", "giSamples":0, "signal":"DIRECT_GAIN",
            "encoding":"COMPACT_RESPONSE_UNORM8", "additionMode":"HOST_RESPONSE_RATIO_V1",
            "framesInFlight":2, "diagnostics":"DEEP"})
    elif managed:
        check_managed_resource_locality(evidence, check)
    elif retained:
        cost = evidence["updateCost"]
        check("same-fixture-refit-cost", cost["samples"] == 32 and cost["sources"] == 42191
              and cost["upserts"] == 4096 and cost["removeInsert"] == 0
              and set(cost["baseline"]) == set(cost["candidate"]) == {"tree", "prepare", "regions"})
        check("baseline-mixed-defect-preserved", evidence["baselineMixedRegression"]["exit"] == 1
              and evidence["baselineMixedRegression"]["correctnessFailure"]
              and not evidence["baselineMixedRegression"]["usedForSpeedup"])
    else:
        cost = evidence["readCost"]
        check("frozen-idle-read-cost", cost["samples"] == 32 and cost["frozen"]
              and cost["cpu_poll"]["returned"] == cost["gpu_poll"]["returned"] == 0
              and cost["cpu_poll"]["allocated_bytes"] < cost["cpu_rolling"]["allocated_bytes"] / 8
              and cost["gpu_poll"]["allocated_bytes"] < cost["gpu_rolling"]["allocated_bytes"] / 8)
    native_path = ROOT / "build/rtxpt-sdk/bin/rtrenderer_rtxpt.dll"
    check("tested-jni-current", sha(native_path.read_bytes()) == evidence["nativeSha256"])
    guid, age = bytes_tool.pdb_identity(ROOT / "build/rtxpt-sdk/bin/rtrenderer_rtxpt.pdb")
    check("tested-jni-matching-pdb", b"RSDS" + guid + struct.pack("<I", age) in native_path.read_bytes())
    branch = git("branch", "--show-current")
    check("authorized-branch", branch == evidence["branch"])
    if args.local:
        check("source-commit-required", bool(args.source_commit))
        check("source-HEAD-pushed-before-local", git("rev-parse", "HEAD") == args.source_commit
              and git("rev-parse", "origin/" + branch) == args.source_commit)
    elif args.source_commit:
        check("source-HEAD", git("rev-parse", "HEAD") == args.source_commit)

    ns = {"m": "http://maven.apache.org/POM/4.0.0"}
    for module in MODULES:
        outputs = {}
        for classifier in ("", "-sources", "-javadoc"):
            filename = f"{module}-{version}{classifier}.jar"
            built = ROOT / module / "build/libs" / filename
            payload = built.read_bytes()
            if args.local:
                check(module + classifier + "-local-bytes", payload == (local / module / version / filename).read_bytes())
            with zipfile.ZipFile(built) as jar:
                if classifier == "":
                    root = ROOT / module / "build/classes/java/main"
                    expected = {p.relative_to(root).as_posix(): sha(p.read_bytes()) for p in root.rglob("*.class")}
                    check(module + "-current-class-inventory-and-bytes", bool(expected)
                          and set(expected) == {n for n in jar.namelist() if n.endswith(".class")}
                          and all(sha(jar.read(n)) == h for n, h in expected.items()))
                elif classifier == "-sources":
                    root = ROOT / module / "src/main/java"
                    check(module + "-current-sources", all(jar.read(p.relative_to(root).as_posix()) == p.read_bytes()
                          for p in root.rglob("*.java")))
                else:
                    check(module + "-javadoc", "index.html" in jar.namelist())
            outputs[classifier or "main"] = {"sha256": sha(payload), "bytes": len(payload)}
        publication = ROOT / module / "build/publications/mavenJava"
        for filename, suffix in (("pom-default.xml", ".pom"), ("module.json", ".module")):
            payload = (publication / filename).read_bytes()
            if args.local:
                check(module + suffix + "-local-bytes", payload == (local / module / version / (module + "-" + version + suffix)).read_bytes())
            if suffix == ".pom":
                pom = ET.fromstring(payload)
                check(module + "-pom-version", pom.findtext("m:version", namespaces=ns) == version)
                check(module + "-pom-internal-dependencies", all(
                    d.findtext("m:version", namespaces=ns) == version for d in pom.findall("m:dependencies/m:dependency", ns)
                    if d.findtext("m:groupId", namespaces=ns) == "top.ceroxe.rt"))
            else:
                metadata = json.loads(payload)
                check(module + "-module-version", metadata["component"]["version"] == version)
        modules[module] = outputs

    for module in (("renderer-api", "renderer-core", "renderer-rtxpt") if continuous else ("renderer-api", "renderer-core")):
        with zipfile.ZipFile(local / module / evidence["baselineVersion"] / f"{module}-{evidence['baselineVersion']}.jar") as old, \
                zipfile.ZipFile(ROOT / module / f"build/libs/{module}-{version}.jar") as new:
            old_classes = {n: sha(old.read(n)) for n in old.namelist() if n.endswith(".class")}
            check(module + "-baseline-class-bytes-retained", old_classes == {n: sha(new.read(n)) for n in new.namelist() if n.endswith(".class")})

    with zipfile.ZipFile(ROOT / "renderer-nvidia" / f"build/libs/renderer-nvidia-{version}.jar") as jar, \
            zipfile.ZipFile(local / "renderer-nvidia" / evidence["baselineVersion"] / f"renderer-nvidia-{evidence['baselineVersion']}.jar") as old:
        nvidia_files = sorted(n for n in jar.namelist() if n.startswith(PREFIX) and not n.endswith("/"))
        check("nvidia-native-current", jar.read(PREFIX + "rtrenderer_nvidia.dll") == (ROOT / "renderer-nvidia/build/native/rtrenderer_nvidia.dll").read_bytes())
        manifest = jar.read(PREFIX + "runtime-files.sha256").decode()
        check("nvidia-runtime-file-manifest", all(sha(jar.read(PREFIX + line.split(maxsplit=1)[1])) == line.split(maxsplit=1)[0] for line in manifest.splitlines() if line))
        check("nvidia-runtime-inventory-retained", nvidia_files == sorted(n for n in old.namelist() if n.startswith(PREFIX) and not n.endswith("/")))
        nvidia_changed = [n for n in nvidia_files if jar.read(n) != old.read(n)]
        nvidia_hash = sha(jar.read(PREFIX + "rtrenderer_nvidia.dll"))

    runtime_entry = PREFIX + "rtxpt-runtime.zip"
    with zipfile.ZipFile(ROOT / "renderer-rtxpt" / f"build/libs/renderer-rtxpt-{version}.jar") as jar, \
            zipfile.ZipFile(local / "renderer-rtxpt" / evidence["baselineVersion"] / f"renderer-rtxpt-{evidence['baselineVersion']}.jar") as old_jar:
        payload = jar.read(runtime_entry)
        runtime_hash = sha(payload)
        check("runtime-zip-digest", jar.read(runtime_entry.replace(".zip", ".sha256")).decode().strip() == runtime_hash)
        with zipfile.ZipFile(io.BytesIO(payload)) as runtime, zipfile.ZipFile(io.BytesIO(old_jar.read(runtime_entry))) as previous:
            check("runtime-tested-jni", sha(runtime.read("rtrenderer_rtxpt.dll")) == evidence["nativeSha256"])
            def executable_dependency(n):
                return not n.endswith("/") and n != "rtrenderer_rtxpt.dll" and (n.lower().endswith(".dll")
                    or n.startswith(("ShaderPrecompiled/", "ShaderDynamic/", "D3D12/", "Assets/", "ThirdPartyLicenses/"))
                    or ("/" not in n and n.startswith("sl.") and n.endswith(".json")))
            locked = sorted(n for n in previous.namelist() if executable_dependency(n))
            check("runtime-dependency-inventory-retained", locked == sorted(n for n in runtime.namelist() if executable_dependency(n)))
            check("runtime-dependency-and-shader-bytes-retained", all(runtime.read(n) == previous.read(n) for n in locked))
            sdk = json.loads(runtime.read("native-lighting-sdk.json"))
            check("sdk-version", sdk["version"] == version)
            check("sdk-files", all(len(runtime.read(e["path"])) == e["bytes"] and sha(runtime.read(e["path"])) == e["sha256"] for e in sdk["files"]))
            inventory = "".join(sorted(f"{e['abiPath']}={e['sha256']}\n" for e in sdk["files"] if e["abiPath"]))
            check("sdk-shader-abi", sha(inventory.encode()) == sdk["shaderAbiSha256"])
            shaders = [n for n in runtime.namelist() if n.startswith("sdk/backend/") and n.endswith((".hlsl", ".hlsli"))]
            check("sdk-current-shader-sources", bool(shaders) and all(runtime.read(n) == (ROOT / "renderer-rtxpt/src/main/cpp" / Path(n).name).read_bytes() for n in shaders))

    result = {"version": version, "baselineVersion": evidence["baselineVersion"], "branch": branch,
              "sourceCommit": args.source_commit, "phase": "maven-local" if args.local else "package-only",
              "githubPushedBeforeMaven": args.local, "modules": modules, "nativeSha256": evidence["nativeSha256"],
              "runtimeZipSha256": runtime_hash, "pdbGuidBytes": guid.hex(), "pdbAge": age,
              "nvidiaNativeSha256": nvidia_hash, "nvidiaRuntimeFiles": len(nvidia_files),
              "nvidiaChangedFromBaseline": nvidia_changed, "lockedRuntimeFiles": len(locked),
              "sdkManifestFiles": len(sdk["files"]), "shaderAbiSha256": sdk["shaderAbiSha256"],
              "checksPassed": len(checks), "checks": checks}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(result, indent=2))


if __name__ == "__main__":
    main()
