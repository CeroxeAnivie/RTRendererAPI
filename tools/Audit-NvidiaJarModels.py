"""Check actual DLL bytes in both backends of a packaged demo against one official SDK."""
import argparse
import hashlib
import io
import json
import re
import zipfile
from pathlib import Path

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--sdk", type=Path, required=True)
parser.add_argument("--jar", type=Path, required=True)
parser.add_argument("--output", type=Path, required=True)
args = parser.parse_args()
names = ("nvngx_dlss.dll", "nvngx_dlssd.dll", "nvngx_dlssg.dll",
         "sl.dlss.dll", "sl.dlss_d.dll", "sl.dlss_g.dll")
header = (args.sdk / "include/sl_version.h").read_text(encoding="utf-8-sig")
version = ".".join(re.search(r"#define SL_VERSION_" + part + r"\s+(\d+)", header)[1]
                   for part in ("MAJOR", "MINOR", "PATCH"))
expected = {name: hashlib.sha256((args.sdk / "bin/x64" / name).read_bytes()).hexdigest()
            for name in names}
results = []
with zipfile.ZipFile(args.jar) as jar:
    nested = [name for name in jar.namelist() if name.endswith("/rtxpt-runtime.zip")]
    if len(nested) != 1:
        raise RuntimeError(f"Expected exactly one RTXPT runtime archive, got {nested}")
    with zipfile.ZipFile(io.BytesIO(jar.read(nested[0]))) as rtxpt:
        for backend, archive, prefix in (("Vulkan", jar, "META-INF/native/windows-x86_64/"),
                                          ("RTXPT", rtxpt, "")):
            for name in names:
                actual = hashlib.sha256(archive.read(prefix + name)).hexdigest()
                results.append(dict(backend=backend, name=name, sha256=actual,
                                    matchesOfficialSdk=actual == expected[name]))
report = dict(sdkVersion=version, jar=str(args.jar.resolve()),
              jarSha256=hashlib.sha256(args.jar.read_bytes()).hexdigest(), results=results,
              passed=all(row["matchesOfficialSdk"] for row in results))
args.output.parent.mkdir(parents=True, exist_ok=True)
args.output.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
print(json.dumps(report, indent=2))
if not report["passed"]:
    raise SystemExit("Packaged models differ from the selected official SDK")
