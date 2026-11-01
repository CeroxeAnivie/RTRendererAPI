"""Build a bounded, feature-rich game workload and its surface-only control.

This is a performance fixture, not production AAA artwork. Every original feature
cell remains present; no coefficients are lowered to improve a benchmark score.
"""
import argparse
import copy
import hashlib
import json
import math
from pathlib import Path
import re
import shutil
import struct

import numpy as np


def matrix(scale, x, y, z):
    if isinstance(scale, (float, int)):
        scale = [scale]*3
    return ' '.join(map(str, [scale[0],0,0,0,0,scale[1],0,0,0,0,scale[2],0,x,y,z,1]))


def write_ply(path, mesh):
    fields = ['x','y','z','s','t','red','green','blue','alpha']
    header = ('ply\nformat binary_little_endian 1.0\nelement vertex '+str(len(mesh['vertices']))+'\n'
              + ''.join('property float '+k+'\n' for k in fields)
              + 'element face '+str(len(mesh['triangles']))+'\nproperty list uchar uint vertex_indices\nend_header\n')
    with path.open('wb') as out:
        out.write(header.encode('ascii'))
        for v in mesh['vertices']:
            out.write(struct.pack('<9f', *(v.get(k, 0) for k in fields)))
        for t in mesh['triangles']:
            out.write(struct.pack('<B3I', 3, *t))


def save(f, destination):
    destination.mkdir(parents=True, exist_ok=True)
    source = Path(f['source'])
    for image in f['images']:
        shutil.copyfile(source/image, destination/image)
    for name, mesh in f['meshes'].items():
        write_ply(destination/name, mesh)
    f['source'] = str(destination.resolve())
    def value(v):
        try:
            [float(x) for x in str(v).split()]
            return str(v)
        except ValueError:
            return json.dumps(str(v))
    lines = []
    for group in ['camera','textures','volumes','materials','objects','lights']:
        for name, item in f[group].items():
            if group == 'camera':
                lines.append('scene.camera.'+name+' = '+value(item))
            else:
                for key, val in item.items():
                    lines.append('scene.'+group+'.'+name+'.'+key+' = '+value(val))
    text = '\n'.join(lines)+'\n'
    (destination/'scene.scn').write_text(text, encoding='utf-8')
    f['sceneSha256'] = hashlib.sha256(text.encode()).hexdigest()
    (destination/'fixture.json').write_text(json.dumps(f, indent=2), encoding='utf-8')
    cfg = '''renderengine.type = RTPATHOCL
opencl.native.threads.count = 0
opencl.cpu.use = 0
opencl.gpu.use = 1
film.width = 1920
film.height = 1080
rtpath.resolutionreduction = 1
rtpath.resolutionreduction.preview = 1
rtpath.resolutionreduction.preview.step = 1
sampler.type = TILEPATHSAMPLER
tilepath.sampling.aa.size = 1
path.pathdepth.total = 5
path.pathdepth.diffuse = 3
path.pathdepth.glossy = 3
path.pathdepth.specular = 5
film.filter.type = NONE
film.opencl.enable = 0
film.imagepipelines.0.0.type = TONEMAP_LINEAR
film.imagepipelines.0.0.scale = 1
film.imagepipelines.0.1.type = GAMMA_CORRECTION
film.outputs.0.type = RGBA_IMAGEPIPELINE
film.outputs.0.filename = unused.png
scene.file = scene.scn
'''
    (destination/'render.cfg').write_text(cfg, encoding='utf-8')


