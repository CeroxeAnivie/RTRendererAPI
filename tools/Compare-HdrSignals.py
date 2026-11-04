"""Compare finite linear HDR PFM signals, retaining hashes and numeric errors."""
import argparse
import hashlib
import json
import math
import struct
from pathlib import Path


def read(path):
    raw = path.read_bytes()
    with path.open("rb") as stream:
        kind = stream.readline().strip()
        if kind not in (b"PF", b"Pf"):
            raise ValueError(f"Invalid PFM header: {path}")
        width, height = map(int, stream.readline().split())
        scale = float(stream.readline())
        payload = stream.read()
    count = width * height * (3 if kind == b"PF" else 1)
    if len(payload) != count * 4 or scale == 0:
        raise ValueError(f"Invalid PFM payload: {path}")
    values = struct.unpack(("<" if scale < 0 else ">") + "f" * count, payload)
    if not all(math.isfinite(value) for value in values):
        raise ValueError(f"Non-finite HDR pixel: {path}")
    return (width, height, kind), values, hashlib.sha256(raw).hexdigest()


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("reference", help="PFM filename prefix, before -case.pfm")
    parser.add_argument("actual", help="PFM filename prefix, before -case.pfm")
    parser.add_argument("--cases", nargs="+", required=True)
    parser.add_argument("--tolerance", type=float, default=0)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    results = {}
    for case in args.cases:
        expected_shape, expected, expected_hash = read(Path(f"{args.reference}-{case}.pfm"))
        actual_shape, actual, actual_hash = read(Path(f"{args.actual}-{case}.pfm"))
        if actual_shape != expected_shape:
            raise ValueError(f"HDR dimensions differ: {case}")
        differences = [abs(a - b) for a, b in zip(expected, actual)]
        results[case] = {"samples": len(actual), "maxError": max(differences),
                         "changed": sum(value != 0 for value in differences),
                         "referenceSha256": expected_hash, "actualSha256": actual_hash}
    passed = all(result["maxError"] <= args.tolerance for result in results.values())
    report = {"passed": passed, "tolerance": args.tolerance, "signals": results}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report))
    raise SystemExit(0 if passed else 1)
