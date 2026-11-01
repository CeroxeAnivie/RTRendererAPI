"""Compare identical light/exposure captures against an independent 32-SPP run.

This measures noise and reference error, not a universal ghost-free certificate.
No resizing, sharpening, brightness matching or exposure normalization is used.
"""
import argparse
import json
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw

parser = argparse.ArgumentParser()
parser.add_argument("reference", type=Path)
parser.add_argument("output", type=Path)
parser.add_argument("candidates", nargs="+", type=Path)
args = parser.parse_args()
rois = {"scene": (780, 180, 1780, 1260), "upperFloor": (1190, 280, 1370, 450)}


def load(directory, phase, frame):
    with Image.open(directory / f"{phase}-{frame}.png") as image:
        if image.size != (2560, 1440):
            raise ValueError("Output dimensions must remain unchanged")
        return np.asarray(image.convert("RGB"), dtype=np.float32)


def crop(image, roi):
    x0, y0, x1, y1 = roi
    return image[y0:y1, x0:x1]


reference = np.mean([load(args.reference, "static", f) for f in range(160, 164)], axis=0)
report = {"reference": str(args.reference.resolve()), "candidates": {}}
canvas = Image.new("RGB", (400 * (len(args.candidates) + 1), 438), "#20242a")
draw = ImageDraw.Draw(canvas)
preview_roi = (1040, 260, 1440, 660)
canvas.paste(Image.fromarray(np.uint8(np.clip(crop(reference, preview_roi), 0, 255))), (0, 36))
draw.text((8, 8), "32-SPP independent reference", fill="white")
for column, directory in enumerate(args.candidates, 1):
    images = np.stack([load(directory, "static", f) for f in range(160, 164)])
    mean = images.mean(axis=0)
    result = {"source": str(directory.resolve()), "rois": {}, "singleFrameReferenceError": {}}
    for name, roi in rois.items():
        sample, target = crop(mean, roi), crop(reference, roi)
        stack = np.stack([crop(image, roi) for image in images])
        delta = sample - target
        result["rois"][name] = {
            "roi": roi,
            "consecutiveRgbMae255": float(np.abs(np.diff(stack, axis=0)).mean()),
            "referenceRgbMae255": float(np.abs(delta).mean()),
            "referenceRgbBias255": delta.mean(axis=(0, 1)).tolist(),
            "meanRgb255": sample.mean(axis=(0, 1)).tolist(),
            "referenceMeanRgb255": target.mean(axis=(0, 1)).tolist(),
            "referenceGradientMae255": float((np.abs(np.diff(sample, axis=0) - np.diff(target, axis=0)).mean()
                + np.abs(np.diff(sample, axis=1) - np.diff(target, axis=1)).mean()) / 2),
        }
    del images
    for phase, frame in (("moving", 119), ("stopped", 0), ("stopped", 3), ("stopped", 15), ("stopped", 79)):
        actual = crop(load(directory, phase, frame), rois["scene"])
        expected = crop(load(args.reference, phase, frame), rois["scene"])
        result["singleFrameReferenceError"][f"{phase}-{frame}"] = float(np.abs(actual - expected).mean())
    report["candidates"][directory.name] = result
    canvas.paste(Image.fromarray(np.uint8(np.clip(crop(mean, preview_roi), 0, 255))), (column * 400, 36))
    draw.text((column * 400 + 8, 8), directory.name, fill="white")
    print(directory.name, json.dumps(result["rois"], ensure_ascii=False))
args.output.parent.mkdir(parents=True, exist_ok=True)
args.output.with_suffix(".json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
canvas.save(args.output.with_suffix(".png"))
