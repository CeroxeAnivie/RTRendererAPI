"""Run the official RTXPT application, including native presentation, FG off.

Only asset conversion and process/ETW measurement live here. Unsupported volume
scattering remains unsupported; no renderer or transport shader is changed.
"""
import argparse
import hashlib
import json
import math
from pathlib import Path
import struct
import subprocess
import time

import numpy as np
from PIL import Image

ROOT=Path(__file__).resolve().parent.parent
DIFFERENCES=[
    'RTXPT sample bridge sets sigmaS=0: no volume scattering, HG or multiple scattering.',
    'Heterogeneous density and volume emission are unsupported and are omitted, not replaced with custom shaders.',
    'Camera-inside tiny absorbing boundary remains geometry; automatic initial volume state is not guaranteed.',
    'Native standard BSDF and mixed transmission differ from original LuxCore BSDF mixtures.',
    'Native alpha testing is used for source transparency; continuous alpha and vertex factors are not transport-equivalent.',
    'Full original geometry, object transforms, lights and constant environment are retained.',
]


def numbers(x):
    return [float(v) for v in str(x).split()]


def texture(f,value):
    try:
        a=np.array(numbers(value),dtype=np.float32)
        return np.broadcast_to(np.resize(a,3),(2,2,3)).copy()
    except ValueError:
        pass
    t=f['textures'][value];kind=t['type']
    if kind=='imagemap':
        a=np.array(f['images'][t['file']],dtype=np.float32)
        if value.endswith('alpha'):
            return np.repeat(a[:,:,3:4],3,axis=2)
        return a[:,:,:3]
    if kind in ['hitpointcolor','hitpointalpha']:
        return np.ones((2,2,3),dtype=np.float32)
    if kind in ['scale','subtract','greaterthan']:
        a,b=texture(f,t['texture1']),texture(f,t['texture2'])
        return a*b if kind=='scale' else a-b if kind=='subtract' else (a>b).astype(np.float32)
    if kind=='splitfloat3':
        a=texture(f,t['texture']);return np.repeat(a[:,:,int(t['channel']):int(t['channel'])+1],3,axis=2)
    if kind=='normalmap':
        return texture(f,t['texture'])
    raise ValueError('Unsupported surface texture '+value)


