"""Compare 1-SPP and 32-SPP RTXPT light-family captures without resizing."""

import argparse
import json
from pathlib import Path

from PIL import Image, ImageChops, ImageDraw, ImageStat


parser = argparse.ArgumentParser()
parser.add_argument("--full-reference", type=Path, required=True)
parser.add_argument("--full-sample", type=Path, required=True)
parser.add_argument("--direct-reference", type=Path, required=True)
parser.add_argument("--direct-sample", type=Path, required=True)
parser.add_argument("--output", type=Path, required=True)
parser.add_argument("--pose", type=int, default=0)
args = parser.parse_args()

frame_ids = range(128, 132)
groups = {
    "fullReference": args.full_reference,
    "fullSample": args.full_sample,
    "directReference": args.direct_reference,
    "directSample": args.direct_sample,
}


def read_stack(directory):
    frames = []
    for frame_id in frame_ids:
        path = directory / f"mixed-pose{args.pose}-static{frame_id}.png"
        with Image.open(path) as image:
            frames.append(image.convert("RGB"))
    shape = frames[0].size
    if any(frame.size != shape for frame in frames):
        raise ValueError(f"Inconsistent dimensions in {directory}")
    return frames


stacks = {name: read_stack(directory) for name, directory in groups.items()}
if len({tuple(frame.size for frame in stack) for stack in stacks.values()}) != 1:
    raise ValueError("All captures must use identical output dimensions")

width, height = stacks["fullReference"][0].size
regions = {
    "whole": (0, 0, width, height),
    "emitterAndObjects": (round(width * 0.32), round(height * 0.22),
                          round(width * 0.70), round(height * 0.76)),
    "floor": (0, round(height * 0.52), width, height),
}


def region(frames, bounds):
    x0, y0, x1, y1 = bounds
    return [frame.crop(bounds) for frame in frames]


def mean_image(frames):
    result = frames[0].copy()
    for index, frame in enumerate(frames[1:], 2):
        result = Image.blend(result, frame, 1.0 / index)
    return result


def mae(first, second):
    return sum(ImageStat.Stat(ImageChops.difference(first, second)).mean) / 3


def mean_rgb(image):
    return [float(value) for value in ImageStat.Stat(image).mean]


def consecutive_mae(frames):
    return sum(mae(first, second) for first, second in zip(frames, frames[1:])) / (len(frames) - 1)


def edge_metrics(sample, reference):
    def gradients(image):
        width, height = image.size
        gray = image.convert("L")
        x = ImageChops.difference(gray.crop((1, 0, width, height)), gray.crop((0, 0, width - 1, height)))
        y = ImageChops.difference(gray.crop((0, 1, width, height)), gray.crop((0, 0, width, height - 1)))
        return x, y

    sample_x, sample_y = gradients(sample)
    reference_x, reference_y = gradients(reference)
    return {
        "sampleMeanGradient255": (ImageStat.Stat(sample_x).mean[0] + ImageStat.Stat(sample_y).mean[0]) / 2,
        "referenceMeanGradient255": (ImageStat.Stat(reference_x).mean[0] + ImageStat.Stat(reference_y).mean[0]) / 2,
        "gradientDifferenceMae255": (mae(sample_x, reference_x) + mae(sample_y, reference_y)) / 2,
    }


report = {
    "pose": args.pose,
    "frameIds": list(frame_ids),
    "width": width,
    "height": height,
    "regions": {},
    "groups": {name: str(directory.resolve()) for name, directory in groups.items()},
}

for name, bounds in regions.items():
    full_reference = region(stacks["fullReference"], bounds)
    full_sample = region(stacks["fullSample"], bounds)
    direct_reference = region(stacks["directReference"], bounds)
    direct_sample = region(stacks["directSample"], bounds)
    full_mean = mean_image(full_reference)
    direct_mean = mean_image(direct_reference)
    sample_mean = mean_image(full_sample)
    direct_sample_mean = mean_image(direct_sample)
    report["regions"][name] = {
        "bounds": list(bounds),
        "fullSample": {
            "consecutiveRgbMae255": consecutive_mae(full_sample),
            "referenceRgbMae255": mae(sample_mean, full_mean),
            "edgeMetrics": edge_metrics(sample_mean, full_mean),
            "singleFrameReferenceMae255": [mae(frame, full_mean) for frame in full_sample],
            "meanRgb255": mean_rgb(sample_mean),
            "referenceMeanRgb255": mean_rgb(full_mean),
        },
        "directSample": {
            "consecutiveRgbMae255": consecutive_mae(direct_sample),
            "referenceRgbMae255": mae(direct_sample_mean, direct_mean),
            "edgeMetrics": edge_metrics(direct_sample_mean, direct_mean),
            "singleFrameReferenceMae255": [mae(frame, direct_mean) for frame in direct_sample],
            "meanRgb255": mean_rgb(direct_sample_mean),
            "referenceMeanRgb255": mean_rgb(direct_mean),
        },
        "referenceFullVsDirectMae255": mae(full_mean, direct_mean),
    }

args.output.parent.mkdir(parents=True, exist_ok=True)
args.output.with_suffix(".json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")

panel_width = 640
panel_height = round(height * panel_width / width)
labels = ("32-SPP full", "1-SPP full", "32-SPP direct", "1-SPP direct")
averages = (
    mean_image(stacks["fullReference"]),
    mean_image(stacks["fullSample"]),
    mean_image(stacks["directReference"]),
    mean_image(stacks["directSample"]),
)
canvas = Image.new("RGB", (panel_width * 4, panel_height + 28), "#20242a")
draw = ImageDraw.Draw(canvas)
for index, (label, average) in enumerate(zip(labels, averages)):
    image = average.resize((panel_width, panel_height), Image.Resampling.LANCZOS)
    canvas.paste(image, (index * panel_width, 28))
    draw.text((index * panel_width + 8, 7), label, fill="white")
canvas.save(args.output.with_suffix(".png"))
print(json.dumps(report["regions"], indent=2))
