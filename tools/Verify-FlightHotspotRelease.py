"""Verify an internal API patch release against tested JNI bytes and Maven Local."""
import argparse
import hashlib
import io
import json
from pathlib import Path
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parents[1]
MODULES = ("renderer-api", "renderer-core", "renderer-nvidia", "renderer-rtxpt")


def digest(data):
    return hashlib.sha256(data).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--tested-native", required=True)
    parser.add_argument("--source-commit", required=True)
    parser.add_argument("--baseline-version", required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    version = next(line.split("=", 1)[1] for line in (ROOT / "gradle.properties").read_text().splitlines()
                   if line.startswith("project_version="))
    local = Path.home() / ".m2/repository/top/ceroxe/rt"
    checks, modules = [], {}

    def check(name, valid):
        if not valid:
            raise ValueError(name)
        checks.append(name)

    def git(*arguments):
        return subprocess.check_output(["git", *arguments], cwd=ROOT, text=True).strip()

    check("source-HEAD-is-published-commit", git("rev-parse", "HEAD") == args.source_commit)
    branch = git("branch", "--show-current")
    check("source-current-branch-pushed", git("rev-parse", "origin/" + branch) == args.source_commit)
    for module in MODULES:
        target = local / module / version
        outputs = {}
        for classifier in ("", "-sources", "-javadoc"):
            name = f"{module}-{version}{classifier}.jar"
            built = ROOT / module / "build/libs" / name
            installed = target / name
            check(module + classifier + "-bytes", built.is_file() and installed.is_file()
                  and built.read_bytes() == installed.read_bytes())
            outputs[classifier or "main"] = {"sha256": digest(installed.read_bytes()), "bytes": installed.stat().st_size}
        for source, suffix in (("pom-default.xml", ".pom"), ("module.json", ".module")):
            built = ROOT / module / "build/publications/mavenJava" / source
            installed = target / f"{module}-{version}{suffix}"
            check(module + suffix + "-metadata", built.is_file() and installed.is_file()
                  and built.read_bytes() == installed.read_bytes())
        modules[module] = outputs
    old_api = local / "renderer-api" / args.baseline_version / f"renderer-api-{args.baseline_version}.jar"
    new_api = local / "renderer-api" / version / f"renderer-api-{version}.jar"
    with zipfile.ZipFile(old_api) as old, zipfile.ZipFile(new_api) as new:
        classes = [name for name in old.namelist() if name.endswith(".class")]
        check("public-api-class-inventory-unchanged", classes == [name for name in new.namelist() if name.endswith(".class")])
        check("public-api-all-class-bytes-unchanged", all(old.read(name) == new.read(name) for name in classes))
    new_jar = local / "renderer-rtxpt" / version / f"renderer-rtxpt-{version}.jar"
    old_jar = local / "renderer-rtxpt" / args.baseline_version / f"renderer-rtxpt-{args.baseline_version}.jar"
    runtime_path = "META-INF/native/windows-x86_64/rtxpt-runtime.zip"
    with zipfile.ZipFile(new_jar) as jar, zipfile.ZipFile(old_jar) as old:
        payload = jar.read(runtime_path)
        runtime_hash = digest(payload)
        check("runtime-zip-digest", jar.read(runtime_path.replace(".zip", ".sha256")).decode().strip() == runtime_hash)
        with zipfile.ZipFile(io.BytesIO(payload)) as runtime, zipfile.ZipFile(io.BytesIO(old.read(runtime_path))) as previous:
            native_hash = digest(runtime.read("rtrenderer_rtxpt.dll"))
            check("runtime-jni-tested", native_hash == args.tested_native)
            check("runtime-jni-current", native_hash == digest((ROOT / "build/rtxpt-sdk/bin/rtrenderer_rtxpt.dll").read_bytes()))
            # C++ only changes must retain every shipped shader and dependency.
            locked = [name for name in previous.namelist()
                      if not name.endswith("/") and name != "rtrenderer_rtxpt.dll" and not name.startswith("sdk/")]
            check("runtime-prior-dependencies-and-shaders-retained", all(runtime.read(name) == previous.read(name) for name in locked))
            shaders = [name for name in runtime.namelist() if name.startswith("sdk/backend/") and name.endswith((".hlsl", ".hlsli"))]
            check("runtime-shader-sources-current", all(runtime.read(name) == (ROOT / "renderer-rtxpt/src/main/cpp" / Path(name).name).read_bytes()
                                                        for name in shaders))
    result = {"version": version, "sourceCommit": args.source_commit, "branch": branch,
              "githubPushedBeforeMaven": True, "modules": modules,
              "nativeSha256": native_hash, "runtimeZipSha256": runtime_hash,
              "lockedRuntimeFiles": len(locked), "apiClassesUnchanged": len(classes),
              "checks": checks, "checksPassed": len(checks)}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(result, indent=2))


if __name__ == "__main__":
    main()
