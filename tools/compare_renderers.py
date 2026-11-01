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


def mitsuba_mixed_scene(f, mi, np):
    """Same geometry/coefficient workload; declared differences prevent false full-equivalence claims."""
    def rgb(v):return {'type':'rgb','value':list(v)}
    def texture(value):
        try:
            v=numbers(value)
            return np.broadcast_to(np.array(v if len(v)==3 else v*3,dtype=np.float32),(2,2,3)).copy()
        except ValueError:pass
        t=f['textures'][value];kind=t['type']
        if kind=='imagemap':
            a=np.asarray(f['images'][t['file']],dtype=np.float32)
            if value.endswith('alpha'):a=np.repeat(a[:,:,3:4],3,axis=2)
            else:a=a[:,:,:3]
            return np.broadcast_to(a,(2,2,3)).copy()
        if kind in ('hitpointcolor','hitpointalpha'):return np.ones((2,2,3),dtype=np.float32)
        if kind in ('scale','subtract','greaterthan'):
            a,b=texture(t['texture1']),texture(t['texture2'])
            return a*b if kind=='scale' else (a-b if kind=='subtract' else (a>b).astype(np.float32))
        if kind=='splitfloat3':
            a=texture(t['texture']);return np.repeat(a[:,:,int(t['channel']):int(t['channel'])+1],3,axis=2)
        if kind=='normalmap':return texture(t['texture'])
        raise ValueError('Unsupported Mitsuba comparison texture '+value)
    def bitmap(a,scalar=False):
        if scalar:a=a[:,:,0:1]
        return {'type':'bitmap','bitmap':mi.Bitmap(np.ascontiguousarray(a,dtype=np.float32)),'raw':True,'filter_type':'bilinear','wrap_mode':'repeat'}
    def surface(name):
        m=f['materials'][name];kind=m['type']
        if kind=='null':b={'type':'null'}
        elif kind=='disney':
            b={'type':'principled','base_color':bitmap(texture(m['basecolor'])),
               'roughness':float(texture(m['roughness'])[0,0,0]),'metallic':float(texture(m['metallic'])[0,0,0]),'specular':0.0}
        elif kind in ('glass','roughglass'):
            b={'type':'dielectric' if kind=='glass' else 'roughdielectric','int_ior':float(m.get('interiorior','1.5')),'ext_ior':1.0}
            if kind=='roughglass':b.update(distribution='ggx',alpha=float(m['uroughness']))
        elif kind=='mix':b={'type':'blendbsdf','weight':float(m['amount']),'first':surface(m['material1']),'second':surface(m['material2'])}
        else:raise ValueError('Unsupported Mitsuba comparison material '+kind)
        if 'transparency.front' in m:
            b={'type':'mask','opacity':bitmap(1-texture(m['transparency.front']),True),'bsdf':b}
        if 'bumptex' in m:
            b={'type':'normalmap','normalmap':bitmap(texture(m['bumptex'])),'bsdf':b}
        return b
    media={}
    def medium(name,transform):
        if name in media:return media[name]
        v=f['volumes'][name]
        def coefficient(key):
            value=v.get(key,'0 0 0')
            if value in f['textures']:value=f['textures'][value]['texture2']
            values=numbers(value)
            return np.asarray(values if len(values)==3 else values*3,dtype=np.float32)
        a,s=coefficient('absorption'),coefficient('scattering');ext=a+s
        albedo=np.divide(s,ext,out=np.zeros(3,dtype=np.float32),where=ext>0)
        p={'type':'heterogeneous' if v['type']=='heterogeneous' else 'homogeneous','albedo':rgb(albedo),
           'phase':{'type':'hg','g':float(v.get('asymmetry','0'))},'sample_emitters':True}
        if v['type']=='heterogeneous':
            grid=f['textures']['density'+name]
            data=np.asarray(numbers(grid['data']),dtype=np.float32).reshape(int(grid['nz']),int(grid['ny']),int(grid['nx']),1)*ext
            p['sigma_t']={'type':'gridvolume','grid':mi.VolumeGrid(data),'raw':True,
                          'to_world':transform@mi.ScalarTransform4f.translate([-.5,-.5,-.5])}
        else:p['sigma_t']=rgb(ext)
        result=mi.load_dict(p);media[name]=result;return result
    scene={'type':'scene','integrator':{'type':'volpath','max_depth':5,'rr_depth':3}}
    for name,o in f['objects'].items():
        transform=mi.ScalarTransform4f(np.asarray(numbers(o['transformation'])).reshape(4,4,order='F'))
        m=f['materials'][o['material']]
        shape={'type':'ply','filename':str(Path(f['source'])/o['ply']),'to_world':transform,'bsdf':surface(o['material'])}
        if 'volume.interior' in m:shape['interior']=medium(m['volume.interior'],transform)
        if 'emission' in m:shape['emitter']={'type':'area','radiance':rgb(numbers(m['emission']))}
        scene[name]=shape
    scene['sensor']={'type':'perspective','fov':30,'fov_axis':'y','near_clip':.001,
        'to_world':mi.ScalarTransform4f.look_at(origin=[0,0,7],target=[0,0,0],up=[0,1,0]),
        'sampler':{'type':'independent','sample_count':1},
        'film':{'type':'hdrfilm','width':1920,'height':1080,'pixel_format':'rgb','rfilter':{'type':'box'}},
        'medium':media['v500']}
    for name,l in f['lights'].items():
        color=np.asarray(numbers(l.get('color','1 1 1')))*np.asarray(numbers(l.get('gain','1 1 1')))
        if color.max()==0:continue
        kind=l['type']
        if kind=='constantinfinite':emitter={'type':'constant','radiance':rgb(color)}
        elif kind=='point':emitter={'type':'point','position':numbers(l['position']),'intensity':rgb(color)}
        elif kind=='spot':emitter={'type':'spot','intensity':rgb(color),'cutoff_angle':float(l['coneangle']),
            'beam_width':float(l['coneangle'])-float(l['conedeltaangle']),
            'to_world':mi.ScalarTransform4f.look_at(origin=numbers(l['position']),target=numbers(l['target']),up=[0,1,0])}
        elif kind=='sharpdistant':emitter={'type':'directional','direction':numbers(l['direction']),'irradiance':rgb(color)}
        else:raise ValueError('Unexpected emitter '+kind)
        scene['light_'+name]=emitter
    return mi.load_dict(scene)


