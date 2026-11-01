"""Compare deterministic SR captures by ROI, without calling dimmer output a denoiser.

Usage: python tools/Analyze-HexBallLightProbes.py <output.json> <capture-dir>...
Only static-frame differences are measured; this is not a motion/ghosting verdict.
Lighting-isolation probes remove scene contributions and are diagnostic only.
"""
import json
import statistics
import sys
from pathlib import Path

from PIL import Image, ImageChops, ImageStat

ROIS = {"scene": (780, 180, 1780, 1260), "upperFloor": (1190, 280, 1370, 450)}


def measure(directory):
    frames = [Image.open(directory / f"static-{frame}.png").convert("RGB") for frame in range(160, 164)]
    if any(frame.size != (2560, 1440) for frame in frames):
        raise ValueError("Expected unchanged 2560x1440 output")
    result = {"source": str(directory.resolve()), "runtime": (directory / "runtime.txt").read_text(encoding="utf-8")}
    for name, roi in ROIS.items():
        crops = [frame.crop(roi) for frame in frames]
        average_rgb = [statistics.mean(ImageStat.Stat(crop).mean[channel] for crop in crops) for channel in range(3)]
        mae = statistics.mean(statistics.mean(ImageStat.Stat(ImageChops.difference(a, b)).mean)
                              for a, b in zip(crops, crops[1:]))
        clipping = statistics.mean(sum(1 for pixel in crop.getdata() if max(pixel) >= 250) / (crop.width * crop.height)
                                   for crop in crops)
        result[name] = {"roi": roi, "consecutiveRgbMae255": mae, "meanRgb255": average_rgb,
                        "highChannelFraction": clipping}
    return result


if __name__ == "__main__":
    if len(sys.argv) < 3:
        raise SystemExit(__doc__)
    destination = Path(sys.argv[1])
    result = {Path(directory).name: measure(Path(directory)) for directory in sys.argv[2:]}
    destination.parent.mkdir(parents=True, exist_ok=True)
    destination.write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    for name, values in result.items():
        print(name, "scene MAE", round(values["scene"]["consecutiveRgbMae255"], 6),
              "floor MAE", round(values["upperFloor"]["consecutiveRgbMae255"], 6),
              "high-channel fraction", round(values["scene"]["highChannelFraction"], 6))
