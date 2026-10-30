"""Compare changing-seed linear HDR captures without denoising or temporal accumulation."""
import argparse
import json
from pathlib import Path

import numpy as np


def measure(folder, width, height, frames, region):
    x0, y0, x1, y1 = region
    total = square = display_total = display_square = None
    previous = None
    difference = 0.0
    frame_means = []
    for index in range(frames):
        path = folder / f"pbr-direct-uniform-{index}.rgba16f"
        raw = np.fromfile(path, dtype="<f2")
        if raw.size != width * height * 4:
            raise ValueError(f"unexpected tightly packed HDR extent: {path}")
        rgb = raw.reshape(height, width, 4)[y0:y1, x0:x1, :3].astype(np.float64)
        if not np.isfinite(rgb).all() or (rgb < 0).any():
            raise ValueError(f"invalid HDR channels: {path}")
        luminance = rgb @ np.array([0.2126, 0.7152, 0.0722])
        mapped = np.clip(rgb * (2.51 * rgb + 0.03) / (rgb * (2.43 * rgb + 0.59) + 0.14), 0, 1) ** (1 / 2.2)
        if total is None:
            total = np.zeros_like(luminance)
            square = np.zeros_like(luminance)
            display_total = np.zeros_like(mapped)
            display_square = np.zeros_like(mapped)
        total += luminance
        square += luminance * luminance
        display_total += mapped
        display_square += mapped * mapped
        frame_means.append(float(luminance.mean()))
        if previous is not None:
            difference += float(np.abs(mapped - previous).mean())
        previous = mapped
    variance = np.maximum((square - total * total / frames) / (frames - 1), 0)
    display_variance = np.maximum((display_square - display_total * display_total / frames) / (frames - 1), 0)
    return {
        "meanLuminance": float(total.mean() / frames),
        "hdrTemporalVariance": float(variance.mean()),
        "sdrTemporalVariance": float(display_variance.mean()),
        "meanAbsoluteConsecutiveSdrDifference": difference / (frames - 1),
        "frameMeanLuminance": frame_means,
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("control", type=Path)
    parser.add_argument("candidate", type=Path)
    parser.add_argument("--width", type=int, required=True)
    parser.add_argument("--height", type=int, required=True)
    parser.add_argument("--frames", type=int, required=True)
    parser.add_argument("--scattering-steps", type=int, required=True,
                        help="Actual RenderFrameRequest scatteringSteps used by both captures")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if min(args.width, args.height) < 1 or args.frames < 2:
        parser.error("positive extent and at least two frames required")
    if not 1 <= args.scattering_steps <= 64:
        parser.error("scattering steps must be in [1, 64]")
    # Regions selected from the reported screenshot, fixed before comparing the candidate.
    regions = {"whole": (0, 0, 1, 1), "left_wall": (0, 0, 58 / 320, 110 / 180),
               "blue_beam": (205 / 320, 20 / 180, 260 / 320, 70 / 180),
               "foreground": (70 / 320, 140 / 180, 160 / 320, 175 / 180)}
    result = {"extent": [args.width, args.height], "framesPerVariant": args.frames,
              "spp": 1, "scatteringSteps": args.scattering_steps, "regions": {}}
    for name, uv in regions.items():
        rect = [int(uv[i] * (args.width if i % 2 == 0 else args.height)) for i in range(4)]
        before = measure(args.control, args.width, args.height, args.frames, rect)
        after = measure(args.candidate, args.width, args.height, args.frames, rect)
        paired = np.array(after["frameMeanLuminance"]) - before["frameMeanLuminance"]
        metrics = {"rectangle": rect, "control": before, "candidate": after,
                   "pairedMeanDifference": float(paired.mean()),
                   "pairedMeanDifferenceStandardError": float(paired.std(ddof=1) / np.sqrt(args.frames))}
        for key in ("meanLuminance", "hdrTemporalVariance", "sdrTemporalVariance",
                    "meanAbsoluteConsecutiveSdrDifference"):
            metrics[key + "ChangePercent"] = 100 * (after[key] / before[key] - 1) if before[key] else None
        result["regions"][name] = metrics
        print(name, json.dumps({k: v for k, v in metrics.items() if k not in ("control", "candidate")}))
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