def prepare(fixture,install,case):
    f=json.loads(fixture.read_text(encoding='utf-8'))
    assets=install/'Assets';model=assets/'Models'/('rtr-game-'+case);model.mkdir(parents=True,exist_ok=True)
    scene_name='rtr-game-'+case+'.scene.json'
    sidecars=assets/'Materials'/Path(scene_name).stem;sidecars.mkdir(parents=True,exist_ok=True)
    g={'asset':{'version':'2.0','generator':'RTRenderer native comparison asset conversion'},
       'scene':0,'scenes':[{'nodes':[]}],'nodes':[],'meshes':[],'materials':[],
       'buffers':[],'bufferViews':[],'accessors':[],'images':[],'textures':[],
       'extensionsUsed':['KHR_lights_punctual','KHR_materials_transmission','KHR_materials_ior'],
       'extensions':{'KHR_lights_punctual':{'lights':[]}}}
    blob=bytearray()
    def accessor(a,kind):
        a=np.ascontiguousarray(a,dtype='<f4');offset=len(blob);blob.extend(a.tobytes())
        view=len(g['bufferViews']);g['bufferViews'].append({'buffer':0,'byteOffset':offset,'byteLength':a.nbytes})
        result={'bufferView':view,'componentType':5126,'count':len(a),'type':kind}
        if kind=='VEC3':result.update(min=a.min(axis=0).tolist(),max=a.max(axis=0).tolist())
        index=len(g['accessors']);g['accessors'].append(result);return index
    def image(name,a,srgb=False):
        a=np.clip(a,0,1)
        if srgb:a=np.where(a<=.0031308,a*12.92,1.055*a**(1/2.4)-.055)
        filename=name+'.png';Image.fromarray(np.uint8(np.clip(a,0,1)*255+.5)).save(model/filename)
        g['images'].append({'uri':filename});g['textures'].append({'source':len(g['images'])-1})
        return len(g['textures'])-1
    def material(name):
        src=f['materials'][name];m=src
        trans=1. if m['type'] in ['null','glass','roughglass'] else 0.
        if m['type']=='mix':
            trans=float(m['amount']);m=f['materials'][m['material1']]
        base=texture(f,m.get('basecolor','1 1 1'))
        opacity=1-texture(f,src['transparency.front'])[:,:,0] if 'transparency.front' in src else np.ones(base.shape[:2])
        rgba=np.concatenate((base,opacity[:,:,None]),axis=-1)
        # Base texture RGB is sRGB-encoded; alpha must remain linear.
        rgb=np.where(base<=.0031308,base*12.92,1.055*np.maximum(base,0)**(1/2.4)-.055)
        base_tex=image(name+'-base',np.concatenate((rgb,opacity[:,:,None]),axis=-1))
        rough=float(texture(f,m.get('roughness',m.get('uroughness','0')))[:,:,0].mean())
        metal=float(texture(f,m.get('metallic','0'))[:,:,0].mean())
        pt={'version':1,'BaseOrDiffuseColor':[1,1,1],'Roughness':rough,'Metalness':metal,
            'Opacity':1.,'TransmissionFactor':trans,'EnableTransmission':trans>0,
            'IoR':float(src.get('interiorior','1' if src['type']=='null' else '1.5')),
            'ThinSurface':False,'EnableAlphaTesting':bool(np.min(opacity)<.999),'AlphaCutoff':.5,
            'BaseTexture':{'path':str((model/(name+'-base.png')).relative_to(assets)).replace('\\','/'),'sRGB':True,'NormalMap':False},
            'EmissiveColor':texture(f,src.get('emission','0 0 0')).mean(axis=(0,1)).tolist(),
            'EmissiveIntensity':1,'VolumeAttenuationColor':[1,1,1],'VolumeAttenuationDistance':1.}
        if 'volume.interior' in src:
            v=f['volumes'][src['volume.interior']]
            if v['type']!='heterogeneous':
                pt['VolumeAttenuationColor']=np.exp(-np.array(numbers(v.get('absorption','0 0 0')))).tolist()
            pt['NestedPriority']=int(v.get('priority','1'))
        gm={'name':name,'doubleSided':True,'pbrMetallicRoughness':{'baseColorTexture':{'index':base_tex},
             'metallicFactor':metal,'roughnessFactor':rough},'emissiveFactor':pt['EmissiveColor'],
             'extensions':{'KHR_materials_transmission':{'transmissionFactor':trans},'KHR_materials_ior':{'ior':max(pt['IoR'],1.)}}}
        if pt['EnableAlphaTesting']:gm.update(alphaMode='MASK',alphaCutoff=.5)
        if 'bumptex' in m:
            normal=image(name+'-normal',texture(f,m['bumptex']))
            gm['normalTexture']={'index':normal}
            pt['NormalTexture']={'path':str((model/(name+'-normal.png')).relative_to(assets)).replace('\\','/'),'sRGB':False,'NormalMap':True}
        (sidecars/(name+'.material.json')).write_text(json.dumps(pt,indent=2),encoding='utf-8')
        index=len(g['materials']);g['materials'].append(gm);return index
    material_ids={name:material(name) for name in sorted({o['material'] for o in f['objects'].values()})}
    for name,o in f['objects'].items():
        mesh=f['meshes'][o['ply']];pos=[];normal=[];uv=[];color=[]
        for tri in mesh['triangles']:
            vs=[mesh['vertices'][i] for i in tri]
            ps=np.array([[v['x'],v['y'],v['z']] for v in vs],dtype=np.float32)
            n=np.cross(ps[1]-ps[0],ps[2]-ps[0]);n/=max(float(np.linalg.norm(n)),1e-12)
            for v,p in zip(vs,ps):
                pos.append(p);normal.append(n);uv.append([v.get('s',0),v.get('t',0)])
                color.append([v.get(k,1) for k in ['red','green','blue','alpha']])
        primitive={'attributes':{'POSITION':accessor(pos,'VEC3'),'NORMAL':accessor(normal,'VEC3'),
                    'TEXCOORD_0':accessor(uv,'VEC2'),'COLOR_0':accessor(color,'VEC4')},'material':material_ids[o['material']]}
        g['meshes'].append({'name':name,'primitives':[primitive]})
        g['scenes'][0]['nodes'].append(len(g['nodes']))
        g['nodes'].append({'name':name,'mesh':len(g['meshes'])-1,'matrix':numbers(o['transformation'])})
    for name,l in f['lights'].items():
        gain=np.array(numbers(l.get('gain','1 1 1')))
        if gain.max()==0 or l['type']=='constantinfinite':continue
        light={'type':{'point':'point','spot':'spot','sharpdistant':'directional'}[l['type']],
               'color':numbers(l.get('color','1 1 1')),'intensity':float(gain.mean())}
        node={'name':name,'translation':numbers(l.get('position','0 0 0'))}
        if l['type'] in ['spot','sharpdistant']:
            direction=np.array(numbers(l['direction'])) if l['type']=='sharpdistant' else np.array(numbers(l['target']))-node['translation']
            direction/=np.linalg.norm(direction);z=np.array([0,0,-1.]);cross=np.cross(z,direction);dot=np.dot(z,direction)
            q=np.array([*cross,1+dot]);q/=np.linalg.norm(q);node['rotation']=q.tolist()
        if l['type']=='spot':light['spot']={'outerConeAngle':math.radians(float(l['coneangle'])),'innerConeAngle':math.radians(float(l['coneangle'])-float(l['conedeltaangle']))}
        lights=g['extensions']['KHR_lights_punctual']['lights'];lights.append(light)
        node['extensions']={'KHR_lights_punctual':{'light':len(lights)-1}}
        g['scenes'][0]['nodes'].append(len(g['nodes']));g['nodes'].append(node)
    (model/'game.bin').write_bytes(blob);g['buffers']=[{'byteLength':len(blob),'uri':'game.bin'}]
    (model/'game.gltf').write_text(json.dumps(g),encoding='utf-8')
    environment=model/'constant.hdr'
    environment.write_bytes(b'#?RADIANCE\nFORMAT=32-bit_rle_rgbe\n\n-Y 4 +X 4\n'+bytes([123,164,225,126])*16)
    scene={'models':[str((model/'game.gltf').relative_to(assets)).replace('\\','/')],
           'graph':[{'name':'Game','model':0},{'name':'Environment','type':'EnvironmentLight','intensity':1.,
                     'path':str(environment.relative_to(assets)).replace('\\','/')},
                    {'name':'Camera','type':'PerspectiveCameraEx','translation':[0,0,7],'rotation':[0,0,0,1],
                     'verticalFov':math.radians(30),'zNear':.001,'enableAutoExposure':False,'exposureCompensation':0,'exposureValue':0},
                    {'name':'SampleSettings','type':'SampleSettings','realtimeMode':True,'enableAnimations':False,
                     'startingCamera':0,'maxBounces':5,'maxDiffuseBounces':3}]}
    (assets/scene_name).write_text(json.dumps(scene,indent=2),encoding='utf-8')
    return {'scene':scene_name,'objects':len(f['objects']),'triangles':f['triangleCount'],
            'fixtureSha256':hashlib.sha256(fixture.read_bytes()).hexdigest(),'semanticDifferences':DIFFERENCES}


