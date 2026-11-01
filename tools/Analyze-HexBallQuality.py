"""Summarize captured static-frame stability and a fixed geometric silhouette.

This does not measure FPS or declare all motion/lighting detail blur-free.
Usage: python tools/Analyze-HexBallQuality.py <raw-directory> <relax-directory> <output-prefix>
"""
import json
import statistics
import sys
from pathlib import Path
from PIL import Image, ImageChops, ImageDraw, ImageStat

roi = (780, 180, 1780, 1260)
directories = [Path(p) for p in sys.argv[1:3]]
prefix = Path(sys.argv[3])
results = {}
canvas = Image.new("RGB", (1280, 740), "#20242a")
draw = ImageDraw.Draw(canvas)
for column, directory in enumerate(directories):
    images = [Image.open(directory / f"static-{f}.png").convert("RGB") for f in range(160, 164)]
    if any(im.size != (2560, 1440) for im in images):
        raise ValueError("Expected unchanged 2560x1440 captures")
    differences = [ImageStat.Stat(ImageChops.difference(a, b).crop(roi)) for a, b in zip(images, images[1:])]
    mae = statistics.mean(statistics.mean(s.mean) for s in differences)
    rms = statistics.mean((sum(x*x for x in s.rms)/3)**.5 for s in differences)
    gray = images[-1].convert("L")
    widths = []
    # Twenty horizontal scans across the static upper-left outer hexagon edge.
    for y in range(250, 390, 7):
        center = round(1280-(y-140)*1.72)
        line = [gray.getpixel((x, y)) for x in range(center-15, center+16)]
        low, high = statistics.mean(line[:4]), statistics.mean(line[-4:])
        if high-low < 25:
            continue
        crossings = []
        for fraction in (.1, .9):
            threshold = low+fraction*(high-low)
            crossings.append(next((i-1+(threshold-line[i-1])/(line[i]-line[i-1])
                for i in range(1, len(line)) if line[i] >= threshold > line[i-1]), None))
        if all(x is not None for x in crossings):
            widths.append(crossings[1]-crossings[0])
    results[directory.name] = {"source": str(directory.resolve()), "roi": roi, "frame_pairs": 3,
        "static_consecutive_rgb_mae_8bit": mae, "static_consecutive_rgb_rms_8bit": rms,
        "outer_silhouette_10_90_pixels": statistics.median(widths), "edge_scan_rows": len(widths)}
    draw.text((column*640+12, 10), "RAW SR" if column == 0 else "NATIVE RELAX + SR", fill="white")
    crop = images[-1].crop((780, 220, 1780, 1220)).resize((640, 640), Image.Resampling.LANCZOS)
    canvas.paste(crop, (column*640, 32))
    draw.text((column*640+12, 688), f"Static temporal RGB MAE: {mae:.3f}/255", fill="white")
prefix.parent.mkdir(parents=True, exist_ok=True)
prefix.with_suffix(".json").write_text(json.dumps(results, indent=2)+"\n", encoding="utf-8")
canvas.save(prefix.with_suffix(".png"))
print(json.dumps(results, indent=2))