def prepare(original, output):
    f = json.loads(original.read_text(encoding='utf-8'))
    # Exported PLYs contain undefined attributes on meshes with no source colors.
    # Set the source API's white default explicitly; recover the known alpha mesh.
    alpha_meshes = {o['ply'] for name,o in f['objects'].items()
                   if name in ['o109p0','o122p0','o204p0','o217p0']}
    for name, mesh in f['meshes'].items():
        for i, v in enumerate(mesh['vertices']):
            v.update(red=1., green=1., blue=1., alpha=(128/255 if i in [0,2,5,7] else 1.)
                     if name in alpha_meshes else 1.)
    # A small industrial room: display/work benches on the sides, props and
    # local smoke/fire effects. Keep all 30 original feature combinations.
    for name, obj in f['objects'].items():
        if name == 'o500p0':
            continue  # The camera-inside test remains bounded to 0.004 m.
        old = list(map(float, obj['transformation'].split()))
        col = round(old[12]/.7+4.5)
        row = round(1-old[13]/.8)
        x = (col-4.5)*.59
        y = [1.05,.1,-.85][row]
        z = -.35 - .08*(col%3)
        # Preserve separation of the visible prop from its front local volume.
        z += old[14]
        obj['transformation'] = matrix([old[0],old[5],old[10]], x,y,z)
    solid_mesh = copy.deepcopy(f['meshes']['mesh-00000.ply'])
    f['meshes']['game-cube.ply'] = solid_mesh
    f['materials']['gameWall'] = {'type':'disney','basecolor':'.24 .29 .34','roughness':'.8','metallic':'0'}
    f['materials']['gameMetal'] = {'type':'disney','basecolor':'.22 .27 .31','roughness':'.32','metallic':'.7'}
    f['materials']['gameCrate'] = {'type':'disney','basecolor':'.36 .19 .08','roughness':'.72','metallic':'0'}
    def solid(name, scale, pos, material):
        name = re.sub(r'[^a-zA-Z0-9_-]', '_', name)
        f['objects'][name] = {'ply':'game-cube.ply','material':material,
                              'transformation':matrix(scale,*pos)}
    solid('floor', [7,.12,5], [0,-1.43,-.65], 'gameWall')
    solid('backWall', [7,3.4,.12], [0,.1,-1.5], 'gameWall')
    for y in [-1.08,-.13,.82]:
        solid('bench'+str(y), [6.2,.07,.55], [0,y,-.65], 'gameMetal')
    for side in [-1,1]:
        for y in [-1.25,1.35]:
            solid('rail'+str((side,y)), [.08,.08,.8], [side*3.18,y,-.6], 'gameMetal')
        for i in range(6):
            solid('rib'+str((side,i)), [.09,2.8,.12], [side*(3.15-i*.035),0,-1.15-i*.025], 'gameMetal')
    for i in range(36):
        side = -1 if i%2 else 1
        solid('crate'+str(i), [.10+.025*(i%3),.12,.12],
              [side*(2.75-.22*(i%4)), -1.28+.15*((i//4)%2), -.85-.15*(i//8)], 'gameCrate')
    f['triangleCount'] = sum(len(f['meshes'][o['ply']]['triangles']) for o in f['objects'].values())
    # Exact primary-ray/AABB intersection budget for bounded volume content.
    h,w = 1080,1920
    yy,xx = np.mgrid[0:h,0:w]
    fy = math.tan(math.radians(15))
    rays = np.stack(((2*(xx+.5)/w-1)*fy*w/h, (1-2*(yy+.5)/h)*fy, -np.ones_like(xx)), -1)
    origin = np.array([0,0,7.])
    counts = np.zeros((h,w), dtype=np.uint8)
    max_distance = np.zeros((h,w), dtype=np.float32)
    features = []
    for name,o in f['objects'].items():
        vol = f['materials'][o['material']].get('volume.interior')
        if not vol or name == 'o500p0':
            continue
        t = np.array(list(map(float,o['transformation'].split()))).reshape(4,4,order='F')
        lo,hi = t[:3,3]-abs(np.diag(t)[:3])*.5,t[:3,3]+abs(np.diag(t)[:3])*.5
        a,b=(lo-origin)/rays,(hi-origin)/rays
        near=np.maximum(np.minimum(a,b).max(-1),0)
        far=np.maximum(a,b).min(-1)
        hit=far>near
        counts += hit
        max_distance += np.where(hit,far-near,0)*np.linalg.norm(rays,axis=-1)
        features.append({'object':name,'medium':vol,'type':f['volumes'][vol]['type'],
                         'projectedPixels':int(hit.sum())})
    budget={'purpose':'Game performance feasibility; feature-rich, spatially bounded workload, not production AAA artwork.',
            'width':w,'height':h,'spp':1,'maxPathDepth':5,'objects':len(f['objects']),
            'triangles':f['triangleCount'],'boundedVolumesExcludingCamera':len(features),
            'volumeScreenCoveragePercent':100*float(np.mean(counts>0)),
            'maxPrimaryVolumeIntersections':int(counts.max()),
            'maxPrimaryDistanceInsideLocalVolumesM':float(max_distance.max()),
            'cameraMedium':'Clear absorption only; 0.004 m bounded cube, maximum primary exit distance <0.003 m.',
            'gridResolution':'4x4x4 original density grids; original coefficients retained',
            'coverageObjects':features,'minProjectedFeaturePixels':min(x['projectedPixels'] for x in features),
            'sourceFixtureSha256':hashlib.sha256(original.read_bytes()).hexdigest(),
            'corrections':['Explicitly initialized all vertex colors/alpha; no undefined PLY attributes.',
                           'Shared native sparse grid layers are allowed; one upstream layer per input object is not required.',
                           'No global scattering fog, no long glass tunnel, no repeated transparent shells across the screen.']}
    print('Measured workload budget:', {k:v for k,v in budget.items() if k!='coverageObjects'},flush=True)
    assert budget['volumeScreenCoveragePercent'] < 15
    assert budget['maxPrimaryVolumeIntersections'] <= 2
    assert budget['minProjectedFeaturePixels'] > 100
    save(copy.deepcopy(f), output/'medium-on')
    no_medium = copy.deepcopy(f)
    no_medium['camera'].pop('volume',None)
    no_medium['camera']['autovolume.enable']='0'
    for name,o in list(no_medium['objects'].items()):
        if no_medium['materials'][o['material']]['type']=='null':
            del no_medium['objects'][name]
    for m in no_medium['materials'].values():
        m.pop('volume.interior',None)
        m.pop('volume.exterior',None)
    no_medium['triangleCount'] = sum(len(no_medium['meshes'][o['ply']]['triangles']) for o in no_medium['objects'].values())
    save(no_medium, output/'medium-off')
    (output/'workload-budget.json').write_text(json.dumps(budget,indent=2),encoding='utf-8')
    print(json.dumps({k:v for k,v in budget.items() if k!='coverageObjects'},indent=2),flush=True)


if __name__ == '__main__':
    p=argparse.ArgumentParser()
    p.add_argument('--source',type=Path,default=Path('docs/quality/data/renderer-comparison-clean-20261101/fixture.json'))
    p.add_argument('--output',type=Path,default=Path('build/reports/game-renderer-comparison/scene'))
    a=p.parse_args();prepare(a.source,a.output)
