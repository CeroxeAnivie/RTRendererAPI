"""同一制品通过内部开关做完整光学帧 ABBA 对照，并核对所有 HDR 哈希。"""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--launch', type=Path, required=True)
parser.add_argument('--output', type=Path, required=True)
parser.add_argument('--property', required=True)
parser.add_argument('--warmup', type=int, default=64)
parser.add_argument('--frames', type=int, default=256)
args = parser.parse_args()
launch = json.loads(args.launch.read_text(encoding='utf-8'))
output = args.output.resolve()
output.mkdir(parents=True, exist_ok=True)
runs = []
for label, enabled in [('A1', True), ('B1', False), ('B2', False), ('A2', True)]:
    directory = output / label
    directory.mkdir(exist_ok=True)
    command = [launch['executable'], *launch['jvmArgs'], '-D' + args.property + '=' + str(enabled).lower(),
               '-Dtop.ceroxe.rt.medium.opticalFrameProfile=false', '-Dtop.ceroxe.rt.medium.opticalKernelPhaseProfile=false',
               '-Dtop.ceroxe.rt.medium.opticalBenchmarkWarmup=' + str(args.warmup),
               '-Dtop.ceroxe.rt.medium.opticalBenchmarkFrames=' + str(args.frames),
               '-Dtop.ceroxe.rt.medium.opticalProductionDirectory=' + str(directory),
               '-cp', launch['classpath'], launch['mainClass'], '--benchmark']
    with (directory / 'run.log').open('w', encoding='utf-8') as log:
        result = subprocess.run(command, stdout=log, stderr=subprocess.STDOUT)
    if result.returncode:
        print((directory / 'run.log').read_text(encoding='utf-8'), flush=True)
        raise SystemExit(result.returncode)
    measurements = json.loads((directory / 'benchmark.json').read_text(encoding='utf-8'))
    digest = hashlib.sha256((directory / 'benchmark-final.rgba16f').read_bytes()).hexdigest()
    runs.append(dict(label=label, enabled=enabled, measurements=measurements, hdrSha256=digest))
    print(label, 'enabled', enabled, 'meanMs', measurements['batchWallMs'] / args.frames,
          'fps', measurements['completedFramesPerSecond'], 'p95', measurements['p95EndToEndMs'], 'HDR', digest, flush=True)

def average(enabled):
    selected = [run['measurements'] for run in runs if run['enabled'] == enabled]
    return sum(item['batchWallMs'] for item in selected) / sum(item['completedFrames'] for item in selected)

before, after = average(False), average(True)
result = dict(method='ABBA; A=enabled, B=disabled; same artifact; full immutable CPU HDR completion; profiling disabled',
              property=args.property, warmupFrames=args.warmup, timedFramesPerRun=args.frames,
              beforeMeanMs=before, afterMeanMs=after, beforeFps=1000 / before, afterFps=1000 / after,
              fpsGainPercent=(before / after - 1) * 100, frameTimeReductionPercent=(1 - after / before) * 100,
              allFinalImageHashesEqual=len({run['hdrSha256'] for run in runs}) == 1, runs=runs)
for entry in launch['classpath'].split(';'):
    path = Path(entry)
    if path.name == 'renderer-core-7.0.0.jar':
        result['artifactSha256'] = hashlib.sha256(path.read_bytes()).hexdigest()
(output / 'summary.json').write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
assert result['allFinalImageHashesEqual'], 'optimized path changed final HDR bytes'
print(json.dumps({key: value for key, value in result.items() if key != 'runs'}, ensure_ascii=False, indent=2), flush=True)
