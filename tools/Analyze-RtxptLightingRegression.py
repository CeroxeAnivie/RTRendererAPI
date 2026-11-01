"""Measure whole-image light-family evidence; no sharpen, resize or noise threshold gaming."""
import argparse
import json
from pathlib import Path

import numpy as np
from PIL import Image

parser = argparse.ArgumentParser()
parser.add_argument("directory", type=Path)
parser.add_argument("output", type=Path)
parser.add_argument("--reference", type=Path)
args = parser.parse_args()


def frames(directory, name, pose):
    paths = sorted(directory.glob(f"{name}-pose{pose}-static*.png"))
    if len(paths) != 4:
        raise RuntimeError(f"Expected four real captures for {name}, pose {pose}: {paths}")
    return np.stack([np.asarray(Image.open(path).convert("RGB"), dtype=np.float32) for path in paths])


result = {}
for path in sorted(args.directory.glob("*-pose0-runtime.txt")):
    name = path.name.removesuffix("-pose0-runtime.txt")
    a, b = frames(args.directory, name, 0), frames(args.directory, name, 1)
    row = {
        "translation_mae_255": float(np.abs(a.mean(0) - b.mean(0)).mean()),
        "static_interframe_mae_255": float(np.abs(np.diff(a, axis=0)).mean()),
        "mean_rgb_255": float(a.mean()),
        "spatial_std_255": float(a.mean(0).std()),
    }
    if args.reference:
        reference = frames(args.reference, name, 0)
        row["reference_mae_255"] = float(np.abs(a.mean(0) - reference.mean(0)).mean())
        row["reference_mean_rgb_255"] = float(reference.mean())
    result[name] = row
if not result:
    raise RuntimeError("No lighting captures")
args.output.write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
print(json.dumps(result, indent=2))
