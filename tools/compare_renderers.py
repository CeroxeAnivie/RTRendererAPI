"""Independent renderers: prepare the exported LuxCore fixture, then benchmark Mitsuba.

This is a comparison tool, never part of the renderer bridge. Unsupported semantic
matches are recorded explicitly instead of claiming full API equivalence.
"""
import argparse
import hashlib
import json
import math
from pathlib import Path
import shlex
import struct
import subprocess
import time


def gpu():
    return subprocess.check_output([
        'nvidia-smi', '--query-gpu=timestamp,name,utilization.gpu,power.draw,clocks.sm',
        '--format=csv,noheader'], text=True).strip()


def numbers(value):
    if isinstance(value, (float, int)):
        return [float(value)]
    return [float(x) for x in value.split()]


def read_ply(path):
    types = {'float': 'f', 'double': 'd', 'uchar': 'B', 'uint': 'I', 'int': 'i'}
    with path.open('rb') as f:
        header = []
        while True:
            line = f.readline().decode('ascii').strip()
            header.append(line)
            if line == 'end_header':
                break
        assert 'format binary_little_endian 1.0' in header
        properties, count, faces = [], 0, 0
        in_vertex = False
        for line in header:
            p = line.split()
            if p[:2] == ['element', 'vertex']:
                count, in_vertex = int(p[2]), True
            elif p[:2] == ['element', 'face']:
                faces, in_vertex = int(p[2]), False
            elif p[0] == 'property' and in_vertex:
                properties.append((p[2], types[p[1]]))
        layout = struct.Struct('<' + ''.join(t for _, t in properties))
        vertices = [dict(zip([n for n, _ in properties], layout.unpack(f.read(layout.size)))) for _ in range(count)]
        triangles = []
        for _ in range(faces):
            n = struct.unpack('<B', f.read(1))[0]
            face = struct.unpack('<'+'I'*n, f.read(n*4))
            assert n == 3
            triangles.append(list(face))
    return {'vertices': vertices, 'triangles': triangles}


def prepare(source, output):
    import mitsuba as mi
    import numpy as np
    groups = {k: {} for k in ['camera', 'materials', 'volumes', 'textures', 'objects', 'lights']}
    for line in (source/'scene.scn').read_text(encoding='utf-8').splitlines():
        if ' = ' not in line:
            continue
        key, value = line.split(' = ', 1)
        value = ' '.join(shlex.split(value))
        prefix = key.split('.')
        if len(prefix) < 3 or prefix[1] not in groups:
            continue
        if prefix[1] == 'camera':
            groups['camera']['.'.join(prefix[2:])] = value
        else:
            groups[prefix[1]].setdefault(prefix[2], {})['.'.join(prefix[3:])] = value
    groups['images'] = {}
    groups['meshes'] = {}
    for path in sorted(source.glob('*.exr')):
        groups['images'][path.name] = np.array(mi.Bitmap(str(path)), copy=True).tolist()
    for path in sorted(source.glob('*.ply')):
        groups['meshes'][path.name] = read_ply(path)
    groups['source'] = str(source.resolve())
    groups['sceneSha256'] = hashlib.sha256((source/'scene.scn').read_bytes()).hexdigest()
    groups['triangleCount'] = sum(len(groups['meshes'][v['ply']]['triangles']) for v in groups['objects'].values())
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(groups, indent=2), encoding='utf-8')
    print('Prepared', len(groups['objects']), 'objects,', groups['triangleCount'], 'triangles:', output, flush=True)


def mitsuba_minimal(fixture, output, rounds, seconds):
    import mitsuba as mi
    import drjit as dr
    import numpy as np
    mi.set_variant('cuda_ad_rgb')
    source = Path(fixture['source'])
    scene = mi.load_dict({
        'type': 'scene',
        'integrator': {'type': 'path', 'max_depth': 5, 'rr_depth': 3},
        'sensor': {
            'type': 'perspective', 'fov': 30, 'fov_axis': 'y', 'near_clip': .001,
            'to_world': mi.ScalarTransform4f.look_at(origin=[0, 0, 7], target=[0, 0, 0], up=[0, 1, 0]),
            'sampler': {'type': 'independent', 'sample_count': 1},
            'film': {'type': 'hdrfilm', 'width': 1920, 'height': 1080, 'pixel_format': 'rgb',
                     'rfilter': {'type': 'box'}},
        },
        'sky': {'type': 'constant', 'radiance': {'type': 'rgb', 'value': [.12, .16, .22]}},
        'cube': {'type': 'ply', 'filename': str(source/'mesh-00000.ply'),
                 'to_world': mi.ScalarTransform4f.scale(.28),
                 'bsdf': {'type': 'diffuse', 'reflectance': {'type': 'rgb', 'value': [.5, .6, .7]}}},
    })
    initial = gpu()
    print('Mitsuba CUDA variant', mi.variant(), 'initialGpu', initial, flush=True)
    for seed in range(2):
        image = mi.render(scene, spp=1, seed=seed)
        dr.eval(image)
        dr.sync_thread()
    frames = []
    loop_start = time.perf_counter()
    seed = 2
    while len(frames) < rounds or time.perf_counter()-loop_start < seconds:
        start = time.perf_counter()
        image = mi.render(scene, spp=1, seed=seed)
        dr.eval(image)
        dr.sync_thread()
        elapsed = time.perf_counter()-start
        frames.append(elapsed*1000)
        if len(frames) <= 3 or len(frames) % 100 == 0:
            print('Mitsuba 1spp frame', seed-1, 'ms', round(elapsed*1000, 3), flush=True)
        seed += 1
    pixels = np.array(image, copy=True)
    assert np.isfinite(pixels).all() and pixels.max() > 0
    output.mkdir(parents=True, exist_ok=True)
    mi.util.write_bitmap(str(output/'minimal.png'), image)
    result = {'renderer': 'Mitsuba', 'version': mi.__version__, 'variant': mi.variant(),
              'device': initial.split(',')[1].strip(), 'scene': 'minimal-exact-geometry-and-diffuse',
              'width': 1920, 'height': 1080, 'spp': 1, 'pathDepth': 5,
              'filter': 'box', 'denoiser': False, 'initialGpu': initial,
              'metric': 'independent completed 1-SPP render calls, CPU+GPU after JIT warmup; no display upload',
              'frameMs': frames, 'fps': len(frames)*1000/sum(frames), 'meanMs': sum(frames)/len(frames),
              'comparisonScope': 'Same single cube mesh, transform, camera, diffuse reflectance and environment as LuxCore minimal. Does not test the mixed-medium fixture.'}
    (output/'minimal.json').write_text(json.dumps(result, indent=2), encoding='utf-8')
    print(json.dumps({k:v for k,v in result.items() if k!='frameMs'}), flush=True)


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--prepare', action='store_true')
    parser.add_argument('--source', type=Path, default=Path('docs/quality/data/luxcore-official-control-20261101/scene'))
    parser.add_argument('--fixture', type=Path, default=Path('build/reports/renderer-comparison/fixture.json'))
    parser.add_argument('--output', type=Path, default=Path('build/reports/renderer-comparison/mitsuba'))
    parser.add_argument('--rounds', type=int, default=20)
    parser.add_argument('--seconds', type=float, default=3)
    args = parser.parse_args()
    if args.prepare:
        prepare(args.source, args.fixture)
    else:
        mitsuba_minimal(json.loads(args.fixture.read_text(encoding='utf-8')), args.output, args.rounds, args.seconds)
