"""Compare identical light/exposure captures against an independent 32-SPP run.

This measures noise and reference error, not a universal ghost-free certificate.
No resizing, sharpening, brightness matching or exposure normalization is used.
"""
import argparse
import json
from pathlib import Path

from PIL import Image, ImageChops, ImageDraw, ImageStat

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
        return image.convert("RGB")


def crop(image, roi):
    x0, y0, x1, y1 = roi
    return image.crop(roi)


def rgb_mae(first, second):
    return sum(ImageStat.Stat(ImageChops.difference(first, second)).mean) / 3


reference_frames = [load(args.reference, "static", f) for f in range(160, 164)]
reference = reference_frames[0].copy()
for index, frame in enumerate(reference_frames[1:], 2):
    reference = Image.blend(reference, frame, 1.0 / index)
report = {"reference": str(args.reference.resolve()), "candidates": {}}
canvas = Image.new("RGB", (400 * (len(args.candidates) + 1), 438), "#20242a")
draw = ImageDraw.Draw(canvas)
preview_roi = (1040, 260, 1440, 660)
canvas.paste(crop(reference, preview_roi), (0, 36))
draw.text((8, 8), "32-SPP independent reference", fill="white")
for column, directory in enumerate(args.candidates, 1):
    images = [load(directory, "static", f) for f in range(160, 164)]
    mean = images[0].copy()
    for index, frame in enumerate(images[1:], 2):
        mean = Image.blend(mean, frame, 1.0 / index)
    result = {"source": str(directory.resolve()), "rois": {}, "singleFrameReferenceError": {}}
    for name, roi in rois.items():
        sample, target = crop(mean, roi), crop(reference, roi)
        stack = [crop(image, roi) for image in images]
        delta = ImageChops.difference(sample, target)
        ref_x = ImageChops.difference(crop(reference, (roi[0] + 1, roi[1], roi[2], roi[3])),
                                     crop(reference, (roi[0], roi[1], roi[2] - 1, roi[3])))
        ref_y = ImageChops.difference(crop(reference, (roi[0], roi[1] + 1, roi[2], roi[3])),
                                     crop(reference, (roi[0], roi[1], roi[2], roi[3] - 1)))
        image_x = ImageChops.difference(crop(mean, (roi[0] + 1, roi[1], roi[2], roi[3])),
                                       crop(mean, (roi[0], roi[1], roi[2] - 1, roi[3])))
        image_y = ImageChops.difference(crop(mean, (roi[0], roi[1] + 1, roi[2], roi[3])),
                                       crop(mean, (roi[0], roi[1], roi[2], roi[3] - 1)))
        ref_x, image_x = ImageChops.offset(ref_x, -1, 0), ImageChops.offset(image_x, -1, 0)
        ref_y, image_y = ImageChops.offset(ref_y, 0, -1), ImageChops.offset(image_y, 0, -1)
        grad_error = (rgb_mae(ref_x, image_x) + rgb_mae(ref_y, image_y)) / 2
        result["rois"][name] = {
            "roi": roi,
            "consecutiveRgbMae255": sum(rgb_mae(a, b)
                                         for a, b in zip(stack, stack[1:])) / 3,
            "referenceRgbMae255": sum(ImageStat.Stat(delta).mean) / 3,
            "referenceMeanRgb255": ImageStat.Stat(target).mean,
            "meanRgb255": ImageStat.Stat(sample).mean,
            "referenceEdgeMagnitudeMae255": grad_error,
        }
    del images
    for phase, frame in (("moving", 119), ("stopped", 0), ("stopped", 3), ("stopped", 15), ("stopped", 79)):
        actual = crop(load(directory, phase, frame), rois["scene"])
        expected = crop(load(args.reference, phase, frame), rois["scene"])
        result["singleFrameReferenceError"][f"{phase}-{frame}"] = rgb_mae(actual, expected)
    report["candidates"][directory.name] = result
    canvas.paste(crop(mean, preview_roi), (column * 400, 36))
    draw.text((column * 400 + 8, 8), directory.name, fill="white")
    print(directory.name, json.dumps(result["rois"], ensure_ascii=False))
args.output.parent.mkdir(parents=True, exist_ok=True)
args.output.with_suffix(".json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
canvas.save(args.output.with_suffix(".png"))
