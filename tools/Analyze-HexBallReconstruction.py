"""Compare HexBall detail against a separate 32-SPP reference, not just noise suppression.

Usage: python tools/Analyze-HexBallReconstruction.py reference baseline candidate output_prefix
Captures must use the same deterministic HexBall material, camera and SR input size.
The measured edge is the right boundary of the static upper ball's cyan shadow.
This local regression does not certify every material or all motion artifacts.
"""
import json
import statistics
import sys
from pathlib import Path
from PIL import Image, ImageChops, ImageDraw, ImageStat

directories = [Path(arg) for arg in sys.argv[1:4]]
prefix = Path(sys.argv[4])
labels = [
    "REFERENCE 32 SPP (quality only)",
    sys.argv[5] if len(sys.argv) > 5 else "BASELINE: 1 SPP",
    sys.argv[6] if len(sys.argv) > 6 else "CANDIDATE: 1 SPP",
]
canvas = Image.new("RGB", (1500, 720), "#20242a")
draw = ImageDraw.Draw(canvas)
results = []
for column, (directory, label) in enumerate(zip(directories, labels)):
    images = [Image.open(directory / f"static-{frame}.png").convert("RGB") for frame in range(160, 164)]
    if any(im.size != (2560, 1440) for im in images):
        raise ValueError("Capture size changed")
    mean = images[0]
    for count, image in enumerate(images[1:], 2):
        mean = Image.blend(mean, image, 1 / count)
    widths, contrasts = [], []
    # Fix the scan domain from scene geometry; do not optimize it per candidate.
    for y in range(483, 532, 3):
        xs = list(range(1160, 1291))
        values = [ImageStat.Stat(mean.crop((x-1, y-2, x+2, y+3))).mean[0] for x in xs]
        low, high = statistics.mean(values[:16]), statistics.mean(values[-16:])
        contrasts.append(high - low)
        crossings = []
        for fraction in (0.1, 0.9):
            threshold = low + (high-low) * fraction
            positions = [xs[i-1] + (threshold-values[i-1])/(values[i]-values[i-1])
                for i in range(1, len(xs)) if values[i] >= threshold > values[i-1]]
            crossings.append(min(positions, key=lambda x: abs(x-1220)) if positions else None)
        if all(value is not None for value in crossings) and crossings[1] > crossings[0]:
            widths.append(crossings[1]-crossings[0])
    if len(widths) < 12:
        raise AssertionError(f"Shadow edge missing or washed out: {directory}")
    mae = statistics.mean(statistics.mean(ImageStat.Stat(ImageChops.difference(a, b)
        .crop((780, 180, 1780, 1260))).mean) for a, b in zip(images, images[1:]))
    result = {"source": str(directory.resolve()), "shadow_edge_10_90_output_pixels": statistics.median(widths),
        "shadow_red_channel_contrast_8bit": statistics.median(contrasts), "scan_rows": len(widths),
        "static_temporal_rgb_mae_8bit": mae}
    # Same deterministic poses; these errors include remaining reference noise and
    # estimator bias, so do not relabel them as isolated ghosting measurements.
    result["rgb_mae_vs_32spp_reference_8bit"] = {}
    for frame in ("static-163", "moving-119", "stopped-0", "stopped-3", "stopped-15", "stopped-79"):
        candidate_frame = Image.open(directory / f"{frame}.png").convert("RGB")
        reference_frame = Image.open(directories[0] / f"{frame}.png").convert("RGB")
        result["rgb_mae_vs_32spp_reference_8bit"][frame] = statistics.mean(ImageStat.Stat(
            ImageChops.difference(candidate_frame, reference_frame).crop((780, 180, 1780, 1260))).mean)
    results.append(result)
    draw.text((column*500+8, 6), label, fill="white")
    # Unscaled 1:1 output-pixel crops; sharpening/resizing would contaminate this comparison.
    canvas.paste(mean.crop((900, 325, 1350, 775)), (column*500, 30))
    canvas.paste(mean.crop((1320, 740, 1620, 940)), (column*500, 492))
    draw.text((column*500+8, 700), f"Shadow edge {result['shadow_edge_10_90_output_pixels']:.2f} px", fill="white")
candidate, reference = results[2], results[0]
passed = candidate["shadow_edge_10_90_output_pixels"] <= reference["shadow_edge_10_90_output_pixels"] * 2
passed &= candidate["shadow_red_channel_contrast_8bit"] >= reference["shadow_red_channel_contrast_8bit"] * .8
passed &= candidate["static_temporal_rgb_mae_8bit"] <= 1.0
report = {"scope": "Static HexBall cyan shadow detail; not a universal blur/ghosting certificate",
    "labels": labels, "results": results, "detail_regression": "PASS" if passed else "FAIL"}
prefix.parent.mkdir(parents=True, exist_ok=True)
prefix.with_suffix(".json").write_text(json.dumps(report, indent=2)+"\n", encoding="utf-8")
canvas.save(prefix.with_suffix(".png"))
print(json.dumps(report, indent=2))
if not passed:
    raise SystemExit("Shadow detail regression")