def main():
    p=argparse.ArgumentParser()
    p.add_argument('--fixture',type=Path,default=ROOT/'build/reports/game-renderer-comparison/scene/medium-on/fixture.json')
    p.add_argument('--install',type=Path,default=ROOT/'build/rtxpt-binary/rtxpt_v.1.9.0_binaries')
    p.add_argument('--output',type=Path,default=ROOT/'build/reports/native-recommended/rtxpt')
    p.add_argument('--case',default='medium-on');p.add_argument('--prepare-only',action='store_true')
    a=p.parse_args();out=a.output.resolve();out.mkdir(parents=True,exist_ok=True)
    metadata=prepare(a.fixture.resolve(),a.install.resolve(),a.case)
    (out/'fixture.json').write_text(json.dumps(metadata,indent=2),encoding='utf-8')
    print('RTXPT prepared:',metadata,flush=True)
    if a.prepare_only:return
    executable=a.install.resolve()/'bin/Rtxpt.exe'
    perf=out/'passes.csv';perf.unlink(missing_ok=True)
    args=[str(executable),'--scene',metadata['scene'],'--width','1920','--height','1080',
          '--adapter','NVIDIA','--nonInteractive','--overrideToRealtimeMode',
          '--realtimeAA','3','--overrideDLSSMode','2','--realtimeSamplesPerPixel','1','--perfLog',str(perf)]
    (out/'command.json').write_text(json.dumps(args,indent=2),encoding='utf-8')
    time.sleep(1.2)
    initial=subprocess.check_output(['nvidia-smi','-i','0','--query-gpu=timestamp,name,utilization.gpu,power.draw,clocks.sm','--format=csv,noheader'],text=True).strip()
    print('RTXPT start:',initial,flush=True)
    with (out/'application.log').open('w',encoding='utf-8') as log:
        process=subprocess.Popen(args,cwd=executable.parent,stdout=log,stderr=subprocess.STDOUT)
        try:
            start=time.monotonic();lines=0
            while time.monotonic()-start<180:
                if process.poll() is not None:raise RuntimeError('RTXPT exited; see '+str(out/'application.log'))
                if perf.exists():
                    lines=len(perf.read_text(encoding='utf-8',errors='replace').splitlines())
                    if lines>=120:break
                time.sleep(.5)
            if lines<120:raise RuntimeError('RTXPT did not complete shader warmup; see application.log')
            print('RTXPT warmup complete, native presenting PID',process.pid,flush=True)
            present=out/'present.csv'
            subprocess.run([str(ROOT/'build/downloads/PresentMon-2.6.0-x64.exe'),'--process_id',str(process.pid),
                            '--output_file',str(present),'--timed','8','--terminate_after_timed','--no_console_stats','--no_track_input','--v2_metrics'],check=True,timeout=30)
            import csv
            rows=list(csv.DictReader(present.open(encoding='utf-8-sig')))
            assert len(rows)>10, 'No native completed/presented frames'
            intervals=[float(r['CPUFrameTime']) for r in rows[2:] if r.get('CPUFrameTime') and float(r['CPUFrameTime'])>0]
            result={'renderer':'Official RTXPT 1.9.0','width':1920,'height':1080,'fg':False,
                    'mode':'Native realtime, DLSS-RR Balanced, default upstream light sampling/cache/filter settings',
                    'internalResolution':'DLSS Balanced; verify actual dimensions in application evidence',
                    'fps':len(intervals)*1000/sum(intervals),'meanMs':sum(intervals)/len(intervals),
                    'metric':'Native application frame cadence from ETW PresentMon; full renderer, reconstruction, postprocess and presentation',
                    'frames':len(intervals),'initialGpu':initial,**metadata}
            (out/'result.json').write_text(json.dumps(result,indent=2),encoding='utf-8')
            print('RTXPT RESULT',json.dumps(result),flush=True)
        finally:
            process.terminate()
            try:process.wait(timeout=10)
            except subprocess.TimeoutExpired:process.kill();process.wait()


if __name__=='__main__':main()