def mitsuba_benchmark(fixture, output, rounds, seconds, case):
    import mitsuba as mi
    import drjit as dr
    import numpy as np
    mi.set_variant('cuda_ad_rgb')
    source = Path(fixture['source'])
    scene = mitsuba_mixed_scene(fixture, mi, np) if case=='mixed' else mi.load_dict({
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
    mi.util.write_bitmap(str(output/(case+'.png')), image)
    result = {'renderer': 'Mitsuba', 'version': mi.__version__, 'variant': mi.variant(),
              'device': initial.split(',')[1].strip(), 'scene': case,
              'width': 1920, 'height': 1080, 'spp': 1, 'pathDepth': 5,
              'filter': 'box', 'denoiser': False, 'initialGpu': initial,
              'metric': 'independent completed 1-SPP render calls, CPU+GPU after JIT warmup; no display upload',
              'frameMs': frames, 'fps': len(frames)*1000/sum(frames), 'meanMs': sum(frames)/len(frames),
              'comparisonScope': 'Matched minimal geometry/diffuse' if case=='minimal' else 'Same 756 triangles, transforms, 30 cells, homogeneous/grid coefficients and lights; common physical subset, NOT full API equivalence.',
              'semanticDifferences': [] if case=='minimal' else ['No volumetric emission in stock Mitsuba volpath; surface emission retained.',
                  'No LuxCore priority or per-volume single/multiple switch; standard nested medium tracking and multiple scattering.',
                  'Vertex color/alpha factors are unity in baked material textures; material and image alpha retained.',
                  'Native BSDF, density grid interpolation and sampling differ.'],
              'triangleCount':12 if case=='minimal' else fixture['triangleCount']}
    (output/(case+'.json')).write_text(json.dumps(result, indent=2), encoding='utf-8')
    print(json.dumps({k:v for k,v in result.items() if k!='frameMs'}), flush=True)


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--prepare', action='store_true')
    parser.add_argument('--source', type=Path, default=Path('docs/quality/data/luxcore-official-control-20261101/scene'))
    parser.add_argument('--fixture', type=Path, default=Path('build/reports/renderer-comparison/fixture.json'))
    parser.add_argument('--output', type=Path, default=Path('build/reports/renderer-comparison/mitsuba'))
    parser.add_argument('--rounds', type=int, default=20)
    parser.add_argument('--seconds', type=float, default=3)
    parser.add_argument('--case', choices=['minimal','mixed','both'], default='both')
    args = parser.parse_args()
    if args.prepare:
        prepare(args.source, args.fixture)
    else:
        for case in (['minimal','mixed'] if args.case=='both' else [args.case]):
            mitsuba_benchmark(json.loads(args.fixture.read_text(encoding='utf-8')), args.output, args.rounds, args.seconds, case)
